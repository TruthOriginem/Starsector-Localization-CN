package com.truthoriginem.starsector.preprocessing.patches;

import com.truthoriginem.starsector.preprocessing.AsmUtil;
import com.truthoriginem.starsector.preprocessing.JarPatch;
import com.truthoriginem.starsector.preprocessing.JarWorkspace;
import com.truthoriginem.starsector.preprocessing.PatchContext;
import com.truthoriginem.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

public final class CampaignDateWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/Oo0o.class";
    @Override
    public String id() {
        return "campaign-date-width";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        int applied = 0;
        int verified = 0;
        for (MethodNode method : classNode.methods) {
            applied += replaceDaySuffix(method);
            applied += replaceWidthBeforeSetSize(method, 60.0f, 100.0f);
            applied += replaceWidthBeforeSetSize(method, 38.0f, 50.0f);
            applied += replaceWidthBeforeSetSize(method, 35.0f, 50.0f);
            applied += replaceWidthBeforeSetSize(method, 135.0f, 150.0f);

            verified += countDaySuffix(method);
            verified += countWidthBeforeSetSize(method, 100.0f, 1);
            verified += countWidthBeforeSetSize(method, 50.0f, 2);
            verified += countWidthBeforeSetSize(method, 150.0f, 1);
        }
        return PatchResult.of(id(), context.classPath(), 6, applied, Math.min(6, verified),
                "campaign date day suffix and label widths");
    }

    private static int replaceDaySuffix(MethodNode method) {
        if (!callsGetDay(method)) {
            return 0;
        }
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (AsmUtil.isStringLdc(node, ",")) {
                ((LdcInsnNode) node).cst = "日,";
                count++;
            }
        }
        return count;
    }

    private static int countDaySuffix(MethodNode method) {
        if (!callsGetDay(method)) {
            return 0;
        }
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (AsmUtil.isStringLdc(node, "日,")) {
                count++;
            }
        }
        return count;
    }

    private static boolean callsGetDay(MethodNode method) {
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && "getDay".equals(call.name)
                    && "()I".equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static int replaceWidthBeforeSetSize(MethodNode method, float from, float to) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (AsmUtil.isFloatLdc(nodes.get(i), from) && hasSetSizeCallSoon(nodes, i)) {
                ((LdcInsnNode) nodes.get(i)).cst = to;
                count++;
            }
        }
        return count;
    }

    private static int countWidthBeforeSetSize(MethodNode method, float value, int max) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (AsmUtil.isFloatLdc(nodes.get(i), value) && hasSetSizeCallSoon(nodes, i)) {
                count++;
            }
        }
        return Math.min(count, max);
    }

    private static boolean hasSetSizeCallSoon(List<AbstractInsnNode> nodes, int start) {
        int limit = Math.min(nodes.size(), start + 6);
        for (int i = start + 1; i < limit; i++) {
            if (nodes.get(i) instanceof MethodInsnNode call
                    && "setSize".equals(call.name)
                    && "(FF)Lcom/fs/starfarer/ui/OO0O;".equals(call.desc)) {
                return true;
            }
        }
        return false;
    }
}

package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

/**
 * Widen the fixed value columns in the combat target reticle.
 *
 * <p>The original victor10 metrics made every character in {@code "%4d su/s"}
 * effectively six pixels wide, so the string always fitted the hard-coded 58 px
 * column. Dynamic victor10 uses narrower spaces and wider tabular digits; three
 * digits therefore need 62 px and wrap {@code su/s} onto a second line.</p>
 */
public final class CombatTargetInfoWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/renderers/A/null.class";
    private static final float ORIGINAL_WIDTH = 58.0f;
    private static final float PATCHED_WIDTH = 80.0f;

    @Override
    public String id() {
        return "combat-target-info-width";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.LOCALIZATION;
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
        int anchoredMethods = 0;

        for (MethodNode method : classNode.methods) {
            if (!isTargetInfoLayoutMethod(method)) {
                continue;
            }
            anchoredMethods++;
            applied += replaceWidthBeforeSetSize(method, ORIGINAL_WIDTH, PATCHED_WIDTH);
            verified += countWidthBeforeSetSize(method, PATCHED_WIDTH);
        }

        String detail = "anchoredMethods=" + anchoredMethods
                + ", target value columns " + ORIGINAL_WIDTH + " -> " + PATCHED_WIDTH;
        return PatchResult.of(id(), context.classPath(), 2, applied, verified, detail);
    }

    private static boolean isTargetInfoLayoutMethod(MethodNode method) {
        return containsString(method, "RANGE")
                && containsString(method, "SPEED")
                && containsString(method, "----m")
                && containsString(method, "----m/s");
    }

    private static boolean containsString(MethodNode method, String value) {
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (AsmUtil.isStringLdc(node, value)) {
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

    private static int countWidthBeforeSetSize(MethodNode method, float value) {
        List<AbstractInsnNode> nodes = AsmUtil.instructions(method);
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (AsmUtil.isFloatLdc(nodes.get(i), value) && hasSetSizeCallSoon(nodes, i)) {
                count++;
            }
        }
        return count;
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

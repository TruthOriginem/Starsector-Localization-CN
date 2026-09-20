package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.fossic.starsector.preprocessing.PatchException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

public final class CampaignDateWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/ui/Oo0o.class";
    private static final String LABEL = "com/fs/starfarer/ui/d";
    private static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);
    private static final Set<String> VALUE_FIELDS = Set.of("OOOo00", "do.this$do", "ø0Oo00");
    @Override
    public String id() {
        return "campaign-date-width";
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
        int noWrap = disableValueWrapping(classNode);
        int applied = noWrap;
        int verified = 0;
        for (MethodNode method : classNode.methods) {
            applied += replaceDaySuffix(method);
            applied += replaceWidthBeforeSetSize(method, 60.0f, 100.0f);
            applied += replaceWidthBeforeSetSize(method, 38.0f, 55.0f);
            applied += replaceWidthBeforeSetSize(method, 35.0f, 50.0f);
            applied += replaceWidthBeforeSetSize(method, 135.0f, 150.0f);

            verified += countDaySuffix(method);
            verified += countWidthBeforeSetSize(method, 100.0f, 1);
            verified += countWidthBeforeSetSize(method, 55.0f, 1);
            verified += countWidthBeforeSetSize(method, 50.0f, 1);
            verified += countWidthBeforeSetSize(method, 150.0f, 1);
        }
        return PatchResult.of(id(), context.classPath(), 9, applied, Math.min(6, verified) + noWrap,
                "campaign date day suffix, label widths and single-line values");
    }

    private static int disableValueWrapping(ClassNode classNode) {
        Map<String, FieldInsnNode> assignments = new HashMap<>();
        Map<String, MethodNode> constructors = new HashMap<>();
        for (MethodNode method : classNode.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : AsmUtil.instructions(method)) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && classNode.name.equals(field.owner)
                        && ("L" + LABEL + ";").equals(field.desc)
                        && VALUE_FIELDS.contains(field.name)) {
                    if (assignments.put(field.name, field) != null) {
                        throw new PatchException("日期数值标签重复初始化: " + field.name);
                    }
                    constructors.put(field.name, method);
                }
            }
        }
        if (!assignments.keySet().equals(VALUE_FIELDS)) {
            throw new PatchException("日期数值标签初始化结构不匹配: " + assignments.keySet());
        }
        for (String name : VALUE_FIELDS) {
            MethodNode method = constructors.get(name);
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, name, "L" + LABEL + ";"));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LABEL, "getRenderer",
                    "()L" + RENDERER + ";", false));
            code.add(new InsnNode(Opcodes.ICONST_0));
            // Renderer.return(boolean) controls wrapping, not clipping or font size.
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "return", "(Z)V", false));
            method.instructions.insert(assignments.get(name), code);
            method.maxStack = Math.max(method.maxStack, 2);
        }
        return assignments.size();
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

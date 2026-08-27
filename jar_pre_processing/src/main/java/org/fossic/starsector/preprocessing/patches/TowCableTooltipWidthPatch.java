package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

public final class TowCableTooltipWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/api/impl/campaign/TowCable.class";
    private static final String TARGET_METHOD = "getTooltipWidth";
    private static final String TARGET_DESCRIPTOR = "()F";
    private static final float DEFAULT_TOOLTIP_WIDTH = 369.0f;

    @Override
    public String id() {
        return "tow-cable-tooltip-width";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.API_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> TARGET_METHOD.equals(method.name)
                        && TARGET_DESCRIPTOR.equals(method.desc))
                .toList();
        if (methods.size() != 1) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected one " + TARGET_METHOD + TARGET_DESCRIPTOR
                    + ", found " + methods.size());
        }

        MethodNode method = methods.get(0);
        List<AbstractInsnNode> instructions = executableInstructions(method);
        if (instructions.size() != 2
                || instructions.get(0).getOpcode() != Opcodes.FCONST_0
                || instructions.get(1).getOpcode() != Opcodes.FRETURN) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected exact instruction sequence FCONST_0, FRETURN");
        }

        method.instructions.set(instructions.get(0), new LdcInsnNode(DEFAULT_TOOLTIP_WIDTH));
        List<AbstractInsnNode> patched = executableInstructions(method);
        int verified = patched.size() == 2
                && AsmUtil.isFloatLdc(patched.get(0), DEFAULT_TOOLTIP_WIDTH)
                && patched.get(1).getOpcode() == Opcodes.FRETURN ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "TowCable tooltip width 0 -> " + DEFAULT_TOOLTIP_WIDTH);
    }

    private static List<AbstractInsnNode> executableInstructions(MethodNode method) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() >= 0)
                .toList();
    }
}

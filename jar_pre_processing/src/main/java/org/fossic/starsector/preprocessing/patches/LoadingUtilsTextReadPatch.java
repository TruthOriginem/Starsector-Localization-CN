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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 用低分配、跨 byte chunk 正确解码 UTF-8 的 helper 替换 LoadingUtils 文本 reader。
 */
public final class LoadingUtilsTextReadPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/LoadingUtils.class";
    private static final String TARGET_METHOD = "super";
    private static final String TARGET_DESC =
            "(Ljava/io/InputStream;)Ljava/lang/String;";
    private static final String HOOK_OWNER =
            "org/fossic/starsector/optimization/FastTextReader";
    private static final String HOOK_METHOD = "read";

    @Override
    public String id() {
        return "loading-utils-fast-text-reader";
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
        List<MethodNode> matches = classNode.methods.stream()
                .filter(m -> TARGET_METHOD.equals(m.name)
                        && TARGET_DESC.equals(m.desc)
                        && (m.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException("LoadingUtils 文本 reader 签名匹配数异常: "
                    + matches.size() + "（预期 1）");
        }
        MethodNode method = matches.get(0);
        verifyOriginalShape(method);

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, TARGET_DESC, false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        method.maxStack = 1;
        method.maxLocals = 1;

        int verified = AsmUtil.countMethodCall(
                classNode, HOOK_OWNER, HOOK_METHOD, TARGET_DESC);
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "replace LoadingUtils.super(InputStream) with FastTextReader.read");
    }

    private static void verifyOriginalShape(MethodNode method) {
        int oneMiBConstants = 0;
        int byteArrayAllocations = 0;
        int stringBufferConstructions = 0;
        int regexReplacements = 0;
        int streamCloses = 0;

        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Integer value
                    && value == 1024 * 1024) {
                oneMiBConstants++;
            } else if (node.getOpcode() == Opcodes.NEWARRAY) {
                byteArrayAllocations++;
            } else if (node instanceof MethodInsnNode call) {
                if ("java/lang/StringBuffer".equals(call.owner)
                        && "<init>".equals(call.name)
                        && "()V".equals(call.desc)) {
                    stringBufferConstructions++;
                } else if ("java/lang/String".equals(call.owner)
                        && "replaceAll".equals(call.name)
                        && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        .equals(call.desc)) {
                    regexReplacements++;
                } else if ("java/io/InputStream".equals(call.owner)
                        && "close".equals(call.name)
                        && "()V".equals(call.desc)) {
                    streamCloses++;
                }
            }
        }

        if (oneMiBConstants != 1
                || byteArrayAllocations != 1
                || stringBufferConstructions != 1
                || regexReplacements != 1
                || streamCloses != 3) {
            throw new PatchException(
                    "LoadingUtils 文本 reader 原始结构变化: oneMiB="
                            + oneMiBConstants
                            + ", byteArrays=" + byteArrayAllocations
                            + ", stringBuffers=" + stringBufferConstructions
                            + ", replaceAll=" + regexReplacements
                            + ", closes=" + streamCloses);
        }
    }
}

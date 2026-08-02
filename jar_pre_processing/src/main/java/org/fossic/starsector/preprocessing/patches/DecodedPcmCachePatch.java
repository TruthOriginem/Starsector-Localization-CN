package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 在 OGG 解码完成、OpenAL 上传之前加入内容寻址 PCM 缓存 wrapper。
 *
 * <p>本 patch 只保留并重命名原方法，再插入一次 helper 调用；摘要、持久化、恢复和
 * 失败回退逻辑全部位于可单测的 {@code DecodedPcmCache}。
 */
public final class DecodedPcmCachePatch implements JarPatch {
    private static final String TARGET_CLASS = "sound/O0oO.class";
    private static final String TARGET_METHOD = "super";
    private static final String TARGET_DESC =
            "(Ljava/io/InputStream;)Lsound/G;";
    private static final String ORIGINAL_METHOD =
            "starsector$decodePcmUncached";
    private static final String HELPER =
            "org/fossic/starsector/optimization/DecodedPcmCache";
    private static final String HELPER_DESC =
            "(Ljava/lang/Object;Ljava/io/InputStream;)Ljava/lang/Object;";

    @Override
    public String id() {
        return "decoded-pcm-content-cache";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.SOUND_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        List<MethodNode> targets = classNode.methods.stream()
                .filter(method -> TARGET_METHOD.equals(method.name))
                .filter(method -> TARGET_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_PUBLIC) != 0)
                .filter(method -> (method.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        long originals = classNode.methods.stream()
                .filter(method -> ORIGINAL_METHOD.equals(method.name))
                .filter(method -> TARGET_DESC.equals(method.desc))
                .count();
        int helperCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "decode", HELPER_DESC);
        if (targets.size() != 1 || originals != 0 || helperCalls != 0) {
            throw new PatchException(
                    "PCM cache wrapper 目标结构异常: target="
                            + targets.size() + ", original=" + originals
                            + ", helper=" + helperCalls);
        }

        MethodNode original = targets.get(0);
        int originalAccess = original.access;
        original.name = ORIGINAL_METHOD;
        original.access = (originalAccess
                & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE
                | Opcodes.ACC_SYNTHETIC;

        String[] exceptions = original.exceptions == null
                ? null
                : original.exceptions.toArray(String[]::new);
        MethodNode wrapper = new MethodNode(
                originalAccess,
                TARGET_METHOD,
                TARGET_DESC,
                original.signature,
                exceptions);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "decode",
                HELPER_DESC,
                false));
        wrapper.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "sound/G"));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.maxStack = 2;
        wrapper.maxLocals = 2;
        classNode.methods.add(wrapper);

        long verifiedTargets = classNode.methods.stream()
                .filter(method -> TARGET_METHOD.equals(method.name))
                .filter(method -> TARGET_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_PUBLIC) != 0)
                .count();
        long verifiedOriginals = classNode.methods.stream()
                .filter(method -> ORIGINAL_METHOD.equals(method.name))
                .filter(method -> TARGET_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_PRIVATE) != 0)
                .count();
        int verifiedCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "decode", HELPER_DESC);
        if (verifiedTargets != 1
                || verifiedOriginals != 1
                || verifiedCalls != 1) {
            throw new PatchException(
                    "PCM cache wrapper 验证失败: target="
                            + verifiedTargets + ", original="
                            + verifiedOriginals + ", helper="
                            + verifiedCalls);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "wrap decoded PCM with content-addressed cache");
    }
}

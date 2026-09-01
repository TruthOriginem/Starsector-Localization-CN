package org.fossic.starsector.preprocessing.patches;

import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 把游戏的两个图片解码入口桥接到可回退 ImageIO 的受限 PNG 快路径。
 *
 * <p>格式筛选、快速解码与安全回退均位于 {@code FastPngDecoder}；
 * 本 patch 只替换方法调用，不改变原方法的控制流或流所有权。
 */
public final class FastPngDecoderPatch implements JarPatch {
    private static final Set<String> TARGET_CLASSES = Set.of(
            "com/fs/graphics/L.class",
            "com/fs/graphics/TextureLoader.class");
    private static final String IMAGE_IO = "javax/imageio/ImageIO";
    private static final String IMAGE_IO_METHOD = "read";
    private static final String DECODE_DESC =
            "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String HELPER =
            "org/fossic/starsector/optimization/FastPngDecoder";
    private static final String HELPER_METHOD = "decode";

    @Override
    public String id() {
        return "fast-png-image-decode";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.FAST_PNG;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return TARGET_CLASSES;
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        int originalCalls = countCalls(
                classNode, IMAGE_IO, IMAGE_IO_METHOD, DECODE_DESC);
        int existingHelperCalls = countCalls(
                classNode, HELPER, HELPER_METHOD, DECODE_DESC);
        if (originalCalls != 1 || existingHelperCalls != 0) {
            throw new PatchException(
                    "PNG 解码入口结构变化: " + context.classPath()
                            + ", ImageIO.read=" + originalCalls
                            + ", FastPngDecoder.decode="
                            + existingHelperCalls);
        }

        int applied = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode node : AsmUtil.instructions(method)) {
                if (!(node instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !IMAGE_IO.equals(call.owner)
                        || !IMAGE_IO_METHOD.equals(call.name)
                        || !DECODE_DESC.equals(call.desc)) {
                    continue;
                }
                call.owner = HELPER;
                call.name = HELPER_METHOD;
                call.itf = false;
                applied++;
            }
        }

        int verified = countCalls(
                classNode, HELPER, HELPER_METHOD, DECODE_DESC);
        int remainingOriginal = countCalls(
                classNode, IMAGE_IO, IMAGE_IO_METHOD, DECODE_DESC);
        if (applied != 1 || verified != 1 || remainingOriginal != 0) {
            throw new PatchException(
                    "PNG 解码入口替换验证失败: " + context.classPath()
                            + ", applied=" + applied
                            + ", verified=" + verified
                            + ", remainingImageIO="
                            + remainingOriginal);
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                1,
                applied,
                verified,
                "replace ImageIO.read(InputStream) with FastPngDecoder.decode");
    }

    private static int countCalls(
            ClassNode classNode,
            String owner,
            String name,
            String desc) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode node : AsmUtil.instructions(method)) {
                if (node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }
}

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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 为两个真实图片读取入口补入资源路径，并在 TextureLoader 的处理器/alpha 边界桥接缓存 helper。
 *
 * <p>内容摘要、缓存格式、失效和回退逻辑全部位于 optimization 包；本 patch 只操作五个
 * 精确调用点。它必须排在 {@link FastPngDecoderPatch} 之后、纹理转换方法替换之前。
 */
public final class TextureConversionCachePatch implements JarPatch {
    private static final String PRELOADER = "com/fs/graphics/L";
    private static final String TEXTURE_LOADER =
            "com/fs/graphics/TextureLoader";
    private static final Set<String> TARGET_CLASSES = Set.of(
            PRELOADER + ".class",
            TEXTURE_LOADER + ".class");
    private static final String FAST_PNG =
            "org/fossic/starsector/optimization/FastPngDecoder";
    private static final String TRACKER =
            "org/fossic/starsector/optimization/TextureSourceTracker";
    private static final String IMAGE = "java/awt/image/BufferedImage";
    private static final String COLOR_MODEL = "java/awt/image/ColorModel";
    private static final String IMAGE_PROCESSOR = "com/fs/graphics/I";
    private static final String READ_METHOD_DESC =
            "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String DECODE_DESC =
            "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String TRACKED_DECODE_DESC =
            "(Ljava/lang/String;Ljava/io/InputStream;)"
                    + "Ljava/awt/image/BufferedImage;";
    private static final String PROCESS_DESC =
            "(Ljava/awt/image/BufferedImage;)"
                    + "Ljava/awt/image/BufferedImage;";
    private static final String PREPARE_PROCESSOR_DESC =
            "(Ljava/awt/image/BufferedImage;)"
                    + "Ljava/awt/image/BufferedImage;";
    private static final String HAS_ALPHA_DESC =
            "(Ljava/awt/image/BufferedImage;)Z";
    private static final String CONVERTER =
            "org/fossic/starsector/optimization/TexturePixelConverter";
    private static final String CONVERT_DESC =
            "(Ljava/awt/image/BufferedImage;Ljava/nio/ByteBuffer;)"
                    + "Lorg/fossic/starsector/optimization/"
                    + "TexturePixelConverter$Result;";
    private static final String LOAD_BUFFERED_DESC =
            "(Ljava/awt/image/BufferedImage;IIII)"
                    + "Lcom/fs/graphics/Object;";
    private static final String LOAD_RESOURCE_DESC =
            "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)"
                    + "Lcom/fs/graphics/Object;";

    @Override
    public String id() {
        return "texture-conversion-content-cache";
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
        if (PRELOADER.equals(classNode.name)) {
            patchTrackedDecode(classNode);
            verifyCounts(classNode, 1, 0, 0, 0);
            return PatchResult.of(
                    id(), context.classPath(), 1, 1, 1,
                    "pass resource path to cached preloader decode");
        }
        if (!TEXTURE_LOADER.equals(classNode.name)) {
            throw new PatchException(
                    "纹理缓存 patch 收到非目标类: " + classNode.name);
        }

        patchTrackedDecode(classNode);
        MethodNode bufferedLoad = requireMethod(
                classNode, LOAD_BUFFERED_DESC);
        MethodNode resourceLoad = requireMethod(
                classNode, LOAD_RESOURCE_DESC);
        patchProcessorBoundary(bufferedLoad);
        patchProcessorBoundary(resourceLoad);
        patchAlphaQuery(bufferedLoad);
        patchAlphaQuery(resourceLoad);
        patchCachedConversion(classNode);
        verifyCounts(classNode, 1, 2, 2, 1);
        return PatchResult.of(
                id(), context.classPath(), 6, 6, 6,
                "track content cache and preserve processor invalidation");
    }

    private static void patchTrackedDecode(ClassNode classNode) {
        List<MethodNode> readers = classNode.methods.stream()
                .filter(method -> READ_METHOD_DESC.equals(method.desc))
                .filter(method -> countMethodCall(
                        method,
                        FAST_PNG,
                        "decode",
                        DECODE_DESC) == 1)
                .toList();
        if (readers.size() != 1) {
            throw new PatchException(
                    classNode.name + " 图片读取方法匹配数异常: "
                            + readers.size());
        }
        MethodNode reader = readers.get(0);
        List<MethodInsnNode> calls = AsmUtil.instructions(reader).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKESTATIC)
                .filter(call -> FAST_PNG.equals(call.owner))
                .filter(call -> "decode".equals(call.name))
                .filter(call -> DECODE_DESC.equals(call.desc))
                .toList();
        if (calls.size() != 1) {
            throw new PatchException(
                    classNode.name + " FastPngDecoder.decode 调用数异常: "
                            + calls.size());
        }
        MethodInsnNode call = calls.get(0);
        int pathLocal = (reader.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        InsnList arguments = new InsnList();
        arguments.add(new VarInsnNode(Opcodes.ALOAD, pathLocal));
        arguments.add(new InsnNode(Opcodes.SWAP));
        reader.instructions.insertBefore(call, arguments);
        call.name = "decodeTracked";
        call.desc = TRACKED_DECODE_DESC;
    }

    private static void patchProcessorBoundary(MethodNode method) {
        List<MethodInsnNode> calls = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEINTERFACE)
                .filter(call -> IMAGE_PROCESSOR.equals(call.owner))
                .filter(call -> PROCESS_DESC.equals(call.desc))
                .toList();
        if (calls.size() != 1) {
            throw new PatchException(
                    "TextureLoader 图片处理器调用数异常: "
                            + calls.size() + "，method=" + method.desc);
        }
        InsnList preparation = new InsnList();
        preparation.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                TRACKER,
                "prepareForProcessor",
                PREPARE_PROCESSOR_DESC,
                false));
        method.instructions.insertBefore(calls.get(0), preparation);
    }

    private static void patchAlphaQuery(MethodNode method) {
        List<MethodInsnNode> modelCalls = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL)
                .filter(call -> IMAGE.equals(call.owner))
                .filter(call -> "getColorModel".equals(call.name))
                .filter(call -> "()Ljava/awt/image/ColorModel;"
                        .equals(call.desc))
                .filter(call -> {
                    AbstractInsnNode next = nextMeaningful(call);
                    return next instanceof MethodInsnNode hasAlpha
                            && hasAlpha.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && COLOR_MODEL.equals(hasAlpha.owner)
                            && "hasAlpha".equals(hasAlpha.name)
                            && "()Z".equals(hasAlpha.desc);
                })
                .toList();
        if (modelCalls.size() != 1) {
            throw new PatchException(
                    "TextureLoader alpha 查询数异常: "
                            + modelCalls.size() + "，method=" + method.desc);
        }
        MethodInsnNode getColorModel = modelCalls.get(0);
        AbstractInsnNode hasAlpha = nextMeaningful(getColorModel);
        getColorModel.setOpcode(Opcodes.INVOKESTATIC);
        getColorModel.owner = TRACKER;
        getColorModel.name = "hasAlpha";
        getColorModel.desc = HAS_ALPHA_DESC;
        getColorModel.itf = false;
        method.instructions.remove(hasAlpha);
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current instanceof LabelNode
                || current instanceof LineNumberNode
                || current instanceof FrameNode) {
            current = current.getNext();
        }
        return current;
    }

    private static void patchCachedConversion(ClassNode classNode) {
        int original = AsmUtil.countMethodCall(
                classNode, CONVERTER, "convert", CONVERT_DESC);
        int existing = AsmUtil.countMethodCall(
                classNode, CONVERTER, "convertCached", CONVERT_DESC);
        if (original != 1 || existing != 0) {
            throw new PatchException(
                    "TextureLoader converter bridge 结构异常: convert="
                            + original + ", convertCached=" + existing);
        }
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode node : AsmUtil.instructions(method)) {
                if (node instanceof MethodInsnNode call
                        && CONVERTER.equals(call.owner)
                        && "convert".equals(call.name)
                        && CONVERT_DESC.equals(call.desc)) {
                    call.name = "convertCached";
                }
            }
        }
    }

    private static MethodNode requireMethod(
            ClassNode classNode, String descriptor) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> descriptor.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    classNode.name + " 方法 " + descriptor + " 匹配数异常: "
                            + matches.size());
        }
        return matches.get(0);
    }

    private static void verifyCounts(
            ClassNode classNode,
            int trackedDecodes,
            int processorPreparations,
            int alphaQueries,
            int cachedConversions) {
        int oldDecodes = AsmUtil.countMethodCall(
                classNode, FAST_PNG, "decode", DECODE_DESC);
        int actualTracked = AsmUtil.countMethodCall(
                classNode,
                FAST_PNG,
                "decodeTracked",
                TRACKED_DECODE_DESC);
        int actualPreparations = AsmUtil.countMethodCall(
                classNode,
                TRACKER,
                "prepareForProcessor",
                PREPARE_PROCESSOR_DESC);
        int actualAlphaQueries = AsmUtil.countMethodCall(
                classNode, TRACKER, "hasAlpha", HAS_ALPHA_DESC);
        int actualCachedConversions = AsmUtil.countMethodCall(
                classNode,
                CONVERTER,
                "convertCached",
                CONVERT_DESC);
        if (oldDecodes != 0
                || actualTracked != trackedDecodes
                || actualPreparations != processorPreparations
                || actualAlphaQueries != alphaQueries
                || actualCachedConversions != cachedConversions) {
            throw new PatchException(
                    classNode.name + " 纹理缓存调用验证失败: oldDecode="
                            + oldDecodes
                            + ", tracked=" + actualTracked
                            + ", prepareProcessor=" + actualPreparations
                            + ", hasAlpha=" + actualAlphaQueries
                            + ", convertCached="
                            + actualCachedConversions);
        }
    }

    private static int countMethodCall(
            MethodNode method,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}

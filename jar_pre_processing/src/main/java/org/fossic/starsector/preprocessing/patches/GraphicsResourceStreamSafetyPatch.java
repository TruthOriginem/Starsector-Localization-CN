package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
import java.util.List;
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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把图片/声音读取入口已经隐含的流所有权桥接到可测试 helper。
 *
 * <p>本 patch 在 FastPng 和纹理缓存之后运行，只识别最终解码调用形态；读取、关闭和
 * 异常抑制逻辑全部位于 optimization 包。
 */
public final class GraphicsResourceStreamSafetyPatch implements JarPatch {
    private static final String PRELOADER = "com/fs/graphics/L";
    private static final String TEXTURE_LOADER =
            "com/fs/graphics/TextureLoader";
    private static final Set<String> TARGETS = Set.of(
            PRELOADER + ".class", TEXTURE_LOADER + ".class");
    private static final String RESOURCE_LOADER = "com/fs/util/C";
    private static final String IMAGE_IO = "javax/imageio/ImageIO";
    private static final String FAST_PNG =
            "org/fossic/starsector/optimization/FastPngDecoder";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String IMAGE_DESC =
            "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String TRACKED_IMAGE_DESC =
            "(Ljava/lang/String;Ljava/io/InputStream;)"
                    + "Ljava/awt/image/BufferedImage;";
    private static final String IMAGE_READER_DESC =
            "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String SOUND_READER_DESC =
            "(Ljava/lang/String;)[B";
    private static final String READ_ALL_DESC =
            "(Ljava/io/InputStream;)[B";

    @Override
    public String id() {
        return "graphics-resource-stream-safety";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.RESOURCE_STREAM_SAFETY;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        boolean preloader = PRELOADER.equals(classNode.name);
        if (!preloader && !TEXTURE_LOADER.equals(classNode.name)) {
            throw new PatchException(
                    "资源流安全 patch 收到非目标类: " + classNode.name);
        }

        MethodNode imageReader = locateImageReader(classNode);
        patchImageReader(imageReader, preloader);
        int expected = 1;
        if (preloader) {
            patchSoundReader(classNode);
            expected++;
        }

        int imageHelpers = countAnyOwnedImageCall(classNode);
        int soundHelpers = countCall(
                classNode, HELPER, "readAllAndClose", READ_ALL_DESC);
        int legacyImages = countLegacyImageCalls(classNode);
        int expectedSound = preloader ? 1 : 0;
        if (imageHelpers != 1
                || soundHelpers != expectedSound
                || legacyImages != 0) {
            throw new PatchException(
                    classNode.name + " 资源流 bridge 验证失败: images="
                            + imageHelpers + ", sounds=" + soundHelpers
                            + ", legacyImages=" + legacyImages);
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                expected,
                expected,
                expected,
                preloader
                        ? "own image failure paths and all sound byte streams"
                        : "own TextureLoader fallback image streams");
    }

    private static MethodNode locateImageReader(ClassNode classNode) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> IMAGE_READER_DESC.equals(method.desc))
                .filter(method -> countLegacyImageCalls(method) == 1)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    classNode.name + " 图片资源方法匹配数异常: "
                            + matches.size());
        }
        return matches.get(0);
    }

    private static void patchImageReader(
            MethodNode method, boolean removeOriginalSuccessClose) {
        List<MethodInsnNode> calls = legacyImageCalls(method);
        if (calls.size() != 1) {
            throw new PatchException(
                    "图片资源解码调用数异常: " + calls.size());
        }
        MethodInsnNode decode = calls.get(0);
        if (IMAGE_IO.equals(decode.owner)) {
            decode.owner = HELPER;
            decode.name = "readImageAndClose";
        } else if ("decode".equals(decode.name)) {
            decode.owner = HELPER;
            decode.name = "decodePngAndClose";
        } else if ("decodeTracked".equals(decode.name)) {
            decode.owner = HELPER;
            decode.name = "decodeTrackedPngAndClose";
        } else {
            throw new PatchException(
                    "不支持的图片解码调用: "
                            + decode.owner + "." + decode.name + decode.desc);
        }
        decode.itf = false;

        List<MethodInsnNode> closes = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> "java/io/BufferedInputStream"
                        .equals(call.owner))
                .filter(call -> "close".equals(call.name))
                .filter(call -> "()V".equals(call.desc))
                .toList();
        int expectedCloses = removeOriginalSuccessClose ? 1 : 0;
        if (closes.size() != expectedCloses) {
            throw new PatchException(
                    "图片资源原始 close 数异常: " + closes.size());
        }
        if (!removeOriginalSuccessClose) {
            return;
        }
        MethodInsnNode close = closes.get(0);
        AbstractInsnNode load = previousMeaningful(close);
        if (!(load instanceof VarInsnNode variable)
                || variable.getOpcode() != Opcodes.ALOAD) {
            throw new PatchException(
                    "图片资源成功 close 前不是 ALOAD");
        }
        method.instructions.remove(load);
        method.instructions.remove(close);
    }

    private static void patchSoundReader(ClassNode classNode) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> SOUND_READER_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> (method.access & Opcodes.ACC_PRIVATE) != 0)
                .toList();
        if (methods.size() != 1) {
            throw new PatchException(
                    "声音字节资源方法匹配数异常: " + methods.size());
        }
        MethodNode method = methods.get(0);
        verifyOriginalSoundReader(method);

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
        replacement.add(new TypeInsnNode(
                Opcodes.NEW, "java/io/BufferedInputStream"));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RESOURCE_LOADER,
                "Ó00000",
                "()Lcom/fs/util/C;",
                false));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                RESOURCE_LOADER,
                "Ô00000",
                "(Ljava/lang/String;)Ljava/io/InputStream;",
                false));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/io/BufferedInputStream",
                "<init>",
                "(Ljava/io/InputStream;)V",
                false));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "readAllAndClose",
                READ_ALL_DESC,
                false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        method.maxStack = 4;
        method.maxLocals = 1;
    }

    private static void verifyOriginalSoundReader(MethodNode method) {
        int resourceOpens = countCall(
                method,
                RESOURCE_LOADER,
                "Ô00000",
                "(Ljava/lang/String;)Ljava/io/InputStream;");
        int bufferedReads = countCall(
                method,
                "java/io/BufferedInputStream",
                "read",
                "([BII)I");
        int byteArrays = countCall(
                method,
                "java/io/ByteArrayOutputStream",
                "toByteArray",
                "()[B");
        int closes = countCall(
                method,
                "java/io/BufferedInputStream",
                "close",
                "()V");
        int existing = countCall(
                method, HELPER, "readAllAndClose", READ_ALL_DESC);
        if (resourceOpens != 1
                || bufferedReads != 1
                || byteArrays != 1
                || closes != 0
                || existing != 0) {
            throw new PatchException(
                    "声音字节资源原始结构变化: open=" + resourceOpens
                            + ", read=" + bufferedReads
                            + ", toByteArray=" + byteArrays
                            + ", close=" + closes
                            + ", helper=" + existing);
        }
    }

    private static List<MethodInsnNode> legacyImageCalls(MethodNode method) {
        ArrayList<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            boolean imageIo = IMAGE_IO.equals(call.owner)
                    && "read".equals(call.name)
                    && IMAGE_DESC.equals(call.desc);
            boolean fast = FAST_PNG.equals(call.owner)
                    && (("decode".equals(call.name)
                                    && IMAGE_DESC.equals(call.desc))
                            || ("decodeTracked".equals(call.name)
                                    && TRACKED_IMAGE_DESC.equals(call.desc)));
            if (imageIo || fast) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static int countLegacyImageCalls(MethodNode method) {
        return legacyImageCalls(method).size();
    }

    private static int countLegacyImageCalls(ClassNode classNode) {
        return classNode.methods.stream()
                .mapToInt(GraphicsResourceStreamSafetyPatch::
                        countLegacyImageCalls)
                .sum();
    }

    private static int countAnyOwnedImageCall(ClassNode classNode) {
        return countCall(
                        classNode,
                        HELPER,
                        "readImageAndClose",
                        IMAGE_DESC)
                + countCall(
                        classNode,
                        HELPER,
                        "decodePngAndClose",
                        IMAGE_DESC)
                + countCall(
                        classNode,
                        HELPER,
                        "decodeTrackedPngAndClose",
                        TRACKED_IMAGE_DESC);
    }

    private static int countCall(
            ClassNode classNode,
            String owner,
            String name,
            String desc) {
        return AsmUtil.countMethodCall(classNode, owner, name, desc);
    }

    private static int countCall(
            MethodNode method,
            String owner,
            String name,
            String desc) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousMeaningful(
            AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }
}

package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Map;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把 TextureLoader 的逐像素转换方法替换为可独立测试的批量转换 helper。
 */
public final class TexturePixelConversionPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/graphics/TextureLoader.class";
    private static final String TARGET_DESC =
            "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)"
                    + "Ljava/nio/ByteBuffer;";
    private static final String TEXTURE_LOADER = "com/fs/graphics/TextureLoader";
    private static final String TEXTURE_OBJECT = "com/fs/graphics/Object";
    private static final String CONVERTER =
            "org/fossic/starsector/optimization/TexturePixelConverter";
    private static final String RESULT = CONVERTER + "$Result";
    private static final String CONVERT_DESC =
            "(Ljava/awt/image/BufferedImage;Ljava/nio/ByteBuffer;)L"
                    + RESULT + ";";
    private static final String COLOR_DESC = "Ljava/awt/Color;";
    private static final String LARGE_BUFFER_FIELD = "oO0000";
    private static final String AVERAGE_FIELD = "õ00000";
    private static final String BRIGHT_FIELD = "interface";
    private static final String MEDIAN_FIELD = "Ó00000";
    private static final String HEIGHT_SETTER = "Ô00000";
    private static final String WIDTH_SETTER = "Object";

    @Override
    public String id() {
        return "texture-row-pixel-conversion";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> TARGET_DESC.equals(method.desc)
                        && (method.access & Opcodes.ACC_PRIVATE) != 0
                        && (method.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "TextureLoader 像素转换方法匹配数异常: "
                            + matches.size() + "（预期 1）");
        }
        MethodNode method = matches.get(0);
        verifyOriginalShape(method);
        replaceMethod(method);

        int converterCalls = AsmUtil.countMethodCall(
                classNode, CONVERTER, "convert", CONVERT_DESC);
        int resultCalls = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && RESULT.equals(call.owner)) {
                resultCalls++;
            }
        }
        if (converterCalls != 1 || resultCalls != 7) {
            throw new PatchException(
                    "TextureLoader helper 调用验证失败: converter="
                            + converterCalls + ", result=" + resultCalls);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, converterCalls,
                "replace per-pixel texture conversion with "
                        + "TexturePixelConverter.convert");
    }

    private static void verifyOriginalShape(MethodNode method) {
        int pixelReads = 0;
        int bufferAllocations = 0;
        int indexedBufferWrites = 0;
        int heightSetters = 0;
        int widthSetters = 0;
        int lowerMedianCalls = 0;
        int brightestAverageCalls = 0;
        int largeBufferReads = 0;
        int largeBufferWrites = 0;
        Map<String, Integer> expectedFieldWrites = Map.of(
                AVERAGE_FIELD, 2,
                BRIGHT_FIELD, 2,
                MEDIAN_FIELD, 2);
        java.util.HashMap<String, Integer> fieldWrites =
                new java.util.HashMap<>();

        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call) {
                if ("java/awt/image/Raster".equals(call.owner)
                        && "getPixel".equals(call.name)
                        && "(II[I)[I".equals(call.desc)) {
                    pixelReads++;
                } else if ("org/lwjgl/BufferUtils".equals(call.owner)
                        && "createByteBuffer".equals(call.name)
                        && "(I)Ljava/nio/ByteBuffer;".equals(call.desc)) {
                    bufferAllocations++;
                } else if ("java/nio/ByteBuffer".equals(call.owner)
                        && "put".equals(call.name)
                        && "(IB)Ljava/nio/ByteBuffer;".equals(call.desc)) {
                    indexedBufferWrites++;
                } else if (TEXTURE_OBJECT.equals(call.owner)
                        && HEIGHT_SETTER.equals(call.name)
                        && "(I)V".equals(call.desc)) {
                    heightSetters++;
                } else if (TEXTURE_OBJECT.equals(call.owner)
                        && WIDTH_SETTER.equals(call.name)
                        && "(I)V".equals(call.desc)) {
                    widthSetters++;
                } else if (TEXTURE_LOADER.equals(call.owner)
                        && "o00000".equals(call.name)
                        && "([FF)F".equals(call.desc)) {
                    lowerMedianCalls++;
                } else if (TEXTURE_LOADER.equals(call.owner)
                        && "new".equals(call.name)
                        && "([FF)F".equals(call.desc)) {
                    brightestAverageCalls++;
                }
            } else if (node instanceof FieldInsnNode field
                    && TEXTURE_LOADER.equals(field.owner)) {
                if (field.getOpcode() == Opcodes.PUTFIELD
                        && COLOR_DESC.equals(field.desc)
                        && expectedFieldWrites.containsKey(field.name)) {
                    fieldWrites.merge(field.name, 1, Integer::sum);
                } else if (LARGE_BUFFER_FIELD.equals(field.name)
                        && "Ljava/nio/ByteBuffer;".equals(field.desc)) {
                    if (field.getOpcode() == Opcodes.GETSTATIC) {
                        largeBufferReads++;
                    } else if (field.getOpcode() == Opcodes.PUTSTATIC) {
                        largeBufferWrites++;
                    }
                }
            }
        }

        if (pixelReads != 2
                || bufferAllocations != 3
                || indexedBufferWrites != 7
                || heightSetters != 1
                || widthSetters != 1
                || lowerMedianCalls != 2
                || brightestAverageCalls != 4
                || largeBufferReads != 2
                || largeBufferWrites != 1
                || !expectedFieldWrites.equals(fieldWrites)) {
            throw new PatchException(
                    "TextureLoader 像素转换原始结构变化: pixelReads="
                            + pixelReads
                            + ", allocations=" + bufferAllocations
                            + ", writes=" + indexedBufferWrites
                            + ", heightSetters=" + heightSetters
                            + ", widthSetters=" + widthSetters
                            + ", lowerMedian=" + lowerMedianCalls
                            + ", brightestAverage=" + brightestAverageCalls
                            + ", largeBufferReads=" + largeBufferReads
                            + ", largeBufferWrites=" + largeBufferWrites
                            + ", colorFields=" + fieldWrites);
        }
    }

    private static void replaceMethod(MethodNode method) {
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
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                TEXTURE_LOADER,
                LARGE_BUFFER_FIELD,
                "Ljava/nio/ByteBuffer;"));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CONVERTER,
                "convert",
                CONVERT_DESC,
                false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, 3));

        replacement.add(new VarInsnNode(Opcodes.ALOAD, 3));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                RESULT,
                "cachedOpaqueLargeTextureBuffer",
                "()Ljava/nio/ByteBuffer;",
                false));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                TEXTURE_LOADER,
                LARGE_BUFFER_FIELD,
                "Ljava/nio/ByteBuffer;"));

        addIntResultSetter(
                replacement, "paddedHeight", HEIGHT_SETTER);
        addIntResultSetter(
                replacement, "paddedWidth", WIDTH_SETTER);
        addColorResultWrite(
                replacement, "averageColor", AVERAGE_FIELD);
        addColorResultWrite(
                replacement, "brightColor", BRIGHT_FIELD);
        addColorResultWrite(
                replacement, "medianColor", MEDIAN_FIELD);

        replacement.add(new VarInsnNode(Opcodes.ALOAD, 3));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                RESULT,
                "buffer",
                "()Ljava/nio/ByteBuffer;",
                false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        method.maxStack = 2;
        method.maxLocals = 4;
    }

    private static void addIntResultSetter(
            InsnList instructions, String accessor, String setter) {
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, RESULT, accessor, "()I", false));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TEXTURE_OBJECT,
                setter,
                "(I)V",
                false));
    }

    private static void addColorResultWrite(
            InsnList instructions, String accessor, String field) {
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                RESULT,
                accessor,
                "()Ljava/awt/Color;",
                false));
        instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                TEXTURE_LOADER,
                field,
                COLOR_DESC));
    }
}

package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 让原版 OGG 解码器实现可测试的 PCM 批量读取接口。
 *
 * <p>本 patch 只添加四个方法桥接；补块、边界和异常语义全部位于
 * {@code PcmBulkReader}。
 */
public final class PcmDecoderAccessPatch implements JarPatch {
    private static final String TARGET_CLASS = "sound/F.class";
    private static final String OWNER = "sound/F";
    private static final String ACCESS =
            "org/fossic/starsector/optimization/PcmDecoderAccess";
    private static final String BUFFER_FIELD = "String.super";
    private static final String BUFFER_DESC = "Ljava/nio/ByteBuffer;";
    private static final String POSITION_FIELD = "void";
    private static final String REFILL_METHOD = "Ö00000";

    @Override
    public String id() {
        return "ogg-pcm-decoder-access";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.PCM_BULK_READ;
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
        if (!OWNER.equals(classNode.name)) {
            throw new PatchException(
                    "OGG PCM 解码器类名异常: " + classNode.name);
        }
        if (classNode.interfaces.contains(ACCESS)) {
            throw new PatchException("OGG PCM 解码接口已存在");
        }

        FieldNode buffer = uniqueInstanceField(
                classNode, BUFFER_FIELD, BUFFER_DESC);
        FieldNode position = uniqueInstanceField(
                classNode, POSITION_FIELD, "I");
        uniqueRefillMethod(classNode);
        verifyReadMethods(classNode);
        verifyNoBridgeMethods(classNode);

        classNode.interfaces.add(ACCESS);
        classNode.methods.add(bufferGetter(buffer));
        classNode.methods.add(positionGetter(position));
        classNode.methods.add(positionSetter(position));
        classNode.methods.add(refillBridge());

        int verified = verifyBridges(classNode);
        return PatchResult.of(
                id(),
                context.classPath(),
                4,
                4,
                verified,
                "implement PcmDecoderAccess through four synthetic accessors; "
                        + "leave the original read methods unchanged");
    }

    private static FieldNode uniqueInstanceField(
            ClassNode classNode, String name, String desc) {
        List<FieldNode> matches = classNode.fields.stream()
                .filter(field -> name.equals(field.name)
                        && desc.equals(field.desc)
                        && (field.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "OGG PCM 字段匹配数异常: " + name + desc + " -> "
                            + matches.size());
        }
        return matches.get(0);
    }

    private static void uniqueRefillMethod(ClassNode classNode) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> REFILL_METHOD.equals(method.name)
                        && "()V".equals(method.desc)
                        && (method.access & Opcodes.ACC_PRIVATE) != 0
                        && (method.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1
                || matches.get(0).exceptions == null
                || !matches.get(0).exceptions.contains(
                        "java/io/IOException")) {
            throw new PatchException(
                    "OGG PCM 补块方法结构变化: " + matches.size());
        }
    }

    private static void verifyReadMethods(ClassNode classNode) {
        long scalarReads = classNode.methods.stream()
                .filter(method -> "read".equals(method.name)
                        && "()I".equals(method.desc))
                .count();
        long bulkReads = classNode.methods.stream()
                .filter(method -> "read".equals(method.name)
                        && "([BII)I".equals(method.desc))
                .count();
        if (scalarReads != 1 || bulkReads != 1) {
            throw new PatchException(
                    "OGG PCM 原版读取方法结构变化: scalar="
                            + scalarReads + ", bulk=" + bulkReads);
        }
    }

    private static void verifyNoBridgeMethods(ClassNode classNode) {
        if (countBridgeMethods(classNode) != 0) {
            throw new PatchException("OGG PCM 解码桥接方法已存在");
        }
    }

    private static MethodNode bufferGetter(FieldNode field) {
        MethodNode method = bridgeMethod(
                "pcmBuffer", "()Ljava/nio/ByteBuffer;", null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, field.name, field.desc));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode positionGetter(FieldNode field) {
        MethodNode method = bridgeMethod(
                "pcmReadPosition", "()I", null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, field.name, field.desc));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode positionSetter(FieldNode field) {
        MethodNode method = bridgeMethod(
                "pcmReadPosition", "(I)V", null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, OWNER, field.name, field.desc));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        return method;
    }

    private static MethodNode refillBridge() {
        MethodNode method = bridgeMethod(
                "decodeNextPcmBlock",
                "()V",
                new String[] {"java/io/IOException"});
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                OWNER,
                REFILL_METHOD,
                "()V",
                false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode bridgeMethod(
            String name, String desc, String[] exceptions) {
        return new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                name,
                desc,
                null,
                exceptions);
    }

    private static int verifyBridges(ClassNode classNode) {
        long interfaceCount = classNode.interfaces.stream()
                .filter(ACCESS::equals)
                .count();
        int bridgeMethods = countBridgeMethods(classNode);
        if (interfaceCount != 1 || bridgeMethods != 4) {
            throw new PatchException(
                    "OGG PCM 解码桥接验证失败: interface="
                            + interfaceCount + ", methods=" + bridgeMethods);
        }
        return bridgeMethods;
    }

    private static int countBridgeMethods(ClassNode classNode) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            if (("pcmBuffer".equals(method.name)
                            && "()Ljava/nio/ByteBuffer;".equals(method.desc))
                    || ("pcmReadPosition".equals(method.name)
                            && ("()I".equals(method.desc)
                                    || "(I)V".equals(method.desc)))
                    || ("decodeNextPcmBlock".equals(method.name)
                            && "()V".equals(method.desc))) {
                count++;
            }
        }
        return count;
    }
}

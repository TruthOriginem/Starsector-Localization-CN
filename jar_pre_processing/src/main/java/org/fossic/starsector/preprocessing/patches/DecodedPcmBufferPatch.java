package org.fossic.starsector.preprocessing.patches;

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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 让 OGG 解码路径用可测试的固定块 PCM 累加器替代 BAOS。
 *
 * <p>本 patch 仅替换存储桥接；原版解码器的完成判断、逐字节读取、
 * 关闭顺序和音频元数据赋值全部保留。
 */
public final class DecodedPcmBufferPatch implements JarPatch {
    private static final String TARGET_CLASS = "sound/O0oO.class";
    private static final String TARGET_DESC =
            "(Ljava/io/InputStream;)Lsound/G;";
    private static final String BAOS =
            "java/io/ByteArrayOutputStream";
    private static final String ACCUMULATOR =
            "org/fossic/starsector/optimization/DecodedPcmBuffer";
    private static final String BUFFER_DESC = "Ljava/nio/ByteBuffer;";

    @Override
    public String id() {
        return "decoded-pcm-fixed-chunk-accumulator";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.PCM_BUFFER;
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
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "super".equals(method.name)
                        && TARGET_DESC.equals(method.desc)
                        && (method.access & Opcodes.ACC_PUBLIC) != 0
                        && (method.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "OGG PCM 解码方法匹配数异常: "
                            + matches.size() + "（预期 1）");
        }

        MethodNode method = matches.get(0);
        OriginalShape shape = verifyOriginalShape(method);
        replaceAccumulator(method, shape);
        verifyPatchedShape(method);

        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "replace ByteArrayOutputStream PCM staging with "
                        + "DecodedPcmBuffer while preserving decoder flow");
    }

    private static OriginalShape verifyOriginalShape(MethodNode method) {
        TypeInsnNode allocation = null;
        MethodInsnNode constructor = null;
        MethodInsnNode write = null;
        MethodInsnNode toByteArray = null;
        MethodInsnNode rewind = null;
        FieldInsnNode resultField = null;
        int baosAllocations = 0;
        int baosConstructors = 0;
        int baosWrites = 0;
        int byteArrayCopies = 0;
        int directAllocations = 0;
        int directPuts = 0;
        int rewinds = 0;
        int decoderReads = 0;
        int decoderDoneChecks = 0;
        int decoderCloses = 0;
        int resultFieldWrites = 0;
        int existingHelperCalls = 0;

        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && BAOS.equals(type.desc)) {
                allocation = type;
                baosAllocations++;
            } else if (node instanceof MethodInsnNode call) {
                if (BAOS.equals(call.owner)
                        && "<init>".equals(call.name)
                        && "()V".equals(call.desc)) {
                    constructor = call;
                    baosConstructors++;
                } else if (BAOS.equals(call.owner)
                        && "write".equals(call.name)
                        && "(I)V".equals(call.desc)) {
                    write = call;
                    baosWrites++;
                } else if (BAOS.equals(call.owner)
                        && "toByteArray".equals(call.name)
                        && "()[B".equals(call.desc)) {
                    toByteArray = call;
                    byteArrayCopies++;
                } else if ("java/nio/ByteBuffer".equals(call.owner)
                        && "allocateDirect".equals(call.name)
                        && "(I)Ljava/nio/ByteBuffer;".equals(call.desc)) {
                    directAllocations++;
                } else if ("java/nio/ByteBuffer".equals(call.owner)
                        && "put".equals(call.name)
                        && "([B)Ljava/nio/ByteBuffer;".equals(call.desc)) {
                    directPuts++;
                } else if ("java/nio/ByteBuffer".equals(call.owner)
                        && "rewind".equals(call.name)
                        && "()Ljava/nio/ByteBuffer;".equals(call.desc)) {
                    rewind = call;
                    rewinds++;
                } else if ("sound/F".equals(call.owner)
                        && "read".equals(call.name)
                        && "()I".equals(call.desc)) {
                    decoderReads++;
                } else if ("sound/F".equals(call.owner)
                        && "o00000".equals(call.name)
                        && "()Z".equals(call.desc)) {
                    decoderDoneChecks++;
                } else if ("sound/F".equals(call.owner)
                        && "close".equals(call.name)
                        && "()V".equals(call.desc)) {
                    decoderCloses++;
                } else if (ACCUMULATOR.equals(call.owner)) {
                    existingHelperCalls++;
                }
            } else if (node instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && "sound/G".equals(field.owner)
                    && "Object".equals(field.name)
                    && BUFFER_DESC.equals(field.desc)) {
                resultField = field;
                resultFieldWrites++;
            }
        }

        int frameTypes = countAndOptionallyReplaceFrameTypes(
                method, false);
        if (baosAllocations != 1
                || baosConstructors != 1
                || baosWrites != 1
                || byteArrayCopies != 1
                || directAllocations != 1
                || directPuts != 1
                || rewinds != 1
                || decoderReads != 1
                || decoderDoneChecks != 1
                || decoderCloses != 1
                || resultFieldWrites != 1
                || frameTypes != 1
                || existingHelperCalls != 0) {
            throw new PatchException(
                    "OGG PCM 解码方法原始结构变化: new="
                            + baosAllocations + ", ctor=" + baosConstructors
                            + ", write=" + baosWrites
                            + ", toByteArray=" + byteArrayCopies
                            + ", allocateDirect=" + directAllocations
                            + ", directPut=" + directPuts
                            + ", rewind=" + rewinds
                            + ", read=" + decoderReads
                            + ", done=" + decoderDoneChecks
                            + ", close=" + decoderCloses
                            + ", resultField=" + resultFieldWrites
                            + ", frameTypes=" + frameTypes
                            + ", helper=" + existingHelperCalls);
        }
        return new OriginalShape(
                allocation, constructor, write, toByteArray,
                rewind, resultField);
    }

    private static void replaceAccumulator(
            MethodNode method, OriginalShape shape) {
        shape.allocation().desc = ACCUMULATOR;
        shape.constructor().owner = ACCUMULATOR;
        shape.write().owner = ACCUMULATOR;

        AbstractInsnNode tailStart = AsmUtil.previousReal(
                shape.toByteArray());
        AbstractInsnNode tailEnd = AsmUtil.nextReal(shape.rewind());
        requireVariableLoad(tailStart, 2, "toByteArray receiver");
        requireOpcode(tailEnd, Opcodes.POP, "rewind result pop");

        AbstractInsnNode afterTail = AsmUtil.nextReal(tailEnd);
        requireVariableLoad(afterTail, 4, "result return load");
        requireOpcode(
                AsmUtil.nextReal(afterTail), Opcodes.ARETURN,
                "result return");

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 4));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                ACCUMULATOR,
                "finish",
                "()Ljava/nio/ByteBuffer;",
                false));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                shape.resultField().owner,
                shape.resultField().name,
                shape.resultField().desc));
        method.instructions.insertBefore(tailStart, replacement);

        AbstractInsnNode node = tailStart;
        while (true) {
            AbstractInsnNode next = node.getNext();
            method.instructions.remove(node);
            if (node == tailEnd) {
                break;
            }
            node = next;
            if (node == null) {
                throw new PatchException(
                        "OGG PCM 尾部替换未找到终点");
            }
        }

        int frameTypes = countAndOptionallyReplaceFrameTypes(
                method, true);
        if (frameTypes != 1) {
            throw new PatchException(
                    "OGG PCM StackMap 类型替换数异常: "
                            + frameTypes + "（预期 1）");
        }
    }

    private static void verifyPatchedShape(MethodNode method) {
        int helperConstructors = 0;
        int helperWrites = 0;
        int helperFinishes = 0;
        int oldCalls = 0;
        int decoderReads = 0;
        int decoderDoneChecks = 0;
        int decoderCloses = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode call)) {
                continue;
            }
            if (ACCUMULATOR.equals(call.owner)
                    && "<init>".equals(call.name)
                    && "()V".equals(call.desc)) {
                helperConstructors++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "write".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                helperWrites++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "finish".equals(call.name)
                    && "()Ljava/nio/ByteBuffer;".equals(call.desc)) {
                helperFinishes++;
            } else if (BAOS.equals(call.owner)
                    || ("java/nio/ByteBuffer".equals(call.owner)
                            && ("allocateDirect".equals(call.name)
                                    || "rewind".equals(call.name)
                                    || ("put".equals(call.name)
                                            && "([B)Ljava/nio/ByteBuffer;"
                                                    .equals(call.desc))))) {
                oldCalls++;
            } else if ("sound/F".equals(call.owner)
                    && "read".equals(call.name)
                    && "()I".equals(call.desc)) {
                decoderReads++;
            } else if ("sound/F".equals(call.owner)
                    && "o00000".equals(call.name)
                    && "()Z".equals(call.desc)) {
                decoderDoneChecks++;
            } else if ("sound/F".equals(call.owner)
                    && "close".equals(call.name)
                    && "()V".equals(call.desc)) {
                decoderCloses++;
            }
        }

        int oldFrameTypes = countFrameTypes(method, BAOS);
        int helperFrameTypes = countFrameTypes(method, ACCUMULATOR);
        if (helperConstructors != 1
                || helperWrites != 1
                || helperFinishes != 1
                || oldCalls != 0
                || decoderReads != 1
                || decoderDoneChecks != 1
                || decoderCloses != 1
                || oldFrameTypes != 0
                || helperFrameTypes != 1) {
            throw new PatchException(
                    "OGG PCM 累加器桥接验证失败: ctor="
                            + helperConstructors + ", write=" + helperWrites
                            + ", finish=" + helperFinishes
                            + ", oldCalls=" + oldCalls
                            + ", read=" + decoderReads
                            + ", done=" + decoderDoneChecks
                            + ", close=" + decoderCloses
                            + ", oldFrame=" + oldFrameTypes
                            + ", helperFrame=" + helperFrameTypes);
        }
    }

    private static int countAndOptionallyReplaceFrameTypes(
            MethodNode method, boolean replace) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof FrameNode frame)
                    || frame.local == null) {
                continue;
            }
            for (int index = 0; index < frame.local.size(); index++) {
                if (BAOS.equals(frame.local.get(index))) {
                    count++;
                    if (replace) {
                        frame.local.set(index, ACCUMULATOR);
                    }
                }
            }
        }
        return count;
    }

    private static int countFrameTypes(
            MethodNode method, String internalName) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof FrameNode frame)
                    || frame.local == null) {
                continue;
            }
            for (Object local : frame.local) {
                if (internalName.equals(local)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void requireVariableLoad(
            AbstractInsnNode node, int variable, String label) {
        if (!(node instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != variable) {
            throw new PatchException(
                    "OGG PCM " + label + " 结构变化");
        }
    }

    private static void requireOpcode(
            AbstractInsnNode node, int opcode, String label) {
        if (node == null || node.getOpcode() != opcode) {
            throw new PatchException(
                    "OGG PCM " + label + " 结构变化");
        }
    }

    private record OriginalShape(
            TypeInsnNode allocation,
            MethodInsnNode constructor,
            MethodInsnNode write,
            MethodInsnNode toByteArray,
            MethodInsnNode rewind,
            FieldInsnNode resultField) {}
}

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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把启动声音预载循环从逐字节读取改为一次搬运一个已解码 PCM 块。
 *
 * <p>本 patch 只重定向一次调用；批量读取语义位于经过单测的
 * {@code DecodedPcmBuffer.readFrom} 和 {@code PcmBulkReader}。
 */
public final class DecodedPcmBulkReadPatch implements JarPatch {
    private static final String TARGET_CLASS = "sound/O0oO.class";
    private static final String TARGET_DESC =
            "(Ljava/io/InputStream;)Lsound/G;";
    private static final String ACCUMULATOR =
            "org/fossic/starsector/optimization/DecodedPcmBuffer";
    private static final String ACCESS_DESC =
            "Lorg/fossic/starsector/optimization/PcmDecoderAccess;";
    private static final String READ_FROM_DESC = "(" + ACCESS_DESC + ")I";

    @Override
    public String id() {
        return "ogg-pcm-bulk-preload-read";
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
                    "OGG PCM 启动预载方法匹配数异常: "
                            + matches.size() + "（预期 1）");
        }

        MethodNode method = matches.get(0);
        OriginalCalls original = verifyOriginalCalls(method);
        original.read().owner = ACCUMULATOR;
        original.read().name = "readFrom";
        original.read().desc = READ_FROM_DESC;
        method.instructions.set(original.write(), new InsnNode(Opcodes.POP));
        verifyPatchedCalls(method);

        return PatchResult.of(
                id(),
                context.classPath(),
                1,
                1,
                1,
                "replace the startup preload scalar F.read/write pair "
                        + "with DecodedPcmBuffer.readFrom; preserve loop, "
                        + "completion check and close");
    }

    private static OriginalCalls verifyOriginalCalls(MethodNode method) {
        MethodInsnNode read = null;
        MethodInsnNode write = null;
        int reads = 0;
        int writes = 0;
        int bulkReads = 0;
        int doneChecks = 0;
        int closes = 0;

        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode call)) {
                continue;
            }
            if ("sound/F".equals(call.owner)
                    && "read".equals(call.name)
                    && "()I".equals(call.desc)) {
                read = call;
                reads++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "write".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                write = call;
                writes++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "readFrom".equals(call.name)
                    && READ_FROM_DESC.equals(call.desc)) {
                bulkReads++;
            } else if ("sound/F".equals(call.owner)
                    && "o00000".equals(call.name)
                    && "()Z".equals(call.desc)) {
                doneChecks++;
            } else if ("sound/F".equals(call.owner)
                    && "close".equals(call.name)
                    && "()V".equals(call.desc)) {
                closes++;
            }
        }

        if (reads != 1
                || writes != 1
                || bulkReads != 0
                || doneChecks != 1
                || closes != 1
                || read == null
                || write == null
                || AsmUtil.nextReal(read) != write) {
            throw new PatchException(
                    "OGG PCM 启动预载原始调用结构变化: read="
                            + reads + ", write=" + writes
                            + ", bulk=" + bulkReads
                            + ", done=" + doneChecks
                            + ", close=" + closes);
        }

        requireLoad(AsmUtil.previousReal(read), 3, "decoder");
        requireLoad(
                AsmUtil.previousReal(AsmUtil.previousReal(read)),
                2,
                "accumulator");
        return new OriginalCalls(read, write);
    }

    private static void verifyPatchedCalls(MethodNode method) {
        int scalarReads = 0;
        int writes = 0;
        int bulkReads = 0;
        int doneChecks = 0;
        int closes = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof MethodInsnNode call)) {
                continue;
            }
            if ("sound/F".equals(call.owner)
                    && "read".equals(call.name)
                    && "()I".equals(call.desc)) {
                scalarReads++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "write".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                writes++;
            } else if (ACCUMULATOR.equals(call.owner)
                    && "readFrom".equals(call.name)
                    && READ_FROM_DESC.equals(call.desc)) {
                bulkReads++;
            } else if ("sound/F".equals(call.owner)
                    && "o00000".equals(call.name)
                    && "()Z".equals(call.desc)) {
                doneChecks++;
            } else if ("sound/F".equals(call.owner)
                    && "close".equals(call.name)
                    && "()V".equals(call.desc)) {
                closes++;
            }
        }
        if (scalarReads != 0
                || writes != 0
                || bulkReads != 1
                || doneChecks != 1
                || closes != 1) {
            throw new PatchException(
                    "OGG PCM 批量读取桥接验证失败: scalar="
                            + scalarReads + ", write=" + writes
                            + ", bulk=" + bulkReads
                            + ", done=" + doneChecks
                            + ", close=" + closes);
        }
    }

    private static void requireLoad(
            AbstractInsnNode node, int variable, String label) {
        if (!(node instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != variable) {
            throw new PatchException(
                    "OGG PCM " + label + " load 结构变化");
        }
    }

    private record OriginalCalls(
            MethodInsnNode read, MethodInsnNode write) {}
}

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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * 把原版单预读 Thread 的生命周期桥接到可单测的并行图片预读 helper。
 *
 * <p>路径领取、声音优先级、并发上限、异常回退均位于 optimization 包；本 patch 只传入
 * 原版队列、私有 loader 标识及原 Runnable 作为失败回退，并保留原 primary Thread 字段。
 */
public final class ParallelImagePreloadPatch implements JarPatch {
    private static final String CLASS_PATH = "com/fs/graphics/L.class";
    private static final String OWNER = "com/fs/graphics/L";
    private static final String FALLBACK_WORKER = "com/fs/graphics/L$1";
    private static final String HELPER =
            "org/fossic/starsector/optimization/ParallelImagePreloader";
    private static final String RESULT_COORDINATOR =
            "org/fossic/starsector/optimization/PreloadResultCoordinator";

    private static final String IMAGE_QUEUE = "Õ00000";
    private static final String IMAGE_RESULTS = "void";
    private static final String IMAGE_SENTINEL = "String";
    private static final String SOUND_QUEUE = "õ00000";
    private static final String SOUND_RESULTS = "Ò00000";
    private static final String SOUND_SENTINEL = "Ö00000";
    private static final String LOGGER = "Ó00000";
    private static final String PRIMARY_THREAD = "super";
    private static final String SOUND_LOADER = "Ô00000";
    private static final String IMAGE_LOADER = "o00000";

    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String MAP_DESC = "Ljava/util/Map;";
    private static final String IMAGE_DESC =
            "Ljava/awt/image/BufferedImage;";
    private static final String LOGGER_DESC =
            "Lorg/apache/log4j/Logger;";
    private static final String THREAD_DESC = "Ljava/lang/Thread;";
    private static final String START_DESC =
            "(Ljava/lang/Runnable;"
                    + LIST_DESC + MAP_DESC + "[B"
                    + LIST_DESC + MAP_DESC + IMAGE_DESC
                    + "Ljava/lang/Class;Ljava/lang/String;"
                    + "Ljava/lang/String;Ljava/lang/Object;)"
                    + THREAD_DESC;

    @Override
    public String id() {
        return "parallel-image-preload";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.PARALLEL_IMAGE_PRELOAD;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(CLASS_PATH);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        MethodNode start = requireStaticMethod(
                classNode, "o00000", "()V");
        MethodNode shutdown = requireStaticMethod(
                classNode, "Ò00000", "()V");
        verifyOriginalStart(start);
        verifyCoordinationShutdown(shutdown);

        replaceStart(start);
        insertStop(shutdown);

        if (countCall(start, HELPER, "start", START_DESC) != 1
                || countCall(
                        start,
                        "java/lang/Thread",
                        "start",
                        "()V") != 0
                || countStaticField(
                        start,
                        Opcodes.PUTSTATIC,
                        PRIMARY_THREAD,
                        THREAD_DESC) != 1
                || countCall(shutdown, HELPER, "stop", "()V") != 1
                || countCall(
                        shutdown,
                        RESULT_COORDINATOR,
                        "clear",
                        "()V") != 1) {
            throw new PatchException("并行图片预读 bridge 验证失败");
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                2,
                2,
                2,
                "bridge preload start/stop to bounded image workers");
    }

    private static void verifyOriginalStart(MethodNode start) {
        if (countType(start, Opcodes.NEW, "java/lang/Thread") != 1
                || countType(start, Opcodes.NEW, FALLBACK_WORKER) != 1
                || countCall(
                        start,
                        FALLBACK_WORKER,
                        "<init>",
                        "()V") != 1
                || countCall(
                        start,
                        "java/lang/Thread",
                        "<init>",
                        "(Ljava/lang/Runnable;)V") != 1
                || countStaticField(
                        start,
                        Opcodes.PUTSTATIC,
                        PRIMARY_THREAD,
                        THREAD_DESC) != 1
                || countCall(
                        start,
                        "java/lang/Thread",
                        "start",
                        "()V") != 1
                || countCall(start, HELPER, "start", START_DESC) != 0) {
            throw new PatchException(
                    "并行图片预读 start 原始结构变化");
        }
    }

    private static void verifyCoordinationShutdown(MethodNode shutdown) {
        if (countCall(
                        shutdown,
                        "java/lang/Thread",
                        "interrupt",
                        "()V") != 1
                || countCall(
                        shutdown,
                        RESULT_COORDINATOR,
                        "clear",
                        "()V") != 1
                || countCall(shutdown, HELPER, "stop", "()V") != 0) {
            throw new PatchException(
                    "并行图片预读 shutdown 前置结构变化");
        }
    }

    private static void replaceStart(MethodNode start) {
        InsnList replacement = new InsnList();
        replacement.add(new TypeInsnNode(Opcodes.NEW, FALLBACK_WORKER));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                FALLBACK_WORKER,
                "<init>",
                "()V",
                false));
        replacement.add(getStatic(SOUND_QUEUE, LIST_DESC));
        replacement.add(getStatic(SOUND_RESULTS, MAP_DESC));
        replacement.add(getStatic(SOUND_SENTINEL, "[B"));
        replacement.add(getStatic(IMAGE_QUEUE, LIST_DESC));
        replacement.add(getStatic(IMAGE_RESULTS, MAP_DESC));
        replacement.add(getStatic(IMAGE_SENTINEL, IMAGE_DESC));
        replacement.add(new LdcInsnNode(Type.getObjectType(OWNER)));
        replacement.add(new LdcInsnNode(SOUND_LOADER));
        replacement.add(new LdcInsnNode(IMAGE_LOADER));
        replacement.add(getStatic(LOGGER, LOGGER_DESC));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "start",
                START_DESC,
                false));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                OWNER,
                PRIMARY_THREAD,
                THREAD_DESC));
        replacement.add(new InsnNode(Opcodes.RETURN));

        start.instructions.clear();
        start.tryCatchBlocks.clear();
        if (start.localVariables != null) {
            start.localVariables.clear();
        }
        start.instructions.add(replacement);
        start.maxStack = 11;
        start.maxLocals = 0;
    }

    private static void insertStop(MethodNode shutdown) {
        List<MethodInsnNode> interrupts = AsmUtil.instructions(shutdown)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> "java/lang/Thread".equals(call.owner)
                        && "interrupt".equals(call.name)
                        && "()V".equals(call.desc))
                .toList();
        if (interrupts.size() != 1) {
            throw new PatchException(
                    "并行图片预读未唯一定位 primary interrupt");
        }
        shutdown.instructions.insert(
                interrupts.get(0),
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "stop",
                        "()V",
                        false));
    }

    private static FieldInsnNode getStatic(String name, String desc) {
        return new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, name, desc);
    }

    private static MethodNode requireStaticMethod(
            ClassNode classNode, String name, String desc) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name)
                        && desc.equals(method.desc)
                        && (method.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "并行图片预读方法匹配数异常: "
                            + name + desc + " = " + matches.size());
        }
        return matches.get(0);
    }

    private static int countCall(
            MethodNode method, String owner, String name, String desc) {
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

    private static int countType(
            MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == opcode
                    && type.equals(typeInsn.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int countStaticField(
            MethodNode method, int opcode, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && OWNER.equals(field.owner)
                    && name.equals(field.name)
                    && desc.equals(field.desc)) {
                count++;
            }
        }
        return count;
    }
}

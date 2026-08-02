package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把原版图片/声音预读桥接到可单测的并发协调器，同时保留 10 ms 轮询节奏。
 *
 * <p>本 patch 只改写固定调用点；排队计数、轮询等待、worker 通知和重复路径语义全部位于
 * {@code PreloadResultCoordinator}。
 */
public final class PreloadResultCoordinatorPatch implements JarPatch {
    private static final String OUTER_CLASS = "com/fs/graphics/L.class";
    private static final String WORKER_CLASS = "com/fs/graphics/L$1.class";
    private static final String OUTER = "com/fs/graphics/L";
    private static final String WORKER = "com/fs/graphics/L$1";
    private static final String HELPER =
            "org/fossic/starsector/optimization/PreloadResultCoordinator";
    private static final String LIST = "java/util/List";
    private static final String MAP = "java/util/Map";
    private static final String THREAD = "java/lang/Thread";
    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String MAP_DESC = "Ljava/util/Map;";
    private static final String IMAGE_DESC =
            "Ljava/awt/image/BufferedImage;";
    private static final String SOUND_DESC = "[B";

    private static final String IMAGE_QUEUE = "Õ00000";
    private static final String IMAGE_RESULTS = "void";
    private static final String IMAGE_SENTINEL = "String";
    private static final String SOUND_QUEUE = "õ00000";
    private static final String SOUND_RESULTS = "Ò00000";
    private static final String SOUND_SENTINEL = "Ö00000";

    private static final String ENQUEUE_DESC =
            "(Ljava/util/List;Ljava/lang/String;)V";
    private static final String AWAIT_SOUND_DESC =
            "(Ljava/lang/String;Ljava/util/Map;[B)[B";
    private static final String AWAIT_IMAGE_DESC =
            "(Ljava/lang/String;Ljava/util/Map;"
                    + IMAGE_DESC + ")" + IMAGE_DESC;
    private static final String SOUND_UPDATE_DESC =
            "(Ljava/util/Map;Ljava/lang/String;[B)[B";
    private static final String IMAGE_UPDATE_DESC =
            "(Ljava/util/Map;Ljava/lang/String;"
                    + IMAGE_DESC + ")" + IMAGE_DESC;
    private static final String SOUND_FAILED_DESC =
            "(Ljava/util/Map;Ljava/lang/String;)[B";
    private static final String IMAGE_FAILED_DESC =
            "(Ljava/util/Map;Ljava/lang/String;)" + IMAGE_DESC;

    @Override
    public String id() {
        return "preload-result-coordination";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(OUTER_CLASS, WORKER_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        return switch (classNode.name) {
            case OUTER -> patchOuter(classNode, context);
            case WORKER -> patchWorker(classNode, context);
            default -> throw new PatchException(
                    "预读协调 patch 收到意外类: " + classNode.name);
        };
    }

    private PatchResult patchOuter(
            ClassNode classNode, PatchContext context) {
        MethodNode enqueueImage =
                requireStaticMethod(classNode, "return", "(Ljava/lang/String;)V");
        MethodNode enqueueSound =
                requireStaticMethod(classNode, "Object", "(Ljava/lang/String;)V");
        MethodNode awaitSound =
                requireStaticMethod(classNode, "Ò00000", "(Ljava/lang/String;)[B");
        MethodNode awaitImage = requireStaticMethod(
                classNode,
                "Õ00000",
                "(Ljava/lang/String;)" + IMAGE_DESC);
        MethodNode shutdown =
                requireStaticMethod(classNode, "Ò00000", "()V");

        verifyEnqueue(enqueueImage, IMAGE_QUEUE);
        verifyEnqueue(enqueueSound, SOUND_QUEUE);
        verifyAwait(
                awaitSound,
                SOUND_QUEUE,
                SOUND_RESULTS,
                SOUND_SENTINEL,
                SOUND_DESC);
        verifyAwait(
                awaitImage,
                IMAGE_QUEUE,
                IMAGE_RESULTS,
                IMAGE_SENTINEL,
                IMAGE_DESC);
        verifyShutdown(shutdown);

        replaceEnqueue(enqueueImage, IMAGE_QUEUE, "queueImage");
        replaceEnqueue(enqueueSound, SOUND_QUEUE, "queueSound");
        replaceAwait(
                awaitSound,
                SOUND_RESULTS,
                SOUND_SENTINEL,
                SOUND_DESC,
                "awaitSound",
                AWAIT_SOUND_DESC);
        replaceAwait(
                awaitImage,
                IMAGE_RESULTS,
                IMAGE_SENTINEL,
                IMAGE_DESC,
                "awaitImage",
                AWAIT_IMAGE_DESC);
        insertClear(shutdown);

        String[][] expectedCalls = {
                {"queueImage", ENQUEUE_DESC},
                {"queueSound", ENQUEUE_DESC},
                {"awaitSound", AWAIT_SOUND_DESC},
                {"awaitImage", AWAIT_IMAGE_DESC},
                {"clear", "()V"}
        };
        int verified = verifyHelperCalls(classNode, expectedCalls);
        if (countCall(awaitSound, THREAD, "sleep", "(J)V") != 0
                || countCall(awaitImage, THREAD, "sleep", "(J)V") != 0
                || countCall(
                        awaitSound,
                        LIST,
                        "contains",
                        "(Ljava/lang/Object;)Z") != 0
                || countCall(
                        awaitImage,
                        LIST,
                        "contains",
                        "(Ljava/lang/Object;)Z") != 0) {
            throw new PatchException("预读 getter 中仍残留轮询指令");
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                5,
                5,
                verified,
                "bridge enqueue/poll/shutdown to "
                        + "PreloadResultCoordinator");
    }

    private PatchResult patchWorker(
            ClassNode classNode, PatchContext context) {
        MethodNode run = requireInstanceMethod(classNode, "run", "()V");
        List<WorkerMapCall> soundCalls =
                workerMapCalls(run, SOUND_RESULTS);
        List<WorkerMapCall> imageCalls =
                workerMapCalls(run, IMAGE_RESULTS);
        verifyWorkerCalls(
                soundCalls, SOUND_SENTINEL, SOUND_DESC, "sound");
        verifyWorkerCalls(
                imageCalls, IMAGE_SENTINEL, IMAGE_DESC, "image");

        replaceWorkerCalls(
                soundCalls,
                "soundStarted",
                "soundCompleted",
                "soundFailed",
                SOUND_UPDATE_DESC,
                SOUND_FAILED_DESC);
        replaceWorkerCalls(
                imageCalls,
                "imageStarted",
                "imageCompleted",
                "imageFailed",
                IMAGE_UPDATE_DESC,
                IMAGE_FAILED_DESC);

        String[][] expectedCalls = {
                {"soundStarted", SOUND_UPDATE_DESC},
                {"soundCompleted", SOUND_UPDATE_DESC},
                {"soundFailed", SOUND_FAILED_DESC},
                {"imageStarted", IMAGE_UPDATE_DESC},
                {"imageCompleted", IMAGE_UPDATE_DESC},
                {"imageFailed", IMAGE_FAILED_DESC}
        };
        int verified = verifyHelperCalls(classNode, expectedCalls);
        if (countCall(
                        run,
                        MAP,
                        "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)"
                                + "Ljava/lang/Object;") != 0
                || countCall(
                        run,
                        MAP,
                        "remove",
                        "(Ljava/lang/Object;)Ljava/lang/Object;") != 0) {
            throw new PatchException(
                    "预读 worker 中仍残留结果 map 的 put/remove");
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                6,
                6,
                verified,
                "bridge worker state transitions to "
                        + "PreloadResultCoordinator");
    }

    private static void verifyEnqueue(
            MethodNode method, String queueField) {
        List<AbstractInsnNode> instructions = realInstructions(method);
        if (instructions.size() != 5
                || !isStaticField(
                        instructions.get(0),
                        queueField,
                        LIST_DESC)
                || !(instructions.get(1) instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0
                || !isInterfaceCall(
                        instructions.get(2),
                        LIST,
                        "add",
                        "(Ljava/lang/Object;)Z")
                || instructions.get(3).getOpcode() != Opcodes.POP
                || instructions.get(4).getOpcode() != Opcodes.RETURN) {
            throw new PatchException(
                    "预读入队方法原始结构变化: "
                            + method.name + method.desc);
        }
    }

    private static void verifyAwait(
            MethodNode method,
            String queueField,
            String resultsField,
            String sentinelField,
            String resultDesc) {
        int queueReads = countStaticField(
                method, queueField, LIST_DESC);
        int resultReads = countStaticField(
                method, resultsField, MAP_DESC);
        int sentinelReads = countStaticField(
                method, sentinelField, resultDesc);
        int contains = countCall(
                method,
                LIST,
                "contains",
                "(Ljava/lang/Object;)Z");
        int containsKey = countCall(
                method,
                MAP,
                "containsKey",
                "(Ljava/lang/Object;)Z");
        int gets = countCall(
                method,
                MAP,
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        int removes = countCall(
                method,
                MAP,
                "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        int sleeps = countCall(method, THREAD, "sleep", "(J)V");
        long interruptedHandlers = method.tryCatchBlocks.stream()
                .filter(block -> "java/lang/InterruptedException"
                        .equals(block.type))
                .count();
        if (queueReads != 1
                || resultReads != 3
                || sentinelReads != 1
                || contains != 1
                || containsKey != 1
                || gets != 1
                || removes != 1
                || sleeps != 1
                || interruptedHandlers != 1) {
            throw new PatchException(
                    "预读等待方法原始结构变化: "
                            + method.name + method.desc
                            + ", queue=" + queueReads
                            + ", results=" + resultReads
                            + ", sentinel=" + sentinelReads
                            + ", contains=" + contains
                            + ", containsKey=" + containsKey
                            + ", get=" + gets
                            + ", remove=" + removes
                            + ", sleep=" + sleeps
                            + ", interruptHandlers="
                            + interruptedHandlers);
        }
    }

    private static void verifyShutdown(MethodNode method) {
        if (countCall(method, THREAD, "interrupt", "()V") != 1
                || countCall(method, MAP, "clear", "()V") != 2
                || realInstructions(method).stream()
                        .filter(node -> node.getOpcode() == Opcodes.RETURN)
                        .count() != 1) {
            throw new PatchException("预读 shutdown 原始结构变化");
        }
    }

    private static void replaceEnqueue(
            MethodNode method, String queueField, String helperMethod) {
        InsnList replacement = new InsnList();
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                OUTER,
                queueField,
                LIST_DESC));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                helperMethod,
                ENQUEUE_DESC,
                false));
        replacement.add(new InsnNode(Opcodes.RETURN));
        replaceBody(method, replacement, 2, 1);
    }

    private static void replaceAwait(
            MethodNode method,
            String resultsField,
            String sentinelField,
            String resultDesc,
            String helperMethod,
            String helperDesc) {
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                OUTER,
                resultsField,
                MAP_DESC));
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                OUTER,
                sentinelField,
                resultDesc));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                helperMethod,
                helperDesc,
                false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, replacement, 3, 1);
    }

    private static void insertClear(MethodNode shutdown) {
        MethodInsnNode interrupt = realInstructions(shutdown)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> THREAD.equals(call.owner))
                .filter(call -> "interrupt".equals(call.name))
                .filter(call -> "()V".equals(call.desc))
                .findFirst()
                .orElseThrow(() -> new PatchException(
                        "预读 shutdown 未找到 primary interrupt"));
        // 先推进 generation、唤醒等待者，再清两个结果 map。这样即使旧
        // decoder 忽略 interrupt 并迟到完成，也只能被 coordinator 丢弃，
        // 不会在 Map.clear() 后重新写回旧结果。
        shutdown.instructions.insert(
                interrupt,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "clear",
                        "()V",
                        false));
    }

    private static void replaceBody(
            MethodNode method,
            InsnList replacement,
            int maxStack,
            int maxLocals) {
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
        method.instructions.add(replacement);
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static List<WorkerMapCall> workerMapCalls(
            MethodNode run, String resultsField) {
        List<WorkerMapCall> calls = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(run)) {
            if (!(node instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !MAP.equals(call.owner)) {
                continue;
            }
            boolean put = "put".equals(call.name)
                    && ("(Ljava/lang/Object;Ljava/lang/Object;)"
                    + "Ljava/lang/Object;").equals(call.desc);
            boolean remove = "remove".equals(call.name)
                    && "(Ljava/lang/Object;)Ljava/lang/Object;"
                    .equals(call.desc);
            if (!put && !remove) {
                continue;
            }
            FieldInsnNode mapRead = precedingResultsField(call);
            if (resultsField.equals(mapRead.name)) {
                calls.add(new WorkerMapCall(call, put));
            }
        }
        return calls;
    }

    private static FieldInsnNode precedingResultsField(
            MethodInsnNode call) {
        AbstractInsnNode current = call;
        for (int i = 0; i < 4; i++) {
            current = AsmUtil.previousReal(current);
            if (current instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && OUTER.equals(field.owner)
                    && MAP_DESC.equals(field.desc)) {
                return field;
            }
        }
        throw new PatchException(
                "预读 worker 的 Map." + call.name
                        + " 前未找到结果 map 字段");
    }

    private static void verifyWorkerCalls(
            List<WorkerMapCall> calls,
            String sentinelField,
            String resultDesc,
            String label) {
        if (calls.size() != 3
                || !calls.get(0).put()
                || !calls.get(1).put()
                || calls.get(2).put()) {
            throw new PatchException(
                    "预读 worker " + label
                            + " 状态调用结构变化: " + calls.size());
        }
        AbstractInsnNode sentinel =
                AsmUtil.previousReal(calls.get(0).call());
        if (!isStaticField(sentinel, sentinelField, resultDesc)) {
            throw new PatchException(
                    "预读 worker " + label
                            + " 首次 put 未写入 loading sentinel");
        }
        if (!(AsmUtil.previousReal(calls.get(1).call())
                instanceof VarInsnNode resultLoad)
                || resultLoad.getOpcode() != Opcodes.ALOAD) {
            throw new PatchException(
                    "预读 worker " + label
                            + " 完成 put 未写入局部结果");
        }
    }

    private static void replaceWorkerCalls(
            List<WorkerMapCall> calls,
            String started,
            String completed,
            String failed,
            String updateDesc,
            String failedDesc) {
        calls.get(0).call().setOpcode(Opcodes.INVOKESTATIC);
        calls.get(0).call().owner = HELPER;
        calls.get(0).call().name = started;
        calls.get(0).call().desc = updateDesc;
        calls.get(0).call().itf = false;

        calls.get(1).call().setOpcode(Opcodes.INVOKESTATIC);
        calls.get(1).call().owner = HELPER;
        calls.get(1).call().name = completed;
        calls.get(1).call().desc = updateDesc;
        calls.get(1).call().itf = false;

        calls.get(2).call().setOpcode(Opcodes.INVOKESTATIC);
        calls.get(2).call().owner = HELPER;
        calls.get(2).call().name = failed;
        calls.get(2).call().desc = failedDesc;
        calls.get(2).call().itf = false;
    }

    private static MethodNode requireStaticMethod(
            ClassNode classNode, String name, String desc) {
        return requireMethod(classNode, name, desc, true);
    }

    private static MethodNode requireInstanceMethod(
            ClassNode classNode, String name, String desc) {
        return requireMethod(classNode, name, desc, false);
    }

    private static MethodNode requireMethod(
            ClassNode classNode,
            String name,
            String desc,
            boolean requireStatic) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name)
                        && desc.equals(method.desc)
                        && ((method.access & Opcodes.ACC_STATIC) != 0)
                        == requireStatic)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "预读方法匹配数异常: " + classNode.name + "."
                            + name + desc + " = " + matches.size());
        }
        return matches.get(0);
    }

    private static int verifyHelperCalls(
            ClassNode classNode, String[][] expectedCalls) {
        int verified = 0;
        for (String[] expected : expectedCalls) {
            int count = AsmUtil.countMethodCall(
                    classNode,
                    HELPER,
                    expected[0],
                    expected[1]);
            if (count != 1) {
                throw new PatchException(
                        "预读 helper 调用验证失败: "
                                + expected[0] + expected[1]
                                + " = " + count);
            }
            verified++;
        }
        return verified;
    }

    private static int countStaticField(
            MethodNode method, String fieldName, String desc) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (isStaticField(node, fieldName, desc)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isStaticField(
            AbstractInsnNode node, String fieldName, String desc) {
        return node instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && OUTER.equals(field.owner)
                && fieldName.equals(field.name)
                && desc.equals(field.desc);
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

    private static boolean isInterfaceCall(
            AbstractInsnNode node, String owner, String name, String desc) {
        return node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEINTERFACE
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc)
                && call.itf;
    }

    private static List<AbstractInsnNode> realInstructions(
            MethodNode method) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() >= 0)
                .toList();
    }

    private record WorkerMapCall(
            MethodInsnNode call, boolean put) {
    }
}

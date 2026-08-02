package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 让弹体、武器、舰体、skin 和 variant 的原版 JSON 读取/合并有界并行，
 * 保持对象构造、跨规格查询和注册循环原样串行。
 */
public final class ParallelSpecParsePatch implements JarPatch {
    private static final String PATCH_ID = "parallel-spec-json-parse";
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/SpecStore.class";
    private static final String SPEC_STORE =
            "com/fs/starfarer/loading/SpecStore";
    private static final String WEAPON_LOADER =
            "com/fs/starfarer/loading/WeaponSpecLoader";
    private static final String HULL_LOADER =
            "com/fs/starfarer/loading/ShipHullSpecLoader";
    private static final String LOADING_UTILS =
            "com/fs/starfarer/loading/LoadingUtils";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OrderedParallelLoader";
    private static final String JSON_LOAD_DESC =
            "(Ljava/lang/String;)Lorg/json/JSONObject;";
    private static final String PATH_LIST_DESC =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;";
    private static final String BEGIN_DESC =
            "(Ljava/util/List;Ljava/lang/invoke/MethodHandle;)V";
    private static final String LOAD_DESC =
            "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)"
                    + "Ljava/lang/Object;";

    @Override
    public String id() {
        return PATCH_ID;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(
                TARGET_CLASS,
                WEAPON_LOADER + ".class",
                HULL_LOADER + ".class",
                LOADING_UTILS + ".class");
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        return switch (classNode.name) {
            case SPEC_STORE -> applyVariants(classNode, context);
            case WEAPON_LOADER -> applyLoopLoaders(
                    classNode,
                    context,
                    Map.of(
                            "Loading projectile [", 2,
                            "Loading weapon [", 2),
                    2);
            case HULL_LOADER -> applyLoopLoaders(
                    classNode,
                    context,
                    Map.of(
                            "Loading ship hull [", 1,
                            "Loading hull skin [", 1),
                    2);
            case LOADING_UTILS -> applyLoadingUtilsLogs(
                    classNode, context);
            default -> throw new PatchException(
                    "并行规格解析收到未知类: " + classNode.name);
        };
    }

    private static PatchResult applyLoadingUtilsLogs(
            ClassNode classNode, PatchContext context) {
        List<MethodInsnNode> infoCalls = classNode.methods.stream()
                .flatMap(method -> AsmUtil.instructions(method).stream())
                .filter(node -> node instanceof MethodInsnNode)
                .map(node -> (MethodInsnNode) node)
                .filter(call -> "org/apache/log4j/Logger".equals(call.owner)
                        && "info".equals(call.name)
                        && "(Ljava/lang/Object;)V".equals(call.desc)
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL)
                .toList();
        if (infoCalls.size() != 15) {
            throw new PatchException(
                    "LoadingUtils INFO 调用数异常: " + infoCalls.size()
                            + "（预期 15）");
        }
        for (MethodInsnNode call : infoCalls) {
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = HELPER;
            call.name = "info";
            call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)V";
            call.itf = false;
        }
        int verified = AsmUtil.countMethodCall(
                classNode,
                HELPER,
                "info",
                "(Ljava/lang/Object;Ljava/lang/Object;)V");
        if (verified != 15) {
            throw new PatchException(
                    "LoadingUtils INFO bridge 验证失败: " + verified);
        }
        return PatchResult.of(
                PATCH_ID,
                context.classPath(),
                15,
                15,
                verified,
                "capture worker LoadingUtils INFO and replay in input order");
    }

    private static PatchResult applyVariants(
            ClassNode classNode, PatchContext context) {
        MethodNode variants = requireVariantMethod(classNode);
        MethodInsnNode jsonLoad = uniqueCall(
                variants, LOADING_UTILS, JSON_LOAD_DESC,
                "variant JSON load");
        MethodInsnNode pathList = uniqueStoredCall(
                variants, LOADING_UTILS, PATH_LIST_DESC,
                "initial variant path list");
        VarInsnNode listStore = requireStoreAfter(pathList);
        MethodInsnNode headingLog = requireInfoAfter(
                variants, "Loading ship & fighter variants");
        MethodInsnNode postProcess = uniqueCall(
                variants,
                "com/fs/starfarer/loading/B",
                "()Ljava/util/List;",
                "variant registry post-processing list");

        Handle loaderHandle = new Handle(
                Opcodes.H_INVOKESTATIC,
                jsonLoad.owner,
                jsonLoad.name,
                jsonLoad.desc,
                false);

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, listStore.var));
        begin.add(new LdcInsnNode(loaderHandle));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, "begin", BEGIN_DESC, false));
        LabelNode tryStart = new LabelNode();
        begin.add(tryStart);
        variants.instructions.insert(headingLog, begin);

        variants.instructions.insertBefore(
                jsonLoad, new LdcInsnNode(loaderHandle));
        jsonLoad.owner = HELPER;
        jsonLoad.name = "load";
        jsonLoad.desc = LOAD_DESC;
        variants.instructions.insert(
                jsonLoad, new TypeInsnNode(
                        Opcodes.CHECKCAST, "org/json/JSONObject"));

        InsnList finish = new InsnList();
        finish.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "finish",
                "()V",
                false));
        LabelNode tryEnd = new LabelNode();
        finish.add(tryEnd);
        variants.instructions.insertBefore(postProcess, finish);
        appendAbortHandler(variants, tryStart, tryEnd);
        variants.maxStack = Math.max(variants.maxStack, 2);

        int beginCalls = countCalls(variants, HELPER, "begin", BEGIN_DESC);
        int loadCalls = countCalls(variants, HELPER, "load", LOAD_DESC);
        int finishCalls = countCalls(variants, HELPER, "finish", "()V");
        int abortCalls = countCalls(variants, HELPER, "abort", "()V");
        int oldJsonCalls = countCalls(
                variants, LOADING_UTILS, null, JSON_LOAD_DESC);
        if (beginCalls != 1 || loadCalls != 1 || finishCalls != 1
                || abortCalls != 1 || oldJsonCalls != 0) {
            throw new PatchException(
                    "variant 并行 JSON 验证失败: begin=" + beginCalls
                            + ", load=" + loadCalls
                            + ", finish=" + finishCalls
                            + ", abort=" + abortCalls
                            + ", oldJson=" + oldJsonCalls);
        }

        return PatchResult.of(
                PATCH_ID, context.classPath(), 4, 4, 4,
                "prefetch original LoadingUtils JSON results in a bounded "
                        + "stage and consume them in the original variant loop");
    }

    private static PatchResult applyLoopLoaders(
            ClassNode classNode,
            PatchContext context,
            Map<String, Integer> loopMessages,
            int expectedJsonBridges) {
        LinkedHashMap<MethodNode, List<MethodInsnNode>> loops =
                new LinkedHashMap<>();
        int expectedStages = 0;
        for (Map.Entry<String, Integer> entry : loopMessages.entrySet()) {
            MethodNode method = requireMethodWithMessage(
                    classNode, entry.getKey());
            List<MethodInsnNode> directCalls = calls(
                    method, classNode.name, null, "(Ljava/lang/String;)V");
            if (directCalls.size() != entry.getValue()) {
                throw new PatchException(
                        classNode.name + " 的 " + entry.getKey()
                                + " 直接 loader 调用数异常: "
                                + directCalls.size() + "（预期 "
                                + entry.getValue() + "）");
            }
            loops.put(method, directCalls);
            expectedStages += directCalls.size();
        }

        LinkedHashMap<String, Handle> handlesByDirectMethod =
                new LinkedHashMap<>();
        for (List<MethodInsnNode> directCalls : loops.values()) {
            for (MethodInsnNode directCall : directCalls) {
                String key = directCall.name + directCall.desc;
                if (handlesByDirectMethod.containsKey(key)) {
                    continue;
                }
                MethodNode directMethod = requireMethod(
                        classNode, directCall.name, directCall.desc);
                MethodInsnNode jsonLoad = uniqueCall(
                        directMethod, LOADING_UTILS, JSON_LOAD_DESC,
                        classNode.name + "." + directCall.name
                                + " JSON load");
                Handle handle = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        jsonLoad.owner,
                        jsonLoad.name,
                        jsonLoad.desc,
                        false);
                handlesByDirectMethod.put(key, handle);
                bridgeJsonLoad(directMethod, jsonLoad, handle);
            }
        }
        if (handlesByDirectMethod.size() != expectedJsonBridges) {
            throw new PatchException(
                    classNode.name + " JSON bridge 方法数异常: "
                            + handlesByDirectMethod.size() + "（预期 "
                            + expectedJsonBridges + "）");
        }

        for (Map.Entry<MethodNode, List<MethodInsnNode>> loop
                : loops.entrySet()) {
            MethodNode method = loop.getKey();
            for (MethodInsnNode directCall : loop.getValue()) {
                Handle handle = handlesByDirectMethod.get(
                        directCall.name + directCall.desc);
                insertStageAroundLoop(method, directCall, handle);
            }
            method.maxStack = Math.max(method.maxStack, 2);
        }

        int beginCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "begin", BEGIN_DESC);
        int loadCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "load", LOAD_DESC);
        int finishCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "finish", "()V");
        int abortCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "abort", "()V");
        if (beginCalls != expectedStages
                || loadCalls != expectedJsonBridges
                || finishCalls != expectedStages
                || abortCalls != expectedStages) {
            throw new PatchException(
                    classNode.name + " 并行 JSON 验证失败: begin="
                            + beginCalls + ", load=" + loadCalls
                            + ", finish=" + finishCalls
                            + ", abort=" + abortCalls);
        }

        int applied = expectedStages * 3 + expectedJsonBridges;
        return PatchResult.of(
                PATCH_ID, context.classPath(), applied, applied, applied,
                "prefetch original LoadingUtils JSON for "
                        + expectedStages + " ordered loader loops");
    }

    private static void bridgeJsonLoad(
            MethodNode method, MethodInsnNode jsonLoad, Handle handle) {
        method.instructions.insertBefore(jsonLoad, new LdcInsnNode(handle));
        jsonLoad.owner = HELPER;
        jsonLoad.name = "load";
        jsonLoad.desc = LOAD_DESC;
        method.instructions.insert(
                jsonLoad,
                new TypeInsnNode(Opcodes.CHECKCAST, "org/json/JSONObject"));
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static void insertStageAroundLoop(
            MethodNode method,
            MethodInsnNode directCall,
            Handle handle) {
        MethodInsnNode iterator = nearestCallBefore(
                directCall,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;");
        AbstractInsnNode listLoadNode = AsmUtil.previousReal(iterator);
        if (!(listLoadNode instanceof VarInsnNode listLoad)
                || listLoad.getOpcode() != Opcodes.ALOAD) {
            throw new PatchException(
                    method.name + " 的 loader loop iterator 前不是 ALOAD");
        }

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, listLoad.var));
        begin.add(new LdcInsnNode(handle));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, "begin", BEGIN_DESC, false));
        LabelNode tryStart = new LabelNode();
        begin.add(tryStart);
        method.instructions.insertBefore(listLoad, begin);

        MethodInsnNode hasNext = nearestCallAfter(
                directCall,
                "java/util/Iterator",
                "hasNext",
                "()Z");
        AbstractInsnNode branchNode = AsmUtil.nextReal(hasNext);
        if (!(branchNode instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFNE) {
            throw new PatchException(
                    method.name + " 的 loader loop hasNext 后不是 IFNE");
        }
        InsnList finish = new InsnList();
        finish.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "finish",
                "()V",
                false));
        LabelNode tryEnd = new LabelNode();
        finish.add(tryEnd);
        method.instructions.insert(branch, finish);
        appendAbortHandler(method, tryStart, tryEnd);
    }

    static void appendAbortHandler(
            MethodNode method, LabelNode tryStart, LabelNode tryEnd) {
        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        // All patched loader stages are static ()V methods.  The catch-all
        // handler does not read their locals, so an explicit full frame with
        // every local at TOP (represented by an empty trailing-local list) is
        // both sufficient and independent of whichever frame happens to
        // precede this appended block.  JarRewriter deliberately uses
        // ClassWriter(0), so this frame must be emitted here rather than being
        // recomputed later.
        method.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                0,
                null,
                1,
                new Object[] {"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, "abort", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, handler, null));
    }

    private static MethodInsnNode nearestCallBefore(
            AbstractInsnNode start,
            String owner,
            String name,
            String descriptor) {
        for (AbstractInsnNode node = start.getPrevious();
                node != null;
                node = node.getPrevious()) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                return call;
            }
        }
        throw new PatchException("loader loop 前找不到 " + owner + "." + name);
    }

    private static MethodInsnNode nearestCallAfter(
            AbstractInsnNode start,
            String owner,
            String name,
            String descriptor) {
        for (AbstractInsnNode node = start.getNext();
                node != null;
                node = node.getNext()) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                return call;
            }
        }
        throw new PatchException("loader loop 后找不到 " + owner + "." + name);
    }

    private static MethodNode requireMethodWithMessage(
            ClassNode classNode, String message) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> "()V".equals(method.desc))
                .filter(method -> AsmUtil.instructions(method).stream()
                        .anyMatch(node -> node instanceof LdcInsnNode ldc
                                && message.equals(ldc.cst)))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    classNode.name + " 中日志 [" + message
                            + "] 所在方法数异常: " + matches.size());
        }
        return matches.get(0);
    }

    private static MethodNode requireMethod(
            ClassNode classNode, String name, String descriptor) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name)
                        && descriptor.equals(method.desc)
                        && (method.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    classNode.name + "." + name + descriptor
                            + " 方法数异常: " + matches.size());
        }
        return matches.get(0);
    }

    private static MethodNode requireVariantMethod(ClassNode classNode) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> "()V".equals(method.desc))
                .filter(method -> AsmUtil.instructions(method).stream()
                        .anyMatch(node -> node instanceof LdcInsnNode ldc
                                && "Loading ship & fighter variants"
                                .equals(ldc.cst)))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "variant loader 方法匹配数异常: " + matches.size()
                            + "（预期 1）");
        }
        return matches.get(0);
    }

    private static MethodInsnNode uniqueStoredCall(
            MethodNode method, String owner, String descriptor, String label) {
        List<MethodInsnNode> matches = calls(method, owner, null, descriptor)
                .stream()
                .filter(call -> AsmUtil.nextReal(call) instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ASTORE)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    label + "匹配数异常: " + matches.size() + "（预期 1）");
        }
        return matches.get(0);
    }

    private static VarInsnNode requireStoreAfter(MethodInsnNode call) {
        AbstractInsnNode next = AsmUtil.nextReal(call);
        if (!(next instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) {
            throw new PatchException("variant path list 后不是 ASTORE");
        }
        return store;
    }

    private static MethodInsnNode requireInfoAfter(
            MethodNode method, String message) {
        List<LdcInsnNode> messages = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof LdcInsnNode ldc
                        && message.equals(ldc.cst))
                .map(node -> (LdcInsnNode) node)
                .toList();
        if (messages.size() != 1) {
            throw new PatchException(
                    "variant heading 日志常量数异常: " + messages.size());
        }
        AbstractInsnNode next = AsmUtil.nextReal(messages.get(0));
        if (!(next instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"org/apache/log4j/Logger".equals(call.owner)
                || !"info".equals(call.name)
                || !"(Ljava/lang/Object;)V".equals(call.desc)) {
            throw new PatchException("variant heading 后日志调用结构变化");
        }
        return call;
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String descriptor, String label) {
        List<MethodInsnNode> matches = calls(method, owner, null, descriptor);
        if (matches.size() != 1) {
            throw new PatchException(
                    label + "匹配数异常: " + matches.size() + "（预期 1）");
        }
        return matches.get(0);
    }

    private static int countCalls(
            MethodNode method,
            String owner,
            String name,
            String descriptor) {
        return calls(method, owner, name, descriptor).size();
    }

    private static List<MethodInsnNode> calls(
            MethodNode method,
            String owner,
            String name,
            String descriptor) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode)
                .map(node -> (MethodInsnNode) node)
                .filter(call -> owner == null || owner.equals(call.owner))
                .filter(call -> name == null || name.equals(call.name))
                .filter(call -> descriptor == null
                        || descriptor.equals(call.desc))
                .toList();
    }
}

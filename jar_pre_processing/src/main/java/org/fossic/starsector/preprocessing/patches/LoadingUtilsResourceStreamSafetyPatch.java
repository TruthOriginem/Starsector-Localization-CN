package org.fossic.starsector.preprocessing.patches;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 给 LoadingUtils 的四个 eager 多来源读取入口建立异常安全的流作用域。
 *
 * <p>ASM 只在 {@code C.new(path)} 后进入 helper scope、在返回/异常边界退出；Pair
 * 提取、幂等关闭和 suppressed 异常逻辑均位于 optimization 包。
 */
public final class LoadingUtilsResourceStreamSafetyPatch
        implements JarPatch {
    private static final String TARGET =
            "com/fs/starfarer/loading/LoadingUtils.class";
    private static final String RESOURCE_LOADER = "com/fs/util/C";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String FAST_TEXT_READER =
            "org/fossic/starsector/optimization/FastTextReader";
    private static final String OPEN_ALL_DESC =
            "(Ljava/lang/String;)Ljava/util/List;";
    private static final String ENTER_DESC = "(Ljava/lang/Iterable;)V";
    private static final String FAILURE_DESC = "(Ljava/lang/Throwable;)V";
    private static final String TEXT_READER_DESC =
            "(Ljava/io/InputStream;)Ljava/lang/String;";
    private static final String CLOSE_AND_FORGET_DESC =
            "(Ljava/io/InputStream;)V";
    private static final Set<String> OWNED_METHOD_DESCRIPTORS = Set.of(
            "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;",
            "(Ljava/lang/String;Ljava/util/Set;)Lorg/json/JSONObject;",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)"
                    + "Lorg/json/JSONArray;",
            "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;");

    @Override
    public String id() {
        return "loading-utils-resource-stream-safety";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> OWNED_METHOD_DESCRIPTORS
                        .contains(method.desc))
                .filter(method -> countOpenAllCalls(method) == 1)
                .toList();
        if (methods.size() != OWNED_METHOD_DESCRIPTORS.size()) {
            throw new PatchException(
                    "LoadingUtils 多来源方法匹配数异常: "
                            + methods.size());
        }
        if (countCall(
                        classNode,
                        HELPER,
                        "enterPairStreams",
                        ENTER_DESC) != 0
                || countCall(
                        classNode,
                        HELPER,
                        "closeCurrentBeforeReturn",
                        "()V") != 0
                || countCall(
                        classNode,
                        HELPER,
                        "closeCurrentAfterFailure",
                        FAILURE_DESC) != 0
                || countCall(
                        classNode,
                        HELPER,
                        "closeAndForgetCurrentPairStream",
                        CLOSE_AND_FORGET_DESC) != 0
                || countCall(
                        classNode,
                        FAST_TEXT_READER,
                        "readTracked",
                        TEXT_READER_DESC) != 0) {
            throw new PatchException(
                    "LoadingUtils 已存在资源流安全 bridge");
        }

        int returns = 0;
        for (MethodNode method : methods) {
            returns += patchMethod(method);
        }
        patchExplicitClose(methods);
        ConsumptionHooks consumption =
                patchSuccessfulConsumption(classNode);

        int entered = countCall(
                classNode, HELPER, "enterPairStreams", ENTER_DESC);
        int normal = countCall(
                classNode,
                HELPER,
                "closeCurrentBeforeReturn",
                "()V");
        int failed = countCall(
                classNode,
                HELPER,
                "closeCurrentAfterFailure",
                FAILURE_DESC);
        int explicitlyClosed = countCall(
                classNode,
                HELPER,
                "closeAndForgetCurrentPairStream",
                CLOSE_AND_FORGET_DESC);
        int trackedFastReads = countCall(
                classNode,
                FAST_TEXT_READER,
                "readTracked",
                TEXT_READER_DESC);
        int expectedExplicitCloses = 1 + consumption.readerCloses();
        if (entered != methods.size()
                || normal != returns
                || failed != methods.size()
                || explicitlyClosed != expectedExplicitCloses
                || trackedFastReads != consumption.fastTrackedReads()) {
            throw new PatchException(
                    "LoadingUtils 资源流 bridge 验证失败: enter="
                            + entered + ", normal=" + normal
                            + ", failed=" + failed
                            + ", explicitClose=" + explicitlyClosed
                            + ", fastTracked=" + trackedFastReads);
        }

        int hooks = entered + normal + failed + explicitlyClosed
                + trackedFastReads;
        return PatchResult.of(
                id(),
                context.classPath(),
                hooks,
                hooks,
                hooks,
                "close all eager JSON/CSV source streams on every exit");
    }

    private static void patchExplicitClose(List<MethodNode> methods) {
        List<MethodInsnNode> closes = methods.stream()
                .flatMap(method -> AsmUtil.instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL)
                .filter(call -> "java/io/InputStream".equals(call.owner))
                .filter(call -> "close".equals(call.name))
                .filter(call -> "()V".equals(call.desc))
                .toList();
        if (closes.size() != 1) {
            throw new PatchException(
                    "LoadingUtils eager 方法显式 InputStream.close 数异常: "
                            + closes.size());
        }
        MethodInsnNode close = closes.get(0);
        close.setOpcode(Opcodes.INVOKESTATIC);
        close.owner = HELPER;
        close.name = "closeAndForgetCurrentPairStream";
        close.desc = CLOSE_AND_FORGET_DESC;
        close.itf = false;
    }

    private static ConsumptionHooks patchSuccessfulConsumption(
            ClassNode classNode) {
        List<MethodNode> readers = classNode.methods.stream()
                .filter(method -> "super".equals(method.name))
                .filter(method -> TEXT_READER_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (readers.size() != 1) {
            throw new PatchException(
                    "LoadingUtils InputStream reader 匹配数异常: "
                            + readers.size());
        }
        MethodNode reader = readers.get(0);
        List<MethodInsnNode> originalCloses =
                AsmUtil.instructions(reader).stream()
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .filter(call -> call.getOpcode()
                                == Opcodes.INVOKEVIRTUAL)
                        .filter(call -> "java/io/InputStream"
                                .equals(call.owner))
                        .filter(call -> "close".equals(call.name))
                        .filter(call -> "()V".equals(call.desc))
                        .toList();
        List<MethodInsnNode> fastReads =
                AsmUtil.instructions(reader).stream()
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .filter(call -> call.getOpcode()
                                == Opcodes.INVOKESTATIC)
                        .filter(call -> FAST_TEXT_READER.equals(call.owner))
                        .filter(call -> "read".equals(call.name))
                        .filter(call -> TEXT_READER_DESC.equals(call.desc))
                        .toList();
        int readerCloses;
        int fastTrackedReads;
        if (originalCloses.size() == 3 && fastReads.isEmpty()) {
            for (MethodInsnNode close : originalCloses) {
                close.setOpcode(Opcodes.INVOKESTATIC);
                close.owner = HELPER;
                close.name = "closeAndForgetCurrentPairStream";
                close.desc = CLOSE_AND_FORGET_DESC;
                close.itf = false;
            }
            readerCloses = 3;
            fastTrackedReads = 0;
        } else if (originalCloses.isEmpty() && fastReads.size() == 1) {
            fastReads.get(0).name = "readTracked";
            readerCloses = 0;
            fastTrackedReads = 1;
        } else {
            throw new PatchException(
                    "LoadingUtils reader close 形态异常: originalClose="
                            + originalCloses.size() + ", fastRead="
                            + fastReads.size());
        }
        return new ConsumptionHooks(readerCloses, fastTrackedReads);
    }

    private record ConsumptionHooks(
            int readerCloses, int fastTrackedReads) {
    }

    private static int patchMethod(MethodNode method) {
        List<MethodInsnNode> openAllCalls = AsmUtil.instructions(method)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL)
                .filter(call -> RESOURCE_LOADER.equals(call.owner))
                .filter(call -> "new".equals(call.name))
                .filter(call -> OPEN_ALL_DESC.equals(call.desc))
                .toList();
        if (openAllCalls.size() != 1) {
            throw new PatchException(
                    "LoadingUtils " + method.name + method.desc
                            + " 的 C.new 调用数异常: "
                            + openAllCalls.size());
        }
        AbstractInsnNode storeNode = nextMeaningful(openAllCalls.get(0));
        if (!(storeNode instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) {
            throw new PatchException(
                    "LoadingUtils C.new 返回值后不是 ASTORE: "
                            + method.name + method.desc);
        }

        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(Opcodes.ALOAD, store.var));
        enter.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "enterPairStreams",
                ENTER_DESC,
                false));
        LabelNode tryStart = new LabelNode();
        enter.add(tryStart);
        method.instructions.insert(store, enter);

        List<AbstractInsnNode> returns = AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() == Opcodes.ARETURN)
                .toList();
        if (returns.isEmpty()) {
            throw new PatchException(
                    "LoadingUtils 多来源方法没有 ARETURN: "
                            + method.name + method.desc);
        }
        for (AbstractInsnNode returnNode : returns) {
            method.instructions.insertBefore(
                    returnNode,
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HELPER,
                            "closeCurrentBeforeReturn",
                            "()V",
                            false));
        }

        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(tryEnd);
        method.instructions.add(handler);
        method.instructions.add(handlerFrame(method, store.var));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "closeCurrentAfterFailure",
                FAILURE_DESC,
                false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, handler, null));
        method.maxStack = Math.max(method.maxStack, 2);
        return returns.size();
    }

    private static int countOpenAllCalls(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && RESOURCE_LOADER.equals(call.owner)
                    && "new".equals(call.name)
                    && OPEN_ALL_DESC.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static FrameNode handlerFrame(
            MethodNode method, int listLocal) {
        Object[] locals = new Object[listLocal + 1];
        Arrays.fill(locals, Opcodes.TOP);
        int local = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new PatchException(
                    "LoadingUtils 多来源方法预期为 static: "
                            + method.name + method.desc);
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            Object frameType = frameType(argument);
            locals[local] = frameType;
            local += argument.getSize();
        }
        locals[listLocal] = "java/util/List";
        return new FrameNode(
                Opcodes.F_FULL,
                locals.length,
                locals,
                1,
                new Object[]{"java/lang/Throwable"});
    }

    private static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN,
                    Type.BYTE,
                    Type.CHAR,
                    Type.SHORT,
                    Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor();
            case Type.OBJECT -> type.getInternalName();
            default -> throw new PatchException(
                    "不支持的 LoadingUtils frame 参数: " + type);
        };
    }

    private static AbstractInsnNode nextMeaningful(
            AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static int countCall(
            ClassNode classNode,
            String owner,
            String name,
            String desc) {
        return AsmUtil.countMethodCall(classNode, owner, name, desc);
    }
}

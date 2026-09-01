package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
import java.util.HashSet;
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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把 LoadingUtils 中两条二次复杂度 CSV 合并路径桥接到可单测 helper。
 *
 * <p>通用合并只替换结果列表的构造；原循环、过滤、重复键异常和来源顺序全部保留。
 * 三参数合并只替换“扫描旧行、删除首个同键行、追加新行”的局部循环，文件读取、JSON
 * 解析、row source 标注和最终 JSONArray 构造仍由原方法执行。
 */
public final class CsvMergeLinearPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/LoadingUtils.class";
    private static final String COMMON_DESC =
            "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;";
    private static final String OVERRIDE_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)"
                    + "Lorg/json/JSONArray;";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String LIST = "java/util/List";
    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String HELPER =
            "org/fossic/starsector/optimization/CsvMergeOptimizer";
    private static final String FACTORY_DESC = "()Ljava/util/List;";
    private static final String PUT_DESC =
            "(Ljava/util/List;Ljava/lang/String;Ljava/lang/String;"
                    + "Ljava/lang/Object;)V";

    @Override
    public String id() {
        return "csv-linear-merge";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.CSV_MERGE_LINEAR;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        MethodNode common = requireUniqueStaticMethod(
                classNode, COMMON_DESC, "通用 CSV 合并");
        MethodNode override = requireUniqueStaticMethod(
                classNode, OVERRIDE_DESC, "三参数 CSV 合并");

        replaceArrayListFactory(common, "baseFirstRows");
        replaceArrayListFactory(override, "overrideRows");
        replaceOverrideScan(override);

        int baseFactories = AsmUtil.countMethodCall(
                classNode, HELPER, "baseFirstRows", FACTORY_DESC);
        int overrideFactories = AsmUtil.countMethodCall(
                classNode, HELPER, "overrideRows", FACTORY_DESC);
        int putCalls = AsmUtil.countMethodCall(
                classNode, HELPER, "putMovingToEnd", PUT_DESC);
        if (baseFactories != 1
                || overrideFactories != 1
                || putCalls != 1) {
            throw new PatchException(
                    "CSV 线性合并验证失败: baseFactory="
                            + baseFactories + ", overrideFactory="
                            + overrideFactories + ", put=" + putCalls);
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                3,
                3,
                3,
                "replace growing front insertion and per-row override scan "
                        + "with CsvMergeOptimizer");
    }

    private static MethodNode requireUniqueStaticMethod(
            ClassNode classNode, String descriptor, String label) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> descriptor.equals(method.desc)
                        && (method.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    label + "方法匹配数异常: " + matches.size()
                            + "（预期 1）");
        }
        return matches.get(0);
    }

    private static void replaceArrayListFactory(
            MethodNode method, String factoryName) {
        List<MethodInsnNode> constructors =
                AsmUtil.instructions(method).stream()
                        .filter(node -> node instanceof MethodInsnNode call
                                && call.getOpcode()
                                == Opcodes.INVOKESPECIAL
                                && ARRAY_LIST.equals(call.owner)
                                && "<init>".equals(call.name)
                                && "()V".equals(call.desc))
                        .map(node -> (MethodInsnNode) node)
                        .toList();
        if (constructors.size() != 1) {
            throw new PatchException(
                    method.desc + " 中 ArrayList() 构造数异常: "
                            + constructors.size() + "（预期 1）");
        }

        MethodInsnNode constructor = constructors.get(0);
        AbstractInsnNode duplicate = AsmUtil.previousReal(constructor);
        AbstractInsnNode allocation = duplicate == null
                ? null : AsmUtil.previousReal(duplicate);
        if (duplicate == null
                || duplicate.getOpcode() != Opcodes.DUP
                || !(allocation instanceof TypeInsnNode type)
                || allocation.getOpcode() != Opcodes.NEW
                || !ARRAY_LIST.equals(type.desc)) {
            throw new PatchException(
                    method.desc + " 中 ArrayList() 分配形状变化");
        }

        method.instructions.set(
                allocation,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        factoryName,
                        FACTORY_DESC,
                        false));
        method.instructions.remove(duplicate);
        method.instructions.remove(constructor);
    }

    private static void replaceOverrideScan(MethodNode method) {
        VarInsnNode keyStore = findNewRowKeyStore(method);
        MethodInsnNode keyRead = (MethodInsnNode)
                AsmUtil.previousReal(keyStore);
        VarInsnNode keyColumnLoad = requireVar(
                AsmUtil.previousReal(keyRead),
                Opcodes.ALOAD,
                "新 row key 列参数");
        VarInsnNode rowLoadForKey = requireVar(
                AsmUtil.previousReal(keyColumnLoad),
                Opcodes.ALOAD,
                "新 row key 读取对象");

        IincInsnNode rowIncrement = firstRowIncrementAfter(keyStore);
        MethodInsnNode add = uniqueCallBetween(
                keyStore,
                rowIncrement,
                LIST,
                "add",
                "(Ljava/lang/Object;)Z");
        VarInsnNode rowLoadForAdd = requireVar(
                AsmUtil.previousReal(add),
                Opcodes.ALOAD,
                "覆盖 row 追加对象");
        VarInsnNode rowsLoad = requireVar(
                AsmUtil.previousReal(rowLoadForAdd),
                Opcodes.ALOAD,
                "覆盖 row 结果列表");
        if (rowLoadForKey.var != rowLoadForAdd.var) {
            throw new PatchException(
                    "CSV 覆盖循环的新 row 局部变量不一致: key="
                            + rowLoadForKey.var + ", add="
                            + rowLoadForAdd.var);
        }
        if (keyColumnLoad.var != 0) {
            throw new PatchException(
                    "CSV 覆盖 key 列参数局部变量异常: "
                            + keyColumnLoad.var + "（预期 0）");
        }

        AbstractInsnNode pop = AsmUtil.nextReal(add);
        if (pop == null || pop.getOpcode() != Opcodes.POP) {
            throw new PatchException("CSV 覆盖追加后缺少 POP");
        }
        verifyRemovedScan(keyStore, pop);

        // 原循环的内层 iterator 帧位于 POP 与外层 IINC 之间，并以 F_CHOP
        // 相对前一个（即将被删除的）内层帧编码。先把它展开为独立 F_FULL，
        // 否则 ClassWriter(0) 会让 CHOP 相对更早的外层帧生效，导致外层
        // row index 被错误标为 TOP，并在类加载时触发 VerifyError。
        makeSuccessorFrameIndependent(method, rowIncrement);

        Set<AbstractInsnNode> removed = nodesBetween(
                keyStore.getNext(), pop);
        verifyNoExternalJumpInto(method, removed);

        AbstractInsnNode current = keyStore.getNext();
        while (current != null) {
            AbstractInsnNode next = current.getNext();
            method.instructions.remove(current);
            if (current == pop) {
                break;
            }
            current = next;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, rowsLoad.var));
        replacement.add(new VarInsnNode(
                Opcodes.ALOAD, keyColumnLoad.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, keyStore.var));
        replacement.add(new VarInsnNode(
                Opcodes.ALOAD, rowLoadForAdd.var));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "putMovingToEnd",
                PUT_DESC,
                false));
        method.instructions.insert(keyStore, replacement);
        method.maxStack = Math.max(method.maxStack, 4);
    }

    private static void makeSuccessorFrameIndependent(
            MethodNode method,
            AbstractInsnNode rowIncrement) {
        List<FrameNode> candidates = new ArrayList<>();
        AbstractInsnNode nextInstruction = AsmUtil.nextReal(rowIncrement);
        for (AbstractInsnNode node = rowIncrement.getNext();
                node != null && node != nextInstruction;
                node = node.getNext()) {
            if (node instanceof FrameNode frame) {
                candidates.add(frame);
            }
        }
        if (candidates.size() != 1) {
            throw new PatchException(
                    "CSV 覆盖扫描后的边界 frame 数异常: "
                            + candidates.size() + "（预期 1）");
        }

        FrameNode compressed = candidates.get(0);
        ExpandedFrame expanded = expandFrameAt(method, compressed);
        FrameNode independent = new FrameNode(
                Opcodes.F_FULL,
                expanded.locals().size(),
                expanded.locals().toArray(),
                expanded.stack().size(),
                expanded.stack().toArray());
        method.instructions.set(compressed, independent);
    }

    private static ExpandedFrame expandFrameAt(
            MethodNode method, FrameNode target) {
        ArrayList<Object> locals = initialFrameLocals(method);
        ArrayList<Object> stack = new ArrayList<>();
        for (AbstractInsnNode node = method.instructions.getFirst();
                node != null;
                node = node.getNext()) {
            if (!(node instanceof FrameNode frame)) {
                continue;
            }
            switch (frame.type) {
                case Opcodes.F_NEW, Opcodes.F_FULL -> {
                    locals = copyFrameValues(frame.local);
                    stack = copyFrameValues(frame.stack);
                }
                case Opcodes.F_APPEND -> {
                    locals.addAll(copyFrameValues(frame.local));
                    stack.clear();
                }
                case Opcodes.F_CHOP -> {
                    int removed = frame.local == null
                            ? 0 : frame.local.size();
                    if (removed > locals.size()) {
                        throw new PatchException(
                                "CSV frame CHOP 超出当前 locals: "
                                        + removed + " > "
                                        + locals.size());
                    }
                    locals.subList(
                            locals.size() - removed,
                            locals.size()).clear();
                    stack.clear();
                }
                case Opcodes.F_SAME -> stack.clear();
                case Opcodes.F_SAME1 ->
                        stack = copyFrameValues(frame.stack);
                default -> throw new PatchException(
                        "CSV 遇到未知 frame 类型: " + frame.type);
            }
            if (frame == target) {
                return new ExpandedFrame(
                        List.copyOf(locals), List.copyOf(stack));
            }
        }
        throw new PatchException("CSV 未找到待展开的边界 frame");
    }

    private static ArrayList<Object> initialFrameLocals(
            MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new PatchException(
                    "CSV 覆盖合并方法预期为 static: "
                            + method.name + method.desc);
        }
        ArrayList<Object> locals = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            locals.add(frameType(argument));
        }
        return locals;
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
                    "CSV frame 参数类型不受支持: " + type);
        };
    }

    private static ArrayList<Object> copyFrameValues(
            List<Object> values) {
        return values == null
                ? new ArrayList<>()
                : new ArrayList<>(values);
    }

    private record ExpandedFrame(
            List<Object> locals, List<Object> stack) {
    }

    private static VarInsnNode findNewRowKeyStore(MethodNode method) {
        List<VarInsnNode> candidates = AsmUtil.instructions(method)
                .stream()
                .filter(node -> node instanceof VarInsnNode variable
                        && variable.getOpcode() == Opcodes.ASTORE)
                .map(node -> (VarInsnNode) node)
                .filter(store -> {
                    AbstractInsnNode previous =
                            AsmUtil.previousReal(store);
                    if (!(previous instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !JSON_OBJECT.equals(call.owner)
                            || !"getString".equals(call.name)
                            || !"(Ljava/lang/String;)Ljava/lang/String;"
                            .equals(call.desc)) {
                        return false;
                    }
                    AbstractInsnNode next = AsmUtil.nextReal(store);
                    AbstractInsnNode next2 = next == null
                            ? null : AsmUtil.nextReal(next);
                    return next instanceof VarInsnNode load
                            && load.getOpcode() == Opcodes.ALOAD
                            && next2 instanceof MethodInsnNode iterator
                            && iterator.getOpcode()
                            == Opcodes.INVOKEINTERFACE
                            && LIST.equals(iterator.owner)
                            && "iterator".equals(iterator.name)
                            && "()Ljava/util/Iterator;".equals(iterator.desc);
                })
                .toList();
        if (candidates.size() != 1) {
            throw new PatchException(
                    "CSV 覆盖新 row key 存储匹配数异常: "
                            + candidates.size() + "（预期 1）");
        }
        return candidates.get(0);
    }

    private static IincInsnNode firstRowIncrementAfter(
            AbstractInsnNode start) {
        for (AbstractInsnNode node = start.getNext();
             node != null;
             node = node.getNext()) {
            if (node instanceof IincInsnNode increment) {
                return increment;
            }
        }
        throw new PatchException("CSV 覆盖循环后未找到 row 下标递增");
    }

    private static MethodInsnNode uniqueCallBetween(
            AbstractInsnNode start,
            AbstractInsnNode end,
            String owner,
            String name,
            String descriptor) {
        java.util.ArrayList<MethodInsnNode> matches =
                new java.util.ArrayList<>();
        for (AbstractInsnNode node = start.getNext();
             node != null && node != end;
             node = node.getNext()) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        if (matches.size() != 1) {
            throw new PatchException(
                    "CSV 覆盖扫描中 " + owner + "." + name
                            + " 匹配数异常: " + matches.size()
                            + "（预期 1）");
        }
        return matches.get(0);
    }

    private static void verifyRemovedScan(
            AbstractInsnNode start, AbstractInsnNode end) {
        int iterators = 0;
        int removals = 0;
        int oldKeyReads = 0;
        int equalsCalls = 0;
        for (AbstractInsnNode node = start.getNext();
             node != null;
             node = node.getNext()) {
            if (node instanceof MethodInsnNode call) {
                if (LIST.equals(call.owner)
                        && "iterator".equals(call.name)) {
                    iterators++;
                } else if (LIST.equals(call.owner)
                        && "remove".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    removals++;
                } else if (JSON_OBJECT.equals(call.owner)
                        && "getString".equals(call.name)) {
                    oldKeyReads++;
                } else if ("java/lang/String".equals(call.owner)
                        && "equals".equals(call.name)) {
                    equalsCalls++;
                }
            }
            if (node == end) {
                break;
            }
        }
        if (iterators != 1
                || removals != 1
                || oldKeyReads != 1
                || equalsCalls != 1) {
            throw new PatchException(
                    "CSV 覆盖线性扫描形状变化: iterator="
                            + iterators + ", remove=" + removals
                            + ", oldKeyRead=" + oldKeyReads
                            + ", equals=" + equalsCalls);
        }
    }

    private static Set<AbstractInsnNode> nodesBetween(
            AbstractInsnNode first, AbstractInsnNode last) {
        Set<AbstractInsnNode> result = new HashSet<>();
        for (AbstractInsnNode node = first;
             node != null;
             node = node.getNext()) {
            result.add(node);
            if (node == last) {
                return result;
            }
        }
        throw new PatchException("CSV 覆盖扫描删除边界无效");
    }

    private static void verifyNoExternalJumpInto(
            MethodNode method, Set<AbstractInsnNode> removed) {
        Set<LabelNode> removedLabels = new HashSet<>();
        for (AbstractInsnNode node : removed) {
            if (node instanceof LabelNode label) {
                removedLabels.add(label);
            }
        }
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!removed.contains(node)
                    && node instanceof JumpInsnNode jump
                    && removedLabels.contains(jump.label)) {
                throw new PatchException(
                        "CSV 覆盖扫描存在外部跳转，拒绝删除");
            }
        }
    }

    private static VarInsnNode requireVar(
            AbstractInsnNode node, int opcode, String label) {
        if (!(node instanceof VarInsnNode variable)
                || variable.getOpcode() != opcode) {
            throw new PatchException(label + "指令结构异常");
        }
        return variable;
    }
}

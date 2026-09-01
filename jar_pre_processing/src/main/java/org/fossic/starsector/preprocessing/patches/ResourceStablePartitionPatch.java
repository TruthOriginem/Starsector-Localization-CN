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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 用线性 helper 替换资源列表的 {@code removeAll + addAll(0, ...)} 稳定分区。
 *
 * <p>Patch 只桥接原有完整列表和原有优先子序列；分区契约、验证和实现全部位于可单测的
 * {@code StableListPartition}。
 */
public final class ResourceStablePartitionPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/ResourceLoaderState.class";
    private static final String OWNER =
            "com/fs/starfarer/loading/ResourceLoaderState";
    private static final String INIT_DESC = "(Ljava/util/Map;)V";
    private static final String LIST = "java/util/List";
    private static final String RESOURCES_FIELD = "resources";
    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String HELPER =
            "org/fossic/starsector/optimization/StableListPartition";
    private static final String HELPER_DESC =
            "(Ljava/util/List;Ljava/util/List;)V";

    @Override
    public String id() {
        return "resource-stable-priority-partition";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.RESOURCE_PARTITION;
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
        MethodNode init = requireInit(classNode);
        MethodInsnNode removeAll = uniqueListCall(
                init, "removeAll", "(Ljava/util/Collection;)Z");
        OriginalSequence sequence = verifySequence(removeAll);

        init.instructions.set(
                removeAll,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "prioritizedSubsequenceFirst",
                        HELPER_DESC,
                        false));
        init.instructions.remove(sequence.firstPop());
        init.instructions.remove(sequence.secondOwnerLoad());
        init.instructions.remove(sequence.secondFieldRead());
        init.instructions.remove(sequence.zeroIndex());
        init.instructions.remove(sequence.secondPriorityLoad());
        init.instructions.remove(sequence.addAll());
        init.instructions.remove(sequence.secondPop());

        int helperCalls = AsmUtil.countMethodCall(
                classNode,
                HELPER,
                "prioritizedSubsequenceFirst",
                HELPER_DESC);
        int remainingRemoveAll = listCalls(
                init, "removeAll", "(Ljava/util/Collection;)Z").size();
        int remainingIndexedAddAll = listCalls(
                init, "addAll", "(ILjava/util/Collection;)Z").size();
        if (helperCalls != 1
                || remainingRemoveAll != 0
                || remainingIndexedAddAll != 0) {
            throw new PatchException(
                    "资源稳定分区调用验证失败: helper=" + helperCalls
                            + ", removeAll=" + remainingRemoveAll
                            + ", indexedAddAll="
                            + remainingIndexedAddAll);
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                1,
                1,
                helperCalls,
                "replace O(N*M) resource removeAll/addAll partition with "
                        + "StableListPartition");
    }

    private static MethodNode requireInit(ClassNode classNode) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "init".equals(method.name)
                        && INIT_DESC.equals(method.desc)
                        && (method.access & Opcodes.ACC_PUBLIC) != 0
                        && (method.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "ResourceLoaderState.init 匹配数异常: "
                            + matches.size() + "（预期 1）");
        }
        return matches.get(0);
    }

    private static MethodInsnNode uniqueListCall(
            MethodNode method, String name, String desc) {
        List<MethodInsnNode> calls = listCalls(method, name, desc);
        if (calls.size() != 1) {
            throw new PatchException(
                    "ResourceLoaderState.init 中 List." + name
                            + " 调用数异常: " + calls.size()
                            + "（预期 1）");
        }
        return calls.get(0);
    }

    private static List<MethodInsnNode> listCalls(
            MethodNode method, String name, String desc) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && LIST.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)
                        && call.itf)
                .map(node -> (MethodInsnNode) node)
                .toList();
    }

    private static OriginalSequence verifySequence(
            MethodInsnNode removeAll) {
        VarInsnNode firstPriorityLoad = requireVar(
                AsmUtil.previousReal(removeAll),
                Opcodes.ALOAD,
                "removeAll 前的优先列表");
        FieldInsnNode firstFieldRead = requireResourcesField(
                AsmUtil.previousReal(firstPriorityLoad));
        requireVar(
                AsmUtil.previousReal(firstFieldRead),
                Opcodes.ALOAD,
                0,
                "removeAll 前的 this");

        AbstractInsnNode firstPop = requireOpcode(
                AsmUtil.nextReal(removeAll),
                Opcodes.POP,
                "removeAll 返回值 POP");
        VarInsnNode secondOwnerLoad = requireVar(
                AsmUtil.nextReal(firstPop),
                Opcodes.ALOAD,
                0,
                "addAll 前的 this");
        FieldInsnNode secondFieldRead = requireResourcesField(
                AsmUtil.nextReal(secondOwnerLoad));
        AbstractInsnNode zeroIndex = requireOpcode(
                AsmUtil.nextReal(secondFieldRead),
                Opcodes.ICONST_0,
                "addAll 插入下标");
        VarInsnNode secondPriorityLoad = requireVar(
                AsmUtil.nextReal(zeroIndex),
                Opcodes.ALOAD,
                firstPriorityLoad.var,
                "addAll 前的优先列表");
        AbstractInsnNode addAllNode =
                AsmUtil.nextReal(secondPriorityLoad);
        if (!(addAllNode instanceof MethodInsnNode addAll)
                || addAll.getOpcode() != Opcodes.INVOKEINTERFACE
                || !LIST.equals(addAll.owner)
                || !"addAll".equals(addAll.name)
                || !"(ILjava/util/Collection;)Z".equals(addAll.desc)
                || !addAll.itf) {
            throw new PatchException(
                    "removeAll 后未找到同列表的 addAll(0, priority)");
        }
        AbstractInsnNode secondPop = requireOpcode(
                AsmUtil.nextReal(addAll),
                Opcodes.POP,
                "addAll 返回值 POP");

        return new OriginalSequence(
                firstPop,
                secondOwnerLoad,
                secondFieldRead,
                zeroIndex,
                secondPriorityLoad,
                addAll,
                secondPop);
    }

    private static FieldInsnNode requireResourcesField(
            AbstractInsnNode node) {
        if (!(node instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !OWNER.equals(field.owner)
                || !RESOURCES_FIELD.equals(field.name)
                || !LIST_DESC.equals(field.desc)) {
            throw new PatchException(
                    "稳定分区调用前未找到 ResourceLoaderState.resources");
        }
        return field;
    }

    private static VarInsnNode requireVar(
            AbstractInsnNode node, int opcode, String label) {
        if (!(node instanceof VarInsnNode variable)
                || variable.getOpcode() != opcode) {
            throw new PatchException(label + " 指令结构异常");
        }
        return variable;
    }

    private static VarInsnNode requireVar(
            AbstractInsnNode node,
            int opcode,
            int variable,
            String label) {
        VarInsnNode result = requireVar(node, opcode, label);
        if (result.var != variable) {
            throw new PatchException(
                    label + " 局部变量异常: " + result.var
                            + "（预期 " + variable + "）");
        }
        return result;
    }

    private static AbstractInsnNode requireOpcode(
            AbstractInsnNode node, int opcode, String label) {
        if (node == null || node.getOpcode() != opcode) {
            throw new PatchException(label + " 指令结构异常");
        }
        return node;
    }

    private record OriginalSequence(
            AbstractInsnNode firstPop,
            VarInsnNode secondOwnerLoad,
            FieldInsnNode secondFieldRead,
            AbstractInsnNode zeroIndex,
            VarInsnNode secondPriorityLoad,
            MethodInsnNode addAll,
            AbstractInsnNode secondPop) {
    }
}

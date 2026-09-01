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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 在资源加载器的 eager 多来源 open 内部追踪尚未返回的流。
 *
 * <p>成功路径只释放 tracker、保持原有所有权转移不变；ASM 只建立 enter/track/
 * release/catch-all 边界，identity 去重、关闭和 suppressed 语义全部交给 helper。
 */
public final class ResourceLoaderStreamSafetyPatch implements JarPatch {
    private static final String TARGET = "com/fs/util/C.class";
    private static final String OWNER = "com/fs/util/C";
    private static final String PAIR = "com/fs/util/container/Pair";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String MULTI_OPEN_DESC =
            "(Ljava/lang/String;)Ljava/util/List;";
    private static final String LEAF_OPEN_DESC =
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)Ljava/io/InputStream;";
    private static final String TRACK_DESC =
            "(Ljava/io/InputStream;)Ljava/io/InputStream;";
    private static final String FAILURE_DESC =
            "(Ljava/lang/Throwable;)V";
    private static final String DISCARDED_DESC =
            "(Ljava/io/InputStream;Z)Z";

    @Override
    public String id() {
        return "resource-loader-partial-stream-safety";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.RESOURCE_STREAM_SAFETY;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET);
    }

    @Override
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        MethodNode method = locateMultiOpen(classNode);
        if (countSafetyHooks(classNode) != 0) {
            throw new PatchException(
                    "资源加载器已存在 partial-stream safety bridge");
        }
        VarInsnNode resultStore = locateResultStore(method);
        if (resultStore.var != 2) {
            throw new PatchException(
                    "资源加载器 partial list local 变化: "
                            + resultStore.var + "（预期 2）");
        }
        patchDiscardedBranch(method);

        LabelNode tryStart = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "enterPartialOpen",
                "()V",
                false));
        enter.add(tryStart);
        method.instructions.insert(resultStore, enter);

        List<AbstractInsnNode> returns = AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() == Opcodes.ARETURN)
                .toList();
        if (returns.size() != 1) {
            throw new PatchException(
                    "资源加载器 eager multi-open ARETURN 数异常: "
                            + returns.size());
        }
        method.instructions.insertBefore(
                returns.get(0),
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "releasePartialOpen",
                        "()V",
                        false));

        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(tryEnd);
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[]{OWNER, "java/lang/String", "java/util/List"},
                1,
                new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "closePartialOpenAfterFailure",
                FAILURE_DESC,
                false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, handler, null));
        method.maxStack = Math.max(method.maxStack, 2);

        int enterHooks = countCall(
                classNode, "enterPartialOpen", "()V");
        int trackHooks = countCall(
                classNode, "trackPartialOpenStream", TRACK_DESC);
        int releaseHooks = countCall(
                classNode, "releasePartialOpen", "()V");
        int failureHooks = countCall(
                classNode,
                "closePartialOpenAfterFailure",
                FAILURE_DESC);
        int discardedHooks = AsmUtil.countMethodCall(
                classNode,
                HELPER,
                "closeIfDiscarded",
                DISCARDED_DESC);
        if (enterHooks != 1
                || trackHooks != 1
                || releaseHooks != 1
                || failureHooks != 1
                || discardedHooks != 1) {
            throw new PatchException(
                    "资源加载器 stream safety bridge 验证失败: enter="
                            + enterHooks + ", track=" + trackHooks
                            + ", release=" + releaseHooks
                            + ", failure=" + failureHooks
                            + ", discarded=" + discardedHooks);
        }
        return PatchResult.of(
                id(),
                context.classPath(),
                5,
                5,
                5,
                "track all open results, release on success, and close on failure");
    }

    private static void patchDiscardedBranch(MethodNode method) {
        List<MethodInsnNode> opens = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> OWNER.equals(call.owner))
                .filter(call -> LEAF_OPEN_DESC.equals(call.desc))
                .toList();
        if (opens.size() != 1) {
            throw new PatchException(
                    "资源加载器 leaf open 调用数异常: " + opens.size());
        }
        MethodInsnNode open = opens.get(0);
        VarInsnNode inputStore = requireVariable(
                AsmUtil.nextReal(open),
                Opcodes.ASTORE,
                "leaf open 结果");
        method.instructions.insert(
                open,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "trackPartialOpenStream",
                        TRACK_DESC,
                        false));
        VarInsnNode inputCheck = requireVariable(
                AsmUtil.nextReal(inputStore),
                Opcodes.ALOAD,
                "leaf open null 检查");
        if (inputCheck.var != inputStore.var) {
            throw new PatchException(
                    "资源加载器 leaf stream local 不一致: store="
                            + inputStore.var + ", check=" + inputCheck.var);
        }
        JumpInsnNode nullBranch = requireJump(
                AsmUtil.nextReal(inputCheck),
                Opcodes.IFNULL,
                "leaf open null 分支");
        VarInsnNode baseLikeFlag = requireVariable(
                AsmUtil.nextReal(nullBranch),
                Opcodes.ILOAD,
                "base/classpath 来源标记");
        JumpInsnNode ordinarySource = requireJump(
                AsmUtil.nextReal(baseLikeFlag),
                Opcodes.IFEQ,
                "普通 mod 来源分支");
        VarInsnNode duplicateFlag = requireVariable(
                AsmUtil.nextReal(ordinarySource),
                Opcodes.ILOAD,
                "已选 base/classpath 标记");
        JumpInsnNode discardBranch = requireJump(
                AsmUtil.nextReal(duplicateFlag),
                Opcodes.IFNE,
                "重复来源丢弃分支");
        if (discardBranch.label != nullBranch.label) {
            throw new PatchException(
                    "重复来源与 null 来源不再汇合到同一 continue");
        }

        method.instructions.insertBefore(
                duplicateFlag,
                new VarInsnNode(Opcodes.ALOAD, inputStore.var));
        method.instructions.insert(
                duplicateFlag,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER,
                        "closeIfDiscarded",
                        DISCARDED_DESC,
                        false));
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static VarInsnNode requireVariable(
            AbstractInsnNode node, int opcode, String label) {
        if (!(node instanceof VarInsnNode variable)
                || variable.getOpcode() != opcode) {
            throw new PatchException(
                    "资源加载器 " + label + " 指令结构变化");
        }
        return variable;
    }

    private static JumpInsnNode requireJump(
            AbstractInsnNode node, int opcode, String label) {
        if (!(node instanceof JumpInsnNode jump)
                || jump.getOpcode() != opcode) {
            throw new PatchException(
                    "资源加载器 " + label + " 指令结构变化");
        }
        return jump;
    }

    private static MethodNode locateMultiOpen(ClassNode classNode) {
        List<MethodNode> methods = classNode.methods.stream()
                .filter(method -> MULTI_OPEN_DESC.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_STATIC) == 0)
                .filter(method -> countCalls(
                        method, OWNER, LEAF_OPEN_DESC) == 1)
                .filter(method -> AsmUtil.instructions(method).stream()
                        .filter(TypeInsnNode.class::isInstance)
                        .map(TypeInsnNode.class::cast)
                        .filter(type -> type.getOpcode() == Opcodes.NEW)
                        .filter(type -> PAIR.equals(type.desc))
                        .count() == 1)
                .toList();
        if (methods.size() != 1) {
            throw new PatchException(
                    "资源加载器 eager multi-open 方法匹配数异常: "
                            + methods.size());
        }
        return methods.get(0);
    }

    private static VarInsnNode locateResultStore(MethodNode method) {
        List<VarInsnNode> stores = AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKESPECIAL)
                .filter(call -> ARRAY_LIST.equals(call.owner))
                .filter(call -> "<init>".equals(call.name))
                .filter(call -> "()V".equals(call.desc))
                .map(AsmUtil::nextReal)
                .filter(VarInsnNode.class::isInstance)
                .map(VarInsnNode.class::cast)
                .filter(store -> store.getOpcode() == Opcodes.ASTORE)
                .toList();
        if (stores.size() != 1) {
            throw new PatchException(
                    "资源加载器 partial list 初始化匹配数异常: "
                            + stores.size());
        }
        return stores.get(0);
    }

    private static int countCall(
            ClassNode classNode, String name, String descriptor) {
        return AsmUtil.countMethodCall(
                classNode,
                HELPER,
                name,
                descriptor);
    }

    private static int countSafetyHooks(ClassNode classNode) {
        return countCall(classNode, "enterPartialOpen", "()V")
                + countCall(
                        classNode,
                        "trackPartialOpenStream",
                        TRACK_DESC)
                + countCall(classNode, "releasePartialOpen", "()V")
                + countCall(
                        classNode,
                        "closePartialOpenAfterFailure",
                        FAILURE_DESC)
                + countCall(
                        classNode,
                        "closeIfDiscarded",
                        DISCARDED_DESC);
    }

    private static int countCalls(
            MethodNode method, String owner, String descriptor) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}

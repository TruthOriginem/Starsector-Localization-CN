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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 把原版固定为 2 的声音解码池大小桥接到可测试的有界策略。 */
public final class SoundDecodeWorkerPatch implements JarPatch {
    private static final String TARGET =
            "com/fs/starfarer/loading/ResourceLoaderState.class";
    private static final String EXECUTORS =
            "java/util/concurrent/Executors";
    private static final String POLICY =
            "org/fossic/starsector/optimization/SoundDecodeWorkerPolicy";
    private static final String EXECUTOR_DESC =
            "(I)Ljava/util/concurrent/ExecutorService;";

    @Override
    public String id() {
        return "sound-decode-worker-count";
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
        MethodNode init = classNode.methods.stream()
                .filter(method -> "init".equals(method.name))
                .filter(method -> "(Ljava/util/Map;)V".equals(method.desc))
                .findFirst()
                .orElseThrow(() -> new PatchException(
                        "ResourceLoaderState.init(Map) 不存在"));
        List<MethodInsnNode> poolFactories = AsmUtil.instructions(init)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKESTATIC)
                .filter(call -> EXECUTORS.equals(call.owner))
                .filter(call -> "newFixedThreadPool".equals(call.name))
                .filter(call -> EXECUTOR_DESC.equals(call.desc))
                .toList();
        if (poolFactories.size() != 1) {
            throw new PatchException(
                    "ResourceLoaderState 声音线程池工厂数异常: "
                            + poolFactories.size());
        }

        AbstractInsnNode originalCount = previousExecutable(
                poolFactories.get(0));
        if (originalCount == null
                || originalCount.getOpcode() != Opcodes.ICONST_2) {
            throw new PatchException(
                    "声音线程池原始 worker 常量不是 ICONST_2");
        }
        init.instructions.set(
                originalCount,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        POLICY,
                        "workerCount",
                        "()I",
                        false));

        int verified = AsmUtil.countMethodCall(
                classNode, POLICY, "workerCount", "()I");
        if (verified != 1
                || previousExecutable(poolFactories.get(0)).getOpcode()
                        != Opcodes.INVOKESTATIC) {
            throw new PatchException(
                    "声音线程池策略 bridge 验证失败: " + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "replace fixed two-worker sound decoder pool with policy");
    }

    private static AbstractInsnNode previousExecutable(
            AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }
}

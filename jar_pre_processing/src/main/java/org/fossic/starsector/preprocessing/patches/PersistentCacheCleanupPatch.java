package org.fossic.starsector.preprocessing.patches;

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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 在标题画面准备完成后触发已注册持久缓存的异步维护。 */
public final class PersistentCacheCleanupPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/title/TitleScreenState.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/PersistentCacheMaintenance";

    @Override
    public String id() {
        return "persistent-cache-cleanup-startup";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.CACHE_MAINTENANCE;
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
        MethodNode prepare = StartupProfilePatchSupport.requireMethod(
                classNode, "prepare", "()V");
        int existing = AsmUtil.countMethodCall(
                classNode, HELPER, "onStartupComplete", "()V");
        long returns = AsmUtil.instructions(prepare).stream()
                .filter(instruction ->
                        instruction.getOpcode() == Opcodes.RETURN)
                .count();
        if (existing != 0 || returns != 1) {
            throw new PatchException(
                    "持久缓存清理挂钩结构异常: existing="
                            + existing + ", returns=" + returns);
        }

        for (AbstractInsnNode instruction
                : prepare.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                prepare.instructions.insertBefore(
                        instruction,
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                HELPER,
                                "onStartupComplete",
                                "()V",
                                false));
            }
        }

        int verified = AsmUtil.countMethodCall(
                classNode, HELPER, "onStartupComplete", "()V");
        if (verified != 1) {
            throw new PatchException(
                    "持久缓存清理挂钩验证失败: " + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, verified,
                "schedule maintenance only for cache namespaces used by this build");
    }
}

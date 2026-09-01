package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * 从 {@code CombatMain.main} 第一条指令开始计时，并标记调用 {@code AppDriver.begin} 的边界。
 */
public final class CombatMainStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatMain.class";

    @Override
    public String id() {
        return "startup-profile-combat-main";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.PROFILING;
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
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        MethodNode main = StartupProfilePatchSupport.requireMethod(
                classNode, "main", "([Ljava/lang/String;)V");
        main.instructions.insert(StartupProfilePatchSupport.initializeCall());

        MethodInsnNode begin = StartupProfilePatchSupport.requireCall(
                main, "com/fs/state/AppDriver", "begin", "()V");
        main.instructions.insertBefore(begin, StartupProfilePatchSupport.phaseCall(
                "end", "combat_main.bootstrap_before_app_driver"));
        main.maxStack += 1;

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 2, 2, verified,
                "initialize at CombatMain.main entry; end bootstrap immediately before AppDriver.begin");
    }
}

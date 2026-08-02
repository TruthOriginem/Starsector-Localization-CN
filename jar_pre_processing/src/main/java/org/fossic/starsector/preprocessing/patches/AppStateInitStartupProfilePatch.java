package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;
import java.util.Set;

/**
 * 计时 AppDriver 在进入标题画面前初始化的 CampaignState 与 CombatState。
 */
public final class AppStateInitStartupProfilePatch implements JarPatch {
    private static final Map<String, String> PHASES = Map.of(
            "com/fs/starfarer/campaign/CampaignState.class", "campaign_state.init",
            "com/fs/starfarer/combat/CombatState.class", "combat_state.init"
    );

    @Override
    public String id() {
        return "startup-profile-app-state-init";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return PHASES.keySet();
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        String phase = PHASES.get(context.classPath());
        if (phase == null) {
            throw new PatchException("未配置 AppState phase: " + context.classPath());
        }

        MethodNode init = StartupProfilePatchSupport.requireMethod(
                classNode, "init", "(Ljava/util/Map;)V");
        init.instructions.insert(StartupProfilePatchSupport.phaseCall("start", phase));

        int returns = StartupProfilePatchSupport.countOpcode(init, Opcodes.RETURN);
        if (returns < 1) {
            throw new PatchException(context.classPath() + " init 没有 RETURN");
        }
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                init.instructions.insertBefore(
                        instruction, StartupProfilePatchSupport.phaseCall("end", phase));
            }
        }
        init.maxStack += 1;

        int expected = 1 + returns;
        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), expected, expected, verified,
                "time " + phase + " across " + returns + " normal return path(s)");
    }
}

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

import java.util.Set;

/**
 * 分别计时标题状态的 AppState 初始化和标题战斗场景/UI prepare。
 */
public final class TitleScreenStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/title/TitleScreenState.class";

    @Override
    public String id() {
        return "startup-profile-title-screen";
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
        MethodNode init = StartupProfilePatchSupport.requireMethod(
                classNode, "init", "(Ljava/util/Map;)V");
        instrumentSimpleDuration(init, "title_state.init", false);

        MethodNode prepare = StartupProfilePatchSupport.requireMethod(
                classNode, "prepare", "()V");
        instrumentSimpleDuration(prepare, "title.prepare", true);
        prepare.maxStack += 1;

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 5, 5, verified,
                "time TitleScreenState.init and prepare; begin first-frame wait at prepare return");
    }

    private static void instrumentSimpleDuration(MethodNode method, String phase,
                                                 boolean startFirstFrameWait) {
        method.instructions.insert(StartupProfilePatchSupport.phaseCall("start", phase));
        int returns = StartupProfilePatchSupport.countOpcode(method, Opcodes.RETURN);
        if (returns != 1) {
            throw new PatchException(method.name + method.desc + " RETURN 数异常: "
                    + returns + "，预期 1");
        }
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            if (startFirstFrameWait) {
                method.instructions.insertBefore(instruction, StartupProfilePatchSupport.phasePair(
                        "end", phase,
                        "start", "title.prepare_to_first_frame"));
            } else {
                method.instructions.insertBefore(
                        instruction, StartupProfilePatchSupport.phaseCall("end", phase));
            }
        }
        method.maxStack += 1;
    }
}

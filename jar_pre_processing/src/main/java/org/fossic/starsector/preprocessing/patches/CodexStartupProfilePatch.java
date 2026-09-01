package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
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
 * 计时标题状态初始化期间同步构建的完整 Codex。
 */
public final class CodexStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/api/impl/codex/CodexDataV2.class";

    @Override
    public String id() {
        return "startup-profile-codex";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.PROFILING;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.API_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        MethodNode init = StartupProfilePatchSupport.requireMethod(classNode, "init", "()V");
        init.instructions.insert(StartupProfilePatchSupport.phaseCall("start", "codex.init"));

        int returns = StartupProfilePatchSupport.countOpcode(init, Opcodes.RETURN);
        if (returns != 1) {
            throw new PatchException("CodexDataV2.init RETURN 数异常: "
                    + returns + "，预期 1");
        }
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                init.instructions.insertBefore(
                        instruction, StartupProfilePatchSupport.phaseCall("end", "codex.init"));
            }
        }
        init.maxStack += 1;

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 2, 2, verified,
                "time CodexDataV2.init including all three mod callback rounds");
    }
}

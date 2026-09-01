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
 * 计时实际执行脚本反射检查与 Janino 编译的后台 Runnable。
 */
public final class ScriptStoreWorkerStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/scripts/ScriptStore$3.class";

    @Override
    public String id() {
        return "startup-profile-script-worker";
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
        MethodNode run = StartupProfilePatchSupport.requireMethod(classNode, "run", "()V");
        run.instructions.insert(StartupProfilePatchSupport.phaseCall(
                "start", "script_store.worker"));

        int returns = StartupProfilePatchSupport.countOpcode(run, Opcodes.RETURN);
        if (returns != 1) {
            throw new PatchException("ScriptStore$3.run RETURN 数异常: "
                    + returns + "，预期 1");
        }
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                run.instructions.insertBefore(instruction, StartupProfilePatchSupport.phaseCall(
                        "end", "script_store.worker"));
            }
        }
        run.maxStack += 1;

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 2, 2, verified,
                "time ScriptStore worker Runnable.run");
    }
}

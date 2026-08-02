package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * 在主循环唯一的 {@code Display.update(true)} 返回后报告已实际显示的一帧。
 */
public final class FirstTitleFrameStartupProfilePatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/BaseGameState.class";

    @Override
    public String id() {
        return "startup-profile-first-title-frame";
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
        MethodNode traverse = StartupProfilePatchSupport.requireMethod(
                classNode, "traverse", "()Ljava/lang/String;");
        MethodInsnNode displayUpdate = StartupProfilePatchSupport.requireCall(
                traverse, "org/lwjgl/opengl/Display", "update", "(Z)V");

        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                StartupProfilePatchSupport.PROFILER_OWNER,
                "onFrame",
                "(Ljava/lang/Object;)V",
                false
        ));
        traverse.instructions.insert(displayUpdate, hook);
        traverse.maxStack = Math.max(traverse.maxStack, 1);

        int verified = StartupProfilePatchSupport.countProfilerCalls(classNode);
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "call StartupProfiler.onFrame(this) after Display.update(true)");
    }
}

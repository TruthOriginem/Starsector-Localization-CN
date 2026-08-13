package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TextFieldImeHookPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/new";
    private static final String PROCESS_DESC = "(Lcom/fs/starfarer/util/A/new;)V";
    private static final String RELEASE_DESC = "(Lcom/fs/starfarer/util/A/C;)V";
    private static final String HOOKS = "org/fossic/starsector/ime/ImeHooks";

    @Test
    void injectsProcessingAtEntryAndCleanupImmediatelyBeforeReleaseReturn() {
        ClassNode classNode = completeClass();

        new TextFieldImeHookPatch().applyAndVerify(classNode, context()).requireSuccess();

        MethodNode process = method(classNode, "processInputImpl", PROCESS_DESC);
        assertEquals(Opcodes.ALOAD, process.instructions.getFirst().getOpcode());
        MethodInsnNode processHook = assertInstanceOf(
                MethodInsnNode.class,
                process.instructions.getFirst().getNext()
        );
        assertEquals(HOOKS, processHook.owner);
        assertEquals("onProcessInput", processHook.name);

        MethodNode release = method(classNode, "releaseFocus", RELEASE_DESC);
        AbstractInsnNode returnInsn = release.instructions.getLast();
        assertEquals(Opcodes.RETURN, returnInsn.getOpcode());
        MethodInsnNode releaseHook = assertInstanceOf(MethodInsnNode.class, returnInsn.getPrevious());
        assertEquals(HOOKS, releaseHook.owner);
        assertEquals("onFocusReleased", releaseHook.name);
    }

    @Test
    void rejectsMissingReleaseFocusMethod() {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;
        classNode.methods.add(voidMethod("processInputImpl", PROCESS_DESC));

        assertThrows(PatchException.class,
                () -> new TextFieldImeHookPatch().applyAndVerify(classNode, context()));
    }

    @Test
    void rejectsAmbiguousReleaseFocusControlFlow() {
        ClassNode classNode = completeClass();
        method(classNode, "releaseFocus", RELEASE_DESC).instructions.insert(new InsnNode(Opcodes.RETURN));

        assertThrows(PatchException.class,
                () -> new TextFieldImeHookPatch().applyAndVerify(classNode, context()));
    }

    private static ClassNode completeClass() {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;
        classNode.methods.add(voidMethod("processInputImpl", PROCESS_DESC));
        classNode.methods.add(voidMethod("releaseFocus", RELEASE_DESC));
        return classNode;
    }

    private static MethodNode voidMethod(String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode method(ClassNode classNode, String name, String descriptor) {
        return classNode.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst()
                .orElseThrow();
    }

    private static PatchContext context() {
        return new PatchContext("starfarer_obf.jar", TARGET_CLASS + ".class");
    }
}

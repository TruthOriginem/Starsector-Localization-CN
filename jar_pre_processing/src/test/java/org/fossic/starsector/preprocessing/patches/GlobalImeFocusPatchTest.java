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
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GlobalImeFocusPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/W";
    private static final String FRAME_METHOD = "o00000";
    private static final String FRAME_DESC = "(Lcom/fs/starfarer/util/A/new;)V";
    private static final String FOCUS_DESC = "()Lcom/fs/starfarer/ui/supersuper;";
    private static final String HOOKS = "org/fossic/starsector/ime/ImeHooks";
    private static final String OWNER_DESC = "(Lcom/fs/starfarer/ui/supersuper;)V";
    private static final String[][] MUTATIONS = {
            {"o00000", "(Ljava/util/List;)V"},
            {"class", OWNER_DESC},
            {"Ø00000", OWNER_DESC},
            {"o00000", OWNER_DESC},
            {"Ô00000", OWNER_DESC},
            {"new", OWNER_DESC},
            {"new", "()V"}
    };

    @Test
    void injectsFrameHookUsingTheExistingFocusedLocal() {
        ClassNode classNode = classWithFrameMethod(1, true);

        new GlobalImeFocusPatch().applyAndVerify(classNode, context()).requireSuccess();

        MethodNode method = classNode.methods.get(0);
        AbstractInsnNode store = method.instructions.getFirst().getNext();
        assertEquals(Opcodes.ASTORE, store.getOpcode());
        VarInsnNode load = assertInstanceOf(VarInsnNode.class, store.getNext());
        assertEquals(Opcodes.ALOAD, load.getOpcode());
        assertEquals(((VarInsnNode) store).var, load.var);
        MethodInsnNode hook = assertInstanceOf(MethodInsnNode.class, load.getNext());
        assertEquals(Opcodes.INVOKESTATIC, hook.getOpcode());
        assertEquals(HOOKS, hook.owner);
        assertEquals("onGlobalInputFrame", hook.name);
        assertEquals("(Ljava/lang/Object;)V", hook.desc);

        for (String[] target : MUTATIONS) {
            MethodNode mutation = method(classNode, target[0], target[1]);
            AbstractInsnNode returnInsn = mutation.instructions.getLast();
            MethodInsnNode focusHook = assertInstanceOf(
                    MethodInsnNode.class, returnInsn.getPrevious());
            assertEquals(HOOKS, focusHook.owner);
            assertEquals("onGlobalFocusChanged", focusHook.name);
            MethodInsnNode focusGetter = assertInstanceOf(
                    MethodInsnNode.class, focusHook.getPrevious());
            assertEquals(TARGET_CLASS, focusGetter.owner);
            assertEquals("Ó00000", focusGetter.name);
            assertEquals(FOCUS_DESC, focusGetter.desc);
        }
    }

    @Test
    void rejectsMissingFocusGetter() {
        ClassNode classNode = classWithFrameMethod(0, true);

        assertThrows(PatchException.class,
                () -> new GlobalImeFocusPatch().applyAndVerify(classNode, context()));
    }

    @Test
    void rejectsAmbiguousFocusGetter() {
        ClassNode classNode = classWithFrameMethod(2, true);

        assertThrows(PatchException.class,
                () -> new GlobalImeFocusPatch().applyAndVerify(classNode, context()));
    }

    @Test
    void rejectsGetterNotStoredInALocal() {
        ClassNode classNode = classWithFrameMethod(1, false);

        assertThrows(PatchException.class,
                () -> new GlobalImeFocusPatch().applyAndVerify(classNode, context()));
    }

    @Test
    void rejectsMutationWithUnexpectedAdditionalReturn() {
        ClassNode classNode = classWithFrameMethod(1, true);
        method(classNode, MUTATIONS[0][0], MUTATIONS[0][1])
                .instructions.insert(new InsnNode(Opcodes.RETURN));

        assertThrows(PatchException.class,
                () -> new GlobalImeFocusPatch().applyAndVerify(classNode, context()));
    }

    private static ClassNode classWithFrameMethod(int getterCount, boolean storeResult) {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FRAME_METHOD,
                FRAME_DESC,
                null,
                null);
        for (int i = 0; i < getterCount; i++) {
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    TARGET_CLASS,
                    "Ó00000",
                    FOCUS_DESC,
                    false));
            method.instructions.add(storeResult
                    ? new VarInsnNode(Opcodes.ASTORE, 2 + i)
                    : new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);
        for (String[] mutation : MUTATIONS) {
            classNode.methods.add(voidMethod(mutation[0], mutation[1]));
        }
        return classNode;
    }

    private static MethodNode voidMethod(String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, descriptor, null, null);
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

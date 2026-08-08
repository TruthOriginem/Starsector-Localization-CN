package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IntelPutFirstTagIdPatchTest {
    private static final String TARGET_METHOD_DESC = "(Lcom/fs/starfarer/campaign/command/M;Z)V";
    private static final String TAG_SPEC = "com/fs/starfarer/loading/C";
    private static final String NAME_GETTER = "Object";
    private static final String ID_GETTER = "Õ00000";
    private static final String STRING_GETTER_DESC = "()Ljava/lang/String;";

    @Test
    void replacesOnlyNameGetterUsedAsPutFirstCountingKey() {
        ClassNode classNode = classWithMethod(1, true);

        PatchResult result = apply(classNode);
        result.requireSuccess();

        MethodNode method = classNode.methods.get(0);
        assertEquals(1, countCalls(method, TAG_SPEC, ID_GETTER, STRING_GETTER_DESC));
        assertEquals(2, countCalls(method, TAG_SPEC, NAME_GETTER, STRING_GETTER_DESC));
    }

    @Test
    void rejectsClassWithoutExpectedPutFirstSequence() {
        ClassNode classNode = classWithMethod(0, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAmbiguousMultiplePutFirstSequences() {
        ClassNode classNode = classWithMethod(2, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsUnexpectedDisplayNameGetterCount() {
        ClassNode classNode = classWithMethod(1, false);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new IntelPutFirstTagIdPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode classWithMethod(int putFirstSequences, boolean includeDisplayNameReads) {
        ClassNode classNode = new ClassNode();
        classNode.name = "com/fs/starfarer/campaign/comms/v2/EventsPanel";
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "o00000",
                TARGET_METHOD_DESC,
                null,
                null
        );
        for (int i = 0; i < putFirstSequences; i++) {
            addPutFirstSequence(method);
        }
        if (includeDisplayNameReads) {
            addDisplayNameRead(method);
            addDisplayNameRead(method);
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);
        return classNode;
    }

    private static void addPutFirstSequence(MethodNode method) {
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/fs/starfarer/api/util/CountingMap"));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, TAG_SPEC));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TAG_SPEC, NAME_GETTER, STRING_GETTER_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/api/util/CountingMap",
                "add",
                "(Ljava/lang/Object;I)V",
                false
        ));
    }

    private static void addDisplayNameRead(MethodNode method) {
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, TAG_SPEC));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TAG_SPEC, NAME_GETTER, STRING_GETTER_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.POP));
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}

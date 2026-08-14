package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NewGameSeedFieldWidthPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/save/null";
    private static final String CONSTRUCTOR_DESC =
            "(Lcom/fs/starfarer/campaign/save/return;Lcom/fs/starfarer/ui/interfacenew;)V";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String POSITION_DESC = "Lcom/fs/starfarer/ui/OO0O;";

    @Test
    void widensFieldAndMovesPasteButtonBySameAmount() {
        ClassNode classNode = classWithSeedLayout(1, true);

        PatchResult result = apply(classNode);
        result.requireSuccess();

        MethodNode constructor = classNode.methods.get(0);
        assertEquals(0, countFloat(constructor, 185.0f));
        assertEquals(1, countFloat(constructor, 210.0f));
        assertEquals(1, countShiftAfterPastePlacement(constructor, 25.0f));
    }

    @Test
    void rejectsMissingPastePlacement() {
        ClassNode classNode = classWithSeedLayout(1, false);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAmbiguousSeedWidth() {
        ClassNode classNode = classWithSeedLayout(2, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsMissingSeedLabelAnchor() {
        ClassNode classNode = classWithSeedLayout(1, true);
        ((LdcInsnNode) classNode.methods.get(0).instructions.getFirst()).cst = "other";

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new NewGameSeedFieldWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode classWithSeedLayout(int widthAnchors, boolean includePastePlacement) {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;
        MethodNode constructor = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "<init>",
                CONSTRUCTOR_DESC,
                null,
                null
        );
        constructor.instructions.add(new LdcInsnNode("Domain sector registry ID: "));
        constructor.instructions.add(new InsnNode(Opcodes.POP));
        constructor.instructions.add(new LdcInsnNode("graphics/fonts/victor14.fnt"));
        constructor.instructions.add(new InsnNode(Opcodes.POP));

        if (includePastePlacement) {
            constructor.instructions.add(new LdcInsnNode(40.0f));
            constructor.instructions.add(call(
                    "belowRight",
                    "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)" + POSITION_DESC
            ));
            constructor.instructions.add(new InsnNode(Opcodes.POP));
        }

        for (int i = 0; i < widthAnchors; i++) {
            addSeedWidthLayout(constructor);
        }
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(constructor);
        return classNode;
    }

    private static void addSeedWidthLayout(MethodNode method) {
        method.instructions.add(new LdcInsnNode(185.0f));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        method.instructions.add(call("setSize", "(FF)" + POSITION_DESC));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                TARGET_CLASS,
                "paste",
                "Lcom/fs/starfarer/ui/n;"
        ));
        method.instructions.add(new LdcInsnNode(3.0f));
        method.instructions.add(call(
                "leftOfMid",
                "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)" + POSITION_DESC
        ));
        method.instructions.add(new InsnNode(Opcodes.POP));
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POSITION, name, desc, false);
    }

    private static int countFloat(MethodNode method, float value) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float actual
                    && Float.compare(actual, value) == 0) {
                count++;
            }
        }
        return count;
    }

    private static int countShiftAfterPastePlacement(MethodNode method, float value) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof MethodInsnNode call)
                    || !"belowRight".equals(call.name)) {
                continue;
            }
            AbstractInsnNode amount = node.getNext();
            AbstractInsnNode shift = amount == null ? null : amount.getNext();
            if (amount instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float actual
                    && Float.compare(actual, value) == 0
                    && shift instanceof MethodInsnNode shiftCall
                    && "setXAlignOffset".equals(shiftCall.name)
                    && "(F)".concat(POSITION_DESC).equals(shiftCall.desc)) {
                count++;
            }
        }
        return count;
    }
}

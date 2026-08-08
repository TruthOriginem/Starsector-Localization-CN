package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WindowDecorationPhysicalResolutionPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatMain";

    @Test
    void replacesLogicalToolkitWidthAndHeightWithPhysicalDesktopMode() {
        ClassNode classNode = classWithDimensions(1, 1);

        PatchResult result = apply(classNode);
        result.requireSuccess();

        MethodNode method = classNode.methods.get(0);
        assertEquals(1, countDisplayModeReads(method, "getWidth"));
        assertEquals(1, countDisplayModeReads(method, "getHeight"));
        assertEquals(0, countDimensionFields(method, "width"));
        assertEquals(0, countDimensionFields(method, "height"));
        assertEquals(0, countToolkitCalls(method));
    }

    @Test
    void rejectsMissingHeightRead() {
        ClassNode classNode = classWithDimensions(1, 0);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAmbiguousDuplicateWidthRead() {
        ClassNode classNode = classWithDimensions(2, 1);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new WindowDecorationPhysicalResolutionPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode classWithDimensions(int widthReads, int heightReads) {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null
        );
        for (int i = 0; i < widthReads; i++) {
            addToolkitDimensionRead(method, "width");
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        for (int i = 0; i < heightReads; i++) {
            addToolkitDimensionRead(method, "height");
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);
        return classNode;
    }

    private static void addToolkitDimensionRead(MethodNode method, String fieldName) {
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/awt/Toolkit",
                "getDefaultToolkit",
                "()Ljava/awt/Toolkit;",
                false
        ));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/awt/Toolkit",
                "getScreenSize",
                "()Ljava/awt/Dimension;",
                false
        ));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "java/awt/Dimension",
                fieldName,
                "I"
        ));
    }

    private static int countDisplayModeReads(MethodNode method, String getterName) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "org/lwjgl/opengl/DisplayMode".equals(call.owner)
                    && getterName.equals(call.name)
                    && "()I".equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int countDimensionFields(MethodNode method, String fieldName) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && "java/awt/Dimension".equals(field.owner)
                    && fieldName.equals(field.name)
                    && "I".equals(field.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int countToolkitCalls(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && "java/awt/Toolkit".equals(call.owner)) {
                count++;
            }
        }
        return count;
    }
}

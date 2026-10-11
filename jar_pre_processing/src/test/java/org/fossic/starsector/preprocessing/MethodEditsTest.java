package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;

final class MethodEditsTest {
    @Test void copiesHaveIndependentInstructionsLabelsFramesAndHandlers() {
        var source = new MethodNode(Opcodes.ACC_STATIC, "source", "()V", null, null);
        var start = new LabelNode();
        var end = new LabelNode();
        var handler = new LabelNode();
        source.instructions.add(start);
        source.instructions.add(new JumpInsnNode(Opcodes.GOTO, end));
        source.instructions.add(end);
        source.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        source.instructions.add(new InsnNode(Opcodes.RETURN));
        source.instructions.add(handler);
        source.instructions.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"}));
        source.instructions.add(new InsnNode(Opcodes.ATHROW));
        source.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        MethodNode copy = MethodEdits.copy(source, "copy");
        assertEquals("copy", copy.name);
        assertEquals(source.instructions.size(), copy.instructions.size());
        for (int i = 0; i < source.instructions.size(); i++) assertNotSame(source.instructions.get(i), copy.instructions.get(i));
        assertNotSame(source.tryCatchBlocks.get(0).start, copy.tryCatchBlocks.get(0).start);
        assertSame(copy.instructions.get(2), ((JumpInsnNode) copy.instructions.get(1)).label);
        copy.instructions.remove(copy.instructions.get(1));
        assertInstanceOf(JumpInsnNode.class, source.instructions.get(1));
    }

    @Test void replacementClearsCodeMetadataAndPreservesDeclaration() {
        var method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, new String[]{"java/io/IOException"});
        var start = new LabelNode(); var end = new LabelNode();
        method.instructions.add(start); method.instructions.add(new InsnNode(Opcodes.RETURN)); method.instructions.add(end);
        method.localVariables = new ArrayList<>();
        method.localVariables.add(new LocalVariableNode("old", "I", null, start, end, 0));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, null));
        method.visibleLocalVariableAnnotations = new ArrayList<>();
        method.visibleLocalVariableAnnotations.add(new LocalVariableAnnotationNode(TypeReference.LOCAL_VARIABLE << 24,
                null, new LabelNode[]{start}, new LabelNode[]{end}, new int[]{0}, "Ltest/A;"));
        method.invisibleLocalVariableAnnotations = new ArrayList<>(method.visibleLocalVariableAnnotations);
        InsnList body = new InsnList(); body.add(new InsnNode(Opcodes.RETURN));
        MethodEdits.replaceBody(method, body, 0, 0);
        assertTrue(method.tryCatchBlocks.isEmpty()); assertTrue(method.localVariables.isEmpty());
        assertTrue(method.visibleLocalVariableAnnotations.isEmpty()); assertTrue(method.invisibleLocalVariableAnnotations.isEmpty());
        assertEquals("java/io/IOException", method.exceptions.get(0));
        assertEquals(1, method.instructions.size());
    }

    @Test void localAllocationAccountsForWideValuesAndRejectsInvalidRequests() {
        MethodNode method = new MethodNode(); method.maxLocals = 3;
        assertEquals(3, MethodEdits.allocateLocal(method, Type.LONG_TYPE));
        assertEquals(5, MethodEdits.allocateLocal(method, Type.INT_TYPE));
        assertEquals(6, MethodEdits.allocateLocal(method, Type.DOUBLE_TYPE));
        assertEquals(8, method.maxLocals);
        assertThrows(PatchException.class, () -> MethodEdits.allocateLocal(method, Type.VOID_TYPE));
        method.maxLocals = 65535;
        assertThrows(PatchException.class, () -> MethodEdits.allocateLocal(method, Type.INT_TYPE));
    }
}

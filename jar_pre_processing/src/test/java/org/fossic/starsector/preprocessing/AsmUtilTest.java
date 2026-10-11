package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class AsmUtilTest {
    @Test void navigationSkipsMetadataAndHasExplicitBoundarySemantics() {
        InsnList list = new InsnList();
        var label = new LabelNode();
        var instruction = new InsnNode(Opcodes.RETURN);
        list.add(label);
        list.add(new LineNumberNode(1, label));
        list.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        list.add(instruction);
        assertSame(instruction, AsmUtil.nextReal(label));
        assertNull(AsmUtil.previousReal(instruction));
        assertNull(AsmUtil.nextReal(null));
        assertThrows(PatchException.class, () -> AsmUtil.requireNextReal(instruction));
    }

    @Test void exactMatchesRejectMissingAndDuplicateMembers() {
        ClassNode node = new ClassNode();
        node.name = "Owner";
        var method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        node.methods.add(method);
        node.methods.add(new MethodNode(Opcodes.ACC_STATIC, "m", "(I)V", null, null));
        assertSame(method, AsmUtil.requireMethod(node, "m", "()V"));
        assertThrows(PatchException.class, () -> AsmUtil.requireMethod(node, "missing", "()V"));
        node.methods.add(method);
        assertThrows(PatchException.class, () -> AsmUtil.requireMethod(node, "m", "()V"));
        node.fields.add(new FieldNode(Opcodes.ACC_STATIC, "f", "I", null, null));
        assertNotNull(AsmUtil.requireField(node, "f", "I"));
        assertThrows(PatchException.class, () -> AsmUtil.requireField(node, "f", "J"));
    }

    @Test void callAndVariableChecksKeepOpcodeAndDescriptorConstraints() {
        var method = new MethodNode();
        var call = new MethodInsnNode(Opcodes.INVOKESTATIC, "Owner", "m", "()V", false);
        method.instructions.add(call);
        assertSame(call, AsmUtil.requireCall(method, Opcodes.INVOKESTATIC, "Owner", "m", "()V"));
        assertThrows(PatchException.class, () -> AsmUtil.requireCall(method, Opcodes.INVOKEVIRTUAL, "Owner", "m", "()V"));
        assertThrows(PatchException.class, () -> AsmUtil.requireVar(new VarInsnNode(Opcodes.ALOAD, 2), Opcodes.ILOAD, "argument"));
    }
}

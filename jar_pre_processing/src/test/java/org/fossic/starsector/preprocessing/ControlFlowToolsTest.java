package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

final class ControlFlowToolsTest {
    private static ClassNode owner(MethodNode method) {
        ClassNode node = new ClassNode(); node.version = Opcodes.V17;
        node.name = "test/Hooks"; node.superName = "java/lang/Object"; node.access = Opcodes.ACC_PUBLIC;
        node.methods.add(method); return node;
    }

    private static MethodNode method(String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "method", desc, null, null);
        if (desc.equals("()V")) method.instructions.add(new InsnNode(Opcodes.RETURN));
        else { method.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); method.instructions.add(new InsnNode(Opcodes.ARETURN)); }
        method.maxStack = desc.equals("()V") ? 0 : 1;
        return method;
    }

    private static InsnList code(int... opcodes) {
        InsnList code = new InsnList(); for (int op : opcodes) code.add(new InsnNode(op)); return code;
    }

    private static void verify(MethodNode method) {
        ClassWriter writer = new ClassWriter(0); owner(method).accept(writer);
        BytecodeVerifier.verify(writer.toByteArray());
    }

    @Test void entryGuardsProvideFramesAndMeasuredStackCapacity() {
        MethodNode method = method("()V");
        EntryHooks.returnIfTrue("test/Hooks", method, code(Opcodes.ICONST_1), code(Opcodes.RETURN));
        assertEquals(1, method.maxStack); verify(method);
        MethodNode reference = method("()Ljava/lang/Object;");
        EntryHooks.returnIfNonNull("test/Hooks", reference, code(Opcodes.ACONST_NULL));
        assertEquals(2, reference.maxStack); verify(reference);
    }

    @Test void preservesExistingEntryFrameWithoutCreatingDuplicateOffset() {
        MethodNode method = method("()V");
        var entry = new InsnList(); entry.add(new LabelNode()); entry.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(entry);
        EntryHooks.returnIfTrue("test/Hooks", method, code(Opcodes.ICONST_1), code(Opcodes.RETURN));
        assertEquals(1, Arrays.stream(method.instructions.toArray()).filter(FrameNode.class::isInstance).count());
        verify(method);
    }

    @Test void rejectsUnsupportedEntryFragmentsBeforeMutation() {
        MethodNode method = method("()V");
        var writes = code(Opcodes.ICONST_1); writes.add(new VarInsnNode(Opcodes.ISTORE, 0)); writes.add(new InsnNode(Opcodes.ICONST_1));
        assertThrows(PatchException.class, () -> EntryHooks.returnIfTrue("Owner", method, writes, code(Opcodes.RETURN)));
        assertThrows(PatchException.class, () -> EntryHooks.returnIfTrue("Owner", method, code(Opcodes.ICONST_0, Opcodes.ICONST_1), code(Opcodes.RETURN)));
        assertThrows(PatchException.class, () -> EntryHooks.returnIfTrue("Owner", method, code(Opcodes.ACONST_NULL), code(Opcodes.RETURN)));
        assertThrows(PatchException.class, () -> EntryHooks.returnIfTrue("Owner", method, code(Opcodes.ICONST_1), code(Opcodes.ICONST_0, Opcodes.IRETURN)));
        assertEquals(1, method.instructions.size());
        method.name = "<init>";
        assertThrows(PatchException.class, () -> EntryHooks.returnIfTrue("Owner", method, code(Opcodes.ICONST_1), code(Opcodes.RETURN)));
    }

    @Test void framesDistinguishWideSlotsFromEntriesAndExpandRelativeFrames() {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "wide", "(JD[Ljava/lang/String;)V", null, null);
        assertEquals(List.of(Opcodes.LONG, Opcodes.DOUBLE, "[Ljava/lang/String;"), FrameSupport.initialLocals("Owner", method));
        assertEquals(5, FrameSupport.slots(FrameSupport.initialLocals("Owner", method)));
        assertEquals(List.of(Opcodes.LONG, Opcodes.DOUBLE, "[Ljava/lang/String;", Opcodes.TOP, "java/util/List"),
                FrameSupport.withLocalAfterArguments("Owner", method, 6, "java/util/List"));
        assertThrows(PatchException.class, () -> FrameSupport.withLocalAfterArguments("Owner", method, 4, "java/util/List"));
        var append = new FrameNode(Opcodes.F_APPEND, 1, new Object[]{Opcodes.INTEGER}, 0, null);
        var chop = new FrameNode(Opcodes.F_CHOP, 1, null, 0, null);
        method.instructions.add(append); method.instructions.add(chop);
        assertEquals(FrameSupport.initialLocals("Owner", method), FrameSupport.expandAt("Owner", method, chop).locals());
        assertEquals(Opcodes.F_FULL, FrameSupport.expandAt("Owner", method, chop).fullFrame().type);
    }

    @Test void removalRejectsAllRetainedReferencesAndLeavesMethodUntouched() {
        for (int kind = 0; kind < 7; kind++) {
            MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "test", "()V", null, null);
            LabelNode removed = new LabelNode(), end = new LabelNode();
            method.instructions.add(removed); method.instructions.add(new InsnNode(Opcodes.NOP)); method.instructions.add(end);
            switch (kind) {
                case 0 -> method.instructions.add(new JumpInsnNode(Opcodes.GOTO, removed));
                case 1 -> method.instructions.add(new TableSwitchInsnNode(0, 0, end, removed));
                case 2 -> method.instructions.add(new LookupSwitchInsnNode(end, new int[]{0}, new LabelNode[]{removed}));
                case 3 -> method.tryCatchBlocks.add(new TryCatchBlockNode(removed, end, end, null));
                case 4 -> {
                    method.localVariables = new ArrayList<>();
                    method.localVariables.add(new LocalVariableNode("v", "I", null, removed, end, 0));
                }
                case 5 -> method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, null, 1, new Object[]{removed}));
                case 6 -> {
                    method.visibleLocalVariableAnnotations = new ArrayList<>();
                    method.visibleLocalVariableAnnotations.add(new LocalVariableAnnotationNode(TypeReference.LOCAL_VARIABLE << 24, null,
                            new LabelNode[]{removed}, new LabelNode[]{end}, new int[]{0}, "Ltest/A;"));
                }
            }
            int size = method.instructions.size();
            assertThrows(PatchException.class, () -> InstructionRanges.remove(method, removed, removed));
            assertEquals(size, method.instructions.size());
        }
    }

    @Test void straightLineMoveRejectsMetadataAndReversedIntervals() {
        MethodNode method = new MethodNode();
        var first = new InsnNode(Opcodes.NOP); var label = new LabelNode(); var last = new InsnNode(Opcodes.RETURN);
        method.instructions.add(first); method.instructions.add(label); method.instructions.add(last);
        assertThrows(PatchException.class, () -> InstructionRanges.detachStraightLine(method, first, last));
        assertThrows(PatchException.class, () -> InstructionRanges.remove(method, last, first));
        InsnList detached = InstructionRanges.detachStraightLine(method, first, first);
        assertSame(first, detached.getFirst()); assertEquals(2, method.instructions.size());
    }
}

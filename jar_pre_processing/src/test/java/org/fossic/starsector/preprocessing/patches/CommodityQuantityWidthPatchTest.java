package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

final class CommodityQuantityWidthPatchTest {
    @Test
    void widensBothPanelModesAndReservesMatchingIconSpace() throws Exception {
        ClassNode node = CampaignDateWidthPatchTest.readClass("starfarer_obf.jar", CommodityQuantityWidthPatch.PANEL_TARGET);
        apply(node);
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("sizeChanged")).findFirst().orElseThrow();
        var code = AsmUtil.instructions(method);
        assertEquals(1, code.stream().filter(i -> AsmUtil.isFloatLdc(i, 34f)).count());
        assertEquals(1, code.stream().filter(i -> AsmUtil.isFloatLdc(i, 26f)).count());
        assertEquals(1, code.stream().filter(i -> AsmUtil.isFloatLdc(i, 24f)).count());
        int reservations = 0;
        for (int i = 0; i + 2 < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && call.name.equals("getWidth") && call.owner.equals(CommodityQuantityWidthPatch.PANEL_TARGET)) {
                assertTrue(AsmUtil.isFloatLdc(code.get(i + 1), 2f));
                assertEquals(org.objectweb.asm.Opcodes.FSUB, code.get(i + 2).getOpcode());
                reservations++;
            }
        }
        assertEquals(1, reservations);
        assertNoWrap(node, 1);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        new ClassReader(writer.toByteArray());
    }

    @Test
    void rejectsPanelWithMissingWidthAnchor() throws Exception {
        ClassNode node = CampaignDateWidthPatchTest.readClass("starfarer_obf.jar", CommodityQuantityWidthPatch.PANEL_TARGET);
        for (MethodNode method : node.methods) for (var insn : AsmUtil.instructions(method)) {
            if (AsmUtil.isFloatLdc(insn, 32f)) ((LdcInsnNode) insn).cst = 33f;
        }
        assertThrows(PatchException.class, () -> apply(node));
    }
    @Test
    void changesOnlyTheTwoFixedWidthsLeavingIconPlacementIntact() throws Exception {
        ClassNode node = readClass();
        ClassWriter before = new ClassWriter(0);
        node.accept(before);
        apply(node);
        assertNoWrap(node, 2);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("getRenderer")
                        && instruction.getNext().getNext() instanceof MethodInsnNode wrap && wrap.name.equals("return") && wrap.desc.equals("(Z)V")) {
                    var start = instruction.getPrevious();
                    var end = instruction.getNext().getNext();
                    method.instructions.remove(start);
                    method.instructions.remove(instruction.getNext());
                    method.instructions.remove(end);
                    method.instructions.remove(instruction);
                    method.maxStack -= 2;
                }
                if (AsmUtil.isFloatLdc(instruction, 26f)) {
                    ((LdcInsnNode) instruction).cst = 24f;
                    count++;
                }
            }
        }
        assertEquals(2, count);
        ClassWriter after = new ClassWriter(0);
        node.accept(after);
        assertArrayEquals(before.toByteArray(), after.toByteArray());
        apply(node);
        ClassWriter patched = new ClassWriter(0);
        node.accept(patched);
        new ClassReader(patched.toByteArray());
    }

    @Test
    void rejectsChangedWidthBeforeMakingPartialChanges() throws Exception {
        ClassNode node = readClass();
        outer: for (MethodNode method : node.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (AsmUtil.isFloatLdc(instruction, 24f)) {
                    ((LdcInsnNode) instruction).cst = 25f;
                    break outer;
                }
            }
        }
        assertThrows(PatchException.class, () -> apply(node));
    }

    private static void assertNoWrap(ClassNode node, int expected) {
        int count = 0;
        for (MethodNode method : node.methods) for (var instruction : AsmUtil.instructions(method)) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("return") && call.desc.equals("(Z)V")) {
                assertEquals(org.objectweb.asm.Opcodes.ICONST_0, instruction.getPrevious().getOpcode());
                count++;
            }
        }
        assertEquals(expected, count);
    }

    private static ClassNode readClass() throws Exception {
        return CampaignDateWidthPatchTest.readClass("starfarer_obf.jar", CommodityQuantityWidthPatch.TARGET);
    }

    private static void apply(ClassNode node) {
        new CommodityQuantityWidthPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", node.name + ".class")).requireSuccess();
    }
}

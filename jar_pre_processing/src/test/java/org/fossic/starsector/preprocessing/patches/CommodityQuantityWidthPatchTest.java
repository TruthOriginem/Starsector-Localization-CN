package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

final class CommodityQuantityWidthPatchTest {
    @Test
    void changesOnlyTheTwoFixedWidthsLeavingIconPlacementIntact() throws Exception {
        ClassNode node = readClass();
        ClassWriter before = new ClassWriter(0);
        node.accept(before);
        apply(node);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (AsmUtil.isFloatLdc(instruction, 28f)) {
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

    private static ClassNode readClass() throws Exception {
        return CampaignDateWidthPatchTest.readClass("starfarer_obf.jar", CommodityQuantityWidthPatch.TARGET);
    }

    private static void apply(ClassNode node) {
        new CommodityQuantityWidthPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", node.name + ".class")).requireSuccess();
    }
}

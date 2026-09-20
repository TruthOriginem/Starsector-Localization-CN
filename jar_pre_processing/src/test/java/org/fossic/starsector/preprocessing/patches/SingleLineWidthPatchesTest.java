package org.fossic.starsector.preprocessing.patches;

import java.util.*;
import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

final class SingleLineWidthPatchesTest {
    record Case(JarPatch patch, String owner, Map<String, Integer> labels) {}

    @Test
    void onlyWidenedLabelsDisableWrappingAndPatchedClassesLink() throws Exception {
        var cases = List.of(
            new Case(new CombatTargetInfoWidthPatch(), "com/fs/starfarer/renderers/A/null", Map.of("oO0000", 1, "ôO0000", 1)),
            new Case(new CombatPlayerStatusValueWidthPatch(), "com/fs/starfarer/class/new/return", Map.of("do.return$public", 1, "ôOÕo00", 1)),
            new Case(new CombatCommandShipInfoValueWidthPatch(), "com/fs/starfarer/combat/new/H", Map.of("ôôOo00", 1, "super.return$do", 1, "ÒôOo00", 2)),
            new Case(new CombatHudCounterWidthPatch(), "com/fs/starfarer/renderers/A/G", Map.of("private.do$public", 1)),
            new Case(new CombatHudCounterWidthPatch(), "com/fs/starfarer/class/new/B", Map.of("ÕØÔo00", 1)),
            new Case(new FleetCardCrTextWidthPatch(), "com/fs/starfarer/coreui/O0oo$o", Map.of("String.null$return", 1)),
            new Case(new SubmarketTitleWidthPatch(), "com/fs/starfarer/campaign/ui/ooOO", Map.of("String.void$int", 1)),
            new Case(new NewGameSeedFieldWidthPatch(), "com/fs/starfarer/campaign/save/null", Map.of("OôoO00", 1))
        );
        for (Case item : cases) {
            ClassNode node = CampaignDateWidthPatchTest.readClass("starfarer_obf.jar", item.owner());
            assertTrue(noWrapFields(node).isEmpty(), item.owner());
            item.patch().applyAndVerify(node, new PatchContext("starfarer_obf.jar", item.owner() + ".class")).requireSuccess();
            assertEquals(item.labels(), noWrapFields(GameDataPatchVerifier.roundTrip(node)), item.owner());
            GameDataPatchVerifier.verifyWithJvm(node);
        }
    }

    private static Map<String, Integer> noWrapFields(ClassNode node) {
        Map<String, Integer> result = new HashMap<>();
        for (MethodNode method : node.methods) {
            var code = AsmUtil.instructions(method);
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof MethodInsnNode call) || !call.owner.equals(SingleLineLabel.RENDERER)
                        || !call.name.equals("return") || !call.desc.equals("(Z)V")) continue;
                assertEquals(Opcodes.ICONST_0, code.get(i - 1).getOpcode());
                assertTrue(code.get(i - 2) instanceof MethodInsnNode getter && getter.name.equals("getRenderer"));
                int fieldIndex = i - 3;
                if (code.get(fieldIndex) instanceof MethodInsnNode input) {
                    assertEquals("getTextLabel", input.name);
                    assertEquals(SingleLineLabel.INPUT, input.owner);
                    fieldIndex--;
                }
                assertTrue(code.get(fieldIndex) instanceof FieldInsnNode);
                FieldInsnNode field = (FieldInsnNode) code.get(fieldIndex);
                assertEquals(node.name, field.owner);
                assertEquals(Opcodes.GETFIELD, field.getOpcode());
                result.merge(field.name, 1, Integer::sum);
            }
        }
        return result;
    }

    @Test
    void refusesAnUnrelatedOrUnknownWidthReceiverBeforeChangingCode() {
        MethodNode method = new MethodNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "Example", "unrelated", "Ljava/lang/Object;"));
        LdcInsnNode width = new LdcInsnNode(42f);
        method.instructions.add(width);
        assertThrows(PatchException.class, () -> SingleLineLabel.beforeWidth(method, width));
        assertEquals(3, method.instructions.size());
    }
}

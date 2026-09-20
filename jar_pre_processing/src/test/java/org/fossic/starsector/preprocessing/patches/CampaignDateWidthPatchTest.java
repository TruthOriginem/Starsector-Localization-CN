package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CampaignDateWidthPatchTest {
    static final String TARGET = "com/fs/starfarer/campaign/ui/Oo0o";
    static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);

    @Test
    void disablesWrappingForAllThreeValuesBeforeTheirFinalSizing() throws Exception {
        ClassNode node = readClass("starfarer_obf.jar", TARGET);
        new CampaignDateWidthPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", TARGET + ".class")).requireSuccess();
        Map<String, Integer> disabled = new HashMap<>();
        for (MethodNode method : node.methods) {
            var instructions = AsmUtil.instructions(method);
            for (int i = 3; i < instructions.size(); i++) {
                if (instructions.get(i) instanceof MethodInsnNode call
                        && call.owner.equals(RENDERER) && call.name.equals("return")
                        && call.desc.equals("(Z)V")) {
                    assertEquals("<init>", method.name);
                    assertEquals(Opcodes.ICONST_0, instructions.get(i - 1).getOpcode());
                    MethodInsnNode getter = (MethodInsnNode) instructions.get(i - 2);
                    assertEquals("getRenderer", getter.name);
                    FieldInsnNode label = (FieldInsnNode) instructions.get(i - 3);
                    FieldInsnNode assignment = (FieldInsnNode) instructions.get(i - 5);
                    assertEquals(Opcodes.PUTFIELD, assignment.getOpcode());
                    assertEquals(label.name, assignment.name);
                    disabled.merge(label.name, 1, Integer::sum);
                }
            }
        }
        assertEquals(Map.of("OOOo00", 1, "do.this$do", 1, "ø0Oo00", 1), disabled);
        // Check the obfuscated API against the real game, not merely our emitted call.
        ClassNode renderer = readClass("fs.common_obf.jar", RENDERER);
        MethodNode setter = renderer.methods.stream().filter(m -> m.name.equals("return")
                && m.desc.equals("(Z)V")).findFirst().orElseThrow();
        var setterCode = AsmUtil.instructions(setter);
        assertEquals("ÖÒ0000", ((FieldInsnNode) setterCode.get(2)).name);
        assertEquals(Opcodes.PUTFIELD, setterCode.get(2).getOpcode());
        ClassNode labelClass = readClass("starfarer_obf.jar", "com/fs/starfarer/ui/d");
        assertEquals(1, labelClass.methods.stream().filter(m -> m.name.equals("getRenderer")
                && m.desc.equals("()L" + RENDERER + ";")).count());
    }

    @Test
    void rejectsMissingMonthAssignmentInsteadOfSilentlyLeavingItWrapping() throws Exception {
        ClassNode node = readClass("starfarer_obf.jar", TARGET);
        for (MethodNode method : node.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("do.this$do")) {
                    field.name = "unexpectedMonthField";
                }
            }
        }
        assertThrows(PatchException.class, () -> new CampaignDateWidthPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", TARGET + ".class")));
    }

    @Test
    void widensOnlyTheMonthBeyondExistingLocalizedWidths() throws Exception {
        ClassNode node = readClass("starfarer_obf.jar", TARGET);
        new CampaignDateWidthPatch().applyAndVerify(node,
                new PatchContext("starfarer_obf.jar", TARGET + ".class")).requireSuccess();
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        ClassNode roundTrip = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(roundTrip, 0);
        Map<String, Float> widths = new HashMap<>();
        for (MethodNode method : roundTrip.methods) {
            var instructions = AsmUtil.instructions(method);
            for (int i = 0; i + 1 < instructions.size(); i++) {
                if (instructions.get(i) instanceof FieldInsnNode field
                        && field.desc.equals("Lcom/fs/starfarer/ui/d;")
                        && instructions.get(i + 1) instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Float width) {
                    widths.put(field.name, width);
                }
            }
        }
        assertEquals(Map.of("OOOo00", 100f, "do.this$do", 55f, "ø0Oo00", 50f), widths);
    }

    static ClassNode readClass(String jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(Path.of("..", "game data", jar).toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(name + ".class"))).accept(node, 0);
            return node;
        }
    }
}

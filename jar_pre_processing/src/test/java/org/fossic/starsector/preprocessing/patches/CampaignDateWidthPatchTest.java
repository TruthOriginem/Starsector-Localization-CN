package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CampaignDateWidthPatchTest {
    static final String TARGET = "com/fs/starfarer/campaign/ui/Oo0o";

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

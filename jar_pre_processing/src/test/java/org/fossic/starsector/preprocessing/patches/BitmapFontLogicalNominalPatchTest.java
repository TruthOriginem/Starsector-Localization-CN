package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BitmapFontLogicalNominalPatchTest {
    private static final String HOOK =
            "org/fossic/starsector/dynfont/DynFontRenderHooks";

    @Test
    void preservesRawGetterAndMakesPublicGetterLogical() throws Exception {
        ClassNode font = readRealFont();

        PatchResult result = new BitmapFontLogicalNominalPatch().applyAndVerify(
                font, new PatchContext("fs.common_obf.jar", font.name + ".class"));

        result.requireSuccess();
        assertEquals(1, methods(font, BitmapFontLogicalNominalPatch.RAW_NOMINAL_GETTER));
        assertEquals(1, calls(font, HOOK, "logicalNominal", "(Ljava/lang/Object;I)I"));
    }

    @Test
    void rejectsFontWhenNominalGetterIsNoLongerSimpleFieldRead() throws Exception {
        ClassNode font = readRealFont();
        MethodNode getter = method(font, BitmapFontLogicalNominalPatch.NOMINAL_GETTER);
        FieldInsnNode field = null;
        for (var insn : getter.instructions) {
            if (insn instanceof FieldInsnNode candidate) {
                field = candidate;
                break;
            }
        }
        if (field == null) throw new IllegalStateException("nominal getter has no field");
        field.owner = "test/DriftedFont";

        assertThrows(PatchException.class,
                () -> new BitmapFontLogicalNominalPatch().applyAndVerify(
                        font, new PatchContext("fs.common_obf.jar", font.name + ".class")));
    }

    private static ClassNode readRealFont() throws Exception {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar").toAbsolutePath();
        String entryName = BitmapFontLogicalNominalPatch.FONT_CLASS + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) throw new IllegalStateException("missing " + entryName);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> name.equals(method.name) && "()I".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static long methods(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> name.equals(method.name) && "()I".equals(method.desc))
                .count();
    }

    private static int calls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                        && name.equals(call.name) && desc.equals(call.desc)) count++;
            }
        }
        return count;
    }
}

package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.awt.Color;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RendererHighlightColorNullPatchTest {
    private static final String COLOR_ARRAY_SETTER_DESC = "([Ljava/awt/Color;)V";

    @Test
    void patchesTheExactReal098Rc8RendererAndRemainsAsmReadable() throws Exception {
        ClassNode renderer = readRealRenderer();
        RendererHighlightColorNullPatch patch = new RendererHighlightColorNullPatch();

        PatchResult result = patch.applyAndVerify(
                renderer, context(renderer));

        result.requireSuccess();
        assertEquals(PatchGroup.LOCALIZATION, patch.group());
        assertEquals(1, countMethods(renderer,
                RendererHighlightColorNullPatch.INJECTED_METHOD,
                RendererHighlightColorNullPatch.NORMALIZE_COLORS_DESC));
        assertEquals(1, countCalls(renderer,
                RendererHighlightColorNullPatch.RENDERER_CLASS,
                RendererHighlightColorNullPatch.INJECTED_METHOD,
                RendererHighlightColorNullPatch.NORMALIZE_COLORS_DESC));

        ClassWriter writer = new ClassWriter(0);
        renderer.accept(writer);
        new ClassReader(writer.toByteArray()).accept(new ClassNode(), 0);
    }

    @Test
    void leavesNullAndCompleteArraysUntouched() {
        Color first = new Color(10, 20, 30, 40);
        Color second = new Color(50, 60, 70, 80);
        Color[] complete = {first, second};

        assertNull(RendererHighlightColorNullPatch
                .normalizeHighlightColorsTemplate(null, Color.YELLOW));
        assertSame(complete, RendererHighlightColorNullPatch
                .normalizeHighlightColorsTemplate(complete, Color.YELLOW));
        assertArrayEquals(new Color[]{first, second}, complete);
    }

    @Test
    void replacesNullEntriesWithoutMutatingTheCallerArray() {
        Color first = new Color(10, 20, 30, 40);
        Color fallback = new Color(90, 100, 110, 120);
        Color[] source = {first, null, null};

        Color[] normalized = RendererHighlightColorNullPatch
                .normalizeHighlightColorsTemplate(source, fallback);

        assertNotSame(source, normalized);
        assertArrayEquals(new Color[]{first, fallback, fallback}, normalized);
        assertArrayEquals(new Color[]{first, null, null}, source);
        assertSame(first, normalized[0]);
        assertSame(fallback, normalized[1]);
        assertSame(fallback, normalized[2]);
    }

    @Test
    void fallsBackToWhiteIfTheRendererDefaultIsUnexpectedlyNull() {
        Color[] normalized = RendererHighlightColorNullPatch
                .normalizeHighlightColorsTemplate(new Color[]{null}, null);

        assertArrayEquals(new Color[]{Color.WHITE}, normalized);
    }

    @Test
    void rejectsRendererWhenHighlightColorArrayStorageDrifts() throws Exception {
        ClassNode renderer = readRealRenderer();
        MethodNode setter = renderer.methods.stream()
                .filter(method -> COLOR_ARRAY_SETTER_DESC.equals(method.desc))
                .findFirst()
                .orElseThrow();
        FieldInsnNode storage = null;
        for (AbstractInsnNode insn : setter.instructions) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && "[Ljava/awt/Color;".equals(field.desc)) {
                storage = field;
                break;
            }
        }
        if (storage == null) throw new IllegalStateException("missing highlight color storage");
        storage.owner = "test/DriftedRenderer";

        assertThrows(PatchException.class, () ->
                new RendererHighlightColorNullPatch().applyAndVerify(
                        renderer, context(renderer)));
    }

    @Test
    void rejectsApplyingThePatchTwice() throws Exception {
        ClassNode renderer = readRealRenderer();
        RendererHighlightColorNullPatch patch = new RendererHighlightColorNullPatch();
        PatchContext context = context(renderer);
        patch.applyAndVerify(renderer, context).requireSuccess();

        assertThrows(PatchException.class, () ->
                patch.applyAndVerify(renderer, context));
    }

    private static ClassNode readRealRenderer() throws Exception {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar").toAbsolutePath();
        String entryName = RendererHighlightColorNullPatch.RENDERER_CLASS + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) throw new IllegalStateException("missing " + entryName);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static PatchContext context(ClassNode renderer) {
        return new PatchContext("fs.common_obf.jar", renderer.name + ".class");
    }

    private static int countMethods(ClassNode node, String name, String desc) {
        return (int) node.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .count();
    }

    private static int countCalls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }
}

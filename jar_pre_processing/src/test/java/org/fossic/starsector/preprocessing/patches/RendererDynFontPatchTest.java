package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RendererDynFontPatchTest {
    private static final String HOOK = "org/fossic/starsector/dynfont/DynFontQuadHooks";
    private static final String FONT = "com/fs/graphics/A/F";

    @Test
    void patchesReal098Rc8RendererAtAllExactProxyAnchors() throws Exception {
        ClassNode renderer = readRealRenderer();

        PatchResult result = new RendererDynFontPatch().applyAndVerify(
                renderer, new PatchContext("fs.common_obf.jar", renderer.name + ".class"));

        result.requireSuccess();
        assertEquals(12, countCalls(renderer, HOOK, "transform", "(FF)J"));
        assertEquals(1, countCalls(renderer, HOOK, "begin",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertEquals(1, countCalls(renderer, HOOK, "translate", "(FF)V"));
        assertEquals(12, countCalls(renderer, FONT,
                BitmapFontLogicalNominalPatch.RAW_NOMINAL_GETTER, "()I"));
        // 字体 setter 的默认字号必须保留公开 getter，取得逻辑 nominal。
        assertEquals(1, countCalls(renderer, FONT,
                BitmapFontLogicalNominalPatch.NOMINAL_GETTER, "()I"));
    }

    @Test
    void rejectsRealRendererWhenGlyphVertexStructureDrifts() throws Exception {
        ClassNode renderer = readRealRenderer();
        MethodInsnNode vertex = firstVertex(renderer);
        vertex.owner = "test/DriftedGL";

        assertThrows(PatchException.class, () -> new RendererDynFontPatch().applyAndVerify(
                renderer, new PatchContext("fs.common_obf.jar", renderer.name + ".class")));
    }

    private static ClassNode readRealRenderer() throws Exception {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar").toAbsolutePath();
        String entryName = RendererDynFontPatch.RENDERER_CLASS + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) throw new IllegalStateException("missing " + entryName);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static MethodInsnNode firstVertex(ClassNode node) {
        for (var method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/lwjgl/opengl/GL11".equals(call.owner)
                        && "glVertex2f".equals(call.name) && "(FF)V".equals(call.desc)) {
                    return call;
                }
            }
        }
        throw new IllegalStateException("real renderer has no glVertex2f");
    }

    private static int countCalls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (var method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                        && name.equals(call.name) && desc.equals(call.desc)) count++;
            }
        }
        return count;
    }
}

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

final class RendererHighlightRegexPatchTest {
    private static final String PATTERN = "java/util/regex/Pattern";
    private static final String HOOK =
            "org/fossic/starsector/dynfont/DynFontHighlightHooks";
    private static final String COMPILE_DESC =
            "(Ljava/lang/String;)Ljava/util/regex/Pattern;";

    @Test
    void redirectsBothReal098Rc8RegexFallbacksToSafeCompiler() throws Exception {
        ClassNode renderer = readRealRenderer();

        PatchResult result = new RendererHighlightRegexPatch().applyAndVerify(
                renderer, new PatchContext("fs.common_obf.jar", renderer.name + ".class"));

        result.requireSuccess();
        assertEquals(0, countCalls(renderer, PATTERN, "compile", COMPILE_DESC));
        assertEquals(2, countCalls(renderer, HOOK, "compileFallback", COMPILE_DESC));
    }

    @Test
    void rejectsRendererWhenOneFallbackStructureDrifts() throws Exception {
        ClassNode renderer = readRealRenderer();
        MethodInsnNode compile = firstCall(renderer, PATTERN, "compile", COMPILE_DESC);
        compile.owner = "test/DriftedPattern";

        assertThrows(PatchException.class, () ->
                new RendererHighlightRegexPatch().applyAndVerify(
                        renderer,
                        new PatchContext("fs.common_obf.jar", renderer.name + ".class")));
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

    private static MethodInsnNode firstCall(ClassNode node, String owner,
                                            String name, String desc) {
        for (var method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && owner.equals(call.owner) && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    return call;
                }
            }
        }
        throw new IllegalStateException("missing call " + owner + "." + name + desc);
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

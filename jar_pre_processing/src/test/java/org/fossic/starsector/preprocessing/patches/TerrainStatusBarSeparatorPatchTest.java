package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TerrainStatusBarSeparatorPatchTest {
    @Test
    void changesOnlyTheReal098Rc8SeparatorConstant() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = recreate(classNode);
        LdcInsnNode comma = literals(method, ",").get(0);
        int instructions = method.instructions.size();
        int labels = countNodes(method, LabelNode.class);
        int frames = countNodes(method, FrameNode.class);
        int maxLocals = method.maxLocals;
        int maxStack = method.maxStack;

        PatchResult result = apply(classNode);

        result.requireSuccess();
        assertEquals(" ", comma.cst);
        assertEquals(instructions, method.instructions.size());
        assertEquals(labels, countNodes(method, LabelNode.class));
        assertEquals(frames, countNodes(method, FrameNode.class));
        assertEquals(maxLocals, method.maxLocals);
        assertEquals(maxStack, method.maxStack);
        assertSame(comma, literals(method, " ").get(0));
    }

    @Test
    void rejectsMissingSeparator() throws Exception {
        ClassNode classNode = readRealClass();
        literals(recreate(classNode), ",").get(0).cst = ";";

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAmbiguousSeparator() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = recreate(classNode);
        method.instructions.add(new LdcInsnNode(","));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new TerrainStatusBarSeparatorPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar").toAbsolutePath();
        String entry = "com/fs/starfarer/ui/newui/public.class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var zipEntry = zip.getEntry(entry);
            if (zipEntry == null) throw new IllegalStateException("missing " + entry);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zipEntry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode recreate(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "recreate".equals(method.name) && "()V".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static List<LdcInsnNode> literals(MethodNode method, String value) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof LdcInsnNode ldc && value.equals(ldc.cst))
                .map(LdcInsnNode.class::cast)
                .toList();
    }

    private static int countNodes(MethodNode method, Class<?> type) {
        return (int) AsmUtil.instructions(method).stream().filter(type::isInstance).count();
    }
}

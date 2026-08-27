package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TowCableTooltipWidthPatchTest {
    private static final String CLASS_NAME = "com/fs/starfarer/api/impl/campaign/TowCable";

    @Test
    void replacesTheReal098Rc8ZeroWidthWithTheStandardTooltipWidth() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        int maxStack = method.maxStack;
        int maxLocals = method.maxLocals;

        PatchResult result = apply(classNode);

        result.requireSuccess();
        List<AbstractInsnNode> instructions = executableInstructions(targetMethod(classNode));
        assertEquals(2, instructions.size());
        assertTrue(AsmUtil.isFloatLdc(instructions.get(0), 369.0f));
        assertEquals(Opcodes.FRETURN, instructions.get(1).getOpcode());
        assertEquals(maxStack, targetMethod(classNode).maxStack);
        assertEquals(maxLocals, targetMethod(classNode).maxLocals);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        ClassNode roundTripped = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(roundTripped, 0);
        assertTrue(AsmUtil.isFloatLdc(
                executableInstructions(targetMethod(roundTripped)).get(0), 369.0f));
    }

    @Test
    void rejectsAChangedReturnConstant() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        method.instructions.set(executableInstructions(method).get(0), new InsnNode(Opcodes.FCONST_1));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAdditionalExecutableInstructions() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        method.instructions.insertBefore(executableInstructions(method).get(1), new InsnNode(Opcodes.NOP));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAnAdditionalMatchingMethod() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode original = targetMethod(classNode);
        MethodNode duplicate = new MethodNode(
                original.access, original.name, original.desc, original.signature, null);
        duplicate.instructions.add(new InsnNode(Opcodes.FCONST_0));
        duplicate.instructions.add(new InsnNode(Opcodes.FRETURN));
        classNode.methods.add(duplicate);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new TowCableTooltipWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer.api.jar", classNode.name + ".class")
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer.api.jar").toAbsolutePath();
        String entry = CLASS_NAME + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var zipEntry = zip.getEntry(entry);
            if (zipEntry == null) throw new IllegalStateException("missing " + entry);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zipEntry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode targetMethod(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "getTooltipWidth".equals(method.name) && "()F".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static List<AbstractInsnNode> executableInstructions(MethodNode method) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() >= 0)
                .toList();
    }
}

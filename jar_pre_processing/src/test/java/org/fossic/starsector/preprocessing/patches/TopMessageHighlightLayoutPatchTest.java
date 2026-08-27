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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TopMessageHighlightLayoutPatchTest {
    private static final String CLASS_NAME = "com/fs/starfarer/campaign/ui/O00O$o";
    private static final String LABEL = "com/fs/starfarer/ui/d";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);

    @Test
    void sizesReal098Rc8MessageBeforeResolvingItsHighlight() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        int labels = countNodes(method, LabelNode.class);
        int frames = countNodes(method, FrameNode.class);
        int maxLocals = method.maxLocals;

        PatchResult result = apply(classNode);

        result.requireSuccess();
        MethodInsnNode setColor = calls(method, LABEL, "setColor", "(Ljava/awt/Color;)V").get(0);
        MethodInsnNode autoSize = calls(method, LABEL, "autoSize",
                "()L" + POSITION + ";").get(0);
        MethodInsnNode highlight = calls(method, RENDERER, "Ø00000",
                "(Ljava/lang/String;)V").get(0);
        assertTrue(indexOf(method, setColor) < indexOf(method, autoSize));
        assertTrue(indexOf(method, autoSize) < indexOf(method, highlight));
        assertEquals(labels, countNodes(method, LabelNode.class));
        assertEquals(frames, countNodes(method, FrameNode.class));
        assertEquals(maxLocals, method.maxLocals);
    }

    @Test
    void rejectsAClassThatAlreadySizesInsideMessageCreation() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        MethodInsnNode setColor = calls(method, LABEL, "setColor", "(Ljava/awt/Color;)V").get(0);
        method.instructions.insert(setColor, new MethodInsnNode(
                org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                LABEL, "autoSize", "()L" + POSITION + ";", false));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAChangedHighlightCall() throws Exception {
        ClassNode classNode = readRealClass();
        MethodInsnNode highlight = calls(targetMethod(classNode), RENDERER, "Ø00000",
                "(Ljava/lang/String;)V").get(0);
        highlight.name = "changed";

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new TopMessageHighlightLayoutPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar").toAbsolutePath();
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
                .filter(method -> "o00000".equals(method.name) && "()V".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc
    ) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .map(MethodInsnNode.class::cast)
                .toList();
    }

    private static int indexOf(MethodNode method, Object target) {
        return AsmUtil.instructions(method).indexOf(target);
    }

    private static int countNodes(MethodNode method, Class<?> type) {
        return (int) AsmUtil.instructions(method).stream().filter(type::isInstance).count();
    }
}

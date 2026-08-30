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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CampaignEntityTooltipHighlightLayoutPatchTest {
    private static final String LABEL = "com/fs/starfarer/ui/d";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);

    @Test
    void laysOutReal098Rc8FactionLineBeforeResolvingItsHighlights() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        int autoSizes = calls(method, LABEL, "autoSizeToWidth",
                "(F)L" + POSITION + ";").size();
        int labels = countNodes(method, LabelNode.class);
        int frames = countNodes(method, FrameNode.class);
        int maxLocals = method.maxLocals;
        int maxStack = method.maxStack;

        PatchResult result = apply(classNode);

        result.requireSuccess();
        MethodInsnNode highlight = uniqueCall(
                method, RENDERER, "o00000", "([Ljava/lang/String;)V");
        MethodInsnNode colors = uniqueCall(
                method, RENDERER, "o00000", "([Ljava/awt/Color;)V");
        MethodInsnNode layout = factionLineLayout(method, highlight);
        assertTrue(indexOf(method, layout) < indexOf(method, highlight));
        assertTrue(indexOf(method, highlight) < indexOf(method, colors));
        assertEquals(autoSizes, calls(method, LABEL, "autoSizeToWidth",
                "(F)L" + POSITION + ";").size());
        assertEquals(labels, countNodes(method, LabelNode.class));
        assertEquals(frames, countNodes(method, FrameNode.class));
        assertEquals(maxLocals, method.maxLocals);
        assertEquals(maxStack, method.maxStack);
        roundTrip(classNode);
    }

    @Test
    void rejectsAnAlreadyReorderedTooltip() throws Exception {
        ClassNode classNode = readRealClass();
        apply(classNode).requireSuccess();

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAChangedFactionLineWidthFormula() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = targetMethod(classNode);
        MethodInsnNode highlight = uniqueCall(
                method, RENDERER, "o00000", "([Ljava/lang/String;)V");
        MethodInsnNode layout = factionLineLayout(method, highlight);
        AbstractInsnNode subtract = previousExecutable(layout);
        method.instructions.set(subtract, new InsnNode(Opcodes.FADD));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAChangedHighlightCall() throws Exception {
        ClassNode classNode = readRealClass();
        MethodInsnNode highlight = uniqueCall(
                targetMethod(classNode), RENDERER, "o00000",
                "([Ljava/lang/String;)V");
        highlight.name = "changed";

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new CampaignEntityTooltipHighlightLayoutPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar").toAbsolutePath();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(CampaignEntityTooltipHighlightLayoutPatch.TARGET_CLASS);
            if (entry == null) {
                throw new IllegalStateException("missing "
                        + CampaignEntityTooltipHighlightLayoutPatch.TARGET_CLASS);
            }
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode targetMethod(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "while.float".equals(method.name)
                        && "()V".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static MethodInsnNode factionLineLayout(
            MethodNode method, MethodInsnNode highlight) {
        MethodInsnNode getRenderer = calls(
                method, LABEL, "getRenderer", "()L" + RENDERER + ";")
                .stream()
                .filter(call -> indexOf(method, call) < indexOf(method, highlight))
                .reduce((first, second) -> second)
                .orElseThrow();
        FieldInsnNode field = (FieldInsnNode) previousExecutable(getRenderer);
        return calls(method, LABEL, "autoSizeToWidth", "(F)L" + POSITION + ";")
                .stream()
                .filter(call -> {
                    AbstractInsnNode node = previousExecutable(call);
                    for (int count = 0; count < 5; count++) {
                        node = previousExecutable(node);
                    }
                    return node instanceof FieldInsnNode receiver
                            && field.owner.equals(receiver.owner)
                            && field.name.equals(receiver.name)
                            && field.desc.equals(receiver.desc);
                })
                .findFirst().orElseThrow();
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> matches = calls(method, owner, name, desc);
        if (matches.size() != 1) {
            throw new IllegalStateException("expected one call, found " + matches.size());
        }
        return matches.get(0);
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .map(MethodInsnNode.class::cast)
                .toList();
    }

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        return AsmUtil.instructions(method).indexOf(target);
    }

    private static int countNodes(MethodNode method, Class<?> type) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(type::isInstance).count();
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static void roundTrip(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        new ClassReader(writer.toByteArray()).accept(new ClassNode(), 0);
    }
}

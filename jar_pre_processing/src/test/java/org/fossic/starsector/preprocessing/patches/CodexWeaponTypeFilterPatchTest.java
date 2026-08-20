package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
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

final class CodexWeaponTypeFilterPatchTest {
    private static final String OUTER = "com/fs/starfarer/api/impl/codex/CodexDataV2";
    private static final String WEAPON_TYPE =
            "com/fs/starfarer/api/combat/WeaponAPI$WeaponType";
    private static final List<String> SPECIAL_TYPES =
            List.of("HYBRID", "COMPOSITE", "SYNERGY", "UNIVERSAL");

    @Test
    void guardsTheReal098Rc8PermissiveFallbackWithoutChangingControlFlow() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = matchesTags(classNode);
        int labels = countNodes(method, LabelNode.class);
        int frames = countNodes(method, FrameNode.class);
        int maxLocals = method.maxLocals;

        PatchResult result = apply(classNode);

        result.requireSuccess();
        assertEquals(labels, countNodes(method, LabelNode.class));
        assertEquals(frames, countNodes(method, FrameNode.class));
        assertEquals(maxLocals, method.maxLocals);
        assertEquals(8, countObjectEqualsCalls(method));
        for (String type : SPECIAL_TYPES) {
            assertEquals(3, countFieldReads(method, WEAPON_TYPE, type));
            assertEquals(2, countFieldReads(method, OUTER, type));
        }

        AbstractInsnNode lastReturn = lastExecutable(method);
        assertEquals(Opcodes.IRETURN, lastReturn.getOpcode());
        assertEquals(Opcodes.IOR, previousExecutable(lastReturn).getOpcode());
    }

    @Test
    void rejectsAChangedTerminalFallback() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = matchesTags(classNode);
        AbstractInsnNode lastReturn = lastExecutable(method);
        AbstractInsnNode fallback = previousExecutable(lastReturn);
        method.instructions.set(fallback, new InsnNode(Opcodes.ICONST_0));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAChangedSpecialTypeShape() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode method = matchesTags(classNode);
        FieldInsnNode field = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof FieldInsnNode candidate
                        && candidate.getOpcode() == Opcodes.GETSTATIC
                        && WEAPON_TYPE.equals(candidate.owner)
                        && "COMPOSITE".equals(candidate.name))
                .map(FieldInsnNode.class::cast)
                .findFirst()
                .orElseThrow();
        field.name = "DECORATIVE";

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new CodexWeaponTypeFilterPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer.api.jar", classNode.name + ".class")
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer.api.jar").toAbsolutePath();
        String entry = "com/fs/starfarer/api/impl/codex/CodexDataV2$21.class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var zipEntry = zip.getEntry(entry);
            if (zipEntry == null) throw new IllegalStateException("missing " + entry);
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zipEntry)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode matchesTags(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "matchesTags".equals(method.name)
                        && "(Ljava/util/Set;)Z".equals(method.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int countObjectEqualsCalls(MethodNode method) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Object".equals(call.owner)
                        && "equals".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc))
                .count();
    }

    private static int countFieldReads(MethodNode method, String owner, String name) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && owner.equals(field.owner)
                        && name.equals(field.name))
                .count();
    }

    private static int countNodes(MethodNode method, Class<?> type) {
        return (int) AsmUtil.instructions(method).stream().filter(type::isInstance).count();
    }

    private static AbstractInsnNode lastExecutable(MethodNode method) {
        AbstractInsnNode current = method.instructions.getLast();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}

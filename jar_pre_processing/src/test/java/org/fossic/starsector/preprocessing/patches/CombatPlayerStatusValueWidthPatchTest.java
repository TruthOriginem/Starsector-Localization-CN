package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

final class CombatPlayerStatusValueWidthPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/class/new/return.class";

    @Test
    void widensExactlyTheTwoRealHullAndFluxValueFields()
            throws Exception {
        ClassNode playerHud = load();

        apply(playerHud);
        ClassNode roundTripped = GameDataPatchVerifier.roundTrip(playerHud);
        MethodNode constructor = constructor(roundTripped);

        assertEquals(0, valueWidthAnchors(constructor, 4.0f).size());
        assertEquals(2, valueWidthAnchors(constructor, 8.0f).size());
    }

    @Test
    void rejectsTheRealClassWhenEitherValueWidthDrifts()
            throws Exception {
        ClassNode playerHud = load();
        MethodNode constructor = constructor(playerHud);
        valueWidthAnchors(constructor, 4.0f).get(0).cst = 5.0f;

        assertThrows(PatchException.class, () -> apply(playerHud));
    }

    private static void apply(ClassNode classNode) {
        new CombatPlayerStatusValueWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", TARGET))
                .requireSuccess();
    }

    private static MethodNode constructor(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && "()V".equals(method.desc))
                .findFirst()
                .orElseThrow();
    }

    private static List<LdcInsnNode> valueWidthAnchors(
            MethodNode method, float multiplier) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> AsmUtil.isFloatLdc(node, multiplier))
                .map(LdcInsnNode.class::cast)
                .filter(CombatPlayerStatusValueWidthPatchTest::matchesLayout)
                .toList();
    }

    private static boolean matchesLayout(LdcInsnNode multiplier) {
        boolean sawMultiply = false;
        boolean sawSetSize = false;
        boolean sawBarOffset = false;
        int remaining = 14;
        for (AbstractInsnNode node = multiplier.getNext();
             node != null && remaining-- > 0;
             node = node.getNext()) {
            if (node.getOpcode() == Opcodes.FMUL) {
                sawMultiply = true;
            } else if (node instanceof MethodInsnNode call
                    && "setSize".equals(call.name)
                    && "(FF)Lcom/fs/starfarer/ui/OO0O;".equals(call.desc)) {
                sawSetSize = true;
            } else if (AsmUtil.isFloatLdc(node, 83.0f)) {
                sawBarOffset = true;
            } else if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)) {
                return sawMultiply && sawSetSize && sawBarOffset;
            }
        }
        return false;
    }

    private static ClassNode load() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar")
                .toAbsolutePath();
        try (ZipFile input = new ZipFile(jar.toFile())) {
            var entry = input.getEntry(TARGET);
            if (entry == null) {
                throw new IllegalStateException("missing " + TARGET);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }
}

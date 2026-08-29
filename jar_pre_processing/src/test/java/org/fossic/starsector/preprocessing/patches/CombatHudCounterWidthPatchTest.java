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
import org.objectweb.asm.tree.VarInsnNode;
import org.junit.jupiter.api.Test;

final class CombatHudCounterWidthPatchTest {
    private static final String WEAPON_TARGET =
            "com/fs/starfarer/renderers/A/G.class";
    private static final String SYSTEM_TARGET =
            "com/fs/starfarer/class/new/B.class";

    @Test
    void weaponAmmoSupportsFourDigitsWithoutMovingItsRightEdge()
            throws Exception {
        ClassNode weapon = load(WEAPON_TARGET);

        apply(weapon, WEAPON_TARGET);
        MethodNode constructor = constructor(
                GameDataPatchVerifier.roundTrip(weapon),
                "(Lcom/fs/starfarer/combat/systems/o00O;Ljava/awt/Color;"
                        + "Lcom/fs/graphics/A/F;)V");

        assertEquals(0, rightAlignedWidths(constructor, 40.0f).size());
        assertEquals(1, rightAlignedWidths(constructor, 52.0f).size());
        assertEquals(2, nameWidthReductions(constructor, 12.0f));
        assertEquals(1, rightOfMidOffsets(constructor, 0.0f));
        assertEquals(40.0f, 52.0f - 12.0f);
    }

    @Test
    void systemUseCountSupportsThreeDigitsWithoutMovingItsRightEdge()
            throws Exception {
        ClassNode system = load(SYSTEM_TARGET);

        apply(system, SYSTEM_TARGET);
        MethodNode constructor = constructor(
                GameDataPatchVerifier.roundTrip(system),
                "(Ljava/awt/Color;Lcom/fs/graphics/A/F;)V");

        assertEquals(0, rightAlignedWidths(constructor, 40.0f).size());
        assertEquals(1, rightAlignedWidths(constructor, 45.0f).size());
        assertEquals(2, nameWidthReductions(constructor, 5.0f));
        assertEquals(1, rightOfMidOffsets(constructor, 0.0f));
        assertEquals(40.0f, 45.0f - 5.0f);
    }

    @Test
    void rejectsEitherRealClassWhenTheCounterWidthDrifts()
            throws Exception {
        for (String target : List.of(WEAPON_TARGET, SYSTEM_TARGET)) {
            ClassNode classNode = load(target);
            MethodNode constructor = classNode.methods.stream()
                    .filter(method -> "<init>".equals(method.name))
                    .filter(method -> !rightAlignedWidths(method, 40.0f).isEmpty())
                    .findFirst()
                    .orElseThrow();
            rightAlignedWidths(constructor, 40.0f).get(0).cst = 41.0f;

            assertThrows(PatchException.class,
                    () -> apply(classNode, target));
        }
    }

    private static void apply(ClassNode classNode, String target) {
        new CombatHudCounterWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", target))
                .requireSuccess();
    }

    private static MethodNode constructor(ClassNode classNode, String desc) {
        return classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && desc.equals(method.desc))
                .findFirst()
                .orElseThrow();
    }

    private static List<LdcInsnNode> rightAlignedWidths(
            MethodNode method, float width) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> AsmUtil.isFloatLdc(node, width))
                .map(LdcInsnNode.class::cast)
                .filter(CombatHudCounterWidthPatchTest::isRightAlignedWidth)
                .toList();
    }

    private static boolean isRightAlignedWidth(LdcInsnNode width) {
        boolean sawSetSize = false;
        int executable = 0;
        for (AbstractInsnNode node = width.getNext();
             node != null && executable < 12;
             node = node.getNext()) {
            if (node.getOpcode() < 0) continue;
            executable++;
            if (node instanceof MethodInsnNode call
                    && "setSize".equals(call.name)
                    && "(FF)Lcom/fs/starfarer/ui/OO0O;".equals(call.desc)) {
                sawSetSize = true;
            } else if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)) {
                return sawSetSize;
            }
        }
        return false;
    }

    private static int nameWidthReductions(MethodNode method, float delta) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.FLOAD) continue;
            AbstractInsnNode value = nextExecutable(load);
            AbstractInsnNode subtract = nextExecutable(value);
            if (AsmUtil.isFloatLdc(value, delta)
                    && subtract != null
                    && subtract.getOpcode() == Opcodes.FSUB) {
                count++;
            }
        }
        return count;
    }

    private static int rightOfMidOffsets(MethodNode method, float offset) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)) {
                AbstractInsnNode previous = previousExecutable(call);
                if (Float.compare(floatConstant(previous), offset) == 0) count++;
            }
        }
        return count;
    }

    private static float floatConstant(AbstractInsnNode node) {
        return switch (node.getOpcode()) {
            case Opcodes.FCONST_0 -> 0.0f;
            case Opcodes.FCONST_1 -> 1.0f;
            case Opcodes.FCONST_2 -> 2.0f;
            default -> node instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Float value ? value : Float.NaN;
        };
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        for (AbstractInsnNode next = node.getNext(); next != null;
             next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        for (AbstractInsnNode previous = node.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static ClassNode load(String target) throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar")
                .toAbsolutePath();
        try (ZipFile input = new ZipFile(jar.toFile())) {
            var entry = input.getEntry(target);
            if (entry == null) throw new IllegalStateException("missing " + target);
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }
}

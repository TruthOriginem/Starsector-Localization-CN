package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

final class CombatCommandShipInfoValueWidthPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/combat/new/H.class";
    private static final String CONSTRUCTOR_DESC =
            "(Lcom/fs/starfarer/combat/CombatFleetManager$O0;)V";

    @Test
    void widensAllFourRealValueFieldsAndPreservesRightAlignment()
            throws Exception {
        ClassNode commandInfo = load();

        apply(commandInfo);
        ClassNode roundTripped = GameDataPatchVerifier.roundTrip(commandInfo);
        MethodNode constructor = constructor(roundTripped);

        assertEquals(0, valueWidths(constructor, 4.0f).size());
        assertEquals(4, valueWidths(constructor, 5.0f).size());
        assertEquals(4, rightAlignedZeroAssignments(constructor).size());
    }

    @Test
    void rejectsRealClassWhenAnyValueWidthDrifts() throws Exception {
        ClassNode commandInfo = load();
        valueWidths(constructor(commandInfo), 4.0f).get(0).cst = 4.5f;

        assertThrows(PatchException.class, () -> apply(commandInfo));
    }

    @Test
    void rejectsRealClassWhenAnyValueLosesRightAlignment()
            throws Exception {
        ClassNode commandInfo = load();
        rightAlignedZeroAssignments(constructor(commandInfo)).get(0).name =
                "LMID";

        assertThrows(PatchException.class, () -> apply(commandInfo));
    }

    private static void apply(ClassNode classNode) {
        new CombatCommandShipInfoValueWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", TARGET))
                .requireSuccess();
    }

    private static MethodNode constructor(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && CONSTRUCTOR_DESC.equals(method.desc))
                .findFirst()
                .orElseThrow();
    }

    private static List<LdcInsnNode> valueWidths(
            MethodNode constructor, float multiplier) {
        List<LdcInsnNode> widths = new ArrayList<>();
        for (AbstractInsnNode node : constructor.instructions) {
            if (!(node instanceof LdcInsnNode width)
                    || !(width.cst instanceof Float actual)
                    || Float.compare(actual, multiplier) != 0) {
                continue;
            }
            AbstractInsnNode multiply = nextExecutable(width);
            AbstractInsnNode owner = nextExecutable(multiply);
            AbstractInsnNode lineHeight = nextExecutable(owner);
            AbstractInsnNode setSize = nextExecutable(lineHeight);
            AbstractInsnNode positionOwner = nextExecutable(setSize);
            AbstractInsnNode label = nextExecutable(positionOwner);
            AbstractInsnNode offset = nextExecutable(label);
            AbstractInsnNode rightOfMid = nextExecutable(offset);
            if (multiply != null
                    && multiply.getOpcode() == Opcodes.FMUL
                    && setSize instanceof MethodInsnNode sizeCall
                    && "setSize".equals(sizeCall.name)
                    && "(FF)Lcom/fs/starfarer/ui/OO0O;"
                            .equals(sizeCall.desc)
                    && offset instanceof LdcInsnNode offsetLdc
                    && Float.valueOf(83.0f).equals(offsetLdc.cst)
                    && rightOfMid instanceof MethodInsnNode positionCall
                    && "rightOfMid".equals(positionCall.name)) {
                widths.add(width);
            }
        }
        return widths;
    }

    private static List<FieldInsnNode> rightAlignedZeroAssignments(
            MethodNode constructor) {
        List<FieldInsnNode> alignments = new ArrayList<>();
        for (AbstractInsnNode node : constructor.instructions) {
            if (!AsmUtil.isStringLdc(node, "0")) continue;
            AbstractInsnNode alignment = nextExecutable(node);
            AbstractInsnNode factory = nextExecutable(alignment);
            AbstractInsnNode assignment = nextExecutable(factory);
            if (alignment instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "com/fs/starfarer/api/ui/Alignment"
                            .equals(field.owner)
                    && "RMID".equals(field.name)
                    && factory instanceof MethodInsnNode call
                    && "(Ljava/lang/String;"
                            .concat("Lcom/fs/starfarer/api/ui/Alignment;)")
                            .concat("Lcom/fs/starfarer/ui/d;")
                            .equals(call.desc)
                    && assignment instanceof FieldInsnNode target
                    && target.getOpcode() == Opcodes.PUTFIELD) {
                alignments.add(field);
            }
        }
        return alignments;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode next = node.getNext();
             next != null;
             next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
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

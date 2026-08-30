package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubmarketTitleWidthPatchTest {
    private static final String TARGET_CLASS_NAME =
            "com/fs/starfarer/campaign/ui/ooOO";
    private static final String LABEL_CLASS = "com/fs/starfarer/ui/d";
    private static final String POSITION_DESC = "Lcom/fs/starfarer/ui/OO0O;";

    @Test
    void givesRealSubmarketTitleTheFullAreaBesideItsIcon() throws Exception {
        ClassNode classNode = readRealClass();

        PatchResult result = apply(classNode);
        result.requireSuccess();

        MethodNode create = uniqueMethod(classNode, "create", "()V");
        assertEquals(0, countCalls(create, LABEL_CLASS, "autoSize", "()" + POSITION_DESC));
        assertEquals(1, countCalls(create, LABEL_CLASS, "autoSizeToWidth",
                "(F)" + POSITION_DESC));
        assertRemainingWidthFormula(create);
        GameDataPatchVerifier.roundTrip(classNode);
    }

    @Test
    void rejectsASecondTitleAutoSizeCall() throws Exception {
        ClassNode classNode = readRealClass();
        MethodNode create = uniqueMethod(classNode, "create", "()V");
        MethodInsnNode autoSize = uniqueCall(
                create, LABEL_CLASS, "autoSize", "()" + POSITION_DESC);
        create.instructions.insert(autoSize, new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                LABEL_CLASS,
                "autoSize",
                "()" + POSITION_DESC,
                false));

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new SubmarketTitleWidthPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", SubmarketTitleWidthPatch.TARGET_CLASS)
        );
    }

    private static ClassNode readRealClass() throws Exception {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar").toAbsolutePath();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(SubmarketTitleWidthPatch.TARGET_CLASS);
            if (entry == null) {
                throw new IllegalStateException(
                        "missing " + SubmarketTitleWidthPatch.TARGET_CLASS);
            }
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry)).accept(node, 0);
            return node;
        }
    }

    private static void assertRemainingWidthFormula(MethodNode method) {
        MethodInsnNode fit = uniqueCall(
                method, LABEL_CLASS, "autoSizeToWidth", "(F)" + POSITION_DESC);
        AbstractInsnNode node = previousExecutable(fit);
        assertEquals(Opcodes.FSUB, node.getOpcode());
        node = previousExecutable(node);
        assertFloat(node, 18.0f);
        node = previousExecutable(node);
        assertEquals(Opcodes.FSUB, node.getOpcode());
        node = previousExecutable(node);
        assertEquals(Opcodes.FMUL, node.getOpcode());
        node = previousExecutable(node);
        assertFloat(node, 1.6f);
        node = previousExecutable(node);
        assertEquals(Opcodes.FSUB, node.getOpcode());
        node = previousExecutable(node);
        assertFloat(node, 8.0f);
        node = previousExecutable(node);
        assertCall(node, TARGET_CLASS_NAME, "getHeight", "()F");
        node = previousExecutable(node);
        assertAloadZero(node);
        node = previousExecutable(node);
        assertCall(node, TARGET_CLASS_NAME, "getWidth", "()F");
        node = previousExecutable(node);
        assertAloadZero(node);
        node = previousExecutable(node);
        assertTrue(node instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD
                && TARGET_CLASS_NAME.equals(field.owner)
                && ("L" + LABEL_CLASS + ";").equals(field.desc));
    }

    private static void assertFloat(AbstractInsnNode node, float expected) {
        assertTrue(node instanceof LdcInsnNode ldc
                && ldc.cst instanceof Float actual
                && Float.compare(actual, expected) == 0);
    }

    private static void assertCall(
            AbstractInsnNode node, String owner, String name, String desc) {
        assertTrue(node instanceof MethodInsnNode call
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc));
    }

    private static void assertAloadZero(AbstractInsnNode node) {
        assertTrue(node instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ALOAD
                && load.var == 0);
    }

    private static MethodNode uniqueMethod(
            ClassNode classNode, String name, String desc) {
        var matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "expected one " + name + desc + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc) {
        var matches = method.instructions.iterator();
        MethodInsnNode result = null;
        int count = 0;
        while (matches.hasNext()) {
            AbstractInsnNode node = matches.next();
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                result = call;
                count++;
            }
        }
        if (count != 1) {
            throw new IllegalStateException(
                    "expected one call " + owner + "." + name + desc + ", found " + count);
        }
        return result;
    }

    private static int countCalls(
            MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        if (current == null) {
            throw new IllegalStateException("missing previous executable instruction");
        }
        return current;
    }
}

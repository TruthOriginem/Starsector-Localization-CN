package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainStatusBarSeparatorPatchTest {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/newui/public";
    private static final String PLUGIN =
            "com/fs/starfarer/api/campaign/CampaignTerrainPlugin";
    private static final String NAME_DESC = "()Ljava/lang/String;";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RIGHT_OF_MID_DESC =
            "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)Lcom/fs/starfarer/ui/OO0O;";

    @Test
    void insertsSeparatorBeforeEveryVisibleNameAfterTheFirst() {
        ClassNode classNode = classWithRecreateMethod(3, 1, true);
        MethodNode method = classNode.methods.get(0);
        int labelsBefore = countLabels(method);

        PatchResult result = apply(classNode);
        result.requireSuccess();

        assertEquals(1, countCalls(method, PLUGIN, "getTerrainName", NAME_DESC));
        assertEquals(1, countCalls(method, "java/lang/String", "isBlank", "()Z"));
        assertEquals(14, method.maxLocals);
        assertEquals(labelsBefore, countLabels(method));

        FrameNode visibleFrame = visibleFrame(method);
        assertEquals("java/lang/String", visibleFrame.local.get(0));
        assertEquals(PLUGIN, visibleFrame.local.get(1));

        JumpInsnNode nullGate = findJump(method, Opcodes.IFNULL, 8);
        JumpInsnNode blankGate = findCallFollowedByJump(
                method, "java/lang/String", "isBlank", "()Z", Opcodes.IFEQ);
        JumpInsnNode blankFallback = (JumpInsnNode) nextExecutable(blankGate);
        assertEquals(Opcodes.GOTO, blankFallback.getOpcode());
        assertSame(blankFallback.label, nullGate.label);
        assertSame(visibleFrameLabel(method), blankGate.label);

        JumpInsnNode previousVisibleGate = findJump(method, Opcodes.IFNULL, 13);
        assertSame(nullGate.label, previousVisibleGate.label);
        assertEquals(0, countVarJumpPairs(method, Opcodes.ILOAD, 8, Opcodes.IFNE));

        List<MethodInsnNode> positioningCalls = calls(
                method, POSITION, "rightOfMid", RIGHT_OF_MID_DESC);
        assertEquals(2, positioningCalls.size());
        assertEquals(13, positioningAnchor(positioningCalls.get(0)).var);
        assertEquals(12, positioningAnchor(positioningCalls.get(1)).var);

        VarInsnNode finalPreviousStore = finalPreviousStore(method);
        assertEquals(11, previousVar(finalPreviousStore).var);
    }

    @Test
    void rejectsMissingCommaConstruction() {
        ClassNode classNode = classWithRecreateMethod(3, 0, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsAmbiguousCommaConstruction() {
        ClassNode classNode = classWithRecreateMethod(3, 2, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsUnexpectedTerrainNameGetterCount() {
        ClassNode classNode = classWithRecreateMethod(2, 1, true);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    @Test
    void rejectsUnexpectedVisibleFrameShape() {
        ClassNode classNode = classWithRecreateMethod(3, 1, false);

        assertThrows(PatchException.class, () -> apply(classNode));
    }

    private static PatchResult apply(ClassNode classNode) {
        return new TerrainStatusBarSeparatorPatch().applyAndVerify(
                classNode,
                new PatchContext("starfarer_obf.jar", classNode.name + ".class")
        );
    }

    private static ClassNode classWithRecreateMethod(
            int terrainNameGetterCount,
            int commaBlocks,
            boolean expectedVisibleFrame
    ) {
        ClassNode classNode = new ClassNode();
        classNode.name = TARGET_CLASS;

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "recreate", "()V", null, null);
        method.maxLocals = 13;
        method.maxStack = 6;

        LabelNode visible = new LabelNode();
        LabelNode loopContinue = new LabelNode();

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        addNameGetter(method);
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, visible));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopContinue));
        method.instructions.add(visible);
        method.instructions.add(new FrameNode(
                Opcodes.F_APPEND,
                2,
                new Object[]{expectedVisibleFrame ? Opcodes.INTEGER : Opcodes.FLOAT, PLUGIN},
                0,
                null
        ));

        for (int i = 1; i < terrainNameGetterCount; i++) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
            addNameGetter(method);
            method.instructions.add(new InsnNode(Opcodes.POP));
        }

        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, loopContinue));
        for (int i = 0; i < commaBlocks; i++) {
            addCommaBlock(method);
        }
        method.instructions.add(loopContinue);
        method.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                8,
                new Object[]{TARGET_CLASS, "java/lang/Object", "com/fs/starfarer/ui/c",
                        Opcodes.FLOAT, "java/lang/String", Opcodes.INTEGER, Opcodes.TOP,
                        "java/util/Iterator"},
                0,
                null
        ));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);
        return classNode;
    }

    private static void addNameGetter(MethodNode method) {
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                PLUGIN,
                "getTerrainName",
                NAME_DESC,
                true
        ));
    }

    private static void addCommaBlock(MethodNode method) {
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "com/fs/starfarer/ui/d"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode(","));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/ui/d",
                "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/awt/Color;ZLcom/fs/starfarer/api/ui/Alignment;)V",
                false
        ));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 12));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 12));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TARGET_CLASS,
                "add",
                "(Lcom/fs/starfarer/ui/c;)Lcom/fs/starfarer/ui/OO0O;",
                false
        ));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 11));
        method.instructions.add(new InsnNode(Opcodes.FCONST_2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                POSITION,
                "rightOfMid",
                RIGHT_OF_MID_DESC,
                false
        ));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 12));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        return calls(method, owner, name, desc).size();
    }

    private static List<MethodInsnNode> calls(
            MethodNode method,
            String owner,
            String name,
            String desc
    ) {
        return AsmUtil.instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .toList();
    }

    private static int countLabels(MethodNode method) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(LabelNode.class::isInstance)
                .count();
    }

    private static FrameNode visibleFrame(MethodNode method) {
        LabelNode label = visibleFrameLabel(method);
        for (var node = label.getNext(); node != null; node = node.getNext()) {
            if (node instanceof FrameNode frame) {
                return frame;
            }
            if (node.getOpcode() >= 0) {
                break;
            }
        }
        throw new AssertionError("visible frame not found");
    }

    private static LabelNode visibleFrameLabel(MethodNode method) {
        return AsmUtil.instructions(method).stream()
                .filter(JumpInsnNode.class::isInstance)
                .map(JumpInsnNode.class::cast)
                .filter(jump -> jump.getOpcode() == Opcodes.IFEQ)
                .map(jump -> jump.label)
                .findFirst()
                .orElseThrow(() -> new AssertionError("visible label not found"));
    }

    private static JumpInsnNode findJump(MethodNode method, int opcode, int loadedVar) {
        return AsmUtil.instructions(method).stream()
                .filter(JumpInsnNode.class::isInstance)
                .map(JumpInsnNode.class::cast)
                .filter(jump -> jump.getOpcode() == opcode)
                .filter(jump -> {
                    var previous = previousExecutable(jump);
                    return previous instanceof VarInsnNode var && var.var == loadedVar;
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("jump not found"));
    }

    private static JumpInsnNode findCallFollowedByJump(
            MethodNode method,
            String owner,
            String name,
            String desc,
            int jumpOpcode
    ) {
        for (MethodInsnNode call : calls(method, owner, name, desc)) {
            var next = nextExecutable(call);
            if (next instanceof JumpInsnNode jump && jump.getOpcode() == jumpOpcode) {
                return jump;
            }
        }
        throw new AssertionError("call followed by jump not found");
    }

    private static int countVarJumpPairs(
            MethodNode method,
            int varOpcode,
            int varIndex,
            int jumpOpcode
    ) {
        int count = 0;
        for (var node : AsmUtil.instructions(method)) {
            if (node instanceof VarInsnNode var
                    && var.getOpcode() == varOpcode
                    && var.var == varIndex
                    && nextExecutable(var) instanceof JumpInsnNode jump
                    && jump.getOpcode() == jumpOpcode) {
                count++;
            }
        }
        return count;
    }

    private static VarInsnNode previousVar(org.objectweb.asm.tree.AbstractInsnNode node) {
        var previous = previousExecutable(node);
        if (previous instanceof VarInsnNode var) {
            return var;
        }
        throw new AssertionError("previous executable instruction is not a variable load");
    }

    private static VarInsnNode positioningAnchor(MethodInsnNode call) {
        var gap = previousExecutable(call);
        return previousVar(gap);
    }

    private static VarInsnNode finalPreviousStore(MethodNode method) {
        List<VarInsnNode> stores = AsmUtil.instructions(method).stream()
                .filter(VarInsnNode.class::isInstance)
                .map(VarInsnNode.class::cast)
                .filter(var -> var.getOpcode() == Opcodes.ASTORE && var.var == 2)
                .toList();
        assertTrue(!stores.isEmpty());
        return stores.get(stores.size() - 1);
    }

    private static org.objectweb.asm.tree.AbstractInsnNode previousExecutable(
            org.objectweb.asm.tree.AbstractInsnNode node
    ) {
        var current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode nextExecutable(
            org.objectweb.asm.tree.AbstractInsnNode node
    ) {
        var current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }
}

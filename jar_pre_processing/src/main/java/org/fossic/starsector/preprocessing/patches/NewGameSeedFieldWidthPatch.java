package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Give the new-game sector seed enough room for dynamic victor14 metrics.
 *
 * <p>The seed field is anchored immediately to the left of the Paste button.
 * Widening it alone would move its label to the left, so the button is shifted
 * right by exactly the same amount to keep the label's left edge unchanged.</p>
 */
public final class NewGameSeedFieldWidthPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/campaign/save/null.class";
    private static final String TARGET_CLASS_NAME = "com/fs/starfarer/campaign/save/null";
    private static final String TARGET_METHOD_NAME = "<init>";
    private static final String TARGET_METHOD_DESC =
            "(Lcom/fs/starfarer/campaign/save/return;Lcom/fs/starfarer/ui/interfacenew;)V";

    private static final float ORIGINAL_WIDTH = 185.0f;
    private static final float PATCHED_WIDTH = 210.0f;
    private static final float RIGHT_SHIFT = PATCHED_WIDTH - ORIGINAL_WIDTH;
    private static final String POSITION_OWNER = "com/fs/starfarer/ui/OO0O";
    private static final String POSITION_DESC = "Lcom/fs/starfarer/ui/OO0O;";

    @Override
    public String id() {
        return "new-game-seed-field-width";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        if (!TARGET_CLASS_NAME.equals(classNode.name)) {
            throw new PatchException(id() + " received unexpected class " + classNode.name);
        }

        MethodNode constructor = findConstructor(classNode, context);
        if (!containsString(constructor, "Domain sector registry ID: ")
                || !containsString(constructor, "graphics/fonts/victor14.fnt")) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": seed field anchors are missing");
        }

        List<LdcInsnNode> widthAnchors = findSeedWidths(constructor, ORIGINAL_WIDTH);
        if (widthAnchors.size() != 1) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": expected one seed width anchor, found " + widthAnchors.size());
        }

        LdcInsnNode width = widthAnchors.get(0);
        MethodInsnNode pastePlacement = findPastePlacement(width);
        if (pastePlacement == null) {
            throw new PatchException(id() + " failed for " + context.classPath()
                    + ": Paste button placement anchor is missing or ambiguous");
        }

        width.cst = PATCHED_WIDTH;
        constructor.instructions.insert(pastePlacement, new LdcInsnNode(RIGHT_SHIFT));
        constructor.instructions.insert(
                pastePlacement.getNext(),
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        POSITION_OWNER,
                        "setXAlignOffset",
                        "(F)" + POSITION_DESC,
                        false
                )
        );

        int patchedWidths = findSeedWidths(constructor, PATCHED_WIDTH).size();
        int shifts = countPlacementShift(constructor, pastePlacement);
        if (findSeedWidths(constructor, ORIGINAL_WIDTH).size() != 0
                || patchedWidths != 1
                || shifts != 1) {
            throw new PatchException(id() + " verification failed for " + context.classPath()
                    + ": widths=" + patchedWidths + ", shifts=" + shifts);
        }

        return PatchResult.of(id(), context.classPath(), 2, 2, 2,
                "seed field " + ORIGINAL_WIDTH + " -> " + PATCHED_WIDTH
                        + ", row shifted right by " + RIGHT_SHIFT);
    }

    private static MethodNode findConstructor(ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (TARGET_METHOD_NAME.equals(method.name) && TARGET_METHOD_DESC.equals(method.desc)) {
                matches.add(method);
            }
        }
        if (matches.size() != 1) {
            throw new PatchException("new-game-seed-field-width failed for "
                    + context.classPath() + ": expected one target constructor, found " + matches.size());
        }
        return matches.get(0);
    }

    private static boolean containsString(MethodNode method, String value) {
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (AsmUtil.isStringLdc(node, value)) {
                return true;
            }
        }
        return false;
    }

    private static List<LdcInsnNode> findSeedWidths(MethodNode method, float value) {
        List<LdcInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof LdcInsnNode ldc) || !AsmUtil.isFloatLdc(node, value)) {
                continue;
            }
            AbstractInsnNode height = nextExecutable(node);
            AbstractInsnNode setSize = nextExecutable(height);
            AbstractInsnNode pasteField = nextExecutable(setSize);
            AbstractInsnNode gap = nextExecutable(pasteField);
            AbstractInsnNode leftOfMid = nextExecutable(gap);
            if (height != null && height.getOpcode() == Opcodes.FLOAD
                    && isCall(setSize, POSITION_OWNER, "setSize", "(FF)" + POSITION_DESC)
                    && pasteField != null && pasteField.getOpcode() == Opcodes.ALOAD
                    && gap instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && "Lcom/fs/starfarer/ui/n;".equals(field.desc)) {
                // The ALOAD/GETFIELD pair means the immediate sequence is one
                // instruction longer than the generic executable walk above.
                AbstractInsnNode actualGap = nextExecutable(gap);
                AbstractInsnNode actualLeftOfMid = nextExecutable(actualGap);
                if (AsmUtil.isFloatLdc(actualGap, 3.0f)
                        && isCall(actualLeftOfMid, POSITION_OWNER, "leftOfMid",
                        "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)" + POSITION_DESC)) {
                    matches.add(ldc);
                }
            }
        }
        return matches;
    }

    private static MethodInsnNode findPastePlacement(AbstractInsnNode widthAnchor) {
        MethodInsnNode match = null;
        int executable = 0;
        for (AbstractInsnNode node = previousExecutable(widthAnchor);
             node != null && executable < 24;
             node = previousExecutable(node), executable++) {
            if (isCall(node, POSITION_OWNER, "belowRight",
                    "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)" + POSITION_DESC)) {
                if (match != null || !AsmUtil.isFloatLdc(previousExecutable(node), 40.0f)
                        || !(nextExecutable(node) instanceof InsnNode pop)
                        || pop.getOpcode() != Opcodes.POP) {
                    return null;
                }
                match = (MethodInsnNode) node;
            }
        }
        return match;
    }

    private static int countPlacementShift(MethodNode method, MethodInsnNode placement) {
        AbstractInsnNode amount = nextExecutable(placement);
        AbstractInsnNode shift = nextExecutable(amount);
        AbstractInsnNode pop = nextExecutable(shift);
        return AsmUtil.isFloatLdc(amount, RIGHT_SHIFT)
                && isCall(shift, POSITION_OWNER, "setXAlignOffset", "(F)" + POSITION_DESC)
                && pop != null && pop.getOpcode() == Opcodes.POP ? 1 : 0;
    }

    private static boolean isCall(AbstractInsnNode node, String owner, String name, String desc) {
        return node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc);
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }
}

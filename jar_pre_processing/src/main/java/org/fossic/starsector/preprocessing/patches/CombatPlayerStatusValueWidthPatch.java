package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Widen the player combat HUD's hull and flux value fields. */
public final class CombatPlayerStatusValueWidthPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/class/new/return.class";
    private static final float ORIGINAL_LINE_MULTIPLIER = 4.0f;
    private static final float PATCHED_LINE_MULTIPLIER = 8.0f;
    private static final float BAR_OFFSET = 83.0f;

    @Override
    public String id() {
        return "combat-player-status-value-width";
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
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        MethodNode constructor = classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && "()V".equals(method.desc))
                .filter(CombatPlayerStatusValueWidthPatch::hasHudAnchors)
                .findFirst()
                .orElse(null);

        int applied = 0;
        int verified = 0;
        if (constructor != null) {
            List<LdcInsnNode> anchors = findValueWidthAnchors(
                    constructor, ORIGINAL_LINE_MULTIPLIER);
            for (LdcInsnNode anchor : anchors) {
                anchor.cst = PATCHED_LINE_MULTIPLIER;
                applied++;
            }
            verified = findValueWidthAnchors(
                    constructor, PATCHED_LINE_MULTIPLIER).size();
        }

        return PatchResult.of(
                id(),
                context.classPath(),
                2,
                applied,
                verified,
                "player hull/flux value width multiplier "
                        + ORIGINAL_LINE_MULTIPLIER + " -> "
                        + PATCHED_LINE_MULTIPLIER);
    }

    private static boolean hasHudAnchors(MethodNode method) {
        return containsString(method, "flux")
                && containsString(method, "hull")
                && containsString(method, "HOLDING FIRE")
                && containsString(method, "STRAFE LOCK");
    }

    private static boolean containsString(MethodNode method, String value) {
        return AsmUtil.instructions(method).stream()
                .anyMatch(node -> AsmUtil.isStringLdc(node, value));
    }

    private static List<LdcInsnNode> findValueWidthAnchors(
            MethodNode method, float multiplier) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> AsmUtil.isFloatLdc(node, multiplier))
                .map(LdcInsnNode.class::cast)
                .filter(CombatPlayerStatusValueWidthPatch::matchesValueLayout)
                .toList();
    }

    private static boolean matchesValueLayout(LdcInsnNode multiplier) {
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
            } else if (AsmUtil.isFloatLdc(node, BAR_OFFSET)) {
                sawBarOffset = true;
            } else if (node instanceof MethodInsnNode call
                    && "rightOfMid".equals(call.name)
                    && "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)"
                            .concat("Lcom/fs/starfarer/ui/OO0O;")
                            .equals(call.desc)) {
                return sawMultiply && sawSetSize && sawBarOffset;
            }
        }
        return false;
    }
}

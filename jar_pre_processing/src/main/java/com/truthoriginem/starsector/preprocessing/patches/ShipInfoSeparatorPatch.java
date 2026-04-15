package com.truthoriginem.starsector.preprocessing.patches;

import com.truthoriginem.starsector.preprocessing.AsmUtil;
import com.truthoriginem.starsector.preprocessing.JarPatch;
import com.truthoriginem.starsector.preprocessing.JarWorkspace;
import com.truthoriginem.starsector.preprocessing.PatchContext;
import com.truthoriginem.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ShipInfoSeparatorPatch implements JarPatch {
    private static final String STANDARD_TOOLTIP = "com/fs/starfarer/ui/impl/StandardTooltipV2.class";
    private static final Set<String> TARGETS = Set.of(
            "com/fs/starfarer/campaign/ui/S.class",
            STANDARD_TOOLTIP,
            "com/fs/starfarer/ui/newui/FleetMemberRecoveryDialog.class",
            "com/fs/starfarer/ui/newui/G.class",
            "com/fs/starfarer/ui/impl/FleetMemberOrdnancePanel.class"
    );

    @Override
    public String id() {
        return "ship-info-separator";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        if (STANDARD_TOOLTIP.equals(context.classPath())) {
            return verifyNoCurrentTarget(classNode, context);
        }
        if ("com/fs/starfarer/ui/impl/FleetMemberOrdnancePanel.class".equals(context.classPath())) {
            return patchOrdnancePanel(classNode, context);
        }
        return patchSimpleLastingDamageClass(classNode, context);
    }

    private PatchResult verifyNoCurrentTarget(ClassNode classNode, PatchContext context) {
        int candidates = 0;
        for (MethodNode method : classNode.methods) {
            if (countStringInMethod(method, ", ") == 1 && countLengthMinusInMethod(method, 2) == 1) {
                candidates++;
            }
        }
        if (candidates != 0) {
            return PatchResult.of(id(), context.classPath(), 0, candidates, 0,
                    "StandardTooltipV2 unexpectedly contains ship-info separator target pattern");
        }
        return PatchResult.of(id(), context.classPath(), 0, 0, 0,
                "guard only: StandardTooltipV2 has no ship-info separator target pattern in current 0.98 jars");
    }

    private PatchResult patchSimpleLastingDamageClass(ClassNode classNode, PatchContext context) {
        int applied = 0;
        int verified = 0;
        for (MethodNode method : classNode.methods) {
            if (countStringInMethod(method, ", ") != 1 || countLengthMinusInMethod(method, 2) != 1) {
                continue;
            }
            applied += replaceStringInMethod(method, ", ", "，");
            applied += replaceLengthMinusInMethod(method, 2, 1);
            int strings = countStringInMethod(method, "，");
            int trims = countLengthMinusInMethod(method, 1);
            verified += Math.min(strings, trims);
        }
        return PatchResult.of(id(), context.classPath(), 2, applied, Math.min(2, verified * 2),
                "simple lasting damage separator string and trim length");
    }

    private PatchResult patchOrdnancePanel(ClassNode classNode, PatchContext context) {
        int applied = 0;
        int verifiedStrings = 0;
        int verifiedTrims = 0;
        for (MethodNode method : classNode.methods) {
            if (!methodContains(method, "Armaments:") || !methodContains(method, "Hull mods:")) {
                continue;
            }
            applied += replaceStringInMethod(method, ", ", "，");
            applied += replaceStringInMethod(method, " (D), ", " (D)，");
            applied += replaceStringInMethod(method, " (S), ", " (S)，");
            applied += replaceLengthMinusInMethod(method, 2, 1);
            verifiedStrings += countStringInMethod(method, "，");
            verifiedStrings += countStringInMethod(method, " (D)，");
            verifiedStrings += countStringInMethod(method, " (S)，");
            verifiedTrims += countLengthMinusInMethod(method, 1);
        }
        int verified = Math.min(verifiedStrings, 6) + Math.min(verifiedTrims, 3);
        return PatchResult.of(id(), context.classPath(), 9, applied, verified,
                "ordnance panel separators and trim lengths");
    }

    private static boolean methodContains(MethodNode method, String value) {
        return countStringInMethod(method, value) > 0;
    }

    private static int replaceStringInMethod(MethodNode method, String from, String to) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof LdcInsnNode ldc && from.equals(ldc.cst)) {
                ldc.cst = to;
                count++;
            }
        }
        return count;
    }

    private static int countStringInMethod(MethodNode method, String value) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                count++;
            }
        }
        return count;
    }

    private static int replaceLengthMinusInMethod(MethodNode method, int from, int to) {
        int count = 0;
        List<AbstractInsnNode> lengthCalls = new ArrayList<>();
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && "java/lang/String".equals(call.owner)
                    && "length".equals(call.name)
                    && "()I".equals(call.desc)) {
                lengthCalls.add(node);
            }
        }
        for (AbstractInsnNode lengthCall : lengthCalls) {
            AbstractInsnNode intNode = AsmUtil.nextReal(lengthCall);
            AbstractInsnNode isub = intNode == null ? null : AsmUtil.nextReal(intNode);
            if (AsmUtil.isIntInsn(intNode, from) && isub != null && isub.getOpcode() == org.objectweb.asm.Opcodes.ISUB) {
                AsmUtil.replaceIntInsn(method.instructions, intNode, to);
                count++;
            }
        }
        return count;
    }

    private static int countLengthMinusInMethod(MethodNode method, int expected) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && "java/lang/String".equals(call.owner)
                    && "length".equals(call.name)
                    && "()I".equals(call.desc)) {
                AbstractInsnNode intNode = AsmUtil.nextReal(node);
                AbstractInsnNode isub = intNode == null ? null : AsmUtil.nextReal(intNode);
                if (AsmUtil.isIntInsn(intNode, expected) && isub != null && isub.getOpcode() == org.objectweb.asm.Opcodes.ISUB) {
                    count++;
                }
            }
        }
        return count;
    }
}

package org.fossic.starsector.preprocessing.patches;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Widen every right-aligned value field in the combat command ship-info widget. */
public final class CombatCommandShipInfoValueWidthPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/combat/new/H.class";
    private static final String TARGET_OWNER =
            "com/fs/starfarer/combat/new/H";
    private static final String CONSTRUCTOR_DESC =
            "(Lcom/fs/starfarer/combat/CombatFleetManager$O0;)V";
    private static final String LABEL_DESC = "Lcom/fs/starfarer/ui/d;";
    private static final String LABEL_FACTORY_DESC =
            "(Ljava/lang/String;Lcom/fs/starfarer/api/ui/Alignment;)"
                    + LABEL_DESC;
    private static final String SET_SIZE_DESC =
            "(FF)Lcom/fs/starfarer/ui/OO0O;";
    private static final String RIGHT_OF_MID_DESC =
            "(Lcom/fs/starfarer/api/ui/UIComponentAPI;F)"
                    + "Lcom/fs/starfarer/ui/OO0O;";
    private static final float ORIGINAL_LINE_MULTIPLIER = 4.0f;
    private static final float PATCHED_LINE_MULTIPLIER = 5.0f;
    private static final float VALUE_OFFSET = 83.0f;

    @Override
    public String id() {
        return "combat-command-ship-info-value-width";
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
        MethodNode constructor = requireConstructor(classNode, context);
        List<FieldKey> rightAlignedValues =
                findRightAlignedZeroValueAssignments(constructor);
        if (rightAlignedValues.size() != 4
                || uniqueCounts(rightAlignedValues).size() != 3) {
            throw failure(context, "expected four RMID value assignments "
                    + "across three fields, found " + rightAlignedValues);
        }

        List<ValueLayout> layouts = findValueLayouts(
                constructor, ORIGINAL_LINE_MULTIPLIER);
        if (layouts.size() != 4) {
            throw failure(context, "expected four "
                    + ORIGINAL_LINE_MULTIPLIER
                    + " right-aligned value layouts, found " + layouts.size());
        }
        if (!uniqueCounts(rightAlignedValues).equals(
                uniqueCounts(layouts.stream()
                        .map(ValueLayout::valueField)
                        .toList()))) {
            throw failure(context, "RMID value assignments do not match "
                    + "the four fixed-width layouts");
        }

        for (ValueLayout layout : layouts) {
            layout.width().cst = PATCHED_LINE_MULTIPLIER;
        }

        int verified = findValueLayouts(
                constructor, PATCHED_LINE_MULTIPLIER).size();
        if (verified != 4 || !findValueLayouts(
                constructor, ORIGINAL_LINE_MULTIPLIER).isEmpty()) {
            verified = 0;
        }
        return PatchResult.of(
                id(), context.classPath(), 4, 4, verified,
                "command ship-info RMID value width multiplier "
                        + ORIGINAL_LINE_MULTIPLIER + " -> "
                        + PATCHED_LINE_MULTIPLIER);
    }

    private static MethodNode requireConstructor(
            ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> "<init>".equals(method.name)
                        && CONSTRUCTOR_DESC.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one command ship-info "
                    + "constructor, found " + matches.size());
        }
        MethodNode constructor = matches.get(0);
        for (String anchor : List.of(
                "graphics/fonts/victor14.fnt",
                "flux", "hull", "cr")) {
            if (!containsString(constructor, anchor)) {
                throw failure(context, "missing constructor string anchor "
                        + anchor);
            }
        }
        return constructor;
    }

    private static boolean containsString(MethodNode method, String value) {
        return AsmUtil.instructions(method).stream()
                .anyMatch(node -> AsmUtil.isStringLdc(node, value));
    }

    private static List<FieldKey> findRightAlignedZeroValueAssignments(
            MethodNode constructor) {
        List<FieldKey> fields = new ArrayList<>();
        for (AbstractInsnNode node : constructor.instructions) {
            if (!AsmUtil.isStringLdc(node, "0")) continue;
            AbstractInsnNode alignment = nextExecutable(node);
            AbstractInsnNode factory = nextExecutable(alignment);
            AbstractInsnNode assignment = nextExecutable(factory);
            if (alignment instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "com/fs/starfarer/api/ui/Alignment".equals(field.owner)
                    && "RMID".equals(field.name)
                    && factory instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TARGET_OWNER.equals(call.owner)
                    && LABEL_FACTORY_DESC.equals(call.desc)
                    && assignment instanceof FieldInsnNode target
                    && target.getOpcode() == Opcodes.PUTFIELD
                    && TARGET_OWNER.equals(target.owner)
                    && LABEL_DESC.equals(target.desc)) {
                fields.add(FieldKey.of(target));
            }
        }
        return fields;
    }

    private static List<ValueLayout> findValueLayouts(
            MethodNode constructor, float multiplier) {
        List<ValueLayout> layouts = new ArrayList<>();
        for (AbstractInsnNode node : constructor.instructions) {
            if (!(node instanceof LdcInsnNode width)
                    || !(width.cst instanceof Float actual)
                    || Float.compare(actual, multiplier) != 0) {
                continue;
            }
            ValueLayout layout = matchValueLayout(width);
            if (layout != null) layouts.add(layout);
        }
        return layouts;
    }

    private static ValueLayout matchValueLayout(LdcInsnNode width) {
        AbstractInsnNode lineHeightBefore = previousExecutable(width);
        AbstractInsnNode ownerBefore = previousExecutable(lineHeightBefore);
        AbstractInsnNode add = previousExecutable(ownerBefore);
        AbstractInsnNode value = previousExecutable(add);
        AbstractInsnNode multiply = nextExecutable(width);
        AbstractInsnNode ownerAfter = nextExecutable(multiply);
        AbstractInsnNode lineHeightAfter = nextExecutable(ownerAfter);
        AbstractInsnNode setSize = nextExecutable(lineHeightAfter);
        AbstractInsnNode ownerForPosition = nextExecutable(setSize);
        AbstractInsnNode label = nextExecutable(ownerForPosition);
        AbstractInsnNode offset = nextExecutable(label);
        AbstractInsnNode rightOfMid = nextExecutable(offset);

        if (!(lineHeightBefore instanceof FieldInsnNode before)
                || before.getOpcode() != Opcodes.GETFIELD
                || !"F".equals(before.desc)
                || ownerBefore == null
                || ownerBefore.getOpcode() != Opcodes.ALOAD
                || !(add instanceof MethodInsnNode addCall)
                || addCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"add".equals(addCall.name)
                || !(value instanceof FieldInsnNode valueField)
                || valueField.getOpcode() != Opcodes.GETFIELD
                || !TARGET_OWNER.equals(valueField.owner)
                || !LABEL_DESC.equals(valueField.desc)
                || multiply == null
                || multiply.getOpcode() != Opcodes.FMUL
                || ownerAfter == null
                || ownerAfter.getOpcode() != Opcodes.ALOAD
                || !(lineHeightAfter instanceof FieldInsnNode after)
                || after.getOpcode() != Opcodes.GETFIELD
                || !sameField(before, after)
                || !(setSize instanceof MethodInsnNode sizeCall)
                || sizeCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"setSize".equals(sizeCall.name)
                || !SET_SIZE_DESC.equals(sizeCall.desc)
                || ownerForPosition == null
                || ownerForPosition.getOpcode() != Opcodes.ALOAD
                || !(label instanceof FieldInsnNode labelField)
                || labelField.getOpcode() != Opcodes.GETFIELD
                || !TARGET_OWNER.equals(labelField.owner)
                || !LABEL_DESC.equals(labelField.desc)
                || !isFloatLdc(offset, VALUE_OFFSET)
                || !(rightOfMid instanceof MethodInsnNode positionCall)
                || positionCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"rightOfMid".equals(positionCall.name)
                || !RIGHT_OF_MID_DESC.equals(positionCall.desc)) {
            return null;
        }
        return new ValueLayout(width, FieldKey.of(valueField));
    }

    private static boolean sameField(
            FieldInsnNode first, FieldInsnNode second) {
        return first.owner.equals(second.owner)
                && first.name.equals(second.name)
                && first.desc.equals(second.desc);
    }

    private static boolean isFloatLdc(AbstractInsnNode node, float value) {
        return node instanceof LdcInsnNode ldc
                && ldc.cst instanceof Float actual
                && Float.compare(actual, value) == 0;
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

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        if (node == null) return null;
        for (AbstractInsnNode previous = node.getPrevious();
             previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static Map<FieldKey, Integer> uniqueCounts(
            List<FieldKey> fields) {
        Map<FieldKey, Integer> counts = new HashMap<>();
        for (FieldKey field : fields) {
            counts.merge(field, 1, Integer::sum);
        }
        return counts;
    }

    private static PatchException failure(
            PatchContext context, String detail) {
        return new PatchException("combat-command-ship-info-value-width "
                + "failed for " + context.classPath() + ": " + detail);
    }

    private record FieldKey(String owner, String name, String desc) {
        private static FieldKey of(FieldInsnNode field) {
            return new FieldKey(field.owner, field.name, field.desc);
        }
    }

    private record ValueLayout(LdcInsnNode width, FieldKey valueField) {
    }
}

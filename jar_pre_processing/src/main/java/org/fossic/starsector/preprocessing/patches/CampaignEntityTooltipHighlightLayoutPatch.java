package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Lays out the campaign-entity faction line before resolving its highlights. */
public final class CampaignEntityTooltipHighlightLayoutPatch implements JarPatch {
    static final String TARGET_CLASS = "com/fs/starfarer/ui/impl/F$2.class";
    private static final String TARGET_CLASS_NAME = "com/fs/starfarer/ui/impl/F$2";
    private static final String TARGET_METHOD = "while.float";
    private static final String TARGET_METHOD_DESC = "()V";
    private static final String LABEL = "com/fs/starfarer/ui/d";
    private static final String LABEL_DESC = "L" + LABEL + ";";
    private static final String POSITION = "com/fs/starfarer/ui/OO0O";
    private static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);
    private static final String GET_RENDERER_DESC = "()L" + RENDERER + ";";
    private static final String AUTO_SIZE_DESC = "(F)L" + POSITION + ";";

    @Override
    public String id() {
        return "campaign-entity-tooltip-highlight-after-layout";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.LOCALIZATION;
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
            throw failure(context, "unexpected class " + classNode.name);
        }

        MethodNode method = requireTargetMethod(classNode, context);
        MethodInsnNode highlight = uniqueCall(
                method, RENDERER, "o00000", "([Ljava/lang/String;)V", context);
        FieldInsnNode labelField = requireRendererReceiver(method, highlight, context);
        requireStringTargets(highlight, context);

        MethodInsnNode colors = uniqueCall(
                method, RENDERER, "o00000", "([Ljava/awt/Color;)V", context);
        FieldInsnNode colorLabelField = requireRendererReceiver(method, colors, context);
        if (!sameField(labelField, colorLabelField)) {
            throw failure(context, "highlight colors use a different label field");
        }

        LayoutBlock layout = requireLayoutBlock(method, labelField, context);
        if (!(indexOf(method, highlight) < indexOf(method, colors)
                && indexOf(method, colors) < indexOf(method, layout.call()))) {
            throw failure(context, "expected highlight, colors, then layout ordering");
        }

        InsnList moved = detachRange(
                method, layout.start(), layout.end(), context);
        method.instructions.insertBefore(
                requireRendererLoadStart(method, highlight, context), moved);

        int verified = indexOf(method, layout.call()) < indexOf(method, highlight)
                && indexOf(method, highlight) < indexOf(method, colors)
                ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "layout campaign entity faction text before resolving faction and relation highlights");
    }

    private static MethodNode requireTargetMethod(
            ClassNode classNode, PatchContext context) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> TARGET_METHOD.equals(method.name)
                        && TARGET_METHOD_DESC.equals(method.desc))
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one tooltip method, found " + matches.size());
        }
        return matches.get(0);
    }

    private static FieldInsnNode requireRendererReceiver(
            MethodNode method, MethodInsnNode target, PatchContext context) {
        MethodInsnNode getRenderer = previousMatchingCall(
                method, target, LABEL, "getRenderer", GET_RENDERER_DESC, context);
        AbstractInsnNode fieldNode = previousExecutable(getRenderer);
        AbstractInsnNode loadNode = previousExecutable(fieldNode);
        if (!(fieldNode instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !TARGET_CLASS_NAME.equals(field.owner)
                || !LABEL_DESC.equals(field.desc)
                || !(loadNode instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0) {
            throw failure(context, "highlight label receiver has drifted");
        }
        return field;
    }

    private static AbstractInsnNode requireRendererLoadStart(
            MethodNode method, MethodInsnNode target, PatchContext context) {
        MethodInsnNode getRenderer = previousMatchingCall(
                method, target, LABEL, "getRenderer", GET_RENDERER_DESC, context);
        AbstractInsnNode field = previousExecutable(getRenderer);
        AbstractInsnNode load = previousExecutable(field);
        if (load == null) {
            throw failure(context, "missing renderer receiver load");
        }
        return load;
    }

    private static void requireStringTargets(
            MethodInsnNode highlight, PatchContext context) {
        AbstractInsnNode node = requireOpcode(
                previousExecutable(highlight), Opcodes.AASTORE,
                context, "second highlight target");
        node = requireVar(previousExecutable(node), Opcodes.ALOAD, 22,
                context, "relationship text local");
        node = requireOpcode(previousExecutable(node), Opcodes.ICONST_1,
                context, "second highlight index");
        node = requireOpcode(previousExecutable(node), Opcodes.DUP,
                context, "second highlight array duplicate");
        node = requireOpcode(previousExecutable(node), Opcodes.AASTORE,
                context, "first highlight target");
        node = requireVar(previousExecutable(node), Opcodes.ALOAD, 20,
                context, "faction text local");
        node = requireOpcode(previousExecutable(node), Opcodes.ICONST_0,
                context, "first highlight index");
        node = requireOpcode(previousExecutable(node), Opcodes.DUP,
                context, "first highlight array duplicate");
        node = previousExecutable(node);
        if (!(node instanceof TypeInsnNode type)
                || type.getOpcode() != Opcodes.ANEWARRAY
                || !"java/lang/String".equals(type.desc)) {
            throw failure(context, "highlight String array creation has drifted");
        }
        requireOpcode(previousExecutable(node), Opcodes.ICONST_2,
                context, "highlight target count");
    }

    private static LayoutBlock requireLayoutBlock(
            MethodNode method, FieldInsnNode labelField, PatchContext context) {
        List<LayoutBlock> matches = calls(method, LABEL, "autoSizeToWidth", AUTO_SIZE_DESC)
                .stream()
                .map(call -> matchLayoutBlock(call, labelField))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (matches.size() != 1) {
            throw failure(context, "expected one matching faction-line layout, found "
                    + matches.size());
        }
        return matches.get(0);
    }

    private static LayoutBlock matchLayoutBlock(
            MethodInsnNode call, FieldInsnNode labelField) {
        AbstractInsnNode subtract = previousExecutable(call);
        AbstractInsnNode multiply = previousExecutable(subtract);
        AbstractInsnNode two = previousExecutable(multiply);
        AbstractInsnNode margin = previousExecutable(two);
        AbstractInsnNode width = previousExecutable(margin);
        AbstractInsnNode fieldNode = previousExecutable(width);
        AbstractInsnNode loadNode = previousExecutable(fieldNode);
        AbstractInsnNode end = nextExecutable(call);
        if (subtract == null || subtract.getOpcode() != Opcodes.FSUB
                || multiply == null || multiply.getOpcode() != Opcodes.FMUL
                || two == null || two.getOpcode() != Opcodes.FCONST_2
                || !(margin instanceof VarInsnNode marginLoad)
                || marginLoad.getOpcode() != Opcodes.FLOAD || marginLoad.var != 18
                || !(width instanceof VarInsnNode widthLoad)
                || widthLoad.getOpcode() != Opcodes.FLOAD || widthLoad.var != 4
                || !(fieldNode instanceof FieldInsnNode field)
                || !sameField(labelField, field)
                || !(loadNode instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || end == null || end.getOpcode() != Opcodes.POP) {
            return null;
        }
        return new LayoutBlock(loadNode, call, end);
    }

    private static InsnList detachRange(
            MethodNode method, AbstractInsnNode start, AbstractInsnNode end,
            PatchContext context) {
        List<AbstractInsnNode> nodes = new ArrayList<>();
        AbstractInsnNode current = start;
        while (current != null) {
            if (current.getOpcode() < 0) {
                throw failure(context, "layout block contains metadata or a frame boundary");
            }
            nodes.add(current);
            if (current == end) break;
            current = current.getNext();
        }
        if (nodes.isEmpty() || nodes.get(nodes.size() - 1) != end) {
            throw failure(context, "layout block end is unreachable");
        }

        InsnList result = new InsnList();
        for (AbstractInsnNode node : nodes) {
            method.instructions.remove(node);
            result.add(node);
        }
        return result;
    }

    private static boolean sameField(FieldInsnNode left, FieldInsnNode right) {
        return left.getOpcode() == right.getOpcode()
                && left.owner.equals(right.owner)
                && left.name.equals(right.name)
                && left.desc.equals(right.desc);
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc,
            PatchContext context) {
        List<MethodInsnNode> matches = calls(method, owner, name, desc);
        if (matches.size() != 1) {
            throw failure(context, "expected one call to " + owner + "." + name
                    + desc + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static MethodInsnNode previousMatchingCall(
            MethodNode method, AbstractInsnNode target,
            String owner, String name, String desc, PatchContext context) {
        MethodInsnNode result = null;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node == target) break;
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                result = call;
            }
        }
        if (result == null) {
            throw failure(context, "missing renderer lookup before highlight call");
        }
        return result;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String desc) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .map(MethodInsnNode.class::cast)
                .toList();
    }

    private static AbstractInsnNode requireOpcode(
            AbstractInsnNode node, int opcode, PatchContext context, String detail) {
        if (node == null || node.getOpcode() != opcode) {
            throw failure(context, detail + " has drifted");
        }
        return node;
    }

    private static AbstractInsnNode requireVar(
            AbstractInsnNode node, int opcode, int var,
            PatchContext context, String detail) {
        if (!(node instanceof VarInsnNode load)
                || load.getOpcode() != opcode || load.var != var) {
            throw failure(context, detail + " has drifted");
        }
        return node;
    }

    private static int indexOf(MethodNode method, AbstractInsnNode target) {
        return AsmUtil.instructions(method).indexOf(target);
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("campaign-entity-tooltip-highlight-after-layout failed for "
                + context.classPath() + ": " + detail);
    }

    private record LayoutBlock(
            AbstractInsnNode start, MethodInsnNode call, AbstractInsnNode end) {
    }
}

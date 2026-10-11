package org.fossic.starsector.preprocessing;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Validated interval edits. Control-flow transformations must explicitly repair boundary frames. */
public final class InstructionRanges {
    private InstructionRanges() {}

    public static Set<AbstractInsnNode> nodesBetween(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        if (first == null || last == null || method.instructions.indexOf(first) < 0 || method.instructions.indexOf(last) < 0) {
            throw new PatchException("Instruction interval does not belong to method");
        }
        Set<AbstractInsnNode> result = new LinkedHashSet<>();
        for (AbstractInsnNode node = first; node != null; node = node.getNext()) {
            result.add(node);
            if (node == last) return result;
        }
        throw new PatchException("Instruction interval end precedes start");
    }

    public static InsnList detachStraightLine(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        Set<AbstractInsnNode> nodes = nodesBetween(method, first, last);
        for (AbstractInsnNode node : nodes) {
            int opcode = node.getOpcode();
            if (opcode < 0 || node instanceof JumpInsnNode || node instanceof TableSwitchInsnNode
                    || node instanceof LookupSwitchInsnNode || opcode == Opcodes.ATHROW || opcode == Opcodes.RET
                    || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) {
                throw new PatchException("Straight-line interval contains metadata or control flow");
            }
        }
        InsnList result = new InsnList();
        for (AbstractInsnNode node : nodes) { method.instructions.remove(node); result.add(node); }
        return result;
    }

    public static void remove(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        Set<AbstractInsnNode> nodes = nodesBetween(method, first, last);
        requireNoExternalReferences(method, nodes);
        for (AbstractInsnNode node : nodes) method.instructions.remove(node);
    }

    public static void requireNoExternalReferences(MethodNode method, Set<AbstractInsnNode> removed) {
        Set<LabelNode> labels = new HashSet<>();
        for (AbstractInsnNode node : removed) if (node instanceof LabelNode label) labels.add(label);
        for (AbstractInsnNode node : method.instructions) {
            if (removed.contains(node)) continue;
            if (node instanceof JumpInsnNode jump) requireKept(labels, jump.label);
            if (node instanceof TableSwitchInsnNode table) { requireKept(labels, table.dflt); table.labels.forEach(l -> requireKept(labels, l)); }
            if (node instanceof LookupSwitchInsnNode lookup) { requireKept(labels, lookup.dflt); lookup.labels.forEach(l -> requireKept(labels, l)); }
            if (node instanceof LineNumberNode line) requireKept(labels, line.start);
            if (node instanceof FrameNode frame) { checkFrame(labels, frame.local); checkFrame(labels, frame.stack); }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            requireKept(labels, block.start); requireKept(labels, block.end); requireKept(labels, block.handler);
        }
        if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) {
            requireKept(labels, local.start); requireKept(labels, local.end);
        }
        checkAnnotations(labels, method.visibleLocalVariableAnnotations);
        checkAnnotations(labels, method.invisibleLocalVariableAnnotations);
    }

    private static void checkFrame(Set<LabelNode> removed, List<Object> values) {
        if (values != null) for (Object value : values) if (value instanceof LabelNode label) requireKept(removed, label);
    }

    private static void checkAnnotations(Set<LabelNode> removed, List<LocalVariableAnnotationNode> annotations) {
        if (annotations != null) for (LocalVariableAnnotationNode annotation : annotations) {
            annotation.start.forEach(l -> requireKept(removed, l));
            annotation.end.forEach(l -> requireKept(removed, l));
        }
    }

    private static void requireKept(Set<LabelNode> removed, LabelNode label) {
        if (removed.contains(label)) throw new PatchException("Removed interval is referenced by retained code or metadata");
    }
}

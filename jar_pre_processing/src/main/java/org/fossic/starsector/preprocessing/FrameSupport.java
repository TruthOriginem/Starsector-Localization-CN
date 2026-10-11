package org.fossic.starsector.preprocessing;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Frame entries use one LONG/DOUBLE item, unlike the two occupied local-variable slots. */
public final class FrameSupport {
    private FrameSupport() {}

    public record State(List<Object> locals, List<Object> stack) {
        public State { locals = List.copyOf(locals); stack = List.copyOf(stack); }
        public FrameNode fullFrame() {
            return new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), stack.size(), stack.toArray());
        }
    }

    public static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor();
            case Type.OBJECT -> type.getInternalName();
            default -> throw new PatchException("Unsupported frame type " + type);
        };
    }

    public static List<Object> initialLocals(String owner, MethodNode method) {
        List<Object> result = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            result.add(method.name.equals("<init>") ? Opcodes.UNINITIALIZED_THIS : owner);
        }
        for (Type type : Type.getArgumentTypes(method.desc)) result.add(frameType(type));
        return result;
    }

    public static int slots(List<Object> entries) {
        int slots = 0;
        for (Object entry : entries) slots += Objects.equals(entry, Opcodes.LONG) || Objects.equals(entry, Opcodes.DOUBLE) ? 2 : 1;
        return slots;
    }

    /** Initial parameters plus a known local after them; gaps are TOP, wide entries are not duplicated. */
    public static List<Object> withLocalAfterArguments(String owner, MethodNode method, int slot, Object type) {
        List<Object> locals = initialLocals(owner, method);
        int used = slots(locals);
        if (slot < used) throw new PatchException("Local overlaps method parameters: " + slot);
        while (used++ < slot) locals.add(Opcodes.TOP);
        locals.add(type);
        return locals;
    }

    /** Decode only up to a selected boundary; never recompute or normalize the whole method. */
    public static State expandAt(String owner, MethodNode method, FrameNode target) {
        List<Object> locals = initialLocals(owner, method);
        List<Object> stack = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof FrameNode frame)) continue;
            switch (frame.type) {
                case Opcodes.F_NEW, Opcodes.F_FULL -> {
                    locals = copy(frame.local); stack = copy(frame.stack);
                }
                case Opcodes.F_APPEND -> { locals.addAll(copy(frame.local)); stack.clear(); }
                case Opcodes.F_CHOP -> {
                    int removed = frame.local == null ? 0 : frame.local.size();
                    if (removed > locals.size()) throw new PatchException("Frame CHOP exceeds locals");
                    locals.subList(locals.size() - removed, locals.size()).clear(); stack.clear();
                }
                case Opcodes.F_SAME -> stack.clear();
                case Opcodes.F_SAME1 -> stack = copy(frame.stack);
                default -> throw new PatchException("Unknown frame type " + frame.type);
            }
            if (frame == target) return new State(locals, stack);
        }
        throw new PatchException("Frame does not belong to method " + method.name + method.desc);
    }

    private static List<Object> copy(List<Object> entries) {
        return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }
}

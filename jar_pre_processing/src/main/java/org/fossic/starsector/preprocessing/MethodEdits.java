package org.fossic.starsector.preprocessing;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Small method-body operations; callers still own control flow, frames and business anchors. */
public final class MethodEdits {
    private MethodEdits() {}

    public static MethodNode copy(MethodNode source, String name) {
        MethodNode copy = new MethodNode(Opcodes.ASM9, source.access, name, source.desc,
                source.signature, source.exceptions.toArray(String[]::new));
        source.accept(copy);
        return copy;
    }

    /** Reserve slots only. A local live across a frame boundary still needs explicit frame edits. */
    public static int allocateLocal(MethodNode method, Type type) {
        if (type.getSort() == Type.VOID || type.getSort() == Type.METHOD) {
            throw new PatchException("Cannot allocate local for " + type);
        }
        int slot = method.maxLocals;
        int end = slot + type.getSize();
        if (slot < 0 || end > 65535) throw new PatchException("Local variable limit exceeded");
        method.maxLocals = end;
        return slot;
    }

    /** Retain method declaration/annotations; remove all standard metadata tied to the old code. */
    public static void clearBody(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
        if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
        method.maxStack = 0;
        method.maxLocals = 0;
    }

    public static void replaceBody(MethodNode method, InsnList replacement, int maxStack, int maxLocals) {
        int arguments = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) arguments += type.getSize();
        if (maxStack < 0 || maxStack > 65535 || maxLocals < arguments || maxLocals > 65535) {
            throw new PatchException("Invalid method limits for " + method.name + method.desc);
        }
        clearBody(method);
        method.instructions.add(replacement);
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }
}

package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.PatchException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Local, stack-neutral wrapping control for labels identified by a width patch. */
final class SingleLineLabel {
    static final String LABEL = "com/fs/starfarer/ui/d";
    static final String INPUT = "com/fs/starfarer/ui/new";
    static final String RENDERER = "com/fs/graphics/A/oo" + "O".repeat(254);

    private SingleLineLabel() {}

    static void beforeWidth(MethodNode method, AbstractInsnNode width) {
        AbstractInsnNode receiver = previous(width);
        // add(label).setSize(lineHeight * multiplier, lineHeight)
        if (receiver instanceof FieldInsnNode height && height.desc.equals("F")) {
            AbstractInsnNode owner = previous(receiver);
            if (!(owner instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
                throw new PatchException("single-line label: unexpected line-height owner");
            }
            receiver = previous(owner);
        }
        // add(label).setSize(width, height), or label.setSize(width, height)
        if (receiver instanceof MethodInsnNode add && add.name.equals("add")
                && add.desc.equals("(Lcom/fs/starfarer/ui/c;)Lcom/fs/starfarer/ui/OO0O;")) {
            receiver = previous(receiver);
        }
        if (!(receiver instanceof FieldInsnNode field)) {
            throw new PatchException("single-line label: width receiver is not a field");
        }
        beforeFieldRead(method, field);
    }

    static void beforeFieldRead(MethodNode method, FieldInsnNode field) {
        AbstractInsnNode owner = previous(field);
        boolean input = field.desc.equals("L" + INPUT + ";");
        if (field.getOpcode() != Opcodes.GETFIELD
                || !(field.desc.equals("L" + LABEL + ";") || input)
                || !(owner instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
            throw new PatchException("single-line label: expected this.label or this.input");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
        if (input) code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INPUT, "getTextLabel", "()L" + LABEL + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LABEL, "getRenderer", "()L" + RENDERER + ";", false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "return", "(Z)V", false));
        method.instructions.insertBefore(owner, code);
        method.maxStack += 2;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}

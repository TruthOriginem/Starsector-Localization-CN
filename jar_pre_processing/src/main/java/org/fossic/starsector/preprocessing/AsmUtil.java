package org.fossic.starsector.preprocessing;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public final class AsmUtil {
    private AsmUtil() {
    }

    public static List<MethodNode> methods(ClassNode classNode) {
        return classNode.methods;
    }

    public static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> nodes = new ArrayList<>();
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            nodes.add(node);
        }
        return nodes;
    }

    public static boolean isStringLdc(AbstractInsnNode node, String value) {
        return node instanceof LdcInsnNode ldc && value.equals(ldc.cst);
    }

    public static boolean isFloatLdc(AbstractInsnNode node, float value) {
        return node instanceof LdcInsnNode ldc && ldc.cst instanceof Float f && Float.compare(f, value) == 0;
    }

    public static int replaceStringConstant(ClassNode classNode, String from, String to) {
        int count = 0;
        for (MethodNode method : methods(classNode)) {
            for (AbstractInsnNode node : instructions(method)) {
                if (isStringLdc(node, from)) {
                    ((LdcInsnNode) node).cst = to;
                    count++;
                }
            }
        }
        return count;
    }

    public static int countStringConstant(ClassNode classNode, String value) {
        int count = 0;
        for (MethodNode method : methods(classNode)) {
            for (AbstractInsnNode node : instructions(method)) {
                if (isStringLdc(node, value)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int countMethodCall(ClassNode classNode, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : methods(classNode)) {
            for (AbstractInsnNode node : instructions(method)) {
                if (node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int replaceLengthMinusConstantNearString(ClassNode classNode, String marker, int from, int to) {
        int count = 0;
        for (MethodNode method : methods(classNode)) {
            boolean methodHasMarker = false;
            for (AbstractInsnNode node : instructions(method)) {
                if (isStringLdc(node, marker)) {
                    methodHasMarker = true;
                    break;
                }
            }
            if (!methodHasMarker) {
                continue;
            }
            for (AbstractInsnNode node : instructions(method)) {
                if (node instanceof MethodInsnNode call
                        && "java/lang/String".equals(call.owner)
                        && "length".equals(call.name)
                        && "()I".equals(call.desc)) {
                    AbstractInsnNode next = nextReal(node);
                    AbstractInsnNode next2 = next == null ? null : nextReal(next);
                    if (isIntInsn(next, from) && next2 != null && next2.getOpcode() == Opcodes.ISUB) {
                        replaceIntInsn(method.instructions, next, to);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static int countLengthMinusConstantNearString(ClassNode classNode, String marker, int expected) {
        int count = 0;
        for (MethodNode method : methods(classNode)) {
            boolean methodHasMarker = false;
            for (AbstractInsnNode node : instructions(method)) {
                if (isStringLdc(node, marker)) {
                    methodHasMarker = true;
                    break;
                }
            }
            if (!methodHasMarker) {
                continue;
            }
            for (AbstractInsnNode node : instructions(method)) {
                if (node instanceof MethodInsnNode call
                        && "java/lang/String".equals(call.owner)
                        && "length".equals(call.name)
                        && "()I".equals(call.desc)) {
                    AbstractInsnNode next = nextReal(node);
                    AbstractInsnNode next2 = next == null ? null : nextReal(next);
                    if (isIntInsn(next, expected) && next2 != null && next2.getOpcode() == Opcodes.ISUB) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static boolean isIntInsn(AbstractInsnNode node, int value) {
        if (node == null) {
            return false;
        }
        return switch (value) {
            case -1 -> node.getOpcode() == Opcodes.ICONST_M1;
            case 0 -> node.getOpcode() == Opcodes.ICONST_0;
            case 1 -> node.getOpcode() == Opcodes.ICONST_1;
            case 2 -> node.getOpcode() == Opcodes.ICONST_2;
            case 3 -> node.getOpcode() == Opcodes.ICONST_3;
            case 4 -> node.getOpcode() == Opcodes.ICONST_4;
            case 5 -> node.getOpcode() == Opcodes.ICONST_5;
            default -> false;
        };
    }

    public static void replaceIntInsn(InsnList instructions, AbstractInsnNode oldNode, int value) {
        int opcode = switch (value) {
            case -1 -> Opcodes.ICONST_M1;
            case 0 -> Opcodes.ICONST_0;
            case 1 -> Opcodes.ICONST_1;
            case 2 -> Opcodes.ICONST_2;
            case 3 -> Opcodes.ICONST_3;
            case 4 -> Opcodes.ICONST_4;
            case 5 -> Opcodes.ICONST_5;
            default -> throw new PatchException("Unsupported replacement int constant: " + value);
        };
        instructions.set(oldNode, new InsnNode(opcode));
    }

    public static AbstractInsnNode nextReal(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) {
            current = current.getNext();
        }
        return current;
    }

}

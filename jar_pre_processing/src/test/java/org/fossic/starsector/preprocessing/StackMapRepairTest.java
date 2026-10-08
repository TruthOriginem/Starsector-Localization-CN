package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackMapRepairTest {

    /** 生成一个带分支、带字符串常量、且带正确帧表的类（模拟阶段 1 的输出）。 */
    private static byte[] framedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Demo", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder",
                "<init>", "()V", false);
        mv.visitLdcInsn("Hello, ");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("!");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "length", "()I", false);
        Label branch = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, branch);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("long");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(branch);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** 剥掉所有 FrameNode，模拟解耦器丢帧后的产物。 */
    private static byte[] stripFrames(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor inner = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, inner) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local,
                                           int numStack, Object[] stack) {
                        // 丢弃
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean hasFrameNode(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode mn : node.methods) {
            if (!method.equals(mn.name)) {
                continue;
            }
            for (org.objectweb.asm.tree.AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof FrameNode) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void repairsFramelessBranchedMethod() {
        byte[] framed = framedClass();
        assertTrue(hasFrameNode(framed, "greet"));

        byte[] broken = stripFrames(framed);
        assertFalse(hasFrameNode(broken, "greet"));
        assertTrue(StackMapRepair.needsRepair(broken));

        byte[] repaired = StackMapRepair.rebuildWithComputedFrames(broken, hierarchyForDemo());
        assertFalse(StackMapRepair.needsRepair(repaired));
        assertTrue(hasFrameNode(repaired, "greet"));
    }

    @Test
    void fullyFramedClassNeedsNoRepair() {
        byte[] framed = framedClass();
        assertFalse(StackMapRepair.needsRepair(framed));
    }

    private static StackMapRepair.Hierarchy hierarchyForDemo() {
        StackMapRepair.Hierarchy hierarchy = new StackMapRepair.Hierarchy();
        hierarchy.index("Demo", framedClass());
        return hierarchy;
    }
}

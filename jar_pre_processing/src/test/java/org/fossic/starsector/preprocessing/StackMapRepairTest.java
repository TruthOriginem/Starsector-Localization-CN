package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackMapRepairTest {

    /**
     * 生成一个带两个分支、带字符串常量、且带正确帧表的类（模拟阶段 1 的输出）。
     * 两个分支保证方法中部至少有两个帧表条目，用于构造"部分帧丢失"的过期场景。
     */
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
        Label second = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, branch);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("long");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(branch);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "length", "()I", false);
        mv.visitJumpInsn(Opcodes.IFGT, second);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("?");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(second);
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

    /**
     * 只保留方法里第一个 FrameNode、丢弃其余帧，模拟解耦器平移代码后
     * "帧表仍在但内容过期"的产物——这类损伤正是只检测"带分支且缺帧"的
     * 旧策略漏掉的大类。
     */
    private static byte[] staleFrames(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean seenFirst = false;
        int dropped = 0;
        for (MethodNode mn : node.methods) {
            for (int i = 0; i < mn.instructions.size(); i++) {
                if (mn.instructions.get(i).getType() == AbstractInsnNode.FRAME) {
                    if (!seenFirst) {
                        seenFirst = true;
                    } else {
                        mn.instructions.remove(mn.instructions.get(i));
                        i--;
                        dropped++;
                    }
                }
            }
        }
        assertTrue(dropped > 0, "测试前提不成立：示例类应有两个以上帧");
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasFrameNode(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode mn : node.methods) {
            if (!method.equals(mn.name)) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getType() == AbstractInsnNode.FRAME) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 镜像 JVM 校验规则的结构检查：v51+ 类的每个跳转目标前一位置必须有
     * FrameNode。CheckClassAdapter 的数据流分析只抓"帧内容错误"，不抓
     * "帧缺失"（分析器自己会重算数据流），所以缺失必须单独查。
     * 返回缺失帧的跳转目标描述列表，空列表 = 通过。
     */
    private static List<String> branchTargetsWithoutFrame(byte[] bytes) {
        List<String> problems = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode mn : node.methods) {
            Set<Integer> frameAfterLabel = new HashSet<>();
            for (int i = 0; i < mn.instructions.size(); i++) {
                if (mn.instructions.get(i).getType() == AbstractInsnNode.FRAME) {
                    frameAfterLabel.add(i - 1);
                }
            }
            for (int i = 0; i < mn.instructions.size(); i++) {
                AbstractInsnNode insn = mn.instructions.get(i);
                if (!(insn instanceof JumpInsnNode)) {
                    continue;
                }
                LabelNode target = ((JumpInsnNode) insn).label;
                for (int t = 0; t < mn.instructions.size(); t++) {
                    AbstractInsnNode at = mn.instructions.get(t);
                    if (at == target) {
                        if (!frameAfterLabel.contains(t)) {
                            problems.add(mn.name + " 跳转目标 @" + t + " 缺帧");
                        }
                        break;
                    }
                }
            }
        }
        return problems;
    }

    /** ASM CheckClassAdapter 全量数据流校验；返回报告文本，空串 = 校验通过。 */
    private static String dataflowVerify(byte[] bytes) {
        StringWriter out = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(out));
        return out.toString();
    }

    @Test
    void repairsFramelessBranchedMethod() {
        byte[] broken = stripFrames(framedClass());
        assertFalse(hasFrameNode(broken, "greet"));

        byte[] repaired = StackMapRepair.rebuildWithComputedFrames(broken, hierarchyForDemo());
        assertTrue(hasFrameNode(repaired, "greet"));
        assertEquals("", dataflowVerify(repaired), "重建后应通过全量数据流校验");
    }

    /** 重建对已带正确帧表的类必须幂等且无害。 */
    @Test
    void rebuildIsHarmlessOnFullyFramedClass() {
        byte[] framed = framedClass();
        assertEquals("", dataflowVerify(framed));

        byte[] rebuilt = StackMapRepair.rebuildWithComputedFrames(framed, hierarchyForDemo());
        assertTrue(hasFrameNode(rebuilt, "greet"));
        assertEquals("", dataflowVerify(rebuilt), "重建不得引入新的校验错误");
    }

    /**
     * 核心回归：帧表"部分存在但过期"（首帧之后的帧丢失）——旧策略
     * （只修"带分支且缺帧"的类）对此完全漏检，全类重建策略必须修复它。
     */
    @Test
    void stalePartiallyFramedClassGetsRepaired() {
        byte[] stale = staleFrames(framedClass());
        assertTrue(hasFrameNode(stale, "greet"), "过期类仍保留部分帧（这正是旧策略漏检的原因）");
        assertFalse(branchTargetsWithoutFrame(stale).isEmpty(),
                "测试前提不成立：丢帧后的跳转目标应缺帧（JVM 校验会拒绝）");

        byte[] repaired = StackMapRepair.rebuildWithComputedFrames(stale, hierarchyForDemo());
        assertTrue(branchTargetsWithoutFrame(repaired).isEmpty(), "全类重建应补齐丢失的帧");
        assertEquals("", dataflowVerify(repaired), "重建不得引入帧内容错误");
    }

    private static StackMapRepair.Hierarchy hierarchyForDemo() {
        StackMapRepair.Hierarchy hierarchy = new StackMapRepair.Hierarchy();
        hierarchy.index("Demo", framedClass());
        return hierarchy;
    }
}

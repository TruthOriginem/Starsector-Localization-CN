package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 正常 CSV 解析只保存最后一个 JSONObject，在引号不匹配时才执行 {@code toString(2)}。
 */
public final class CsvLazyErrorFormattingPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/loading/G.class";
    private static final String TARGET_DESC =
            "(Ljava/lang/String;)Lorg/json/JSONArray;";
    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String PRETTY_DESC = "(I)Ljava/lang/String;";
    private static final String FORMATTER =
            "org/fossic/starsector/optimization/CsvErrorFormatter";
    private static final String FORMAT_METHOD = "formatLastRow";
    private static final String FORMAT_DESC =
            "(Ljava/lang/Object;)Ljava/lang/String;";
    private static final int LAST_ROW_LOCAL = 10;

    @Override
    public String id() {
        return "csv-lazy-error-row-formatting";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.CSV_ERROR_FORMATTING;
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
        List<MethodNode> matches = classNode.methods.stream()
                .filter(m -> TARGET_DESC.equals(m.desc)
                        && (m.access & Opcodes.ACC_PUBLIC) != 0
                        && (m.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "CSV parser 方法签名匹配数异常: " + matches.size()
                            + "（预期 1）");
        }
        MethodNode method = matches.get(0);

        MethodInsnNode eagerPrettyCall = uniqueCall(
                method, JSON_OBJECT, "toString", PRETTY_DESC,
                "JSONObject.toString(2)");
        AbstractInsnNode indent = AsmUtil.previousReal(eagerPrettyCall);
        AbstractInsnNode rowLoad = indent == null ? null
                : AsmUtil.previousReal(indent);
        AbstractInsnNode rowStore = AsmUtil.nextReal(eagerPrettyCall);
        if (!AsmUtil.isIntInsn(indent, 2)
                || !(rowLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || !(rowStore instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE
                || store.var != LAST_ROW_LOCAL) {
            throw new PatchException(
                    "CSV parser 每行 pretty-print 指令形状变化");
        }

        List<VarInsnNode> errorLoads = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof VarInsnNode var
                        && var.getOpcode() == Opcodes.ALOAD
                        && var.var == LAST_ROW_LOCAL
                        && isStringAppend(AsmUtil.nextReal(var)))
                .map(node -> (VarInsnNode) node)
                .toList();
        if (errorLoads.size() != 1) {
            throw new PatchException(
                    "CSV parser 错误消息 last-row 读取数异常: "
                            + errorLoads.size() + "（预期 1）");
        }

        int adjustedFrames = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (!(node instanceof FrameNode frame)
                    || frame.local == null
                    || frame.local.size() <= LAST_ROW_LOCAL) {
                continue;
            }
            Object type = frame.local.get(LAST_ROW_LOCAL);
            if ("java/lang/String".equals(type)) {
                frame.local.set(LAST_ROW_LOCAL, JSON_OBJECT);
                adjustedFrames++;
            } else if (JSON_OBJECT.equals(type)) {
                throw new PatchException(
                        "CSV parser StackMap 已包含修改后的 row 类型");
            }
        }
        if (adjustedFrames != 1) {
            throw new PatchException(
                    "CSV parser StackMap last-row 类型调整数异常: "
                            + adjustedFrames + "（预期 1）");
        }

        // ALOAD row, ICONST_2, JSONObject.toString(2), ASTORE last
        // -> ALOAD row, ASTORE last
        method.instructions.remove(indent);
        method.instructions.remove(eagerPrettyCall);

        method.instructions.insert(
                errorLoads.get(0),
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, FORMATTER, FORMAT_METHOD,
                        FORMAT_DESC, false));

        int oldCalls = AsmUtil.countMethodCall(
                classNode, JSON_OBJECT, "toString", PRETTY_DESC);
        int newCalls = AsmUtil.countMethodCall(
                classNode, FORMATTER, FORMAT_METHOD, FORMAT_DESC);
        if (oldCalls != 0 || newCalls != 1) {
            throw new PatchException(
                    "CSV lazy formatting 验证失败: eager=" + oldCalls
                            + ", lazy=" + newCalls);
        }

        return PatchResult.of(id(), context.classPath(), 1, 1, newCalls,
                "store the last JSONObject and pretty-print it only on "
                        + "the mismatched-quotes error path");
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc,
            String label) {
        List<MethodInsnNode> calls = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc))
                .map(node -> (MethodInsnNode) node)
                .toList();
        if (calls.size() != 1) {
            throw new PatchException(
                    label + " 调用数异常: " + calls.size() + "（预期 1）");
        }
        return calls.get(0);
    }

    private static boolean isStringAppend(AbstractInsnNode node) {
        return node instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/StringBuilder".equals(call.owner)
                && "append".equals(call.name)
                && "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                .equals(call.desc);
    }
}

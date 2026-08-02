package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 用 trigger -> ID HashSet 代替 Rules 加载时对同 trigger 既有 rule 的全表扫描。
 *
 * <p>helper 返回空列表或当前 rule 的 singleton，因此保留原循环、异常构造、StackMap、
 * 正式规则列表和首个重复错误顺序，只把迭代候选从 O(k) 缩到 O(1)。
 */
public final class RulesDuplicateIdPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/starfarer/campaign/rules/Rules.class";
    private static final String OWNER =
            "com/fs/starfarer/campaign/rules/Rules";
    private static final String LOAD_DESC =
            "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V";
    private static final String RULE_CLASS =
            "com/fs/starfarer/campaign/rules/ooOO";
    private static final String RULE_GET_ID = "getId";
    private static final String RULE_GET_ID_DESC = "()Ljava/lang/String;";
    private static final String RULE_LIST_DESC =
            "(Ljava/lang/String;)Ljava/util/List;";
    private static final String TRACKER =
            "org/fossic/starsector/optimization/RuleIdTracker";
    private static final String CANDIDATES_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)"
                    + "Ljava/util/List;";
    private static final int CURRENT_RULE_LOCAL = 10;
    private static final String DUPLICATE_MARKER = "Duplicate rule id: ";

    @Override
    public String id() {
        return "rules-linear-duplicate-id-check";
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
                .filter(m -> LOAD_DESC.equals(m.desc)
                        && (m.access & Opcodes.ACC_PUBLIC) != 0
                        && (m.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "Rules loader 方法签名匹配数异常: " + matches.size()
                            + "（预期 1）");
        }
        MethodNode method = matches.get(0);
        if (countMarker(method, DUPLICATE_MARKER) != 1) {
            throw new PatchException("Rules duplicate marker 数量不是 1");
        }

        MethodInsnNode duplicateListCall = findDuplicateListCall(method);
        AbstractInsnNode triggerLoad = AsmUtil.previousReal(duplicateListCall);
        if (!(triggerLoad instanceof VarInsnNode trigger)
                || trigger.getOpcode() != Opcodes.ALOAD) {
            throw new PatchException(
                    "Rules duplicate scan 前找不到 trigger ALOAD");
        }

        MethodInsnNode clearCall = uniqueCall(
                method, "java/util/Map", "clear", "()V", true,
                "Rules map clear");
        int returns = (int) AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() == Opcodes.RETURN)
                .count();
        if (returns != 1) {
            throw new PatchException(
                    "Rules loader RETURN 数量异常: " + returns + "（预期 1）");
        }
        AbstractInsnNode returnInsn = AsmUtil.instructions(method).stream()
                .filter(node -> node.getOpcode() == Opcodes.RETURN)
                .findFirst().orElseThrow();

        method.instructions.insert(
                clearCall,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, TRACKER, "reset", "()V", false));

        InsnList candidateArguments = new InsnList();
        candidateArguments.add(new VarInsnNode(
                Opcodes.ALOAD, CURRENT_RULE_LOCAL));
        candidateArguments.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, RULE_CLASS, RULE_GET_ID,
                RULE_GET_ID_DESC, false));
        candidateArguments.add(new VarInsnNode(
                Opcodes.ALOAD, CURRENT_RULE_LOCAL));
        method.instructions.insertBefore(duplicateListCall, candidateArguments);
        method.instructions.set(
                duplicateListCall,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, TRACKER, "candidates",
                        CANDIDATES_DESC, false));

        method.instructions.insertBefore(
                returnInsn,
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, TRACKER, "finish", "()V", false));

        int resetCalls = AsmUtil.countMethodCall(
                classNode, TRACKER, "reset", "()V");
        int candidateCalls = AsmUtil.countMethodCall(
                classNode, TRACKER, "candidates", CANDIDATES_DESC);
        int finishCalls = AsmUtil.countMethodCall(
                classNode, TRACKER, "finish", "()V");
        if (resetCalls != 1 || candidateCalls != 1 || finishCalls != 1) {
            throw new PatchException(
                    "Rules ID tracker 调用验证失败: reset=" + resetCalls
                            + ", candidates=" + candidateCalls
                            + ", finish=" + finishCalls);
        }
        if (findDuplicateListCalls(method).size() != 0) {
            throw new PatchException(
                    "Rules 原 O(k) duplicate list scan 调用仍然存在");
        }

        return PatchResult.of(id(), context.classPath(), 3, 3, 3,
                "preserve the original duplicate loop and error, but feed "
                        + "it O(1) HashSet candidates per trigger");
    }

    private static MethodInsnNode findDuplicateListCall(MethodNode method) {
        List<MethodInsnNode> calls = findDuplicateListCalls(method);
        if (calls.size() != 1) {
            throw new PatchException(
                    "Rules duplicate list scan 匹配数异常: " + calls.size()
                            + "（预期 1）");
        }
        return calls.get(0);
    }

    private static List<MethodInsnNode> findDuplicateListCalls(
            MethodNode method) {
        return AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && OWNER.equals(call.owner)
                        && RULE_LIST_DESC.equals(call.desc)
                        && followedByIteratorAndDuplicateMarker(call))
                .map(node -> (MethodInsnNode) node)
                .toList();
    }

    private static boolean followedByIteratorAndDuplicateMarker(
            MethodInsnNode call) {
        AbstractInsnNode next = AsmUtil.nextReal(call);
        if (!(next instanceof MethodInsnNode iterator)
                || iterator.getOpcode() != Opcodes.INVOKEINTERFACE
                || !"java/util/List".equals(iterator.owner)
                || !"iterator".equals(iterator.name)
                || !"()Ljava/util/Iterator;".equals(iterator.desc)) {
            return false;
        }
        int realInstructions = 0;
        for (AbstractInsnNode node = next; node != null && realInstructions < 45;
             node = node.getNext()) {
            if (node.getType() == AbstractInsnNode.LABEL
                    || node.getType() == AbstractInsnNode.LINE
                    || node.getType() == AbstractInsnNode.FRAME) {
                continue;
            }
            realInstructions++;
            if (node instanceof LdcInsnNode ldc
                    && DUPLICATE_MARKER.equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    private static int countMarker(MethodNode method, String marker) {
        return (int) AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof LdcInsnNode ldc
                        && marker.equals(ldc.cst))
                .count();
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String desc,
            boolean interfaceCall, String label) {
        List<MethodInsnNode> calls = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)
                        && call.itf == interfaceCall)
                .map(node -> (MethodInsnNode) node)
                .toList();
        if (calls.size() != 1) {
            throw new PatchException(
                    label + " 调用数异常: " + calls.size() + "（预期 1）");
        }
        return calls.get(0);
    }
}

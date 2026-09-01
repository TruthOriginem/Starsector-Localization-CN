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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把资源根快照和一次性 open 状态消费留在短同步方法内，在 monitor 外遍历和打开文件。
 */
public final class ResourceLookupSynchronizationPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/util/C.class";
    private static final String OWNER = "com/fs/util/C";
    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String OPEN_DESC =
            "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final String OPEN_WITH_MODE_DESC =
            "(Ljava/lang/String;Z)Ljava/io/InputStream;";
    private static final String SNAPSHOT_METHOD = "$fossic$snapshotResourceRoots";
    private static final String SNAPSHOT_DESC = "()Ljava/util/List;";
    private static final String OPEN_CONTEXT_METHOD = "$fossic$takeOpenContext";
    private static final String OPEN_CONTEXT_DESC = "(Z)[Ljava/lang/Object;";
    private static final String SPECULATIVE_CONTEXT =
            "org/fossic/starsector/optimization/SpeculativeResourceContext";

    private static final Map<String, Integer> READ_METHOD_COUNTS;

    static {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(OPEN_DESC, 1);
        counts.put(OPEN_WITH_MODE_DESC, 1);
        counts.put("(Ljava/lang/String;Z)J", 1);
        counts.put("(Ljava/lang/String;)Ljava/util/List;", 2);
        counts.put("(Ljava/lang/String;Ljava/lang/String;Z)Ljava/util/List;", 1);
        counts.put("(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;", 1);
        counts.put("(Ljava/lang/String;Z)Ljava/io/File;", 1);
        counts.put("(Ljava/lang/String;)Ljava/lang/String;", 1);
        READ_METHOD_COUNTS = Map.copyOf(counts);
    }

    @Override
    public String id() {
        return "resource-lookup-short-monitor";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.RESOURCE_LOCKS;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        FieldNode rootsField = uniqueField(classNode, LIST_DESC, false, "资源根列表");
        FieldNode selectorField = uniqueField(
                classNode, "Ljava/lang/String;", false, "下一来源 selector");
        FieldNode skipModsField = classNode.fields.stream()
                .filter(f -> "Z".equals(f.desc)
                        && (f.access & Opcodes.ACC_STATIC) != 0
                        && (f.access & Opcodes.ACC_PUBLIC) != 0)
                .reduce((a, b) -> {
                    throw new PatchException("静态 skip-mod flag 匹配超过一个");
                })
                .orElseThrow(() -> new PatchException("找不到静态 skip-mod flag"));

        List<MethodNode> readMethods = classNode.methods.stream()
                .filter(m -> READ_METHOD_COUNTS.containsKey(m.desc)
                        && (m.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        verifyMethodCounts(readMethods);
        for (MethodNode method : readMethods) {
            if ((method.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
                throw new PatchException(
                        "资源高层读取方法不再是 synchronized: "
                                + method.name + method.desc);
            }
        }

        if (classNode.methods.stream().anyMatch(m ->
                SNAPSHOT_METHOD.equals(m.name) || OPEN_CONTEXT_METHOD.equals(m.name))) {
            throw new PatchException("资源短锁 helper 已存在，拒绝重复 patch");
        }
        classNode.methods.add(createSnapshotMethod(rootsField));
        classNode.methods.add(createOpenContextMethod(
                rootsField, selectorField, skipModsField));

        MethodNode openWrapper = uniqueMethod(readMethods, OPEN_DESC);
        MethodNode openWithMode = uniqueMethod(readMethods, OPEN_WITH_MODE_DESC);
        rewriteOpenWithMode(openWithMode, rootsField);

        int snapshotRewrites = 0;
        for (MethodNode method : readMethods) {
            method.access &= ~Opcodes.ACC_SYNCHRONIZED;
            if (method == openWrapper || method == openWithMode) {
                continue;
            }
            int methodRewrites = replaceRootsFieldRead(method, rootsField);
            if (methodRewrites != 1) {
                throw new PatchException(
                        "资源读取方法的根列表读取数异常: "
                                + method.name + method.desc + " -> " + methodRewrites);
            }
            snapshotRewrites += methodRewrites;
        }
        if (snapshotRewrites != 7) {
            throw new PatchException(
                    "资源根快照替换总数异常: " + snapshotRewrites + "（预期 7）");
        }

        int verified = (int) readMethods.stream()
                .filter(m -> (m.access & Opcodes.ACC_SYNCHRONIZED) == 0)
                .count();
        int contextCalls = AsmUtil.countMethodCall(
                classNode, OWNER, OPEN_CONTEXT_METHOD, OPEN_CONTEXT_DESC);
        int snapshotCalls = AsmUtil.countMethodCall(
                classNode, OWNER, SNAPSHOT_METHOD, SNAPSHOT_DESC);
        if (contextCalls != 1 || snapshotCalls != 7) {
            throw new PatchException(
                    "资源短锁调用验证失败: context=" + contextCalls
                            + ", snapshots=" + snapshotCalls);
        }

        return PatchResult.of(id(), context.classPath(), 9, readMethods.size(), verified,
                "move root snapshots and one-shot flags into short synchronized helpers; "
                        + "run nine high-level lookups outside the global monitor");
    }

    private static FieldNode uniqueField(
            ClassNode classNode, String desc, boolean requireStatic, String label) {
        List<FieldNode> matches = classNode.fields.stream()
                .filter(f -> desc.equals(f.desc)
                        && ((f.access & Opcodes.ACC_STATIC) != 0) == requireStatic)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(label + "字段匹配数异常: " + matches.size());
        }
        return matches.get(0);
    }

    private static void verifyMethodCounts(List<MethodNode> methods) {
        for (Map.Entry<String, Integer> expected : READ_METHOD_COUNTS.entrySet()) {
            long actual = methods.stream()
                    .filter(m -> expected.getKey().equals(m.desc))
                    .count();
            if (actual != expected.getValue()) {
                throw new PatchException(
                        "资源高层方法 descriptor 数异常: " + expected.getKey()
                                + " -> " + actual + "（预期 "
                                + expected.getValue() + "）");
            }
        }
        if (methods.size() != 9) {
            throw new PatchException(
                    "资源高层读取方法总数异常: " + methods.size() + "（预期 9）");
        }
    }

    private static MethodNode uniqueMethod(List<MethodNode> methods, String desc) {
        List<MethodNode> matches = methods.stream()
                .filter(m -> desc.equals(m.desc))
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "资源 open 方法匹配数异常: " + desc + " -> " + matches.size());
        }
        return matches.get(0);
    }

    private static MethodNode createSnapshotMethod(FieldNode rootsField) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
                SNAPSHOT_METHOD, SNAPSHOT_DESC, null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, rootsField.name, rootsField.desc));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode createOpenContextMethod(
            FieldNode rootsField, FieldNode selectorField, FieldNode skipModsField) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
                OPEN_CONTEXT_METHOD, OPEN_CONTEXT_DESC, null, null);
        LabelNode ordinaryAccess = new LabelNode();
        LabelNode noSkip = new LabelNode();

        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SPECULATIVE_CONTEXT,
                "isActive",
                "()Z",
                false));
        method.instructions.add(new JumpInsnNode(
                Opcodes.IFEQ, ordinaryAccess));
        appendSpeculativeContext(
                method.instructions, rootsField);
        method.instructions.add(ordinaryAccess);
        method.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[] {OWNER, Opcodes.INTEGER},
                0,
                null));

        method.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.instructions.add(new TypeInsnNode(
                Opcodes.ANEWARRAY, "java/lang/Object"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, selectorField.name, selectorField.desc));
        method.instructions.add(new InsnNode(Opcodes.AASTORE));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, OWNER, selectorField.name, selectorField.desc));

        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, skipModsField.name, skipModsField.desc));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, noSkip));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, OWNER, skipModsField.name, skipModsField.desc));
        method.instructions.add(noSkip);
        method.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[] {
                    OWNER, Opcodes.INTEGER, "[Ljava/lang/Object;"
                },
                0,
                null));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                "(Z)Ljava/lang/Boolean;", false));
        method.instructions.add(new InsnNode(Opcodes.AASTORE));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, rootsField.name, rootsField.desc));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false));
        method.instructions.add(new InsnNode(Opcodes.AASTORE));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 6;
        method.maxLocals = 3;
        return method;
    }

    private static void appendSpeculativeContext(
            InsnList instructions, FieldNode rootsField) {
        instructions.add(new InsnNode(Opcodes.ICONST_3));
        instructions.add(new TypeInsnNode(
                Opcodes.ANEWARRAY, "java/lang/Object"));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));

        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ICONST_0));
        instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        instructions.add(new InsnNode(Opcodes.AASTORE));

        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ICONST_1));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Boolean",
                "valueOf",
                "(Z)Ljava/lang/Boolean;",
                false));
        instructions.add(new InsnNode(Opcodes.AASTORE));

        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ICONST_2));
        instructions.add(new TypeInsnNode(
                Opcodes.NEW, "java/util/ArrayList"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, OWNER, rootsField.name, rootsField.desc));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(Ljava/util/Collection;)V",
                false));
        instructions.add(new InsnNode(Opcodes.AASTORE));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void rewriteOpenWithMode(
            MethodNode method, FieldNode rootsField) {
        AbstractInsnNode iteratorCall = AsmUtil.instructions(method).stream()
                .filter(node -> node instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "iterator".equals(call.name)
                        && "()Ljava/util/Iterator;".equals(call.desc))
                .findFirst()
                .orElseThrow(() -> new PatchException(
                        "open(path, mode) 找不到根列表 iterator"));
        AbstractInsnNode iteratorStore = AsmUtil.nextReal(iteratorCall);
        if (!(iteratorStore instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE
                || store.var != 6) {
            throw new PatchException(
                    "open(path, mode) iterator local 结构变化");
        }
        AbstractInsnNode afterPrefix = iteratorStore.getNext();

        AbstractInsnNode node = method.instructions.getFirst();
        while (node != afterPrefix) {
            AbstractInsnNode next = node.getNext();
            method.instructions.remove(node);
            node = next;
        }

        InsnList prefix = new InsnList();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new VarInsnNode(Opcodes.ILOAD, 2));
        prefix.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, OWNER, OPEN_CONTEXT_METHOD,
                OPEN_CONTEXT_DESC, false));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, 8));

        prefix.add(new VarInsnNode(Opcodes.ALOAD, 8));
        prefix.add(new InsnNode(Opcodes.ICONST_0));
        prefix.add(new InsnNode(Opcodes.AALOAD));
        prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, 3));

        prefix.add(new VarInsnNode(Opcodes.ALOAD, 8));
        prefix.add(new InsnNode(Opcodes.ICONST_1));
        prefix.add(new InsnNode(Opcodes.AALOAD));
        prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
        prefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue",
                "()Z", false));
        prefix.add(new VarInsnNode(Opcodes.ISTORE, 2));

        prefix.add(new LdcInsnNode(""));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, 4));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 8));
        prefix.add(new InsnNode(Opcodes.ICONST_2));
        prefix.add(new InsnNode(Opcodes.AALOAD));
        prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        prefix.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;", true));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, 6));
        method.instructions.insertBefore(afterPrefix, prefix);
        method.maxStack = Math.max(method.maxStack, 3);
        method.maxLocals = Math.max(method.maxLocals, 9);

        if (replaceRootsFieldRead(method, rootsField) != 0) {
            throw new PatchException(
                    "open(path, mode) 原始根列表读取未被完整移除");
        }
    }

    private static int replaceRootsFieldRead(
            MethodNode method, FieldNode rootsField) {
        int count = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && OWNER.equals(field.owner)
                    && rootsField.name.equals(field.name)
                    && rootsField.desc.equals(field.desc)) {
                method.instructions.set(field, new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, OWNER, SNAPSHOT_METHOD,
                        SNAPSHOT_DESC, false));
                count++;
            }
        }
        return count;
    }
}

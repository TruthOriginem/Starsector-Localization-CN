package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Redirect the original selector accesses, including the optional short-lock helper. */
public final class ResourceContextIsolationPatch implements JarPatch {
    private static final String OWNER = "com/fs/util/C";
    private static final String HELPER = "org/fossic/starsector/optimization/ResourceReadContext";
    private static final String FIELD = "$fossic$resourceReadContext";
    private static final String DESC = "L" + HELPER + ";";

    @Override
    public String id() { return "resource-context-isolation"; }
    @Override
    public PatchGroup group() { return PatchGroup.RESOURCE_CONTEXT; }
    @Override
    public String targetJar() { return JarWorkspace.COMMON_OBF_JAR; }
    @Override
    public Set<String> targetClasses() { return Set.of(OWNER + ".class"); }

    @Override
    public PatchResult applyAndVerify(ClassNode node, PatchContext context) {
        if (node.fields.stream().anyMatch(f -> FIELD.equals(f.name))) {
            throw new PatchException("资源线程上下文已存在，拒绝重复 patch");
        }
        List<FieldNode> selectors = node.fields.stream()
                .filter(f -> "Ljava/lang/String;".equals(f.desc)
                        && (f.access & Opcodes.ACC_STATIC) == 0).toList();
        if (selectors.size() != 1) {
            throw new PatchException("资源 selector 字段匹配数异常: " + selectors.size());
        }
        FieldNode selector = selectors.get(0);
        int reads = 0, writes = 0, constructors = 0, failures = 0, skipReads = 0;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC, FIELD, DESC, null, null));
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : AsmUtil.instructions(method)) {
                if (insn instanceof FieldInsnNode field && OWNER.equals(field.owner)
                        && "super".equals(field.name) && "Z".equals(field.desc)
                        && field.getOpcode() == Opcodes.GETSTATIC) {
                    method.instructions.insert(field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HELPER, "consumeSkipMods", "(Z)Z", false));
                    skipReads++;
                }
                if (insn instanceof MethodInsnNode call && "<init>".equals(method.name)
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "java/lang/Object".equals(call.owner) && "<init>".equals(call.name)) {
                    InsnList init = new InsnList();
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new TypeInsnNode(Opcodes.NEW, HELPER));
                    init.add(new InsnNode(Opcodes.DUP));
                    init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, HELPER, "<init>", "()V", false));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, FIELD, DESC));
                    method.instructions.insert(call, init);
                    method.maxStack = Math.max(method.maxStack, 3);
                    constructors++;
                }
                if (insn instanceof FieldInsnNode field && OWNER.equals(field.owner)
                        && selector.name.equals(field.name) && selector.desc.equals(field.desc)) {
                    InsnList replacement = new InsnList();
                    if (field.getOpcode() == Opcodes.GETFIELD) {
                        replacement.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, FIELD, DESC));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HELPER,
                                "selector", "()Ljava/lang/String;", false));
                        reads++;
                    } else if (field.getOpcode() == Opcodes.PUTFIELD) {
                        replacement.add(new InsnNode(Opcodes.SWAP));
                        replacement.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, FIELD, DESC));
                        replacement.add(new InsnNode(Opcodes.SWAP));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HELPER,
                                "select", "(Ljava/lang/String;)V", false));
                        writes++;
                    } else {
                        throw new PatchException("资源 selector 使用了意外的字段操作");
                    }
                    method.instructions.insertBefore(field, replacement);
                    method.instructions.remove(field);
                }
                if ("(Ljava/lang/String;Z)Ljava/io/InputStream;".equals(method.desc)
                        && insn.getOpcode() == Opcodes.ATHROW) {
                    InsnList report = new InsnList();
                    report.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    report.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    report.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                            "reportMissing", "(Ljava/lang/String;Ljava/lang/String;Z)V", false));
                    method.instructions.insertBefore(insn, report);
                    method.maxStack += 3;
                    failures++;
                }
            }
        }
        if (reads != 1 || writes != 3 || constructors != 1 || failures != 1 || skipReads != 1) {
            throw new PatchException("资源线程上下文结构变化: reads=" + reads + ", writes="
                    + writes + ", constructors=" + constructors + ", failures=" + failures
                    + ", skipReads=" + skipReads);
        }
        int verified = AsmUtil.countMethodCall(node, HELPER, "selector", "()Ljava/lang/String;")
                + AsmUtil.countMethodCall(node, HELPER, "select", "(Ljava/lang/String;)V")
                + AsmUtil.countMethodCall(node, HELPER, "consumeSkipMods", "(Z)Z");
        return PatchResult.of(id(), context.classPath(), 5, reads + writes + skipReads, verified,
                "thread-confined one-shot selector; preserve I/O concurrency and legacy signatures");
    }
}

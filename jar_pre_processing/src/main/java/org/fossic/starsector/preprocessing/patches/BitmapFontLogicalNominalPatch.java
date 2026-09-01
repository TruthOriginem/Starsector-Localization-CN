package org.fossic.starsector.preprocessing.patches;

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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 令 BitmapFont 对外公开逻辑 nominal，同时为原版 renderer 保留代理 raw nominal。
 *
 * <p>代理 FNT 的 nominal 为实现精确缩放而放大了 {@code scale * 64}。
 * 地图与 mod 会将公开 getter 乘任意缩放系数生成 requested size，因此不能
 * 根据数值幅度事后猜测来源。本 Patch 克隆原 getter 为 raw getter，公开 getter
 * 只对已映射代理返回 base nominal。</p>
 */
public final class BitmapFontLogicalNominalPatch implements JarPatch {
    static final String FONT_CLASS = "com/fs/graphics/A/F";
    private static final String TARGET = FONT_CLASS + ".class";
    static final String NOMINAL_GETTER = "Õ" + "00000";
    static final String RAW_NOMINAL_GETTER = "$dynfontRawNominal";
    private static final String HOOK =
            "org/fossic/starsector/dynfont/DynFontRenderHooks";

    @Override
    public String id() {
        return "bitmap-font-logical-nominal";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.DYNFONT;
    }

    @Override
    public String targetJar() {
        return JarWorkspace.COMMON_OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        MethodNode getter = requireOneMethod(classNode, NOMINAL_GETTER, "()I");
        requireSimpleIntFieldGetter(getter);
        if (classNode.methods.stream().anyMatch(m -> RAW_NOMINAL_GETTER.equals(m.name))) {
            throw new PatchException("raw nominal getter 已存在");
        }

        MethodNode raw = cloneMethod(getter, RAW_NOMINAL_GETTER);
        int returns = 0;
        for (AbstractInsnNode insn = getter.instructions.getFirst(); insn != null;) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.IRETURN) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.SWAP));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "logicalNominal",
                        "(Ljava/lang/Object;I)I", false));
                getter.instructions.insertBefore(insn, hook);
                returns++;
            }
            insn = next;
        }
        if (returns != 1) {
            throw new PatchException("nominal getter IRETURN 数量异常: " + returns);
        }
        getter.maxStack = Math.max(getter.maxStack, 2);
        classNode.methods.add(raw);

        int verified = countCalls(classNode, HOOK, "logicalNominal",
                "(Ljava/lang/Object;I)I");
        return PatchResult.of(id(), context.classPath(), 2, 2,
                verified + (hasMethod(classNode, RAW_NOMINAL_GETTER, "()I") ? 1 : 0),
                "public nominal is logical for proxies; raw getter retained for renderer");
    }

    private static void requireSimpleIntFieldGetter(MethodNode method) {
        int fields = 0;
        int returns = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) {
                if (field.getOpcode() != Opcodes.GETFIELD || !FONT_CLASS.equals(field.owner)
                        || !"I".equals(field.desc)) {
                    throw new PatchException("nominal getter 字段结构漂移");
                }
                fields++;
            } else if (insn.getOpcode() == Opcodes.IRETURN) {
                returns++;
            }
        }
        if (fields != 1 || returns != 1) {
            throw new PatchException("nominal getter 不再是唯一 int 字段读取: fields="
                    + fields + ", returns=" + returns);
        }
    }

    private static MethodNode requireOneMethod(ClassNode node, String name, String desc) {
        List<MethodNode> methods = node.methods.stream()
                .filter(m -> name.equals(m.name) && desc.equals(m.desc)).toList();
        if (methods.size() != 1) {
            throw new PatchException("method " + name + desc + " count=" + methods.size());
        }
        return methods.get(0);
    }

    private static MethodNode cloneMethod(MethodNode original, String name) {
        MethodNode clone = new MethodNode(Opcodes.ASM9, original.access, name,
                original.desc, original.signature,
                original.exceptions.toArray(String[]::new));
        original.accept(clone);
        return clone;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> name.equals(m.name) && desc.equals(m.desc));
    }

    private static int countCalls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                        && name.equals(call.name) && desc.equals(call.desc)) count++;
            }
        }
        return count;
    }
}

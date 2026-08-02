package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 让原版字体 glyph 数组在进入手写复制循环前通过 JVM 批量扩容。 */
public final class GlyphArrayGrowthPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/graphics/A/F.class";
    private static final String OWNER = "com/fs/graphics/A/F";
    private static final String GLYPH = "com/fs/graphics/A/oOOO";
    private static final String GLYPH_ARRAY_DESC =
            "[Lcom/fs/graphics/A/oOOO;";
    private static final String TARGET_DESC =
            "(Lcom/fs/graphics/A/oOOO;)V";
    private static final String HELPER =
            "org/fossic/starsector/optimization/GlyphArrayGrowth";
    private static final String HELPER_DESC =
            "([Ljava/lang/Object;I)[Ljava/lang/Object;";

    @Override
    public String id() {
        return "font-glyph-bulk-array-growth";
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
    public PatchResult applyAndVerify(
            ClassNode classNode, PatchContext context) {
        List<FieldNode> glyphFields = classNode.fields.stream()
                .filter(field -> GLYPH_ARRAY_DESC.equals(field.desc))
                .toList();
        List<MethodNode> targets = classNode.methods.stream()
                .filter(method -> "o00000".equals(method.name))
                .filter(method -> TARGET_DESC.equals(method.desc))
                .toList();
        int existing = AsmUtil.countMethodCall(
                classNode, HELPER, "ensureCapacity", HELPER_DESC);
        if (glyphFields.size() != 1
                || targets.size() != 1
                || existing != 0) {
            throw new PatchException(
                    "字体 glyph 扩容结构异常: fields="
                            + glyphFields.size() + ", methods="
                            + targets.size() + ", helper=" + existing);
        }

        MethodNode target = targets.get(0);
        List<MethodInsnNode> glyphIdCalls = AsmUtil.instructions(target)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> GLYPH.equals(call.owner))
                .filter(call -> "return".equals(call.name))
                .filter(call -> "()I".equals(call.desc))
                .toList();
        long allocations = AsmUtil.instructions(target).stream()
                .filter(TypeInsnNode.class::isInstance)
                .map(TypeInsnNode.class::cast)
                .filter(node -> node.getOpcode() == Opcodes.ANEWARRAY)
                .filter(node -> GLYPH.equals(node.desc))
                .count();
        long stores = AsmUtil.instructions(target).stream()
                .filter(node -> node.getOpcode() == Opcodes.AASTORE)
                .count();
        if (glyphIdCalls.size() != 2
                || allocations != 1
                || stores != 2) {
            throw new PatchException(
                    "字体 glyph 原复制循环异常: ids="
                            + glyphIdCalls.size() + ", allocations="
                            + allocations + ", stores=" + stores);
        }

        AbstractInsnNode idStoreNode = AsmUtil.nextReal(
                glyphIdCalls.get(0));
        if (!(idStoreNode instanceof VarInsnNode idStore)
                || idStore.getOpcode() != Opcodes.ISTORE) {
            throw new PatchException(
                    "字体 glyph ID 后预期 ISTORE，实际 opcode="
                            + (idStoreNode == null
                            ? "null" : idStoreNode.getOpcode()));
        }

        FieldNode glyphField = glyphFields.get(0);
        InsnList bridge = new InsnList();
        bridge.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                OWNER,
                glyphField.name,
                glyphField.desc));
        bridge.add(new VarInsnNode(Opcodes.ILOAD, idStore.var));
        bridge.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "ensureCapacity",
                HELPER_DESC,
                false));
        bridge.add(new TypeInsnNode(
                Opcodes.CHECKCAST, GLYPH_ARRAY_DESC));
        bridge.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                OWNER,
                glyphField.name,
                glyphField.desc));
        target.instructions.insert(idStore, bridge);
        target.maxStack = Math.max(target.maxStack, 3);

        int verified = AsmUtil.countMethodCall(
                classNode, HELPER, "ensureCapacity", HELPER_DESC);
        if (verified != 1) {
            throw new PatchException(
                    "字体 glyph 批量扩容 bridge 验证失败: "
                            + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 1, 1, 1,
                "preserve glyphId+100 capacity while using bulk copy");
    }
}

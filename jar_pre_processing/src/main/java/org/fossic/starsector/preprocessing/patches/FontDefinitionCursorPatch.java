package org.fossic.starsector.preprocessing.patches;

import java.util.List;
import java.util.Set;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 在 O26 之后以顺序游标取代仍会分配的 BMFont token 数组。 */
public final class FontDefinitionCursorPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/graphics/A/D.class";
    private static final String OWNER = "com/fs/graphics/A/D";
    private static final String OLD_HELPER =
            "org/fossic/starsector/optimization/FontDefinitionParser";
    private static final String CURSOR =
            "org/fossic/starsector/optimization/FontDefinitionCursor";
    private static final String CURSOR_DESC = "L" + CURSOR + ";";
    private static final String CURSOR_FIELD =
            "starsector$fontDefinitionCursor";

    @Override
    public String id() {
        return "font-definition-cursor-parser";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.FONT_TOKEN_CURSOR;
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
        if (!OWNER.equals(classNode.name)) {
            throw new PatchException(
                    "BMFont 游标目标类异常: " + classNode.name);
        }

        FieldNode tokens = uniqueStaticField(
                classNode, "[Ljava/lang/String;");
        FieldNode originalCursor = uniqueStaticField(classNode, "I");
        MethodNode tokenize = uniquePrivateStaticMethod(
                classNode, "super", "(Ljava/lang/String;)V");
        MethodNode parseInt = uniquePrivateStaticMethod(
                classNode, "super", "()I");
        MethodNode parseString = uniquePrivateStaticMethod(
                classNode, "super", "(Z)Ljava/lang/String;");

        if (classNode.fields.stream().anyMatch(field ->
                CURSOR_FIELD.equals(field.name))) {
            throw new PatchException("BMFont 游标字段已存在，拒绝重复 patch");
        }
        if (oldHelperCalls(classNode) != 3
                || cursorHelperCalls(classNode) != 0) {
            throw new PatchException(
                    "BMFont 游标前置 O26 结构异常: old="
                            + oldHelperCalls(classNode) + ", cursor="
                            + cursorHelperCalls(classNode));
        }

        classNode.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                CURSOR_FIELD,
                CURSOR_DESC,
                null,
                null));
        replaceTokenizer(tokenize, originalCursor);
        replaceIntParser(parseInt, originalCursor);
        replaceStringParser(parseString, originalCursor);

        if (oldHelperCalls(classNode) != 0
                || cursorHelperCalls(classNode) != 3
                || countFieldReferences(classNode, tokens) != 0) {
            throw new PatchException(
                    "BMFont 游标桥接验证失败: old="
                            + oldHelperCalls(classNode) + ", cursor="
                            + cursorHelperCalls(classNode) + ", tokens="
                            + countFieldReferences(classNode, tokens));
        }
        return PatchResult.of(
                id(), context.classPath(), 3, 3, 3,
                "replace token arrays with sequential line cursor");
    }

    private static void replaceTokenizer(
            MethodNode method, FieldNode originalCursor) {
        clearBody(method);
        InsnList replacement = new InsnList();
        replacement.add(new InsnNode(Opcodes.ICONST_0));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                OWNER,
                originalCursor.name,
                originalCursor.desc));
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                OWNER,
                CURSOR_FIELD,
                CURSOR_DESC));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CURSOR,
                "reset",
                "(" + CURSOR_DESC + "Ljava/lang/String;)" + CURSOR_DESC,
                false));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                OWNER,
                CURSOR_FIELD,
                CURSOR_DESC));
        replacement.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(replacement);
        method.maxStack = 2;
        method.maxLocals = 1;
    }

    private static void replaceIntParser(
            MethodNode method, FieldNode originalCursor) {
        clearBody(method);
        InsnList replacement = incrementOriginalCursor(originalCursor);
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, CURSOR_FIELD, CURSOR_DESC));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                CURSOR,
                "nextInt",
                "()I",
                false));
        replacement.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(replacement);
        method.maxStack = 2;
        method.maxLocals = 0;
    }

    private static void replaceStringParser(
            MethodNode method, FieldNode originalCursor) {
        clearBody(method);
        InsnList replacement = incrementOriginalCursor(originalCursor);
        replacement.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, CURSOR_FIELD, CURSOR_DESC));
        replacement.add(new VarInsnNode(Opcodes.ILOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                CURSOR,
                "nextString",
                "(Z)Ljava/lang/String;",
                false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        method.maxStack = 2;
        method.maxLocals = 1;
    }

    private static InsnList incrementOriginalCursor(FieldNode cursor) {
        InsnList result = new InsnList();
        result.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, cursor.name, cursor.desc));
        result.add(new InsnNode(Opcodes.ICONST_1));
        result.add(new InsnNode(Opcodes.IADD));
        result.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, OWNER, cursor.name, cursor.desc));
        return result;
    }

    private static void clearBody(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }
    }

    private static FieldNode uniqueStaticField(
            ClassNode classNode, String descriptor) {
        List<FieldNode> matches = classNode.fields.stream()
                .filter(field -> descriptor.equals(field.desc))
                .filter(field -> (field.access & Opcodes.ACC_STATIC) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "BMFont 游标字段 " + descriptor + " 匹配数异常: "
                            + matches.size());
        }
        return matches.get(0);
    }

    private static MethodNode uniquePrivateStaticMethod(
            ClassNode classNode, String name, String descriptor) {
        List<MethodNode> matches = classNode.methods.stream()
                .filter(method -> name.equals(method.name))
                .filter(method -> descriptor.equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> (method.access & Opcodes.ACC_PRIVATE) != 0)
                .toList();
        if (matches.size() != 1) {
            throw new PatchException(
                    "BMFont 游标方法 " + descriptor + " 匹配数异常: "
                            + matches.size());
        }
        return matches.get(0);
    }

    private static int oldHelperCalls(ClassNode classNode) {
        return AsmUtil.countMethodCall(
                classNode,
                OLD_HELPER,
                "tokenizeAfterKeyword",
                "(Ljava/lang/String;)[Ljava/lang/String;")
                + AsmUtil.countMethodCall(
                        classNode,
                        OLD_HELPER,
                        "parseIntToken",
                        "(Ljava/lang/String;)I")
                + AsmUtil.countMethodCall(
                        classNode,
                        OLD_HELPER,
                        "parseStringToken",
                        "(Ljava/lang/String;Z)Ljava/lang/String;");
    }

    private static int cursorHelperCalls(ClassNode classNode) {
        return AsmUtil.countMethodCall(
                classNode,
                CURSOR,
                "reset",
                "(" + CURSOR_DESC + "Ljava/lang/String;)" + CURSOR_DESC)
                + AsmUtil.countMethodCall(
                        classNode, CURSOR, "nextInt", "()I")
                + AsmUtil.countMethodCall(
                        classNode,
                        CURSOR,
                        "nextString",
                        "(Z)Ljava/lang/String;");
    }

    private static int countFieldReferences(
            ClassNode classNode, FieldNode field) {
        int result = 0;
        for (MethodNode method : classNode.methods) {
            for (var instruction : AsmUtil.instructions(method)) {
                if (instruction instanceof FieldInsnNode access
                        && OWNER.equals(access.owner)
                        && field.name.equals(access.name)
                        && field.desc.equals(access.desc)) {
                    result++;
                }
            }
        }
        return result;
    }
}

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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 用可测试 helper 替换原版 BMFont 解析器的三个正则/切分方法。 */
public final class FontDefinitionParserPatch implements JarPatch {
    private static final String TARGET_CLASS =
            "com/fs/graphics/A/D.class";
    private static final String OWNER = "com/fs/graphics/A/D";
    private static final String HELPER =
            "org/fossic/starsector/optimization/FontDefinitionParser";
    private static final String TOKENIZE_DESC =
            "(Ljava/lang/String;)[Ljava/lang/String;";
    private static final String PARSE_INT_DESC =
            "(Ljava/lang/String;)I";
    private static final String PARSE_STRING_DESC =
            "(Ljava/lang/String;Z)Ljava/lang/String;";

    @Override
    public String id() {
        return "font-definition-low-allocation-parser";
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
                    "BMFont 解析器目标类异常: " + classNode.name);
        }

        FieldNode tokens = uniqueStaticField(
                classNode, "[Ljava/lang/String;");
        FieldNode cursor = uniqueStaticField(classNode, "I");
        MethodNode tokenize = uniquePrivateStaticMethod(
                classNode, "super", "(Ljava/lang/String;)V");
        MethodNode parseInt = uniquePrivateStaticMethod(
                classNode, "super", "()I");
        MethodNode parseString = uniquePrivateStaticMethod(
                classNode, "super", "(Z)Ljava/lang/String;");

        verifyOriginalShape(tokenize, parseInt, parseString);
        if (countHelperCalls(classNode) != 0) {
            throw new PatchException("BMFont helper 已存在，拒绝重复 patch");
        }

        replaceTokenizer(tokenize, tokens, cursor);
        replaceIntParser(parseInt, tokens, cursor);
        replaceStringParser(parseString, tokens, cursor);

        int verified = countHelperCalls(classNode);
        if (verified != 3) {
            throw new PatchException(
                    "BMFont helper bridge 验证失败: " + verified);
        }
        return PatchResult.of(
                id(), context.classPath(), 3, 3, verified,
                "replace regex tokenization and per-value split arrays");
    }

    private static void replaceTokenizer(
            MethodNode method, FieldNode tokens, FieldNode cursor) {
        clearBody(method);
        InsnList replacement = new InsnList();
        replacement.add(new InsnNode(Opcodes.ICONST_0));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, OWNER, cursor.name, cursor.desc));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "tokenizeAfterKeyword",
                TOKENIZE_DESC,
                false));
        replacement.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, OWNER, tokens.name, tokens.desc));
        replacement.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(replacement);
        method.maxStack = 1;
        method.maxLocals = 1;
    }

    private static void replaceIntParser(
            MethodNode method, FieldNode tokens, FieldNode cursor) {
        clearBody(method);
        InsnList replacement = nextToken(tokens, cursor);
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "parseIntToken",
                PARSE_INT_DESC,
                false));
        replacement.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(replacement);
        method.maxStack = 3;
        method.maxLocals = 0;
    }

    private static void replaceStringParser(
            MethodNode method, FieldNode tokens, FieldNode cursor) {
        clearBody(method);
        InsnList replacement = nextToken(tokens, cursor);
        replacement.add(new VarInsnNode(Opcodes.ILOAD, 0));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER,
                "parseStringToken",
                PARSE_STRING_DESC,
                false));
        replacement.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(replacement);
        method.maxStack = 3;
        method.maxLocals = 1;
    }

    private static InsnList nextToken(
            FieldNode tokens, FieldNode cursor) {
        InsnList result = new InsnList();
        result.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, tokens.name, tokens.desc));
        result.add(new FieldInsnNode(
                Opcodes.GETSTATIC, OWNER, cursor.name, cursor.desc));
        result.add(new InsnNode(Opcodes.DUP));
        result.add(new InsnNode(Opcodes.ICONST_1));
        result.add(new InsnNode(Opcodes.IADD));
        result.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, OWNER, cursor.name, cursor.desc));
        result.add(new InsnNode(Opcodes.AALOAD));
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
                    "BMFont 字段 " + descriptor + " 匹配数异常: "
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
                    "BMFont 方法 " + name + descriptor
                            + " 匹配数异常: " + matches.size());
        }
        return matches.get(0);
    }

    private static void verifyOriginalShape(
            MethodNode tokenize,
            MethodNode parseInt,
            MethodNode parseString) {
        int tokenizeIndexOf = countCall(
                tokenize, "java/lang/String", "indexOf",
                "(Ljava/lang/String;)I");
        int tokenizeSubstring = countCall(
                tokenize, "java/lang/String", "substring",
                "(I)Ljava/lang/String;");
        int tokenizeRegex = countCall(
                tokenize, "java/lang/String", "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        int tokenizeSplit = countCall(
                tokenize, "java/lang/String", "split",
                "(Ljava/lang/String;)[Ljava/lang/String;");
        int intSplit = countCall(
                parseInt, "java/lang/String", "split",
                "(Ljava/lang/String;)[Ljava/lang/String;");
        int integerParse = countCall(
                parseInt, "java/lang/Integer", "parseInt",
                "(Ljava/lang/String;)I");
        int stringSplit = countCall(
                parseString, "java/lang/String", "split",
                "(Ljava/lang/String;)[Ljava/lang/String;");
        int quoteRegex = countCall(
                parseString, "java/lang/String", "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

        if (tokenizeIndexOf != 1
                || tokenizeSubstring != 1
                || tokenizeRegex != 1
                || tokenizeSplit != 1
                || intSplit != 1
                || integerParse != 1
                || stringSplit != 1
                || quoteRegex != 1) {
            throw new PatchException(
                    "BMFont 原解析结构变化: indexOf="
                            + tokenizeIndexOf + ", substring="
                            + tokenizeSubstring + ", tokenizeRegex="
                            + tokenizeRegex + ", tokenizeSplit="
                            + tokenizeSplit + ", intSplit=" + intSplit
                            + ", parseInt=" + integerParse
                            + ", stringSplit=" + stringSplit
                            + ", quoteRegex=" + quoteRegex);
        }
    }

    private static int countCall(
            MethodNode method,
            String owner,
            String name,
            String descriptor) {
        int result = 0;
        for (AbstractInsnNode node : AsmUtil.instructions(method)) {
            if (node instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result++;
            }
        }
        return result;
    }

    private static int countHelperCalls(ClassNode classNode) {
        return AsmUtil.countMethodCall(
                classNode, HELPER, "tokenizeAfterKeyword", TOKENIZE_DESC)
                + AsmUtil.countMethodCall(
                        classNode, HELPER, "parseIntToken", PARSE_INT_DESC)
                + AsmUtil.countMethodCall(
                        classNode, HELPER,
                        "parseStringToken", PARSE_STRING_DESC);
    }
}

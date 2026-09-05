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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Makes the renderer's fuzzy highlight fallback safe for translated tooltip text. */
public final class RendererHighlightRegexPatch implements JarPatch {
    private static final String TARGET_CLASS = RendererDynFontPatch.RENDERER_CLASS;
    private static final String TARGET_CLASS_FILE = TARGET_CLASS + ".class";
    private static final String TARGET_METHOD_DESC = "(Ljava/lang/String;)V";
    private static final String PATTERN = "java/util/regex/Pattern";
    private static final String MATCHER = "java/util/regex/Matcher";
    private static final String STRING = "java/lang/String";
    private static final String HOOK =
            "org/fossic/starsector/dynfont/DynFontHighlightHooks";
    private static final String COMPILE_DESC =
            "(Ljava/lang/String;)Ljava/util/regex/Pattern;";

    @Override
    public String id() {
        return "renderer-highlight-safe-regex";
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
        return Set.of(TARGET_CLASS_FILE);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        if (!TARGET_CLASS.equals(classNode.name)) {
            throw failure(context, "unexpected class " + classNode.name);
        }

        List<MethodNode> fallbacks = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            int compileCalls = countCalls(method, PATTERN, "compile", COMPILE_DESC);
            if (compileCalls == 0) continue;
            if (compileCalls != 1 || !TARGET_METHOD_DESC.equals(method.desc)) {
                throw failure(context, "unexpected Pattern.compile structure in "
                        + method.name + method.desc + ": " + compileCalls);
            }
            requireFallbackShape(method, context);
            fallbacks.add(method);
        }
        if (fallbacks.size() != 2) {
            throw failure(context, "expected two fuzzy highlight fallbacks, found "
                    + fallbacks.size());
        }
        int indexMethods = 0;
        int lastIndexMethods = 0;
        for (MethodNode method : fallbacks) {
            int index = countCalls(method, STRING, "indexOf", "(Ljava/lang/String;)I");
            int lastIndex = countCalls(
                    method, STRING, "lastIndexOf", "(Ljava/lang/String;)I");
            if (index == 2 && lastIndex == 0) {
                indexMethods++;
            } else if (index == 1 && lastIndex == 1) {
                lastIndexMethods++;
            } else {
                throw failure(context, "unexpected exact/group-result search structure in "
                        + method.name + method.desc + ": indexOf=" + index
                        + ", lastIndexOf=" + lastIndex);
            }
        }
        if (indexMethods != 1 || lastIndexMethods != 1) {
            throw failure(context, "expected indexOf/lastIndexOf fallback pair");
        }

        int applied = 0;
        for (MethodNode method : fallbacks) {
            for (AbstractInsnNode insn : method.instructions) {
                if (isCall(insn, PATTERN, "compile", COMPILE_DESC)) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                        throw failure(context, "Pattern.compile is no longer static");
                    }
                    call.owner = HOOK;
                    call.name = "compileFallback";
                    call.itf = false;
                    applied++;
                }
            }
        }

        int verified = countCalls(classNode, HOOK, "compileFallback", COMPILE_DESC);
        if (countCalls(classNode, PATTERN, "compile", COMPILE_DESC) != 0) {
            throw failure(context, "an unsafe Pattern.compile call remains");
        }
        return PatchResult.of(id(), context.classPath(), 2, applied, verified,
                "retain exact highlight searches and safely quote two fuzzy fallbacks");
    }

    private static void requireFallbackShape(MethodNode method, PatchContext context) {
        requireCount(method, STRING, "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", 1, context);
        requireCount(method, PATTERN, "matcher",
                "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;", 1, context);
        requireCount(method, MATCHER, "find", "()Z", 1, context);
        requireCount(method, MATCHER, "group", "()Ljava/lang/String;", 1, context);
        int flags = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && "(?is)".equals(ldc.cst)) flags++;
        }
        if (flags != 1) {
            throw failure(context, "expected one (?is) prefix in "
                    + method.name + method.desc + ", found " + flags);
        }
    }

    private static void requireCount(MethodNode method, String owner, String name,
                                     String desc, int expected, PatchContext context) {
        int actual = countCalls(method, owner, name, desc);
        if (actual != expected) {
            throw failure(context, "unexpected " + owner + "." + name + desc + " count in "
                    + method.name + method.desc + ": " + actual);
        }
    }

    private static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (isCall(insn, owner, name, desc)) count++;
        }
        return count;
    }

    private static int countCalls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) count += countCalls(method, owner, name, desc);
        return count;
    }

    private static boolean isCall(AbstractInsnNode insn, String owner,
                                  String name, String desc) {
        return insn instanceof MethodInsnNode call
                && owner.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc);
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("renderer-highlight-safe-regex failed for "
                + context.classPath() + ": " + detail);
    }
}

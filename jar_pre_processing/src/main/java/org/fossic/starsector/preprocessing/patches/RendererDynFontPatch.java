package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/** 精确代理 BitmapFont 与最终 glyph quad 物理像素吸附。 */
public final class RendererDynFontPatch implements JarPatch {
    static final String RENDERER_CLASS = "com/fs/graphics/A/oo" + "O".repeat(254);
    private static final String TARGET_CLASS_FILE = RENDERER_CLASS + ".class";
    private static final String FONT_CLASS = "com/fs/graphics/A/F";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String FONT_FIELD = "float.new";
    private static final String FONT_DESC = "L" + FONT_CLASS + ";";
    private static final String SIZE_FIELD = "float";
    private static final String SIZE_DESC = "F";
    private static final String FONT_SETTER = "o00000";
    private static final String FONT_SETTER_DESC = "(" + FONT_DESC + ")V";
    private static final String SIZE_SETTER = "Ô00000";
    private static final String SIZE_SETTER_DESC = "(F)V";
    private static final String FONT_NOMINAL_GETTER = "Õ00000";
    private static final String RAW_NOMINAL_GETTER = "$dynfontRawNominal";
    private static final String EXPANSION_FIELD = "oo0000";
    private static final String EXPANSION_SETTER = "int";
    private static final String EXPANSION_SETTER_DESC = "(I)V";
    private static final String RENDER_METHOD = "Õ00000";
    private static final String RENDER_DESC = "()V";
    private static final String IMMEDIATE_DRAW = "Õ00000";
    private static final String IMMEDIATE_DRAW_DESC = "(Z)V";
    private static final String CACHE_PREDICATE = "öO0000";
    private static final String CACHE_PREDICATE_DESC = "()Z";
    private static final String EXTRA_TRANSFORM_FIELD = "oÒ0000";
    private static final String EXTRA_TRANSFORM_DESC = "Ljava/nio/FloatBuffer;";
    private static final String DRAW_PASS = "o00000";
    private static final String DRAW_PASS_DESC = "(FFZ)V";
    private static final String GLYPH_QUAD = "o00000";
    private static final String GLYPH_QUAD_DESC = "(FFLcom/fs/graphics/A/oOOO;FZ)V";
    private static final String GLYPH_UNDERLINE_DESC = "(FFLcom/fs/graphics/A/oOOO;F)V";
    private static final String EXACT_SUFFIX = "$dynfontExact";

    private static final String RENDER_HOOK =
            "org/fossic/starsector/dynfont/DynFontRenderHooks";
    private static final String QUAD_HOOK =
            "org/fossic/starsector/dynfont/DynFontQuadHooks";

    @Override
    public String id() {
        return "renderer-dynfont-exact-proxy";
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
        requireOneField(classNode, FONT_FIELD, FONT_DESC, "font");
        requireOneField(classNode, SIZE_FIELD, SIZE_DESC, "requested size");
        requireOneField(classNode, EXTRA_TRANSFORM_FIELD, EXTRA_TRANSFORM_DESC,
                "optional text transform");
        MethodNode fontSetter = requireOneMethod(classNode, FONT_SETTER, FONT_SETTER_DESC);
        requireOneMethod(classNode, SIZE_SETTER, SIZE_SETTER_DESC);
        requireOneField(classNode, EXPANSION_FIELD, "I", "glyph expansion layers");
        MethodNode expansionSetter = requireOneMethod(
                classNode, EXPANSION_SETTER, EXPANSION_SETTER_DESC);
        MethodNode render = requireOneMethod(classNode, RENDER_METHOD, RENDER_DESC);
        MethodNode immediate = requireOneMethod(classNode, IMMEDIATE_DRAW, IMMEDIATE_DRAW_DESC);
        MethodNode cachePredicate = requireOneMethod(
                classNode, CACHE_PREDICATE, CACHE_PREDICATE_DESC);
        MethodNode drawPass = requireOneMethod(classNode, DRAW_PASS, DRAW_PASS_DESC);
        MethodNode glyphQuad = requireOneMethod(classNode, GLYPH_QUAD, GLYPH_QUAD_DESC);
        MethodNode underlineQuad = requireOneMethod(
                classNode, GLYPH_QUAD, GLYPH_UNDERLINE_DESC);

        requireFontWrite(fontSetter);
        requireFieldWrite(expansionSetter, EXPANSION_FIELD, "I", "expansion setter");
        requireFontTextureGetter(render);
        requireVertexCount(glyphQuad, 8);
        requireVertexCount(underlineQuad, 4);
        int rawNominalCalls = redirectInternalNominalReads(classNode, fontSetter);

        injectFontSetter(fontSetter);
        injectDisableExpansion(expansionSetter);
        injectRenderFontDefense(render);
        injectProxyCacheBypass(cachePredicate);
        int scopeEnds = injectScope(render);
        injectPassTranslation(drawPass);
        MethodNode exactGlyphQuad = cloneMethod(glyphQuad, GLYPH_QUAD + EXACT_SUFFIX);
        MethodNode exactUnderlineQuad = cloneMethod(
                underlineQuad, GLYPH_QUAD + EXACT_SUFFIX);
        MethodNode exactImmediate = cloneMethod(
                immediate, IMMEDIATE_DRAW + EXACT_SUFFIX);
        redirectExactGlyphCalls(exactImmediate);
        int vertices = replaceVertices(exactGlyphQuad) + replaceVertices(exactUnderlineQuad);
        injectExactDispatch(immediate);
        classNode.methods.add(exactGlyphQuad);
        classNode.methods.add(exactUnderlineQuad);
        classNode.methods.add(exactImmediate);

        int rawNominalVerified = countCalls(
                classNode, FONT_CLASS, RAW_NOMINAL_GETTER, "()I");
        int verified = countCalls(classNode, RENDER_HOOK, "resolveFont",
                "(Ljava/lang/Object;)Ljava/lang/Object;")
                + countCalls(classNode, RENDER_HOOK, "isProxyFont", "(Ljava/lang/Object;)Z")
                + countCalls(classNode, QUAD_HOOK, "begin",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V")
                + countCalls(classNode, QUAD_HOOK, "end", "()V")
                + countCalls(classNode, QUAD_HOOK, "translate", "(FF)V")
                + countCalls(classNode, QUAD_HOOK, "isActive", "()Z")
                + countCalls(classNode, QUAD_HOOK, "transform", "(FF)J")
                + rawNominalVerified;
        int expansionVerified = startsWithForceZero(expansionSetter) ? 1 : 0;
        int expected = 2 + 1 + 1 + scopeEnds + 1 + 1 + vertices + 1
                + rawNominalVerified;
        verified += expansionVerified;
        return PatchResult.of(id(), context.classPath(), expected, expected, verified,
                "exact proxy in font setter; internal raw nominal reads="
                        + rawNominalCalls + "; proxy display-list bypass; "
                        + vertices + " glyph vertices snapped in immediate scope");
    }

    private static void injectFontSetter(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_HOOK, "resolveFont",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, FONT_CLASS));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static void injectRenderFontDefense(MethodNode method) {
        // 防御某些原版/mod 路径绕过字体 setter 直接写字段。字号无需
        // 事后猜测来源：公开 nominal getter 已统一暴露逻辑 nominal。
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_HOOK, "resolveFont",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, FONT_CLASS));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    /**
     * renderer 的缩放/测量必须使用代理 raw nominal；字体 setter 的默认
     * requested size 故意保留公开 getter，因而取得逻辑 nominal。
     */
    private static int redirectInternalNominalReads(ClassNode node, MethodNode fontSetter) {
        int originalCalls = 0;
        int setterCalls = 0;
        int redirected = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && FONT_CLASS.equals(call.owner)
                        && FONT_NOMINAL_GETTER.equals(call.name)
                        && "()I".equals(call.desc)) {
                    originalCalls++;
                    if (method == fontSetter) {
                        setterCalls++;
                    } else {
                        call.name = RAW_NOMINAL_GETTER;
                        redirected++;
                    }
                }
            }
        }
        if (originalCalls != 12 || setterCalls != 1 || redirected != 11) {
            throw new PatchException("renderer nominal 调用结构漂移: total="
                    + originalCalls + ", setter=" + setterCalls
                    + ", redirected=" + redirected);
        }
        return redirected;
    }

    private static void injectDisableExpansion(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static void injectProxyCacheBypass(MethodNode method) {
        LabelNode original = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_HOOK, "isProxyFont",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(original);
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static int injectScope(MethodNode method) {
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS,
                EXTRA_TRANSFORM_FIELD, EXTRA_TRANSFORM_DESC));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK, "begin",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insert(begin);

        int ends = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) {
                method.instructions.insertBefore(insn, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, QUAD_HOOK, "end", "()V", false));
                ends++;
            }
            insn = next;
        }
        method.maxStack = Math.max(method.maxStack, 1);
        return ends;
    }

    private static void injectPassTranslation(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK, "translate",
                "(FF)V", false));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static int replaceVertices(MethodNode method) {
        int temp = method.maxLocals;
        method.maxLocals += 2; // long
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL11.equals(call.owner) && "glVertex2f".equals(call.name)
                    && "(FF)V".equals(call.desc)) {
                InsnList replacement = new InsnList();
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK,
                        "transform", "(FF)J", false));
                replacement.add(new VarInsnNode(Opcodes.LSTORE, temp));
                replacement.add(new VarInsnNode(Opcodes.LLOAD, temp));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK,
                        "unpackX", "(J)F", false));
                replacement.add(new VarInsnNode(Opcodes.LLOAD, temp));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK,
                        "unpackY", "(J)F", false));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11,
                        "glVertex2f", "(FF)V", false));
                method.instructions.insertBefore(call, replacement);
                method.instructions.remove(call);
                count++;
            }
            insn = next;
        }
        method.maxStack = Math.max(method.maxStack, 4);
        return count;
    }

    private static MethodNode cloneMethod(MethodNode original, String newName) {
        MethodNode clone = new MethodNode(Opcodes.ASM9, original.access, newName,
                original.desc, original.signature,
                original.exceptions.toArray(String[]::new));
        original.accept(clone);
        return clone;
    }

    private static void redirectExactGlyphCalls(MethodNode method) {
        int redirected = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && RENDERER_CLASS.equals(call.owner)
                    && GLYPH_QUAD.equals(call.name)
                    && (GLYPH_QUAD_DESC.equals(call.desc)
                        || GLYPH_UNDERLINE_DESC.equals(call.desc))) {
                call.name = GLYPH_QUAD + EXACT_SUFFIX;
                redirected++;
            }
        }
        if (redirected != 2) {
            throw new PatchException("即时 glyph 循环私有调用匹配数异常: " + redirected
                    + "（预期正文/下划线各1）");
        }
    }

    private static void injectExactDispatch(MethodNode original) {
        LabelNode useOriginal = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUAD_HOOK,
                "isActive", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, useOriginal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RENDERER_CLASS,
                IMMEDIATE_DRAW + EXACT_SUFFIX, IMMEDIATE_DRAW_DESC, false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(useOriginal);
        original.instructions.insert(code);
        original.maxStack = Math.max(original.maxStack, 2);
    }

    private static void requireOneField(ClassNode node, String name, String desc, String label) {
        List<FieldNode> fields = node.fields.stream()
                .filter(f -> name.equals(f.name) && desc.equals(f.desc)).toList();
        if (fields.size() != 1) {
            throw new PatchException(label + " 字段匹配数异常: " + fields.size());
        }
    }

    private static MethodNode requireOneMethod(ClassNode node, String name, String desc) {
        List<MethodNode> methods = node.methods.stream()
                .filter(m -> name.equals(m.name) && desc.equals(m.desc)).toList();
        if (methods.size() != 1) {
            throw new PatchException("方法 " + name + desc + " 匹配数异常: " + methods.size());
        }
        return methods.get(0);
    }

    private static void requireFontWrite(MethodNode method) {
        requireFieldWrite(method, FONT_FIELD, FONT_DESC, "font setter");
    }

    private static void requireFieldWrite(MethodNode method, String name, String desc,
                                          String label) {
        long count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && RENDERER_CLASS.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        if (count != 1) throw new PatchException(label + " 未唯一写入 " + name + ": " + count);
    }

    private static boolean startsWithForceZero(MethodNode method) {
        AbstractInsnNode first = method.instructions.getFirst();
        AbstractInsnNode second = first == null ? null : first.getNext();
        return first != null && first.getOpcode() == Opcodes.ICONST_0
                && second instanceof VarInsnNode store
                && store.getOpcode() == Opcodes.ISTORE && store.var == 1;
    }

    private static void requireFontTextureGetter(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && FONT_CLASS.equals(call.owner)
                    && "()Lcom/fs/graphics/Object;".equals(call.desc)) count++;
        }
        if (count != 1) throw new PatchException("render 纹理 getter 匹配数异常: " + count);
    }

    private static void requireVertexCount(MethodNode method, int expected) {
        int actual = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL11.equals(call.owner) && "glVertex2f".equals(call.name)
                    && "(FF)V".equals(call.desc)) actual++;
        }
        if (actual != expected) {
            throw new PatchException(method.name + method.desc + " glVertex2f=" + actual
                    + "（预期 " + expected + "）");
        }
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

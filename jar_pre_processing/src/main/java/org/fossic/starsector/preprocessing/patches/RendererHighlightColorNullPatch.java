package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** Prevents null per-range highlight colors from crashing the low-level text renderer. */
public final class RendererHighlightColorNullPatch implements JarPatch {
    static final String RENDERER_CLASS = "com/fs/graphics/A/oo" + "O".repeat(254);
    static final String INJECTED_METHOD = "fossic$normalizeHighlightColors";
    static final String NORMALIZE_COLORS_DESC =
            "([Ljava/awt/Color;Ljava/awt/Color;)[Ljava/awt/Color;";

    private static final String TARGET_CLASS_FILE = RENDERER_CLASS + ".class";
    private static final String COLOR_DESC = "Ljava/awt/Color;";
    private static final String COLOR_ARRAY_DESC = "[Ljava/awt/Color;";
    private static final String COLOR_ARRAY_SETTER_DESC = "(" + COLOR_ARRAY_DESC + ")V";
    private static final String DEFAULT_HIGHLIGHT_FIELD = "Ô00000";
    private static final String HIGHLIGHT_COLORS_FIELD = "ØÓ0000";
    private static final String TEMPLATE_METHOD = "normalizeHighlightColorsTemplate";

    @Override
    public String id() {
        return "renderer-highlight-color-null";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.LOCALIZATION;
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
        if (!RENDERER_CLASS.equals(classNode.name)) {
            throw failure(context, "unexpected class " + classNode.name);
        }

        MethodNode setter = requireHighlightColorSetter(classNode, context);
        requireNoInjectedMethod(classNode, context);
        MethodNode normalizer = copyNormalizerTemplate(context);

        classNode.methods.add(normalizer);
        injectNormalizationCall(setter);

        int injectedMethods = countMethods(
                classNode, INJECTED_METHOD, NORMALIZE_COLORS_DESC);
        int calls = countCalls(
                classNode, RENDERER_CLASS, INJECTED_METHOD, NORMALIZE_COLORS_DESC);
        int verified = injectedMethods == 1 && calls == 1 ? 1 : 0;
        return PatchResult.of(id(), context.classPath(), 1, 1, verified,
                "normalize null per-range colors to the renderer default without mutating callers");
    }

    /**
     * Bytecode template copied into the renderer as a private static method during preprocessing.
     * Keep this method self-contained: the installed game does not contain this patch class.
     */
    static Color[] normalizeHighlightColorsTemplate(Color[] colors, Color defaultColor) {
        if (colors == null) return null;

        int firstNull = -1;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == null) {
                firstNull = i;
                break;
            }
        }
        if (firstNull < 0) return colors;

        Color fallback = defaultColor != null ? defaultColor : Color.WHITE;
        Color[] normalized = colors.clone();
        for (int i = firstNull; i < normalized.length; i++) {
            if (normalized[i] == null) normalized[i] = fallback;
        }
        return normalized;
    }

    private static MethodNode requireHighlightColorSetter(
            ClassNode node, PatchContext context) {
        long defaultFields = node.fields.stream()
                .filter(field -> DEFAULT_HIGHLIGHT_FIELD.equals(field.name)
                        && COLOR_DESC.equals(field.desc))
                .count();
        long arrayFields = node.fields.stream()
                .filter(field -> HIGHLIGHT_COLORS_FIELD.equals(field.name)
                        && COLOR_ARRAY_DESC.equals(field.desc))
                .count();
        if (defaultFields != 1 || arrayFields != 1) {
            throw failure(context, "unexpected highlight color fields: default="
                    + defaultFields + ", array=" + arrayFields);
        }

        List<MethodNode> methods = node.methods.stream()
                .filter(method -> "o00000".equals(method.name)
                        && COLOR_ARRAY_SETTER_DESC.equals(method.desc))
                .toList();
        if (methods.size() != 1) {
            throw failure(context, "expected one highlight color-array setter, found "
                    + methods.size());
        }

        MethodNode setter = methods.get(0);
        int stores = 0;
        for (AbstractInsnNode insn : setter.instructions) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && RENDERER_CLASS.equals(field.owner)
                    && HIGHLIGHT_COLORS_FIELD.equals(field.name)
                    && COLOR_ARRAY_DESC.equals(field.desc)) {
                stores++;
            }
        }
        if (stores != 1) {
            throw failure(context, "expected one highlight color-array store, found " + stores);
        }
        return setter;
    }

    private static void requireNoInjectedMethod(ClassNode node, PatchContext context) {
        int existing = countMethods(node, INJECTED_METHOD, NORMALIZE_COLORS_DESC);
        if (existing != 0) {
            throw failure(context, "injected normalizer already exists: " + existing);
        }
    }

    private static MethodNode copyNormalizerTemplate(PatchContext context) {
        ClassNode source = new ClassNode();
        try (InputStream input = RendererHighlightColorNullPatch.class
                .getResourceAsStream("RendererHighlightColorNullPatch.class")) {
            if (input == null) {
                throw failure(context, "normalizer bytecode template is unavailable");
            }
            new ClassReader(input).accept(source, 0);
        } catch (IOException e) {
            throw new PatchException("renderer-highlight-color-null failed for "
                    + context.classPath() + ": unable to read normalizer template", e);
        }

        List<MethodNode> templates = source.methods.stream()
                .filter(method -> TEMPLATE_METHOD.equals(method.name)
                        && NORMALIZE_COLORS_DESC.equals(method.desc))
                .toList();
        if (templates.size() != 1) {
            throw failure(context, "expected one normalizer template, found "
                    + templates.size());
        }
        MethodNode template = templates.get(0);
        for (AbstractInsnNode insn : template.instructions) {
            if (insn instanceof MethodInsnNode call && source.name.equals(call.owner)) {
                throw failure(context, "normalizer template depends on the build-time patch class");
            }
            if (insn instanceof FieldInsnNode field && source.name.equals(field.owner)) {
                throw failure(context, "normalizer template depends on build-time patch state");
            }
        }

        MethodNode copy = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                INJECTED_METHOD,
                NORMALIZE_COLORS_DESC,
                null,
                null);
        template.accept(copy);
        return copy;
    }

    private static void injectNormalizationCall(MethodNode setter) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS,
                DEFAULT_HIGHLIGHT_FIELD, COLOR_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDERER_CLASS,
                INJECTED_METHOD, NORMALIZE_COLORS_DESC, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        setter.instructions.insert(code);
        setter.maxStack = Math.max(setter.maxStack, 2);
    }

    private static int countMethods(ClassNode node, String name, String desc) {
        return (int) node.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .count();
    }

    private static int countCalls(ClassNode node, String owner, String name, String desc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && name.equals(call.name)
                        && desc.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static PatchException failure(PatchContext context, String detail) {
        return new PatchException("renderer-highlight-color-null failed for "
                + context.classPath() + ": " + detail);
    }
}

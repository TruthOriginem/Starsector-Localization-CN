package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

/**
 * 动态字体：主渲染器 render 入口的高清字体切换钩子（SSO 式）。
 *
 * <p>目标类 {@code com.fs.graphics.A.ooOO…}（274 字符混淆名，位于
 * {@code fs.common_obf.jar}，0.98a-RC8 唯一超长名渲染器类，纯 ASCII）。
 * 其 render 入口 {@code Õ00000()V}（内含唯一一处字体纹理 bind，
 * display list 烘焙也发生在其方法体内）开头插入：
 *
 * <pre>
 * this.font = (F) DynFontRenderHooks.resolveFont(this.font);
 * this.requestedFontSize = DynFontRenderHooks.adjustSize(this.font, this.requestedFontSize);
 * </pre>
 *
 * <p>切换发生在纹理 bind 与一切字形取用/display list 烘焙之前，且决策自游戏内
 * 首帧起恒定（scale 启动定死）——烘焙进 display list 的 UV 与 bind 的纹理
 * 永远来自同一套字体，方案 A 的碎块问题在结构上不存在。
 *
 * <p>字段锚点（字节码实证）：font 字段 {@code float.new}（desc
 * {@code Lcom/fs/graphics/A/F;}）、requestedFontSize 字段 {@code float}（desc
 * {@code F}，唯一赋值点为 setFontSize {@code Ô00000(F)V}）。均按
 * 名称+描述符双重校验唯一性，混淆布局变化时 fail loud。
 *
 * <p>注入净栈变化 0、峰值栈需求 3；游戏以 {@code -noverify} 启动，无需补帧。
 */
public final class RendererDynFontPatch implements JarPatch {
    /** 渲染器内部名：com/fs/graphics/A/oo + 254 个大写 O（274 字符）。 */
    static final String RENDERER_CLASS = "com/fs/graphics/A/oo" + "O".repeat(254);
    private static final String TARGET_CLASS_FILE = RENDERER_CLASS + ".class";
    private static final String RENDER_METHOD = "Õ00000";
    private static final String RENDER_DESC = "()V";
    private static final String FONT_FIELD = "float.new";
    private static final String FONT_DESC = "Lcom/fs/graphics/A/F;";
    private static final String SIZE_FIELD = "float";
    private static final String SIZE_DESC = "F";
    /** 测试开关：原版为 Orbitron 20/24 配置的额外字形四边形层数。 */
    private static final String EXPANSION_LAYERS_FIELD = "oo0000";
    private static final String EXPANSION_LAYERS_DESC = "I";
    private static final String EXPANSION_SETTER_METHOD = "int";
    private static final String EXPANSION_SETTER_DESC = "(I)V";
    private static final String HOOK_OWNER = "org/fossic/starsector/dynfont/DynFontRenderHooks";
    private static final String RESOLVE_METHOD = "resolveFont";
    private static final String RESOLVE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String ADJUST_METHOD = "adjustSize";
    private static final String ADJUST_DESC = "(Ljava/lang/Object;F)F";

    @Override
    public String id() {
        return "renderer-dynfont-hd-swap";
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
        List<FieldNode> fontFields = classNode.fields.stream()
                .filter(f -> FONT_FIELD.equals(f.name) && FONT_DESC.equals(f.desc))
                .toList();
        if (fontFields.size() != 1) {
            throw new PatchException("font 字段（" + FONT_FIELD + " : " + FONT_DESC
                    + "）匹配数异常: " + fontFields.size() + "（预期 1，混淆布局可能已变化）");
        }
        List<FieldNode> sizeFields = classNode.fields.stream()
                .filter(f -> SIZE_FIELD.equals(f.name) && SIZE_DESC.equals(f.desc))
                .toList();
        if (sizeFields.size() != 1) {
            throw new PatchException("requestedFontSize 字段（" + SIZE_FIELD + " : F"
                    + "）匹配数异常: " + sizeFields.size() + "（预期 1）");
        }
        List<FieldNode> expansionFields = classNode.fields.stream()
                .filter(f -> EXPANSION_LAYERS_FIELD.equals(f.name)
                        && EXPANSION_LAYERS_DESC.equals(f.desc))
                .toList();
        if (expansionFields.size() != 1) {
            throw new PatchException("字形四边形膨胀层数字段（" + EXPANSION_LAYERS_FIELD
                    + " : I）匹配数异常: " + expansionFields.size()
                    + "（预期 1，混淆布局可能已变化）");
        }
        List<MethodNode> expansionSetters = classNode.methods.stream()
                .filter(m -> EXPANSION_SETTER_METHOD.equals(m.name)
                        && EXPANSION_SETTER_DESC.equals(m.desc))
                .toList();
        if (expansionSetters.size() != 1) {
            throw new PatchException("字形四边形膨胀层数 setter（"
                    + EXPANSION_SETTER_METHOD + EXPANSION_SETTER_DESC
                    + "）匹配数异常: " + expansionSetters.size() + "（预期 1）");
        }
        MethodNode expansionSetter = expansionSetters.get(0);
        boolean setterWritesExpansionField = false;
        for (var insn = expansionSetter.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && RENDERER_CLASS.equals(field.owner)
                    && EXPANSION_LAYERS_FIELD.equals(field.name)
                    && EXPANSION_LAYERS_DESC.equals(field.desc)) {
                setterWritesExpansionField = true;
                break;
            }
        }
        if (!setterWritesExpansionField) {
            throw new PatchException("膨胀层数 setter 结构校验失败：未找到目标 PUTFIELD");
        }
        List<MethodNode> renders = classNode.methods.stream()
                .filter(m -> RENDER_METHOD.equals(m.name) && RENDER_DESC.equals(m.desc))
                .toList();
        if (renders.size() != 1) {
            throw new PatchException("render 入口（" + RENDER_METHOD + RENDER_DESC
                    + "）匹配数异常: " + renders.size() + "（预期 1）");
        }
        MethodNode render = renders.get(0);
        // 结构校验：render 入口内含唯一一处字体纹理 bind（font.texture().bind()），
        // 即必然存在 INVOKEVIRTUAL A/F → ()Lcom/fs/graphics/Object;（纹理 getter）
        boolean hasTextureGetter = false;
        for (var insn = render.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "com/fs/graphics/A/F".equals(call.owner)
                    && "()Lcom/fs/graphics/Object;".equals(call.desc)) {
                hasTextureGetter = true;
                break;
            }
        }
        if (!hasTextureGetter) {
            throw new PatchException("render 入口结构校验失败：未找到字体纹理 getter 调用"
                    + "（INVOKEVIRTUAL com/fs/graphics/A/F ()Lcom/fs/graphics/Object;），"
                    + "混淆布局可能已变化");
        }

        // 在配置膨胀层数的统一入口强制改为 0。此时透明度尚未按层数派生，之后所有
        // setAlpha 都会自然按单层正文计算，避免渲染期清零造成渐隐补偿沿用旧值。
        InsnList disableExpansion = new InsnList();
        disableExpansion.add(new InsnNode(Opcodes.ICONST_0));
        disableExpansion.add(new VarInsnNode(Opcodes.ISTORE, 1));
        expansionSetter.instructions.insert(disableExpansion);
        expansionSetter.maxStack = Math.max(expansionSetter.maxStack, 1);

        InsnList prelude = new InsnList();
        // this.font = (F) DynFontRenderHooks.resolveFont(this.font)
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        prelude.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER,
                RESOLVE_METHOD, RESOLVE_DESC, false));
        prelude.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(FONT_DESC).getInternalName()));
        prelude.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        // this.requestedFontSize = DynFontRenderHooks.adjustSize(this.font, this.requestedFontSize)
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, FONT_FIELD, FONT_DESC));
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, SIZE_FIELD, SIZE_DESC));
        prelude.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER,
                ADJUST_METHOD, ADJUST_DESC, false));
        prelude.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER_CLASS, SIZE_FIELD, SIZE_DESC));
        render.instructions.insert(prelude);
        render.maxStack = Math.max(render.maxStack, 3);

        var setterFirst = expansionSetter.instructions.getFirst();
        var setterSecond = setterFirst == null ? null : AsmUtil.nextReal(setterFirst);
        int expansionDisableVerified = AsmUtil.isIntInsn(setterFirst, 0)
                && setterSecond instanceof VarInsnNode store
                && store.getOpcode() == Opcodes.ISTORE
                && store.var == 1 ? 1 : 0;
        int verified = AsmUtil.countMethodCall(classNode, HOOK_OWNER, RESOLVE_METHOD, RESOLVE_DESC)
                + AsmUtil.countMethodCall(classNode, HOOK_OWNER, ADJUST_METHOD, ADJUST_DESC)
                + expansionDisableVerified;
        return PatchResult.of(id(), context.classPath(), 3, 3, verified,
                "disable glyph expansion at its setter and inject"
                        + " DynFontRenderHooks.resolveFont/adjustSize");
    }
}

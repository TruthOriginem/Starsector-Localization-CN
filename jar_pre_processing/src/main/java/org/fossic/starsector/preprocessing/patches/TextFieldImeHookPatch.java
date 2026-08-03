package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * 中文输入法支持：向游戏文本框实现类注入每帧输入法处理钩子。
 *
 * <p>目标类 {@code com/fs/starfarer/ui/new}（{@code TextFieldAPI} 的实现）的
 * {@code processInputImpl} 方法在每帧对每个文本框调用。在其方法体开头插入
 * {@code ImeHooks.onProcessInput(this)}，由运行时模块跟踪焦点、注入输入法上屏
 * 文本并定位候选窗。
 *
 * <p>被调用的 {@code org.fossic.starsector.ime.ImeHooks} 及其依赖类由预处理的
 * 类注入步骤（{@link org.fossic.starsector.preprocessing.ImeRuntimeInjector}）
 * 额外写入同一 jar，因此运行时可解析。
 *
 * <p>注入只压入 {@code this} 并调用一个静态 void 方法，净栈变化为 0，峰值栈需求
 * 为 1，不影响原方法控制流。
 */
public final class TextFieldImeHookPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/new.class";
    private static final String METHOD_NAME = "processInputImpl";
    private static final String HOOKS_OWNER = "org/fossic/starsector/ime/ImeHooks";
    private static final String HOOK_METHOD = "onProcessInput";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

    @Override
    public String id() {
        return "textfield-ime-hook";
    }

    @Override
    public String targetJar() {
        return JarWorkspace.OBF_JAR;
    }

    @Override
    public Set<String> targetClasses() {
        return Set.of(TARGET_CLASS);
    }

    @Override
    public PatchResult applyAndVerify(ClassNode classNode, PatchContext context) {
        int applied = 0;
        for (MethodNode method : classNode.methods) {
            if (!METHOD_NAME.equals(method.name)) {
                continue;
            }
            InsnList prelude = new InsnList();
            prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prelude.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_OWNER, HOOK_METHOD, HOOK_DESC, false));
            method.instructions.insert(prelude);
            method.maxStack = Math.max(method.maxStack, 1);
            applied++;
        }

        int verified = AsmUtil.countMethodCall(classNode, HOOKS_OWNER, HOOK_METHOD, HOOK_DESC);
        return PatchResult.of(id(), context.classPath(), 1, applied, verified,
                "inject ImeHooks.onProcessInput at start of processInputImpl");
    }
}

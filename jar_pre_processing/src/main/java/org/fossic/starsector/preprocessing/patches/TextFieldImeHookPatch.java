package org.fossic.starsector.preprocessing.patches;

import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarPatch;
import org.fossic.starsector.preprocessing.PatchGroup;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.fossic.starsector.preprocessing.PatchException;
import org.fossic.starsector.preprocessing.PatchResult;
import org.objectweb.asm.tree.AbstractInsnNode;
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
 * 文本并定位候选窗。另在 {@code releaseFocus} 的正常出口插入
 * {@code ImeHooks.onFocusReleased(this)}，确保同一轮输入处理中释放并移除文本框时
 * 也能立即解除原生输入法焦点。
 *
 * <p>被调用的 {@code org.fossic.starsector.ime.ImeHooks} 及其依赖类由预处理的
 * 类注入步骤（{@link org.fossic.starsector.preprocessing.RuntimeClassInjector}）
 * 额外写入同一 jar，因此运行时可解析。
 *
 * <p>三处注入均只压入 {@code this} 并调用一个静态 void 方法，净栈变化为 0，
 * 峰值栈需求为 1，不影响原方法控制流。
 */
public final class TextFieldImeHookPatch implements JarPatch {
    private static final String TARGET_CLASS = "com/fs/starfarer/ui/new.class";
    private static final String PROCESS_METHOD = "processInputImpl";
    private static final String PROCESS_DESC = "(Lcom/fs/starfarer/util/A/new;)V";
    private static final String RELEASE_METHOD = "releaseFocus";
    private static final String RELEASE_DESC = "(Lcom/fs/starfarer/util/A/C;)V";
    private static final String GRAB_METHOD = "grabFocus";
    private static final String GRAB_DESC = "(Z)V";
    private static final String HOOKS_OWNER = "org/fossic/starsector/ime/ImeHooks";
    private static final String PROCESS_HOOK = "onProcessInput";
    private static final String RELEASE_HOOK = "onFocusReleased";
    private static final String GAIN_HOOK = "onTextFieldFocusGained";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

    @Override
    public String id() {
        return "textfield-ime-hook";
    }

    @Override
    public PatchGroup group() {
        return PatchGroup.IME;
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
        MethodNode process = null;
        MethodNode release = null;
        MethodNode grab = null;
        for (MethodNode method : classNode.methods) {
            if (PROCESS_METHOD.equals(method.name) && PROCESS_DESC.equals(method.desc)) {
                if (process != null) {
                    throw new PatchException(id() + " found duplicate " + PROCESS_METHOD + PROCESS_DESC);
                }
                process = method;
            } else if (RELEASE_METHOD.equals(method.name) && RELEASE_DESC.equals(method.desc)) {
                if (release != null) {
                    throw new PatchException(id() + " found duplicate " + RELEASE_METHOD + RELEASE_DESC);
                }
                release = method;
            } else if (GRAB_METHOD.equals(method.name) && GRAB_DESC.equals(method.desc)) {
                if (grab != null) {
                    throw new PatchException(id() + " found duplicate " + GRAB_METHOD + GRAB_DESC);
                }
                grab = method;
            }
        }
        if (process == null || release == null || grab == null) {
            throw new PatchException(id() + " requires exactly one " + PROCESS_METHOD + PROCESS_DESC
                    + ", one " + RELEASE_METHOD + RELEASE_DESC
                    + " and one " + GRAB_METHOD + GRAB_DESC);
        }

        AbstractInsnNode releaseReturn = soleReturn(release, RELEASE_METHOD, RELEASE_DESC);
        AbstractInsnNode grabReturn = soleReturn(grab, GRAB_METHOD, GRAB_DESC);

        InsnList prelude = hook(PROCESS_HOOK);
        process.instructions.insert(prelude);
        process.maxStack = Math.max(process.maxStack, 1);

        release.instructions.insertBefore(releaseReturn, hook(RELEASE_HOOK));
        release.maxStack = Math.max(release.maxStack, 1);
        grab.instructions.insertBefore(grabReturn, hook(GAIN_HOOK));
        grab.maxStack = Math.max(grab.maxStack, 1);

        int verified = AsmUtil.countMethodCall(classNode, HOOKS_OWNER, PROCESS_HOOK, HOOK_DESC)
                + AsmUtil.countMethodCall(classNode, HOOKS_OWNER, RELEASE_HOOK, HOOK_DESC)
                + AsmUtil.countMethodCall(classNode, HOOKS_OWNER, GAIN_HOOK, HOOK_DESC);
        return PatchResult.of(id(), context.classPath(), 3, 3, verified,
                "inject IME process, focus-gain and focus-release hooks");
    }

    private AbstractInsnNode soleReturn(MethodNode method, String name, String descriptor) {
        AbstractInsnNode result = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                if (result != null) {
                    throw new PatchException(id() + " expected one normal return in " + name + descriptor);
                }
                result = insn;
            }
        }
        if (result == null) {
            throw new PatchException(id() + " found no normal return in " + name + descriptor);
        }
        return result;
    }

    private static InsnList hook(String method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_OWNER, method, HOOK_DESC, false));
        return code;
    }
}

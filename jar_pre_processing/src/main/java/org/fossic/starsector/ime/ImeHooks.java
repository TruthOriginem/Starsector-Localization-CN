package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

/**
 * ASM 注入代码调用的静态入口。
 *
 * <p>由预处理阶段注入到游戏文本框实现类 {@code com.fs.starfarer.ui.new}
 * （即 {@code TextFieldAPI} 的实现）的 {@code processInputImpl} 方法开头。
 *
 * <p>所有方法必须绝对不抛异常：入口位于游戏 UI 热路径，任何异常都可能导致
 * 游戏崩溃或输入失灵。输入法是增强功能，出错时应静默降级。因此每个入口都用
 * {@code try/catch(Throwable)} 完整包裹。
 */
public final class ImeHooks {
    /** 在任何 Controller/JNI 引用前读取，false 时不会触发 {@link ImeNatives} 初始化。 */
    private static final boolean runtimeEnabled = ImeRuntimeConfig.enabled();

    /** 首次不可恢复异常后熔断；热路径后续只读一次 volatile 后立即返回。 */
    private static volatile boolean broken;

    private ImeHooks() {
    }

    /** 游戏全局输入帧入口：尽早初始化，并为后续焦点状态机提供稳定帧边界。 */
    public static void onGlobalInputFrame(Object focusOwner) {
        if (!runtimeEnabled) {
            return;
        }
        if (broken) {
            advanceEmergencyCleanup(ImeController.get());
            return;
        }
        try {
            ImeController.get().onGlobalInputFrame(focusOwner);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** 游戏全局焦点栈发生底层修改后的即时通知。 */
    public static void onGlobalFocusChanged(Object focusOwner) {
        if (!runtimeEnabled || broken) {
            return;
        }
        try {
            ImeController.get().onGlobalFocusChanged(focusOwner);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * 每帧对每个文本框调用。参数为 {@code com.fs.starfarer.ui.new} 实例
     * （实现 {@link TextFieldAPI}）。
     */
    public static void onProcessInput(Object textField) {
        if (!runtimeEnabled || broken) {
            return;
        }
        try {
            if (textField instanceof TextFieldAPI field) {
                ImeController.get().onProcessInput(field);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** 文本框释放焦点后调用，防止同帧移除文本框时原生 IME 继续截获游戏按键。 */
    public static void onFocusReleased(Object textField) {
        if (!runtimeEnabled || broken) {
            return;
        }
        try {
            if (textField instanceof TextFieldAPI field) {
                ImeController.get().onFocusReleased(field);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** 已验证文本框在 {@code grabFocus(boolean)} 正常返回后取得焦点。 */
    public static void onTextFieldFocusGained(Object textField) {
        if (!runtimeEnabled || broken) {
            return;
        }
        try {
            if (textField instanceof TextFieldAPI field) {
                ImeController.get().onTextFieldFocusGained(field);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void fail(Throwable error) {
        // 先熔断，防止清理或日志路径重入钩子。
        broken = true;
        try {
            ImeController.get().disableAfterFailure();
        } catch (Throwable ignored) {
            // JNI 故障时清理也可能失败；绝不能让增强功能影响游戏输入处理。
        }
        try {
            ImeLog.error("输入法钩子异常，已永久停用本次会话的输入法支持", error);
        } catch (Throwable ignored) {
            // 连日志都失败时彻底静默。
        }
    }

    /** 熔断后只推进不接触游戏对象的原生解绑；任何异常均保持静默。 */
    static void advanceEmergencyCleanup(ImeController controller) {
        try {
            controller.onEmergencyCleanupFrame();
        } catch (Throwable ignored) {
            // 紧急清理自身失败时，下一全局帧仍会继续尝试，绝不回到正常业务路径。
        }
    }
}

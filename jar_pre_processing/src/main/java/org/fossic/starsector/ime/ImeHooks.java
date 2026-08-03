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
    /** 首次异常记录一次日志后置位，此后完全静默（避免热路径每帧刷错误日志）。 */
    private static volatile boolean errorLogged;

    private ImeHooks() {
    }

    /**
     * 每帧对每个文本框调用。参数为 {@code com.fs.starfarer.ui.new} 实例
     * （实现 {@link TextFieldAPI}）。
     */
    public static void onProcessInput(Object textField) {
        try {
            if (textField instanceof TextFieldAPI field) {
                ImeController.get().onProcessInput(field);
            }
        } catch (Throwable t) {
            // 静默降级，绝不影响游戏输入处理；仅首次异常留一条日志便于诊断。
            if (!errorLogged) {
                errorLogged = true;
                try {
                    ImeLog.error("输入法钩子异常（仅记录一次，此后静默降级）", t);
                } catch (Throwable ignored) {
                    // 连日志都失败时彻底静默。
                }
            }
        }
    }
}

package org.fossic.starsector.ime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 输入法支持的核心协调器（单例）。
 *
 * <p>职责：
 * <ul>
 *   <li>延迟初始化：首次被调用时反射获取 LWJGL 窗口句柄（HWND）并接管窗口过程；</li>
 *   <li>焦点跟踪：每帧根据文本框 {@code hasFocus()} 收敛输入法启用状态；</li>
 *   <li>文本注入：轮询原生上屏队列，逐字符写入当前聚焦的文本框；</li>
 *   <li>候选窗定位：把系统候选/组合窗定位到输入框光标处。</li>
 * </ul>
 *
 * <p>本类仅由游戏主线程（UI 输入处理）调用，原生 WndProc 亦在同线程被消息泵触发，
 * 因此无需复杂同步。所有对外入口在 {@link ImeHooks} 中以异常隔离方式调用。
 */
final class ImeController {
    private static final ImeController INSTANCE = new ImeController();

    private volatile boolean initAttempted;
    private volatile boolean available;
    private volatile long ctx;

    private WeakReference<TextFieldAPI> focusedField;

    // 候选窗定位去重：坐标未变化时跳过原生调用（每帧 4+ 次系统调用）与日志
    private int lastSpotX = Integer.MIN_VALUE;
    private int lastSpotY = Integer.MIN_VALUE;
    private int lastSpotHeight = Integer.MIN_VALUE;
    private boolean spotBroken;

    private ImeController() {
    }

    static ImeController get() {
        return INSTANCE;
    }

    /** 每帧对每个文本框调用（注入点：ui.new.processInputImpl 开头）。 */
    void onProcessInput(TextFieldAPI field) {
        if (field == null) {
            return;
        }
        if (!initAttempted) {
            ensureInit();
        }
        if (!available) {
            return;
        }

        boolean hasFocus = field.hasFocus();
        TextFieldAPI current = focusedField != null ? focusedField.get() : null;

        if (hasFocus) {
            if (current != field) {
                // 先解除旧文本框并清空其组合/上屏队列，再把同一原生上下文交给新文本框。
                // 即使 WeakReference 已失效也执行 false，避免旧文本进入新文本框。
                ImeNatives.nativeSetFocused(ctx, false);
                focusedField = new WeakReference<>(field);
                ImeNatives.nativeSetFocused(ctx, true);
            }
            drainCommittedText(field);
            updateSpot(field);
        } else if (current == field || (current == null && focusedField != null)) {
            clearFocus();
        }
    }

    /** {@code releaseFocus} 正常返回后调用，覆盖文本框同帧关闭、此后不再 advance 的情况。 */
    void onFocusReleased(TextFieldAPI field) {
        if (!available || field == null) {
            return;
        }
        TextFieldAPI current = focusedField != null ? focusedField.get() : null;
        if (current == null || current == field) {
            clearFocus();
        }
    }

    /** 钩子发生不可恢复错误时尽力解除 IME，随后彻底停用本模块。 */
    void disableAfterFailure() {
        long active = ctx;
        focusedField = null;
        available = false;
        ctx = 0L;
        resetSpot();
        if (active != 0L) {
            try {
                ImeNatives.nativeSetFocused(active, false);
            } catch (Throwable ignored) {
                // 原始错误可能正是 JNI 故障，清理绝不能覆盖它或再次传播。
            }
        }
    }

    private void clearFocus() {
        focusedField = null;
        resetSpot();
        long active = ctx;
        if (active != 0L) {
            ImeNatives.nativeSetFocused(active, false);
        }
    }

    private void resetSpot() {
        lastSpotX = Integer.MIN_VALUE;
        lastSpotY = Integer.MIN_VALUE;
        lastSpotHeight = Integer.MIN_VALUE;
    }

    private synchronized void ensureInit() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;

        if (!ImeNatives.isLoaded()) {
            return;
        }
        long hwnd = resolveHwnd();
        if (hwnd == 0L) {
            return;
        }
        long attached = ImeNatives.nativeAttach(hwnd);
        if (attached == 0L) {
            ImeLog.error("接管窗口过程失败：" + ImeNatives.nativeLastError(), null);
            return;
        }
        ctx = attached;
        available = true;
    }

    /** 反射读取 org.lwjgl.opengl.WindowsDisplay 实例的 hwnd 字段。 */
    private long resolveHwnd() {
        try {
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            Method getImplementation = displayClass.getDeclaredMethod("getImplementation");
            getImplementation.setAccessible(true);
            Object implementation = getImplementation.invoke(null);
            if (implementation == null) {
                ImeLog.error("Display.getImplementation() 返回 null", null);
                return 0L;
            }
            Field hwndField = implementation.getClass().getDeclaredField("hwnd");
            hwndField.setAccessible(true);
            long hwnd = hwndField.getLong(implementation);
            if (hwnd == 0L) {
                ImeLog.error("窗口实现返回了无效的 HWND", null);
            }
            return hwnd;
        } catch (Throwable t) {
            ImeLog.error("反射获取 HWND 失败", t);
            return 0L;
        }
    }

    private void drainCommittedText(TextFieldAPI field) {
        String text;
        while ((text = ImeNatives.nativePoll(ctx)) != null) {
            if (text.isEmpty()) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20) {
                    continue;
                }
                // appendCharIfPossible 返回 false 表示输入框拒绝该字符（超出长度/宽度
                // 限制或字体无字形），尊重游戏自身的约束与反馈（提示音），不强行写入。
                field.appendCharIfPossible(c);
            }
        }
    }

    /**
     * 把候选/组合窗定位到输入框光标处。GL 坐标（原点左下）转换为窗口坐标（原点左上）。
     *
     * <p>定位基于文本 label（{@code getTextLabelAPI()}）自身的 position，而非外层文本框的
     * position。文本 label 经 {@code autoSize} 后其 position 即文本的实际渲染框，位置由布局
     * 系统按对齐方式（左对齐 {@code inLMid} / 居中 {@code inMid}）设置，因此其右边缘即光标
     * 位置——对左对齐与居中对齐（如舰船命名框）均正确。用外层文本框的 position 会假设
     * 左对齐，导致居中框错位。
     */
    private void updateSpot(TextFieldAPI field) {
        if (spotBroken) {
            return;
        }
        try {
            LabelAPI label = field.getTextLabelAPI();
            PositionAPI textPos = label != null ? label.getPosition() : null;
            PositionAPI fieldPos = field.getPosition();
            PositionAPI basis = textPos != null ? textPos : fieldPos;
            if (basis == null) {
                return;
            }

            float caretX = basis.getX() + basis.getWidth();
            float caretBottom = basis.getY();
            float height = basis.getHeight();
            if (height <= 0f && fieldPos != null) {
                height = fieldPos.getHeight();
            }

            // 文本框 position 是游戏 UI 的逻辑坐标；候选窗需要客户区物理像素坐标。
            // UI 缩放非 100% 时二者不同，需乘以 缩放倍数 = 物理高 / 逻辑高
            // （自算比值，不依赖 getScreenScaleMult 的方向约定）。
            SettingsAPI settings = Global.getSettings();
            float logicalHeight = settings.getScreenHeight();
            float pixelHeight = settings.getScreenHeightPixels();
            if (logicalHeight <= 0f || pixelHeight <= 0f) {
                return;
            }
            float scale = pixelHeight / logicalHeight;

            int winX = Math.round(caretX * scale);
            int winY = Math.round(pixelHeight - (caretBottom + height) * scale);
            int winHeight = Math.round(height * scale);

            // 坐标未变化时跳过原生调用（否则每帧产生 4+ 次 Imm* 系统调用）。
            if (winX == lastSpotX && winY == lastSpotY && winHeight == lastSpotHeight) {
                return;
            }
            lastSpotX = winX;
            lastSpotY = winY;
            lastSpotHeight = winHeight;
            ImeNatives.nativeSetSpot(ctx, winX, winY, winHeight);
        } catch (Throwable t) {
            spotBroken = true;
            ImeLog.error("更新候选窗位置失败", t);
        }
    }
}

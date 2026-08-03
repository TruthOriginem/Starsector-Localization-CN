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
                focusedField = new WeakReference<>(field);
                ImeNatives.nativeSetFocused(ctx, true);
                ImeLog.debug("文本框获得焦点，启用输入法");
            }
            drainCommittedText(field);
            updateSpot(field);
        } else if (current == field) {
            focusedField = null;
            ImeNatives.nativeSetFocused(ctx, false);
            ImeLog.debug("文本框失去焦点，解除输入法");
        }
    }

    private synchronized void ensureInit() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;

        if (!ImeNatives.isLoaded()) {
            ImeLog.info("原生库未加载，输入法支持不可用");
            return;
        }
        long hwnd = resolveHwnd();
        if (hwnd == 0L) {
            ImeLog.info("未能获取窗口句柄（HWND），输入法支持不可用");
            return;
        }
        long attached = ImeNatives.nativeAttach(hwnd);
        if (attached == 0L) {
            ImeLog.error("接管窗口过程失败：" + ImeNatives.nativeLastError(), null);
            return;
        }
        ctx = attached;
        available = true;
        ImeLog.info("输入法支持已启用，hwnd=0x" + Long.toHexString(hwnd) + " ctx=0x" + Long.toHexString(ctx));
        registerShutdownHook();
    }

    /** 反射读取 org.lwjgl.opengl.WindowsDisplay 实例的 hwnd 字段。 */
    private long resolveHwnd() {
        try {
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            Method getImplementation = displayClass.getDeclaredMethod("getImplementation");
            getImplementation.setAccessible(true);
            Object implementation = getImplementation.invoke(null);
            if (implementation == null) {
                ImeLog.info("Display.getImplementation() 返回 null");
                return 0L;
            }
            Field hwndField = implementation.getClass().getDeclaredField("hwnd");
            hwndField.setAccessible(true);
            long hwnd = hwndField.getLong(implementation);
            ImeLog.info("获取到窗口实现 " + implementation.getClass().getName()
                    + " hwnd=0x" + Long.toHexString(hwnd));
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
            ImeLog.debug("注入上屏文本：" + text);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20) {
                    continue;
                }
                // appendCharIfPossible 返回 false 表示输入框拒绝该字符（超出长度/宽度
                // 限制或字体无字形），尊重游戏自身的约束与反馈（提示音），不强行写入。
                if (!field.appendCharIfPossible(c)) {
                    ImeLog.debug("字符被输入框拒绝，已丢弃：U+"
                            + Integer.toHexString(c).toUpperCase());
                }
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
            ImeLog.debug("候选窗定位 x=" + winX + " y=" + winY + " h=" + winHeight
                    + "（文本框x=" + (fieldPos != null ? Math.round(fieldPos.getX()) : -1)
                    + " 文本x=" + Math.round(basis.getX())
                    + " 文本宽=" + Math.round(basis.getWidth()) + "）");
        } catch (Throwable t) {
            ImeLog.error("更新候选窗位置失败", t);
        }
    }

    private void registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long c = ctx;
                if (c != 0L) {
                    ImeNatives.nativeDetach(c);
                }
            }, "ss-ime-detach"));
        } catch (Throwable ignored) {
            // 关机钩子注册失败无关紧要，进程退出时 OS 会恢复窗口过程。
        }
    }
}

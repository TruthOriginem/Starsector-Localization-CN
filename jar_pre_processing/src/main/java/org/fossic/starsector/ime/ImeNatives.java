package org.fossic.starsector.ime;

/**
 * {@code ssime.dll} 的 JNI 绑定。
 *
 * <p>原生库通过 {@code java.library.path}（游戏 vmparams 中配置为 {@code native\windows}）
 * 加载。加载失败时 {@link #isLoaded()} 返回 {@code false}，上层据此静默降级，
 * 不影响游戏其余功能。
 *
 * <p>约定：{@code ctx} 为原生上下文句柄，0 表示无效。
 */
final class ImeNatives {
    private static final boolean LOADED = load();

    private ImeNatives() {
    }

    private static boolean load() {
        try {
            System.loadLibrary("ssime");
        } catch (Throwable t) {
            ImeLog.error("加载 ssime 原生库失败（java.library.path 中应存在 ssime.dll）", t);
            return false;
        }
        try {
            boolean ok = nativeInit(nativeLogPath());
            ImeLog.info("ssime 原生库已加载，nativeInit=" + ok);
            return ok;
        } catch (Throwable t) {
            ImeLog.error("ssime 原生库 nativeInit 调用失败", t);
            return false;
        }
    }

    /**
     * 原生日志路径：游戏的 logs 目录（与 {@code starsector.log} 同级）。
     *
     * <p>取 {@code com.fs.starfarer.settings.paths.logs} —— 游戏自身就用它定位
     * starsector.log。早先原生侧用相对文件名，依赖进程 CWD 恰好是 starsector-core，
     * 启动方式一变日志就会落到别处，且 fopen 失败是静默的。
     */
    private static String nativeLogPath() {
        try {
            String dir = System.getProperty("com.fs.starfarer.settings.paths.logs", ".");
            return java.nio.file.Path.of(dir).toAbsolutePath().normalize()
                    .resolve("starsector_ime_native.log").toString();
        } catch (Throwable t) {
            return "";  // 空串 → 原生侧退回相对路径（旧行为）
        }
    }

    static boolean isLoaded() {
        return LOADED;
    }

    /** @param logPath 日志完整路径；空串表示沿用 CWD 下的相对路径 */
    static native boolean nativeInit(String logPath);

    /** 子类化窗口过程，返回原生上下文句柄；失败返回 0。 */
    static native long nativeAttach(long hwnd);

    /** 恢复窗口过程并释放上下文。 */
    static native void nativeDetach(long ctx);

    /** 设置输入框焦点状态：true 启用输入法，false 解除以免按键被输入法截获。 */
    static native void nativeSetFocused(long ctx, boolean focused);

    /** 设置候选/组合窗定位点（客户区物理像素坐标，y 向下）。 */
    static native void nativeSetSpot(long ctx, int x, int y, int height);

    /** 取出一段已上屏文本；队列为空返回 null。 */
    static native String nativePoll(long ctx);

    /** 当前正在组合的预编辑串；无则返回空串。 */
    static native String nativePreedit(long ctx);

    /** 当前是否处于输入法组合态。 */
    static native boolean nativeComposing(long ctx);

    /** 最近一次原生错误信息。 */
    static native String nativeLastError();
}

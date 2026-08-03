package org.fossic.starsector.dynfont;

/**
 * {@code ss_dyn_font.dll} 的 JNI 绑定。
 *
 * <p>dll 位于游戏 {@code java.library.path}（{@code native\windows}，与 ssime.dll
 * 同目录），用 {@link System#loadLibrary} 按库名加载。加载失败时 {@link #load}
 * 返回 {@code false}，上层据此禁用动态字体、回退原版位图字体，不影响游戏
 * 其余功能。
 */
final class DynFontNatives {
    private static volatile boolean loaded;

    private DynFontNatives() {
    }

    /** 加载 dll（幂等）；失败记日志并返回 false。 */
    static synchronized boolean load() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary("ss_dyn_font");
            loaded = true;
            return true;
        } catch (Throwable t) {
            DynFontLog.error("加载动态字体原生库失败（java.library.path 中应存在 ss_dyn_font.dll）", t);
            return false;
        }
    }

    /** native ABI 版本；与 Java 侧预期不符时禁用（dll 与运行时类不匹配）。 */
    static native int nativeVersion();

    /**
     * 全量生成 11 套字体到 outDir（阻塞，native 内部多线程）。
     *
     * @param typefacePath 分发数据包（graphics/fonts/dyn_font/typefaces.dat，build.py
     *                 打包的 TTF 与 kerning 表）
     * @param logPath  native 日志的完整路径；空串表示输出到 stderr（CLI 用）
     * @return 0 成功；非 0 失败（详情见 logPath）
     */
    static native int nativeGenerate(
            String typefacePath, String charsPath, String outDir, double scale, String only,
            String logPath);
}

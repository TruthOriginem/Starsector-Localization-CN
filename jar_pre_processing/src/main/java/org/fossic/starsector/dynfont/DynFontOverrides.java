package org.fossic.starsector.dynfont;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态字体资源流拦截入口（ASM 注入 {@code com.fs.util.C} 的
 * {@code openStream(String)} 方法开头调用）。
 *
 * <p>命中我们生成的字体文件（11 套 .fnt 及其图集 PNG）时返回缓存产物的文件流，
 * 未命中返回 {@code null} 走游戏原始加载逻辑。首次命中字体 .fnt 请求时懒初始化：
 * 读取游戏 UI 缩放 → 校验缓存（键含 chars.txt/TTF/kerning 表/dll 内容哈希与
 * scale）→ 未命中则调 native 全量生成（阻塞数秒，仅首启动或字表/字体变更时）。
 *
 * <p>任何异常都静默降级为原版位图字体：入口位于游戏资源加载热路径，
 * 绝不允许因动态字体故障影响游戏启动。
 */
public final class DynFontOverrides {
    /** 覆盖的 11 套字体（须与 native 内置 spec 表一致）。 */
    private static final Set<String> FONT_NAMES = Set.of(
            "insignia15LTaa", "insignia21LTaa", "insignia25LTaa",
            "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
            "orbitron24aa", "orbitron24aabold",
            "victor10", "victor14", "victor16");

    /** 期望的 native ABI 版本（dll 不匹配时禁用）。v9：新增精确 .dfnt 度量。 */
    private static final int EXPECTED_NATIVE_VERSION = 9;
    /** native 日志文件名（写在游戏 logs 目录，与 starsector.log 同级）。 */
    private static final String NATIVE_LOG_NAME = "ss_dyn_font_native.log";
    /** 参数表/生成语义版本（进缓存键；native 侧 spec 表变更时递增强制重生成）。 */
    private static final String SPEC_VERSION = "2";
    /**
     * 同一份安装下最多保留几个缩放档的缓存（基础与 exact 两套图集），
     * 保留多档是为了让玩家切回旧缩放时秒开，但必须有上限——早先无上限的策略
     * 实测积到 9 档 / 1.7 GB。超出时按目录 mtime 淘汰最旧的。
     */
    private static final int MAX_CACHED_SCALES = 3;
    /** 游戏 0.98a 启动器支持的最大 UI 缩放为 300%。 */
    static final double MAX_SCREEN_SCALE = 3.0;

    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_ENABLED = 1;
    private static final int STATE_DISABLED = 2;

    private static volatile int state = STATE_UNINITIALIZED;
    /** 认领的文件名（小写）→ 缓存文件路径；重生成时整体替换（volatile 原子）。 */
    private static volatile Map<String, Path> claimed;
    private static volatile boolean errorLogged;
    /** 当前产物对应的 UI 缩放。 */
    private static volatile double screenScale = 1.0;
    /** 游戏阶段是否已复检过缩放（每进程一次）。 */
    private static volatile boolean gameScaleChecked;
    /**
     * 分发数据包文件名（{@code graphics/fonts/dyn_font/} 下）：TTF 与固化的 GPOS
     * kerning 表，由 build.py 打包。装的是生成字形的原料，成品图集在 {@code cache/}。
     */
    static final String TYPEFACE_PACK = "typefaces.dat";
    /**
     * 游戏本体入口类；launcher 的渲染线程（{@code GLLauncher$2.run}）栈上不会出现。
     * 与下面的读条状态类一样未被混淆，可直接按名字比对。
     */
    private static final String GAME_MAIN_CLASS = "com.fs.starfarer.combat.CombatMain";
    /** 游戏读条状态：注册全部字体的直接调用者（{@code ResourceLoaderState.init}）。 */
    private static final String GAME_LOADER_CLASS =
            "com.fs.starfarer.loading.ResourceLoaderState";
    /**
     * 小写 stem → 游戏请求时使用的**原样**资源路径。
     *
     * <p>预加载需要主动注册原字体以补全映射，而 BitmapFontManager 以路径字符串
     * 为 key，大小写不同会造出第二个 font 对象（如 insignia15LTaa vs
     * insignia15ltaa）。此处记录游戏实际用的那个字符串。
     */
    private static final Map<String, String> EXACT_FNT_PATH =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 游戏 GL context 是否已就绪，见 {@link #isGameContextReady()}。 */
    private static volatile boolean gameContextReady;
    /** 重生成进行中：渲染侧此期间不得加载 exact 代理（否则会拿到旧 scale 的产物）。 */
    private static volatile boolean regenerating;

    private DynFontOverrides() {
    }

    /** ASM 注入调用的静态入口；返回 null 时游戏走原始加载逻辑。 */
    public static InputStream openStream(String path) {
        try {
            if (state == STATE_DISABLED || path == null) {
                return null;
            }
            String name = baseName(path);
            if (state == STATE_UNINITIALIZED) {
                // 只有命中我们字体的 .fnt 请求才触发初始化（其余资源请求零开销通过）
                if (!isOwnedFnt(name)) {
                    return null;
                }
                initialize();
                if (state != STATE_ENABLED) {
                    return null;
                }
            }
            Map<String, Path> map = claimed;
            if (map == null) {
                return null;
            }
            Path file = map.get(name);
            if (file == null) {
                return null;
            }
            String requestedStem = name.endsWith(".fnt")
                    ? name.substring(0, name.length() - 4) : null;
            if (requestedStem != null && ownedFontStem(requestedStem) != null) {
                EXACT_FNT_PATH.putIfAbsent(requestedStem, path);
                if (!gameContextReady && inGameContext()) {
                    gameContextReady = true;
                    DynFontLog.info("检测到游戏 GL context 就绪（游戏本体正在加载字体）");
                    prepareHdDuringLoading();
                    // 同步重生成可能已整体替换产物映射（旧 scale 目录还会被缓存清理
                    // 顺手删掉），故重取一次，避免把已失效的路径交给游戏
                    Map<String, Path> refreshed = claimed;
                    Path updated = refreshed == null ? null : refreshed.get(name);
                    if (updated != null) {
                        file = updated;
                    }
                }
            }
            return new BufferedInputStream(Files.newInputStream(file));
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                try {
                    DynFontLog.error("资源拦截异常（仅记录一次，此后按未命中处理）", t);
                } catch (Throwable ignored) {
                    // 连日志都失败时彻底静默
                }
            }
            return null;
        }
    }

    /** 动态字体是否已成功初始化（供 {@link DynFontRenderHooks} 快速判断）。 */
    public static boolean isEnabled() {
        return state == STATE_ENABLED;
    }

    /** 启动时检测到的游戏 UI 缩放（仅初始化成功后有意义）。 */
    public static double detectedScreenScale() {
        return screenScale;
    }

    /** 缓存产物中是否存在指定文件（小写文件名，如 {@code orbitron24aa_exact.fnt}）。 */
    public static boolean hasClaim(String lowerFileName) {
        Map<String, Path> map = claimed;
        return map != null && map.containsKey(lowerFileName);
    }

    /** 取得生成缓存文件；仅供同包运行时读取，不向游戏资源层暴露真实路径。 */
    static Path claimedPath(String lowerFileName) {
        Map<String, Path> map = claimed;
        return map == null ? null : map.get(lowerFileName.toLowerCase(Locale.ROOT));
    }

    /**
     * 小写 stem 对应的原样资源路径（供预加载主动注册原字体）；未见过则返回 null。
     */
    public static String exactFntPath(String lowerStem) {
        return EXACT_FNT_PATH.get(lowerStem);
    }

    /**
     * 游戏 GL context 是否已就绪（可安全加载 exact 纹理）。
     *
     * <p>launcher 与游戏使用不同的 GL context（launcher 收尾时
     * {@code GLLauncher} 调 {@code Display.destroy()}，游戏再
     * {@code Display.create()}），前者加载的纹理 id 在后者中全部失效，故 exact 代理
     * 必须等游戏 context 建立后再加载。判据见 {@link #inGameContext()}。
     */
    public static boolean isGameContextReady() {
        return gameContextReady;
    }

    /**
     * 当前调用栈是否位于游戏本体（而非 launcher）。
     *
     * <p>游戏读条注册字体的栈是
     * {@code ResourceLoaderState.init → AppDriver.begin → CombatMain.main}，
     * launcher 的是 {@code GLLauncher.prepare → loadFont → GLLauncher$2.run}
     * —— 两者互斥，且这两个类名未被混淆，可直接比对。
     *
     * <p><b>为何不能用「基础包 .fnt 被二次请求」这类启发式。</b>launcher 与游戏
     * 同进程：玩家在 launcher 里改设置后 launcher 会在同一 JVM 内重启，
     * {@code GLLauncher.prepare} 遂第二次加载同一批字体，静态状态却不会重置。
     * 旧判据因此在 launcher 阶段就误判翻转，代理纹理被装进 launcher 的 GL context；
     * 进游戏后 context 已换，而 {@code BitmapFontManager}
     * （{@code com/fs/graphics/A/D}）的 HashMap 是 static 且无 clear，游戏读条只
     * 重新注册原版路径，{@code *_exact.fnt} 永远停在失效的纹理 id 上 —— 屏幕上就是
     * 整片色块。
     *
     * <p>判据失败方向是安全的：认不出来只会一直返回 false，代理不加载、全程 1x，
     * 不会出现错配纹理。
     */
    private static boolean inGameContext() {
        try {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                String className = frame.getClassName();
                if (GAME_MAIN_CLASS.equals(className) || GAME_LOADER_CLASS.equals(className)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 抓栈失败：按「尚未就绪」处理，宁可不高清也不要错配纹理
        }
        return false;
    }

    /**
     * exact 代理是否可安全加载。重生成期间为 false —— 此时若让游戏加载代理，
     * 拿到的会是旧 scale 的产物，而游戏的 BitmapFontManager 按路径缓存字体
     * 对象与纹理，之后无法再换掉（陈旧 UV/纹理正是方案 A 碎块的成因）。
     */
    public static boolean isProxyReady() {
        return !regenerating;
    }

    /**
     * 游戏阶段复检 UI 缩放（由 {@link DynFontRenderHooks} 在游戏 API 就绪后调用一次）。
     *
     * <p>launcher 与游戏本体共用同一个 JVM 进程（实证：dynfont 初始化与其后
     * 数百秒的游戏日志同一时间基准），而 {@code initialize()} 只在首次字体请求
     * 时跑一遍——即 launcher 阶段。用户在 launcher 里改缩放后 launcher 只是
     * 进程内重建 UI，本类的静态状态不会重置，产物便一直停留在旧 scale，游戏
     * 里全部文本被几何层放大糊掉。
     *
     * <p>故在游戏阶段重新检测：不一致则后台线程重新生成（native 生成耗时数秒，
     * 绝不能阻塞渲染线程），完成后整体替换 {@link #claimed}。此时代理尚未被
     * 游戏加载过（渲染切换由 {@link #isProxyReady()} 门控），因此不存在陈旧字体对象——1x 包
     * 虽已加载但其度量与 scale 无关，布局照旧正确。
     */
    public static void recheckScaleForGame() {
        recheckScaleForGame(false);
    }

    /**
     * @param blocking 在当前线程内直接重新生成。仅供读条阶段使用——那里阻塞只是
     *                 让读条条多走一两秒，而渲染线程上绝不可阻塞
     */
    private static void recheckScaleForGame(boolean blocking) {
        if (gameScaleChecked || state != STATE_ENABLED) {
            return;
        }
        synchronized (DynFontOverrides.class) {
            if (gameScaleChecked) {
                return;
            }
            gameScaleChecked = true;
            double detected;
            try {
                detected = detectScreenScale();
            } catch (Throwable t) {
                DynFontLog.error("游戏阶段缩放复检失败（保持现有产物）", t);
                return;
            }
            if (Math.abs(detected - screenScale) < 0.001) {
                return;
            }
            DynFontLog.info("游戏阶段缩放变化: " + screenScale + " -> " + detected
                    + (blocking ? "，读条阶段就地重新生成" : "，后台重新生成字体产物"));
            regenerating = true;
            if (blocking) {
                regenerate(detected);
                return;
            }
            Thread worker = new Thread(() -> regenerate(detected), "dynfont-regen");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * 读条阶段（判据翻转的那一刻，即 {@code ResourceLoaderState.init} 内）把 exact
     * 代理准备到位：先就地复检缩放，再把全部 exact 图集喂进显存。
     *
     * <p>为什么必须在这里做：代理映射挂在渲染入口上，而
     * {@code AppDriver.begin} 要等 {@code ResourceLoaderState.init} 整个跑完才进入
     * 渲染循环——读条期间一次 render 都没有。因此渲染侧最早的可切换时刻就是主菜单
     * 第一帧，11 张图集的解码上传（数秒）必然砸在那一帧上，表现为进入主菜单后卡顿
     * 并当场替换字体。
     *
     * <p>这里只注册 {@code *_exact.fnt}（游戏永不主动注册这些路径），不碰原字体，故不会与
     * 读条正在进行的注册相互覆盖；映射仍留到首帧建立，那时
     * 全部命中 BitmapFontManager 缓存，代价可忽略。
     *
     * <p>递归进入 {@code D.super()} 是安全的：本方法由资源流拦截调用，位置在
     * {@code C.openStream()} 内，外层此刻尚未开始使用它那两个静态解析游标
     * （{@code Object} / {@code o00000}）——它在拿到流之后才 {@code readLine()}
     * 并重新设置它们。
     */
    private static void prepareHdDuringLoading() {
        try {
            recheckScaleForGame(true);
            DynFontRenderHooks.warmUpExactTextures();
        } catch (Throwable t) {
            DynFontLog.error("读条阶段预热高清套失败（退回首帧加载）", t);
        }
    }

    /** 后台重生成（仅 {@link #recheckScaleForGame} 调用）。失败则保留原产物。 */
    private static void regenerate(double scale) {
        try {
            long t0 = System.nanoTime();
            Path dynFontDir = Path.of("graphics", "fonts", "dyn_font").toAbsolutePath();
            Path outDir = ensureGenerated(dynFontDir.resolve(TYPEFACE_PACK),
                    dynFontDir.resolve("chars.txt"),
                    Path.of("native", "windows", "ss_dyn_font.dll").toAbsolutePath(),
                    dynFontDir.resolve("cache"), scale);
            Map<String, Path> rebuilt = buildClaims(outDir);
            claimed = rebuilt;
            screenScale = scale;
            long ms = (System.nanoTime() - t0) / 1_000_000;
            DynFontLog.info("重新生成完成: scale=" + scale + ", " + rebuilt.size()
                    + " 个文件, 耗时 " + ms + " ms");
        } catch (Throwable t) {
            DynFontLog.error("重新生成失败，保留原有产物（高清切换按原 scale 进行）", t);
        } finally {
            regenerating = false;
        }
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return (slash >= 0 ? p.substring(slash + 1) : p).toLowerCase(Locale.ROOT);
    }

    private static boolean isOwnedFnt(String name) {
        return name.endsWith(".fnt")
                && ownedFontStem(name.substring(0, name.length() - 4)) != null;
    }

    /** 小写 stem → 规格名（保留原始大小写，非 11 套返回 null）。 */
    public static String ownedFontStem(String lowerStem) {
        for (String font : FONT_NAMES) {
            if (font.toLowerCase(Locale.ROOT).equals(lowerStem)) {
                return font;
            }
        }
        return null;
    }

    private static synchronized void initialize() {
        if (state != STATE_UNINITIALIZED) {
            return;
        }
        try {
            long t0 = System.nanoTime();
            // 游戏工作目录即 starsector-core；dyn_font 下仅 typefaces.dat + chars.txt
            // （+ 运行时 cache/）
            Path dynFontDir = Path.of("graphics", "fonts", "dyn_font").toAbsolutePath();
            Path typefacePack = dynFontDir.resolve(TYPEFACE_PACK);
            Path charsFile = dynFontDir.resolve("chars.txt");
            // dll 在 java.library.path（native\windows）；此路径仅用于缓存键哈希
            Path dll = Path.of("native", "windows", "ss_dyn_font.dll").toAbsolutePath();
            if (!Files.isRegularFile(typefacePack) || !Files.isRegularFile(charsFile)
                    || !Files.isRegularFile(dll)) {
                disable("动态字体文件不完整（需 dyn_font/" + TYPEFACE_PACK
                        + "、dyn_font/chars.txt 与 native/windows/ss_dyn_font.dll）");
                return;
            }

            if (!DynFontNatives.load()) {
                disable("原生库加载失败");
                return;
            }
            int version = DynFontNatives.nativeVersion();
            if (version != EXPECTED_NATIVE_VERSION) {
                disable("原生库 ABI 版本不匹配: got " + version
                        + ", expected " + EXPECTED_NATIVE_VERSION);
                return;
            }

            double scale = detectScreenScale();
            screenScale = scale;
            Path cacheRoot = dynFontDir.resolve("cache");

            // 精确代理套：native 按真实 scale 生成物理图集，代理 FNT 用固定倍率
            // 虚拟度量抵消游戏后续 UI 缩放；DFNT 保留精确小数排版数据。
            Path outDir = ensureGenerated(typefacePack, charsFile, dll, cacheRoot, scale);
            claimed = buildClaims(outDir);
            state = STATE_ENABLED;
            long ms = (System.nanoTime() - t0) / 1_000_000;
            DynFontLog.info("动态字体已启用: scale=" + scale + ", " + claimed.size()
                    + " 个文件, 初始化耗时 " + ms + " ms");
        } catch (Throwable t) {
            disable("初始化异常");
            DynFontLog.error("动态字体初始化异常，回退原版位图字体", t);
        }
    }

    /** 确保指定 scale 的缓存套存在（键不匹配则调 native 重生成），返回缓存目录。 */
    private static Path ensureGenerated(Path typefacePack, Path charsFile, Path dll,
                                        Path cacheRoot, double scale) throws Exception {
        cacheRoot = prepareCacheRoot(cacheRoot);
        String scaleTag = String.format(Locale.ROOT, "s%.2f", scale);
        String fingerprint = inputFingerprint(typefacePack, charsFile, dll);
        Path outDir = cacheRoot.resolve(scaleTag + "-" + fingerprint);
        Path marker = outDir.resolve(".complete");
        if (Files.isRegularFile(marker)) {
            try {
                // 完成标记不是唯一真值：玩家、杀软或异常退出都可能只删掉
                // 某个大 PNG。命中时重验整套产物，避免永久 fail-open 到原版字体。
                buildClaims(outDir);
                // 命中也要清理：玩家可能刚更新过汉化包，旧指纹的档需要回收
                pruneCaches(cacheRoot, fingerprint, outDir.getFileName().toString());
                return outDir;
            } catch (IOException invalid) {
                DynFontLog.info("动态字体缓存不完整，自动重建: "
                        + invalid.getMessage());
                deleteCache(outDir, "产物不完整");
            }
        }
        pruneCaches(cacheRoot, fingerprint, outDir.getFileName().toString());
        DynFontLog.info("生成动态字体图集: scale=" + scale + " → " + outDir);
        Path nativeLog = nativeLogPath();
        int rc = DynFontNatives.nativeGenerate(typefacePack.toString(), charsFile.toString(),
                outDir.toString(), scale, "", nativeLog.toString());
        if (rc != 0) {
            throw new IOException("原生生成失败 rc=" + rc + "（详见 " + nativeLog + "）");
        }
        relayNativeWarnings(nativeLog);
        Files.writeString(marker, fingerprint);
        return outDir;
    }

    /**
     * native 日志路径：游戏的 logs 目录（与 {@code starsector.log} 同级）。
     *
     * <p>取 {@code com.fs.starfarer.settings.paths.logs} —— 游戏自身就用它定位
     * starsector.log，故二者必然同处。早先写在产物目录内，玩家排障时翻不到。
     */
    private static Path nativeLogPath() {
        String dir = System.getProperty("com.fs.starfarer.settings.paths.logs", ".");
        try {
            return Path.of(dir).toAbsolutePath().normalize().resolve(NATIVE_LOG_NAME);
        } catch (Throwable t) {
            return Path.of(NATIVE_LOG_NAME).toAbsolutePath();
        }
    }

    /**
     * 把 native 日志里的告警转抄进游戏日志。
     *
     * <p>native 日志写在缓存目录内，玩家不会主动去看；而「图集接近单页上限」这类
     * 告警恰恰是需要玩家知道的——他继续扩字表就会触发降级。生成只在冷启动发生，
     * 转抄成本可忽略。读取失败一律忽略：告警转抄本身绝不能影响主流程。
     */
    private static void relayNativeWarnings(Path log) {
        try {
            if (!Files.isRegularFile(log)) {
                return;
            }
            for (String line : Files.readAllLines(log)) {
                if (line.startsWith("[warning]")) {
                    DynFontLog.info(line);
                }
            }
        } catch (Throwable ignored) {
            // 告警转抄失败不影响生成结果
        }
    }

    private static void disable(String reason) {
        state = STATE_DISABLED;
        DynFontLog.info("动态字体已禁用（回退原版位图字体）: " + reason);
    }

    /** 认领缓存目录下全部 .fnt、.png 与 .dfnt（键小写文件名）。 */
    private static Map<String, Path> buildClaims(Path outDir) throws IOException {
        Map<String, Path> map = new HashMap<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(outDir, "*.{fnt,png,dfnt}")) {
            for (Path file : stream) {
                map.put(file.getFileName().toString().toLowerCase(Locale.ROOT), file);
            }
        }
        for (String font : FONT_NAMES) {
            String stem = font.toLowerCase(Locale.ROOT);
            for (String suffix : List.of(".fnt", "_0.png", "_exact.fnt",
                    "_exact_0.png", ".dfnt")) {
                if (!map.containsKey(stem + suffix)) {
                    throw new IOException("动态字体缓存缺少 " + stem + suffix
                            + ": " + outDir);
                }
            }
        }
        return map;
    }

    /**
     * 缓存清理：删除指纹失效的全部档，并把同指纹的档数压到 {@link #MAX_CACHED_SCALES}。
     *
     * <p>目录名形如 {@code s2.00-<指纹>}，指纹只由 data/chars/dll/spec 版本决定、
     * <b>不含 scale</b>。这样一次安装下所有缩放档共享同一指纹，更新汉化包时能一次性
     * 认出并清空全部旧档——而早先把 scale 混进哈希的做法使不同档的键必然不同、
     * 无法互相识别，非当前档的失效目录会永久滞留（实测积到 1.7 GB / 9 档）。
     *
     * <p>同指纹的档按目录 mtime 保留最近若干个（切回旧缩放可秒开），其余淘汰。
     *
     * <p>清理纯属磁盘卫生，任何文件操作失败都只记日志不中断生成。
     */
    private static void pruneCaches(Path cacheRoot, String fingerprint, String keep) {
        try {
            cacheRoot = prepareCacheRoot(cacheRoot);
        } catch (IOException unsafeRoot) {
            DynFontLog.error("动态字体缓存根不安全（跳过清理）", unsafeRoot);
            return;
        }
        String suffix = "-" + fingerprint;
        List<Path> sameFingerprint = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheRoot)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || name.equals(keep)) {
                    continue;
                }
                if (name.endsWith(suffix)) {
                    sameFingerprint.add(dir);   // 同一份安装的其它缩放档，稍后按 LRU 处理
                } else {
                    deleteCache(dir, "指纹失效");
                }
            }
        } catch (IOException e) {
            DynFontLog.error("枚举缓存目录失败（跳过清理）", e);
            return;
        }

        // keep 自身占一个名额
        int over = sameFingerprint.size() + 1 - MAX_CACHED_SCALES;
        if (over <= 0) {
            return;
        }
        sameFingerprint.sort(Comparator.comparingLong(DynFontOverrides::lastModifiedOrZero));
        for (int i = 0; i < over && i < sameFingerprint.size(); i++) {
            deleteCache(sameFingerprint.get(i), "超出保留档数");
        }
    }

    /**
     * 创建并验证缓存根是工作目录内的普通目录。除了拒绝缓存根
     * 自身是 symlink/junction，还要比较真实路径：否则上级 {@code dyn_font/}
     * 可以是指向游戏目录外的 junction，后续递归清理仍会越界。
     */
    static Path prepareCacheRoot(Path cacheRoot) throws IOException {
        return prepareCacheRoot(cacheRoot, Path.of("."));
    }

    /** 可注入允许根的安全校验核心；生产路径固定使用当前工作目录。 */
    static Path prepareCacheRoot(Path cacheRoot, Path allowedRoot) throws IOException {
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        Path allowedReal = allowedRoot.toAbsolutePath().normalize().toRealPath();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("动态字体缓存根缺少上级目录: " + normalized);
        }
        // 先验证已存在的父目录，再创建 cache，避免经父 junction
        // 在允许根外产生任何文件系统写入。
        Path parentReal = parent.toRealPath();
        if (!parentReal.startsWith(allowedReal)) {
            throw new IOException("动态字体缓存父目录越出工作目录: "
                    + parentReal + " (root=" + allowedReal + ")");
        }
        try {
            Files.createDirectory(normalized);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            // 下方统一读取 NOFOLLOW 属性并验证，不能用 Files.isDirectory 跟随链接。
        }
        BasicFileAttributes attributes = Files.readAttributes(
                normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isOther()
                || attributes.isSymbolicLink()) {
            throw new IOException("动态字体缓存根不是普通目录: " + normalized);
        }
        Path rootReal = normalized.toRealPath();
        if (!rootReal.startsWith(allowedReal)) {
            throw new IOException("动态字体缓存根越出工作目录: "
                    + rootReal + " (root=" + allowedReal + ")");
        }
        return normalized;
    }

    private static long lastModifiedOrZero(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;  // 读不到时间戳的目录优先淘汰
        }
    }

    /** 递归删除一个缓存档；失败只记日志（可能被杀软/资源管理器占用）。 */
    private static void deleteCache(Path dir, String reason) {
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            DynFontLog.info("清理缓存（" + reason + "）: " + dir.getFileName());
        } catch (Throwable t) {
            DynFontLog.error("清理缓存失败（忽略）: " + dir.getFileName(), t);
        }
    }

    // ── 缓存键：spec 版本 + scale + chars.txt/dll/全部 TTF 与 kerning 表内容哈希 ──

    /**
     * 输入指纹：spec 版本 + data 包（含全部 TTF 与 kerning 表）/chars.txt/dll 内容。
     *
     * <p><b>不含 scale</b> —— scale 体现在目录名前缀里。这样同一份安装的所有缩放档
     * 共享一个指纹，{@link #pruneCaches} 才能识别并清空全部失效档（见其注释）。
     */
    private static String inputFingerprint(Path typefacePack, Path charsFile, Path dll)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("spec" + SPEC_VERSION + ";").getBytes());
        byte[] buf = new byte[65536];
        for (Path input : new Path[]{typefacePack, charsFile, dll}) {
            digest.update(input.getFileName().toString().getBytes());
            // 流式读：data 包 15+ MB，一次性 readAllBytes 会在 G1 下产生 humongous 分配
            try (InputStream is = Files.newInputStream(input)) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    // ── 游戏 UI 缩放读取（复刻 launcher 规则；启动时定死，改缩放需重启游戏）────

    private static double detectScreenScale() {
        double scale = readSettingsOverride();
        if (scale <= 0) {
            scale = readPreferencesScale();
        }
        if (scale <= 0) {
            scale = defaultScaleFromResolution();
        }
        return normalizeScreenScale(scale);
    }

    static double normalizeScreenScale(double scale) {
        if (!Double.isFinite(scale)) {
            throw new IllegalArgumentException("UI 缩放不是有限值: " + scale);
        }
        scale = Math.max(1.0, scale);
        if (scale > MAX_SCREEN_SCALE) {
            throw new IllegalArgumentException("UI 缩放超出支持范围: " + scale
                    + " > " + MAX_SCREEN_SCALE);
        }
        // 游戏存储粒度 0.05
        return Math.round(scale * 20.0) / 20.0;
    }

    /** settings.json 的 screenScaleOverride（>0 时覆盖一切）。 */
    private static double readSettingsOverride() {
        try {
            Path settings = Path.of("data", "config", "settings.json");
            if (!Files.isRegularFile(settings)) {
                return 0;
            }
            String text = Files.readString(settings);
            Matcher m = Pattern.compile("\"screenScaleOverride\"\\s*:\\s*([0-9.]+)")
                    .matcher(text);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                if (v > 0) {
                    DynFontLog.info("screenScaleOverride=" + v);
                    return v > 20 ? v / 100.0 : v;
                }
            }
        } catch (Throwable t) {
            DynFontLog.error("读取 settings.json 失败（忽略）", t);
        }
        return 0;
    }

    /** Java Preferences 的 screenScale（launcher 写入；兼容浮点与百分比两种格式）。 */
    private static double readPreferencesScale() {
        try {
            Preferences prefs = Preferences.userRoot().node("com/fs/starfarer");
            String value = prefs.get("screenScale", null);
            if (value == null) {
                return 0;
            }
            double v = Double.parseDouble(value.trim());
            return v > 20 ? v / 100.0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 键缺失时复刻游戏默认规则（按 resolution 高度）。 */
    private static double defaultScaleFromResolution() {
        int height = 0;
        try {
            Preferences prefs = Preferences.userRoot().node("com/fs/starfarer");
            String res = prefs.get("resolution", null);  // 如 "1920x1080"
            if (res != null) {
                int x = res.indexOf('x');
                if (x > 0) {
                    height = Integer.parseInt(res.substring(x + 1).trim());
                }
            }
        } catch (Throwable ignored) {
            // 走保底 1.0
        }
        if (height <= 0 || height < 1300) {
            return 1.0;
        }
        if (height < 2160) {
            return Math.round(height / 1080.0 * 20.0) / 20.0;
        }
        return Math.round(height / 1080.0);
    }
}

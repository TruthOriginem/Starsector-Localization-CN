package org.fossic.starsector.dynfont;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String LOGICAL_FONT_DIRECTORY = "graphics/fonts/";
    /** 覆盖的 11 套字体（须与 native 内置 spec 表一致）。 */
    private static final Set<String> FONT_NAMES = Set.of(
            "insignia15LTaa", "insignia21LTaa", "insignia25LTaa",
            "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
            "orbitron24aa", "orbitron24aabold",
            "victor10", "victor14", "victor16");

    /** 期望的 native ABI 版本（dll 不匹配时禁用）。v8：nativeGenerate 增 logPath。 */
    private static final int EXPECTED_NATIVE_VERSION = 8;
    /** native 日志文件名（写在游戏 logs 目录，与 starsector.log 同级）。 */
    private static final String NATIVE_LOG_NAME = "ss_dyn_font_native.log";
    /** 参数表/生成语义版本（进缓存键；native 侧 spec 表变更时递增强制重生成）。 */
    private static final String SPEC_VERSION = "2";
    /**
     * 同一份安装下最多保留几个缩放档的缓存。每档约 200 MB（hd 图集占大头），
     * 保留多档是为了让玩家切回旧缩放时秒开，但必须有上限——早先无上限的策略
     * 实测积到 9 档 / 1.7 GB。超出时按目录 mtime 淘汰最旧的。
     */
    private static final int MAX_CACHED_SCALES = 3;
    private static final long TEMPORARY_CACHE_RETENTION_MILLIS =
            24L * 60L * 60L * 1000L;
    private static final String CACHE_MANIFEST_VERSION =
            "dynfont-cache-manifest-v3";
    private static final String CACHE_PUBLICATION_LOCK = ".publish.lock";
    private static final String CACHE_USE_LOCK = ".in-use.lock";
    private static final long MAXIMUM_MANIFEST_BYTES = 128L * 1024L;
    private static final long MANIFEST_REVERIFY_MILLIS =
            7L * 24L * 60L * 60L * 1000L;
    /** JVM 文件锁不会等待同一进程内的重叠锁，先在进程内串行化。 */
    private static final Object CACHE_PUBLICATION_MONITOR = new Object();
    /**
     * 本 JVM 已认领缓存的生命周期锁。旧 scale 的锁也保留到进程退出：资源线程可能已
     * 取到旧路径但尚未打开文件，过早释放会让本进程自己的剪枝删掉它。
     */
    private static final Map<Path, CacheLease> RETAINED_CACHE_LEASES =
            new HashMap<>();

    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_ENABLED = 1;
    private static final int STATE_DISABLED = 2;

    private static volatile int state = STATE_UNINITIALIZED;
    /** 小写完整逻辑路径→缓存文件路径；重生成时整体替换（volatile 原子）。 */
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
    /** 游戏 GL context 是否已就绪；CAS 不依赖已被 O03 移除的资源加载器总锁。 */
    private static final AtomicBoolean GAME_CONTEXT_READY =
            new AtomicBoolean();
    /** 重生成进行中：渲染侧此期间不得加载 hd 套（否则会拿到旧 scale 的产物）。 */
    private static volatile boolean regenerating;

    private DynFontOverrides() {
    }

    /** ASM 注入调用的静态入口；返回 null 时游戏走原始加载逻辑。 */
    public static InputStream openStream(String path) {
        try {
            if (state == STATE_DISABLED || path == null) {
                return null;
            }
            String logicalPath = normalizeLogicalPath(path);
            if (logicalPath == null) {
                return null;
            }
            String name = logicalPath.substring(
                    LOGICAL_FONT_DIRECTORY.length());
            if (state == STATE_UNINITIALIZED) {
                // 只有命中我们字体的 .fnt 请求才触发初始化（其余资源请求零开销通过）
                if (!isOwnedFntPath(logicalPath)) {
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
            Path file = map.get(logicalPath);
            if (file == null) {
                return null;
            }
            if (name.endsWith(".fnt") && !name.contains("_hd.")) {
                EXACT_FNT_PATH.putIfAbsent(name.substring(0, name.length() - 4), path);
                if (inGameContext() && markGameContextReady()) {
                    DynFontLog.info("检测到游戏 GL context 就绪（游戏本体正在加载字体）");
                    prepareHdDuringLoading();
                    // 同步重生成可能已整体替换产物映射（旧 scale 目录还会被缓存清理
                    // 顺手删掉），故重取一次，避免把已失效的路径交给游戏
                    Map<String, Path> refreshed = claimed;
                    Path updated = refreshed == null
                            ? null : refreshed.get(logicalPath);
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

    /** 缓存产物中是否存在指定文件（小写文件名，如 {@code orbitron24aa_hd.fnt}）。 */
    public static boolean hasClaim(String lowerFileName) {
        Map<String, Path> map = claimed;
        if (map == null || lowerFileName == null) {
            return false;
        }
        String key = lowerFileName.indexOf('/') >= 0
                || lowerFileName.indexOf('\\') >= 0
                ? normalizeLogicalPath(lowerFileName)
                : LOGICAL_FONT_DIRECTORY
                        + lowerFileName.toLowerCase(Locale.ROOT);
        return key != null && map.containsKey(key);
    }

    /**
     * 小写 stem 对应的原样资源路径（供预加载主动注册原字体）；未见过则返回 null。
     */
    public static String exactFntPath(String lowerStem) {
        return EXACT_FNT_PATH.get(lowerStem);
    }

    /**
     * 游戏 GL context 是否已就绪（可安全加载 hd 纹理）。
     *
     * <p>launcher 与游戏使用不同的 GL context（launcher 收尾时
     * {@code GLLauncher} 调 {@code Display.destroy()}，游戏再
     * {@code Display.create()}），前者加载的纹理 id 在后者中全部失效，故 hd 套
     * 必须等游戏 context 建立后再加载。判据见 {@link #inGameContext()}。
     */
    public static boolean isGameContextReady() {
        return GAME_CONTEXT_READY.get();
    }

    static boolean markGameContextReady() {
        return GAME_CONTEXT_READY.compareAndSet(false, true);
    }

    static void resetGameContextGateForTests() {
        GAME_CONTEXT_READY.set(false);
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
     * 旧判据因此在 launcher 阶段就误判翻转，hd 套被装进 launcher 的 GL context；
     * 进游戏后 context 已换，而 {@code BitmapFontManager}
     * （{@code com/fs/graphics/A/D}）的 HashMap 是 static 且无 clear，游戏读条只
     * 重新注册原版路径，{@code *_hd.fnt} 永远停在失效的纹理 id 上 —— 屏幕上就是
     * 整片色块。
     *
     * <p>判据失败方向是安全的：认不出来只会一直返回 false，hd 不加载、全程 1x，
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
     * hd 套是否可安全加载。重生成期间为 false —— 此时若让游戏加载 hd 套，
     * 拿到的会是旧 scale 的产物，而游戏的 BitmapFontManager 按路径缓存字体
     * 对象与纹理，之后无法再换掉（陈旧 UV/纹理正是方案 A 碎块的成因）。
     */
    public static boolean isHdReady() {
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
     * 绝不能阻塞渲染线程），完成后整体替换 {@link #claimed}。此时 hd 套尚未被
     * 游戏加载过（渲染切换由 isHdReady 门控），因此不存在陈旧字体对象——1x 包
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
     * 读条阶段（判据翻转的那一刻，即 {@code ResourceLoaderState.init} 内）把 hd
     * 套准备到位：先就地复检缩放，再把全部 hd 图集喂进显存。
     *
     * <p>为什么必须在这里做：{@code preloadAllHd} 挂在渲染入口上，而
     * {@code AppDriver.begin} 要等 {@code ResourceLoaderState.init} 整个跑完才进入
     * 渲染循环——读条期间一次 render 都没有。因此渲染侧最早的可切换时刻就是主菜单
     * 第一帧，11 张图集的解码上传（数秒）必然砸在那一帧上，表现为进入主菜单后卡顿
     * 并当场替换字体。
     *
     * <p>这里只注册 {@code *_hd.fnt}（游戏永不注册这些路径），不碰原字体，故不会与
     * 读条正在进行的注册相互覆盖；映射仍留到首帧的 {@code preloadAllHd} 建立，那时
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
            if (screenScale > 1.001) {
                DynFontRenderHooks.warmUpHdTextures();
            }
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
            Map<String, Path> rebuilt = buildClaims(outDir, scale);
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

    private static String normalizeLogicalPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String normalized = path.replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(LOGICAL_FONT_DIRECTORY)
                || normalized.length() <= LOGICAL_FONT_DIRECTORY.length()) {
            return null;
        }
        String name = normalized.substring(
                LOGICAL_FONT_DIRECTORY.length());
        if (name.indexOf('/') >= 0
                || ".".equals(name)
                || "..".equals(name)
                || name.contains("/../")
                || name.contains("/./")) {
            return null;
        }
        return LOGICAL_FONT_DIRECTORY + name;
    }

    static boolean isOwnedFntPath(String path) {
        String logicalPath = normalizeLogicalPath(path);
        if (logicalPath == null) {
            return false;
        }
        String name = logicalPath.substring(
                LOGICAL_FONT_DIRECTORY.length());
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

            // 方案 Y 双套：基础 fnt/png 永远是纯 1x；scale>1 时另生成度量同比例
            // 同构的 hd fnt/png 自洽套，由渲染 hook 整体切换。
            Path outDir = ensureGenerated(typefacePack, charsFile, dll, cacheRoot, scale);
            claimed = buildClaims(outDir, scale);
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
        String scaleTag = scaleTag(scale);
        String fingerprint = inputFingerprint(typefacePack, charsFile, dll);
        Path outDir = cacheRoot.resolve(scaleTag + "-" + fingerprint);
        if (claimCompleteCache(
                cacheRoot, outDir, fingerprint, scale, true)) {
            return outDir;
        }
        DynFontLog.info("生成动态字体图集: scale=" + scale + " → " + outDir);
        Files.createDirectories(cacheRoot);
        Path temporary = cacheRoot.resolve(
                "." + outDir.getFileName() + ".tmp-" + UUID.randomUUID());
        Path nativeLog = nativeLogPath();
        try {
            int rc = DynFontNatives.nativeGenerate(
                    typefacePack.toString(),
                    charsFile.toString(),
                    temporary.toString(),
                    scale,
                    "",
                    nativeLog.toString());
            if (rc != 0) {
                throw new IOException(
                        "原生生成失败 rc=" + rc + "（详见 " + nativeLog + "）");
            }
            relayNativeWarnings(nativeLog);
            String fingerprintAfterGeneration = inputFingerprint(
                    typefacePack, charsFile, dll);
            if (!fingerprint.equals(fingerprintAfterGeneration)) {
                throw new IOException(
                        "动态字体生成期间输入发生变化，丢弃本轮产物");
            }
            if (!hasExpectedOutputs(temporary, scale)) {
                throw new IOException("原生生成结果不完整: " + temporary);
            }
            writeCompletionMarker(temporary, fingerprint, scale);
            publishClaimAndPrune(
                    cacheRoot, temporary, outDir, fingerprint, scale);
            return outDir;
        } finally {
            if (Files.exists(temporary)) {
                deleteCache(temporary, "未发布临时产物");
            }
        }
    }

    static void writeCompletionMarker(
            Path directory, String fingerprint, double scale) throws IOException {
        if (!isPlainDirectory(directory)) {
            throw new IOException(
                    "动态字体完成清单目录不是普通目录: " + directory);
        }
        ensureUseLockFile(directory);
        Map<String, CacheFileManifest> files = new HashMap<>();
        for (String name : new TreeSet<>(expectedOutputNames(scale))) {
            Path output = directory.resolve(name);
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("动态字体产物缺失: " + output);
            }
            long size = Files.size(output);
            if (size <= 0L) {
                throw new IOException("动态字体产物为空: " + output);
            }
            files.put(name, new CacheFileManifest(
                    size,
                    Files.getLastModifiedTime(
                            output, LinkOption.NOFOLLOW_LINKS).toMillis(),
                    sha256(output)));
        }
        writeCompletionMarker(
                directory, fingerprint, scale, System.currentTimeMillis(), files);
    }

    private static void ensureUseLockFile(Path directory) throws IOException {
        Path lock = directory.resolve(CACHE_USE_LOCK);
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("动态字体使用锁不是普通文件: " + lock);
        }
        try (FileChannel ignored = FileChannel.open(
                lock,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            // 文件本身是跨进程共享锁的稳定锚点，不写入进程相关内容。
        }
    }

    private static void writeCompletionMarker(
            Path directory,
            String fingerprint,
            double scale,
            long verifiedAtMillis,
            Map<String, CacheFileManifest> files) throws IOException {
        Path marker = directory.resolve(".complete");
        Path temporary = directory.resolve(
                ".complete.tmp-" + UUID.randomUUID());
        StringBuilder body = new StringBuilder();
        body.append(CACHE_MANIFEST_VERSION).append('\n');
        body.append("fingerprint\t").append(fingerprint).append('\n');
        body.append("scale\t").append(manifestScale(scale)).append('\n');
        body.append("verified-at\t").append(verifiedAtMillis).append('\n');
        for (String name : new TreeSet<>(expectedOutputNames(scale))) {
            CacheFileManifest file = files.get(name);
            if (file == null) {
                throw new IOException("动态字体清单缺失: " + name);
            }
            body.append(name)
                    .append('\t').append(file.size())
                    .append('\t').append(file.modifiedMillis())
                    .append('\t').append(file.sha256())
                    .append('\n');
        }
        String manifest = body
                + "manifest-sha256\t"
                + sha256(body.toString().getBytes(StandardCharsets.UTF_8))
                + '\n';
        try {
            Files.writeString(
                    temporary,
                    manifest,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        marker,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        marker,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 原子发布已生成且带完成清单的目录。
     *
     * <p>JVM monitor 处理同进程线程，文件锁处理两个同时启动的游戏进程。锁内必须
     * 重新验证目标：后到者只能复用先到者的完整结果，绝不能把它当作旧残留删除。
     */
    static void publishGeneratedDirectory(
            Path cacheRoot,
            Path generated,
            Path target,
            String fingerprint,
            double scale) throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path normalizedGenerated = generated.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedRoot.equals(normalizedGenerated.getParent())
                || !normalizedRoot.equals(normalizedTarget.getParent())) {
            throw new IOException("动态字体发布路径越界");
        }
        if (!cacheDirectoryName(fingerprint, scale).equals(
                normalizedTarget.getFileName().toString())) {
            throw new IOException("动态字体目标目录与 scale/指纹不匹配");
        }
        if (!isCompleteCache(normalizedGenerated, fingerprint, scale)) {
            throw new IOException("动态字体完成标记复验失败: " + generated);
        }
        withCacheRootLock(normalizedRoot, root -> {
            publishGeneratedDirectoryLocked(
                    root,
                    normalizedGenerated,
                    normalizedTarget,
                    fingerprint,
                    scale);
            return null;
        });
    }

    private static void publishClaimAndPrune(
            Path cacheRoot,
            Path generated,
            Path target,
            String fingerprint,
            double scale) throws IOException {
        Path normalizedGenerated = generated.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        withCacheRootLock(cacheRoot, root -> {
            if (!root.equals(normalizedGenerated.getParent())
                    || !root.equals(normalizedTarget.getParent())) {
                throw new IOException("动态字体发布路径越界");
            }
            publishGeneratedDirectoryLocked(
                    root,
                    normalizedGenerated,
                    normalizedTarget,
                    fingerprint,
                    scale);
            acquireCacheLeaseLocked(root, normalizedTarget);
            touchCacheDirectory(normalizedTarget);
            pruneCachesLocked(
                    root, fingerprint, normalizedTarget.getFileName().toString());
            return null;
        });
    }

    private static void publishGeneratedDirectoryLocked(
            Path normalizedRoot,
            Path normalizedGenerated,
            Path normalizedTarget,
            String fingerprint,
            double scale) throws IOException {
        if (!normalizedRoot.equals(normalizedGenerated.getParent())
                || !normalizedRoot.equals(normalizedTarget.getParent())) {
            throw new IOException("动态字体发布路径越界");
        }
        if (!cacheDirectoryName(fingerprint, scale).equals(
                normalizedTarget.getFileName().toString())) {
            throw new IOException("动态字体目标目录与 scale/指纹不匹配");
        }
        if (!isCompleteCache(normalizedGenerated, fingerprint, scale)) {
            throw new IOException(
                    "动态字体完成标记复验失败: " + normalizedGenerated);
        }
        if (isCompleteCache(normalizedTarget, fingerprint, scale)) {
            return;
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            if (isCacheInUseLocked(normalizedTarget)) {
                throw new IOException(
                        "动态字体目标缓存不完整但仍被其它游戏实例使用: "
                                + normalizedTarget);
            }
            deleteTreeStrict(normalizedTarget);
        }
        moveDirectory(normalizedGenerated, normalizedTarget);
        // 移动前已逐文件验证；移动后再做清单与全部元数据复验，拒绝半发布目录。
        if (!isCompleteCache(normalizedTarget, fingerprint, scale)) {
            throw new IOException(
                    "动态字体目录发布后复验失败: " + normalizedTarget);
        }
    }

    private static void deleteTreeStrict(Path root) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    root,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException missing) {
            return;
        }
        // Windows junction 在 NIO 中同时是 directory 与 other，Files.walk() 即使未传
        // FOLLOW_LINKS 也会穿过它。所有 reparse-point/符号链接都只能删除链接本身。
        if (!attributes.isDirectory()
                || attributes.isOther()
                || attributes.isSymbolicLink()) {
            Files.deleteIfExists(root);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                deleteTreeStrict(child);
            }
        }
        Files.deleteIfExists(root);
    }

    private static void moveDirectory(Path source, Path target)
            throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static boolean claimCompleteCache(
            Path cacheRoot,
            Path target,
            String fingerprint,
            double scale,
            boolean prune) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        try {
            return withCacheRootLock(cacheRoot, root -> claimCompleteCacheLocked(
                    root,
                    normalizedTarget,
                    fingerprint,
                    scale,
                    prune));
        } catch (IOException exclusiveFailure) {
            try {
                // 已发布缓存的两个锁文件都可只读获取共享锁。只读安装目录不能
                // touch/prune，但仍能安全阻止其它可写进程取得根独占锁并删除它。
                return withCacheRootSharedLock(
                        cacheRoot,
                        root -> claimCompleteCacheLocked(
                                root,
                                normalizedTarget,
                                fingerprint,
                                scale,
                                false));
            } catch (IOException sharedFailure) {
                exclusiveFailure.addSuppressed(sharedFailure);
                throw exclusiveFailure;
            }
        }
    }

    private static boolean claimCompleteCacheLocked(
            Path normalizedRoot,
            Path normalizedTarget,
            String fingerprint,
            double scale,
            boolean prune) throws IOException {
        if (!normalizedRoot.equals(normalizedTarget.getParent())) {
            throw new IOException("动态字体认领路径越界");
        }
        if (!cacheDirectoryName(fingerprint, scale).equals(
                normalizedTarget.getFileName().toString())) {
            throw new IOException("动态字体认领目录与 scale/指纹不匹配");
        }
        if (!isCompleteCache(normalizedTarget, fingerprint, scale)) {
            return false;
        }
        acquireCacheLeaseLocked(normalizedRoot, normalizedTarget);
        if (prune) {
            touchCacheDirectory(normalizedTarget);
            pruneCachesLocked(
                    normalizedRoot,
                    fingerprint,
                    normalizedTarget.getFileName().toString());
        }
        return true;
    }

    static boolean claimCompleteCacheForTests(
            Path cacheRoot,
            Path target,
            String fingerprint,
            double scale) throws IOException {
        return claimCompleteCache(
                cacheRoot, target, fingerprint, scale, false);
    }

    private static CacheLease acquireCacheLeaseLocked(
            Path normalizedRoot, Path normalizedDirectory) throws IOException {
        if (!normalizedRoot.equals(normalizedDirectory.getParent())) {
            throw new IOException("动态字体认领路径越界");
        }
        CacheLease retained = RETAINED_CACHE_LEASES.get(normalizedDirectory);
        if (retained != null && retained.isValid()) {
            return retained;
        }
        Path lockPath = normalizedDirectory.resolve(CACHE_USE_LOCK);
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("动态字体使用锁缺失: " + lockPath);
        }
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.READ);
        try {
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, true);
            if (lock == null) {
                throw new IOException("无法获取动态字体缓存共享使用锁: " + lockPath);
            }
            CacheLease lease = new CacheLease(normalizedDirectory, channel, lock);
            RETAINED_CACHE_LEASES.put(normalizedDirectory, lease);
            return lease;
        } catch (Throwable failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException(
                    "获取动态字体缓存共享使用锁失败: " + lockPath,
                    failure);
        }
    }

    /** 必须在 cache root 独占锁内调用；未知锁状态一律按正在使用处理。 */
    private static boolean isCacheInUseLocked(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        CacheLease retained = RETAINED_CACHE_LEASES.get(normalized);
        if (retained != null && retained.isValid()) {
            return true;
        }
        Path lockPath = normalized.resolve(CACHE_USE_LOCK);
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                return lock == null;
            } catch (OverlappingFileLockException activeInThisJvm) {
                return true;
            }
        } catch (IOException | RuntimeException unknown) {
            return true;
        }
    }

    private static void touchCacheDirectory(Path directory) {
        try {
            Files.setLastModifiedTime(
                    directory, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // LRU 提示写失败不影响已取得的生命周期锁与缓存正确性。
        }
    }

    private static <T> T withCacheRootLock(
            Path cacheRoot, CacheLockedAction<T> action) throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        synchronized (CACHE_PUBLICATION_MONITOR) {
            Path lockPath = normalizedRoot.resolve(CACHE_PUBLICATION_LOCK);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(
                            lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "动态字体缓存根锁不是普通文件: " + lockPath);
            }
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.run(normalizedRoot);
            }
        }
    }

    private static <T> T withCacheRootSharedLock(
            Path cacheRoot, CacheLockedAction<T> action) throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("动态字体缓存根目录不存在: " + normalizedRoot);
        }
        synchronized (CACHE_PUBLICATION_MONITOR) {
            Path lockPath = normalizedRoot.resolve(CACHE_PUBLICATION_LOCK);
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("动态字体缓存根锁缺失: " + lockPath);
            }
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.READ);
                 FileLock ignored = channel.lock(
                         0L, Long.MAX_VALUE, true)) {
                return action.run(normalizedRoot);
            }
        }
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
     * <p>native 的独立日志不一定会被玩家主动查看；而「图集接近单页上限」这类
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

    /** 认领缓存目录下全部 .fnt 与 .png（键为小写完整游戏逻辑路径）。 */
    static Map<String, Path> buildClaims(Path outDir, double scale) throws IOException {
        if (!isPlainDirectory(outDir)) {
            throw new IOException("动态字体缓存目录不是普通目录: " + outDir);
        }
        Map<String, Path> map = new HashMap<>();
        Set<String> expected = expectedOutputNames(scale);
        for (String name : expected) {
            Path file = outDir.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                map.put(
                        LOGICAL_FONT_DIRECTORY
                                + file.getFileName().toString()
                                .toLowerCase(Locale.ROOT),
                        file);
            }
        }
        if (map.size() != expected.size()) {
            throw new IOException(
                    "动态字体缓存认领不完整: " + map.size() + "/"
                            + expected.size() + "（" + outDir + "）");
        }
        return map;
    }

    /** 与 native 的严格合同一致：{@code scale > 1.001} 才生成 hd 套。 */
    static Set<String> expectedOutputNames(double scale) {
        TreeSet<String> names = new TreeSet<>();
        for (String font : FONT_NAMES) {
            names.add(font + ".fnt");
            names.add(font + "_0.png");
            if (scale > 1.001) {
                names.add(font + "_hd.fnt");
                names.add(font + "_hd_0.png");
            }
        }
        return Set.copyOf(names);
    }

    private static String scaleTag(double scale) {
        return String.format(Locale.ROOT, "s%.2f", scale);
    }

    private static String manifestScale(double scale) {
        return Double.toString(scale);
    }

    private static String cacheDirectoryName(
            String fingerprint, double scale) {
        return scaleTag(scale) + "-" + fingerprint;
    }

    static boolean isCompleteCache(
            Path outDir, String fingerprint, double scale) {
        try {
            if (!isPlainDirectory(outDir)) {
                return false;
            }
            Path marker = outDir.resolve(".complete");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!Files.isRegularFile(
                    outDir.resolve(CACHE_USE_LOCK),
                    LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            long markerSize = Files.size(marker);
            if (markerSize <= 0L || markerSize > MAXIMUM_MANIFEST_BYTES) {
                return false;
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            List<String> expected = new TreeSet<>(
                    expectedOutputNames(scale)).stream().toList();
            if (lines.size() != expected.size() + 5
                    || !CACHE_MANIFEST_VERSION.equals(lines.get(0))
                    || !("fingerprint\t" + fingerprint).equals(lines.get(1))
                    || !("scale\t" + manifestScale(scale)).equals(lines.get(2))
                    || !lines.get(3).startsWith("verified-at\t")
                    || !validManifestChecksum(lines)) {
                return false;
            }
            long verifiedAt = Long.parseLong(
                    lines.get(3).substring("verified-at\t".length()));
            long now = System.currentTimeMillis();
            boolean reverify = verifiedAt < 0L
                    || verifiedAt > now
                    || now - verifiedAt >= MANIFEST_REVERIFY_MILLIS;
            Map<String, CacheFileManifest> current = new HashMap<>();
            for (int index = 0; index < expected.size(); index++) {
                String[] fields = lines.get(index + 4).split("\\t", -1);
                String name = expected.get(index);
                if (fields.length != 4 || !name.equals(fields[0])) {
                    return false;
                }
                long expectedSize = Long.parseLong(fields[1]);
                long expectedModified = Long.parseLong(fields[2]);
                if (expectedSize <= 0L || !isSha256(fields[3])) {
                    return false;
                }
                Path output = outDir.resolve(name);
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                long actualSize = Files.size(output);
                long actualModified = Files.getLastModifiedTime(
                        output, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (actualSize != expectedSize
                        || actualModified != expectedModified) {
                    reverify = true;
                }
                current.put(name, new CacheFileManifest(
                        actualSize, actualModified, fields[3]));
            }
            if (!reverify) {
                return true;
            }
            for (String name : expected) {
                Path output = outDir.resolve(name);
                CacheFileManifest file = current.get(name);
                if (file.size() <= 0L
                        || !file.sha256().equals(sha256(output))
                        || Files.size(output) != file.size()
                        || Files.getLastModifiedTime(
                                output, LinkOption.NOFOLLOW_LINKS).toMillis()
                                != file.modifiedMillis()) {
                    return false;
                }
            }
            try {
                writeCompletionMarker(
                        outDir, fingerprint, scale, now, current);
            } catch (IOException refreshFailure) {
                // 内容已完整验证；只读安装目录不能刷新元数据时仍可安全命中。
                DynFontLog.error("刷新动态字体完成清单失败（忽略）", refreshFailure);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static boolean validManifestChecksum(List<String> lines) {
        String checksumLine = lines.get(lines.size() - 1);
        String prefix = "manifest-sha256\t";
        if (!checksumLine.startsWith(prefix)) {
            return false;
        }
        String expected = checksumLine.substring(prefix.length());
        if (!isSha256(expected)) {
            return false;
        }
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < lines.size() - 1; index++) {
            body.append(lines.get(index)).append('\n');
        }
        return expected.equals(sha256(
                body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean hasExpectedOutputs(Path outDir, double scale)
            throws IOException {
        if (!isPlainDirectory(outDir)) {
            return false;
        }
        for (String name : expectedOutputNames(scale)) {
            Path output = outDir.resolve(name);
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(output) <= 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private record CacheFileManifest(
            long size, long modifiedMillis, String sha256) {
    }

    private record CacheCandidate(
            Path directory, long lastModified, boolean inUse) {
    }

    @FunctionalInterface
    private interface CacheLockedAction<T> {
        T run(Path normalizedRoot) throws IOException;
    }

    private static final class CacheLease implements AutoCloseable {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;

        private CacheLease(
                Path directory, FileChannel channel, FileLock lock) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
        }

        private boolean isValid() {
            return channel.isOpen() && lock.isValid();
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 测试清理/进程退出路径；channel.close 仍会释放系统锁。
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // 同上。
            }
        }

        @Override
        public String toString() {
            return directory.toString();
        }
    }

    static void releaseCacheLeasesForTests() {
        synchronized (CACHE_PUBLICATION_MONITOR) {
            for (CacheLease lease : RETAINED_CACHE_LEASES.values()) {
                lease.close();
            }
            RETAINED_CACHE_LEASES.clear();
        }
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
     * 被当前或其它 JVM 共享锁认领的档绝不删除，因此多实例运行期间上限是软上限；
     * 实例退出后的下一次清理会恢复到硬上限。
     *
     * <p>清理纯属磁盘卫生，任何文件操作失败都只记日志不中断生成。
     */
    static void pruneCaches(Path cacheRoot, String fingerprint, String keep) {
        try {
            withCacheRootLock(cacheRoot, root -> {
                pruneCachesLocked(root, fingerprint, keep);
                return null;
            });
        } catch (IOException e) {
            DynFontLog.error("获取缓存清理锁失败（跳过清理）", e);
        }
    }

    private static void pruneCachesLocked(
            Path cacheRoot, String fingerprint, String keep) {
        String suffix = "-" + fingerprint;
        List<CacheCandidate> sameFingerprint = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheRoot)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (name.equals(keep)
                        || name.equals(CACHE_PUBLICATION_LOCK)) {
                    continue;
                }
                if (!isPlainDirectory(dir)) {
                    if (Files.isDirectory(dir)
                            || Files.isSymbolicLink(dir)) {
                        deleteCache(dir, "非普通目录/reparse point");
                    }
                    continue;
                }
                if (name.startsWith(".")) {
                    Long modified = lastModifiedOrNull(dir);
                    long age = modified == null
                            ? -1L : System.currentTimeMillis() - modified;
                    if (age >= TEMPORARY_CACHE_RETENTION_MILLIS
                            && !isCacheInUseLocked(dir)) {
                        deleteCache(dir, "过期临时产物");
                    }
                    continue;
                }
                if (name.endsWith(suffix)) {
                    // 非当前缩放档无需做 200+ MB 的定期 SHA 复验；真正命中该档时
                    // claimCompleteCache 会完整验证。这里只负责有界 LRU。
                    sameFingerprint.add(new CacheCandidate(
                            dir,
                            lastModifiedOrZero(dir),
                            isCacheInUseLocked(dir)));
                } else {
                    if (!isCacheInUseLocked(dir)) {
                        deleteCache(dir, "指纹失效");
                    }
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
        sameFingerprint.sort(Comparator.comparingLong(CacheCandidate::lastModified));
        for (CacheCandidate candidate : sameFingerprint) {
            if (over <= 0) {
                break;
            }
            if (candidate.inUse()) {
                continue;
            }
            deleteCache(candidate.directory(), "超出保留档数");
            over--;
        }
    }

    private static Long lastModifiedOrNull(Path dir) {
        try {
            return Files.getLastModifiedTime(
                    dir, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    private static long lastModifiedOrZero(Path dir) {
        Long modified = lastModifiedOrNull(dir);
        return modified == null ? 0L : modified;
    }

    /** 递归删除一个缓存档；失败只记日志（可能被杀软/资源管理器占用）。 */
    private static void deleteCache(Path dir, String reason) {
        try {
            deleteTreeStrict(dir);
            DynFontLog.info("清理缓存（" + reason + "）: " + dir.getFileName());
        } catch (Throwable t) {
            DynFontLog.error("清理缓存失败（忽略）: " + dir.getFileName(), t);
        }
    }

    private static boolean isPlainDirectory(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory()
                    && !attributes.isOther()
                    && !attributes.isSymbolicLink();
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    // ── 输入指纹：有域元组(spec + chars/dll/字体包)；scale 作为目录/清单独立维度 ──

    /**
     * 输入指纹：spec 版本 + data 包（含全部 TTF 与 kerning 表）/chars.txt/dll 内容。
     *
     * <p><b>不含 scale</b> —— scale 体现在目录名前缀里。这样同一份安装的所有缩放档
     * 共享一个指纹，{@link #pruneCaches} 才能识别并清空全部失效档（见其注释）。
     */
    private static String inputFingerprint(Path typefacePack, Path charsFile, Path dll)
            throws Exception {
        return framedInputFingerprint(typefacePack, charsFile, dll);
    }

    /**
     * 对输入元组做有域、有长度的编码。文件名与内容不能直接拼接：例如
     * {@code ("a", "Xb"), ("c", "Y")} 与
     * {@code ("a", "X"), ("bc", "Y")} 的裸拼接完全相同。
     */
    static String framedInputFingerprint(Path... inputs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 1);
        updateFramedUtf8(digest, "dynfont-input-fingerprint");
        digest.update((byte) 2);
        updateFramedUtf8(digest, SPEC_VERSION);
        digest.update((byte) 3);
        updateLong(digest, inputs.length);
        byte[] buf = new byte[65536];
        for (Path input : inputs) {
            digest.update((byte) 4);
            updateFramedUtf8(digest, input.getFileName().toString());
            long expectedLength = Files.size(input);
            long expectedModified = Files.getLastModifiedTime(
                    input, LinkOption.NOFOLLOW_LINKS).toMillis();
            updateLong(digest, expectedLength);
            // 流式读：data 包 15+ MB，一次性 readAllBytes 会在 G1 下产生 humongous 分配
            long total = 0L;
            try (InputStream is = Files.newInputStream(input)) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    total += n;
                }
            }
            if (total != expectedLength
                    || Files.size(input) != expectedLength
                    || Files.getLastModifiedTime(
                            input, LinkOption.NOFOLLOW_LINKS).toMillis()
                            != expectedModified) {
                throw new IOException("动态字体指纹计算期间输入发生变化: " + input);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static void updateFramedUtf8(
            MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateLong(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            digest.update((byte) (value >>> shift));
        }
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
        if (scale < 1.0) {
            scale = 1.0;
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

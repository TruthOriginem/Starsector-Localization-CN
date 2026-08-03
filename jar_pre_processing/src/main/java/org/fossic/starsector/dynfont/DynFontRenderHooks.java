package org.fossic.starsector.dynfont;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染期高清字体切换（SSO 式，{@code RendererDynFontPatch} 注入调用）。
 *
 * <p>游戏主渲染器 render 入口（{@code Õ00000()V}，纹理 bind 之前）先经
 * {@link #resolveFont}：若当前 font 是我们接管的 8 套矢量字体之一且存在
 * {@code {name}_hd} 高清套（screenScale&gt;1 时 native 双包生成），把渲染器的
 * font 字段换成高清套。requestedFontSize 保持 1x 逻辑字号不变，引擎自身的
 * {@code scale = requested / nominal}（高清套 nominal = round(1x×s)）自动完成
 * 1/s 补偿——quad 仍是 1x 逻辑尺寸，经几何变换层 ×s 放大后物理 1:1 采样高清
 * 纹理。布局层（launcher、直接读度量的组件）只见原路径 1x 字体，不受影响。
 *
 * <p>一致性关键（对照 SSOptimizer 实证结论）：切换必须**整齐划一且尽早**——
 * scale 启动定死、映射一经建立不变，且**全部套在同一时刻切换**
 * （{@link #preloadAllHd}）。否则先渲染的文本会把 1x 字体的 UV 烘焙进
 * display list，之后换纹理即撕裂（方案 A 碎块的结构性根因就是这个漂移窗口）。
 *
 * <p>launcher 阶段不切换：其 GL context 与游戏不同，此时加载的纹理 id 在游戏
 * 中失效。门控见 {@link DynFontOverrides#isGameContextReady()}——判据落在启动
 * 读条阶段。launcher 固定使用 1x 基础包。
 *
 * <p>任何异常永久降级为不切换（原版行为），绝不影响渲染热路径。
 */
public final class DynFontRenderHooks {
    /**
     * 参与高清切换的套。victor 系也在列：游戏纹理一律 GL_LINEAR（字节码实证无
     * NEAREST），1x 像素字图集被 bilinear 放大必糊，hd 套（strike 整数 k 倍
     * 逐像素放大）采样密度足够才锐利。k 与 screenScale 解耦、全 scale 覆盖，
     * 故 victor hd 恒存在；仍按 hasClaim 判定，缺失时自动回退原字体。
     */
    private static final Set<String> HD_ELIGIBLE = Set.of(
            "insignia15ltaa", "insignia21ltaa", "insignia25ltaa",
            "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
            "orbitron24aa", "orbitron24aabold",
            "victor10", "victor14", "victor16");

    /** font 实例 → 渲染应使用的实例（自身或高清套）；建立后不变。 */
    private static final Map<Object, Object> RESOLVED = new ConcurrentHashMap<>();
    /**
     * hd 实例 → {nominal_hd, k}，k = nominal_hd / nominal_1x 即该套字形的实际
     * 膨胀因子。既是 adjustSize 的归一化依据，也让热路径不必反射读 nominal。
     */
    private static final Map<Object, float[]> HD_INFO = new ConcurrentHashMap<>();

    private static volatile Method managerGet;       // A.D.Ò00000(String) → F（纯 map 查找）
    private static volatile Method managerRegister;  // A.D.super(String,String)（加载并注册）
    private static volatile Field managerMap;        // A.D.Ò00000 static HashMap<String,F>
    private static volatile Method fontNominal;      // F.Õ00000() → int（info size）
    private static volatile boolean preloaded;
    /** hd 图集是否已在读条阶段喂进显存，见 {@link #warmUpHdTextures()}。 */
    private static volatile boolean warmedUp;
    private static volatile boolean broken;
    private static volatile boolean failureLogged;

    private DynFontRenderHooks() {
    }

    /** render 入口注入点 ①：返回渲染应使用的 font（原样或高清套）。 */
    public static Object resolveFont(Object font) {
        try {
            if (broken || font == null) {
                return font;
            }
            Object hit = RESOLVED.get(font);
            if (hit != null) {
                return hit;
            }
            if (!DynFontOverrides.isEnabled()) {
                return font;
            }
            if (!DynFontOverrides.isGameContextReady()) {
                // launcher 阶段：其 GL context 与游戏不同，此时加载的 hd 纹理
                // 在游戏中会失效。判据落在启动读条阶段，见 isGameContextReady。
                return font;
            }
            // 游戏 API 就绪后复检缩放：launcher 与游戏同进程，launcher 阶段
            // 定死的 scale 可能已被用户改过（详见 recheckScaleForGame）
            DynFontOverrides.recheckScaleForGame();
            if (!DynFontOverrides.isHdReady()
                    || DynFontOverrides.detectedScreenScale() <= 1.001) {
                // 重生成进行中：本帧不建立映射，下一帧再试（避免加载到旧产物）
                return font;
            }
            preloadAllHd(font.getClass());
            return resolveSlow(font);
        } catch (Throwable t) {
            fail(t);
            return font;
        }
    }

    /**
     * render 入口注入点 ②（resolveFont 之后）：requestedFontSize 归一化。
     *
     * <p>少数调用方按「当前 font 的 nominal × 系数」派生字号（如地图区域标签的
     * {@code scaleNameWithZoom} 分支）。font 被换成 hd 后它们读到的是
     * {@code nominal_hd}，引擎算出的绘制 scale 不变，quad 遂恒偏大 —— 此处把
     * 这类请求归一回 1x 逻辑字号。
     *
     * <p><b>除数必须是 k 而非 screenScale。</b>hd 字形的膨胀因子是
     * {@code k = nominal_hd / nominal_1x}：矢量套取 {@code round(n×s)/n}、像素套
     * 取 {@code max(2, ceil(s−0.1))}，**只有整数 s 且矢量字时才恰好等于 s**。
     * 用 s 会让判据在常见档位永不成立（如 s=2.0 时地图标签恒偏大 2 倍）。
     */
    public static float adjustSize(Object font, float requested) {
        try {
            if (broken || font == null) {
                return requested;
            }
            float[] info = HD_INFO.get(font);
            if (info == null || !Float.isFinite(requested) || requested <= 0f) {
                return requested;
            }
            // info[0] = nominal_hd，info[1] = k；判据识别「按 nominal_hd 派生」的请求
            if (Math.abs(requested - info[0]) <= 0.75f * info[1]) {
                return requested / info[1];
            }
            return requested;
        } catch (Throwable t) {
            fail(t);
            return requested;
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 首次切换时一次性加载全部 hd 套（幂等）。
     *
     * <p>逐套懒加载会让每套字体第一次出现时同步做 fnt 解析 + PNG 解码 + 纹理
     * 上传，表现为反复卡顿；更严重的是**切换时机分散**——切换前渲染的长文本
     * 已把 1x 字体的 UV 烘焙进 display list（GLListManager 缓存 len×(copies+1)&gt;20
     * 的文本），之后 font 换成 hd 并 bind 新纹理，replay 时旧 UV 打在新纹理上
     * 就是乱码（与方案 A 碎块同一机制）。
     *
     * <p>故在第一次可切换的时刻把全部 hd 一次装完：此后所有文本自始至终用 hd，
     * 不存在混用两套纹理的 display list。
     *
     * <p>这一帧本身的开销很小——图集的解码上传已由 {@link #warmUpHdTextures()} 在
     * 读条阶段完成，此处两侧 {@code loadOrRegister} 都命中 BitmapFontManager 缓存，
     * 只剩建映射。预热未能生效时（反射失败等）本方法仍会走完整路径，届时退化为
     * 首帧集中卡顿一次，但结果依旧正确。
     */
    private static synchronized void preloadAllHd(Class<?> fontClass) {
        if (preloaded) {
            return;
        }
        preloaded = true;
        long t0 = System.nanoTime();
        int expected = 0;
        int mapped = 0;
        int failed = 0;
        try {
            ensureReflection(fontClass);
        } catch (Throwable t) {
            DynFontLog.error("反射初始化失败，放弃高清切换", t);
            broken = true;
            return;
        }
        for (String stem : HD_ELIGIBLE) {
            if (!DynFontOverrides.hasClaim(stem + "_hd.fnt")) {
                continue;  // 该套无产物（如非整数 scale 的像素字），按设计跳过
            }
            expected++;
            // 逐套隔离：一套加载失败不能带走其余套。若让异常逃出循环，剩下的
            // 套会留在 1x 并在后续某帧经懒路径分散切换，正是铁律②禁止的模式。
            try {
                Object hd = loadOrRegister("graphics/fonts/" + stem + "_hd.fnt");
                if (hd == null) {
                    failed++;
                    continue;
                }
                // 主动注册原字体：不能等游戏自己加载——注册表/请求记录何时完整
                // 都取决于加载顺序，实测预加载时可能只有 4/11 套可见，其余就会
                // 退回懒路径、切换时机再次分散（撕裂复现）。故路径直接由已知的
                // 规格名构造（保留原始大小写：manager 以路径为 key，大小写不同
                // 会造出第二个 font 对象）；有实际请求记录时优先用它。
                String basePath = DynFontOverrides.exactFntPath(stem);
                if (basePath == null) {
                    String originalName = DynFontOverrides.ownedFontStem(stem);
                    if (originalName == null) {
                        failed++;
                        continue;
                    }
                    basePath = "graphics/fonts/" + originalName + ".fnt";
                }
                Object base = loadOrRegister(basePath);
                if (base == null || base == hd) {
                    failed++;
                    continue;
                }
                RESOLVED.put(hd, hd);
                RESOLVED.put(base, hd);
                registerHdInfo(hd, base);
                mapped++;
            } catch (Throwable t) {
                failed++;
                DynFontLog.error("高清套预加载失败: " + stem, t);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (failed > 0) {
            // 宁可全 1x，也不要半 hd 半 1x：后者会让已烘焙的 display list 与
            // 新纹理错配（撕裂），且比整体降级难诊断得多。
            RESOLVED.clear();
            HD_INFO.clear();
            broken = true;
            DynFontLog.info("预加载高清套: " + mapped + "/" + expected
                    + " 套成功，" + failed + " 套失败 —— 整体回退 1x 以避免撕裂，耗时 "
                    + ms + " ms");
            return;
        }
        DynFontLog.info("预加载高清套: " + mapped + "/" + expected
                + " 套已建立映射，耗时 " + ms + " ms");
    }

    private static synchronized Object resolveSlow(Object font) throws Exception {
        Object hit = RESOLVED.get(font);
        if (hit != null) {
            return hit;
        }
        ensureReflection(font.getClass());

        String stem = ownedStemOf(font);
        if (stem == null || !DynFontOverrides.hasClaim(stem + "_hd.fnt")) {
            RESOLVED.put(font, font);
            return font;
        }

        String hdPath = "graphics/fonts/" + stem + "_hd.fnt";
        Object hd = loadOrRegister(hdPath);
        if (hd == null || hd == font) {
            DynFontLog.info("高清套加载失败，保持原字体: " + hdPath);
            RESOLVED.put(font, font);
            return font;
        }
        RESOLVED.put(hd, hd);
        RESOLVED.put(font, hd);
        registerHdInfo(hd, font);
        DynFontLog.info("渲染切换高清套: " + stem + " -> " + hdPath);
        return hd;
    }

    /**
     * 登记 hd 套的 nominal 与膨胀因子 k（= nominal_hd / nominal_1x）。
     *
     * <p>k 由两个 font 实例的 nominal 实测得出，而非从 screenScale 推算——native
     * 侧对矢量套与像素套用的是两套不同的 k 选取规则，只有实测才不会算错。
     * 反射在此处一次性完成，热路径的 adjustSize 只做一次 map 查找。
     */
    private static void registerHdInfo(Object hd, Object base) {
        try {
            int nominalHd = Math.abs((Integer) fontNominal.invoke(hd));
            int nominal1x = Math.abs((Integer) fontNominal.invoke(base));
            if (nominalHd > 0 && nominal1x > 0) {
                HD_INFO.put(hd, new float[]{nominalHd, (float) nominalHd / nominal1x});
            }
        } catch (Throwable t) {
            // 读不到 nominal 只是让该套不做字号归一化，不影响高清切换本身
            DynFontLog.error("读取 hd 套 nominal 失败（该套跳过字号归一化）", t);
        }
    }

    /**
     * 读条阶段预热：把全部 hd 图集喂进显存，<b>不</b>建立映射。
     *
     * <p>由 {@code DynFontOverrides} 在判据翻转处（{@code ResourceLoaderState.init}
     * 内）调用。图集解码上传是这套机制里唯一的大额开销（实测数秒），放在读条里只是
     * 让读条条多走一会儿；留到首帧就是主菜单上一次硬卡顿。
     *
     * <p>此处只注册 hd 路径，原字体交由游戏读条自己注册，两边互不覆盖。映射仍由首帧
     * 的 {@link #preloadAllHd} 建立——那时两侧 {@code loadOrRegister} 全部命中
     * BitmapFontManager 缓存，开销可忽略。
     *
     * <p>预热失败不致命：首帧的预加载会照常走完整路径，只是卡顿依旧。
     */
    static synchronized void warmUpHdTextures() {
        if (broken || warmedUp) {
            return;
        }
        warmedUp = true;
        long t0 = System.nanoTime();
        int warmed = 0;
        int expected = 0;
        try {
            ensureReflection(Class.forName("com.fs.graphics.A.F"));
        } catch (Throwable t) {
            DynFontLog.error("读条阶段反射初始化失败（高清套改由首帧加载）", t);
            return;
        }
        for (String stem : HD_ELIGIBLE) {
            if (!DynFontOverrides.hasClaim(stem + "_hd.fnt")) {
                continue;  // 该套无产物（如非整数 scale 的像素字），按设计跳过
            }
            expected++;
            try {
                if (loadOrRegister("graphics/fonts/" + stem + "_hd.fnt") != null) {
                    warmed++;
                }
            } catch (Throwable t) {
                DynFontLog.error("读条阶段预热高清套失败: " + stem, t);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        DynFontLog.info("读条阶段预热高清套: " + warmed + "/" + expected
                + " 套纹理已就位，耗时 " + ms + " ms");
    }

    /** 取字体；未注册则先注册再取（走资源流拦截，命中我们的产物）。 */
    private static Object loadOrRegister(String path) throws Exception {
        Object font = managerGet.invoke(null, path);
        if (font != null) {
            return font;
        }
        managerRegister.invoke(null, path, path);
        return managerGet.invoke(null, path);
    }

    /** 注册路径 → 小写 stem（{@code graphics/fonts/insignia15LTaa.fnt} → {@code insignia15ltaa}）。 */
    private static String stemOf(String registryKey) {
        String name = registryKey.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        String base = (slash >= 0 ? name.substring(slash + 1) : name).toLowerCase(Locale.ROOT);
        return base.endsWith(".fnt") ? base.substring(0, base.length() - 4) : base;
    }

    /** 反查 font 实例在游戏字体管理器中的注册名，命中接管套时返回小写 stem。 */
    private static String ownedStemOf(Object font) throws Exception {
        Map<?, ?> fonts = (Map<?, ?>) managerMap.get(null);
        for (Map.Entry<?, ?> entry : fonts.entrySet()) {
            if (entry.getValue() == font && entry.getKey() instanceof String key) {
                String stem = stemOf(key);
                return HD_ELIGIBLE.contains(stem) ? stem : null;
            }
        }
        return null;
    }

    private static void ensureReflection(Class<?> fontClass) throws Exception {
        if (managerGet != null) {
            return;
        }
        ClassLoader loader = fontClass.getClassLoader();
        Class<?> manager = Class.forName("com.fs.graphics.A.D", false, loader);
        // Ò00000(String)→F：BitmapFontManager.getFont（纯 map.get，无副作用）
        Method get = manager.getDeclaredMethod("Ò" + "00000", String.class);
        get.setAccessible(true);
        // super(String,String)：加载 .fnt（经资源流拦截命中我们的产物）并注册
        Method register = manager.getDeclaredMethod("super", String.class, String.class);
        register.setAccessible(true);
        // Ò00000：static HashMap<String,F> 注册表（反查实例 → 注册名）
        Field map = manager.getDeclaredField("Ò" + "00000");
        map.setAccessible(true);
        // F.Õ00000()→int：nominal（info size）
        Method nominal = fontClass.getDeclaredMethod("Õ" + "00000");
        nominal.setAccessible(true);

        managerGet = get;
        managerRegister = register;
        managerMap = map;
        fontNominal = nominal;
    }

    /** 建映射期间的部分状态（HashMap 快照类）损坏无碍：永久降级为不切换。 */
    private static void fail(Throwable t) {
        broken = true;
        if (!failureLogged) {
            failureLogged = true;
            try {
                DynFontLog.error("渲染字体切换异常，已永久降级为原版行为", t);
            } catch (Throwable ignored) {
                // 连日志都失败时彻底静默
            }
        }
    }

    /** 供测试/诊断：清空映射（游戏内不会调用）。 */
    static void resetForTests() {
        RESOLVED.clear();
        HD_INFO.clear();
        broken = false;
        failureLogged = false;
        warmedUp = false;
        preloaded = false;
    }
}

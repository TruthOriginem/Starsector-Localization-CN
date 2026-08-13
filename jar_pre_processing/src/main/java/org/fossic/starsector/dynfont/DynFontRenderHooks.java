package org.fossic.starsector.dynfont;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 游戏原生 BitmapFont renderer 的精确代理字体入口。 */
public final class DynFontRenderHooks {
    private static final Set<String> MANAGED = Set.of(
            "insignia15ltaa", "insignia21ltaa", "insignia25ltaa",
            "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
            "orbitron24aa", "orbitron24aabold",
            "victor10", "victor14", "victor16");

    /** 基础/代理实例 -> 代理实例；只有全套映射成功后才整体发布。 */
    private static final Map<Object, Object> RESOLVED = new ConcurrentHashMap<>();
    /** 代理实例 -> {proxy nominal, base nominal, proxy/base multiplier}。 */
    private static final Map<Object, float[]> PROXY_INFO = new ConcurrentHashMap<>();

    private static volatile Method managerGet;
    private static volatile Method managerRegister;
    private static volatile Field managerMap;
    private static volatile Method fontNominal;
    private static volatile boolean warmedUp;
    private static volatile boolean mappedAll;
    private static volatile boolean broken;
    private static volatile boolean failureLogged;

    private DynFontRenderHooks() {
    }

    /** renderer 的字体 setter 注入点：游戏 context 内把已知基础字体换为 exact 代理。 */
    public static Object resolveFont(Object font) {
        try {
            if (font == null || broken) {
                return font;
            }
            Object hit = RESOLVED.get(font);
            if (hit != null) {
                return hit;
            }
            if (!DynFontOverrides.isEnabled() || !DynFontOverrides.isGameContextReady()) {
                return font;
            }
            DynFontOverrides.recheckScaleForGame();
            if (!DynFontOverrides.isProxyReady()) {
                return font;
            }
            mapAll(font.getClass());
            hit = RESOLVED.get(font);
            return hit != null ? hit : resolveLate(font);
        } catch (Throwable t) {
            fail("精确代理字体映射异常，已永久回退基础字体", t);
            return font;
        }
    }

    /** BitmapFont 公开 getter：代理对外暴露逻辑 nominal，未接管字体不变。 */
    public static int logicalNominal(Object font, int rawNominal) {
        try {
            float[] info = PROXY_INFO.get(font);
            return info == null ? rawNominal : Math.round(info[1]);
        } catch (Throwable t) {
            fail("精确代理逻辑 nominal 读取异常", t);
            return rawNominal;
        }
    }

    /** 供 quad scope/display-list 门控使用；热路径只有一次并发 map 查询。 */
    public static boolean isProxyFont(Object font) {
        return font != null && PROXY_INFO.containsKey(font);
    }

    /**
     * 游戏读条阶段预载全部 exact 代理纹理并校验 `.dfnt`。这里只加载代理，不加载
     * 基础字体：首个基础 FNT 此刻仍在 manager.register 的外层调用中，递归加载自己
     * 会无限重入。基础到代理的原子映射在第一个 renderer 字体 setter 里完成。
     */
    static synchronized void warmUpExactTextures() {
        if (warmedUp || broken) {
            return;
        }
        long started = System.nanoTime();
        int loaded = 0;
        try {
            ensureReflection(Class.forName("com.fs.graphics.A.F"));
            for (String stem : MANAGED) {
                validateDfnt(stem);
                Object proxy = loadOrRegister(exactPath(stem));
                if (proxy == null) {
                    throw new IllegalStateException("代理字体注册后仍为空: " + stem);
                }
                loaded++;
            }
            warmedUp = true;
            long ms = (System.nanoTime() - started) / 1_000_000;
            DynFontLog.info("读条阶段预热 exact 代理: " + loaded + "/" + MANAGED.size()
                    + " 套纹理及度量已校验，耗时 " + ms + " ms");
        } catch (Throwable t) {
            fail("exact 代理预热失败（整套回退基础字体）", t);
        }
    }

    /** 全部基础字体和代理都到位后一次性发布映射，避免半套切换。 */
    private static synchronized void mapAll(Class<?> fontClass) throws Exception {
        if (mappedAll || broken) {
            return;
        }
        if (!warmedUp) {
            warmUpExactTextures();
        }
        if (broken) {
            return;
        }
        ensureReflection(fontClass);

        Map<Object, Object> resolved = new HashMap<>();
        Map<Object, float[]> info = new HashMap<>();
        for (String lowerStem : MANAGED) {
            String originalName = DynFontOverrides.ownedFontStem(lowerStem);
            if (originalName == null) {
                throw new IllegalStateException("未知动态字体规格: " + lowerStem);
            }
            String basePath = DynFontOverrides.exactFntPath(lowerStem);
            if (basePath == null) {
                basePath = "graphics/fonts/" + originalName + ".fnt";
            }
            Object base = loadOrRegister(basePath);
            Object proxy = loadOrRegister(exactPath(lowerStem));
            if (base == null || proxy == null || base == proxy) {
                throw new IllegalStateException("基础/代理字体无效: " + lowerStem);
            }
            int baseRawNominal = (Integer) fontNominal.invoke(base);
            int proxyRawNominal = (Integer) fontNominal.invoke(proxy);
            int baseNominal = Math.abs(baseRawNominal);
            int proxyNominal = Math.abs(proxyRawNominal);
            if (baseNominal <= 0 || proxyNominal <= baseNominal) {
                throw new IllegalStateException("代理 nominal 无效: " + lowerStem
                        + " base=" + baseNominal + " proxy=" + proxyNominal);
            }
            float multiplier = (float) proxyNominal / baseNominal;
            resolved.put(base, proxy);
            resolved.put(proxy, proxy);
            // 公开 getter 保留基础 FNT info size 的原始符号；原 renderer
            // 的 setter 本就负号后取 abs，mod 则可能直接观测该值。
            info.put(proxy, new float[]{proxyRawNominal, baseRawNominal, multiplier});
        }

        RESOLVED.putAll(resolved);
        PROXY_INFO.putAll(info);
        mappedAll = true;
        DynFontLog.info("精确代理映射已原子启用: " + MANAGED.size()
                + " 套，metricScale=64, atlasScale="
                + DynFontOverrides.detectedScreenScale());
    }

    private static void validateDfnt(String stem) throws Exception {
        Path path = DynFontOverrides.claimedPath(stem + ".dfnt");
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException("缺少精确度量: " + stem + ".dfnt");
        }
        ExactFontData data = ExactFontData.parse(Files.readAllBytes(path));
        double expected = DynFontOverrides.detectedScreenScale();
        if (Math.abs(data.atlasScreenScale() - expected) > 0.001) {
            throw new IllegalStateException("dfnt scale 不匹配: " + stem + " got="
                    + data.atlasScreenScale() + " expected=" + expected);
        }
    }

    private static String exactPath(String lowerStem) {
        return "graphics/fonts/" + lowerStem.toLowerCase(Locale.ROOT) + "_exact.fnt";
    }

    private static Object loadOrRegister(String path) throws Exception {
        Object font = managerGet.invoke(null, path);
        if (font == null) {
            managerRegister.invoke(null, path, path);
            font = managerGet.invoke(null, path);
        }
        return font;
    }

    /** manager 在映射发布后重新注册基础路径时，按当前注册表身份补上新实例。 */
    private static Object resolveLate(Object font) throws Exception {
        Map<?, ?> fonts = (Map<?, ?>) managerMap.get(null);
        for (Map.Entry<?, ?> entry : fonts.entrySet()) {
            if (entry.getValue() != font || !(entry.getKey() instanceof String key)) {
                continue;
            }
            String normalized = key.replace('\\', '/').toLowerCase(Locale.ROOT);
            int slash = normalized.lastIndexOf('/');
            String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (!name.endsWith(".fnt") || name.endsWith("_exact.fnt")) {
                return font;
            }
            String stem = name.substring(0, name.length() - 4);
            if (!MANAGED.contains(stem)) {
                return font;
            }
            Object proxy = managerGet.invoke(null, exactPath(stem));
            if (proxy != null && PROXY_INFO.containsKey(proxy)) {
                RESOLVED.put(font, proxy);
                return proxy;
            }
            return font;
        }
        return font;
    }

    private static synchronized void ensureReflection(Class<?> fontClass) throws Exception {
        if (managerGet != null) {
            return;
        }
        ClassLoader loader = fontClass.getClassLoader();
        Class<?> manager = Class.forName("com.fs.graphics.A.D", false, loader);
        Method get = manager.getDeclaredMethod("Ò" + "00000", String.class);
        Method register = manager.getDeclaredMethod("super", String.class, String.class);
        Field map = manager.getDeclaredField("Ò" + "00000");
        Method nominal = fontClass.getDeclaredMethod("$dynfontRawNominal");
        get.setAccessible(true);
        register.setAccessible(true);
        map.setAccessible(true);
        nominal.setAccessible(true);
        managerGet = get;
        managerRegister = register;
        managerMap = map;
        fontNominal = nominal;
    }

    private static void fail(String message, Throwable t) {
        broken = true;
        RESOLVED.clear();
        PROXY_INFO.clear();
        if (!failureLogged) {
            failureLogged = true;
            try {
                DynFontLog.error(message, t);
            } catch (Throwable ignored) {
                // 日志也不可影响游戏
            }
        }
    }

    static void resetForTests() {
        RESOLVED.clear();
        PROXY_INFO.clear();
        warmedUp = false;
        mappedAll = false;
        broken = false;
        failureLogged = false;
    }

    static void registerProxyForTests(Object proxy, float proxyNominal,
                                      float baseNominal) {
        PROXY_INFO.put(proxy, new float[]{proxyNominal, baseNominal,
                proxyNominal / baseNominal});
    }
}

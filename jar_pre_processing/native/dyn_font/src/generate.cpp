#include "dynfont.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <exception>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <future>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

#include "chars_file.h"
#include "composer.h"
#include "dfnt_writer.h"
#include "fnt_writer.h"

namespace dynfont {

// ── 日志（CLI 直通 stderr；JNI 模式 setLogFile 后写文件，每次生成重写）──────
namespace {

std::mutex g_logMutex;
std::wstring g_logPath;

bool writeBinary(const std::filesystem::path& path, const void* data, size_t size) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) {
        return false;
    }
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return out.good();
}

/*
 * 产物自检 —— 写盘前拦住"能生成但游戏用不了/观感已退化"的结果。
 *
 * 设计契约是「任何异常静默降级为原版位图字体」，而这里的两类问题原本都会以
 * rc=0 通过、被 Java 侧打上 .complete 缓存下来，把风险带进渲染热路径。
 *
 * ① 多页：BMFont 的 common 行只有一组 scaleW/H，而游戏的 fnt 解析器更是
 *    **硬编码只读一行 page**（反编译实证），多页产物会在解析 chars 行时
 *    数组越界抛异常 —— exact 代理根本加载不进来。分页在本项目里等价于产物不可用。
 * ② 基线未对齐：西文相对中文的垂直位置退化，各套方向幅度还不一致。
 * ③ 等宽数字契约：启用该规格的 Insignia/Victor 必须保证 0-9 的整数/精确
 *    计步严格一致，且过滤任何数字字偶距；Orbitron 保留来源字体的自然度量。
 */
bool validatePack(const ComposedFont& font, const char* name) {
    if (font.pages.size() > 1) {
        logLine("[error] %s: 图集分了 %zu 页，而游戏 fnt 解析器只支持单页 —— "
                "请缩减 chars.txt 或降低界面缩放", name, font.pages.size());
        return false;
    }
    if (font.pages.empty() || font.glyphs.empty()) {
        logLine("[error] %s: 产物为空（%zu 页 / %zu 字形）", name,
                font.pages.size(), font.glyphs.size());
        return false;
    }
    if (!font.baselineAligned) {
        logLine("[error] %s: 基线对齐未执行（基准字形 H/舰 渲染失败）", name);
        return false;
    }

    if (font.tabularDigits) {
        int digitPen = -1;
        double preciseDigitPen = -1;
        for (uint32_t d = '0'; d <= '9'; d++) {
            auto it = font.glyphs.find(d);
            if (it == font.glyphs.end()) {
                logLine("[error] %s: 等宽数字自检缺少字符 %c", name,
                        static_cast<char>(d));
                return false;
            }
            int pen = it->second.xoffset + it->second.xadvance;
            double precisePen = it->second.preciseAdvance;
            if (digitPen < 0) {
                digitPen = pen;
                preciseDigitPen = precisePen;
            } else if (pen != digitPen || !std::isfinite(precisePen)
                    || std::abs(precisePen - preciseDigitPen) > 1e-6) {
                logLine("[error] %s: 数字 %c 非等宽（整数 %d/%d，精确 %.6f/%.6f）",
                        name, static_cast<char>(d), pen, digitPen,
                        precisePen, preciseDigitPen);
                return false;
            }
        }
        auto isDigit = [](uint32_t ch) { return ch >= '0' && ch <= '9'; };
        for (const auto& [first, second, amount] : font.kernings) {
            if (isDigit(first) || isDigit(second)) {
                logLine("[error] %s: 整数 kerning 仍包含数字字偶 %u/%u=%d",
                        name, first, second, amount);
                return false;
            }
        }
        for (const auto& [first, second, amount] : font.preciseKernings) {
            if (isDigit(first) || isDigit(second)) {
                logLine("[error] %s: 精确 kerning 仍包含数字字偶 %u/%u=%.6f",
                        name, first, second, amount);
                return false;
            }
        }
    }

    // 接近单页上限时提前预警。撞线才报错对玩家没有可操作性——他扩字表或调高
    // 缩放时就该知道快到极限了，而不是等到某天字体整体降级才发现。
    int used = 0;
    for (const auto& [id, g] : font.glyphs) {
        used = std::max(used, g.dstY + g.height());
    }
    if (used > MAX_PAGE_H * 4 / 5) {
        logLine("[warning] %s: 图集纵向已用 %d/%d（%d%%），接近单页上限；"
                "继续扩充 chars.txt 或调高界面缩放将导致分页，届时本套会整体"
                "降级为原版位图字体", name, used, MAX_PAGE_H, used * 100 / MAX_PAGE_H);
    }
    return true;
}

/* 写出一套 fnt + 全部图集 PNG（outName 同时决定页文件名前缀）。 */
bool writePack(const std::filesystem::path& outDir, const std::string& outName,
               const ComposedFont& font) {
    if (!validatePack(font, outName.c_str())) {
        return false;
    }
    FntInfo info{font.face, font.infoSize, font.lineHeight, font.base,
                 font.smooth, font.aa};
    std::string fnt = buildFntText(outName, info, font.glyphs, font.pages, font.kernings);
    if (!writeBinary(outDir / (outName + ".fnt"), fnt.data(), fnt.size())) {
        logLine("[error] %s: .fnt 写入失败", outName.c_str());
        return false;
    }
    for (size_t i = 0; i < font.pages.size(); i++) {
        std::vector<uint8_t> png;
        if (!encodePagePng(font.pages[i], png)) {
            logLine("[error] %s: PNG 编码失败 (page %zu)", outName.c_str(), i);
            return false;
        }
        std::string pngName = outName + "_" + std::to_string(i) + ".png";
        if (!writeBinary(outDir / pngName, png.data(), png.size())) {
            logLine("[error] %s: PNG 写入失败 (%s)", outName.c_str(), pngName.c_str());
            return false;
        }
    }
    return true;
}

/* 写出供新渲染器读取的精确浮点度量；PNG 与同一份高清 ComposedFont 共用。 */
bool writeDfnt(const std::filesystem::path& outDir, const std::string& outName,
               double atlasScale, float baseNominal, const ComposedFont& font) {
    try {
        std::vector<uint8_t> data = buildDfnt(atlasScale, baseNominal, font);
        return writeBinary(outDir / (outName + ".dfnt"), data.data(), data.size());
    } catch (const std::exception& e) {
        logLine("[error] %s.dfnt 写出失败: %s", outName.c_str(), e.what());
        return false;
    }
}

constexpr int PROXY_METRIC_SCALE = 64;

/*
 * 写出游戏原生 renderer 使用的精确代理 FNT。PNG 已由 exact 套写出；代理只
 * 更换整数坐标系，不重复写纹理。游戏加载该 FNT 时仍会按 page 行请求同一张
 * {name}_exact_0.png。
 */
bool writeProxyFnt(const std::filesystem::path& outDir, const std::string& outName,
                   const ComposedFont& font) {
    try {
        std::string fnt = buildProxyFntText(outName, font, PROXY_METRIC_SCALE);
        return writeBinary(outDir / (outName + ".fnt"), fnt.data(), fnt.size());
    } catch (const std::exception& e) {
        logLine("[error] %s.fnt 代理写出失败: %s", outName.c_str(), e.what());
        return false;
    }
}

}  // namespace

bool isSupportedScreenScale(double scale) noexcept {
    return std::isfinite(scale) && scale >= 1.0 && scale <= 3.0;
}

void setLogFile(const std::wstring& path) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    g_logPath = path;
    if (!path.empty()) {
        std::ofstream reset(std::filesystem::path(path), std::ios::trunc);
    }
}

void logLine(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_logPath.empty()) {
        std::fprintf(stderr, "%s\n", buf);
    } else {
        std::ofstream out(std::filesystem::path(g_logPath), std::ios::app);
        out << buf << '\n';
    }
}

int generateAll(const GenerateConfig& config) {
    auto t0 = std::chrono::steady_clock::now();

    // JNI/CLI 都必须在进入 FreeType 和装箱计算前拒绝异常输入，避免浮点转整数
    // 未定义行为、尺寸溢出及 nextPow2 无法收敛。0.98a 启动器上限为 300%。
    if (!isSupportedScreenScale(config.scale)) {
        logLine("[error] screenScale 超出支持范围 [1.0, 3.0]: %.17g", config.scale);
        return 1;
    }

    std::vector<uint32_t> chars = loadCharsFile(config.charsPath);
    if (chars.empty()) {
        logLine("[error] chars 文件读取失败或为空");
        return 1;
    }

    std::vector<OutputSpec> specs;
    for (const OutputSpec& spec : builtinSpecs()) {
        if (config.only.empty() || config.only == spec.name) {
            specs.push_back(spec);
        }
    }
    if (specs.empty()) {
        logLine("[error] 没有匹配的输出规格: %s", config.only.c_str());
        return 1;
    }

    TypefacePack pack;
    if (!loadTypefacePack(config.typefacePath, pack)) {
        return 1;
    }

    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(config.outDir), ec);

    logLine("[info] 生成 %zu 套字体, scale=%.2f, chars=%zu", specs.size(), config.scale,
            chars.size());

    // 套间并行（FT_Library 线程封闭在 renderGlyphs 内部）
    std::vector<std::future<bool>> jobs;
    jobs.reserve(specs.size());
    for (const OutputSpec& spec : specs) {
        jobs.push_back(std::async(std::launch::async, [&, spec]() -> bool {
          // 异常不出线程：future 会把它存起来并在 job.get() 处重抛到 generateAll
          // 栈上，越过下面的 ok 汇总逻辑。单套失败不应带走其余 10 套。
          try {
            auto ts = std::chrono::steady_clock::now();
            std::filesystem::path outDir(config.outDir);

            // 基础包：永远是纯 1x 渲染，与 screenScale 无关。布局层（launcher、
            // 直接读 font 度量的组件）与游戏内的 quad 尺寸都以它为准。
            ComposedFont composed;
            if (!composeOutput(spec, 1.0, pack, chars, config.atlasWidth, composed)) {
                return false;
            }
            if (!writePack(outDir, composed.name, composed)) {
                return false;
            }

            // 精确套始终存在。100% 时可复用基础栅格化结果；高缩放按真正的
            // screenScale 独立栅格化，不经过整数 nominal 量化。
            ComposedFont exact;
            if (config.scale <= 1.001) {
                exact = composed;
            } else if (!composeOutput(spec, config.scale, pack, chars,
                                      config.atlasWidth, exact)) {
                return false;
            }
            const std::string exactName = std::string(spec.name) + "_exact";
            if (!writePack(outDir, exactName, exact)) {
                return false;
            }
            // 覆盖刚才仅用于写 PNG 的普通 exact FNT，改为 64 倍虚拟度量代理。
            // 纹理 UV 与几何/nominal 的同倍率在原版 renderer 内精确抵消。
            if (!writeProxyFnt(outDir, exactName, exact)) {
                return false;
            }
            if (!writeDfnt(outDir, spec.name, config.scale,
                           static_cast<float>(std::abs(spec.infoSize)), exact)) {
                return false;
            }

            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now() - ts).count();
            logLine("[done] %-20s %4zu chars, %zu page(s) %dx%d, %lld ms", spec.name,
                    composed.glyphs.size(), composed.pages.size(),
                    composed.pages.empty() ? 0 : composed.pages[0].w,
                    composed.pages.empty() ? 0 : composed.pages[0].h,
                    static_cast<long long>(ms));
            return true;
          } catch (const std::exception& e) {
            logLine("[error] %s: 生成异常: %s", spec.name, e.what());
            return false;
          } catch (...) {
            logLine("[error] %s: 生成未知异常", spec.name);
            return false;
          }
        }));
    }

    bool ok = true;
    for (auto& job : jobs) {
        ok = job.get() && ok;
    }

    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::steady_clock::now() - t0).count();
    logLine("[info] 全部完成: %s, 总耗时 %lld ms", ok ? "成功" : "有失败",
            static_cast<long long>(totalMs));
    return ok ? 0 : 1;
}

}  // namespace dynfont

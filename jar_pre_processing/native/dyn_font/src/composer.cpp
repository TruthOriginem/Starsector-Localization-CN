#include "composer.h"

#include <algorithm>
#include <cmath>
#include <optional>

#include "chars_file.h"
#include "kerning.h"

namespace dynfont {

// ── 最终参数表（文档「字体参数」；数值与 fnt_composer demo_dyn.py 逐项对应）──
namespace {

constexpr const char* LTE = "lte50549.ttf";
constexpr const char* LANTING = "方正兰亭中粗黑.ttf";  // 方正兰亭中粗黑.ttf
constexpr const char* ORBITRON_VF = "Orbitron-VariableFont_wght.ttf";
// 锐字逼格青春粗黑体简2.0.TTF
constexpr const char* RUIZI =
    "锐字逼格青春粗黑体简2.0.TTF";

SourceSpec light(const char* file, double size, double y, double x, double bold,
                 bool latin, int supersample = 4) {
    SourceSpec s;
    s.file = file;
    s.size = size;
    s.mode = RenderMode::LightAA;
    s.supersample = supersample;
    s.yAdjust = y;
    s.xadvAdjust = x;
    s.bold = bold;
    s.latinOnly = latin;
    return s;
}

std::vector<OutputSpec> makeSpecs() {
    std::vector<OutputSpec> specs;
    // insignia：西文 lte50549、中文兰亭（name, 西文 size/x/bold,
    // 中文 size/x/bold, info/lh/base, 西文上飘）
    struct {
        const char* n;
        double lsz, x, lbold, csz, cx, cbold;
        int info, lh, base, up, westSupersample, cjkSupersample;
    } ins[] = {
        {"insignia15LTaa", 15.0, 0, 0.0, 15, 0, 0.10, 15, 17, 15, 2, 8, 8},
        {"insignia21LTaa", 17.0, 1, 0.0, 16, 1, 0.0, 18, 18, 16, 2, 4, 4},
        {"insignia25LTaa", 24.0, 0, 0.0, 22, 1, 0.0, 24, 25, 22, 2, 4, 4},
    };
    for (const auto& r : ins) {
        OutputSpec o;
        o.name = r.n;
        o.infoSize = r.info;
        o.lineHeight = r.lh;
        o.base = r.base;
        o.smooth = 1;  // 原版 insignia 系 smooth=1 aa=4
        o.aa = 4;
        o.upshiftPx = r.up;
        o.west = light(LTE, r.lsz, 0, r.x, r.lbold, true,
                       r.westSupersample);
        o.west.kerning = true;
        o.west.tabularDigits = true;
        o.cjk = light(LANTING, r.csz, 0, r.cx, r.cbold, false,
                      r.cjkSupersample);
        specs.push_back(o);
    }
    // orbitron / victor：西文 Orbitron VF（wght + GPOS kerning）、中文锐字。
    struct {
        const char* n; double sz, wght, x; int info, lh, base;
        double csz, cy, cbold;
        int smooth, aa, up;
        bool uppercaseLatin, tabularDigits;
    } orb[] = {
        {"orbitron12condensed", 12.0, 800, 0.5, -12, 16, 16, 16, 0, 0.15, 1, 1, 2, false, false},
        {"orbitron20aa", 15.5, 800, 0.5, 20, 20, 19, 18, 1, 0.15, 0, 4, 2, false, false},
        {"orbitron20aabold", 16.0, 800, 0.5, -20, 20, 19, 18, 1, 0.15, 0, 4, 2, false, false},
        {"orbitron24aa", 18.0, 800, 0.5, -24, 24, 21, 20, 1, 0.15, 0, 4, 0, false, false},
        {"orbitron24aabold", 20.0, 800, 0.5, 24, 24, 21, 20, 1, 0.15, 0, 4, 0, false, false},
        // victor 系：原为 Zpix 点阵（见文件末注），因高缩放下 strike 整数放大仍是
        // 放大的点阵、清晰度不足，改为与 orbitron 同源同策略的矢量渲染。
        // infoSize/lineHeight/base 一律沿用原值——布局度量冻结，UI 零位移；
        // 中文字号取 Zpix 版原字号（10/12/16），故汉字 advance 与视觉大小不变，
        // 变的只是字形从点阵变矢量。西文取 csz×0.85（orbitron 系 sz/csz 在
        // 0.75~1.0 之间），上飘取行高的 10%（与 orbitron 系的 2/20 同比例）。
        {"victor10", 10.0, 900, 1, -10, 10, 9, 11, 0, 0.17, 0, 1, 1, true, true},
        {"victor14", 10.0, 800, 1, -14, 13, 11, 12, 0, 0.15, 0, 1, 1, true, true},
        // victor16 的中文字号取 17 而非 16：锐字 advance/em≈0.963，round(16×0.963)=15
        // 会让汉字排版窄 1px（victor10/14 恰好进位故取原字号即可）。17 → round=16，
        // 与 Zpix 版逐字同宽。
        {"victor16", 13.5, 800, 1, -20, 18, 16, 17, 0, 0.15, 0, 1, 2, true, true},
    };
    for (const auto& r : orb) {
        OutputSpec o;
        o.name = r.n;
        o.infoSize = r.info;
        o.lineHeight = r.lh;
        o.base = r.base;
        o.smooth = r.smooth;
        o.aa = r.aa;
        // 西文上飘（实机校准值见表末列）
        o.upshiftPx = r.up;
        o.west = light(ORBITRON_VF, r.sz, 0, r.x, 0, true);
        o.west.wght = r.wght;
        o.west.kerning = true;
        o.west.uppercaseLatin = r.uppercaseLatin;
        o.west.tabularDigits = r.tabularDigits;
        o.cjk = light(RUIZI, r.csz, r.cy, 0, r.cbold, false);
        specs.push_back(o);
    }
    return specs;
}

std::string fileStem(const std::string& file) {
    size_t dot = file.rfind('.');
    return dot == std::string::npos ? file : file.substr(0, dot);
}

RenderParams deriveParams(const SourceSpec& src, double s) {
    RenderParams p;
    p.wght = src.wght;
    p.boldPx = src.bold * s;
    switch (src.mode) {
        case RenderMode::LightAA:
            p.sizePx = src.size * s;
            p.supersample = src.supersample;
            p.hint = HintMode::Light;
            break;
        case RenderMode::PixelCeil:
            p.sizePx = std::ceil(src.size * s);
            p.supersample = 1;
            p.hint = HintMode::MonoNoHint;
            break;
        case RenderMode::ZpixAuto: {
            // 小字号读内嵌位图 strike（≤16px 覆盖），大字号走字体自带矢量轮廓
            // mono no-hint —— 一律不超采样，保住像素字体的硬边。字号取整：
            // 渲小数字号会使同一字内笔画粗细不均。
            // 对像素字体做整数倍输出时：>16px 一律改用基准字号 strike 逐像素放大
            // （postUpscale），不走轮廓。两个实证理由：① strike 拉丁（等宽 6px
            // 窄体）与轮廓（比例宽体，A@24px 墨迹 ~18px）是两套设计，轮廓墨迹
            // 会撑爆与 1x 包同构的 advance；② Zpix upm=256 非 12 的整数倍
            // （1 设计像素 = 21.33 单位），轮廓在 24px mono 下阈值化笔画粗细
            // 不均、结构变形（实测「国」框厚薄不一）。strike 整数放大 = 精确
            // 2x 像素形态，与 1x 包逐像素同构。
            long target = pyRound(src.size * s);
            long scaleInt = pyRound(s);
            bool integerScale = std::abs(s - static_cast<double>(scaleInt)) < 1e-6;
            p.sizePx = static_cast<double>(target);
            if (target <= 16) {
                p.bitmapStrike = true;
            } else if (integerScale && pyRound(src.size) <= 16) {
                p.bitmapStrike = true;
                p.sizePx = static_cast<double>(pyRound(src.size));
                p.postUpscale = static_cast<int>(scaleInt);
            } else {
                p.supersample = 1;
                p.hint = HintMode::MonoNoHint;
            }
            break;
        }
    }
    return p;
}

// 实心底（alpha≥128 最低行；bbox 底被 AA 灰度边污染，不可用）
std::optional<int> solidBottom(const std::map<uint32_t, Glyph>& glyphs, uint32_t ch) {
    auto it = glyphs.find(ch);
    if (it == glyphs.end() || it->second.img.empty()) {
        return std::nullopt;
    }
    const Glyph& g = it->second;
    for (int y = g.img.h - 1; y >= 0; y--) {
        const uint8_t* row = g.img.alpha.data() + static_cast<size_t>(y) * g.img.w;
        for (int x = 0; x < g.img.w; x++) {
            if (row[x] >= 128) {
                return g.yoffset + y + 1;
            }
        }
    }
    return std::nullopt;
}

}  // namespace

const std::vector<OutputSpec>& builtinSpecs() {
    static const std::vector<OutputSpec> specs = makeSpecs();
    return specs;
}

std::string kerningTableName(const SourceSpec& source) {
    std::string name = fileStem(source.file);
    if (source.wght > 0) {
        name += "_w" + std::to_string(static_cast<int>(source.wght));
    }
    return name + ".kern.txt";
}

BaselineDeltas calculateBaselineDeltas(int westBottom, int cjkBottom,
                                       double preciseWestBottom,
                                       double preciseCjkBottom,
                                       double upshift) {
    BaselineDeltas result;
    result.integerDelta = -static_cast<int>(pyRound(upshift))
        - (westBottom - cjkBottom);
    result.preciseDelta = -upshift - (preciseWestBottom - preciseCjkBottom);
    return result;
}

bool remapLowercaseLatinGlyphs(std::map<uint32_t, Glyph>& glyphs) {
    for (uint32_t lower = 'a'; lower <= 'z'; lower++) {
        auto lowerIt = glyphs.find(lower);
        if (lowerIt == glyphs.end()) continue;
        auto upperIt = glyphs.find(lower - 'a' + 'A');
        if (upperIt == glyphs.end()) return false;
        Glyph mapped = upperIt->second;
        mapped.id = lower;
        lowerIt->second = std::move(mapped);
    }
    return true;
}

std::vector<std::pair<uint32_t, uint32_t>> uppercaseLatinKerningAliases(
        uint32_t first, uint32_t second) {
    auto isLower = [](uint32_t ch) { return ch >= 'a' && ch <= 'z'; };
    auto isUpper = [](uint32_t ch) { return ch >= 'A' && ch <= 'Z'; };
    if (isLower(first) || isLower(second)) return {};

    std::vector<uint32_t> firstAliases = {first};
    std::vector<uint32_t> secondAliases = {second};
    if (isUpper(first)) firstAliases.push_back(first - 'A' + 'a');
    if (isUpper(second)) secondAliases.push_back(second - 'A' + 'a');

    std::vector<std::pair<uint32_t, uint32_t>> result;
    result.reserve(firstAliases.size() * secondAliases.size());
    for (uint32_t firstAlias : firstAliases) {
        for (uint32_t secondAlias : secondAliases) {
            result.emplace_back(firstAlias, secondAlias);
        }
    }
    return result;
}

void makeDigitsTabular(std::map<uint32_t, Glyph>& glyphs) {
    int widestPen = 0;
    double preciseWidest = 0;
    for (uint32_t d = '0'; d <= '9'; d++) {
        auto it = glyphs.find(d);
        if (it != glyphs.end()) {
            widestPen = std::max(
                widestPen, it->second.xoffset + it->second.xadvance);
            preciseWidest = std::max(preciseWidest, it->second.preciseAdvance);
        }
    }
    for (uint32_t d = '0'; d <= '9'; d++) {
        auto it = glyphs.find(d);
        if (it == glyphs.end()) {
            continue;
        }
        int naturalPen = it->second.xoffset + it->second.xadvance;
        int center = static_cast<int>(pyRound((widestPen - naturalPen) / 2.0));
        it->second.xoffset += center;
        it->second.xadvance = widestPen - it->second.xoffset;
        it->second.preciseBearingX +=
            (preciseWidest - it->second.preciseAdvance) / 2.0;
        it->second.preciseAdvance = preciseWidest;
    }
}

bool composeOutput(const OutputSpec& spec, double s, const TypefacePack& typefaces,
                   const std::vector<uint32_t>& charList, int atlasWidth, ComposedFont& out) {
    // ── 西文源（latin 区 [32, 0x2FFF]）──────────────────────────────────────
    RenderParams wp = deriveParams(spec.west, s);
    std::vector<uint32_t> westChars;
    for (uint32_t c : charList) {
        if (!spec.west.latinOnly || (c >= 32 && c <= 0x2FFF)) {
            westChars.push_back(c);
        }
    }
    std::map<uint32_t, Glyph> westGlyphs;
    auto westFont = typefaces.find(spec.west.file);
    if (westFont == typefaces.end()
            || !renderGlyphs(westFont->second, wp, westChars, westGlyphs)) {
        logLine("[error] %s: 西文源渲染失败（数据包缺条目或字体损坏: %s）",
                spec.name, spec.west.file);
        return false;
    }
    if (spec.west.uppercaseLatin && !remapLowercaseLatinGlyphs(westGlyphs)) {
        logLine("[error] %s: Victor 大写映射缺少对应 A-Z 字形", spec.name);
        return false;
    }
    // 西文无 yAdjust：垂直位置由 post_align + upshiftPx 全权（yAdjust 会被
    // post_align 的闭环 delta 严格抵消，属死参数，已删）
    int wx = static_cast<int>(pyRound(spec.west.xadvAdjust * s));
    double preciseWx = spec.west.xadvAdjust * s;
    for (auto& [id, g] : westGlyphs) {
        g.xadvance += wx;
        g.preciseAdvance += preciseWx;
    }
    // 数字等宽（字距已计入，故必须在上面的 += wx 之后）：取 0-9 的最大 advance
    // 作为等宽单元，窄字形在其中居中。游戏的 BMFont 渲染器实际每字前进量为
    // xoffset + xadvance（而非标准 BMFont 的单独 xadvance），所以居中增加 xoffset 后
    // 必须从 xadvance 扣回，否则窄数字“1”反而会使整串变宽。
    if (spec.west.tabularDigits) {
        makeDigitsTabular(westGlyphs);
    }

    // ── 中文源（补渲西文未覆盖的余集，含西文字体缺字的拉丁字符）────────────
    RenderParams cp = deriveParams(spec.cjk, s);
    std::vector<uint32_t> cjkChars;
    for (uint32_t c : charList) {
        if (westGlyphs.find(c) == westGlyphs.end()) {
            cjkChars.push_back(c);
        }
    }
    std::map<uint32_t, Glyph> cjkGlyphs;
    auto cjkFont = typefaces.find(spec.cjk.file);
    if (cjkFont == typefaces.end()
            || !renderGlyphs(cjkFont->second, cp, cjkChars, cjkGlyphs)) {
        logLine("[error] %s: 中文源渲染失败（数据包缺条目或字体损坏: %s）",
                spec.name, spec.cjk.file);
        return false;
    }
    int cy = static_cast<int>(pyRound(spec.cjk.yAdjust * s));
    int cx = static_cast<int>(pyRound(spec.cjk.xadvAdjust * s));
    for (auto& [id, g] : cjkGlyphs) {
        g.yoffset += cy;
        g.xadvance += cx;
        g.preciseBearingY += spec.cjk.yAdjust * s;
        g.preciseAdvance += spec.cjk.xadvAdjust * s;
    }

    out.glyphs = std::move(westGlyphs);
    out.glyphs.merge(cjkGlyphs);  // 已有键不覆盖（西文优先，同 fnt_composer needed 语义）

    // ── overrides ───────────────────────────────────────────────────────────
    for (uint32_t b : {static_cast<uint32_t>('{'), static_cast<uint32_t>('}')}) {
        auto it = out.glyphs.find(b);
        if (it != out.glyphs.end()) {
            // 游戏高亮标记：清零不渲染
            it->second.xadvance = 0;
            it->second.preciseAdvance = 0;
            it->second.widthOverride = 0;
            it->second.heightOverride = 0;
        }
    }

    // ── kerning（来源 TTF 的 kern/GPOS 固化表 → 按渲染字号像素化）───────────
    if (spec.west.kerning) {
        std::string kernName = kerningTableName(spec.west);
        auto kernEntry = typefaces.find(kernName);
        if (kernEntry != typefaces.end()) {
            KerningUnits units;
            parseKerningUnits(kernEntry->second, units);
            for (const auto& [first, second, u] : units.pairs) {
                // 等宽数字不允许任何一端的 kerning 改变单元宽度。
                if (spec.west.tabularDigits
                        && ((first >= '0' && first <= '9')
                            || (second >= '0' && second <= '9'))) {
                    continue;
                }
                double preciseAmount = static_cast<double>(u) * wp.sizePx / units.upm;
                int amount = static_cast<int>(pyRound(preciseAmount));
                std::vector<std::pair<uint32_t, uint32_t>> aliases =
                    spec.west.uppercaseLatin
                        ? uppercaseLatinKerningAliases(first, second)
                        : std::vector<std::pair<uint32_t, uint32_t>>{{first, second}};
                for (const auto& [aliasFirst, aliasSecond] : aliases) {
                    if (out.glyphs.count(aliasFirst) == 0
                            || out.glyphs.count(aliasSecond) == 0) {
                        continue;
                    }
                    if (amount != 0) {
                        out.kernings.emplace_back(aliasFirst, aliasSecond, amount);
                    }
                    if (preciseAmount != 0.0) {
                        out.preciseKernings.emplace_back(
                            aliasFirst, aliasSecond, preciseAmount);
                    }
                }
            }
        } else {
            logLine("[warning] %s: 数据包缺 kerning 表条目 (%s)，本套无字偶距", spec.name,
                    kernName.c_str());
        }
    }

    // ── 装箱 ────────────────────────────────────────────────────────────────
    out.pages = pack(out.glyphs, atlasWidth, 1);

    // ── post_align：西文实心底对齐中文实心底后上飘 round(upshiftPx×s)（1x 逻辑
    // 像素等比缩放；旧百分比制 floor(0.1×lh_s) 在不同 scale 下取整漂移，已废）──
    std::optional<int> bWest = solidBottom(out.glyphs, 'H');
    std::optional<int> bCjk = solidBottom(out.glyphs, POST_ALIGN_CJK_REF);  // 舰
    if (bWest && bCjk) {
        const Glyph& westRef = out.glyphs.at('H');
        const Glyph& cjkRef = out.glyphs.at(POST_ALIGN_CJK_REF);
        // solidBottom 的 y 是字形 alpha 内的整数行索引；它在精确路径
        // 中不变，只将 bearing 替换为未舍入的 26.6 值即得精确底边。
        double preciseWestBottom = westRef.preciseBearingY
            + (*bWest - westRef.yoffset);
        double preciseCjkBottom = cjkRef.preciseBearingY
            + (*bCjk - cjkRef.yoffset);
        BaselineDeltas deltas = calculateBaselineDeltas(
            *bWest, *bCjk, preciseWestBottom, preciseCjkBottom,
            spec.upshiftPx * s);
        int delta = deltas.integerDelta;
        double preciseDelta = deltas.preciseDelta;
        if (delta != 0) {
            for (auto& [id, g] : out.glyphs) {
                if (id < 0x3000) {
                    g.yoffset += delta;
                }
            }
        }
        if (preciseDelta != 0.0) {
            for (auto& [id, g] : out.glyphs) {
                if (id < 0x3000) {
                    g.preciseBearingY += preciseDelta;
                }
            }
        }
        out.baselineAligned = true;
    } else {
        // 两个基准字符都是无条件注入的（ASCII 'H' 与 POST_ALIGN_CJK_REF），
        // 走到这里说明字体源本身渲不出它们 —— 由 generateAll 的产物自检拦截
        logLine("[warning] %s: post_align 标定字符缺失（H/舰），跳过基线对齐", spec.name);
    }

    // ── 锚点定稿 ────────────────────────────────────────────────────────────
    out.name = spec.name;
    out.infoSize = spec.infoSize >= 0
        ? static_cast<int>(pyRound(spec.infoSize * s))
        : -static_cast<int>(pyRound(-static_cast<double>(spec.infoSize) * s));
    out.preciseInfoSize = spec.infoSize * s;
    out.lineHeight = static_cast<int>(pyRound(spec.lineHeight * s));
    out.base = static_cast<int>(pyRound(spec.base * s));
    out.preciseLineHeight = spec.lineHeight * s;
    out.preciseBase = spec.base * s;
    out.smooth = spec.smooth;
    out.aa = spec.aa;
    out.tabularDigits = spec.west.tabularDigits;
    // face 值禁止含空格：游戏 fnt 解析器按空格 split token，含空格的 face 会
    // 使后续字段错位（实测 ArrayIndexOutOfBoundsException 崩启动器）
    char face[256];
    snprintf(face, sizeof(face), "dyn:%s@%g+%s@%g_s%g", fileStem(spec.west.file).c_str(),
             wp.sizePx, fileStem(spec.cjk.file).c_str(), cp.sizePx, s);
    out.face = face;
    return true;
}

}  // namespace dynfont

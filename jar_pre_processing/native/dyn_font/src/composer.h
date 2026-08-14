/*
 * 单套字体组装 — fnt_composer main.py run() 的运行时对应物。
 *
 * 流程：西文源渲染（latin 区）→ 中文源补渲余集 → 合并 → overrides
 * （orbitron 等宽数字 / { } 清零）→ kerning 像素化 → Shelf 装箱 →
 * post_align（西文实心底对齐中文后上飘 round(upshiftPx×s)）→ 锚点定稿。
 *
 * 字体与 kerning 表均取自分发数据包（pack_reader，build.py 打包）。
 */
#pragma once

#include <map>
#include <string>
#include <utility>
#include <tuple>
#include <vector>

#include "dynfont.h"
#include "freetype_renderer.h"
#include "pack_reader.h"
#include "packer.h"

namespace dynfont {

struct ComposedFont {
    std::string name;
    std::string face;
    int infoSize = 0;
    double preciseInfoSize = 0;
    int lineHeight = 0;
    int base = 0;
    double preciseLineHeight = 0;
    double preciseBase = 0;
    int smooth = 1;
    int aa = 1;
    // post_align 是否真的执行（基准字形渲染成功）。false 表示西文相对中文的
    // 垂直位置退化为 FreeType 原始 ascender 差 —— 由 generateAll 的产物自检拦截。
    bool baselineAligned = false;
    std::map<uint32_t, Glyph> glyphs;
    std::vector<AtlasPage> pages;
    std::vector<std::tuple<uint32_t, uint32_t, int>> kernings;
    std::vector<std::tuple<uint32_t, uint32_t, double>> preciseKernings;
};

/** 整数 BMFont 与精确 26.6 度量各自的基线对齐修正。 */
struct BaselineDeltas {
    int integerDelta = 0;
    double preciseDelta = 0;
};

BaselineDeltas calculateBaselineDeltas(int westBottom, int cjkBottom,
                                       double preciseWestBottom,
                                       double preciseCjkBottom,
                                       double upshift);

// Victor 风格：保留小写码位，但令 a-z 使用对应 A-Z 的字形与完整度量。
bool remapLowercaseLatinGlyphs(std::map<uint32_t, Glyph>& glyphs);

// 大写字偶向大小写输入码位展开；含小写的源字偶不是规范源，返回空。
std::vector<std::pair<uint32_t, uint32_t>> uppercaseLatinKerningAliases(
    uint32_t first, uint32_t second);

// kerning 固化表命名规则的唯一实现；运行时读取与构建资产清单共用。
std::string kerningTableName(const SourceSpec& source);

/*
 * 组装一套自洽字体：fnt 的全部字段（度量/装箱坐标/scaleW/H）与 pages 均来自
 * 同一遍 scale 渲染，UV = x/scaleW 天然对应本套图集。
 *
 * 图集尺寸必须是 2 的幂——游戏的纹理加载对非 POT 会做 padding，而 fnt 的
 * 归一化 UV 无从感知，字形会整体采到错位区域（踩过：非整数 scale 下 overlay
 * 页 6144 宽导致启动器字符全乱）。装箱器按 2^n 枚举，天然满足。
 */
bool composeOutput(const OutputSpec& spec, double scale, const TypefacePack& typefaces,
                   const std::vector<uint32_t>& charList, int atlasWidth, ComposedFont& out);

}  // namespace dynfont

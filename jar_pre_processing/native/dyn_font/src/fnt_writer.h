/*
 * BMFont 文本 .fnt + PNG 图集写出（fnt_composer core/fnt_writer.py 格式复刻）。
 */
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <tuple>
#include <vector>

#include "dynfont.h"
#include "packer.h"

namespace dynfont {

struct ComposedFont;

struct FntInfo {
    std::string face;
    int size = 0;        // 带符号
    int lineHeight = 0;
    int base = 0;
    int smooth = 1;      // 抄录原版（游戏纹理过滤标志）
    int aa = 1;
};

// 生成 .fnt 文本（page 文件名为 {name}_{i}.png）。
std::string buildFntText(const std::string& name, const FntInfo& info,
                         const std::map<uint32_t, Glyph>& glyphs,
                         const std::vector<AtlasPage>& pages,
                         const std::vector<std::tuple<uint32_t, uint32_t, int>>& kernings);

// alpha 页展开为 (A,A,A,A) 并编码 PNG（fpng）。
bool encodePagePng(const AtlasPage& page, std::vector<uint8_t>& out);

// 把精确高清度量编码进游戏原生 BitmapFont 可读取的整数坐标系。metricScale
// 同时放大 nominal、glyph 度量、kerning 与虚拟 atlas 尺寸，游戏 renderer 的
// requested/nominal 会把它完整抵消；page PNG 仍保持真实像素尺寸。
std::string buildProxyFntText(
    const std::string& name, const ComposedFont& font, int metricScale);

}  // namespace dynfont

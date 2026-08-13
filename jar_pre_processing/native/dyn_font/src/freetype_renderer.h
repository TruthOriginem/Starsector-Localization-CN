/*
 * FreeType 字形渲染 — fnt_composer core/ttf_extractor.py 的逐语义复刻。
 *
 * 双 face 机制：渲染 face 在 size×supersample 分辨率出位图；metrics face 在
 * 原始 size 取 xoffset/xadvance（与超采样倍数无关，避免舍入差异）。
 * 固定 canvas 高度：所有字形共用同一 Y 轴缩放比，消除逐字形 ±1px 垂直抖动。
 *
 * 与 Python 的一致性要求（金标准 diff）：
 *  - FreeType 版本 2.13.2（freetype-py 内嵌同版）；
 *  - 所有 round() 走 Python banker's rounding（pyRound，half-to-even）；
 *  - bbox 裁剪只把 y0 记入 yoffset、x 方向不补偿 xoffset —— 照抄，勿"修正"。
 */
#pragma once

#include <map>
#include <vector>

#include "dynfont.h"

namespace dynfont {

// Python round()：half-to-even（nearbyint 默认舍入模式即是）
long pyRound(double v);

enum class HintMode {
    Light,       // FT_LOAD_RENDER | FT_LOAD_TARGET_LIGHT
    MonoNoHint,  // FT_LOAD_RENDER | FT_LOAD_NO_HINTING | FT_LOAD_TARGET_MONO
};

struct RenderParams {
    double sizePx = 0;      // 实际渲染字号（浮点直传 26.6 定点）
    int supersample = 1;    // 1 / 4
    HintMode hint = HintMode::Light;
    double wght = 0;        // >0：可变字体 wght 轴
    double boldPx = 0;      // >0：超采样分辨率下方形膨胀半径 pyRound(boldPx×ss)
    bool xadvCompat = true; // starsector_xadvance_compat：xoffset>0 时 xadvance-=xoffset
    bool bitmapStrike = false;  // Zpix 内嵌 strike 模式（整数字号、默认加载允许位图）
    // >1：strike 渲染后逐像素整数放大（NEAREST，含全部度量 ×k）。像素字体的
    // 拉丁用——Zpix strike 拉丁（等宽窄体）与矢量轮廓（比例宽体）是两套设计，
    // 大字号轮廓墨迹会撑爆与 1x 包同构的 advance；strike 整数放大保设计一致且硬边。
    int postUpscale = 1;
};

// 渲染一批字符（font 为 TTF 内存镜像，FT_New_Memory_Face 输入，线程间只读共享；
// 内部自建 FT_Library/FT_Face 线程封闭；跳过字体中不存在的字符）。
// 失败（字体无法打开等）返回 false。
bool renderGlyphs(const std::vector<uint8_t>& font, const RenderParams& params,
                  const std::vector<uint32_t>& chars,
                  std::map<uint32_t, Glyph>& out);

}  // namespace dynfont

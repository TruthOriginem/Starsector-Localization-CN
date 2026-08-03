/*
 * PIL（Pillow）LANCZOS 重采样的逐像素复刻 — 金标准 diff 的前提。
 *
 * 算法照 Pillow src/libImaging/Resample.c 的 8bpc 单通道路径：
 * Lanczos3 分离卷积（先水平后垂直）、系数浮点归一化后 22bit 定点化、
 * 半像素中心采样、窗口边界 (int)(x+0.5) 舍入。任何偏离都会破坏与
 * fnt_composer 静态产物的逐字形一致性，修改前必须过金标准 diff。
 */
#pragma once

#include "dynfont.h"

namespace dynfont {

GlyphImage resizeLanczos(const GlyphImage& in, int outW, int outH);

}  // namespace dynfont

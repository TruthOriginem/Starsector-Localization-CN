/*
 * 单通道 alpha 图像基元：bbox 裁剪、粘贴、方形膨胀（PIL MaxFilter 语义）。
 */
#pragma once

#include "dynfont.h"

namespace dynfont {

// 非零 alpha 的包围盒（PIL getbbox 语义）。找到返回 true 并填 [x0,y0,x1,y1)。
bool alphaBbox(const GlyphImage& img, int& x0, int& y0, int& x1, int& y1);

GlyphImage crop(const GlyphImage& img, int x0, int y0, int x1, int y1);

// 把 src 粘贴到 dst 的 (x, y)，超出 dst 的部分裁掉（alpha 直接覆盖，
// 对应 fnt_composer 中透明 canvas 上的无 mask paste）。
void pasteClipped(GlyphImage& dst, const GlyphImage& src, int x, int y);

// 方形窗口 (2r+1)² 取最大（PIL ImageFilter.MaxFilter 对 alpha 通道的语义）。
// 输出与输入同尺寸；窗口 clamp 到图像边界（对 Max 与 PIL 的边缘扩展等价）。
GlyphImage dilateMax(const GlyphImage& img, int radius);

}  // namespace dynfont

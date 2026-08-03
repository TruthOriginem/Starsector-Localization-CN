/*
 * GPOS kerning 固化表解析与像素化。
 *
 * FreeType 的 FT_Get_Kerning 只读传统 kern 表，而 Orbitron 的字偶距在 GPOS——
 * 因此 pair 表由 fontTools 离线导出（tools/export_kerning.py，可变字体先按
 * wght 实例化），以 font units 固化为文本随分发数据包携带；native 运行时
 * 只做 amount = pyRound(units×size/upm)。
 *
 * 表文本格式（UTF-8）：
 *   upm 1000
 *   <first> <second> <units>   （每行一对，十进制 codepoint 与带符号 units）
 * 数据包内条目名约定：{TTF 文件名去扩展}[_w{wght}].kern.txt
 */
#pragma once

#include <cstdint>
#include <string>
#include <tuple>
#include <vector>

#include "dynfont.h"

namespace dynfont {

struct KerningUnits {
    int upm = 1000;
    std::vector<std::tuple<uint32_t, uint32_t, int>> pairs;  // first, second, units
};

// 解析固化表文本（数据包条目内容）。
void parseKerningUnits(const std::vector<uint8_t>& text, KerningUnits& out);

}  // namespace dynfont

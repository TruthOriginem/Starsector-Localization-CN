/* 精确高清字体描述格式（.dfnt）。 */
#pragma once

#include <cstdint>
#include <map>
#include <tuple>
#include <vector>

#include "composer.h"

namespace dynfont {

constexpr uint32_t DFNT_FORMAT_VERSION = 1;

// 生成确定性小端二进制。atlasScale 是图集对应的游戏 screenScale；baseNominal
// 是调用方的逻辑基准字号（绝对值）。
std::vector<uint8_t> buildDfnt(double atlasScale, float baseNominal,
                               const ComposedFont& font);

}  // namespace dynfont

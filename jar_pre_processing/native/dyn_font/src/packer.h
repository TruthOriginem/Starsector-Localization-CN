/*
 * Shelf 装箱（fnt_composer core/atlas_packer.py 语义）：
 * 字形按高度降序（同高按 id 升序，确定性）排行装箱，auto width 枚举 2^n 选
 * 面积最小，高度不限模式最终对齐到 2^n。
 *
 * 注意：装箱内部排列与 Python 产物允许不同（Python 受 dict/set 迭代序影响）；
 * "效果一致"的验收口径是逐字形像素与度量，不是图集布局。
 */
#pragma once

#include <map>
#include <vector>

#include "dynfont.h"

namespace dynfont {

// 单页高度上限（GL 纹理尺寸的保守值）。超出即分页，而游戏的 fnt 解析器只支持
// 单页 —— 故在本项目里"分页"等价于"产物不可用"，由 generate 的产物自检拦截。
constexpr int MAX_PAGE_H = 8192;

struct AtlasPage {
    int w = 0;
    int h = 0;
    std::vector<uint8_t> alpha;  // 写 PNG 时展开 (A,A,A,A)
};

// 装箱：更新每个 Glyph 的 dstPage/dstX/dstY，返回页列表。
// atlasWidth = -1 时自动选宽（256..4096 枚举 2^n）。
std::vector<AtlasPage> pack(std::map<uint32_t, Glyph>& glyphs, int atlasWidth, int padding);

}  // namespace dynfont

#include "packer.h"

#include <algorithm>

namespace dynfont {
namespace {

// 单页最大高度：保守取老显卡 GL_MAX_TEXTURE_SIZE（游戏为 LWJGL2/GL 固定管线时代）。
// 超出时分页（页宽不变，各页高度独立对齐 2^n）。

int nextPow2(int n) {
    int p = 1;
    while (p < n) {
        p <<= 1;
    }
    return p;
}

int simulateShelf(const std::vector<Glyph*>& glyphs, int width, int padding) {
    int shelfX = 0;
    int shelfY = 0;
    int shelfH = 0;
    for (const Glyph* g : glyphs) {
        int gw = g->width() + padding * 2;
        int gh = g->height() + padding * 2;
        if (gw > width) {
            continue;
        }
        if (shelfX + gw > width) {
            shelfY += shelfH;
            shelfX = 0;
            shelfH = 0;
        }
        shelfX += gw;
        shelfH = std::max(shelfH, gh);
    }
    return shelfY + shelfH;
}

}  // namespace

std::vector<AtlasPage> pack(std::map<uint32_t, Glyph>& glyphs, int atlasWidth, int padding) {
    std::vector<Glyph*> sorted;
    sorted.reserve(glyphs.size());
    for (auto& [id, g] : glyphs) {
        sorted.push_back(&g);
    }
    // 高度降序；同高按 id 升序（std::map 遍历已按 id，stable_sort 保序）
    std::stable_sort(sorted.begin(), sorted.end(),
                     [](const Glyph* a, const Glyph* b) { return a->height() > b->height(); });

    if (atlasWidth == -1) {
        // auto：枚举 2^n 宽（256..4096），单页高 ≤MAX_PAGE_H 的可行解中选面积最小；
        // 面积平手取更宽（更方正，纹理友好）。全部超限则取 4096 交给分页。
        int minGw = 1;
        for (const Glyph* g : sorted) {
            minGw = std::max(minGw, g->width() + padding * 2);
        }
        int minW = std::max(256, nextPow2(minGw));
        long long bestArea = -1;
        int bestW = 0;
        for (int w = minW; w <= 4096; w <<= 1) {
            int h = nextPow2(simulateShelf(sorted, w, padding));
            if (h > MAX_PAGE_H) {
                continue;
            }
            long long area = static_cast<long long>(w) * h;
            if (bestArea < 0 || area <= bestArea) {
                bestArea = area;
                bestW = w;
            }
        }
        atlasWidth = bestW > 0 ? bestW : 4096;
    }

    std::vector<AtlasPage> pages;
    pages.push_back(AtlasPage{atlasWidth, 0, {}});
    int shelfX = 0;
    int shelfY = 0;
    int shelfH = 0;

    auto newPage = [&]() {
        pages.push_back(AtlasPage{atlasWidth, 0, {}});
        shelfX = 0;
        shelfY = 0;
        shelfH = 0;
    };

    for (Glyph* g : sorted) {
        int gw = g->width() + padding * 2;
        int gh = g->height() + padding * 2;
        if (gw > atlasWidth) {
            logLine("[warning] glyph U+%04X width %d too wide for atlas, skipped",
                    g->id, g->width());
            continue;
        }
        if (shelfX + gw > atlasWidth) {
            shelfY += shelfH;
            shelfX = 0;
            shelfH = 0;
        }
        if (shelfY + gh > MAX_PAGE_H) {
            newPage();
        }

        AtlasPage& page = pages.back();
        int neededH = shelfY + gh;
        if (neededH > page.h) {
            page.alpha.resize(static_cast<size_t>(atlasWidth) * neededH, 0);
            page.h = neededH;
        }

        int dstX = shelfX + padding;
        int dstY = shelfY + padding;
        // 覆盖粘贴（fnt_composer 装箱层无 mask paste）；width/height override 为 0 的
        // 占位字形（如 { }）不落像素，规避原 Python 版 src_image 溢出槽位的问题
        int copyW = std::min(g->img.w, g->width());
        int copyH = std::min(g->img.h, g->height());
        for (int y = 0; y < copyH; y++) {
            const uint8_t* src = g->img.alpha.data() + static_cast<size_t>(y) * g->img.w;
            uint8_t* dst = page.alpha.data() + static_cast<size_t>(dstY + y) * atlasWidth + dstX;
            std::copy(src, src + copyW, dst);
        }

        g->dstPage = static_cast<int>(pages.size()) - 1;
        g->dstX = dstX;
        g->dstY = dstY;

        shelfX += gw;
        shelfH = std::max(shelfH, gh);
    }

    for (AtlasPage& page : pages) {
        int target = nextPow2(std::max(1, page.h));
        if (target != page.h) {
            page.alpha.resize(static_cast<size_t>(page.w) * target, 0);
            page.h = target;
        }
    }
    return pages;
}

}  // namespace dynfont

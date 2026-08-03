#include "image.h"

#include <algorithm>

namespace dynfont {

bool alphaBbox(const GlyphImage& img, int& x0, int& y0, int& x1, int& y1) {
    x0 = img.w;
    y0 = img.h;
    x1 = 0;
    y1 = 0;
    for (int y = 0; y < img.h; y++) {
        const uint8_t* row = img.alpha.data() + static_cast<size_t>(y) * img.w;
        for (int x = 0; x < img.w; x++) {
            if (row[x] != 0) {
                x0 = std::min(x0, x);
                y0 = std::min(y0, y);
                x1 = std::max(x1, x + 1);
                y1 = std::max(y1, y + 1);
            }
        }
    }
    return x1 > x0 && y1 > y0;
}

GlyphImage crop(const GlyphImage& img, int x0, int y0, int x1, int y1) {
    GlyphImage out;
    out.w = std::max(0, x1 - x0);
    out.h = std::max(0, y1 - y0);
    out.alpha.assign(static_cast<size_t>(out.w) * out.h, 0);
    for (int y = 0; y < out.h; y++) {
        const uint8_t* src = img.alpha.data() + static_cast<size_t>(y0 + y) * img.w + x0;
        std::copy(src, src + out.w, out.alpha.data() + static_cast<size_t>(y) * out.w);
    }
    return out;
}

void pasteClipped(GlyphImage& dst, const GlyphImage& src, int x, int y) {
    int sx0 = std::max(0, -x);
    int sy0 = std::max(0, -y);
    int dx0 = std::max(0, x);
    int dy0 = std::max(0, y);
    int w = std::min(src.w - sx0, dst.w - dx0);
    int h = std::min(src.h - sy0, dst.h - dy0);
    for (int row = 0; row < h; row++) {
        const uint8_t* s = src.alpha.data() + static_cast<size_t>(sy0 + row) * src.w + sx0;
        uint8_t* d = dst.alpha.data() + static_cast<size_t>(dy0 + row) * dst.w + dx0;
        std::copy(s, s + std::max(0, w), d);
    }
}

GlyphImage dilateMax(const GlyphImage& img, int radius) {
    if (radius <= 0) {
        return img;
    }
    // 方形窗口可分离：先水平后垂直各做一维滑动最大值。
    GlyphImage mid;
    mid.w = img.w;
    mid.h = img.h;
    mid.alpha.assign(img.alpha.size(), 0);
    for (int y = 0; y < img.h; y++) {
        const uint8_t* row = img.alpha.data() + static_cast<size_t>(y) * img.w;
        uint8_t* out = mid.alpha.data() + static_cast<size_t>(y) * img.w;
        for (int x = 0; x < img.w; x++) {
            int lo = std::max(0, x - radius);
            int hi = std::min(img.w - 1, x + radius);
            uint8_t m = 0;
            for (int i = lo; i <= hi; i++) {
                m = std::max(m, row[i]);
            }
            out[x] = m;
        }
    }
    GlyphImage out;
    out.w = img.w;
    out.h = img.h;
    out.alpha.assign(img.alpha.size(), 0);
    for (int x = 0; x < img.w; x++) {
        for (int y = 0; y < img.h; y++) {
            int lo = std::max(0, y - radius);
            int hi = std::min(img.h - 1, y + radius);
            uint8_t m = 0;
            for (int i = lo; i <= hi; i++) {
                m = std::max(m, mid.alpha[static_cast<size_t>(i) * img.w + x]);
            }
            out.alpha[static_cast<size_t>(y) * img.w + x] = m;
        }
    }
    return out;
}

}  // namespace dynfont

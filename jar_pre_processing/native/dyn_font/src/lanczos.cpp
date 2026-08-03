#include "lanczos.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <utility>
#include <vector>

namespace dynfont {
namespace {

constexpr int PRECISION_BITS = 32 - 8 - 2;  // Pillow: 22

inline double sincFilter(double x) {
    if (x == 0.0) {
        return 1.0;
    }
    x = x * 3.14159265358979323846;
    return std::sin(x) / x;
}

inline double lanczosFilter(double x) {
    if (-3.0 <= x && x < 3.0) {
        return sincFilter(x) * sincFilter(x / 3.0);
    }
    return 0.0;
}

inline uint8_t clip8(int in) {
    if (in >= (1 << PRECISION_BITS << 8)) {
        return 255;
    }
    if (in <= 0) {
        return 0;
    }
    return static_cast<uint8_t>(in >> PRECISION_BITS);
}

// Pillow precompute_coeffs + normalize_coeffs_8bpc：
// 输出每个像素的采样窗口 [xmin, xmin+xmax) 与 22bit 定点系数。
struct Coeffs {
    int ksize = 0;
    std::vector<int> bounds;  // outSize*2: xmin, xmax(窗口长度)
    std::vector<int> kk;      // outSize*ksize 定点系数
};

Coeffs precomputeCoeffs(int inSize, int outSize) {
    Coeffs c;
    double scale = static_cast<double>(inSize) / outSize;
    double filterscale = scale < 1.0 ? 1.0 : scale;
    double support = 3.0 * filterscale;  // Lanczos3
    c.ksize = static_cast<int>(std::ceil(support)) * 2 + 1;
    c.bounds.resize(static_cast<size_t>(outSize) * 2);
    c.kk.resize(static_cast<size_t>(outSize) * c.ksize);
    std::vector<double> prekk(static_cast<size_t>(outSize) * c.ksize);

    for (int xx = 0; xx < outSize; xx++) {
        double center = (xx + 0.5) * scale;
        double ww = 0.0;
        double ss = 1.0 / filterscale;
        int xmin = static_cast<int>(center - support + 0.5);
        if (xmin < 0) {
            xmin = 0;
        }
        int xmax = static_cast<int>(center + support + 0.5);
        if (xmax > inSize) {
            xmax = inSize;
        }
        xmax -= xmin;
        double* k = prekk.data() + static_cast<size_t>(xx) * c.ksize;
        int x = 0;
        for (; x < xmax; x++) {
            double w = lanczosFilter((x + xmin - center + 0.5) * ss);
            k[x] = w;
            ww += w;
        }
        for (x = 0; x < xmax; x++) {
            if (ww != 0.0) {
                k[x] /= ww;
            }
        }
        for (; x < c.ksize; x++) {
            k[x] = 0;
        }
        c.bounds[static_cast<size_t>(xx) * 2 + 0] = xmin;
        c.bounds[static_cast<size_t>(xx) * 2 + 1] = xmax;
    }

    for (size_t i = 0; i < prekk.size(); i++) {
        double w = prekk[i];
        c.kk[i] = w < 0 ? static_cast<int>(-0.5 + w * (1 << PRECISION_BITS))
                        : static_cast<int>(0.5 + w * (1 << PRECISION_BITS));
    }
    return c;
}

GlyphImage resampleHorizontal(const GlyphImage& in, int outW, const Coeffs& c) {
    GlyphImage out;
    out.w = outW;
    out.h = in.h;
    out.alpha.assign(static_cast<size_t>(outW) * in.h, 0);
    for (int yy = 0; yy < in.h; yy++) {
        const uint8_t* row = in.alpha.data() + static_cast<size_t>(yy) * in.w;
        uint8_t* dst = out.alpha.data() + static_cast<size_t>(yy) * outW;
        // 固定高度 canvas 的留白行恒为全零（pasteClipped 只写字形所在行区间）：
        // 全零输入下累加器停在初值、clip8 得 0，而 out 已零初始化 —— 跳过与
        // 逐像素卷积逐位等价，纯属省算力。
        if (std::all_of(row, row + in.w, [](uint8_t v) { return v == 0; })) {
            continue;
        }
        for (int xx = 0; xx < outW; xx++) {
            int xmin = c.bounds[static_cast<size_t>(xx) * 2 + 0];
            int xmax = c.bounds[static_cast<size_t>(xx) * 2 + 1];
            const int* k = c.kk.data() + static_cast<size_t>(xx) * c.ksize;
            int ss0 = 1 << (PRECISION_BITS - 1);
            for (int x = 0; x < xmax; x++) {
                ss0 += row[x + xmin] * k[x];
            }
            dst[xx] = clip8(ss0);
        }
    }
    return out;
}

GlyphImage resampleVertical(const GlyphImage& in, int outH, const Coeffs& c) {
    GlyphImage out;
    out.w = in.w;
    out.h = outH;
    out.alpha.assign(static_cast<size_t>(in.w) * outH, 0);
    for (int yy = 0; yy < outH; yy++) {
        int ymin = c.bounds[static_cast<size_t>(yy) * 2 + 0];
        int ymax = c.bounds[static_cast<size_t>(yy) * 2 + 1];
        const int* k = c.kk.data() + static_cast<size_t>(yy) * c.ksize;
        uint8_t* dst = out.alpha.data() + static_cast<size_t>(yy) * in.w;
        for (int xx = 0; xx < in.w; xx++) {
            int ss0 = 1 << (PRECISION_BITS - 1);
            for (int y = 0; y < ymax; y++) {
                ss0 += in.alpha[static_cast<size_t>(y + ymin) * in.w + xx] * k[y];
            }
            dst[xx] = clip8(ss0);
        }
    }
    return out;
}

/*
 * 系数表记忆化。precomputeCoeffs 是纯函数（只依赖 inSize/outSize），而
 * resizeLanczos 在字形循环内被逐字形调用：垂直方向的 (canvasHss→canvasTh) 是
 * 整个 renderGlyphs 的循环不变量，水平方向的宽度取值集合也很小。
 *
 * thread_local 是必需的：套间以 std::async 并行，共享表会引入数据竞争。
 */
const Coeffs& cachedCoeffs(int inSize, int outSize) {
    thread_local std::map<std::pair<int, int>, Coeffs> memo;
    auto key = std::pair<int, int>(inSize, outSize);
    auto it = memo.find(key);
    if (it == memo.end()) {
        it = memo.emplace(key, precomputeCoeffs(inSize, outSize)).first;
    }
    return it->second;
}

}  // namespace

GlyphImage resizeLanczos(const GlyphImage& in, int outW, int outH) {
    bool needH = outW != in.w;
    bool needV = outH != in.h;
    if (!needH && !needV) {
        return in;
    }
    GlyphImage mid = in;
    if (needH) {
        mid = resampleHorizontal(mid, outW, cachedCoeffs(in.w, outW));
    }
    if (needV) {
        mid = resampleVertical(mid, outH, cachedCoeffs(in.h, outH));
    }
    return mid;
}

}  // namespace dynfont

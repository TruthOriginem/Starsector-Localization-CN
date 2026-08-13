#include "freetype_renderer.h"

#include <cmath>
#include <cstring>

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MULTIPLE_MASTERS_H

#include "image.h"
#include "lanczos.h"

namespace dynfont {

long pyRound(double v) {
    // Python round() = banker's rounding；nearbyint 在默认 FE_TONEAREST 下即 half-to-even
    return static_cast<long>(std::nearbyint(v));
}

namespace {

// FT 位图 → 单通道 alpha（FT_PIXEL_MODE_MONO 按位解包，MSB first）
GlyphImage extractAlpha(const FT_Bitmap& bm) {
    GlyphImage img;
    img.w = static_cast<int>(bm.width);
    img.h = static_cast<int>(bm.rows);
    img.alpha.assign(static_cast<size_t>(img.w) * img.h, 0);
    int pitch = std::abs(bm.pitch);
    for (int y = 0; y < img.h; y++) {
        const unsigned char* row = bm.buffer + static_cast<size_t>(y) * pitch;
        uint8_t* dst = img.alpha.data() + static_cast<size_t>(y) * img.w;
        if (bm.pixel_mode == FT_PIXEL_MODE_MONO) {
            for (int x = 0; x < img.w; x++) {
                dst[x] = (row[x >> 3] & (0x80u >> (x & 7))) ? 255 : 0;
            }
        } else {
            std::memcpy(dst, row, static_cast<size_t>(img.w));
        }
    }
    return img;
}

// PIL paste(region, pos, region) 在透明 canvas 上的混合结果：
// BLEND(mask=A, in=0, src=A) = MULDIV255(A, A)，即 Pillow 的四舍五入除 255
// 位技巧 (tmp = A*A + 128; (tmp>>8 + tmp)>>8)。中间调被平方压暗（64→16、
// 128→64、200→157）—— fnt_composer 管线的既有行为，打样视觉验收即基于此，
// 属"现有观感"的组成部分，必须复刻、勿"修正"（实测区分点 a=200/220/90）。
void squareAlphaInPlace(GlyphImage& img) {
    for (uint8_t& a : img.alpha) {
        int tmp = static_cast<int>(a) * a + 128;
        a = static_cast<uint8_t>(((tmp >> 8) + tmp) >> 8);
    }
}

struct FaceGuard {
    FT_Library lib = nullptr;
    FT_Face face = nullptr;
    ~FaceGuard() {
        if (face != nullptr) {
            FT_Done_Face(face);
        }
        if (lib != nullptr) {
            FT_Done_FreeType(lib);
        }
    }
    bool open(const std::vector<uint8_t>& font) {
        if (FT_Init_FreeType(&lib) != 0) {
            return false;
        }
        if (FT_New_Memory_Face(lib, font.data(),
                               static_cast<FT_Long>(font.size()), 0, &face) != 0) {
            face = nullptr;
            return false;
        }
        return true;
    }
    bool setWght(double wght) {
        if (wght <= 0) {
            return true;
        }
        FT_Fixed coord = static_cast<FT_Fixed>(std::lround(wght * 65536.0));
        return FT_Set_Var_Design_Coordinates(face, 1, &coord) == 0;
    }
};

// strike 整数放大（NEAREST 逐像素复制，全部度量同乘 k）。供像素字体整数倍率输出：
// 在 strike 坐标系完成渲染后整体 ×k，与 1x 包逐像素设计一致且保硬边。
void upscaleGlyphNearest(Glyph& g, int k) {
    if (k <= 1) {
        return;
    }
    if (!g.img.empty()) {
        GlyphImage up;
        up.w = g.img.w * k;
        up.h = g.img.h * k;
        up.alpha.assign(static_cast<size_t>(up.w) * up.h, 0);
        for (int y = 0; y < g.img.h; y++) {
            for (int x = 0; x < g.img.w; x++) {
                uint8_t a = g.img.alpha[static_cast<size_t>(y) * g.img.w + x];
                if (a == 0) {
                    continue;
                }
                for (int dy = 0; dy < k; dy++) {
                    uint8_t* row =
                        &up.alpha[static_cast<size_t>(y * k + dy) * up.w + x * k];
                    for (int dx = 0; dx < k; dx++) {
                        row[dx] = a;
                    }
                }
            }
        }
        g.img = std::move(up);
    }
    g.xoffset *= k;
    g.yoffset *= k;
    g.xadvance *= k;
    g.preciseBearingX *= k;
    g.preciseBearingY *= k;
    g.preciseAdvance *= k;
}

// Zpix 内嵌 strike 模式（ttf_extractor._extract_bitmap 复刻）：
// 整数字号、FT_LOAD_RENDER 默认加载（允许内嵌位图）、ascender 取采样字符 max(bitmap_top)。
bool renderBitmapStrike(const std::vector<uint8_t>& font, const RenderParams& p,
                        const std::vector<uint32_t>& chars,
                        std::map<uint32_t, Glyph>& out) {
    FaceGuard fg;
    if (!fg.open(font)) {
        return false;
    }
    int size = static_cast<int>(p.sizePx);
    if (FT_Set_Pixel_Sizes(fg.face, 0, static_cast<FT_UInt>(size)) != 0) {
        return false;
    }

    // _bitmap_strike_ascender："AHbdlf中国人大" 的 max(bitmap_top)
    static const uint32_t kSample[] = {'A', 'H', 'b', 'd', 'l', 'f',
                                       0x4E2D, 0x56FD, 0x4EBA, 0x5927};
    int ascender = 0;
    for (uint32_t ch : kSample) {
        FT_UInt gi = FT_Get_Char_Index(fg.face, ch);
        if (gi != 0 && FT_Load_Glyph(fg.face, gi, FT_LOAD_RENDER) == 0
                && fg.face->glyph->bitmap.rows > 0) {
            ascender = std::max(ascender, static_cast<int>(fg.face->glyph->bitmap_top));
        }
    }
    if (ascender <= 0) {
        ascender = static_cast<int>(fg.face->size->metrics.ascender >> 6);
    }

    for (uint32_t ch : chars) {
        FT_UInt gi = FT_Get_Char_Index(fg.face, ch);
        if (gi == 0) {
            continue;
        }
        if (FT_Load_Glyph(fg.face, gi, FT_LOAD_RENDER) != 0) {
            continue;
        }
        FT_GlyphSlot slot = fg.face->glyph;
        Glyph g;
        g.id = ch;
        g.xoffset = static_cast<int>(slot->bitmap_left);
        g.xadvance = static_cast<int>(slot->advance.x >> 6);
        g.preciseBearingX = static_cast<double>(g.xoffset);
        g.preciseAdvance = static_cast<double>(slot->advance.x) / 64.0;
        if (p.xadvCompat && (g.xoffset > 0 || g.xoffset < -1)) {
            // 正 offset 预减（游戏渲染怪癖补偿，原规则）；≤-2 的真实左挂补回
            // （如 Orbitron 'j' left=-3：advance 不含左挂宽度，不补则与邻字粘连）。
            // -1 是抗锯齿边缘而非设计左挂（原版位图同位置 xoffset=0），不补——
            // 补了会使 '/' 'v' '¨' 等约 30 个字形比原版宽 1px。
            g.xadvance -= g.xoffset;
        }
        if (slot->bitmap.width == 0 || slot->bitmap.rows == 0) {
            g.img = GlyphImage{1, 1, {0}};
            g.yoffset = 0;
        } else {
            g.img = extractAlpha(slot->bitmap);
            g.yoffset = ascender - static_cast<int>(slot->bitmap_top);
        }
        g.preciseBearingY = static_cast<double>(g.yoffset);
        if (p.postUpscale > 1) {
            upscaleGlyphNearest(g, p.postUpscale);
        }
        out[ch] = std::move(g);
    }
    return true;
}

}  // namespace

bool renderGlyphs(const std::vector<uint8_t>& font, const RenderParams& p,
                  const std::vector<uint32_t>& chars,
                  std::map<uint32_t, Glyph>& out) {
    if (p.bitmapStrike) {
        return renderBitmapStrike(font, p, chars, out);
    }

    int ss = std::max(1, p.supersample);

    // 渲染 face：size×ss 分辨率
    FaceGuard render;
    if (!render.open(font)) {
        return false;
    }
    if (FT_Set_Char_Size(render.face, 0,
                         static_cast<FT_F26Dot6>(pyRound(p.sizePx * ss * 64)), 72, 72) != 0) {
        return false;
    }
    if (!render.setWght(p.wght)) {
        return false;
    }
    int ascender = static_cast<int>(render.face->size->metrics.ascender >> 6);

    FT_Int32 loadFlags;
    if (p.hint == HintMode::Light) {
        loadFlags = FT_LOAD_RENDER | FT_LOAD_TARGET_LIGHT;
    } else {
        loadFlags = FT_LOAD_RENDER | FT_LOAD_NO_HINTING | FT_LOAD_TARGET_MONO;
    }

    // 固定 canvas 高度：全部字形共用同一 Y 轴缩放比
    int descenderBudget = static_cast<int>(pyRound(p.sizePx * ss * 0.5));
    int canvasHss = ascender + descenderBudget;
    int canvasTh = std::max(1L, pyRound(static_cast<double>(canvasHss) / ss));
    int boldSs = static_cast<int>(pyRound(p.boldPx * ss));

    // metrics face：原始 size、FT_LOAD_DEFAULT|FT_LOAD_NO_HINTING（与超采样无关）
    FaceGuard metrics;
    if (!metrics.open(font)) {
        return false;
    }
    if (FT_Set_Char_Size(metrics.face, 0,
                         static_cast<FT_F26Dot6>(pyRound(p.sizePx * 64)), 72, 72) != 0) {
        return false;
    }
    if (!metrics.setWght(p.wght)) {
        return false;
    }
    const FT_Int32 metricsFlags = FT_LOAD_DEFAULT | FT_LOAD_NO_HINTING;

    for (uint32_t ch : chars) {
        FT_UInt gi = FT_Get_Char_Index(render.face, ch);
        if (gi == 0) {
            continue;
        }
        if (FT_Load_Glyph(metrics.face, gi, metricsFlags) != 0) {
            continue;
        }
        int xoffset = static_cast<int>(metrics.face->glyph->bitmap_left);
        int xadvance = static_cast<int>(metrics.face->glyph->advance.x >> 6);
        double preciseAdvance = static_cast<double>(metrics.face->glyph->advance.x) / 64.0;

        if (FT_Load_Glyph(render.face, gi, loadFlags) != 0) {
            continue;
        }
        FT_GlyphSlot slot = render.face->glyph;

        Glyph g;
        g.id = ch;
        int w = static_cast<int>(slot->bitmap.width);
        int h = static_cast<int>(slot->bitmap.rows);
        if (w == 0 || h == 0) {
            g.img = GlyphImage{1, 1, {0}};
            g.yoffset = 0;
        } else {
            GlyphImage glyphSs = extractAlpha(slot->bitmap);
            if (boldSs > 0) {
                glyphSs = dilateMax(glyphSs, boldSs);
            }
            // PIL paste with mask：alpha 平方压暗后再入 canvas
            squareAlphaInPlace(glyphSs);

            GlyphImage canvas;
            canvas.w = w;
            canvas.h = canvasHss;
            canvas.alpha.assign(static_cast<size_t>(canvas.w) * canvas.h, 0);
            int pasteY = ascender - static_cast<int>(slot->bitmap_top);
            pasteClipped(canvas, glyphSs, 0, pasteY);

            int tw = std::max(1L, pyRound(static_cast<double>(w) / ss));
            GlyphImage down = resizeLanczos(canvas, tw, canvasTh);

            int x0, y0, x1, y1;
            if (alphaBbox(down, x0, y0, x1, y1)) {
                // 照抄 Python：x 方向裁剪不补偿 xoffset
                g.img = crop(down, x0, y0, x1, y1);
                g.yoffset = y0;
            } else {
                g.img = GlyphImage{1, 1, {0}};
                g.yoffset = 0;
            }
        }

        if (p.xadvCompat && (xoffset > 0 || xoffset < -1)) {
            xadvance -= xoffset;  // 规则与阈值理由见上一处注释
        }
        g.xoffset = xoffset;
        g.xadvance = xadvance;
        g.preciseBearingX = static_cast<double>(xoffset);
        g.preciseBearingY = static_cast<double>(g.yoffset);
        g.preciseAdvance = preciseAdvance;
        out[ch] = std::move(g);
    }
    return true;
}

}  // namespace dynfont

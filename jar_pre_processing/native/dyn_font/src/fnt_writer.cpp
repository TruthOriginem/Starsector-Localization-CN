#include "fnt_writer.h"

#include "composer.h"

#include <cstdarg>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <stdexcept>

#include "fpng.h"

namespace dynfont {
namespace {

void appendf(std::string& out, const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    out += buf;
}

}  // namespace

std::string buildFntText(const std::string& name, const FntInfo& info,
                         const std::map<uint32_t, Glyph>& glyphs,
                         const std::vector<AtlasPage>& pages,
                         const std::vector<std::tuple<uint32_t, uint32_t, int>>& kernings) {
    std::string out;
    out.reserve(glyphs.size() * 96 + 1024);

    // info / common 行的固定字段与 fnt_composer 输出一致
    appendf(out,
            "info face=\"%s\" size=%d bold=0 italic=0 charset=\"\" unicode=1 "
            "stretchH=100 smooth=%d aa=%d padding=0,0,0,0 spacing=1,1 outline=0\n",
            info.face.c_str(), info.size, info.smooth, info.aa);
    // scaleW/H 恒为本套图集的实际尺寸——UV = x/scaleW 归一化后必须落在本套
    // 纹理上（图集为 2 的幂，游戏不会 padding）
    int scaleW = pages.empty() ? 0 : pages[0].w;
    int scaleH = pages.empty() ? 0 : pages[0].h;
    appendf(out,
            "common lineHeight=%d base=%d scaleW=%d scaleH=%d pages=%zu packed=0 "
            "alphaChnl=1 redChnl=0 greenChnl=0 blueChnl=0\n",
            info.lineHeight, info.base, scaleW, scaleH, pages.size());
    for (size_t i = 0; i < pages.size(); i++) {
        appendf(out, "page id=%zu file=\"%s_%zu.png\"\n", i, name.c_str(), i);
    }

    appendf(out, "chars count=%zu\n", glyphs.size());
    for (const auto& [id, g] : glyphs) {
        // 与 Python 的 f'{v:<5}' 左对齐列宽一致，便于文本级比对
        appendf(out,
                "char id=%-5u x=%-5d y=%-5d width=%-5d height=%-5d "
                "xoffset=%-6d yoffset=%-6d xadvance=%-6d page=%d  chnl=15\n",
                id, g.dstX, g.dstY, g.width(), g.height(),
                g.xoffset, g.yoffset, g.xadvance, g.dstPage);
    }

    if (!kernings.empty()) {
        appendf(out, "kernings count=%zu\n", kernings.size());
        for (const auto& [first, second, amount] : kernings) {
            appendf(out, "kerning first=%u second=%u amount=%d\n", first, second, amount);
        }
    }
    return out;
}

std::string buildProxyFntText(
    const std::string& name, const ComposedFont& font, int metricScale) {
    if (metricScale <= 0 || font.pages.size() != 1) {
        throw std::invalid_argument("proxy fnt requires positive metricScale and one page");
    }
    auto fixed = [metricScale](double value) {
        return static_cast<int>(std::llround(value * metricScale));
    };

    std::string out;
    out.reserve(font.glyphs.size() * 112 + 1024);
    const double preciseInfoSize = font.preciseInfoSize != 0
        ? font.preciseInfoSize : font.infoSize;
    int nominal = fixed(std::abs(preciseInfoSize));
    if (preciseInfoSize < 0) nominal = -nominal;
    appendf(out,
            "info face=\"%s\" size=%d bold=0 italic=0 charset=\"\" unicode=1 "
            "stretchH=100 smooth=%d aa=%d padding=0,0,0,0 spacing=1,1 outline=0\n",
            font.face.c_str(), nominal, font.smooth, font.aa);
    appendf(out,
            "common lineHeight=%d base=%d scaleW=%d scaleH=%d pages=1 packed=0 "
            "alphaChnl=1 redChnl=0 greenChnl=0 blueChnl=0\n",
            fixed(font.preciseLineHeight), fixed(font.preciseBase),
            font.pages[0].w * metricScale, font.pages[0].h * metricScale);
    appendf(out, "page id=0 file=\"%s_0.png\"\n", name.c_str());

    appendf(out, "chars count=%zu\n", font.glyphs.size());
    for (const auto& [id, g] : font.glyphs) {
        const double bearingX = std::isfinite(g.preciseBearingX)
            ? g.preciseBearingX : g.xoffset;
        const double bearingY = std::isfinite(g.preciseBearingY)
            ? g.preciseBearingY : g.yoffset;
        const double advance = std::isfinite(g.preciseAdvance)
            ? g.preciseAdvance : g.xoffset + g.xadvance;
        const int xoffset = fixed(bearingX);
        // 游戏的 pen advance = xoffset + xadvance，而标准 BMFont 只累加
        // xadvance。代理字段需扣回 bearing，保证最终使用精确完整 advance。
        const int xadvance = fixed(advance) - xoffset;
        appendf(out,
                "char id=%-5u x=%-7d y=%-7d width=%-7d height=%-7d "
                "xoffset=%-7d yoffset=%-7d xadvance=%-7d page=0  chnl=15\n",
                id, g.dstX * metricScale, g.dstY * metricScale,
                g.width() * metricScale, g.height() * metricScale,
                xoffset, fixed(bearingY), xadvance);
    }

    if (!font.preciseKernings.empty()) {
        size_t nonzero = 0;
        for (const auto& [first, second, amount] : font.preciseKernings) {
            (void)first;
            (void)second;
            if (fixed(amount) != 0) ++nonzero;
        }
        appendf(out, "kernings count=%zu\n", nonzero);
        for (const auto& [first, second, amount] : font.preciseKernings) {
            int encoded = fixed(amount);
            if (encoded != 0) {
                appendf(out, "kerning first=%u second=%u amount=%d\n",
                        first, second, encoded);
            }
        }
    }
    return out;
}

bool encodePagePng(const AtlasPage& page, std::vector<uint8_t>& out) {
    static std::once_flag initFlag;
    std::call_once(initFlag, [] { fpng::fpng_init(); });

    // (A,A,A,A)：与静态产物像素格式一致（fnt 管线的 R=G=B=A）
    std::vector<uint8_t> rgba(static_cast<size_t>(page.w) * page.h * 4);
    for (size_t i = 0; i < page.alpha.size(); i++) {
        uint8_t a = page.alpha[i];
        rgba[i * 4 + 0] = a;
        rgba[i * 4 + 1] = a;
        rgba[i * 4 + 2] = a;
        rgba[i * 4 + 3] = a;
    }
    return fpng::fpng_encode_image_to_memory(rgba.data(), static_cast<uint32_t>(page.w),
                                             static_cast<uint32_t>(page.h), 4, out);
}

}  // namespace dynfont

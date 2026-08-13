#include "dfnt_writer.h"

#include <cstring>
#include <cmath>
#include <stdexcept>

namespace dynfont {
namespace {

void putU32(std::vector<uint8_t>& out, uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) {
        out.push_back(static_cast<uint8_t>(value >> shift));
    }
}

void putF32(std::vector<uint8_t>& out, float value) {
    static_assert(sizeof(float) == sizeof(uint32_t));
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    putU32(out, bits);
}

void putF64(std::vector<uint8_t>& out, double value) {
    static_assert(sizeof(double) == sizeof(uint64_t));
    uint64_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    for (int shift = 0; shift < 64; shift += 8) {
        out.push_back(static_cast<uint8_t>(bits >> shift));
    }
}

}  // namespace

std::vector<uint8_t> buildDfnt(double atlasScale, float baseNominal,
                               const ComposedFont& font) {
    if (font.pages.size() != 1) {
        throw std::invalid_argument("dfnt requires exactly one atlas page");
    }
    std::vector<uint8_t> out;
    out.reserve(48 + font.glyphs.size() * 40 + font.kernings.size() * 12);
    static constexpr uint8_t magic[8] = {'S', 'S', 'D', 'F', 'O', 'N', 'T', 0};
    out.insert(out.end(), std::begin(magic), std::end(magic));
    putU32(out, DFNT_FORMAT_VERSION);
    putF64(out, atlasScale);
    putF32(out, baseNominal);
    putF32(out, static_cast<float>(font.preciseLineHeight));
    putF32(out, static_cast<float>(font.preciseBase));
    putU32(out, static_cast<uint32_t>(font.pages[0].w));
    putU32(out, static_cast<uint32_t>(font.pages[0].h));
    putU32(out, static_cast<uint32_t>(font.glyphs.size()));
    putU32(out, static_cast<uint32_t>(font.preciseKernings.size()));

    for (const auto& [id, glyph] : font.glyphs) {
        putU32(out, id);
        putU32(out, static_cast<uint32_t>(glyph.dstX));
        putU32(out, static_cast<uint32_t>(glyph.dstY));
        putU32(out, static_cast<uint32_t>(glyph.width()));
        putU32(out, static_cast<uint32_t>(glyph.height()));
        putF32(out, static_cast<float>(std::isfinite(glyph.preciseBearingX)
                                          ? glyph.preciseBearingX : glyph.xoffset));
        putF32(out, static_cast<float>(std::isfinite(glyph.preciseBearingY)
                                          ? glyph.preciseBearingY : glyph.yoffset));
        putF32(out, static_cast<float>(std::isfinite(glyph.preciseAdvance)
                                          ? glyph.preciseAdvance
                                          : glyph.xoffset + glyph.xadvance));
    }
    for (const auto& [first, second, amount] : font.preciseKernings) {
        putU32(out, first);
        putU32(out, second);
        putF32(out, static_cast<float>(amount));
    }
    return out;
}

}  // namespace dynfont

#include "dfnt_writer.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>

using namespace dynfont;

namespace {

[[noreturn]] void fail(const char* expression, int line) {
    std::cerr << "CHECK failed at line " << line << ": " << expression << '\n';
    std::exit(1);
}

#define CHECK(expression) ((expression) ? static_cast<void>(0) : fail(#expression, __LINE__))

uint32_t u32(const std::vector<uint8_t>& data, size_t offset) {
    return static_cast<uint32_t>(data[offset])
        | static_cast<uint32_t>(data[offset + 1]) << 8
        | static_cast<uint32_t>(data[offset + 2]) << 16
        | static_cast<uint32_t>(data[offset + 3]) << 24;
}

float f32(const std::vector<uint8_t>& data, size_t offset) {
    uint32_t bits = u32(data, offset);
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

double f64(const std::vector<uint8_t>& data, size_t offset) {
    uint64_t bits = 0;
    for (int i = 0; i < 8; i++) {
        bits |= static_cast<uint64_t>(data[offset + i]) << (8 * i);
    }
    double value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

}  // namespace

int main() {
    CHECK(isSupportedScreenScale(1.0));
    CHECK(isSupportedScreenScale(1.95));
    CHECK(isSupportedScreenScale(3.0));
    CHECK(!isSupportedScreenScale(0.99));
    CHECK(!isSupportedScreenScale(3.01));
    CHECK(!isSupportedScreenScale(std::numeric_limits<double>::infinity()));
    CHECK(!isSupportedScreenScale(std::numeric_limits<double>::quiet_NaN()));

    ComposedFont font;
    font.lineHeight = 31;
    font.base = 27;
    font.preciseLineHeight = 31.25;
    font.preciseBase = 27.5;
    font.pages.push_back(AtlasPage{64, 128, {}});
    Glyph glyph;
    glyph.id = 65;
    glyph.dstX = 3;
    glyph.dstY = 4;
    glyph.xoffset = 2;
    glyph.yoffset = 5;
    glyph.xadvance = 7;
    glyph.img = GlyphImage{6, 8, {}};
    font.glyphs.emplace(glyph.id, glyph);
    font.kernings.emplace_back(65, 86, -1);
    font.preciseKernings.emplace_back(65, 86, -1.25);

    auto first = buildDfnt(1.95, 20.0f, font);
    auto second = buildDfnt(1.95, 20.0f, font);
    CHECK(first == second);
    CHECK(first.size() == 92);
    CHECK(std::memcmp(first.data(), "SSDFONT\0", 8) == 0);
    CHECK(u32(first, 8) == DFNT_FORMAT_VERSION);
    CHECK(std::abs(f64(first, 12) - 1.95) < 1e-12);
    CHECK(f32(first, 20) == 20.0f);
    CHECK(f32(first, 24) == 31.25f);
    CHECK(f32(first, 28) == 27.5f);
    CHECK(u32(first, 32) == 64);
    CHECK(u32(first, 36) == 128);
    CHECK(u32(first, 40) == 1);
    CHECK(u32(first, 44) == 1);
    CHECK(u32(first, 48) == 65);
    CHECK(f32(first, 68) == 2.0f);
    CHECK(f32(first, 72) == 5.0f);
    CHECK(f32(first, 76) == 9.0f);  // 完整 pen advance = xoffset + xadvance
    CHECK(u32(first, 80) == 65);
    CHECK(u32(first, 84) == 86);
    CHECK(f32(first, 88) == -1.25f);
}

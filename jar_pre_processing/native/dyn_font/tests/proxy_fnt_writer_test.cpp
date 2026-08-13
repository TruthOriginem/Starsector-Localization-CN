#include "fnt_writer.h"
#include "composer.h"

#include <cstdlib>
#include <iostream>
#include <string>

using namespace dynfont;

namespace {

[[noreturn]] void fail(const char* expression, int line) {
    std::cerr << "CHECK failed at line " << line << ": " << expression << '\n';
    std::exit(1);
}

#define CHECK(expression) ((expression) ? static_cast<void>(0) : fail(#expression, __LINE__))

}  // namespace

int main() {
    ComposedFont font;
    font.face = "proxy-test";
    font.infoSize = 20;
    font.preciseInfoSize = 19.5;
    font.preciseLineHeight = 31.25;
    font.preciseBase = 27.5;
    font.pages.push_back(AtlasPage{64, 128, {}});

    Glyph glyph;
    glyph.id = 'A';
    glyph.dstX = 3;
    glyph.dstY = 4;
    glyph.img = GlyphImage{6, 8, {}};
    glyph.preciseBearingX = 0.5;
    glyph.preciseBearingY = 2.25;
    glyph.preciseAdvance = 15.6;
    font.glyphs.emplace(glyph.id, glyph);
    font.preciseKernings.emplace_back('A', 'V', -1.25);

    std::string fnt = buildProxyFntText("victor10_exact", font, 64);
    CHECK(fnt.find("size=1248") != std::string::npos);
    CHECK(fnt.find("lineHeight=2000") != std::string::npos);
    CHECK(fnt.find("base=1760") != std::string::npos);
    CHECK(fnt.find("scaleW=4096 scaleH=8192") != std::string::npos);
    CHECK(fnt.find("x=192") != std::string::npos);
    CHECK(fnt.find("y=256") != std::string::npos);
    CHECK(fnt.find("width=384") != std::string::npos);
    CHECK(fnt.find("height=512") != std::string::npos);
    CHECK(fnt.find("xoffset=32") != std::string::npos);
    CHECK(fnt.find("yoffset=144") != std::string::npos);
    // round(15.6*64)=998，扣除 xoffset 32，游戏相加后仍为 998。
    CHECK(fnt.find("xadvance=966") != std::string::npos);
    CHECK(fnt.find("kerning first=65 second=86 amount=-80") != std::string::npos);
}

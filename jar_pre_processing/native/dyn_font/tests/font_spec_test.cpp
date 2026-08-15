#include "composer.h"

#include <cstdlib>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace {

#define CHECK(condition) do { if (!(condition)) std::abort(); } while (false)

const dynfont::OutputSpec& findSpec(const char* name) {
    for (const auto& spec : dynfont::builtinSpecs()) {
        if (std::string(spec.name) == name) return spec;
    }
    std::abort();
}

void checkConfiguredFamilies() {
    const auto& insignia15 = findSpec("insignia15LTaa");
    CHECK(insignia15.west.size == 15.0);
    CHECK(insignia15.west.supersample == 8);
    CHECK(insignia15.cjk.supersample == 8);
    CHECK(insignia15.west.bold == 0.13);
    CHECK(insignia15.cjk.bold == 0.08);
    CHECK(insignia15.west.kerning);
    for (const auto& [name, size] : {
            std::pair{"insignia21LTaa", 17.0},
            std::pair{"insignia25LTaa", 24.0}}) {
        const auto& spec = findSpec(name);
        CHECK(spec.west.size == size);
        CHECK(spec.west.supersample == 4);
        CHECK(spec.cjk.supersample == 4);
        CHECK(spec.west.bold == 0.0);
        CHECK(spec.cjk.bold == 0.0);
        CHECK(spec.west.kerning);
    }
    for (const char* name : {
            "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
            "orbitron24aa", "orbitron24aabold"}) {
        const auto& spec = findSpec(name);
        CHECK(spec.west.xadvAdjust == 0.5);
        CHECK(spec.west.kerning);
        CHECK(!spec.west.uppercaseLatin);
    }
    for (const char* name : {"victor10", "victor14", "victor16"}) {
        const auto& spec = findSpec(name);
        CHECK(spec.west.kerning);
        CHECK(spec.west.uppercaseLatin);
    }
    for (const auto& spec : dynfont::builtinSpecs()) {
        CHECK(!spec.cjk.kerning);
    }
}

void checkLowercaseGlyphUsesUppercaseShapeAndMetrics() {
    std::map<uint32_t, dynfont::Glyph> glyphs;
    dynfont::Glyph upper;
    upper.id = 'A';
    upper.xoffset = 3;
    upper.yoffset = 4;
    upper.xadvance = 11;
    upper.preciseBearingX = 3.25;
    upper.preciseBearingY = 4.5;
    upper.preciseAdvance = 11.75;
    upper.img.w = 2;
    upper.img.h = 1;
    upper.img.alpha = {17, 29};
    glyphs.emplace('A', upper);

    dynfont::Glyph lower;
    lower.id = 'a';
    lower.xadvance = 7;
    lower.img.w = 1;
    lower.img.h = 1;
    lower.img.alpha = {99};
    glyphs.emplace('a', lower);

    CHECK(dynfont::remapLowercaseLatinGlyphs(glyphs));
    const auto& mapped = glyphs.at('a');
    CHECK(mapped.id == 'a');
    CHECK(mapped.xoffset == upper.xoffset);
    CHECK(mapped.yoffset == upper.yoffset);
    CHECK(mapped.xadvance == upper.xadvance);
    CHECK(mapped.preciseBearingX == upper.preciseBearingX);
    CHECK(mapped.preciseBearingY == upper.preciseBearingY);
    CHECK(mapped.preciseAdvance == upper.preciseAdvance);
    CHECK(mapped.img.w == upper.img.w);
    CHECK(mapped.img.h == upper.img.h);
    CHECK(mapped.img.alpha == upper.img.alpha);
}

void checkUppercaseKerningExpandsToLowercaseAliases() {
    const std::vector<std::pair<uint32_t, uint32_t>> expected = {
        {'A', 'V'}, {'A', 'v'}, {'a', 'V'}, {'a', 'v'}};
    CHECK(dynfont::uppercaseLatinKerningAliases('A', 'V') == expected);
    CHECK(dynfont::uppercaseLatinKerningAliases('a', 'V').empty());

    const std::vector<std::pair<uint32_t, uint32_t>> punctuation = {
        {'T', '.'}, {'t', '.'}};
    CHECK(dynfont::uppercaseLatinKerningAliases('T', '.') == punctuation);
}

}  // namespace

int main() {
    checkConfiguredFamilies();
    checkLowercaseGlyphUsesUppercaseShapeAndMetrics();
    checkUppercaseKerningExpandsToLowercaseAliases();
    return 0;
}

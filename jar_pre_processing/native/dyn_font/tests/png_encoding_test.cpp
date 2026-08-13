#include "fnt_writer.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <vector>

#include "fpng.h"

using namespace dynfont;

namespace {

[[noreturn]] void fail(const char* expression, int line) {
    std::cerr << "CHECK failed at line " << line << ": " << expression << '\n';
    std::exit(1);
}

#define CHECK(expression) ((expression) ? static_cast<void>(0) : fail(#expression, __LINE__))

}  // namespace

int main() {
    AtlasPage page;
    page.w = 3;
    page.h = 2;
    page.alpha = {0, 1, 63, 127, 254, 255};

    std::vector<uint8_t> png;
    CHECK(encodePagePng(page, png));
    CHECK(png.size() >= 8);
    const uint8_t signature[] = {0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    for (size_t i = 0; i < sizeof(signature); i++) {
        CHECK(png[i] == signature[i]);
    }

    std::vector<uint8_t> decoded;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t channels = 0;
    CHECK(fpng::fpng_decode_memory(
        png.data(), static_cast<uint32_t>(png.size()), decoded,
        width, height, channels, 4) == fpng::FPNG_DECODE_SUCCESS);
    CHECK(width == static_cast<uint32_t>(page.w));
    CHECK(height == static_cast<uint32_t>(page.h));
    CHECK(channels == 4);
    CHECK(decoded.size() == page.alpha.size() * 4);
    for (size_t i = 0; i < page.alpha.size(); i++) {
        for (size_t channel = 0; channel < 4; channel++) {
            CHECK(decoded[i * 4 + channel] == page.alpha[i]);
        }
    }
}

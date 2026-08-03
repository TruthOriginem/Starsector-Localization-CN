#include "kerning.h"

#include <sstream>

namespace dynfont {

void parseKerningUnits(const std::vector<uint8_t>& text, KerningUnits& out) {
    std::istringstream in(std::string(text.begin(), text.end()));
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (line.empty() || line[0] == '#') {
            continue;
        }
        std::istringstream head(line);
        std::string tag;
        head >> tag;
        if (tag == "upm") {
            head >> out.upm;
            continue;
        }
        uint32_t first = 0;
        uint32_t second = 0;
        int units = 0;
        std::istringstream fields(line);
        if (fields >> first >> second >> units) {
            out.pairs.emplace_back(first, second, units);
        }
    }
}

}  // namespace dynfont

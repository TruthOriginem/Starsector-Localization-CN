/*
 * dynfont_cli — 动态字体生成命令行驱动。
 *
 * 用途：离线调试与金标准验收（与 fnt_composer 同参数产物逐字形 diff）。
 *
 * 用法：
 *   dynfont_cli --typefaces <数据包> --chars <chars.txt> --out <输出目录>
 *               [--scale 1.0] [--only <name>] [--atlas-width N]
 *   dynfont_cli --list-assets
 */
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <set>
#include <string>
#include <tuple>

#include "composer.h"
#include "dynfont.h"

namespace {

std::string narrow(const wchar_t* w) {
    std::string out;
    for (; *w != 0; w++) {
        out += static_cast<char>(*w <= 0x7F ? *w : '?');
    }
    return out;
}

std::string jsonString(const std::string& value) {
    static constexpr char HEX[] = "0123456789abcdef";
    std::string out = "\"";
    for (unsigned char c : value) {
        switch (c) {
            case '\"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += HEX[c >> 4];
                    out += HEX[c & 0x0F];
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}

int printAssetManifest() {
    std::set<std::string> fonts;
    std::set<std::tuple<std::string, int, std::string>> kernings;
    for (const dynfont::OutputSpec& spec : dynfont::builtinSpecs()) {
        for (const dynfont::SourceSpec* source : {&spec.west, &spec.cjk}) {
            if (source->file == nullptr || source->file[0] == '\0') {
                continue;
            }
            fonts.emplace(source->file);
            if (!source->kerning) {
                continue;
            }
            int weight = static_cast<int>(source->wght);
            if (source->wght < 0 || std::abs(source->wght - weight) > 1e-9) {
                std::fprintf(stderr, "kerning weight must be a non-negative integer: %s %.6f\n",
                             source->file, source->wght);
                return 1;
            }
            kernings.emplace(source->file, weight, dynfont::kerningTableName(*source));
        }
    }

    std::printf("{\n  \"version\": 1,\n  \"fonts\": [\n");
    size_t index = 0;
    for (const std::string& font : fonts) {
        std::printf("    %s%s\n", jsonString(font).c_str(), ++index < fonts.size() ? "," : "");
    }
    std::printf("  ],\n  \"kerning\": [\n");
    index = 0;
    for (const auto& [font, weight, table] : kernings) {
        std::printf("    {\"font\": %s, \"weight\": %d, \"table\": %s}%s\n",
                    jsonString(font).c_str(), weight, jsonString(table).c_str(),
                    ++index < kernings.size() ? "," : "");
    }
    std::printf("  ]\n}\n");
    return 0;
}

void printUsage() {
    std::fprintf(stderr,
                 "usage: dynfont_cli --typefaces <file> --chars <file> --out <dir>\n"
                 "                   [--scale 1.0] [--only <name>] [--atlas-width N]\n"
                 "       dynfont_cli --list-assets\n");
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc == 2 && std::wstring(argv[1]) == L"--list-assets") {
        return printAssetManifest();
    }
    dynfont::GenerateConfig config;
    for (int i = 1; i < argc; i++) {
        std::wstring arg = argv[i];
        auto next = [&]() -> const wchar_t* {
            return i + 1 < argc ? argv[++i] : L"";
        };
        if (arg == L"--typefaces") {
            config.typefacePath = next();
        } else if (arg == L"--chars") {
            config.charsPath = next();
        } else if (arg == L"--out") {
            config.outDir = next();
        } else if (arg == L"--scale") {
            config.scale = _wtof(next());
        } else if (arg == L"--only") {
            config.only = narrow(next());
        } else if (arg == L"--atlas-width") {
            config.atlasWidth = _wtoi(next());
        } else {
            printUsage();
            return 2;
        }
    }
    if (config.typefacePath.empty() || config.charsPath.empty() || config.outDir.empty()) {
        std::fprintf(stderr, "missing required args (--typefaces / --chars / --out)\n");
        return 2;
    }
    return dynfont::generateAll(config);
}

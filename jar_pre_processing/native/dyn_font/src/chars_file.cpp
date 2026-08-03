#include "chars_file.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <set>

namespace dynfont {
namespace {

/*
 * UTF-8 解码。对玩家手编文件宽容（非法序列跳过而非中止），但**必须校验续字节**：
 * 只看首字节前缀会把 GBK/UTF-16 文本静默解成一堆无效码点，最终产出「只有 ASCII、
 * 零个汉字」的字体并顶掉随包分发的静态中文字体——生成日志却报成功。
 * 故这里统计非法序列数，由调用方据此判断编码是否根本不对。
 */
void decodeUtf8Line(const std::string& line, std::set<uint32_t>& out, size_t& badSeqs) {
    auto isCont = [&](size_t j) {
        return j < line.size() && (static_cast<uint8_t>(line[j]) & 0xC0) == 0x80;
    };
    size_t i = 0;
    while (i < line.size()) {
        uint8_t b = static_cast<uint8_t>(line[i]);
        uint32_t cp = 0;
        int len = 1;
        if (b < 0x80) {
            cp = b;
        } else if ((b & 0xE0) == 0xC0 && isCont(i + 1)) {
            cp = (b & 0x1F) << 6 | (line[i + 1] & 0x3F);
            len = 2;
        } else if ((b & 0xF0) == 0xE0 && isCont(i + 1) && isCont(i + 2)) {
            cp = (b & 0x0F) << 12 | (line[i + 1] & 0x3F) << 6 | (line[i + 2] & 0x3F);
            len = 3;
        } else if ((b & 0xF8) == 0xF0 && isCont(i + 1) && isCont(i + 2) && isCont(i + 3)) {
            cp = (b & 0x07) << 18 | (line[i + 1] & 0x3F) << 12 | (line[i + 2] & 0x3F) << 6
                 | (line[i + 3] & 0x3F);
            len = 4;
        } else {
            badSeqs++;
            i += 1;
            continue;
        }
        i += len;
        if (cp >= 32) {  // 控制字符不进字表
            out.insert(cp);
        }
    }
}

}  // namespace

std::vector<uint32_t> loadCharsFile(const std::wstring& path) {
    std::filesystem::path fsPath(path);
    std::ifstream in(fsPath, std::ios::binary);
    if (!in) {
        return {};
    }
    std::set<uint32_t> set;
    std::string line;
    bool first = true;
    size_t badSeqs = 0;
    size_t rawBytes = 0;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (first) {
            first = false;
            if (line.size() >= 3 && static_cast<uint8_t>(line[0]) == 0xEF
                    && static_cast<uint8_t>(line[1]) == 0xBB
                    && static_cast<uint8_t>(line[2]) == 0xBF) {
                line.erase(0, 3);  // UTF-8 BOM
            } else if (line.size() >= 2
                    && ((static_cast<uint8_t>(line[0]) == 0xFF
                         && static_cast<uint8_t>(line[1]) == 0xFE)
                        || (static_cast<uint8_t>(line[0]) == 0xFE
                            && static_cast<uint8_t>(line[1]) == 0xFF))) {
                // UTF-16 BOM：PowerShell 的 > / Out-File 默认就是这个编码，
                // 继续按 UTF-8 解会得到满屏垃圾码点
                logLine("[error] chars.txt 是 UTF-16 编码（检测到 BOM），请另存为 UTF-8");
                return {};
            }
        }
        if (line.rfind("//", 0) == 0) {
            continue;
        }
        rawBytes += line.size();
        decodeUtf8Line(line, set, badSeqs);
    }

    // 编码自检。返回空触发上层降级——玩家因此拿到的是随包分发的完整静态中文
    // 字体，远好于"11 套只剩垃圾字形、日志却报成功"。两条判据：
    //   ① 解不出任何非 ASCII 码点；
    //   ② 非法序列占比过高 —— GBK 文本的双字节汉字有相当比例会误命中 2/3 字节
    //      首字节前缀并"解出"垃圾码点，故不能只看 ①。实测现网字表转 GBK 后为
    //      11050 个非法序列 / 约 20KB（55%），而合法 UTF-8 恒为 0。
    size_t nonAscii = 0;
    for (uint32_t cp : set) {
        if (cp > 127) {
            nonAscii++;
        }
    }
    if (rawBytes > 0 && (nonAscii == 0 || badSeqs * 20 > rawBytes)) {
        logLine("[error] chars.txt 不是有效的 UTF-8（%zu 字节、%zu 个非法序列、"
                "%zu 个非 ASCII 字符）—— 请以 UTF-8 编码另存",
                rawBytes, badSeqs, nonAscii);
        return {};
    }
    if (badSeqs > 0) {
        logLine("[warning] chars.txt 含 %zu 个非法 UTF-8 序列（已跳过）", badSeqs);
    }

    for (uint32_t c = 32; c <= 126; c++) {
        set.insert(c);
    }
    // post_align 的 CJK 基准字符。缺失会让整套跳过基线对齐、西文相对中文的
    // 垂直位置退化为 FreeType 原始 ascender 差（各套方向与幅度还不一致）。
    // 玩家可任意替换字表，故不能假定它一定在里面。
    set.insert(POST_ALIGN_CJK_REF);

    return std::vector<uint32_t>(set.begin(), set.end());
}

}  // namespace dynfont

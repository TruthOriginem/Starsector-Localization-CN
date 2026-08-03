#include "pack_reader.h"

#include <cstring>
#include <filesystem>
#include <fstream>

namespace dynfont {
namespace {

template <typename T>
bool readValue(std::ifstream& in, T& out) {
    return static_cast<bool>(in.read(reinterpret_cast<char*>(&out), sizeof(T)));
}

}  // namespace

bool loadTypefacePack(const std::wstring& path, TypefacePack& out) {
    std::filesystem::path fsPath(path);
    std::ifstream in(fsPath, std::ios::binary);
    if (!in) {
        // 用 UTF-8 而非 %ls：MinGW CRT 在默认 C locale 下无法转换宽字符，
        // vsnprintf 会在第一个非 ASCII 字符处停止输出（中文路径日志被截断）
        logLine("[error] 无法打开数据包: %s", fsPath.u8string().c_str());
        return false;
    }
    // 文件总长，用于校验条目声明的 size —— size 是从文件直读的 uint64，
    // 若被损坏成巨大值，vector 构造会抛 bad_alloc；JNI 入口虽已设异常屏障，
    // 但在源头拦掉更好（也避免 2~4GB 这种"分配得逞但 memset 整块"的中间量级）
    in.seekg(0, std::ios::end);
    const std::streamoff totalBytes = in.tellg();
    in.seekg(0, std::ios::beg);
    if (totalBytes < 0) {
        logLine("[error] 无法确定数据包长度");
        return false;
    }

    char magic[4];
    if (!in.read(magic, 4) || std::memcmp(magic, "SSDF", 4) != 0) {
        logLine("[error] 数据包魔数不符（损坏或非 data 文件）");
        return false;
    }
    uint32_t version = 0;
    uint32_t count = 0;
    if (!readValue(in, version) || version != 1 || !readValue(in, count)) {
        logLine("[error] 数据包版本不支持: %u", version);
        return false;
    }

    for (uint32_t i = 0; i < count; i++) {
        uint16_t nameLen = 0;
        if (!readValue(in, nameLen)) {
            logLine("[error] 数据包截断（条目 %u 名长）", i);
            return false;
        }
        std::string name(nameLen, '\0');
        uint64_t size = 0;
        if (!in.read(name.data(), nameLen) || !readValue(in, size)) {
            logLine("[error] 数据包截断（条目 %u 头）", i);
            return false;
        }
        const std::streamoff remaining = totalBytes - in.tellg();
        if (remaining < 0 || size > static_cast<uint64_t>(remaining)) {
            logLine("[error] 数据包条目 %s 声明长度 %llu 超出剩余字节 %lld（已损坏）",
                    name.c_str(), static_cast<unsigned long long>(size),
                    static_cast<long long>(remaining));
            return false;
        }
        std::vector<uint8_t> payload(static_cast<size_t>(size));
        if (!in.read(reinterpret_cast<char*>(payload.data()),
                     static_cast<std::streamsize>(size))) {
            logLine("[error] 数据包截断（条目 %s 内容）", name.c_str());
            return false;
        }
        out[std::move(name)] = std::move(payload);
    }
    return true;
}

}  // namespace dynfont

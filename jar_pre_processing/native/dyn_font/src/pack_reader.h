/*
 * 分发数据包（data）读取器 —— build.py 打包的动态字体源资产（TTF + kerning 表）。
 *
 * 格式（小端，写入方为 build.py 的 build_data_pack）：
 *   "SSDF" | uint32 version=1 | uint32 count |
 *   count × ( uint16 nameLen | name UTF-8 | uint64 size | payload )
 */
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "dynfont.h"

namespace dynfont {

// 条目名（UTF-8 文件名）→ 内容。全量载入内存（数据包 ~15MB，一次读入）。
using TypefacePack = std::map<std::string, std::vector<uint8_t>>;

// 读取并校验数据包；失败（缺文件/魔数或版本不符/截断）返回 false 并 log。
bool loadTypefacePack(const std::wstring& path, TypefacePack& out);

}  // namespace dynfont

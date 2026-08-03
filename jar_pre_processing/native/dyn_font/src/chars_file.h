/*
 * chars.txt 解析 — 玩家可编辑字符集（增删字符，重启生效）。
 *
 * 格式：UTF-8（可带 BOM），`//` 开头的行为注释；其余行内每个字符（除换行）
 * 都进入字表，空格也算字符。ASCII 32~126 与 POST_ALIGN_CJK_REF 无条件并入。
 * 编码不是 UTF-8（GBK / UTF-16）时返回空，由上层降级为原版位图字体。
 */
#pragma once

#include <string>
#include <vector>

#include "dynfont.h"

namespace dynfont {

// post_align 的中文基准字形「舰」。composer 用它的实心底对齐西文基线，
// 缺失则整套跳过基线对齐，故在字表中无条件注入。
constexpr uint32_t POST_ALIGN_CJK_REF = 0x8230;

// 读取并解析 chars.txt，返回去重升序的 codepoint 列表；失败返回空。
std::vector<uint32_t> loadCharsFile(const std::wstring& path);

}  // namespace dynfont

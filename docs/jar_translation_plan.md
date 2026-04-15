# Jar 翻译改造实现计划

本文档规划两个相关问题的解决方案，建议作为一个整体分两阶段落地。

---

## 背景：两个问题

### 问题 1：同一 class 内两个相同原文，只需翻译其一
目前词条 key 仅由 `jar 路径 + 类路径 + 原文内容` 决定（见 `class_file.py:181` `generate_string_key`），
`_get_original_string_to_const_pairs_mapping` 按 `original.string` 聚合，`update_strings` 会把同原文
对应的所有 constant pair 一起改译文。`include_strings: Set[str]` 也是按内容整体 include/exclude。
所以"同类中两个相同原文、只译其一"现在**无法表达**。

### 问题 2：Windows / Linux（/ Mac）jar 各自混淆，需要把汉化迁移过去

经过与 `starsector_linux-0.98a-RC8.zip` 的实测比较，已经确认如下事实：

- **`starfarer.api.jar`**：两平台所有 class 文件**逐字节相同**（jar 只是自嵌入条目的路径前缀不同）。
  汉化产物直接 `cp` 到 Linux 版即可，**不需要任何迁移逻辑**。
- **`starfarer_obf.jar`**：两平台独立混淆，各 2818 个 class。
  - 类路径 57% 同名（1622 个），43% 不同名（1196 个）。
  - 在 `para_tranz_map.json` 中涉及的 583 个 obf 类里，308 个同名、275 个改名。
  - 抽样 + 批量验证 308 个同名类：
    - **297 / 308（96.4%）** 字符串集合完全相同，且**在常量表中顺序完全一致**。
    - **0 / 308** 出现"集合相同但顺序不同"。
    - 11 / 308 是纯粹的名字碰撞（字符串交集为 0，是不同源类）。
  - 结论：**同源类的 `constant_index` 顺序跨平台稳定**。问题 1 的 occurrence-index 设计可以直接复用到跨平台迁移。

---

## 统一设计思路

引入"**类内出现序号（occurrence index）**"这个维度——在同一 class 内，对同原文的多个 Utf8 常量按
`constant_index` 升序枚举出 0, 1, 2, …。这一维度：

- 同平台内绝对稳定（问题 1 所需）。
- 跨平台在**同源类**之间同样稳定（已验证，用于问题 2）。
- 跨平台在**不同源的同名撞车类**之间失效——此时走内容匹配流程。

---

## 阶段一：支持类内重复原文的部分翻译（解决问题 1）

### 1.1 occurrence-index 的定义

- 遍历 `get_utf8_constants_with_string_ref()` 返回的 Utf8 常量（已按 `constant_index` 升序）。
- 同一 `original.string` 出现多次时，依出现先后编号 `0, 1, 2, …`。
- 只在类内唯一出现的原文不带编号（保持 key 向后兼容）。

### 1.2 词条 key 格式升级

- 旧格式（唯一原文）：`{jar}:{class}#"{原文}"`（保持不变）
- 新格式（重复原文）：`{jar}:{class}#{n}:"{原文}"`，`n` 为 occurrence-index

  这样，在类内不重复的词条 key 完全不变，ParaTranz 上现有词条无需迁移；只有真正出现重复的类才会引入 `#n:`。

- 对长 key 截断格式（`~` + hash）也做同步处理：在 hash 前加 `#n:` 字段。
- 更新 `class_file.py` 里的 `construct_string_key_from_context` 正则 & `jar_file.py:130` 的 `re.split(r'[#:]', key)[1]` 解析逻辑——这里依赖 `#` 和 `:`，要小心新增的 `#n:` 不破坏现有解析。

### 1.3 `include_strings` 扩展

允许两种元素并存（向后兼容）：

```json
"include_strings": [
  "普通字符串（翻译所有 occurrence）",
  {"string": "Hello", "occurrences": [0]}
]
```

- `ClassFileMapItem` 解析时归一成 `{原文: Optional[Set[int]]}`，`None` 表示"全部"。
- `JavaClassFile.get_utf8_constant_pairs` / `_get_original_string_to_const_pairs_mapping`
  按 occurrence-index 过滤。
- `update_strings` 按 (原文, occurrence) 精准定位要写的常量。

### 1.4 context 补一行

在 `get_strings` 生成 context 时加入 `在类中位置：{n}/{total}`，便于跨平台迁移匹配 & 人工核对。
格式改动需要同步更新 `construct_string_key_from_context` 的正则。

### 1.5 ParaTranz 迁移

- 本阶段只对"类内存在重复原文"的类产生新词条 key，预计极少数。
- 一次性导出并以**安全模式**导入 ParaTranz，旧 key 词条会自动保留；手工处理重复 key 即可。

### 1.6 重构提示（顺便做）

`jar_file.py:92` 已经有 TODO：把 jar 词条从通用 `String` 抽成 `JarString` 子类，承载 jar 路径、类路径、原文、occurrence 等字段。建议本阶段顺手完成——所有上述改动都在这个子类上集中实现，避免污染通用 `String`。

---

## 阶段二：跨平台 obf jar 迁移流水线（解决问题 2）

阶段一落地后再做。阶段二不改 ParaTranz、不改 `para_tranz_map.json`，只生成新平台的译文 jar。

### 2.1 输入 / 输出

- **输入**：
  - Win 版（当前标注平台）：`game data/starfarer_obf.jar` + `localization/starfarer_obf.jar`
  - 目标平台：`game data.<platform>/starfarer_obf.jar`（原始 jar）
- **输出**：
  - `localization.<platform>/starfarer_obf.jar`（写好译文的目标平台 jar）
  - `docs/cross_platform_mapping_<platform>.json`（类映射表，可重用）
  - 迁移报告（未能自动匹配的类/词条列表）

### 2.2 流程

1. **API jar 直拷**：`localization.<platform>/starfarer.api.jar` 直接从 `localization/starfarer.api.jar` 复制。
2. **obf jar 类对齐**：对 `para_tranz_map.json` 中每个 obf jar 类：
   1. 按同路径在目标 jar 中取同名类。
   2. 对比两边字符串集合：
      - **交集 ≥ 阈值**（建议 `|intersection| >= 3 且 intersection / min(|A|,|B|) >= 0.5`）→ 判定为同源。
      - **不满足** → 进入内容指纹匹配。
   3. 内容指纹匹配：以 "字符串 multiset + 对非混淆类（API jar 中的类、以及两平台同名锚点类）的引用" 作为指纹，在目标 obf jar 全量类里找最相近候选。达到阈值写入映射表，否则列入报告。
3. **常量写回**：对每个匹配成功的 (win_class, target_class)：
   - 枚举 Win 类中带译文的 Utf8 常量，按 `(原文, occurrence_index)` 在 target 类中定位对应常量，把译文字符串写入。
   - occurrence 对不上（目标类中同原文出现次数不同）→ 列入报告。
4. **非 class 条目**：目标 jar 中其它文件（资源、MANIFEST 等）保持原样。

### 2.3 脚本接口

在 `para_tranz/para_tranz_script.py` 增加操作编号（例如 `7`）：

```
python para_tranz/para_tranz_script.py 7 linux
python para_tranz/para_tranz_script.py 7 mac
```

配套在 `.env` / `config.py` 增加：

- `PLATFORM_GAME_DATA_DIR`：各平台原 jar 路径（如 `game data.linux/`）
- `PLATFORM_TRANSLATION_DIR`：各平台输出路径（如 `localization.linux/`）

### 2.4 映射表持久化

`docs/cross_platform_mapping_<platform>.json` 结构示例：

```json
{
  "generated_at": "2026-xx-xx",
  "win_obf_sha": "5dd2...",
  "target_obf_sha": "3d41...",
  "classes": {
    "com/fs/starfarer/campaign/CampaignEngine.class": {
      "target": "com/fs/starfarer/campaign/CampaignEngine.class",
      "match_type": "same_name_same_strings",
      "confidence": 1.0
    },
    "com/fs/starfarer/campaign/OOoO.class": {
      "target": "com/fs/starfarer/campaign/xxyy.class",
      "match_type": "content_fingerprint",
      "confidence": 0.87
    }
  }
}
```

新版本游戏发布时只做增量刷新，不必每次从零匹配。

### 2.5 打包集成

`packaging/make_zip.py` / `make_exe.py` 增加按平台选择 `localization.<platform>/` 的能力，产出 Win/Linux/Mac 各自的汉化包。

---

## 风险与权衡

- **阶段一**：key 格式微调会让少数类产生"旧 key → 新 key"的变动，ParaTranz 需走一次安全模式导入并人工确认。不做这个就无法在表示层解决问题 1，也为阶段二打基础。
- **阶段二**：
  - 297 / 308 同名类确认顺序稳定，是强证据；但仅覆盖"同名 & mapped"的子集。275 个改名类尚未验证顺序稳定性（可在阶段二实现时用指纹匹配成功的样本二次验证）。
  - 如果 Alex 未来某版本更换混淆器或重排源码，常量池顺序稳定的前提可能失效——届时需要重新做一次采样验证，并考虑把 occurrence-index 弱化为 fallback、主路径改为严格内容匹配。
  - Mac 版尚未实测。Linux 版结论是否能无条件推广到 Mac，需在实现前另取一个 Mac 包做同样验证。

---

## 实施顺序建议

1. 阶段一 1.1 – 1.6（含 `JarString` 重构）
2. 阶段一上线、重导 ParaTranz
3. 阶段二 2.1 – 2.3，先只做 Linux，验证效果
4. 评估是否扩到 Mac、是否把映射表生成纳入版本更新 checklist

阶段一和阶段二之间没有强依赖之外的耦合，阶段二也可以用实际实现检验阶段一引入的 occurrence-index 在跨平台的正确性。

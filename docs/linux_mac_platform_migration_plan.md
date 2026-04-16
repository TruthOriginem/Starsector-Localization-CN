# Linux / Mac 平台迁移计划

本文档仅保留 Windows 汉化产物迁移到 Linux / Mac 平台的实施计划。

---

## 目标

- 让 Linux / Mac 版使用与 Windows 版一致的汉化内容。
- 在不改动 ParaTranz 词条结构的前提下，生成平台专用译文 jar。
- 产出可复用的跨平台类映射文件，降低后续版本迁移成本。

---

## 适用范围

- 平台：`linux`、`mac`
- 目标文件：`starfarer.api.jar`、`starfarer_obf.jar`
- 输入来源：
  - 当前 Windows 汉化：`game data/starfarer_obf.jar` + `localization/starfarer_obf.jar`
  - 目标平台原始包：`game data.<platform>/starfarer_obf.jar`

---

## 输入 / 输出

- 输入：
  - `game data/starfarer.api.jar`
  - `localization/starfarer.api.jar`
  - `game data/starfarer_obf.jar`
  - `localization/starfarer_obf.jar`
  - `game data.<platform>/starfarer_obf.jar`
- 输出：
  - `localization.<platform>/starfarer.api.jar`
  - `localization.<platform>/starfarer_obf.jar`
  - `docs/cross_platform_mapping_<platform>.json`
  - 迁移报告（未匹配类、未写入项、异常项）

---

## 迁移流程

1. API jar 处理
   - 直接复制 `localization/starfarer.api.jar` 到 `localization.<platform>/starfarer.api.jar`。

2. obf jar 类映射
   - 遍历 `para_tranz_map.json` 中的 obf 类条目。
   - 优先尝试同路径同名匹配。
   - 若同名候选不可靠，使用内容指纹在目标 obf jar 中检索最相近类。
   - 对达到阈值的候选建立映射，未达阈值的项记录到迁移报告。

3. obf jar 译文写入
   - 按映射关系将 Windows 译文写入目标平台类。
   - 写入失败或冲突项记录到迁移报告，后续人工处理。

4. 资源保持原样
   - 目标 jar 中非 class 条目（资源、MANIFEST 等）不做改写。

---

## 脚本接口

在 `para_tranz/para_tranz_script.py` 增加迁移命令（示例编号 `7`）：

```bash
python para_tranz/para_tranz_script.py 7 linux
python para_tranz/para_tranz_script.py 7 mac
```

配套在 `.env` / `config.py` 增加：

- `PLATFORM_GAME_DATA_DIR`：平台原始 jar 路径（如 `game data.linux/`）
- `PLATFORM_TRANSLATION_DIR`：平台输出路径（如 `localization.linux/`）

---

## 映射表格式

`docs/cross_platform_mapping_<platform>.json` 示例：

```json
{
  "generated_at": "2026-xx-xx",
  "win_obf_sha": "5dd2...",
  "target_obf_sha": "3d41...",
  "classes": {
    "com/fs/starfarer/campaign/CampaignEngine.class": {
      "target": "com/fs/starfarer/campaign/CampaignEngine.class",
      "match_type": "same_name",
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

---

## 打包集成

- `packaging/make_zip.py` / `packaging/make_exe.py` 增加平台参数。
- 按 `localization.<platform>/` 产出 Linux / Mac 独立汉化包。

---

## 风险与待确认项

- Mac 包需要独立验证，不默认继承 Linux 的匹配结果。
- 混淆策略变化可能导致旧映射表失效，需要在版本升级时重建或增量刷新映射。
- 自动映射无法覆盖的条目需保留人工校验流程。

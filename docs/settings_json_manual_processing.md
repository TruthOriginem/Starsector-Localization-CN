# settings.json 手动修改记录

本文件记录当前版本中需要手动维护的 `settings.json` 改动，这些改动不经过 ParaTranz 自动流程，需在打包前手动确认。

---

## cjkMode

**文件**：`localization/data/config/settings.json`

启用 CJK 字体的自动换行支持，中文显示必须开启。

```diff
- "cjkMode":false,
+ "cjkMode":true,
```

---

## showCNTranslationCredits

**文件**：`localization/data/config/settings.json`

显示汉化组制作人员名单。

```diff
- "showCNTranslationCredits":false,
+ "showCNTranslationCredits":true,
```

---

## designTypeColors 保留英文原文 key

**文件**：`original/data/config/settings.json`

**背景**：`designTypeColors` 对象以舰船设计类型名称为 key，游戏运行时通过 key 查找对应颜色。玩家加载未汉化的 mod 时，mod 中的舰船仍以英文设计类型名称注册，若 key 已被翻译为中文则无法匹配颜色。

**处理方式**：在 `localization/data/config/settings.json` 中，将 `designTypeColors` 的所有 key 翻译为中文后，在同一对象内手动追加一份完整的英文原文 key（value 相同），确保中英文两套名称均可命中颜色配置。

> 注意：此对象因此包含两倍数量的条目，中英文 key 均唯一，无真正重复。

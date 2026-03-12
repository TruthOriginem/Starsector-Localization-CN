# JSON Loader 需求整理

## ParaTranz Key 格式

采用 JSONPath（RFC 9535）格式，`$` 作为文件名与路径的分隔符：

```
{文件名}$.{路径}
```

示例：
- `battle_objectives.json$.nav_buoy.name`
- `planets.json$.nebula_center_old.name`
- `strings.json$.fleetInteractionDialog.someKey`
- `tips.json$.tips[0].tip`
- `tips.json$.tips[1]`（纯字符串元素）
- `hegemony.faction$.displayName`
- `afflictor_d_pirates.skin$.hullName`
- `afflictor_Strike.variant$.displayName`
- `advanced_countermeasures.skill$.effectGroups[0].name`
- `settings.json$.designTypeColors["Low Tech"]`（key 含空格时用方括号）

解析规则：按第一个 `$` 分割，左侧为文件名，右侧（含 `$`）为 JSONPath。

---

## para_tranz_map.json 配置格式

```json
{
  "type": "json",
  "path": "data/config/battle_objectives.json",
  "text_paths": [
    "$.*.name"
  ]
}
```

### `text_paths` 路径表达式语法

| 表达式 | 含义 | 操作 | 生成 key 路径部分示例 |
|--------|------|------|----------------------|
| `$.fieldName` | 根级别字段，值为字符串 | 替换 value | `$.fieldName` |
| `$.*.name` | 根对象下任意 key 的 `name` 字段 | 替换 value | `$.nav_buoy.name` |
| `$.*.*` | 根对象下任意 key 的任意子字段（值为字符串） | 替换 value | `$.group.key` |
| `$.*.*.title` | 3 层嵌套，第 3 层指定字段 | 替换 value | `$.codex.damage_kinetic.title` |
| `$.key[*]` | `key` 字段下的数组，每个元素是字符串 | 替换 value | `$.tips[0]` |
| `$.key[*].field` | `key` 字段下的数组，每个对象的指定字段 | 替换 value | `$.tips[0].tip` |
| `$[*]` | 根数组，每个元素是字符串 | 替换 value | `$[0]` |
| `$.key.$key` | `key` 字段下对象的所有 key 本身 | 重命名 key（`rename_key()`） | `$.designTypeColors["Low Tech"]` |

规则：
- `*` 匹配对象的任意 key（枚举）
- `[*]` 匹配数组的任意下标（枚举，从 0 开始）
- `.$key` 后缀表示翻译对象的 key 而非 value，写回时调用 `Object.rename_key()`
- key 中含特殊字符（空格等）时，生成的 key 路径部分使用 `["key"]` 形式
- 若路径对应的值不存在或不是字符串（或为空字符串），静默跳过

---

## 需要翻译的文件清单

### .json 文件

| 文件 | 结构 | `text_paths` |
|------|------|--------------|
| `data/config/battle_objectives.json` | `{id: {name, ...}}` | `["$.*.name"]` |
| `data/config/planets.json` | `{id: {name, ...}}` | `["$.*.name"]` |
| `data/config/custom_entities.json` | `{id: {defaultName, nameInText, shortName, aOrAn, isOrAre, ...}}` | `["$.*.defaultName", "$.*.nameInText", "$.*.shortName", "$.*.aOrAn", "$.*.isOrAre"]` |
| `data/config/tag_data.json` | `{id: {name, ...}}` | `["$.*.name"]` |
| `data/config/contact_tag_data.json` | `{id: {name, ...}}` | `["$.*.name"]` |
| `data/campaign/channels.json` | `{id: {name, type, shortType, ...}}` | 暂不处理 |
| `data/strings/strings.json` | `{group: {key: text}}` | `["$.*.*"]` |
| `data/strings/tips.json` | `{tips: [str \| {freq, tip}]}` | `["$.tips[*]", "$.tips[*].tip"]` |
| `data/strings/tooltips.json` | `{group: {id: {title, body}}}` | `["$.*.*.title", "$.*.*.body"]` |
| `data/world/factions/default_fleet_type_names.json` | `{key: text}` | `["$.*"]` |
| `data/world/factions/default_ranks.json` | `{ranks: {id: {name}}, posts: {id: {name}}}` | `["$.ranks.*.name", "$.posts.*.name"]` |
| `data/missions/*/descriptor.json` | `{title, ...}` | `["$.title"]` |
| `data/config/settings.json` | `{designTypeColors: {"Low Tech": [...], ...}}` | `["$.designTypeColors.$key"]`（翻译 key，使用 `Object.rename_key()`） |

> `ship_names.json` 不在 localization/ 中，**无需翻译**。

### .faction 文件（21 个）

| 文件 | 结构 | `text_paths` |
|------|------|--------------|
| `data/world/factions/*.faction` | 见下 | `["$.displayName", "$.displayNameWithArticle", "$.displayNameLong", "$.displayNameLongWithArticle"]` |

翻译字段：`displayName`、`displayNameWithArticle`、`displayNameLong`（可选）、`displayNameLongWithArticle`（可选）。
不翻译：`shipNamePrefix`、`personNamePrefixAOrAn`、`displayNameIsOrAre`（localization 中均为空字符串）。

### .skin 文件（66 个）

| 文件 | 结构 | `text_paths` |
|------|------|--------------|
| `data/hulls/skins/*.skin` | `{hullName, tech, descriptionPrefix?, ...}` | `["$.hullName", "$.tech", "$.descriptionPrefix"]`（`descriptionPrefix` 不存在时静默跳过） |

### .skill 文件（70 个）

| 文件 | 结构 | `text_paths` |
|------|------|--------------|
| `data/characters/skills/*.skill` | `{effectGroups: [{name, ...}]}` | `["$.effectGroups[*].name"]` |

### .variant 文件（439 个）

| 文件 | 结构 | `text_paths` |
|------|------|--------------|
| `data/variants/**/*.variant` | `{displayName, ...}` | `["$.displayName"]` |

---

## 通用处理规则（已确认）

- 路径对应值不存在、非字符串、或为空字符串 → 静默跳过，无需黑名单
- 数组下标从 0 开始
- value 以 `$` 开头的模板字符串（如 `$sender`）→ 跳过（不可翻译）
- 生成的词条 key 长度超过 `MAX_STRING_KEY_LENGTH`（256）→ 抛出异常（`config.py` 中定义）
- 词条 `context` 字段格式：`{EXPORTED_STRING_CONTEXT_PREFIX}{文件名}$.{json路径}`
  - 示例：`版本：0.98-RC8 词条格式：v2\nbattle_objectives.json$.nav_buoy.name`
  - `EXPORTED_STRING_CONTEXT_PREFIX` 已包含末尾换行，直接拼接即可

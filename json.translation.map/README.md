# json.translation.map 使用说明

本工具用于批量替换游戏文件中特定 JSON 键的值（如舰船变体的 `displayName`），通过映射文件实现中英文对照翻译。

## 使用方法

运行脚本：

```
python _jsonMapHandler.py
```

按提示输入操作编号：

### 功能 1：更新映射文件

扫描 `original/` 目录下的源文件，将所有匹配键的值收集到映射文件（`map_*.json`）中。

- 如果映射文件不存在，则新建
- 如果已存在，则追加新条目，不覆盖已有翻译
- 所有键均转为小写存储

**使用场景**：游戏版本更新后，运行一次功能 1 将新条目同步到映射文件中。

### 功能 2：更新本地化文件

读取映射文件（`map_*.json`），将 `localization/` 目录下对应文件中匹配键的值替换为译文。

- 只有映射文件中值与键不同（即已翻译）的条目才会被替换
- 未在映射文件中出现的条目会打印警告

**使用场景**：编辑完映射文件中的译文后，运行功能 2 将译文写入本地化文件。

## 编辑映射文件

映射文件格式为 JSON，键为英文原文的小写形式，值为译文（或尚未翻译时保留英文原文）：

```json
{
    "assault": "突击",
    "ancient": "Ancient"
}
```

- 将值改为中文译文即视为"已翻译"，功能 2 会执行替换
- 值与键相同（大小写不计）则视为"未翻译"，功能 2 跳过

## 编辑 `_jsonMapCollection.csv`

CSV 文件定义了需要处理哪些文件和键，每行一条规则：

| 列 | 说明 |
|---|---|
| `prefix` | 映射文件名前缀，用于区分不同规则 |
| `ext` | 限定处理的文件扩展名，如 `.variant`、`.json` |
| `key` | 要匹配的 JSON 键名，如 `displayName` |
| `path` | 源文件或目录路径（相对于 `original/` 和 `localization/`） |
| `mapKey` | （可选）多个 key 共用同一映射文件时填写，留空则用 `key` 列的值命名映射文件 |

**映射文件命名规则**：

- `mapKey` 为空：`map_{prefix}_{ext去掉点}_{key}.json`，如 `map_hull_variant_displayName.json`
- `mapKey` 不为空：`map_{prefix}_{ext去掉点}_{mapKey}.json`，如 `map_entities_json_common.json`

**`mapKey` 的用途**：当同一文件中多个键共享同一套词汇时，可让它们共用一个映射文件，统一维护翻译。

例如 `custom_entities.json` 中 `defaultName`、`nameInText`、`shortName`、`aOrAn`、`isOrAre` 这5个键都是对同一批实体名称的不同称谓，CSV 中均设置 `mapKey=common`：

```
prefix,    ext,   key,          path,                                mapKey
entities,  .json, defaultName,  data/config/custom_entities.json,    common
entities,  .json, nameInText,   data/config/custom_entities.json,    common
entities,  .json, shortName,    data/config/custom_entities.json,    common
entities,  .json, aOrAn,        data/config/custom_entities.json,    common
entities,  .json, isOrAre,      data/config/custom_entities.json,    common
```

这样5条规则共用同一个映射文件 `map_entities_json_common.json`，而不是各自生成5个独立文件。翻译一次实体名，所有字段都能同步替换。

# 也不麻烦的数据和版本管理指南
- 在首次使用之前，您需要：
    - 安装`Python 3.10`或以上版本。
      - 可以从 [这里下载](https://www.python.org/downloads/)
    - 安装`Git`版本管理系统。
      - 可以从 [这里下载](https://git-scm.com/downloads)
    - 克隆 [远行星号翻译存储库](https://github.com/TruthOriginem/Starsector-Localization-CN) 到本地。
      - 命令：`git clone https://github.com/TruthOriginem/Starsector-Localization-CN.git`
      - 克隆完成后，执行以下命令拉取子模块（submodule）：
        ```bash
        git submodule update --init
        ```
        > 本项目使用 [alexson](https://github.com/jnxyp/alexson) 作为子模块，用于处理游戏作者 Alex 风格的非标准 JSON 文件（含 `#` 注释、无引号枚举值等），以实现在修改翻译内容时完整保留原文件格式。
    - 配置 ParaTranz API Key，以便脚本自动从平台下载数据：
      - 复制项目目录下的`.env.example`为`.env`
      - 用文本编辑器打开`.env`，填入你的 ParaTranz API Key：
        ```
        PARATRANZ_PROJECT_ID=3489
        PARATRANZ_API_KEY=<你的API Key>
        ```
      - API Key 可在 [ParaTranz 个人页面](https://paratranz.cn/users/my) 的【设置】栏中找到 Token 并复制
- 脚本将假设：
  - 游戏原文文件位于 `项目目录/original` 文件夹
  - 游戏译文文件位于 `项目目录/localization` 文件夹
  - ParaTranz数据文件位于 `项目目录/para_tranz/output` 文件夹

## 准备工作
- 从仓库拉取更新，确保存储库文件和脚本为最新版本
  - 命令：`git pull`
  - 如果子模块有更新，还需执行：`git submodule update --init`
- 备份 ParaTranz 平台上的最新翻译数据（**在向平台上传数据前必须进行**，以防止平台上的特殊词条状态丢失）
  - 运行脚本，选择`3 - 从 ParaTranz 平台下载最新导出并写回汉化文件`，等待完成
  - 将生成的`para_tranz/output`文件夹内容提交到 git 作为备份

## 从 git 导入原文和译文到 ParaTranz
本操作将会把更新后的游戏原文和译文转换为ParaTranz使用的格式，并将其上传到该平台。

在游戏版本更新后，或是通过git对csv文件进行修改后，需要进行本操作。

在游戏版本更新后，需要首先设置新的词条导出上下文前缀，以便区分不同版本的词条
- 用文本编辑器打开`项目目录/para_tranz/config.py`文件
- 找到`EXPORTED_STRING_CONTEXT_PREFIX_PREFIX`变量，修改其中的游戏版本号
  - 例如，当前游戏版本为`0.98-RC8`，则设置为`EXPORTED_STRING_CONTEXT_PREFIX_PREFIX = "版本：0.98-RC8 词条格式：v2"`

（日后会尝试将此操作自动化）

- 双击打开`项目目录/para_tranz/para_tranz_script.py`。
- 选择`1 - 从原始和汉化文件导出 ParaTranz 词条`，等待程序执行完毕。
  - 请注意程序执行过程中的警告`[Warning]`开头的输出内容。
- 导出的文件存储在`项目目录/para_tranz/output`，打开该文件夹。
- 打开ParaTranz上的 [项目文件管理](https://paratranz.cn/projects/3489/settings/files) 页面
- 点击【上传文件】按钮右侧的下拉菜单
  - 更新**游戏原文**选择【批量更新文件】
  - 更新**译文**选择【批量导入译文】  
  - ![][update_files]
- 将`项目目录/para_tranz/output`中的`data`文件夹**整个拖入**弹出的上传文件框中
  - ![][upload_folder]
- 完成！

## 将翻译完成的译文导入 git
本操作将会从ParaTranz平台自动下载最新的汉化内容，并将其写回`localization`文件夹。

当翻译完毕后，或是中途需要测试翻译时，需要进行本操作。

- 双击打开`项目目录/para_tranz/para_tranz_script.py`。
- 选择`3 - 从 ParaTranz 平台下载最新导出并写回汉化文件`，等待程序执行完毕。
  - 请注意程序执行过程中的警告`[Warning]`开头的输出内容。
- 完成！之后可以使用`localization`文件夹中的内容对游戏进行测试，或上传到 git。

## 添加新 csv 文件
该脚本根据`para_tranz_map.json`中的配置，查找并提取csv中需要翻译成的词条。
该配置文件的格式如下：
```json
[
    {
        "path": "csv文件路径，使用'/'作分隔符.csv",
        "id_column_name": "csv中作为id的列名",
        "text_column_names": [
          "需要翻译列的列名1",
          "需要翻译列的列名2"
        ]
    },
    {
        "path": "csv文件路径，使用'/'作分隔符.csv",
        "translation_path": "译文csv文件相对路径，如果和原文一样则不用填",
        "id_column_name": "csv中作为id的列名",
        "text_column_names": [
          "需要翻译列的列名1",
          "需要翻译列的列名2"
        ]
    },
    {
        "path": "csv文件路径，使用'/'作分隔符.csv",
        "id_column_name": ["作为id的列名1", "作为id的列名2"],
        "text_column_names": [
          "需要翻译列的列名1",
          "需要翻译列的列名2"
        ]
    }
]
```

例如，`rules.csv`对应的配置如下：
```json
{
    "path": "data/campaign/rules.csv",
    "translation_path": "data/campaign/rules汉化校对测试样本(手动替换以测试).csv",
    "id_column_name": "id",
    "text_column_names": [
      "text",
      "options"
    ]
}
```
- 原文文件相对于`starsector-core`文件夹的路径是`data/campaign/rules.csv`
- 译文文件的路径是`data/campaign/rules汉化校对测试样本(手动替换以测试).csv`
- csv中含有id的列名是`id`，该id在文件中唯一，可以以此确定是哪一行
- 需要翻译的列有`text`和`options`

## 添加新 JSON/游戏数据文件

脚本根据 `para_tranz_map.json` 中的配置提取 JSON 类文件（`.json`、`.faction`、`.skin`、`.skill`、`.variant` 等）的可翻译词条。配置格式如下：

```json
{
    "type": "json",
    "path": "data/config/battle_objectives.json",
    "text_paths": [
        "$.*.name"
    ]
}
```

- `type`：固定为 `"json"`
- `path`：文件路径（相对于 `original/` 文件夹），支持 glob 通配符（如 `data/variants/**/*.variant`）
- `text_paths`：路径表达式列表，指定文件中哪些字段需要翻译

### `text_paths` 路径表达式语法

| 表达式 | 含义 | 生成 key 示例 |
|--------|------|--------------|
| `$.fieldName` | 根级别字段，值为字符串 | `$.fieldName` |
| `$.*.name` | 根对象下任意 key 的 `name` 字段 | `$.nav_buoy.name` |
| `$.*.*` | 根对象下任意 key 的任意子字段（值为字符串） | `$.group.key` |
| `$.*.*.title` | 3 层嵌套，第 3 层指定字段 | `$.codex.damage_kinetic.title` |
| `$.key[*]` | `key` 字段下的数组，每个元素是字符串 | `$.tips[0]` |
| `$.key[*].field` | `key` 字段下的数组，每个对象的指定字段 | `$.tips[0].tip` |
| `$[*]` | 根数组，每个元素是字符串 | `$[0]` |
| `$.key.$key` | 翻译 `key` 字段下对象的所有 **key 名称**（而非 value） | `$.designTypeColors["Low Tech"]` |

规则：
- `*` 匹配对象的任意 key；`[*]` 匹配数组的任意下标（从 0 开始）
- `.$key` 后缀表示翻译 key 名称本身，写回时会调用 `Object.rename_key()` 重命名
- key 中含特殊字符（如空格）时，生成的路径使用 `["key"]` 形式
- 路径对应值不存在、不是字符串、为空字符串，或以 `$` 开头的模板变量（如 `$sender`）→ 静默跳过
- 若该文件无可翻译词条，不会生成对应的 output 文件

### 注意事项

- 对同一 Object 的同一层级，**不要同时**配置 `.$key`（重命名 key）和值路径（如 `.*`），否则 key 重命名先执行后，值替换找不到原始 key 会静默失败

## 查找本次游戏更新后修改/添加的词条
- 打开 [翻译界面](https://paratranz.cn/projects/3489/strings)。
- 点击左上方搜索框右侧的漏斗状按钮，选择【高级筛选】
  - ![][advance_filter]
- 要查找**本次更新修改的词条**，请按【词条修改时间】进行筛选
- 要查找**本次更新添加的词条**，请按【词条添加时间】进行筛选
- 筛选时间设定为【晚于】上传文件的时间
  - ![][filter_options] 

## 词条 stage 的含义
- 0：未翻译
- 1：已翻译
- 2：有疑问
- 3：已检查（二校才有，本项目未启用）
- 5：已审核
- 9：已锁定，此状态下仅管理员可解锁，词条强制按译文导出
- -1：已隐藏，此状态下词条强制按原文导出

由于本项目没有使用二次校对，所以不会有stage为3的词条。如果已经审核，stage为5

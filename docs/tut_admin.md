# 也不麻烦的数据和版本管理指南
- 在首次使用之前，您需要：
    - 安装`Python 3.8`或以上版本。
      - 可以从 [这里下载](https://www.python.org/downloads/)
    - 安装`Git`版本管理系统。
      - 可以从 [这里下载](https://git-scm.com/downloads)
    - 克隆 [远行星号翻译存储库](https://github.com/TruthOriginem/Starsector-095-Localization) 到本地。
      - 命令：`git clone https://github.com/TruthOriginem/Starsector-095-Localization.git`
- 脚本将假设：
  - 游戏原文文件位于 `项目目录/original` 文件夹
  - 游戏译文文件位于 `项目目录/localization` 文件夹
  - ParaTranz数据文件位于 `项目目录/para_tranz/output` 文件夹

## 准备工作
- 从仓库拉取更新，确保存储库文件和脚本为最新版本
  - 命令：`git pull`
- 从Paratranz平台下载最新版本的翻译数据
  > **即使是从git向平台更新数据，也需要进行本操作**，以保证平台上词条的特殊状态不会丢失
  - 前往项目的[数据下载页面](https://paratranz.cn/projects/3489/artifact) ，
    点击【重新生成压缩包】以确保数据为最新。
  - 勾选同意协议，点击【下载压缩包】；下载完成后打开。
  - 打开`项目目录/para_tranz/output`文件夹，删除其中的`data`文件夹（如果有的话）。
  - 将压缩包中的`/utf-8/data`文件夹解压到`项目目录/para_tranz/output`。
    - ![][unzip]

## 从 git 导入原文和译文到 ParaTranz
本操作将会把更新后的游戏原文和译文转换为ParaTranz使用的格式，并将其上传到该平台。

在游戏版本更新后，或是通过git对csv文件进行修改后，需要进行本操作。

> 注意，请不要将导出的文件内容上传到git！

（日后会尝试将此操作自动化）

- 双击打开`项目目录/para_tranz/para_tranz.py`。
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
本操作将会从ParaTranz平台下载最新的汉化内容，并将其写回`localization`中的csv文件。

当翻译完毕后，或是中途需要测试翻译时，需要进行本操作。

（日后会尝试将此操作自动化）

- 双击打开`项目目录/para_tranz/para_tranz.py`。
- 选择`2 - 将 ParaTranz 词条写回汉化文件`，等待程序执行完毕。
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
- 3：已检查
- 5：已审核（二校）
- 9：已锁定，此状态下仅管理员可解锁，词条强制按译文导出
- -1：已隐藏，此状态下词条强制按原文导出

由于本项目没有使用二次校对，所以不会有stage为3的词条。如果已经审核，stage为5

[update_files]:update_files.png
[upload_folder]:upload_folder.png
[unzip]:unzip.png
[advance_filter]:advance_filter.png
[filter_options]:filter_options.png
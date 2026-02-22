# Starsector-Localization-CN

本项目为《远行星号》游戏的中文翻译项目。

目前正在进行的是游戏 **0.98a** 版本的汉化。

## 下载汉化

> **注意：本汉化包仅适用于 0.98a-RC8 版本**

### 从论坛下载

目前0.98尚且没有打包的汉化包。您可以在论坛帖子 [远行星号 0.97a-RC11 中文汉化v1.0.0](https://www.fossic.org/thread-13676-1-1.html) 下载0.97汉化版游戏安装包。

### 下载 GitHub 上的最新汉化

#### 分支版本
**对于不同的使用需求，我们提供了不同版本的汉化文件，请在下载前根据自身需要自行选择：**
- 主分支 [master](https://github.com/TruthOriginem/Starsector-Localization-CN/tree/master) 为最新版本的汉化，**字库采用宋体作为正文字体**
- 旧版字体分支 [font-lantinghei](https://github.com/TruthOriginem/Starsector-Localization-CN/tree/font-lantinghei) 为最新版本的汉化，**字库采用兰亭黑体作为正文字体**
- 综艺字体分支 [font-zongyi](https://github.com/TruthOriginem/Starsector-Localization-CN/tree/font-zongyi) 为最新版本的汉化，**字库采用综艺体作为正文字体**
- Miko24适配分支 [miko](https://github.com/TruthOriginem/Starsector-Localization-CN/tree/miko) 为适配 Miko Java 24 版本的汉化，字库字体与主分支相同

如果您希望抢先体验汉化内容，请参照如下步骤下载汉化：

1. 下载汉化文件
   1. [点这里下载汉化文件](https://github.com/TruthOriginem/Starsector-Localization-CN/archive/refs/heads/master.zip)
   2. 解压下载的文件，其中应包括名为`localization`的文件夹
2. 安装汉化文件
   1. 打开游戏目录下的`starsector-core`文件夹
   2. 将下载解压文件中`localization`文件夹内的全部内容复制到`starsector-core`文件夹中
      1. 如提示文件已存在，请选择【覆盖】
3. 正常游玩，**如果遇到翻译质量问题或bug，请向我们报告**。
   1. 由于汉化尚未完成，出现未翻译英文属于正常现象，无需报告。 

## 参与汉化

如果想参与汉化或者想为汉化提出意见和建议，请通过 QQ 553816216 添加 **议长**的好友，或者申请加入 QQ 群 **788249918 汉化顾问团**，然后才申请加入本项目。
申请入群时**请注明你的来历和英语水平**，英语水平需达到 **CET6** 或更高。

之后，参照这个[非常简单的翻译指南](docs/paratranz/tut_translator.md)开始使用Paratranz平台进行翻译

## 译文格式规范

请参见[远行星号译文格式规范](docs/paratranz/format_standard.md)

## 译名表

请参见[远行星号术语参考表](https://paratranz.cn/projects/3489/terms)

## 项目管理

### 文件夹结构

* "game data"存档的是游戏的原始文件，包括 `starsector-core/data` 文件夹下的所有文件和 `starfarer.api.jar`、`starfarer_obf.jar` 文件。
* "original" 内存放当前版本所有需要翻译的文件，不要直接编辑其内容
* "original.old" 内存放上个版本所有需要翻译的文件，不要直接编辑其内容
* "localization" 内存放当前版本翻译后的文件
* "localization.old" 内存放上个版本的译文，不要直接编辑其内容
* "para_tranz" 内存放Paratranz平台相关脚本
* "docs" 内存放项目文档内容
* "packaging" 内存放安装包及汉化包打包相关脚本和素材
* "json.translation.map" 内存放游戏 JSON 翻译映射和相关自动化脚本

### 自动化脚本

* **Python环境: 3.10 或更高**

| 文件                                      | 用途及文档                                                           |
|-----------------------------------------|-----------------------------------------------------------------|
| _cleanLocalization.py                   | 根据original文件夹清理localization文件夹。                                 |
| _copyOldLocalization.py                 | 通过比对original文件夹，更新汉化包中的未变更文件。                                   |
| _handleVariantNames.py                  | 处理指定文件夹中所有装配名，并更新/使用映射 json 用于翻译。                               |
| _overwriteLocalizationByOriginal.py     | TODO                                                            |
| _swapLangFile.py                        | 用来更替汉化文件和英文文件的脚本。                                               |
| _updateOriginal.py                      | TODO                                                            |
| _variant_name_map.json                  | 装配名映射文件，英文名对应汉化名，可后继继续更新。                                       |
| para_tranz/para_tranz.py                | 用于ParaTranz平台的数据导入导出工具，使用方法参见[本指南](docs/paratranz/tut_admin.md) |
| json.translation.map/_jsonMapHandler.py | TODO                                                            |

### 版本汉化流程

![][flow-chart]

> 其中内核文件在开始翻译前可能需要手动预处理，具体请参见[Jar文件手动处理记录](docs/jar_manual_processing/jar_manual_processing.md)

1. 创建以新版本号命名的分支，例如`0.97`，并切换到该分支
2. 删除 `original.old` 和 `localization.old` 文件夹，清空 `game data`文件夹
3. 将新版本游戏目录下 `starsector-core` 文件夹中的`data`文件夹和 `starfarer.api.jar`，`starfarer_obf.jar` 复制到`game data`文件夹中
4. 复制 `original`文件夹为 `original.old`
5. 重命名 `localization` 文件夹为 `localization.old`，并新建一个空的 `localization` 文件夹
6. 使用脚本复制相关文件到 `original` 和 `localization` 文件夹中
    1. 运行 `_updateOriginal.py` 脚本，将 `original` 文件夹更新为最新版本的对应文件
    2. 运行 `_copyOldLocalization.py` 脚本，将 `localization.old` 文件夹中对应原文件未变更的文件复制到 `localization` 文件夹中
    3. 手动添加游戏新增加的，需要翻译的文件到 `original` 文件夹中
    4. 运行 `_overwriteLocalizationByOriginal.py` 脚本，将 `original` 中出现变化的文件覆盖到 `localization` 文件夹中
7. 将 `localization.old/graphics` 文件夹下的可以复用的图像和字体资源复制到 `localization/graphics` 文件夹中
8. 在使用Paratranz脚本前，请确保词条对照表文件 `para_tranz/para_tranz_map.json` 已经更新
     - 请联系 jn_xyp 以获取最新的 `para_tranz_map.json` 文件
9. **参照[Paratran版本管理指南](docs/paratranz/tut_admin.md)步骤，从 git 导入新的原文到 ParaTranz**
     - 在导入前必须下载最新的 ParaTranz 平台数据作为备份
     - 首先导入原文，然后导入译文
       - 必须在导入时选择**安全模式（不删除词条）**！
10. **参照[Paratran版本管理指南](docs/paratranz/tut_admin.md)中的步骤，将翻译完成的译文导入 git**
11. 提交commit并push
     - 在提交前，请将汉化文件复制入游戏，尝试能否正常启动游戏
12. 待测试通过后，可以考虑将更改合并到`master`分支
     - 在合并前，请在`master`分支上创建以上一个版本号命名的tag，例如`0.97`

[flow-chart]:docs/paratranz/flow_chart.png

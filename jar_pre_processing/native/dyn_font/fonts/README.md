# dyn_font 字体源资产

本目录存放**动态字体系统**（运行时按 UI 缩放生成字体图集，见
[../../../docs/dynamic-font.md](../../../docs/dynamic-font.md)）
的本地构建输入：TTF 字体源文件，以及构建时生成的 GPOS kerning 固化表。

`build.py` 的 `jar` 步骤依据 `../assets.json` 同步所需 kerning，再把清单引用的字体与
固化表确定性打包为入库的单文件数据包
`localization/graphics/fonts/dyn_font/typefaces.dat`（格式见 build.py 的打包函数与
native 侧 `src/pack_reader.cpp`）随汉化包分发；运行时 native 库从 data 包
读取，不再依赖散装文件。

**TTF 与 `*.kern.txt` 均不入 git**（见 `../.gitignore`）。构建前需将下列字体放入
本目录。权威来源为 fnt_composer 仓库的 `source/` 目录（与静态字库工具链共用
同一批文件）。

## 所需字体清单

| 文件名 | 用途 | 备注 |
|---|---|---|
| `lte50549.ttf` | insignia 系西文（Insignia 矢量原稿） | 游戏自带（`starsector-core/graphics/fonts/`） |
| `方正兰亭中粗黑.ttf` | insignia 系中文（master 分支正文字体） | 方正兰亭中粗黑（FZLanTingHeiS-DB1-GBK） |
| `Orbitron-VariableFont_wght.ttf` | orbitron 系西文（可变字重轴 wght 400~900） | Google Fonts，SIL OFL |
| `锐字逼格青春粗黑体简2.0.TTF` | orbitron / victor 系中文（BigYoungBoldGB2.0） | 锐字家族 |

victor 系现与 orbitron 系共用 Orbitron VF + 锐字，因此旧的 `victor-pixel.ttf` 与
`ZpixEX2_EX.ttf` 已从构建输入和分发数据包移除。

## kerning 固化表（自动生成，不入 git）

`Orbitron-VariableFont_wght_w{800,900}.kern.txt`——Orbitron 的字偶距在
GPOS（FreeType 运行时读不到），由 `../tools/export_kerning.py` 离线导出为 font units，
native 生成时按渲染字号像素化。

当前 orbitron 五套及 victor14/victor16 使用 w800 表，victor10 使用 w900 表。无需手工维护
字重列表：`python build.py dynfont` 从编译后的 native 规格刷新 `../assets.json`；`dynfont`
与 `jar` 步骤都会按清单生成所需表，删除未引用表，并且只打包清单中的表。

注：

- 宋体（font-simsong）/ 综艺体（font-zongyi）分支将 insignia 系中文替换为
  对应分支字体，其余文件相同。
- 玩家可编辑的字表 `chars.txt` 不在本目录——它直接位于分发位置
  `localization/graphics/fonts/dyn_font/chars.txt`（与 data 包并列的两个
  运行时文件之一）。

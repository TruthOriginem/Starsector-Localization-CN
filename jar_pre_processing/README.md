# Jar 预处理工具

本工具用于对《远行星号》游戏 jar 文件进行汉化前预处理，输出可供 ParaTranz 工作流直接使用的 jar 文件。

启动优化的总体设计、决策流程、完整优化台账和兼容边界见
[docs/startup_optimization.md](docs/startup_optimization.md)；测量口径、关键 A/B 与当前基准见
[docs/startup_profile_runs.md](docs/startup_profile_runs.md)。

## 功能

预处理按顺序对 `starfarer.api.jar`、`starfarer_obf.jar`、`fs.common_obf.jar`
与 `fs.sound_obf.jar` 执行：

1. **ASM 字节码 Patch**：通过 [ASM](https://asm.ow2.io/) 库直接修改 `.class` 文件中的字节码，修复游戏原代码中与中文显示不兼容的逻辑（分隔符、字体、列宽、日期格式等），并注入中文输入法与动态字体钩子。各 Patch 的详细说明见下文。

2. **字符串解耦（jar-string-decoupler）**：调用 `vendor/jar-string-decoupler-1.0.0-all.jar`，将 `.class` 文件中硬编码的字符串常量提取并解耦，使 ParaTranz 的 jar 加载器能够读取、翻译并写回字符串，无需再手动修改字节码。该工具来自[jar-string-decoupler项目](https://github.com/jnxyp/jar-string-decoupler)。仅 `starfarer.api.jar` 与 `starfarer_obf.jar` 参与解耦；`fs.common_obf.jar` 和 `fs.sound_obf.jar` 只做 ASM 注入。

3. **运行时类注入**：把随本模块一起编译的运行时类追加进 `starfarer_obf.jar`——中文输入法（`org.fossic.starsector.ime.*`，见 [docs/ime-support.md](docs/ime-support.md)）、动态字体（`org.fossic.starsector.dynfont.*`，见 [docs/dynamic-font.md](docs/dynamic-font.md)）和优化 helper。启动计时 runtime 只有显式启用 `--profiling on` 才会注入；发布默认完全不携带。

`fs.common_obf.jar` 和 `fs.sound_obf.jar` 只过第 1 阶段、不做字符串解耦。
前者始终纳入分发，以便各字体变体互相覆盖安装时能清掉其它分支的动态字体
hook，避免残留 hook 找不到已被换走的运行时类而导致
`NoClassDefFoundError`。后者作为声音加载优化的 patch-only 输入/输出；没有相关
Patch 的分支可原样产出副本。

处理完成后，结果 jar 同时写入仓库根目录的 `original/` 和 `localization/`，
并在 `target/preprocess-work/preprocess-report.json` 生成处理报告（含输入/输出哈希、
各 Patch 结果）。如果新产物与现有 `original/` 的 entry 内容完全一致，工具会保留
现有 `original/` 和结构兼容的 `localization/`，避免仅因 ZIP 时间戳、压缩流或权限
字段变化而产生二进制 diff；内容实际变化、文件缺失或结构不兼容时仍会重置两个输出。

构建编排以 Python 为主（与仓库其它构建脚本一致）：`build.py` 负责编译 native 库
（g++ 编 `ssime.dll`、CMake+Ninja 编 `ss_dyn_font.dll`）、调用 mvnw 运行上述 Java
管线，以及全部产物复制分发；Maven 只负责 Java 部分。`jar` 步骤固定先执行
`mvnw clean compile`，避免 IDE/增量编译目录中的旧 helper 或错误桩被注入发布 Jar。

## 环境要求

- **JDK 17+**（`JAVA_HOME` 指向之；Maven Wrapper 已内置，无需单独安装 Maven）
- **Python 3.10+** 与 **fontTools**（构建编排及 kern/GPOS kerning 固化表生成）
- **MinGW-w64 g++、CMake、Ninja**（PATH 中；仅重编 native 库时需要）

## 使用方法

在 `jar_pre_processing/` 目录下执行：

三个步骤（`ime` / `dynfont` / `jar`）可单独、组合或用 `all` 全量运行
（执行顺序固定为 native 先于 jar，与输入顺序无关）：

```bash
python build.py                  # 日常流程 = jar：Java 管线 → 分发（用已提交的 native 库）
python build.py ime              # 只重编输入法原生库 ssime.dll
python build.py dynfont          # 重编动态字体，并刷新资产清单与 kerning
python build.py dynfont jar      # 重编动态字体库后走完整流程
python build.py all              # 全部
```

`jar` 步骤支持按功能组选择 ASM patch。默认启用全部优化，但不注入 profiling；
基准或诊断构建必须显式使用 `--profiling on`：

```powershell
python -X utf8 build.py jar --optimizations all
python -X utf8 build.py jar --optimizations none
python -X utf8 build.py jar --optimizations fast-text,resource-locks,rules-id-index
python -X utf8 build.py jar --disable-patch-group texture-cache
python -X utf8 build.py jar --profiling on
```

完整优化组、依赖、JVM 调试属性、缓存失效/清理规则和各项兼容边界统一见
[docs/startup_optimization.md](docs/startup_optimization.md)。选择器不会自动启用依赖，也不会在禁用
一个组时递归关闭依赖方；缺依赖、冗余禁用和未知组会在改写 Jar 前汇总报错。请求项、严格
校验后的最终启用组及显式禁用组会写入 `target/preprocess-work/preprocess-report.json`，构建和
A/B 核对应以该报告为准。

Java 单元测试单独执行：

```powershell
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
.\mvnw.cmd test
```

新增运行时优化时，具体算法应放在 `org.fossic.starsector.optimization`，ASM Patch 只保留
结构校验和最小桥接。采用测试优先流程：先加入会失败的语义/回归测试并确认红灯，再实现
helper，最后运行全套测试和实际游戏 profiling。完整决策与验证流程见
[docs/startup_optimization.md](docs/startup_optimization.md#决策与验证流程)。

native 库是提交入库的预编译产物，日常构建不重编（编译器升级会产生无关的
二进制 diff）；重编是显式操作（`ime` / `dynfont` 步骤），并连同产物一起提交。
`dynfont` 首次构建会自动 clone 锁定 commit 的 FreeType 2.13.2 源码（需网络）；
之后每次都会拒绝错误版本或含本地改动的 checkout。该步骤会先构建并运行全部
native CTest，通过后才覆盖预编译 DLL；还会从刚编译的
`dynfont_cli --list-assets` 刷新并提交 `native/dyn_font/assets.json`；`dynfont` 与 `jar`
都会据此自动生成当前字重所需的 kerning 表并删除旧表。`*.kern.txt` 是忽略的生成物。

**前置条件**：仓库根目录的 `game data/` 下需存在待处理的原版 jar 文件：
- `game data/starfarer.api.jar`
- `game data/starfarer_obf.jar`
- `game data/fs.common_obf.jar`
- `game data/fs.sound_obf.jar`

**输出**：
- `original/` 与 `localization/` 下的 `starfarer.api.jar`、`starfarer_obf.jar`、
  `fs.common_obf.jar` 和 `fs.sound_obf.jar`
- `localization/native/windows/`：`ssime.dll` 与 `ss_dyn_font.dll`（两个 native 库，build.py 分发）
- `localization/graphics/fonts/dyn_font/typefaces.dat`（动态字体源资产单文件数据包，build.py 确定性打包并入库；
  与入库的 `chars.txt` 一起构成该目录仅有的两个分发文件）
- `target/preprocess-work/preprocess-report.json`（处理报告）
- `target/preprocess-work/reports/*.decoupler.json`（解耦报告）

构建脚本单元测试：

```powershell
python -X utf8 -m unittest discover -s tests -v
```

## 目录结构

```
jar_pre_processing/
├── build.py                           # 构建编排入口（native 编译 + Java 管线 + 分发）
├── src/main/java/org/fossic/starsector/
│   ├── preprocessing/
│   │   ├── JarPreProcessorMain.java   # Java 管线主入口
│   │   ├── JarWorkspace.java          # 路径管理与文件 IO
│   │   ├── JarRewriter.java           # ASM Patch 调度器
│   │   ├── DecouplerRunner.java       # jar-string-decoupler 调用
│   │   ├── RuntimeClassInjector.java  # 运行时类注入（ime / dynfont / startup / optimization）
│   │   ├── PatchGroup.java            # 组目录、类型与组间依赖
│   │   ├── PatchSelection.java        # 严格解析和校验组选择
│   │   ├── PatchRegistry.java         # 有序注册所有 Patch
│   │   ├── JarPatch.java              # Patch 接口
│   │   └── patches/                   # 各具体 Patch 实现
│   ├── ime/                           # 中文输入法运行时（注入 obf jar，见 docs/ime-support.md）
│   ├── dynfont/                       # 动态字体运行时（注入 obf jar，hook 在 fs.common_obf.jar）
│   └── optimization/                  # 可单测的运行时优化算法
├── src/test/java/                     # JUnit 单元与回归测试
├── native/
│   ├── ime/
│   │   ├── ssime.cpp                  # 输入法原生库源码（IMM32 / JNI）
│   │   └── ssime.dll                  # 预编译产物（提交入库）
│   └── dyn_font/                      # 动态字体原生库（CMake 工程，FreeType 静态链接）
│       ├── assets.json                # native 规格导出的资产依赖清单（提交入库）
│       ├── src/                       # 渲染/装箱/写出全流程（金标准：fnt_composer）
│       ├── fonts/                     # 本地生成资产（TTF + kerning 均不入库，按清单打包）
│       ├── tools/                     # kerning 导出与金标准 diff 工具
│       └── ss_dyn_font.dll            # 预编译产物（提交入库）
├── vendor/
│   └── jar-string-decoupler-1.0.0-all.jar
├── docs/                              # 输入法、动态字体与启动优化文档
└── pom.xml
```

## 添加新 Patch

1. 在 `patches/` 目录下新建实现 `JarPatch` 接口的类。
2. 由 Patch 自身的 `group()` 返回所属 `PatchGroup`；分组事实不要重复写在 Registry。
3. 若需新组，在 `PatchGroup` 中定义稳定 ID、类型和直接依赖。依赖只做严格校验，
   不会隐式启用或禁用其它组。
4. 在 `PatchRegistry` 的有序 catalog 中按原版字节码所需顺序注册工厂。
5. 补充 Registry 顺序、组声明和选择失败路径的单元测试。

---

# 修改记录

本节记录当前版本中各 ASM Patch 的修改背景与 diff，以及其他需要手动维护的配置文件改动。

> 以下所有 diff 方向均为：`game data/`（未修改的游戏原文件） → `original/`（已手动修改后的版本），
> 即 `-` 行为游戏原始内容，`+` 行为我们的修改结果。

游戏本身的代码逻辑需要修改，以适应翻译后的文本。

### 6. 舰船信息页文本末尾丢字

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/ShipInfoSeparatorPatch.java`

![line_end_char_missing-1.png](docs/line_end_char_missing-1.png)
![line_end_char_missing-2.png](docs/line_end_char_missing-2.png)

**原因**：游戏原代码在列举多个词条时，每项末尾追加 `", "`（英文逗号+空格，2字符），再统一截去末尾2字符。为了使用中文全角逗号作为分隔符，需要将分隔符改为 `"，"`（1字符），并同步调整去尾长度，否则会多截或少截字符。

**修改方案**：将分隔符从 `", "` 改为 `"，"`，并将 `substring(0, length()-2)` 改为 `substring(0, length()-1)`。

**涉及文件**（均在 `starfarer_obf.jar`）：

**`starfarer_obf.jar:com/fs/starfarer/campaign/ui/S.class`、`starfarer_obf.jar:com/fs/starfarer/ui/newui/FleetMemberRecoveryDialog.class`、`starfarer_obf.jar:com/fs/starfarer/ui/newui/G.class`** — 各1处，模式相同：

```diff
- string = String.valueOf(string) + mod.getDisplayName() + ", ";
+ string = String.valueOf(string) + mod.getDisplayName() + "，";
  ...
- string = string.substring(0, string.length() - 2);
+ string = string.substring(0, string.length() - 1);
```

**`starfarer_obf.jar:com/fs/starfarer/ui/impl/StandardTooltipV2.class`** — 当前 0.98 `game data/` 与 `original/` 中未发现上述目标模式，预处理脚本仅保留 guard 检查；若后续版本重新出现该模式，strict 模式应失败并要求补充 patch。

**`starfarer_obf.jar:com/fs/starfarer/ui/impl/FleetMemberOrdnancePanel.class`** — 共3处，前两处为武器/插件列表，第三处含 `(D)`/`(S)` 标记：

```diff
// 武器/插件列表（前两处，变量名略有不同）
- object10 = hashMap.get(string5) + "×" + " " + string5 + ", ";
+ object10 = hashMap.get(string5) + "×" + " " + string5 + "，";
  if (...last element...) {
-     object10 = ((String)object10).substring(0, ((String)object10).length() - 2);
+     object10 = ((String)object10).substring(0, ((String)object10).length() - 1);
  }

// 改装列表（第三处，含 D-Mod/S-Mod 标记）
- object4 = mod.getDisplayName() + ", ";
+ object4 = mod.getDisplayName() + "，";
  if (bl8) {
-     object4 = mod.getDisplayName() + " (D), ";
+     object4 = mod.getDisplayName() + " (D)，";
  } else if (bl9) {
-     object4 = mod.getDisplayName() + " (S), ";
+     object4 = mod.getDisplayName() + " (S)，";
  }
  if (...last element...) {
-     object4 = ((String)object4).substring(0, ((String)object4).length() - 2);
+     object4 = ((String)object4).substring(0, ((String)object4).length() - 1);
  }
```

---

### 7. 敌对活动事件名称为英文 'Hostilities'

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/FactionHostilityNoManualPatch.java`

> **0.98 中不再适用**：当前 `original/starfarer.api.jar` 中仍保留 `Hostilities`，但 ParaTranz 导出数据中已有 `"Hostilities" -> "敌对活动"` 译文。后续预处理应依赖 `jar-string-decoupler` 解耦后由 ParaTranz 写回，不再作为 ASM 或手动替换项处理。

相关文件：`starfarer.api.jar: com/fs/starfarer/api/impl/campaign/intel/FactionHostilityIntel.class`

旧版本中代码直接引用了事件 tag `Tags.INTEL_HOSTILITIES`，曾计划通过手动修改返回值处理。

![hostilities_intel_title.png](docs/hostilities_intel_title.png)
![hostilities_intel_title-code.png](docs/hostilities_intel_title-code.png)

**当前处理方式**：不再手动修改此 class，保留原文字符串并交由 ParaTranz 流程写入译文。

---

### 8. 战斗页面舰船部署提示字体不显示

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/CombatDeploymentFontPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/class/new/return.class`

![combat-ship_deployment_note.jpg](docs/combat-ship_deployment_note.jpg)
![combat-ship_deployment_note-after.jpg](docs/combat-ship_deployment_note-after.jpg)

**修改**：将字体从 `graphics/fonts/victor21.fnt` 改为 `graphics/fonts/victor16.fnt`。该类共有 2 处此字符串引用，均被替换。

```diff
- d d2 = new d(string, "graphics/fonts/victor21.fnt");
+ d d2 = new d(string, "graphics/fonts/victor16.fnt");
```

---

### 9. 战役界面左上角日期显示宽度不足

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/CampaignDateWidthPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/campaign/ui/Oo0o.class`

![campaign_date_overlap.png](docs/campaign_date_overlap.png)

**修改**：
1. 日期显示末尾加上 `"日"` 字。
2. 调整各显示元素宽度：年份/周期 60→100，月份 38→50，日期 35→50，整体组件 135→150。

```diff
- this.ø0Oo00 = new d(campaignClock.getDay() + ",", ...);
+ this.ø0Oo00 = new d(campaignClock.getDay() + "日,", ...);

- this.OOOo00.setSize(60.0f, ...);   // 年份/周期
- this.do.this$do.setSize(38.0f, ...); // 月份
- this.ø0Oo00.setSize(35.0f, ...);   // 日期
- this.setSize(135.0f, 28.0f);       // 整体
+ this.OOOo00.setSize(100.0f, ...);
+ this.do.this$do.setSize(50.0f, ...);
+ this.ø0Oo00.setSize(50.0f, ...);
+ this.setSize(150.0f, 28.0f);
```

---

### 10. 存档列表页存档保存日期未按中文格式化

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/SaveDateLocalePatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/campaign/save/LoadGameDialog$o.class`

![save_date_locale.png](docs/save_date_locale.png)

**修改**：将日期格式和 Locale 改为中文。

```diff
- SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE, MMM d, yyyy, hh:mm a", Locale.ENGLISH);
+ SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINESE);
```

---

### 11. 星球列表页部分列宽度不足

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/PlanetListColumnWidthPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/campaign/ui/intel/PlanetListV2.class`

**修改**：调整 SL（稳定点）和 Class（等级）列的宽度。

```diff
- this.øÓØ000.addColumn(..., "SL",    50.0f, ...);
- this.øÓØ000.addColumn(..., "Class", 65.0f, ...);
+ this.øÓØ000.addColumn(..., "SL",    75.0f, ...);
+ this.øÓØ000.addColumn(..., "Class", 60.0f, ...);
```

改动前后列宽对照：

| 列 | 改动前 | 改动后 |
|---|---|---|
| Name（名称） | 230+（浮动） | 230+（浮动） |
| Type（类型） | 270+（浮动） | 270+（浮动） |
| Location（位置） | 85 | 85 |
| Pop.（人口） | 60 | 60 |
| SL（稳定点） | 50 | 75 |
| Class（等级） | 65 | 60 |
| Hazard（危险度） | 75 | 75 |
| Dist（距离） | 60 | 60 |

---

### 12. 星系地图星系名称字体偏小

**对应 ASM Patch**：
- `src/main/java/org/fossic/starsector/preprocessing/patches/StarSystemMapFontPatch.java`

**背景**：星系地图的星系名称 label 在缩放时会在两档字体间切换：

- 缩放值 > 阈值 → 小字（原为 `graphics/fonts/victor10.fnt`）
- 缩放值 ≤ 阈值 → 大字（原为 `graphics/fonts/victor14.fnt`）

切换逻辑位于通用地图标记父类 `starfarer_obf.jar: com/fs/starfarer/coreui/A/ooOO.class`。

汉字在原始小字体下可读性较差，因此将两档字体整体上调一级。

**修改：两档字体整体上调**

涉及文件：`starfarer_obf.jar: com/fs/starfarer/coreui/A/ooOO.class`

Patch 先替换大字体 `victor14.fnt` → `victor16.fnt`，再替换小字体 `victor10.fnt` → `victor14.fnt`，避免新写入的小字体 `victor14.fnt` 被二次替换。

```diff
- object = f5 > this.ö00000() ? "graphics/fonts/victor10.fnt" : "graphics/fonts/victor14.fnt";
+ object = f5 > this.ö00000() ? "graphics/fonts/victor14.fnt" : "graphics/fonts/victor16.fnt";
```

---

### 13. 情报页优先标签汉化后重复

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/IntelPutFirstTagIdPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/campaign/comms/v2/EventsPanel.class`

**原因**：游戏按英文标签 ID 统计情报数量，但补充 `putFirst` 标签时误将配置中的
本地化显示名加入统计表。英文配置的 ID 与显示名相同，问题不会显现；汉化后会同时
出现例如 `New` 和 `新消息` 两个键，最终生成两个同名按钮。

**修改**：补充 `putFirst` 标签时使用配置 ID，显示按钮时仍使用本地化 `name`。

```diff
- countingMap.add(tagSpec.getName(), 0);
+ countingMap.add(tagSpec.getId(), 0);
```

Patch 只替换紧邻 `CountingMap.add(Object, int)` 的一处 getter 调用，并验证后续两处
合法的显示名读取保持不变。

---

### 14. 高 DPI 缩放下窗口模式错误隐藏边框

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/WindowDecorationPhysicalResolutionPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/combat/CombatMain.class`

**原因**：游戏用启动分辨率与 `Toolkit.getScreenSize()` 返回的桌面尺寸判断窗口是否铺满
屏幕。Windows 开启 DPI 缩放后，启动分辨率是物理像素，而 AWT 返回的是逻辑像素；
例如 2560×1600、缩放 150% 时会被报告为约 1707×1067，使 1920×1080 窗口被误判为
铺满屏幕并自动隐藏边框。

**修改**：改用 LWJGL 返回的物理桌面分辨率进行比较。仅修正自动隐藏边框的判定，
不改变真正全屏、显式无边框和 `alwaysUndecoratedAtFullscreen` 设置的行为。

> **维护备注**：预计游戏下一版本会在原版中修复此问题。本 Patch 属于临时兼容修复；
> 升级目标游戏版本时应优先检查原版实现，若上游修复已包含等价逻辑，则移除此 Patch。

```diff
- Toolkit.getDefaultToolkit().getScreenSize().width
- Toolkit.getDefaultToolkit().getScreenSize().height
+ Display.getDesktopDisplayMode().getWidth()
+ Display.getDesktopDisplayMode().getHeight()
```

---

### 15. 航行状态栏地形名称出现尾随逗号

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/TerrainStatusBarSeparatorPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/ui/newui/public.class`

**原因**：游戏按内部地形表的原始条目位置判断是否在名称后绘制分隔符，之后才跳过
名称为 `null` 的地形；较长或不可见的后续名称也可能被状态栏裁掉。此时最后一个可见
名称后会留下一个 ASCII 逗号。

**修改**：仅把该控件唯一的逗号常量改成一个空格。多个地形名称之间仍有间距，末尾
空格则不可见。这里刻意不改控制流、局部变量或 StackMap：游戏随附的、关闭字节码验证
的 Zulu 17 C1 编译器会在热点编译这个高度混淆的方法时因结构改写而崩溃。

```diff
- 如果当前条目不是原始表最后一项：绘制英文逗号
+ 如果当前条目不是原始表最后一项：绘制空格
```

---

### 16. 数据百科特殊类型武器错误通过其它类型筛选

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/CodexWeaponTypeFilterPatch.java`

相关文件：`starfarer.api.jar: com/fs/starfarer/api/impl/codex/CodexDataV2$21.class`

**原因**：原版武器通常用 `type=BALLISTIC/MISSILE/ENERGY` 表示基础类型，再用
`mountTypeOverride=HYBRID/COMPOSITE/SYNERGY/UNIVERSAL` 表示特殊挂载分类。原版筛选器
先按特殊挂载类型放行，再明确拒绝未选中的三种基础类型，最后默认返回 `true`。

部分 mod 直接把特殊类型写入武器的 `type`，且不设置 `mountTypeOverride`。此时
`getType()` 与 `getMountType()` 都是例如 `COMPOSITE`；未选择“复合”时，它仍会避开
三种基础类型的拒绝分支，并被末尾默认值错误放行到“能量”“通用”“光束”等列表。
按钮计数使用另一段严格判断，所以还会出现计数正确而列表内容错误。

**修改**：保留原版基础类型与特殊挂载类型可以交叉匹配的行为，只把末尾默认放行改成
对四种特殊 `getType()` 的严格检查。直接定义为复合、混合、协同或通用的 mod 武器必须
选中自身类型（或由前面的挂载类型判断命中）才能显示；普通基础类型及未知扩展类型仍
沿用原版回退行为，降低 mod 兼容风险。

注入逻辑不新增跳转、Label 或 StackMap frame，而是使用布尔按位组合完成判断，避免改写
该混淆类的控制流。测试直接读取 0.98a-RC8 原始类，校验原始锚点、四类字段访问和最终
无分支保护结构。

```diff
- 未被实弹、导弹、能量拒绝：默认显示
+ 若 getType() 是特殊类型：仅在对应类型已选择时显示
+ 其它类型：保留原版默认行为
```

---

### 17. 屏幕顶部提示的高亮位置偏移

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/TopMessageHighlightLayoutPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/campaign/ui/O00O$o.class`

**原因**：顶部消息在标签尚未取得最终宽度时就按当前文本计算高亮索引。中文文本会进入
CJK 排版路径，该路径可能在初始的零宽度布局中插入换行并删除断行空格。消息随后调用
`autoSize()` 恢复成最终单行文本，却不会重新计算之前保存的高亮范围，因此数字等动态
内容的颜色会落到错误字符上。

**修改**：在设置高亮前先调用一次 `autoSize()`，使高亮查找基于最终文本。消息显示阶段
原有的尺寸更新保持不变；Patch 不修改分支、局部变量或 StackMap，并严格校验原版创建、
设色和高亮调用的结构。

```diff
  label.setColor(baseColor);
+ label.autoSize();
  label.getRenderer().setHighlightColor(highlightColor);
  label.getRenderer().highlight(highlightText);
```

---

### 18. 牵引线缆船插提示框宽度异常

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/TowCableTooltipWidthPatch.java`

相关文件：`starfarer.api.jar: com/fs/starfarer/api/impl/campaign/TowCable.class`

**原因**：原版 `TowCable` 直接实现 `HullModEffect`，其 `getTooltipWidth()` 固定返回 `0`，
没有继承 `BaseHullMod` 的标准提示框宽度 `369`。该船插虽在原版中隐藏，仍可能被 Mod 引用；
中文可按字符换行，因此零宽度提示框会把描述排成一条很长的竖线。

**修改**：仅将 `TowCable.getTooltipWidth()` 的返回值改为原版标准宽度 `369`。Patch 严格
校验目标方法仍为唯一的 `FCONST_0; FRETURN` 指令序列，不改变其它船插或 Mod 自定义宽度。

```diff
- return 0.0f;
+ return 369.0f;
```

---

### 19. 大地图实体提示的势力与关系高亮错位

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/CampaignEntityTooltipHighlightLayoutPatch.java`

相关文件：`starfarer_obf.jar: com/fs/starfarer/ui/impl/F$2.class`

**原因**：实体提示先在 Label 尚未取得最终宽度时解析势力名和关系文本的高亮范围，之后
才调用 `autoSizeToWidth()`。中文文本在初始零宽度布局中会被临时插入换行；最终布局会
恢复正常文本，却不会重算已保存的高亮索引。因此势力色可能落到逗号上，而被换行拆开的
关系文本没有找到匹配范围，表现为关系颜色缺失。

**修改**：把原有的 `autoSizeToWidth()` 调用块移动到高亮解析之前。原宽度公式、调用次数、
高亮颜色、控制流、局部变量和 StackMap 均保持不变；Patch 同时校验目标 Label 字段、两项
高亮数组和宽度计算结构，原版代码发生漂移时中止构建。

---

### 20. 高亮颜色数组含空项时偶发崩溃

**对应 ASM Patch**：`src/main/java/org/fossic/starsector/preprocessing/patches/RendererHighlightColorNullPatch.java`

**原因**：底层文本渲染器允许单项高亮颜色为空，并在部分路径中把它视为“使用默认
高亮色”，但透明度重算的另一条路径会直接调用该空项的 `Color.getAlpha()`。模组若传入
含空项的颜色数组，便可能在界面刷新或存档期间触发 `NullPointerException`；原版代码也
存在同一缺陷，只是原版内容较少生成这种输入。

**修改**：在底层渲染器保存颜色数组前进行规范化。空数组引用仍原样保留；不含空项的
数组保持原对象；仅对含空项的数组创建副本，并以渲染器默认高亮色补齐，默认色意外为空
时退回白色。补丁把自包含的私有静态规范化方法直接加入目标类，不依赖动态字体运行时，
并严格校验目标字段、setter 和唯一数组写入结构。

```diff
+ label.autoSizeToWidth(width - margin * 2);
  label.getRenderer().highlight(factionName, relationshipText);
  label.getRenderer().setHighlightColors(factionColor, relationshipColor);
- label.autoSizeToWidth(width - margin * 2);
```

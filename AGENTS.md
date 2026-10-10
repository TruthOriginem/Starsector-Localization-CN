# AGENTS.md

本文件给 Codex / Claude Code 等代码代理提供仓库级工作指引。面向用户和译者的完整说明请优先参考 [README.md](README.md)。

## 项目概况

本仓库是《远行星号》（Starsector）中文汉化项目，当前目标版本为 **0.98a-RC8**。

ParaTranz 项目：[https://paratranz.cn/projects/3489](https://paratranz.cn/projects/3489)

## 目录约定

- `game data/`：未修改的游戏原始文件。
- `original/`：当前版本待翻译原文；通常只读，不要直接编辑。
- `localization/`：当前版本汉化结果；导入译文会写入这里。
- `original.old/`、`localization.old/`：上个版本快照；通常只读。
- `para_tranz/`：ParaTranz 导入导出脚本、映射和输出数据。
- `jar_pre_processing/`：Jar 预处理工具；详见 [jar_pre_processing/README.md](jar_pre_processing/README.md)。
- `docs/`：翻译、平台和迁移文档。
- `packaging/`：打包脚本与安装包素材。

## 编码和命令

- 默认使用 UTF-8。PowerShell、Python、Java 命令涉及中文文件时必须显式避免本地编码乱码。
- Python 命令优先使用 `python -X utf8 ...`（或本地实际可用的 Python 3 解释器）或显式 `encoding='utf-8'`。
- Java 命令优先使用 `-Dfile.encoding=UTF-8`；编译时使用 `-encoding UTF-8`。
- Windows PowerShell 中读写文本文件时使用 `-Encoding UTF8`。

## 常用命令

ParaTranz 在 `para_tranz/` 内使用 uv 和 Python 3.14.x：`uv sync --locked` 准备环境，`uv run --locked <命令>` 运行工具。
依赖变更须同步提交该目录下的 `pyproject.toml` 和 `uv.lock`。

ParaTranz 脚本（不带参数运行进入交互式菜单，带数字参数直接执行对应操作）。
进入 `para_tranz/` 后使用 `uv run paratranz`：

```powershell
uv run paratranz
uv run paratranz 1  # 导出：游戏文件 -> para_tranz/output
uv run paratranz 2  # 导入：para_tranz/output -> localization
uv run paratranz 2 --rebuild-jars  # 从预处理原文重新生成汉化 Jar
uv run paratranz 3  # 下载平台导出并导入，需要 .env
uv run paratranz 3 --rebuild-jars  # 下载后重新生成汉化 Jar
uv run paratranz 4 "com.fs.starfarer.api.SomeClass"
uv run paratranz 5 "search pattern"
uv run paratranz 6  # 格式化 para_tranz_map.json
```

子命令说明：

- `1` 导出：按 `para_tranz_map.json` 的映射，从 `original/` 和 `localization/` 读取原文与现有译文，生成 ParaTranz JSON 词条文件写入 `para_tranz/output/`，用于上传到平台。
- `2` 导入：将 `para_tranz/output/` 中的 ParaTranz JSON 词条（通常来自平台导出）写回 `localization/` 下的译文文件。
- `3` 下载并导入：通过 ParaTranz API 下载平台最新导出压缩包并解压到 `para_tranz/output/`，随后自动执行 `2`（写回译文）和 `1`（重新导出），需要在 `.env` 中配置 API Key。
- `4` 生成类映射：对指定的 jar 内类文件（接受 `starfarer.api.jar:com/.../Foo.class` 或 `com.fs.starfarer.api.Foo` 两种路径格式），提取其中所有字符串并打印可粘贴到 `para_tranz_map.json` 的类文件映射项，用于把新类纳入翻译范围。
- `5` 搜索字符串：在所有 jar 文件的类中查找指定原文字符串并打印所在类和位置，用于定位某句游戏文本出自哪个类。
- `6` 格式化映射：对 `para_tranz_map.json` 去重、排序并保存，修改该文件后建议运行一次以校验格式。

选项 `2`、`3` 可加 `--rebuild-jars`：仅对当前映射中的 Jar，以预处理后的
`original/` 为基底重新应用译文，清除删除词条、缩减白名单或移除类映射后的历史译文残留。
其他 loader 仍按原流程导入。此选项不执行 Java 编译或 Jar 预处理，不能替代上游更新后的完整
Jar 生成管线；使用前确保 `original/` 为当前分支的预处理产物。缺少对应 JSON 时跳过，空数组
则生成未翻译原文；单个 Jar 在临时目录成功生成后才替换正式文件，内容相同不替换。

### 补充缺失词条流程

当发现 jar 内某个字符串漏翻（未被 `para_tranz_map.json` 白名单收录）时，按以下步骤补充：

1. **定位来源**：若只知道原文内容、不知来自哪个类，用 `5` 搜索原文，得到所在 jar 与类。
2. **列出并对比**：用 `4` 生成该类的完整映射项，末尾会打印与现有 map 的**彩色对比**——`绿色`=已收录、`无色`=未收录（待补候选）、`黄色背景`=同时被非 string 属性引用，**无法自动写回，不可翻译**。据此挑出真正该补的显示文本，注意排除黄底项和内部 ID（`$xxx`、配置键等）。
3. **确认用途**（必要时）：用 CFR 反编译该类源码，确认候选字符串确实是玩家可见文本，而非内部标识。
4. **插入 map**：把选定字符串（若同值多次出现则用 `{"val": "...", "occurs": [...]}` 形式，与 `4` 生成的一致）加入该类 `include_strings`。
5. **格式化**：运行 `6` 去重排序。
6. **导出**：运行 `1` 重新导出，新词条会出现在 `para_tranz/output/` 中（`stage:0` 待翻译）。
7. **检查 diff**：`git diff` 确认改动只有预期的新增词条，无意外漂移。
8. **停下交由管理员**：将词条文件上传到 ParaTranz 平台翻译，平台确认后再运行 `3` 回写译文。

Jar 预处理：

```powershell
cd jar_pre_processing
.\mvnw.cmd compile exec:java
```

Lint / format（仅检查 `para_tranz/` 自有代码；在 `para_tranz/` 内运行，配置见该目录的 `pyproject.toml`）：

```powershell
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked ruff format .  # 应用格式化
```

类型检查（同样在 `para_tranz/` 内运行，包含测试和临时脚本）：

```powershell
uv run --locked ty check
uv run --locked ty check json_loader/alexson  # 单独检查子模块
```

默认只报告自有代码的诊断，仍解析 alexson 的类型。

Git 提交钩子：在仓库根目录运行一次
`uv run --project para_tranz --locked pre-commit install --config para_tranz/.pre-commit-config.yaml --allow-missing-config`。
仅暂存的 `para_tranz/` 文件触发上述三项检查，失败会阻止提交，不自动修改文件；新克隆需重新安装。
旧分支没有此配置时自动跳过；全量手动检查可在仓库根目录运行
`uv run --project para_tranz --locked pre-commit run --config para_tranz/.pre-commit-config.yaml --all-files`。

[ParaTranz CI](.github/workflows/paratranz-check.yml) 在相关文件的 push / PR 中自动运行
上述检查及下方两组测试（Windows / Python 3.14，排除存档分支）；全量导入导出仍在本地验证。

### Jar 与命令参数测试

在 `para_tranz/` 内运行：

```powershell
uv run --locked python -X utf8 -m unittest discover -s jar_loader/test
uv run --locked python -X utf8 -m unittest discover -s jar_loader/integration
```

第一条为隔离单元测试：参数解析直接传入参数列表；Jar 测试在临时目录生成最小 class 和 Jar，
不需要游戏文件、Java、平台凭据或网络，不改写项目日志和汉化产物。
第二条为集成测试：检查入口工作流和真实 TTBlackSite 回归，需要项目映射及
`original/starfarer.api.jar`；只在临时目录重建 Jar，不访问平台。
核心模块导入不初始化文件日志；脚本入口显式调用 `configure_logging()`，保持中文日志输出。

## ParaTranz 工作流

核心入口：

- `para_tranz/para_tranz_script.py`：交互式和命令行入口。
- `para_tranz/config.py`：路径、loader 开关、ParaTranz API 配置。
- `para_tranz/para_tranz_map.json`：文件和字符串映射配置。
- `para_tranz/output/`：导出的 ParaTranz JSON 数据。
- `para_tranz/utils/cli_args.py`：命令参数解析与分发。
- `para_tranz/logs/`：运行日志目录，在脚本启动时自动创建。

当前 loader：

- `csv_loader`：CSV 文件。
- `json_loader`：Alex 风格 JSON / `.faction` / `.skin` / `.variant` / `.skill`。
- `txt_loader`：纯文本文件，通常一个文件一条词条。
- `jar_loader`：预处理后 jar 内 `.class` 字符串。
- `java_loader`：Java 源码中的普通字符串字面量。

关于 ParaTranz 平台操作、译者流程和格式规范，请链接到现有文档，不要复制长篇说明：

- 管理员流程：[docs/paratranz/tut_admin.md](docs/paratranz/tut_admin.md)
- 译者指南：[docs/paratranz/tut_translator.md](docs/paratranz/tut_translator.md)
- 译文格式规范：[docs/paratranz/format_standard.md](docs/paratranz/format_standard.md)

## 修改注意事项

- 不要直接编辑 `original/`、`original.old/`、`localization.old/`，除非任务明确要求。
- 导入 ParaTranz 数据后，应检查 `localization/` 的 git diff 是否符合预期。
- 向 ParaTranz 平台导入词条时必须使用安全模式，避免删除平台词条。
- `original/` 下的 jar 已经过预处理，不等同于 `game data/` 的原始 jar。
- 修改 `para_tranz_map.json` 后可运行选项 `6` 格式化并校验。
- Git commit message 使用中文。
- 提交前尽量运行相关脚本或最小验证；涉及汉化结果时，最好复制到游戏目录测试启动。

### 上游更新后的 rebase 与 Jar 重建

- 当上游分支有更新时，`startup-optimization` 等包含独立 Jar 补丁或运行时注入的派生分支在 rebase 后，必须基于更新后的源码和当前分支配置重新运行完整 Jar 生成管线，再导入译文。即使 rebase 没有冲突，或上游只更新版本号，也不能直接沿用旧 Jar 作为本次发布产物。
- 对使用 `build.py` 的分支，在 `jar_pre_processing/` 下运行 `python -X utf8 build.py jar`，完成 Java 预处理、运行时注入及产物分发；随后在仓库根运行 `python -X utf8 para_tranz/para_tranz_script.py 2` 恢复译文。其他派生分支按其 `jar_pre_processing/README.md` 执行对应的完整 Jar 管线。原生库是否重编按其源码变更决定，不能省略 Jar 管线步骤。
- 禁止手动合并 Jar 修改，包括逐个合并、替换或移植 `.class` / ZIP 条目，以及拼接上下游 Jar 内容。发生 Jar 二进制冲突时，应先解决源码、配置和映射冲突，再由完整管线重新生成最终 Jar；不能把 Git 某一侧的旧 Jar 直接当作最终产物。
- 重建后检查产物与译文差异并完成相关验证，再提交生成结果、部署或打包。译文导入若校验失败，立即停止后续流程，待平台修正后重新同步，不得本地绕过校验。

## 分支和字体

- `master`：主分支，正文字体为兰亭黑体。
- `font-simsong`：宋体字体分支。
- `font-zongyi`：综艺体字体分支。
- `startup-optimization`：包含动态字体、中文输入法及启动优化的实验分支。
- `legacy-097-miko`、`legacy-097-miko-j23`：**仅存档**的 0.97 历史分支，不再维护。今后“所有分支”等批量操作默认不包含这两个分支，包括同步、版本更新、rebase / merge、重建、测试、推送、部署和打包；只有用户明确点名要求处理历史分支时才操作。

## 参考链接

- 项目说明和版本更新流程：[README.md](README.md)
- Jar 预处理细节：[jar_pre_processing/README.md](jar_pre_processing/README.md)
- 非标准 JSON 解析设计：[para_tranz/json_loader/DESIGN.md](para_tranz/json_loader/DESIGN.md)
- 临时脚本说明：[para_tranz/temporary_scripts/README.md](para_tranz/temporary_scripts/README.md)

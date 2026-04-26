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
- Python 命令优先使用 `py -3 -X utf8 ...` 或显式 `encoding='utf-8'`。
- Java 命令优先使用 `-Dfile.encoding=UTF-8`；编译时使用 `-encoding UTF-8`。
- Windows PowerShell 中读写文本文件时使用 `-Encoding UTF8`。

## 常用命令

ParaTranz 脚本：

```powershell
py -3 -X utf8 para_tranz\para_tranz_script.py
py -3 -X utf8 para_tranz\para_tranz_script.py 1  # 导出：游戏文件 -> para_tranz/output
py -3 -X utf8 para_tranz\para_tranz_script.py 2  # 导入：para_tranz/output -> localization
py -3 -X utf8 para_tranz\para_tranz_script.py 3  # 下载平台导出并导入，需要 .env
py -3 -X utf8 para_tranz\para_tranz_script.py 4 "com.fs.starfarer.api.SomeClass"
py -3 -X utf8 para_tranz\para_tranz_script.py 5 "search pattern"
py -3 -X utf8 para_tranz\para_tranz_script.py 6  # 格式化 para_tranz_map.json
```

Jar 预处理：

```powershell
cd jar_pre_processing
.\mvnw.cmd compile exec:java
```

Lint / format：

```powershell
ruff check .
ruff format .
```

## ParaTranz 工作流

核心入口：

- `para_tranz/para_tranz_script.py`：交互式和命令行入口。
- `para_tranz/config.py`：路径、loader 开关、ParaTranz API 配置。
- `para_tranz/para_tranz_map.json`：文件和字符串映射配置。
- `para_tranz/output/`：导出的 ParaTranz JSON 数据。

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

## 分支和字体

- `master`：主分支，正文字体为兰亭黑体。
- `font-simsong`：宋体字体分支。
- `font-zongyi`：综艺体字体分支。

## 参考链接

- 项目说明和版本更新流程：[README.md](README.md)
- Jar 预处理细节：[jar_pre_processing/README.md](jar_pre_processing/README.md)
- 非标准 JSON 解析设计：[para_tranz/json_loader/DESIGN.md](para_tranz/json_loader/DESIGN.md)
- 临时脚本说明：[para_tranz/temporary_scripts/README.md](para_tranz/temporary_scripts/README.md)

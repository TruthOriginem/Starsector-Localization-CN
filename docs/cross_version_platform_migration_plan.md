# 跨版本与跨平台 Jar 汉化迁移重构计划（TODO）

状态：待办规划，尚未开始实现。日期：2026-11-19。

工作分支：`refactor/product-build-pipeline`。本文件记录独立于 [多产品构建重构计划](product_build_refactor_plan.md) 之外的跨版本混淆迁移、类内定位增强及 Linux / macOS 平台支持规划，与既有设计文档 [jar_translation_plan.md](jar_translation_plan.md)、[linux_mac_platform_migration_plan.md](linux_mac_platform_migration_plan.md) 衔接并补充调研结论。

## 1. 背景与核心问题

当前汉化工具链在 Jar 翻译侧存在两项长期痛点：

1. **跨游戏版本迁移成本高**：`para_tranz_map.json` 与 ParaTranz 词条直接绑定 Windows 版混淆类名与类内常量顺序。版本升级后一旦混淆发生变化，大量白名单规则、已翻译词条和 `occurs` 筛选需要人工重新定位。
2. **跨平台支持缺失**：`starfarer_obf.jar` 在 Windows、Linux、macOS 平台分别独立混淆（`0.98a-RC8` 实测 Win/Linux 仅约 57% 类同名，且同名类中存在少量源码不同的名字碰撞），目前工具链只针对 Windows Jar 解析与写回，尚未稳定产出 Linux / macOS 汉化包。

调研结论表明：**“跨版本混淆迁移”（`Win v1 → Win v2`）与“跨平台混淆迁移”（`Win v1 → Linux v1 / Mac v1`）本质上是同一类字节码对齐问题**，并且可以在**不引入 Mod Loader / CoreMod 运行时框架**的前提下独立落地。

## 2. 外部方案参考与边界（NanoForged 生态调研）

对 [NanoForged](https://github.com/NanoForged) 组织相关项目（`NanoForge`、`SourceSector`、`Matcher`、`SSLoc-CN-CoreMod`）的调研结论如下：

| 项目 / 机制 | 可借鉴之处 | 当前局限或不适用的原因 |
| :--- | :--- | :--- |
| `Matcher`（字节码结构对齐） | 综合类名、继承树、字段/方法描述符、常量池、指令哈希与引用图自动对齐两个混淆 Jar，输出 Tiny v2 映射，跨平台与跨版本对齐准确率远高于纯字符串集合匹配 | 本身只是分析工具，不绑定任何运行时框架；适合引入作为离线迁移工具 |
| `SourceSector` / 中间名层 | 建立稳定中间命名空间（`obf ↔ intermediary ↔ named`），把配置与易变的混淆类名解耦 | 全量反混淆和运行时重映射工程量较大；本仓库不一定采用运行时重映射方案 |
| `SSLoc-CN-CoreMod`（运行期替换） | 构建期转表 + `Auditor` 对纯净原始 Jar 做 100% 常量池与 `extra_ref` 审计，防止游戏更新后词条静默漂移 | 运行期按扁平 `Map<原文, 译文>` 替换，**无法处理同一类内同值异译（本仓库实测 11 组高亮花括号差异）及部分 `occurs` 不翻译（本仓库实测 17 处跳过前序 occurrence 的筛选）**，且强依赖 NanoForge 加载器 |
| `NanoForge` OS 分支审计 | 实查 0.98a-RC8 反编译源码发现跨平台逻辑分支极少，仅通过 `-Dcom.fs.starfarer.settings.linux/osx` 切换内存诊断与 GL 设置 | 若直接在 Linux / macOS 共用 Windows 汉化 Jar，仍需先在真机验证图形/路径/原生库兼容性，并确认分发边界 |

本计划明确：**不强制绑定 Mod Loader 或运行期 CoreMod 方案**，优先沿用离线处理或轻量安装期补丁的路线，但吸收 `Matcher` 结构对齐、中间名解耦与构建期 100% 审计的工程实践。

## 3. 目标与范围

### 纳入范围

- 实现 `para_tranz/jar_loader` 的类内出现序号（`occurrence_index`）支持，补齐“同值部分翻译 / 同值异译”的表达能力（对应原 [jar_translation_plan.md](jar_translation_plan.md) 阶段一）。
- 引入跨混淆 Jar 类/方法对齐机制（优先评估 `Matcher` + 增强指纹），服务于**跨版本升级迁移**与**跨平台 Jar 生成**。
- 强化版本迁移定位粒度：结合“对齐后的方法 + 方法内出现序号 / 指令上下文”，降低类内方法增删对 `occurs` 序号的破坏。
- 评估并落地 Linux / macOS 平台汉化支持路径（共用 Windows 汉化 Jar 实机验证，或跨平台对齐后生成各自平台 Jar）。
- 建立迁移与构建期 100% 常量池/词条审计门禁，暴露失效、漂移与低置信度匹配。

### 暂不纳入

- 不在本计划中引入 NanoForge / LiteLoader / JavaAgent 作为玩家运行游戏的前置条件。
- 不改变现有 [多产品构建重构计划](product_build_refactor_plan.md) 的 P1—P11 阶段边界。两者属于正交重构方向（前者解决横向多产品构建与目录隔离，本计划解决纵向跨版本与跨平台迁移），在以下接口节点保持错峰衔接：
  - **ParaTranz 改造顺序（P4 与阶段 A）**：先完成 `product_build_refactor_plan.md` 的 P4（路径上下文隔离，保持既有 key 与定位语义不变用于基线对照），再推进本计划阶段 A 的 `occurrence_index` 与 `context` 改动。
  - **打包目录参数化（P8 与阶段 B）**：`product_build_refactor_plan.md` 的 P8 取消分支名猜测并改为消费产物清单后，阶段 B 可将 Linux / macOS 平台目录作为正交维度直接接入打包流水线。
  - **可独立并行的预研项**：阶段 B1（Linux / macOS 共用 Windows 汉化 Jar 真机测试）与阶段 C 的 `Matcher` 跨版本匹配率实验不修改仓库源码，可随时并行推进。

## 4. 技术方案与核心待办

### 4.1 阶段 A：类内精确定位补强（前置基础）

- [ ] 落实 `occurrence_index` 维度与 `JarString` 抽象（继承 [jar_translation_plan.md](jar_translation_plan.md) 阶段一设计）。
- [ ] 在不影响唯一原文旧 key 兼容性的前提下，升级重复原文词条的 key 与 `context` 元数据（补充所在方法签名及方法内出现序）。
- [ ] 为 `17` 处跳过前序 occurrence 的筛选规则及 `11` 组同值异译条目补全回归测试。

### 4.2 阶段 B：跨平台支持评估与落地（Windows → Linux / macOS）

两条备选路径需在真机测试后定案：

- **路径 B1（共用验证路径）**：保留 Linux / macOS 原生库与启动脚本（含 `-Dcom.fs.starfarer.settings.linux=true` / `osx=true`），实测直接加载 Windows 汉化 `starfarer_obf.jar` 与 `starfarer.api.jar` 的稳定性与渲染/输入表现。
- **路径 B2（独立平台 Jar 生成路径）**：以 Windows 为唯一 ParaTranz 翻译事实源，通过 `Matcher`（或“字符串 multiset + 父类/描述符 + API 引用边”指纹）产出固化的 `docs/cross_platform_mapping_<platform>.json`，按 [linux_mac_platform_migration_plan.md](linux_mac_platform_migration_plan.md) 将译文与 `occurs` 精确写回目标平台原版 Jar。

待办事项：

- [ ] 收集目标版本（当前 0.98a-RC8）纯净 Linux 与 macOS 原版包，核对 `starfarer.api.jar` 与 `starfarer_obf.jar` 差异。
- [ ] 实机验证路径 B1 是否存在隐藏 JNI、路径或窗口兼容问题；对比确定主推路径（B1 或 B2）。
- [ ] 如采用路径 B2，用 `Matcher` 验证 Win → Linux / Win → Mac 自动对齐率与同值常量序稳定性，建立人工复核低置信度类的报告流程，并接入 `packaging/` 多平台打包。

### 4.3 阶段 C：跨版本混淆迁移流水线（应对后续大版本更新）

- [ ] 调研并验证 `Matcher` 对历史版本（如 `0.97 → 0.98a-RC8`）混淆 Jar 的类与方法自动匹配率。
- [ ] 设计基于映射表的自动迁移脚本：输入 `old_jar`、`new_jar`、`Matcher` 映射、`para_tranz_map.json` 与 `para_tranz/output/`，自动产出：
  - 更新后的新版 `para_tranz_map.json` 候选（含迁移后的类名与重新对齐的 `occurs`）；
  - 可直接继承的旧版译文与 `stage` 快照；
  - 人工复核清单（低置信度类、原文变更词条、方法签名变化导致 `occurs` 无法确认的条目、新增未映射类）。
- [ ] 评估是否引入轻量的稳定逻辑 ID 层（中间名映射）存储或校验映射变更，减少后续大版本对表工作量。
- [ ] 引入构建期自动化审计命令（参考 `SSLoc-CN-CoreMod` 的 `Auditor`）：对生成配置和词条执行 100% 常量池存在性与 `extra_ref` 安全检查。

## 5. 需要后续确认的决策点

| 时点 | 待定决策 | 备选方向 |
| :--- | :--- | :--- |
| 启动前 | 本计划与多产品构建重构计划的执行顺序 | 建议先完成 `product_build_refactor_plan.md` 核心重构冻结工作区边界，再开展本计划阶段 A；阶段 B 的 Linux/Mac 真机验证可提前并行 |
| 阶段 B 调研后 | 跨平台发布采用共用 Win Jar 还是各平台独立重写 Jar | 优先以真机验证结果与分发规范为准；独立重写各平台 Jar 安全性更高 |
| 阶段 C 启动前 | 迁移基准是否引入中间名层，以及是否保留仅针对混淆 Jar 的 Python/Java 混合工具链 | 可先用 `Matcher` 产出一次性版本间 Tiny 映射配合 Python 迁移脚本，验证效果后再决定是否长期维护中间名表 |

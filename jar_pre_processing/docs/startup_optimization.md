# Starsector 启动优化

更新时间：2026-08-02。本文是启动优化的维护入口，说明总体设计、决策方法、
优化取舍、兼容边界和后续工作。可复算的基准与关键 A/B 数据单独保存在
[startup_profile_runs.md](startup_profile_runs.md)。原始游戏版本为 `0.98a-RC8`，实验实现位于
`startup-optimization` 分支。

## 总体设计

- 优化在 Jar 预处理期以 ASM Patch 挂接，按 `PatchGroup` 独立开关并显式声明依赖。
  Patch 只做原版结构验证和最小桥接，算法放在 `org.fossic.starsector.optimization`
  中以单元测试覆盖。
- 实现分为低分配算法、缩小锁竞争、有界并行、内容寻址缓存和无效日志路径
  消除。不扫描未启用 mod，不改资源覆盖顺序，不把启动成本转移到首次战斗或纹理 bind。
- 缓存均以内容和实现版本作为失效条件，完整性校验失败时 fail-soft 回退原路径；
  定期清理只管理当前构建实际打包的缓存 namespace。
- profiling 是独立、默认关闭的注入组；发布 Jar 不包含计时 runtime、JFR 触发点或
  为测量增加的日志。
- 目标是在保持原版和 mod 可观测语义的前提下缩短启动；不修改 mod 自身算法，
  不为了单次基准默认改变线程数或 JVM 内存。

## 决策与验证流程

1. 对原版字节码、反编译源码、mod 交互和 JFR/分阶段日志做白盒定位，先证明候选点
   位于关键路径，不只根据采样热点判断。
2. 先写 helper 的语义、异常、并发或缓存失效测试并确认红灯，再实现逻辑；
   Patch 另用真实 class fixture 验证指令形状、计数和链接。
3. 同一工作树构建开/关组 Jar，暖机后交错 A/B，记录三轮中位数、CV、分阶段耗时
   和分配。缓存填充收益与热启动收益分开报告。
4. 通过功能组隔离、`none/all`、大型内容 mod、GraphicsLib/BoxUtil 等交互做兼容门禁；
   不可安全回退或改变运行时语义的方案不默认启用。
5. 仅在收益可复现、语义边界可测试且实际游戏回归通过时保留。中性项只能因确定的
   分配/CPU/复杂度收益保留；回退、高风险或不可归因方案撤回并在台账留下原因。

## 测量口径与已确认瓶颈

- 正式终点是 `TitleScreenState` 首个 `Display.update(true)` 返回，而非日志的 `Reading save data`。
  后者只是 Campaign 存档描述扫描，之后仍有 Codex、shader、战斗场景和标题准备工作。
- 标准样本为 `javaw.exe` 直启、1280×720、`startSound=true`、`-Xms8g -Xmx8g`
  与 `AlwaysPreTouch`、11 个固定 mod、非首次启动缓存；一次暖机后取三次 repeat 中位数。
  JFR 用 `settings=profile,stackdepth=256`，热点仅统计首帧前。
- 阶段有并行/嵌套，不能相加。跨锁屏、解锁、Jar、缓存状态或 mod 版本的绝对时间不能相减；
  单项结论只采用同源码/同 Jar 的交错 A/B。
- GC 不是瓶颈：启动采样中暂停仅毫秒级。主要负载是小文件 I/O、脚本/Janino、纹理解码与转换、
  声音解码及资源锁竞争；高分配会增加 CPU、内存带宽和 JIT 压力，但不是 STW 问题。
- 原始 11-mod 代表运行首帧前估算分配为 15.822 GiB（`byte[]` 12.235 GiB）；资源项、
  SpecStore、mod 回调分别为 7.743、5.882、1.854 GiB。主要链为 `LoadingUtils`、
  raster 副本/像素转换、JOrbis、Rules、Janino 与 PNG。

## 当前结论

在 11-mod、8 GiB、热/半热缓存的可比条件下，O37 的全部关闭→全部启用 exact A/B 中位数为
**21.943→9.042 s（-12.901 s，-58.79%，2.43×）**；`ResourceLoader` 为
18.966→5.965 s（-68.55%）。这不是冷缓存、49-mod 或其它硬件的承诺倍率。

O38 在 Polaris v0.4.3 条件下撤回 O13 后，三次 JVM→首帧为 **9.004 / 8.920 / 9.036 s**，
中位数 **9.004 s**、CV **0.66%**；相对 O37 全启用 9.042 s 为 -0.038 s，属噪声。
因此撤回 O13 **没有可测性能损失**，但 Polaris 条件变化，不能把它称为严格 exact A/B。

O11 结果通知已撤回，主线程恢复原版 10 ms 轮询；O16/O27 仅保留多 worker 领取、
同路径串行和代际隔离所需的协调。49-mod 热缓存同机 A/B 中，撤回前中位数 **22.387 s**，
撤回后三轮中位数 **22.206 s**（-0.81%，CV 0.51%），无可测回归。

## 缓存与首次启动

- O20 纹理、O22 PCM 和 O33 Janino 属于暖缓存优化；首次启动需要执行原路径并
  原子写入缓存，不应使用热启动倍率作为首次启动承诺。
- 一次 49-mod、Jar 替换导致缓存失效后的实际观测为 **99.294 s**，随后为
  33.966 s、25.241 s，最终稳定在约 22.2 s。该轮没有标准化清空 OS 页缓存，只用作
  更新后首启体感，不与正式热启动 A/B 混算。
- 缓存失效、容量限制和自动清理属于各功能组自身语义；只打包部分优化组时，
  未打包组的缓存不会被其他组误删。

## 优化列表与决策台账

“墙钟中性”不等于无价值：已保留项可能降低确定分配、CPU 或复杂度上限。百分比均为该项当时的
同场对照，除非明确写“相对 A0”。

| 项 | 状态 | 关键结论与约束 |
| --- | --- | --- |
| O01 | 保留 | 低分配 UTF-8 reader；相对 A0 -2.262 s/-7.46%，分配约 -4.758 GiB。必须保持 BOM、孤立 CR、跨块 UTF-8 与异常优先级。 |
| O02 | 保留（O03 前置） | resource leaf 去 monitor，单独墙钟中性；不改变一次性资源 selector/skip-mod。 |
| O03 | 保留 | 高层查询短锁和根快照；相对 O02 -3.045 s/-10.81%，约 4 s monitor wait 消失。短锁内原子消费状态，锁外 I/O。 |
| O04 | 保留 | CSV 错误行延迟 pretty-print；墙钟中性，目标分配 113.221→3.685 MiB；保持错误 JSON/文本。 |
| O05 | 保留 | Rules 按 trigger 的 ID Set；相对 O04 -1.310 s/-5.21%，热点 72→0；保留列表和异常语义。 |
| O06 | 保留 | 纹理整行批量转换；相对 O05 -1.446 s/-6.07%，纹理样本 -55.42%；保持 RGB/RGBA、padding、翻转和派生颜色。 |
| O07 | 否决 | 声音 worker 2→4 +0.138 s，join 仅 1.756 ms；不以热点替代关键路径分析。 |
| O08 | 保留 | 标准图像直接读 backing raster；墙钟中性但分配约 -0.922 GiB；子类/`TYPE_CUSTOM` 回原语义。 |
| O09 | 否决 | 直接读 byte backing array 虽去热点，exact A/B +0.330 s/+1.47%。 |
| O10 | 保留 | 资源列表线性稳定分区；目标 50.667→13.877 ms/-72.61%，以 identity 验证精确保序。 |
| O11 | **撤回** | 移除结果通知，恢复原版 10 ms 轮询；49-mod 撤回 A/B -0.81%，无可测回归。O16/O27 的并发协调不属于 O11，继续保留。 |
| O12 | 否决 | ScriptStore 核心队列去重移除 11,210 次探测，但 A/B +0.053 s；后台工作不在关键路径。 |
| O13 | **否决并撤回** | staging buffer 复用仅 -0.074 s/-0.33%，却在 **BoxUtil 共享 OpenGL context** 活跃、Intel 驱动下间歇性整帧花屏；pool、cleaner 桥、同步补偿与 Patch 全移除，恢复逐图 buffer/原版 cleaner。O38 证实撤回无可测损失。 |
| O14 | 保留 | OGG 固定块 PCM 累加器；分配 615.8→242.3 MiB，墙钟中性；不改 JOrbis/PCM/OpenAL 语义。 |
| O15 | 保留 | OGG 块级搬运；声音跨度 -5.04%、worker CPU -14.3%，总墙钟不归因；消除逐字节读取。 |
| O16 | 保留 | 有界并行图片预读；exact A/B -5.87%，资源项 -45.54%；仅真实队列、声音优先、同路径串行。 |
| O17 | 否决 | metadata 缓存两版 +1.16%/+0.85%，且有 stale 风险、不能缩短高层锁；全部撤回。 |
| O18 | 保留 | 白名单纯 Java PNG 快路；exact A/B -2.60%，资源项 -7.58%，纯解码 15.84×；仅 8-bit RGB/RGBA，其余回 ImageIO，无 DLL。 |
| O19 | 否决 | 单独惰性 Janino finder 60.087→118.817 s；重复 metadata/open 扫描，必须与可靠 cache 同时才可重评。 |
| O20 | 保留 | 内容寻址纹理转换缓存；暖缓存 -13.14%，资源项 -53.50%，约 284 MiB；内容 hash、版本、metadata/CRC 与损坏回退。 |
| O21 | 显式调优 | 4 worker 总体仅 -0.13%、ResourceLoader -1.45%；默认原版 2，1–8 仅系统属性显式指定。 |
| O22 | 保留 | 内容寻址 PCM 缓存；暖 A/B -32.93%，async wait 16.213→0.0067 s，约 235.5 MiB；失败回实时解码，默认 32 KiB 阈值。 |
| O23 | 保留 | GUI 无真实 console 时移除无效 ConsoleAppender；-9.94%，SpecStore -21.27%；完整文件日志仍写入。 |
| O24 | 否决 | 暂停四个 INFO logger 虽少约 34,204 行，墙钟仅 -0.16% 且不稳定；保留诊断。 |
| O25 | 保留 | glyph 数组批量扩容；-3.00%，字体预热 1.967→1.222 s；保持原容量/数组语义。 |
| O26 | 保留 | BMFont 低分配行解析；资源项 -5.03%，总体 -1.14%（低置信度）；保持 split/regex 边界和异常。 |
| O27 | 保留 | 图片预读精确路径去重；-1.38%，资源项 -7.31%，8,204 请求去掉 3,761；不规范化别名，失败/消费后可重入。 |
| O28 | 保留 | BMFont 顺序 token 游标；墙钟不归因，字体预热 -7.71%，微基准 CPU -32.71%、分配 73.036→0.006 MiB。 |
| O29 | 否决 | PCM cache 扩至小音频令资源项回退；保留 O22 默认阈值。 |
| O30 | 对照 | 非新增优化；旧状态 all/none exact 为 31.642→14.241 s/-54.99%，现由 O37 替代为当前汇总。 |
| O31 | 保留 | Janino 2.7.8 compilation-unit 去重；-10.99%，Script worker -13.29%，Janino 分配 -95.75%。 |
| O32 | 组合前置 | 逻辑 source index：212 请求全唯一、0 hit，不单独归因；作为 O33 完整 source graph 保留。 |
| O33 | 保留 | 完整输入校验的 Janino bytecode cache；暖 A/B -7.46%，Script worker -14.33%；内容/negative graph/整代原子发布，变化或损坏回实时编译。 |
| O34 | 保留，不计当前收益 | CSV 合并线性化；墙钟 -0.49%、方向不一、helper 无 sample；作为大量 mod 的 O(N²) 上限修复，严格保持重复 key/异常顺序。 |
| O35 | 保留 | 有界并行规格 JSON 准备；目标跨度 -53.69%、SpecStore -13.63%、总体观测 -4.69%；只并行读取/合并，按 ordinal 主线程注册和回放日志，默认 3 worker。 |
| O36 | 否决并撤回 | 资源目录索引两版各省 9,811 leaf probe，却 +2.04%/+3.14%；即使加载期静态、无 watcher 仍不值 map/路径成本。以后应消除上层请求×根循环。 |

## 构建、分组与调试

日常发布构建使用 `python -X utf8 build.py jar --optimizations all`，不注入 profiling。
单项 A/B 通过 `--optimizations`、`--disable-patch-group` 和 `--profiling on` 组合；
依赖展开后的最终功能组必须以 `target/preprocess-work/preprocess-report.json` 为准。
基础命令见 [README.md](../README.md#使用方法)；测量约定与历史数据见
[startup_profile_runs.md](startup_profile_runs.md)。

### 功能组与依赖

| 类别 | 功能组 | 对应项 |
| --- | --- | --- |
| 文本/资源 | `fast-text`、`resource-locks`、`resource-stream-safety`、`resource-partition` | O01–O03、O10 及流所有权加固 |
| CSV/Rules/spec | `csv-error-formatting`、`csv-merge-linear`、`rules-id-index`、`parallel-spec-parse` | O04、O05、O34、O35 |
| 图像/纹理 | `fast-png`、`texture-pipeline`、`texture-cache` | O06、O08、O18、O20 |
| 声音 | `pcm-buffer`、`pcm-bulk-read`、`pcm-cache`、`sound-decode-workers` | O14、O15、O21、O22 |
| 预读 | `preload-coordination`、`preload-path-dedup`、`parallel-image-preload` | O16、O27；协调组不包含已撤回的 O11 |
| 字体 | `font-glyph-copy`、`font-line-parser`、`font-token-cursor` | O25、O26、O28 |
| Janino | `janino-cu-dedup`、`janino-source-index`、`janino-bytecode-cache` | O31–O33 |
| 日志 | `gui-console-log` | O23 |

依赖链：`pcm-cache → pcm-bulk-read → pcm-buffer`；
`texture-cache → fast-png + texture-pipeline`；
`font-token-cursor → font-line-parser`；
`parallel-spec-parse → resource-locks`；
`preload-path-dedup/parallel-image-preload → preload-coordination`；
`janino-bytecode-cache → janino-source-index → janino-cu-dedup`。
`localization`、`ime`、`dynfont` 和 `profiling` 是可开关的非优化组。

### 运行时调试属性

| 方向 | JVM 属性 | 默认/用途 |
| --- | --- | --- |
| spec 并行 | `starsector.optimization.specParseWorkers`、`specParseWindow` | 3 worker、窗口为 2×worker；worker=0 回原顺序 |
| 图片预读 | `starsector.optimization.imagePreloadWorkers` | CPU 数一半，范围 1–3；1 回原 worker 路径 |
| 声音 | `starsector.optimization.soundDecodeWorkers` | 默认 2，范围 1–8 |
| 日志 | `starsector.optimization.keepConsoleLogging=true` | 即使无真实控制台也保留 ConsoleAppender |
| PNG | `starsector.optimization.pngMaximumEncodedBytes` | 快路最大编码输入，默认 64 MiB |
| 纹理缓存 | `disableTextureCache`、`textureCacheDirectory`、`textureCacheMinimumBytes`、`textureCacheMaximumBytes` | 默认阈值 64 KiB、上限 2 GiB |
| PCM 缓存 | `disablePcmCache`、`pcmCacheDirectory`、`pcmCacheMinimumBytes`、`pcmCacheMaximumBytes`、`pcmCacheMaximumEncodedBytes` | 默认阈值 32 KiB、上限 1 GiB、编码输入 64 MiB |
| Janino 缓存 | `disableJaninoBytecodeCache`、`janinoBytecodeCacheDirectory`、`janinoBytecodeCacheMaximumBytes`、`janinoBytecodeCacheMaximumPacks` | 默认 32 MiB/8 pack |
| 公共清理 | `cacheRetentionDays`、`cacheCleanupDelaySeconds`、`cacheCleanupMaximumScannedPaths`、`disablePersistentCacheCleanup` | 默认 30 天、首帧后 60 s、最多扫描 250,000 路径 |

表中省略了共同前缀 `starsector.optimization.`。纹理、PCM 和 Janino 默认目录位于
`starsector-core/cache/startup-optimization/`。缓存按内容命中，带格式/完整性验证和原子发布；
失效或损坏时回原加载路径。清理仅注册实际打包并运行的 namespace，不跟随
junction/symlink/reparse point，不会因只启用某一缓存组而扫描或删除其他组。普通缓存命中只
登记本进程活跃项，不在游玩期间反复触发整树扫描；成功发布新条目才请求容量维护，退出时再
批量刷新晚期命中的近似 LRU 时间。失败或已消失的预保护路径会按发布代次撤销，不会形成永久
dirty 状态。扫描预算耗尽意味着视图不完整，此时只保留已确认的过期/临时/畸形清理，不做
基于局部枚举顺序的容量淘汰。

## SSOptimizer 对照与剩余方向

参考快照为 SSOptimizer 0.1.10、提交 `a8fb828f16be508d3711d811fcafa398646a512d`。它只作为源码
参考，不作为依赖；README 的约 1/3 启动时间不等同于同机 exact A/B。

| 方向 | 与 SSO 的关系 | 当前结论 |
| --- | --- | --- |
| 文本、图片并行 | O01、O16/O27 覆盖同类目标 | 保持孤立 CR/异常、实际启用资源、同路径串行和原版轮询语义；不复制 SSO helper。 |
| 资源 metadata | O17 直接验证 SSO 风格方案 | 两版均回退且有 stale 风险，已撤回；O02/O03 通过缩锁解决真实瓶颈。 |
| PNG、纹理缓存 | O18/O20 对应 SSO libpng/Zstd 缓存 | 改为白名单纯 Java PNG、内容 hash 与完整校验，避免 JNI、mtime/size 误命中和 classpath 污染。 |
| Janino | O31/O33 对应 SSO Janino cache | SSO 无法解析游戏自定义 finder，按类 mtime cache 输入不完整；采用官方 CU 修复和完整 source graph 整代缓存。 |
| 声音 | O14/O15/O21/O22 覆盖同类负载 | 不扫描全部 mod 或常驻编码 byte cache；在实际解码入口缓存最终 PCM，默认仍为 2 worker。 |
| 文件名回退 | SSO 独有 case-insensitive fallback | 属跨平台兼容功能，不是当前启动热点；未实现。 |
| NPOT、延迟 GPU 上传 | SSO 独有高潜力方案 | 可能减少转换/上传，但会改变纹理尺寸或把卡顿移到首次 bind；图形兼容风险高，未实现。 |
| 字体/渲染 hook | SSO 与动态字体会拦截同一资源入口 | 不并装两套 override；SSO engine render 默认关闭且不属于本次启动优化。 |

SSO 未覆盖而本项目收益明确的重点包括 O03 资源锁、O05 Rules、O22 PCM、O23 GUI console，
以及动态字体相关 O25/O26/O28。后续若继续优化，应优先消除上层请求×资源根循环；Codex、标题
战斗场景和惰性 GPU 上传会改变准备时机或运行时行为，只能作为独立高风险实验。

## 兼容性、健壮性与发布边界

- 所有 ASM Patch 以类、descriptor、原调用形状和精确计数定位；版本不符即构建失败。业务逻辑
  放 runtime helper，`ClassWriter(0)` 不重算 frame；真实 class patch/linking 测试覆盖结构。
- `profiling` 是默认关闭的独立非优化组；发布 Jar 不含 startup profiler runtime/公开 Zstd 或 TWL
  命名空间。PNG/Zstd 从私有 CodeSource 加载，避免 mod classpath 污染。
- 资源流安全按 identity 管理所有权，成功移交、失败逆序关闭，保持 primary/suppressed/fatal 异常；
  图像/声音、CSV/JSON 多来源回退均纳入。
- 纹理、PCM、Janino 缓存均内容寻址并有版本/完整性验证、原子发布、失效/损坏 fail-soft 回退。
  自动回收只注册已打包 namespace，30 天保留期；纹理 2 GiB、PCM 1 GiB、Janino 32 MiB/8 pack。
- 缓存读取的临时保护、成功发布和失败撤销按代次区分；命中不会在战役/战斗中重复安排全目录
  维护，损坏/写入失败也不会留下不存在路径迫使后续每次访问重扫。
- 清理与动态字体缓存不跟随 junction/symlink/reparse point；仍存在 Windows 检查到删除之间极窄的
  恶意 TOCTOU，不影响正常本地缓存模型。动态字体 manifest/lease 已覆盖 scale、输出和并发。
- 并行规格解析不允许 worker 改变资源选择器、日志顺序、最早异常或 registry 顺序；`workers=0`
  是同 Jar 原顺序对照。声音默认 2 worker；高并发仍是用户自担风险的调优选项。
- 当前 Java `clean test`：377 项通过、0 failure/error、3 项平台权限跳过；`none`、流安全、PNG、
  三缓存和 `all` 的 profiling-off Jar 均可构建。11-mod 发布 smoke 无 verifier/缺类/链接错误。

## 已解决风险与后续验证

1. 49-mod 花屏已定位为 O13 staging buffer 复用与 BoxUtil 共享 OpenGL context 的间歇性冲突。
   O13 已完整撤回并标记不实现；撤回后以正常 BoxUtil 配置启动 49 mod，用户视觉复测通过。
2. 仍需补做首次战斗和较长战役回归，并补充可靠首帧终点的完整 I/O JFR；现有不完整 I/O
   记录只用于调用路径和局部聚合。
3. 后续覆盖冷热缓存多轮、声音句柄/不同采样率与声道、资源覆盖/CSV-JSON 黄金结果、PNG/纹理
   像素和 lazy texture bind。未有数据前，不默认启用 NPOT 或延迟 GPU 上传。

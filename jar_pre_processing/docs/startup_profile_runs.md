# Starsector 启动 Profiling 基准与运行对比

更新时间：2026-09-27。优化取舍、语义边界和兼容结论见
[startup_optimization.md](startup_optimization.md)。本文只保留可横向比较的基准、
关键 A/B 和当前产物定位；逐次开发过程、重复测试流水和逐轮 JFR 链接已省略。

## 2026-09-27：资源线程上下文隔离

本次修复及兼容边界见 [资源读取线程上下文隔离](resource_context_isolation.md)。
这里使用当前测试目录的 **30 个 MOD**，与下面历史 11-mod 基准不可直接比较。
沿用 `D:\Game\098-RC8-CHS\vmparams` 的 JVM 参数及 8 GiB 堆，`javaw.exe`、
`launchDirect=true`、1280×720、声音开启；统一使用 UTF-8、同一首帧测量和退出观察器。
两版逐 entry 比较，只有 `com/fs/util/C.class` 和 `StarfarerSettings$1.class` 不同。

对照关闭 `resource-context`，修复版开启；其他优化均开启，两者均注入 profiling。
预热后采用 A/B 与 B/A 交错。首组与 Maven 编译重叠，保留为稳定性样本但排除性能统计；
最后追加一组，两侧再次预热后测量，最终有效 **6 对**。单侧稳定性轮次不用于性能结论。

| 有效对 | 对照 JVM→标题首帧 / s | 修复 JVM→标题首帧 / s |
| --- | ---: | ---: |
| 2 | 17.843 | 17.442 |
| 3 | 17.478 | 17.687 |
| 4 | 17.470 | 17.804 |
| 5 | 18.387 | 18.022 |
| 6 | 18.547 | 18.155 |
| 7 | 18.287 | 18.182 |
| 中位数 | **18.065** | **17.913** |
| CV | 2.39% | 1.48% |

中位数差 **−0.152 s（−0.84%）**，逐对差值中位数 −0.235 s；没有观察到性能回退，
也不将小于样本波动的差异认定为确定的加速收益。
两侧暖缓存诊断一致：纹理命中 4,652 项，PCM 命中 1,567 项；Janino 环境指纹失败后
两侧均回退实时编译。因此本结论不代表 Janino 字节码缓存成功命中时的其他环境。

修复版完成 **20 次暖缓存 + 10 次独立空优化缓存** 启动，全部到达真正标题首帧，
再由测试观察器延迟 3 秒执行 JVM 正常退出。空缓存每轮使用新的纹理、PCM、Janino
目录，确认发生缓存重建；没有清空操作系统文件缓存或动态字体缓存，不称为冷机启动。
各轮日志均为相同的既有 **60 ERROR、0 fatal**，逐条归一化比较未发现新增错误。
有限启动次数本身不能证明消灭所有偶发问题；确定性回归与压力测试是并发修复的主要证据。

自动化验证：Java **494 项，491 通过、3 跳过、零失败**；跳过项均因 Windows 无创建
符号链接权限，与这次修复无关。Python 构建测试 **10/10** 通过。实际游戏资源类的
32,000 次交错读取、异常清理、线程/实例隔离、短锁 I/O 和普通读取分配测试均通过。

另外，`--optimizations resource-context --profiling on` 的独立组合完成一次实际启动，
验证修复与原版资源锁兼容；最终 `--profiling off` 发布 JAR 也完成标题首帧与正常退出
检查。发布版本的首帧由临时 Java agent 观察 `Display.update(true)` 后的回调，
仅在测试进程内挂接，既不改写磁盘 JAR，也不加入发布包。含预热、对照与这两次检查在内，
本轮共 **44 次实际游戏启动成功**，错误内容均与对照一致。

整包 `-Xverify:all` 在对照与修复版都被原游戏 `new.super` 混淆名称拒绝；不计作
游戏启动回归，新增补丁使用正常 JVM 校验的 class fixture 验证。测试准备阶段有一次
漏加 `launchDirect` 停留在启动器，已终止且排除；以上统计仅包括实际加载游戏的样本。

原始证据目录：`%TEMP%/starsector-resource-validation-20260927/`，包含每轮命令、
启用 MOD 清单、JAR 哈希、原始日志、首帧时间线、构建报告、JUnit 报告、A/B 排除说明、
`performance-summary.json` 与复跑脚本。发布 JAR 由完整流水线生成，再导入译文；
关闭 profiling，与修复前内容相比仅两个目标 class 和新增 `ResourceReadContext.class`
有变化，API JAR 内容没有变化。

### 发布产物读档、操作与另存回归

在 `D:\Game\098-RC8-CHS` 使用上述无 profiling 的发布产物和 30 个 MOD，完成桌面实际操作：
读取最新 `jn_xyp` 存档，打开舰队、货舱及星系地图，短暂恢复战役运行并观察航行与日期推进；
随后暂停，选择空白槽另存为 `startup-fix-validation-20260927`，返回主菜单重新读取，
再次打开舰队界面确认响应正常。原存档目录时间及各文件大小未变，未覆盖原存档。

新存档为 `saves/save_jnxyp_978479604204017662`，`campaign.xml` 与 `descriptor.xml`
均可完整解析；保存日志到达“完成保存”。三个部署 JAR 的 SHA-256 与 `localization/`
一致。本轮未挂接首帧观察 agent，不计入上述 44 次自动启动样本或性能统计。

本次启动记录 **57 ERROR、0 FATAL**，ERROR 内容均已见于对照：40 条武器表缺项、
14 条船体表缺项、1 条 `tooltip_followersDiplomacy` 字符串缺失、1 条 FOB 势力关系
缺少 `factionid`、1 条以 ERROR 级别输出的“确定获取到了前置mod”提示。
读档、操作和保存阶段没有 ERROR/FATAL，但并非没有异常：两次读档共出现 8 次
MagicLib 对 `doom_GC.variant`、`conquest_GC.variant` 的 WARN，附带
`Weapon spec [null] not found!` 异常；另有 MOD 配置缺项警告。启动还存在 MIRV
数据缺少 `damage`、在线版本检查失败等 WARN。它们未阻止本次流程完成，不能据此
声称所有 MOD 功能正常，也未将读档 WARN 与只到标题的对照日志作等价比较。
本轮覆盖基本战役与存取档，不包括战斗或长时间游玩。

证据目录：`%TEMP%/starsector-save-validation-20260927/`，包含原始日志、启动命令、
操作前存档清单及 `gameplay-file-log-verification.json`。

## 历史固定测量约定

- 游戏 `0.98a-RC8`，分支 `startup-optimization`；`javaw.exe` 直启、1280×720、
  `startSound=true`、`-Xms8g -Xmx8g -XX:+AlwaysPreTouch`。
- 固定 11 mod：LazyLib、MagicLib、LunaLib、shaderLib、BoxUtil、Console、Nexerelin、
  TraverserDesignBureau、Polaris Prime、FSF Military Corporation、jc_sf。结果目录保存启用清单
  和原始压缩包 manifest/SHA-256。
- 非首次启动：先暖机一次，再连续三次 repeat；报告中位数、min–max 和样本 CV。缓存/系统状态、
  Jar、mod 版本或计时终点变化时，绝对秒数不可跨组相减。
- JFR 为 `settings=profile,stackdepth=256`；事件在首帧标记后才 dump，因此热点统计限制在
  `title.first_frame.displayed` 前。日志用 GBK/CP936 解码并在结果目录保存 UTF-8 当轮副本。
- 可靠终点：`TitleScreenState` 首次 `Display.update(true)` 返回；`Reading save data` 不是主菜单
  就绪点，不能作为正式 time-to-menu。

## 当前标准：O38（已撤回 O13，Polaris v0.4.3）

O38 是当前工作树的 11-mod、8 GiB、profiling-on 样本；Polaris 为 v0.4.3。暖机为 **9.807 s**。
正式三轮均到标题首帧，JVM→首帧为 **9.004 / 8.920 / 9.036 s**，中位数 **9.004 s**、
min–max 8.920–9.036、CV **0.66%**。

| 指标 | r1 | r2 | r3 | 中位数 |
| --- | ---: | ---: | ---: | ---: |
| JVM→标题首帧 | 9.004 | 8.920 | 9.036 | **9.004** |
| main→标题首帧 | 8.390 | 8.300 | 8.408 | **8.390** |
| ResourceLoader | 5.788 | 5.774 | 5.821 | **5.788** |
| SpecStore | 1.730 | 1.646 | 1.749 | **1.730** |
| 资源项 | 2.382 | 2.288 | 2.283 | **2.288** |
| ScriptStore worker | 4.259 | 4.105 | 4.172 | **4.172** |
| mod callbacks | 1.469 | 1.608 | 1.588 | **1.588** |

`ScriptStore worker` 与主线程阶段重叠，不能同其他阶段求和。每轮 **11 ERROR、0 fatal**，日志
均为 **44,092** 行（暖机 44,093），无新增优化 runtime/验证/缺类错误。

产物目录：

- `tmp/startup-profile-o13-removed-polaris043-warm-20260801-230653`
- `tmp/startup-profile-o13-removed-polaris043-r1-20260801-230715`
- `tmp/startup-profile-o13-removed-polaris043-r2-20260801-230732`
- `tmp/startup-profile-o13-removed-polaris043-r3-20260801-230752`

相对 O37 全启用中位数 9.042 s，O38 为 -0.038 s，远低于可归因量级；这支持“撤回 O13 staging
buffer 没有可测性能损失”。但 O38 的 Polaris v0.4.3 条件与 O37 不同，因此不是严格 exact A/B，
不得把 38 ms 作为 O13 收益或损失。

### O11 撤回回归（49 mod）

同机、同目录、8 GiB、热缓存直接 A/B：撤回前 22.231/22.543 s，中位数 **22.387 s**；
撤回后 22.206/22.097/22.325 s，中位数 **22.206 s**，CV **0.51%**。差异 -0.81%，
各主要阶段中位数波动仅 -2.32%～+2.20%，无可测回归。Jar 替换后首次缓存重建轮不纳入统计。
产物目录：`tmp/startup-profile-o11-ab-old-r*`、`tmp/startup-profile-o11-ab-new-r*`。

## 当前可比累计汇总

O37 是兼容性加固后、同工作树 `--optimizations none/all` 的交错 A/B/B/A/A/B；两边均 profiling-on、
11 mod、8 GiB、相同汉化和非首次缓存。它仍是“全部启用相对全部关闭”的严格累计证据。

| 样本 | 全部关闭 JVM→首帧 | 全部启用 | 差值 | 关闭 ResourceLoader | 启用 | 差值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| #1 | 21.923 | 8.904 | -13.019 | 18.894 | 5.947 | -12.947 |
| #2（反向） | 22.136 | 9.068 | -13.068 | 19.163 | 6.011 | -13.152 |
| #3 | 21.943 | 9.042 | -12.901 | 18.966 | 5.965 | -13.001 |
| 中位数 | **21.943** | **9.042** | **-12.901/-58.79%** | **18.966** | **5.965** | **-13.001/-68.55%** |

| 阶段 | 全部关闭中位数 | 全部启用中位数 | 变化 | all CV |
| --- | ---: | ---: | ---: | ---: |
| main→首帧 | 21.270 | 8.401 | -12.869/-60.50% | 0.90% |
| SpecStore | 5.334 | 1.851 | -3.483/-65.30% | 2.99% |
| 资源项 | 10.860 | 2.326 | -8.534/-78.58% | 1.73% |
| ScriptStore worker | 16.374 | 4.316 | -12.058/-73.64% | 1.42% |
| mod callbacks | 2.519 | 1.604 | -0.915/-36.32% | 3.36% |

结论：当前条件下累计启动约为关闭组的 41.2%（约 2.43×）。所有阶段存在并行/嵌套，不可相加；
此结果不外推到首次缓存填充、49 mod 或其它设备。

## 关键单项证据（历史 exact A/B）

下表只列保留决策所需的同场数值；“中性/不归因”表示不把墙钟差计入累计收益。详细语义与
否决原因见启动优化总览文档。

| 优化 | 保留判定 | 关键结果 |
| --- | --- | --- |
| O01 reader | 保留 | 相对 A0 30.346→28.084 s，-7.46%；分配 -4.758 GiB。 |
| O03 资源锁 | 保留 | 相对 O02 28.169→25.124 s，-10.81%；长 monitor wait 消失。 |
| O05 Rules | 保留 | 相对 O04 25.123→23.813 s，-5.21%。 |
| O06 像素转换 | 保留 | 相对 O05 23.813→22.367 s，-6.07%。 |
| O08 raster | 保留 | 墙钟中性；约 -0.922 GiB 分配。 |
| O10 稳定分区 | 保留 | 目标 50.667→13.877 ms，-72.61%。 |
| O11 通知 | **撤回** | 恢复原版 10-ms 轮询；49-mod 撤回 A/B 中位数 22.387→22.206 s，无可测回归。 |
| O13 staging | **否决/撤回** | 当时仅 -0.074 s/-0.33%；与 BoxUtil 共享 GL context 触发间歇性整帧花屏。O38 显示撤回无可测损失。 |
| O14/O15 PCM 搬运 | 保留 | 分配 615.8→242.3 MiB；声音跨度 -5.04%，总墙钟中性。 |
| O16 图片预读 | 保留 | 23.534→22.152 s，-5.87%；资源项 12.114→6.598 s，-45.54%。 |
| O18 PNG | 保留 | 锁屏同场 56.933→55.452 s，-2.60%；资源项 -7.58%。 |
| O20 纹理缓存 | 保留 | 暖缓存 54.153→47.035 s，-13.14%；资源项 -53.50%；首次填充不计。 |
| O21 声音 worker | 仅显式调优 | 总体 -0.13%、ResourceLoader -1.45%；默认仍为 2。 |
| O22 PCM 缓存 | 保留 | 48.249→32.361 s，-32.93%；async wait 16.213→0.0067 s。 |
| O23 ConsoleAppender | 保留 | 31.871→28.703 s，-9.94%；SpecStore -21.27%。 |
| O25 glyph 扩容 | 保留 | 14.421→13.988 s，-3.00%；字体预热 1.967→1.222 s。 |
| O26 BMFont parser | 保留 | 14.597→14.430 s，-1.14%；资源项 -5.03%。 |
| O27 图片去重 | 保留 | 14.924→14.718 s，-1.38%；去 3,761/8,204 请求。 |
| O28 token 游标 | 保留 | 墙钟不归因；字体预热 -7.71%，CPU -32.71%。 |
| O31 Janino CU | 保留 | 15.635→13.916 s，-10.99%；worker -13.29%。 |
| O33 Janino cache | 保留 | 暖缓存 13.655→12.637 s，-7.46%；worker -14.33%。 |
| O34 CSV merge | 保留不计收益 | 墙钟 -0.49%、方向不一；作为 O(N²) 上限修复。 |
| O35 并行 spec JSON | 保留 | 总体观测 12.362→11.783 s，-4.69%；SpecStore -13.63%，目标跨度 -53.69%。 |

否决但仍值得避免重做：O07 声音 4 worker（+0.138 s）、O09 direct backing array（+1.47%）、
O12 ScriptStore 去重（+0.053 s）、O17 metadata cache（+1.16%/+0.85%）、O19 单独惰性
Janino finder（+97.74%）、O24 INFO 抑制（-0.16% 且不稳）、O29 小声音 PCM cache（资源项回退）、
O36 资源目录索引（watch +2.04%、静态 +3.14%）。O32 source index 是 O33 的前置观察层，
0 hit 不单独计收益；O30 是旧 all/none 对照而非新增优化。

## 基线 JFR 与运行门禁

未优化 R002 的首帧前 JFR：估算分配 15.822 GiB（`byte[]` 12.235 GiB）；主要调用点为
`LoadingUtils.super` 4.211 GiB、`ByteInterleavedRaster.getByteData` 2.333 GiB、
`DataBufferByte.<init>` 1.580 GiB、`Arrays.copyOf` 1.579 GiB。资源锁等待 4.344 s；JIT 167 事件、
聚合线程时间 44.182 s（与墙钟重叠）；3 次 Shenandoah 暂停合计 3.525 ms。

49-mod 门禁曾在存档 stage 39 与 Console 造舰后出现间歇性整帧花屏；功能组隔离把根因定位到
O13 staging buffer 复用与 BoxUtil 共享 OpenGL context 的冲突。O13 已撤回，随后以正常 BoxUtil
配置重新启动 49 mod，用户视觉复测通过。首次战斗和较长战役仍需作为发布前扩展回归。

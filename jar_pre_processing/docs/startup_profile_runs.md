# Starsector 启动 Profiling 基准与运行对比

更新时间：2026-08-02。优化取舍、语义边界和兼容结论见
[startup_optimization.md](startup_optimization.md)。本文只保留可横向比较的基准、
关键 A/B 和当前产物定位；逐次开发过程、重复测试流水和逐轮 JFR 链接已省略。

## 固定测量约定

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

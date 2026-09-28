# 资源读取线程上下文隔离

适用版本：0.98a-RC8；功能组：`resource-context`。

## 问题与修复边界

2026-09-27 的启动失败日志指向 SCY 的 `mission_text.txt`；历史失败还涉及 Imperium 的
`descriptor.json`。文件实际存在，另一个线程正在运行 LunaLib 的指定 MOD 读取。
原版 `com.fs.util.C` 用实例字段保存一次性 MOD selector，指定和消费虽然各自同步，
但两个调用之间并不原子。普通读取线程可以消费其他线程的 selector，造成假缺失；
如果两个 MOD 恰好有同名文件，还可能静默读取错误内容。原版锁和短锁均可确定性复现。

`ResourceReadContext` 将 selector 改为每个 loader 实例拥有的普通 `ThreadLocal`。
原有 setter、读取入口、覆盖优先级和同线程“最后设置、消费一次”的行为保留。
线程退出或显式清理时不向其他线程传播状态；不使用 `InheritableThreadLocal`。
实际消费后删除非空线程条目；普通无选择读取保留空条目，避免每次 get/remove 分配对象。

`SettingsAPI.loadJSON/loadCSV(path, includeMods)` 的两处跳过 MOD 写入改为线程状态，
在资源入口消费一次；方法正常返回及任何异常退出都清理残留。标志仍按线程跨 loader
共享，匹配原静态标志的作用域。指定 MOD 的 JSON/CSV/text API 已有 finally 清理，沿用。
纯预读 worker 的原有旁路继续有效，不消费普通线程上下文。

此修改不扩大 I/O 锁、不调整 worker 数、不改缓存格式，也不通过重试掩盖错误。
`resource-locks` 关闭时仍可单独启用修复。ASM 验证 selector 的读写数、构造器数、
skip 标志消费点和 SettingsAPI 方法结构；结构不符或重复应用直接拒绝构建。

## 兼容边界与诊断

原 `C.super` 公共静态字段保留，读取时仍消费并重置旧字段，以保持第三方二进制链接。
直接写此旧字段仍具有原来的全局语义，不能保证跨线程隔离。审计原版与当前 MOD 的
45 个 JAR、17,753 个 class，相关字段/方法引用均来自游戏自身；未发现 MOD 直接写入。
反射、运行时生成代码以及未来 MOD 不能由此静态审计排除。

诊断参数 `-Dstarsector.resourceContext.diagnostics=true` 默认关闭，只在真实的资源缺失
异常路径记录当前线程、路径、消费的 selector 和 includeMods；成功读取不产生日志。

```powershell
# 随全部优化启用（正常发布不带 profiling）
python -X utf8 build.py jar
# 保留其他优化，关闭这次修复，构建 A/B 对照
python -X utf8 build.py jar --disable-patch-group resource-context --profiling on
# 独立测试原版锁 + 线程上下文修复
python -X utf8 build.py jar --optimizations resource-context
```

每次构建后都必须从仓库根运行 `python -X utf8 para_tranz/para_tranz_script.py 2` 恢复译文。
不要合并 class 来生成发布 JAR。

## 自动化验证

- `ResourceContextIsolationPatchTest` 使用实际游戏 `C.class`，覆盖修复开/关 × 短锁开/关。
  关闭修复时固定交错稳定重现“假缺失后立即重试成功”；启用时正确选择来源。
- 覆盖同名文件内容、多实例、创建子线程、线程池复用、异常消费、显式清理、最后设置优先、
  speculative 旁路和旧公共字段兼容。
- 8 线程 × 1,000 轮 × 每轮 2 次读 × 两种锁模式，共 32,000 次文件读取。
- 在真正 `FileInputStream` 打开点检查 `Thread.holdsLock`，确认短锁模式不持有资源锁。
- `SettingsResourceContextPatchTest` 执行真实 API 方法控制流，对依赖边界注入异常，
  验证 JSON/CSV 正常返回、读取前异常和解析异常均不残留 skip 状态，且保留原异常。
- `ResourceReadContextTest` 测量普通无选择读取的线程分配，防止反复创建 ThreadLocal 条目。

游戏整包的 `-Xverify:all` 会因原有混淆方法名 `new.super` 抛 `ClassFormatError`；
修复版与对照版一致。实际启动沿用游戏原配置，补丁 fixture 在正常开启字节码验证的
JUnit JVM 中加载执行，不以游戏的 `-noverify` 代替补丁验证。

实际启动与性能结果见 [启动测试记录](startup_profile_runs.md)。

# 中文输入法支持（Windows）

为《远行星号》汉化版提供游戏内 Windows 中文输入法（IMM32）支持。玩家端**零配置**：
安装汉化包后即生效，无需修改 vmparams、无需额外 mod、无需 Java 版本变更。

## 背景

游戏基于 LWJGL2，其窗口过程不处理 `WM_IME_*` 消息（且吞掉了 `WM_SETFOCUS`/`WM_KILLFOCUS`，
使 IME 上下文无法激活），系统输入法无法在游戏输入框（船名、存档名、角色名等）中正常
工作。本模块通过子类化窗口过程接管输入法消息，把上屏文本注入游戏输入框。

## 架构

三部分协作，思路与主流方案（CocoaInput / SSOptimizer）一致——原生 WndProc 接管 + IMM32：

```
玩家按键
  → 系统输入法组合（拼音候选）
  → WM_IME_COMPOSITION（被 ssime.dll 子类化的窗口过程截获）
  → 读取上屏文本入队
  → 游戏全局输入帧维护 HWND 与焦点生命周期
  → 文本框 processInputImpl 轮询当前输入框
  → ImeHooks  ← ASM 注入的入口调用
  → ImeController 轮询队列，逐字符 appendCharIfPossible 注入输入框
```

| 组件 | 位置 | 说明 |
|---|---|---|
| 原生库 `ssime.dll` | `jar_pre_processing/native/ime/ssime.cpp` | `SetWindowLongPtrW` 子类化 LWJGL2 窗口过程，处理 `WM_IME_STARTCOMPOSITION/COMPOSITION/ENDCOMPOSITION`，维护上屏队列与候选窗定位；焦点管理用 `ImmAssociateContext` 保存/恢复上下文，避免无输入框聚焦时按键被输入法截获；并修复 Win+空格 引发的修饰键卡死（见下） |
| 运行时类 `org.fossic.starsector.ime.*` | `jar_pre_processing/src/main/java/org/fossic/starsector/ime/` | `ImeController`（生命周期/焦点/注入/定位）、`ImeNatives`（JNI 绑定）、`ImeHooks`（ASM 注入入口，全程异常隔离）、`ImeLog`（日志） |
| ASM 注入 | `patches/GlobalImeFocusPatch.java`、`patches/TextFieldImeHookPatch.java` | 在全局输入帧与焦点栈修改出口维护窗口/焦点生命周期；在文本框的 `processInputImpl`、`grabFocus`、`releaseFocus` 三处处理登记、启用与失焦清理 |

### Gameplay 与文本输入状态

模块在游戏首次出现文本框之前便接管窗口，并根据全局焦点进入以下两种稳定状态：

- **Gameplay**：没有受支持的文本框聚焦。保存游戏窗口的 HIMC 和用户当前中/英文状态，取消组合、
  关闭候选窗，将该 HIMC 切到关闭状态并从 HWND 解绑。每项操作都复读实际状态，只有满足“未关联且
  已关闭”的后置条件才视为成功。解绑本身不足以阻止部分现代 TSF 输入法产生 `VK_PROCESSKEY`，
  因此必须同时关闭游戏窗口所属的输入上下文，才能保证 WASD 和快捷键不被截获。
- **文本输入**：只有经过 `processInputImpl` 登记、实际持有游戏全局焦点且能消费上屏字符的
  `TextFieldAPI` 才能启用 IME。模块恢复保存的 HIMC 和用户状态，接收组合结果并逐字符调用
  `appendCharIfPossible`，继续遵守游戏自身的长度、宽度和字形限制。

文本框 A 切换到 B 或关闭面板时，Java 层先执行 `beginCancel`，在下一全局输入帧执行
`finishCancel` 并再次确认 Gameplay 后置条件，再允许 B 启用。跨帧屏障用于隔离同步重入或迟到的
组合消息，避免旧文本串入新输入框，也避免关闭输入框后留下游离候选窗。

### 窗口、线程与上下文生命周期

启动器窗口和实际游戏窗口可能由不同的 Java/Win32 线程持有。线程所有权只允许在全局输入帧中
移交，并且必须同时满足：解析到替代 HWND，且旧 native 上下文已经 `DETACHED`、`WINDOW_GONE`
或 `RETIRED`。普通文本框 Hook 不能夺取状态机，后台 mod 线程也不能调用窗口线程专属的 IMM API。

新旧 HWND 短暂重叠时，旧窗口先退役并恢复保存的用户输入状态，再接管新窗口；若线程也发生切换，
新线程只遗忘已经安全解绑的旧 Java 句柄，不跨线程操作旧窗口。`WM_NCDESTROY` 处理期间会保留窗口
property，直到原 WndProc 返回，确保同步产生的嵌套消息仍沿正确的 subclass 链转发。

原生窗口上下文与游戏进程同生命周期，不在 JVM shutdown hook 中释放。退出时由 Windows 统一
回收，避免 shutdown 线程、窗口消息和 JNI 调用之间产生 use-after-free；窗口重建产生的少量退役
上下文是为此采用的有意取舍。

### 失败隔离

- 运行时在调用其他 JNI 前校验 native ABI，Jar/DLL 不匹配时安全停用输入法模块
- HWND 尚未建立或处于切换阶段时延后重试；永久初始化失败只记录一次
- 所有 native 状态转换检查窗口线程、窗口存活状态和实际 HIMC 后置条件
- 非零 native 上下文只有在确认安全解绑、窗口消失或退役后才能从 Java 状态机清除
- Hook 异常会熔断正常业务路径，但全局帧仍会有限次推进不接触游戏 UI 的紧急解绑
- 候选窗坐标解析错误按文本框实例隔离；单个 mod 控件异常不会停用其他输入框的定位

候选窗定位基于文本 label（`getTextLabelAPI()`）自身的 position 而非外层文本框，
对左对齐与居中对齐（如舰船命名框）均正确。文本框 position 是游戏 UI 的逻辑坐标，
而候选窗需要客户区物理像素坐标——UI 缩放非 100% 时二者不同，故用
`Global.getSettings()` 的 `getScreenHeight()`（逻辑）与 `getScreenHeightPixels()`
（物理）自算缩放倍数并换算，各缩放档位下定位均正确。

为避免 UI 热路径产生额外开销，HWND 反射审计集中在全局输入帧；普通文本框 Hook 在已接管状态下
使用 O(1) 快速路径。文本框登记使用带 `ReferenceQueue` 的弱身份哈希表，既不调用 mod 控件的
`equals/hashCode`，也不延长已销毁 UI 的生命周期；候选窗物理坐标不变时不会重复调用 IMM32。

### 修饰键卡死修复（Win+空格）

`Win+空格` 等系统热键会向游戏窗口发送修饰键的 `WM_KEYDOWN`，却把配对的
`WM_KEYUP` 吞掉，导致 LWJGL2 的键状态缓冲把该修饰键卡在"按下"。由于游戏跨平台
（Mac 的 Cmd/Meta 与 Ctrl 等价），卡住的 Win 键被当作 Ctrl，使文本框退格误判为
Ctrl+退格（删词）；需手动单击一次 Ctrl 才能复位。这是 LWJGL2 的固有缺陷，但因
输入法切换才被触发。

由于本模块已子类化窗口过程、处在消息链上，可直接修复：检测到 Win 键按下后置位
标志，待 Win 键**物理松开**时（`GetAsyncKeyState`）对所有物理已松开的修饰键补发
`WM_KEYUP` 给 LWJGL 使其复位。触发点覆盖 `WM_INPUTLANGCHANGE` 与紧随的按键，
故切换后第一次退格即正常。仅补发物理已松开的键，不会误放玩家正按住的修饰键，
且 LWJGL 对已松开的键因去重忽略，对未卡死的键无副作用。

## 零配置原理

游戏 classpath 是固定列表（含 `starfarer_obf.jar`），`java.library.path=native\windows`。汉化包
安装时把 `localization/*` 递归复制到 `starsector-core/`。因此：

- 运行时类打包进 `starfarer_obf.jar`（已在 classpath）；
- `ssime.dll` 放入 `localization/native/windows/`，安装后落到 `native\windows`（即 `java.library.path`），`System.loadLibrary("ssime")` 直接加载。

均无需玩家改动任何配置。

## 构建与集成（Python 编排：`build.py`）

构建脚本以 Python 为主：`build.py` 依次调用 g++ 编译原生库、mvnw 运行 Java
管线（运行时类随工具一起由 Maven 编译，编译期依赖 pom 中 system scope 的
`game data/starfarer.api.jar`；`RuntimeClassInjector` 从 classpath 取编译产物
注入 obf jar），最后把 `native/ime/ssime.dll` 复制到 `localization/native/windows/`：

```powershell
cd jar_pre_processing
python build.py                 # 日常流程（用已提交的 dll）
python build.py ime             # 只重编本模块原生库（产物 native/ime/ssime.dll 提交入库）
python build.py ime jar         # 重编后走完整流程
```

依赖：MinGW-w64 g++（在 PATH 中，仅重编 native 时）；JNI 头文件取自 `JAVA_HOME`。
原生库以确定性参数构建（去 PE 时间戳）——同一编译器下源码不变则产物字节一致；
编译器升级仍会产生二进制 diff，故日常构建不重编，重编须连同产物一起提交。

`python build.py ime` 会以 C++17、`-Wall -Wextra -Werror` 编译正式 DLL，并运行隐藏窗口
smoke test。该测试直接加载本次产物，验证 ABI、attach 结果分类、错误线程拒绝、启用/取消、迟到
组合消息、窗口退役与替换、用户输入状态恢复及默认日志行为；所有线程和子进程等待都有超时。

Java 测试覆盖 ASM 注入结构、Patch 组开关、延迟 HWND、启动器到游戏窗口的跨线程交接、焦点切换、
跨帧取消、异常熔断和紧急清理、弱身份登记、候选窗故障隔离，以及运行时关闭时不加载 DLL：

```powershell
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
.\mvnw.cmd clean test
```

## 日志

- **游戏日志**（`starsector.log`）：只记录带 `[SS-IME]` 前缀的错误，不记录焦点切换、
  候选窗坐标或玩家输入内容。不可恢复错误会让钩子在本次会话熔断，避免热路径逐帧抛错。
- **原生日志**（`<游戏 logs 目录>/starsector_ime_native.log`，与 `starsector.log` 同级）：
  只记录窗口过程接管失败等原生错误，每次游戏启动时清空重写。路径由 Java 侧读
  `com.fs.starfarer.settings.paths.logs` 构造后经 `nativeInit` 传入。默认不写逐消息诊断；仅开发时
  显式添加 `-Dfossic.ime.debug=true` 才启用，而且不会记录字符值、组合串或最终输入文本。

需要完全停用输入法运行时可添加 `-Dfossic.ime.enabled=false`。该开关在引用 Controller/JNI 前
短路，因此不会加载 `ssime.dll` 或创建原生日志。

## 已知限制

- **仅 Windows**。Linux/macOS 未实现（原生层为 IMM32）。
- **独占全屏下系统候选窗不可见**。这是所有"系统候选窗"方案的固有限制（LWJGL2/GLFW 至今如此），
  规避方式见下节。窗口化下候选窗会定位到输入框光标处。

### 独占全屏下候选窗不可见：成因与规避

`Display.setFullscreen(true)` 走的是**独占全屏**——应用直接占有显示输出的扫描链，**DWM 被绕过**。
而候选窗是独立的顶层窗口，要叠加到游戏画面之上必须经 DWM 合成；DWM 不参与，它就无处可画。
与本模块的实现无关，属 Windows 显示模型的固有约束。

三种规避方式，任选其一：

| 方式 | 操作 | 适用 |
|---|---|---|
| **无边框全屏**（推荐） | 启动器里**不勾**「全屏启动」，分辨率**选桌面分辨率** | 全平台显卡 |
| 窗口化 | 启动器里不勾全屏，选一个小于桌面的分辨率 | 全平台显卡 |
| DXGI 分层 | NVIDIA 控制面板 → 管理 3D 设置 → 为 `jre\bin\java.exe` 把「Vulkan/OpenGL 现行方法」设为**优先在 DXGI 交换链上分层** | 仅 NVIDIA（驱动 ≥ 526.61） |

**无边框全屏**由游戏自身提供，视觉上与全屏无异。触发条件见 `CombatMain`：

```java
boolean bl2 = StarfarerSettings.class("alwaysUndecoratedAtFullscreen");
…
if (!bl && n2 >= n4 && n3 >= n5 && bl2) {          // 非全屏 + 所选分辨率 ≥ 桌面 + 设置开启
    System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
}
```

`settings.json` 的 `alwaysUndecoratedAtFullscreen` 默认即为 `true`，故玩家只需满足前两个条件。
此路径下窗口交由 DWM 合成，候选窗正常显示。

**DXGI 分层**的原理是让驱动把 OpenGL 的呈现包进 DXGI flip-model 交换链——应用仍以为自己在独占
全屏，实际已由 DWM 合成，等价于无边框窗口（微软对 Fullscreen Optimizations 的描述是同一套机制）。
代价是该选项在部分 OpenGL 程序上有掉帧报告，且只对 NVIDIA 有效，故列为备选。

## 致谢

本模块的技术路线借鉴了 [KasumiNova](https://github.com/KasumiNova)（Hikari_Nova）的开源项目
[SSOptimizer](https://github.com/KasumiNova/SSOptimizer)（MIT 许可）中的输入法实现思路，
包括：子类化窗口过程接管 `WM_IME_*` 消息、通过 `ImmGetCompositionStringW` 读取上屏文本入队
供游戏侧逐帧注入、用 `ImmSetCompositionWindow`/`ImmSetCandidateWindow` 将候选窗定位到光标处，
以及按输入框焦点启停 IME 上下文的焦点管理策略。本模块为独立实现（静态字节码注入 + 随汉化包
分发，非 Java Agent 路线），未复制其代码，但整体设计深受其验证过的方案启发，特此致谢。

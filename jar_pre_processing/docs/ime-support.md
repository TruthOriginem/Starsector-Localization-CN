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
  → 游戏每帧 ui.new.processInputImpl
  → ImeHooks.onProcessInput(this)  ← ASM 注入的调用
  → ImeController 轮询队列，逐字符 appendCharIfPossible 注入输入框
```

| 组件 | 位置 | 说明 |
|---|---|---|
| 原生库 `ssime.dll` | `jar_pre_processing/native/ssime.cpp` | `SetWindowLongPtrW` 子类化 LWJGL2 窗口过程，处理 `WM_IME_STARTCOMPOSITION/COMPOSITION/ENDCOMPOSITION`，维护上屏队列与候选窗定位；焦点管理用 `ImmAssociateContext` 保存/恢复上下文，避免无输入框聚焦时按键被输入法截获；并修复 Win+空格 引发的修饰键卡死（见下） |
| 运行时类 `org.fossic.starsector.ime.*` | `jar_pre_processing/src/main/java/org/fossic/starsector/ime/` | `ImeController`（生命周期/焦点/注入/定位）、`ImeNatives`（JNI 绑定）、`ImeHooks`（ASM 注入入口，全程异常隔离）、`ImeLog`（日志） |
| ASM 注入 | `patches/TextFieldImeHookPatch.java` | 在 `com.fs.starfarer.ui.new`（`TextFieldAPI` 实现）的 `processInputImpl` 开头插入 `ImeHooks.onProcessInput(this)` |

候选窗定位基于文本 label（`getTextLabelAPI()`）自身的 position 而非外层文本框，
对左对齐与居中对齐（如舰船命名框）均正确。文本框 position 是游戏 UI 的逻辑坐标，
而候选窗需要客户区物理像素坐标——UI 缩放非 100% 时二者不同，故用
`Global.getSettings()` 的 `getScreenHeight()`（逻辑）与 `getScreenHeightPixels()`
（物理）自算缩放倍数并换算，各缩放档位下定位均正确。

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

## 构建与集成（全部由 mvnw 管理）

**日常构建**：正常运行预处理即可，运行时类随工具一起由 Maven 编译
（编译期依赖 pom 中 system scope 的 `game data/starfarer.api.jar`），
`ImeRuntimeInjector` 从 classpath 取编译产物注入 obf jar，`ssime.dll`
复制到 `localization/native/windows/`：

```powershell
cd jar_pre_processing
.\mvnw.cmd compile exec:java
```

**重编原生库**（仅修改 `native/ssime.cpp` 后需要，产物 `native/ssime.dll` 提交入库）：

```powershell
.\mvnw.cmd -Pbuild-native compile
```

依赖：MinGW-w64 g++（在 PATH 中）；JNI 头文件取自运行 Maven 的 JDK（`JAVA_HOME`）。

## 日志

- **游戏日志**（`starsector.log`）：常规日志前缀 `[SS-IME]`（初始化、HWND、错误等）；
  交互级调试日志前缀 `[SS-IME][DEBUG]`（焦点切换、上屏注入、候选窗定位），
  可用 `findstr "[SS-IME][DEBUG]" starsector.log` 单独检索。
- **原生日志**（游戏工作目录 `starsector_ime_native.log`）：记录窗口过程接管、`WM_IME_*` 消息。每次游戏启动时清空重写。

## 已知限制

- **仅 Windows**。Linux/macOS 未实现（原生层为 IMM32）。
- **独占全屏下系统候选窗不可见**。这是所有"系统候选窗"方案的固有限制（LWJGL2/GLFW 至今如此）。请使用**窗口化**或**无边框全屏**。窗口化下候选窗会定位到输入框光标处。

## 致谢

本模块的技术路线借鉴了 [KasumiNova](https://github.com/KasumiNova)（Hikari_Nova）的开源项目
[SSOptimizer](https://github.com/KasumiNova/SSOptimizer)（MIT 许可）中的输入法实现思路，
包括：子类化窗口过程接管 `WM_IME_*` 消息、通过 `ImmGetCompositionStringW` 读取上屏文本入队
供游戏侧逐帧注入、用 `ImmSetCompositionWindow`/`ImmSetCandidateWindow` 将候选窗定位到光标处，
以及按输入框焦点启停 IME 上下文的焦点管理策略。本模块为独立实现（静态字节码注入 + 随汉化包
分发，非 Java Agent 路线），未复制其代码，但整体设计深受其验证过的方案启发，特此致谢。

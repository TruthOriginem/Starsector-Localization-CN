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
  → ImeHooks.onProcessInput(this)  ← ASM 注入的入口调用
  → ImeController 轮询队列，逐字符 appendCharIfPossible 注入输入框
```

| 组件 | 位置 | 说明 |
|---|---|---|
| 原生库 `ssime.dll` | `jar_pre_processing/native/ime/ssime.cpp` | `SetWindowLongPtrW` 子类化 LWJGL2 窗口过程，处理 `WM_IME_STARTCOMPOSITION/COMPOSITION/ENDCOMPOSITION`，维护上屏队列与候选窗定位；焦点管理用 `ImmAssociateContext` 保存/恢复上下文，避免无输入框聚焦时按键被输入法截获；并修复 Win+空格 引发的修饰键卡死（见下） |
| 运行时类 `org.fossic.starsector.ime.*` | `jar_pre_processing/src/main/java/org/fossic/starsector/ime/` | `ImeController`（生命周期/焦点/注入/定位）、`ImeNatives`（JNI 绑定）、`ImeHooks`（ASM 注入入口，全程异常隔离）、`ImeLog`（日志） |
| ASM 注入 | `patches/TextFieldImeHookPatch.java` | 在 `com.fs.starfarer.ui.new`（`TextFieldAPI` 实现）的 `processInputImpl` 开头插入输入处理；在 `releaseFocus` 正常出口插入失焦清理，覆盖文本框同帧关闭后不再执行下一帧处理的情况 |

文本框从 A 切换到 B 时，Java 层先解除 A、清空其尚未消费的组合与上屏队列，再启用 B；
`releaseFocus` 返回时立即解除原生 IME 上下文。因此旧文本不会串入新输入框，关闭文本框后的
游戏快捷键也不会继续被输入法截获。

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

## 构建与集成（Python 编排：`build.py`）

构建脚本以 Python 为主：`build.py` 依次调用 g++ 编译原生库、mvnw 运行 Java
管线（运行时类随工具一起由 Maven 编译，编译期依赖 pom 中 system scope 的
`game data/starfarer.api.jar`；`ImeRuntimeInjector` 从 classpath 取编译产物
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

## 日志

- **游戏日志**（`starsector.log`）：只记录带 `[SS-IME]` 前缀的错误，不记录焦点切换、
  候选窗坐标或玩家输入内容。不可恢复错误会让钩子在本次会话熔断，避免热路径逐帧抛错。
- **原生日志**（`<游戏 logs 目录>/starsector_ime_native.log`，与 `starsector.log` 同级）：
  只记录窗口过程接管失败等原生错误，每次游戏启动时清空重写。路径由 Java 侧读
  `com.fs.starfarer.settings.paths.logs` 构造后经 `nativeInit` 传入。

原生窗口上下文与游戏进程同生命周期，不在 JVM shutdown hook 中主动释放。退出时 Windows
会随进程统一回收窗口过程和上下文，避免 shutdown 线程与仍在执行的 WndProc 发生释放竞态。

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

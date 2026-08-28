/*
 * ssime.dll — 远行星号（Starsector）汉化版 Windows 输入法（IMM32）原生支持。
 *
 * 游戏基于 LWJGL2，其窗口过程不处理 WM_IME_* 消息，因此系统输入法无法在游戏
 * 内正常工作。本库通过 SetWindowLongPtrW 子类化 LWJGL2 创建的窗口过程，接管
 * IME 组合消息，把最终上屏文本放入队列供 Java 层逐字符注入游戏输入框，并把
 * 候选窗定位到输入框光标处。
 *
 * 对应 Java 类：org.fossic.starsector.ime.ImeNatives
 *
 * 设计要点：
 *  - 焦点管理用 ImmAssociateContext 保存/恢复窗口的 IME 上下文：无输入框聚焦时
 *    解除上下文，避免中文输入法状态下按键被 IME 吞掉、干扰游戏快捷键；输入框
 *    聚焦时恢复上下文启用输入法。
 *  - 只读取组合/结果串并定位候选窗，不接管候选窗绘制（交系统绘制），因此在
 *    独占全屏下候选窗可能不可见，需配合窗口化/无边框全屏使用。
 *  - 所有跨线程状态用互斥量保护（WndProc 与 poll 通常同在主线程，互斥量作为
 *    保险）。
 */

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <imm.h>
#include <jni.h>

#include <cstdio>
#include <cstdarg>
#include <deque>
#include <mutex>
#include <string>

namespace {

// 存放上下文指针的窗口属性名。
constexpr wchar_t CONTEXT_PROP[] = L"FossicSSImeContext";
constexpr char LOG_FILE_NAME[] = "starsector_ime_native.log";

enum class NativeState : jint {
    DETACHED = 0,
    ENABLING = 1,
    ENABLED = 2,
    CANCELLING = 3,
    WINDOW_GONE = 4,
    FAILED = 5,
    RETIRED = 6,
};

enum class AttachStatus : jint {
    SUCCESS = 0,
    RETRYABLE_FAILURE = 1,
    PERMANENT_FAILURE = 2,
};

enum class TransitionResult : jint {
    SUCCESS = 0,
    WINDOW_GONE = 1,
    WRONG_THREAD = 2,
    RETRYABLE_FAILURE = 3,
    PERMANENT_FAILURE = 4,
};

std::mutex g_logMutex;
std::string g_lastError;
bool g_debugEnabled = false;
thread_local AttachStatus g_lastAttachStatus = AttachStatus::SUCCESS;
// 日志完整路径，由 nativeInit 从 Java 侧接收（游戏的 logs 目录）。为空时退回
// 相对路径——那依赖进程 CWD 恰好是 starsector-core，启动方式一变日志就会落到
// 意想不到的地方且静默失败。
std::wstring g_logPath;

// 每个被接管的窗口对应一个上下文。
//
// 上下文与游戏进程同生命周期，退出时由 Windows 统一回收。不能从 JVM shutdown
// hook 删除：shutdown hook 在独立线程运行，可能与已进入 imeWndProc 的调用形成 UAF。
//
// mutex 必须是递归锁：Imm* API（如 ImmAssociateContext 在组合中途解除上下文时）
// 会以同线程 SendMessage 方式同步回调本窗口过程（WM_IME_ENDCOMPOSITION 等），
// 形成"持锁 → Imm 调用 → WndProc 重入 → 再次加锁"的同线程重入，非递归锁在此
// 场景下是未定义行为（实测表现为永久卡死）。
struct ImeContext {
    HWND hwnd = nullptr;
    WNDPROC originalWndProc = nullptr;
    HIMC savedContext = nullptr;   // 焦点离开时解除并保存的 IME 上下文
    bool savedOpenStatus = false;  // 解绑前用户为该窗口选择的中/英文输入状态
    bool hasSavedOpenStatus = false;
    bool imeEnabled = false;       // 当前是否已恢复 IME 上下文（输入框聚焦中）
    NativeState state = NativeState::DETACHED;
    DWORD windowThread = 0;
    bool composing = false;
    int spotX = 0;
    int spotY = 0;
    int spotHeight = 16;
    std::deque<std::wstring> committed;
    std::wstring preedit;
    bool winComboPending = false;
    std::recursive_mutex mutex;
};

using ImeLock = std::lock_guard<std::recursive_mutex>;

bool hasAssociatedContext(HWND hwnd, HIMC* value = nullptr) {
    HIMC current = ImmGetContext(hwnd);
    if (value != nullptr) {
        *value = current;
    }
    if (current == nullptr) {
        return false;
    }
    ImmReleaseContext(hwnd, current);
    return true;
}

// 停止当前组合/候选会话，并把这个窗口专属的输入上下文切到关闭状态。单纯把
// HIMC 从 HWND 摘下不足以约束现代 TSF 输入法：部分输入法仍会把按键当作
// VK_PROCESSKEY 消费，直到上下文本身关闭。这里不影响其他应用的 HIMC。
bool setOpenStatusVerified(HIMC himc, bool open) {
    if (himc == nullptr) {
        return true;
    }
    BOOL desired = open ? TRUE : FALSE;
    if ((ImmGetOpenStatus(himc) != FALSE) == open) {
        return true;
    }
    ImmSetOpenStatus(himc, desired);
    return (ImmGetOpenStatus(himc) != FALSE) == open;
}

bool closeImeSession(HIMC himc) {
    if (himc == nullptr) {
        return true;
    }
    ImmNotifyIME(himc, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
    ImmNotifyIME(himc, NI_CLOSECANDIDATE, 0, 0);
    return setOpenStatusVerified(himc, false);
}

// 调用方需持有 ctx->mutex。captureOpenStatus=true 表示从真实的输入状态转入
// gameplay，需要保存用户选择；false 表示系统意外重绑，只在换了 HIMC 时更新。
bool detachForGameplayLocked(ImeContext* ctx, bool captureOpenStatus) {
    HIMC active = ImmGetContext(ctx->hwnd);
    if (active != nullptr) {
        if (captureOpenStatus || !ctx->hasSavedOpenStatus || active != ctx->savedContext) {
            ctx->savedOpenStatus = ImmGetOpenStatus(active) != FALSE;
            ctx->hasSavedOpenStatus = true;
        }
        // 先尽力终止仍关联窗口的组合；最终结果在解绑后统一复读确认。
        closeImeSession(active);
        ImmReleaseContext(ctx->hwnd, active);
    }

    HIMC previous = ImmAssociateContext(ctx->hwnd, nullptr);
    if (previous != nullptr) {
        // active 通常等于 previous；若输入法在两次调用之间替换了上下文，仍要确保
        // 新上下文关闭，并把它作为下一次文本输入要恢复的上下文。
        if (previous != active) {
            if (captureOpenStatus || !ctx->hasSavedOpenStatus
                    || previous != ctx->savedContext) {
                ctx->savedOpenStatus = ImmGetOpenStatus(previous) != FALSE;
                ctx->hasSavedOpenStatus = true;
            }
            closeImeSession(previous);
        }
        ctx->savedContext = previous;
    }
    // 以最终复读结果为准；某些 IME 第一次在仍关联时拒绝切换，但解绑后可成功。
    // 对本轮见过的每个不同 HIMC 再确认一次，避免把已恢复的瞬时失败误报为永久失败。
    bool finalClosed = closeImeSession(active);
    if (previous != active) {
        finalClosed = closeImeSession(previous) && finalClosed;
    }
    if (ctx->savedContext != active && ctx->savedContext != previous) {
        finalClosed = closeImeSession(ctx->savedContext) && finalClosed;
    }
    ctx->imeEnabled = false;
    return finalClosed;
}

bool restoreSavedOpenStatusLocked(ImeContext* ctx) {
    return !ctx->hasSavedOpenStatus || ctx->savedContext == nullptr
            || setOpenStatusVerified(ctx->savedContext, ctx->savedOpenStatus);
}

// 按当前配置打开日志文件；g_logPath 为空时退回 CWD 下的相对路径。
FILE* openLog(const char* mode) {
    if (!g_logPath.empty()) {
        wchar_t wmode[4] = {static_cast<wchar_t>(mode[0]), 0, 0, 0};
        return _wfopen(g_logPath.c_str(), wmode);
    }
    return std::fopen(LOG_FILE_NAME, mode);
}

// 每次库加载时清空日志，避免跨会话无限增长。
void resetLog() {
    std::lock_guard<std::mutex> lock(g_logMutex);
    FILE* f = openLog("w");
    if (f != nullptr) {
        std::fclose(f);
    }
}

void writeLogLine(const char* fmt, va_list args) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    FILE* f = openLog("a");
    if (f == nullptr) {
        return;
    }
    SYSTEMTIME st;
    GetLocalTime(&st);
    std::fprintf(f, "[%02d:%02d:%02d.%03d] ", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    std::vfprintf(f, fmt, args);
    std::fputc('\n', f);
    std::fclose(f);
}

void logLine(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    writeLogLine(fmt, args);
    va_end(args);
}

void debugLine(const char* fmt, ...) {
    if (!g_debugEnabled) {
        return;
    }
    va_list args;
    va_start(args, fmt);
    writeLogLine(fmt, args);
    va_end(args);
}

void setLastError(const std::string& value) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    g_lastError = value;
}

// UTF-16（Windows 宽字符）转 JNI jstring。
jstring toJString(JNIEnv* env, const std::wstring& text) {
    static_assert(sizeof(wchar_t) == sizeof(jchar), "wchar_t must be 16-bit on Windows");
    return env->NewString(reinterpret_cast<const jchar*>(text.data()),
                          static_cast<jsize>(text.size()));
}

std::wstring readCompositionString(HWND hwnd, DWORD index) {
    HIMC himc = ImmGetContext(hwnd);
    if (himc == nullptr) {
        return std::wstring();
    }
    LONG bytes = ImmGetCompositionStringW(himc, index, nullptr, 0);
    if (bytes <= 0) {
        ImmReleaseContext(hwnd, himc);
        return std::wstring();
    }
    std::wstring buffer(static_cast<size_t>(bytes) / sizeof(wchar_t), L'\0');
    ImmGetCompositionStringW(himc, index, buffer.data(), bytes);
    ImmReleaseContext(hwnd, himc);
    return buffer;
}

// 把组合窗和候选窗定位到 spot（客户区物理像素坐标）。调用方需已持有 ctx->mutex。
void updateSpotLocked(ImeContext* ctx) {
    if (ctx->hwnd == nullptr || !IsWindow(ctx->hwnd)) {
        return;
    }
    HIMC himc = ImmGetContext(ctx->hwnd);
    if (himc == nullptr) {
        return;
    }
    COMPOSITIONFORM cf;
    ZeroMemory(&cf, sizeof(cf));
    cf.dwStyle = CFS_POINT;
    cf.ptCurrentPos.x = ctx->spotX;
    cf.ptCurrentPos.y = ctx->spotY;
    ImmSetCompositionWindow(himc, &cf);

    CANDIDATEFORM caf;
    ZeroMemory(&caf, sizeof(caf));
    caf.dwIndex = 0;
    caf.dwStyle = CFS_EXCLUDE;
    caf.ptCurrentPos.x = ctx->spotX;
    caf.ptCurrentPos.y = ctx->spotY;
    caf.rcArea.left = ctx->spotX;
    caf.rcArea.top = ctx->spotY;
    caf.rcArea.right = ctx->spotX + 1;
    caf.rcArea.bottom = ctx->spotY + (ctx->spotHeight > 0 ? ctx->spotHeight : 16);
    ImmSetCandidateWindow(himc, &caf);

    ImmReleaseContext(ctx->hwnd, himc);
}

// Win+空格 等系统热键会向窗口发送修饰键 WM_KEYDOWN 却吞掉其 WM_KEYUP，导致 LWJGL
// 的键状态缓冲把该修饰键卡在"按下"（游戏跨平台，Win/Meta 键被当作 Ctrl 等价，进而
// 使文本框退格误判为 Ctrl+退格 = 删词）。检测到 Win 键按下后置位此标志，待 Win 键
// 物理松开时对所有已松开的修饰键补发 WM_KEYUP 使 LWJGL 复位。
LPARAM makeKeyUpLParam(UINT vk) {
    UINT scan = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
    bool extended = (vk == VK_RCONTROL || vk == VK_RMENU || vk == VK_LWIN || vk == VK_RWIN);
    LPARAM lp = 1;                       // repeat count
    lp |= static_cast<LPARAM>(scan & 0xFF) << 16;
    if (extended) {
        lp |= static_cast<LPARAM>(1) << 24;   // extended key
    }
    lp |= static_cast<LPARAM>(1) << 30;       // previous state = down
    lp |= static_cast<LPARAM>(1) << 31;       // transition = up
    return lp;
}

// 对所有物理上已松开的修饰键补发 WM_KEYUP 给原窗口过程（LWJGL），复位卡死状态。
// 只补发物理已松开的键：既能清除被吞掉 keyup 的卡死键，又绝不会误放玩家正按住的键；
// LWJGL 对已处于松开态的键会因去重忽略，故对未卡死的键无副作用。
void resyncModifiers(HWND hwnd, WNDPROC original) {
    if (original == nullptr) {
        return;
    }
    static const UINT mods[] = {
        VK_LWIN, VK_RWIN, VK_LCONTROL, VK_RCONTROL,
        VK_LSHIFT, VK_RSHIFT, VK_LMENU, VK_RMENU,
    };
    for (UINT vk : mods) {
        if ((GetAsyncKeyState(vk) & 0x8000) == 0) {
            CallWindowProcW(original, hwnd, WM_KEYUP, vk, makeKeyUpLParam(vk));
        }
    }
}

const char* keyboardMessageName(UINT msg) {
    switch (msg) {
        case WM_KEYDOWN: return "WM_KEYDOWN";
        case WM_KEYUP: return "WM_KEYUP";
        case WM_SYSKEYDOWN: return "WM_SYSKEYDOWN";
        case WM_SYSKEYUP: return "WM_SYSKEYUP";
        case WM_CHAR: return "WM_CHAR";
        case WM_SYSCHAR: return "WM_SYSCHAR";
        case WM_IME_KEYDOWN: return "WM_IME_KEYDOWN";
        case WM_IME_KEYUP: return "WM_IME_KEYUP";
        default: return "WM_KEY_UNKNOWN";
    }
}

void debugKeyboardMessage(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam,
                          ImeContext* ctx) {
    if (!g_debugEnabled) {
        return;
    }
    HIMC himc = ImmGetContext(hwnd);
    int open = himc == nullptr ? -1 : (ImmGetOpenStatus(himc) != FALSE ? 1 : 0);
    UINT translated = wParam == VK_PROCESSKEY ? ImmGetVirtualKey(hwnd)
                                               : static_cast<UINT>(wParam);
    int state = -1;
    bool enabled = false;
    if (ctx != nullptr) {
        ImeLock lock(ctx->mutex);
        state = static_cast<int>(ctx->state);
        enabled = ctx->imeEnabled;
    }
    // WM_CHAR/WM_SYSCHAR 的 wParam 就是实际字符，不写入日志，避免诊断日志记录
    // 玩家名称、存档种子等文本内容。
    bool characterMessage = msg == WM_CHAR || msg == WM_SYSCHAR;
    if (characterMessage) {
        debugLine("key %s hwnd=%p character=<redacted> scan=0x%llx repeat=%llu "
                  "state=%d enabled=%d himc=%p open=%d focus=%p foreground=%p hkl=%p",
                  keyboardMessageName(msg), (void*) hwnd,
                  static_cast<unsigned long long>((lParam >> 16) & 0xff),
                  static_cast<unsigned long long>(lParam & 0xffff), state,
                  enabled ? 1 : 0, (void*) himc, open, (void*) GetFocus(),
                  (void*) GetForegroundWindow(), (void*) GetKeyboardLayout(0));
    } else {
        debugLine("key %s hwnd=%p vk=0x%llx translated=0x%x scan=0x%llx repeat=%llu "
                  "state=%d enabled=%d himc=%p open=%d focus=%p foreground=%p hkl=%p",
                  keyboardMessageName(msg), (void*) hwnd,
                  static_cast<unsigned long long>(wParam), translated,
                  static_cast<unsigned long long>((lParam >> 16) & 0xff),
                  static_cast<unsigned long long>(lParam & 0xffff), state,
                  enabled ? 1 : 0, (void*) himc, open, (void*) GetFocus(),
                  (void*) GetForegroundWindow(), (void*) GetKeyboardLayout(0));
    }
    if (himc != nullptr) {
        ImmReleaseContext(hwnd, himc);
    }
}

LRESULT CALLBACK imeWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(GetPropW(hwnd, CONTEXT_PROP));
    WNDPROC original = (ctx != nullptr) ? ctx->originalWndProc : nullptr;

    if (g_debugEnabled) {
        switch (msg) {
            case WM_KEYDOWN:
            case WM_KEYUP:
            case WM_SYSKEYDOWN:
            case WM_SYSKEYUP:
            case WM_CHAR:
            case WM_SYSCHAR:
            case WM_IME_KEYDOWN:
            case WM_IME_KEYUP:
                debugKeyboardMessage(hwnd, msg, wParam, lParam, ctx);
                break;
            case WM_SETFOCUS:
                debugLine("window WM_SETFOCUS hwnd=%p thread=%lu", (void*) hwnd,
                          GetCurrentThreadId());
                break;
            case WM_KILLFOCUS:
                debugLine("window WM_KILLFOCUS hwnd=%p thread=%lu", (void*) hwnd,
                          GetCurrentThreadId());
                break;
            case WM_INPUTLANGCHANGE:
                debugLine("window WM_INPUTLANGCHANGE hwnd=%p hkl=%p thread=%lu",
                          (void*) hwnd, (void*) lParam, GetCurrentThreadId());
                break;
            case WM_IME_SETCONTEXT:
            {
                HIMC actual = ImmGetContext(hwnd);
                int open = actual == nullptr ? -1
                        : (ImmGetOpenStatus(actual) != FALSE ? 1 : 0);
                debugLine("window WM_IME_SETCONTEXT hwnd=%p active=%d flags=0x%llx "
                          "state=%d himc=%p open=%d focus=%p foreground=%p thread=%lu",
                          (void*) hwnd, wParam != FALSE ? 1 : 0,
                          static_cast<unsigned long long>(lParam),
                          ctx == nullptr ? -1 : static_cast<int>(ctx->state),
                          (void*) actual, open, (void*) GetFocus(),
                          (void*) GetForegroundWindow(),
                          GetCurrentThreadId());
                if (actual != nullptr) {
                    ImmReleaseContext(hwnd, actual);
                }
                break;
            }
            case WM_ACTIVATE:
                debugLine("window WM_ACTIVATE hwnd=%p state=%u minimized=%u other=%p "
                          "focus=%p foreground=%p thread=%lu", (void*) hwnd,
                          LOWORD(wParam), HIWORD(wParam), (void*) lParam,
                          (void*) GetFocus(), (void*) GetForegroundWindow(),
                          GetCurrentThreadId());
                break;
            case WM_ACTIVATEAPP:
                debugLine("window WM_ACTIVATEAPP hwnd=%p active=%d otherThread=%llu "
                          "focus=%p foreground=%p thread=%lu", (void*) hwnd,
                          wParam != FALSE ? 1 : 0,
                          static_cast<unsigned long long>(lParam), (void*) GetFocus(),
                          (void*) GetForegroundWindow(), GetCurrentThreadId());
                break;
            case WM_NCDESTROY:
                debugLine("window WM_NCDESTROY hwnd=%p thread=%lu", (void*) hwnd,
                          GetCurrentThreadId());
                break;
            case WM_IME_STARTCOMPOSITION:
                debugLine("ime WM_IME_STARTCOMPOSITION hwnd=%p enabled=%d", (void*) hwnd,
                          ctx != nullptr && ctx->state == NativeState::ENABLED ? 1 : 0);
                break;
            case WM_IME_COMPOSITION:
                debugLine("ime WM_IME_COMPOSITION hwnd=%p flags=0x%llx enabled=%d",
                          (void*) hwnd, static_cast<unsigned long long>(lParam),
                          ctx != nullptr && ctx->state == NativeState::ENABLED ? 1 : 0);
                break;
            case WM_IME_ENDCOMPOSITION:
                debugLine("ime WM_IME_ENDCOMPOSITION hwnd=%p enabled=%d", (void*) hwnd,
                          ctx != nullptr && ctx->state == NativeState::ENABLED ? 1 : 0);
                break;
            case WM_IME_CHAR:
                debugLine("ime WM_IME_CHAR hwnd=%p enabled=%d", (void*) hwnd,
                          ctx != nullptr && ctx->state == NativeState::ENABLED ? 1 : 0);
                break;
            default:
                break;
        }
    }

    // 修饰键卡死防护（状态按窗口上下文保存，避免多窗口相互污染）。
    switch (msg) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
            if (wParam == VK_LWIN || wParam == VK_RWIN) {
                if (ctx != nullptr) {
                    ctx->winComboPending = true;
                }
            }
            [[fallthrough]];
        case WM_KEYUP:
        case WM_SYSKEYUP:
        case WM_INPUTLANGCHANGE:
            if (ctx != nullptr && ctx->winComboPending
                    && (GetAsyncKeyState(VK_LWIN) & 0x8000) == 0
                    && (GetAsyncKeyState(VK_RWIN) & 0x8000) == 0) {
                ctx->winComboPending = false;
                // 在转发当前消息前复位，使紧随的按键（如退格）看到干净的修饰键状态。
                resyncModifiers(hwnd, original);
            }
            break;
        default:
            break;
    }

    if (ctx != nullptr) {
        switch (msg) {
            case WM_IME_STARTCOMPOSITION: {
                {
                    ImeLock lock(ctx->mutex);
                    ctx->preedit.clear();
                    ctx->composing = ctx->state == NativeState::ENABLED;
                    if (ctx->state == NativeState::ENABLED) {
                        updateSpotLocked(ctx);
                    }
                }
                break;
            }
            case WM_IME_COMPOSITION: {
                bool acceptsComposition;
                {
                    ImeLock lock(ctx->mutex);
                    acceptsComposition = ctx->state == NativeState::ENABLED;
                    if (!acceptsComposition) {
                        ctx->composing = false;
                        ctx->preedit.clear();
                    }
                }
                if (acceptsComposition && (lParam & GCS_RESULTSTR)) {
                    std::wstring result = readCompositionString(hwnd, GCS_RESULTSTR);
                    if (!result.empty()) {
                        {
                            ImeLock lock(ctx->mutex);
                            if (ctx->state == NativeState::ENABLED) {
                                ctx->committed.push_back(result);
                            }
                        }
                    }
                }
                if (acceptsComposition && (lParam & GCS_COMPSTR)) {
                    std::wstring comp = readCompositionString(hwnd, GCS_COMPSTR);
                    ImeLock lock(ctx->mutex);
                    if (ctx->state == NativeState::ENABLED) {
                        ctx->preedit = comp;
                        ctx->composing = !comp.empty();
                        updateSpotLocked(ctx);
                    }
                }
                break;
            }
            case WM_IME_ENDCOMPOSITION: {
                {
                    ImeLock lock(ctx->mutex);
                    ctx->composing = false;
                    ctx->preedit.clear();
                }
                break;
            }
            default:
                break;
        }

        if (msg == WM_NCDESTROY) {
            ImeLock lock(ctx->mutex);
            if (ctx->state == NativeState::ENABLED
                    || ctx->state == NativeState::ENABLING) {
                HIMC active = ImmGetContext(hwnd);
                if (active != nullptr) {
                    ctx->savedContext = active;
                    ctx->savedOpenStatus = ImmGetOpenStatus(active) != FALSE;
                    ctx->hasSavedOpenStatus = true;
                    ImmReleaseContext(hwnd, active);
                }
            } else if (ctx->state != NativeState::RETIRED
                    && !restoreSavedOpenStatusLocked(ctx)) {
                logLine("WM_NCDESTROY: failed to restore saved IME open status");
            }
            ctx->state = NativeState::WINDOW_GONE;
            ctx->imeEnabled = false;
            ctx->composing = false;
            ctx->preedit.clear();
            ctx->committed.clear();
            ctx->hwnd = nullptr;
        }
    }

    // 窗口激活时，Windows/TSF 可能在发送 WM_IME_SETCONTEXT 前恢复线程默认 HIMC。
    // 游戏没有文本框请求输入时仍需转发消息，但清空 UI 标志，避免系统先弹出游离的
    // 组合/候选窗；转发完成后再重新断言解绑状态。
    LPARAM forwardedLParam = lParam;
    if (ctx != nullptr && msg == WM_IME_SETCONTEXT) {
        ImeLock lock(ctx->mutex);
        if (ctx->state == NativeState::DETACHED || ctx->state == NativeState::CANCELLING) {
            forwardedLParam = 0;
        }
    }

    LRESULT result;
    if (original != nullptr) {
        result = CallWindowProcW(original, hwnd, msg, wParam, forwardedLParam);
    } else {
        result = DefWindowProcW(hwnd, msg, wParam, forwardedLParam);
    }

    // Windows 在窗口重新获得 OS 焦点、激活 IME 上下文或切换输入语言后可能自行
    // 重新关联 HIMC。
    // 游戏控件未请求文字输入时，转发系统消息后重新断言 DETACHED/CANCELLING。
    if (ctx != nullptr && (msg == WM_SETFOCUS || msg == WM_IME_SETCONTEXT
            || msg == WM_INPUTLANGCHANGE)) {
        ImeLock lock(ctx->mutex);
        if ((ctx->state == NativeState::DETACHED || ctx->state == NativeState::CANCELLING)
                && ctx->hwnd != nullptr && IsWindow(ctx->hwnd)) {
            bool closed = detachForGameplayLocked(ctx, false);
            if (!closed || hasAssociatedContext(ctx->hwnd)) {
                ctx->state = NativeState::FAILED;
                logLine("lifecycle reassert: gameplay postcondition failed");
            }
        }
    }
    if (ctx != nullptr && msg == WM_NCDESTROY) {
        // 保留 property 直到原 WndProc 返回，确保它同步产生的嵌套消息仍能沿正确的
        // subclass 链转发；此后窗口才真正不再需要上下文查找。
        RemovePropW(hwnd, CONTEXT_PROP);
    }
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeAbiVersion(JNIEnv*, jclass) {
    return 2;
}

static jboolean initialize(JNIEnv* env, jstring logPath, jboolean debug) {
    if (logPath != nullptr) {
        const jchar* chars = env->GetStringChars(logPath, nullptr);
        if (chars != nullptr) {
            jsize len = env->GetStringLength(logPath);
            static_assert(sizeof(jchar) == sizeof(wchar_t), "wchar_t must be 16-bit on Windows");
            std::lock_guard<std::mutex> lock(g_logMutex);
            g_logPath.assign(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(len));
            env->ReleaseStringChars(logPath, chars);
        }
    }
    g_debugEnabled = debug == JNI_TRUE;
    resetLog();
    debugLine("nativeInit debug=%d thread=%lu", g_debugEnabled ? 1 : 0,
              GetCurrentThreadId());
    return JNI_TRUE;
}

// ABI 1 compatibility: an old Jar can still be paired with this DLL without calling a
// function through the wrong native signature.  New code checks ABI 2 and uses nativeInitV2.
JNIEXPORT jboolean JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeInit(JNIEnv* env, jclass,
                                                      jstring logPath) {
    return initialize(env, logPath, JNI_FALSE);
}

JNIEXPORT jboolean JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeInitV2(JNIEnv* env, jclass,
                                                        jstring logPath, jboolean debug) {
    return initialize(env, logPath, debug);
}

TransitionResult validateTransition(ImeContext* ctx, const char* operation,
                                    bool allowFailed = false) {
    if (ctx == nullptr) {
        setLastError(std::string(operation) + ": null context");
        return TransitionResult::PERMANENT_FAILURE;
    }
    if (GetCurrentThreadId() != ctx->windowThread) {
        setLastError(std::string(operation) + ": wrong thread");
        return TransitionResult::WRONG_THREAD;
    }
    ImeLock lock(ctx->mutex);
    if (ctx->state == NativeState::WINDOW_GONE || ctx->hwnd == nullptr
            || !IsWindow(ctx->hwnd)) {
        ctx->state = NativeState::WINDOW_GONE;
        ctx->imeEnabled = false;
        setLastError(std::string(operation) + ": window is gone");
        return TransitionResult::WINDOW_GONE;
    }
    if (ctx->state == NativeState::FAILED && !allowFailed) {
        setLastError(std::string(operation) + ": context is failed");
        return TransitionResult::PERMANENT_FAILURE;
    }
    if (ctx->state == NativeState::RETIRED) {
        setLastError(std::string(operation) + ": context is retired");
        return TransitionResult::PERMANENT_FAILURE;
    }
    return TransitionResult::SUCCESS;
}

JNIEXPORT jlong JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeAttach(JNIEnv*, jclass, jlong hwndValue) {
    g_lastAttachStatus = AttachStatus::PERMANENT_FAILURE;
    HWND hwnd = reinterpret_cast<HWND>(static_cast<uintptr_t>(hwndValue));
    DWORD windowThread = hwnd == nullptr ? 0 : GetWindowThreadProcessId(hwnd, nullptr);
    debugLine("nativeAttach request hwnd=%p currentThread=%lu windowThread=%lu",
              (void*) hwnd, GetCurrentThreadId(), windowThread);
    if (hwnd == nullptr || !IsWindow(hwnd)) {
        g_lastAttachStatus = AttachStatus::RETRYABLE_FAILURE;
        setLastError("nativeAttach: invalid HWND");
        debugLine("nativeAttach: invalid HWND %p", (void*) hwnd);
        return 0;
    }
    if (windowThread != GetCurrentThreadId()) {
        g_lastAttachStatus = AttachStatus::RETRYABLE_FAILURE;
        setLastError("nativeAttach: wrong window thread");
        debugLine("nativeAttach: wrong thread current=%lu window=%lu",
                  GetCurrentThreadId(), windowThread);
        return 0;
    }
    if (GetPropW(hwnd, CONTEXT_PROP) != nullptr) {
        setLastError("nativeAttach: already attached");
        logLine("nativeAttach: window already attached");
        return 0;
    }

    ImeContext* ctx = new ImeContext();
    ctx->hwnd = hwnd;
    ctx->windowThread = windowThread;

    if (!SetPropW(hwnd, CONTEXT_PROP, reinterpret_cast<HANDLE>(ctx))) {
        setLastError("nativeAttach: SetPropW failed");
        logLine("nativeAttach: SetPropW failed err=%lu", GetLastError());
        delete ctx;
        return 0;
    }

    SetLastError(0);
    LONG_PTR previous = SetWindowLongPtrW(hwnd, GWLP_WNDPROC,
                                          reinterpret_cast<LONG_PTR>(&imeWndProc));
    DWORD err = GetLastError();
    if (previous == 0 && err != 0) {
        setLastError("nativeAttach: SetWindowLongPtrW failed");
        logLine("nativeAttach: SetWindowLongPtrW failed err=%lu", err);
        RemovePropW(hwnd, CONTEXT_PROP);
        delete ctx;
        return 0;
    }
    ctx->originalWndProc = reinterpret_cast<WNDPROC>(previous);

    // 默认解除 IME 上下文：未聚焦输入框时按键不被输入法截获。先进入取消态，
    // 确保 ImmAssociateContext 同步重入 WndProc 时不会接收旧组合结果。
    ctx->state = NativeState::CANCELLING;
    bool closed;
    {
        ImeLock lock(ctx->mutex);
        closed = detachForGameplayLocked(ctx, true);
        if (!closed) {
            setLastError("nativeAttach: failed to close input context");
        }
    }
    bool stillAssociated = hasAssociatedContext(hwnd);
    bool stillOpen = ctx->savedContext != nullptr
            && ImmGetOpenStatus(ctx->savedContext) != FALSE;
    if (!closed || stillAssociated || stillOpen) {
        g_lastAttachStatus = AttachStatus::RETRYABLE_FAILURE;
        setLastError(stillAssociated
                ? "nativeAttach: failed to detach input context"
                : "nativeAttach: failed to close input context");
        logLine("nativeAttach: gameplay postcondition failed associated=%d open=%d",
                stillAssociated ? 1 : 0, stillOpen ? 1 : 0);
        if (ctx->savedContext != nullptr) {
            ImmAssociateContext(hwnd, ctx->savedContext);
            if (ctx->hasSavedOpenStatus) {
                if (!setOpenStatusVerified(ctx->savedContext,
                                           ctx->savedOpenStatus)) {
                    logLine("nativeAttach rollback: failed to restore open status");
                }
            }
        }
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC,
                          reinterpret_cast<LONG_PTR>(ctx->originalWndProc));
        RemovePropW(hwnd, CONTEXT_PROP);
        delete ctx;
        return 0;
    }
    ctx->state = NativeState::DETACHED;
    g_lastAttachStatus = AttachStatus::SUCCESS;
    debugLine("nativeAttach complete hwnd=%p ctx=%p savedHimc=%p savedOpen=%d "
              "savedOpenValid=%d", (void*) hwnd, (void*) ctx,
              (void*) ctx->savedContext, ctx->savedOpenStatus ? 1 : 0,
              ctx->hasSavedOpenStatus ? 1 : 0);

    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeLastAttachStatus(JNIEnv*, jclass) {
    return static_cast<jint>(g_lastAttachStatus);
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeEnable(JNIEnv*, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    TransitionResult validation = validateTransition(ctx, "nativeEnable");
    if (validation != TransitionResult::SUCCESS) {
        return static_cast<jint>(validation);
    }
    ImeLock lock(ctx->mutex);
    if (ctx->state == NativeState::ENABLED) {
        return static_cast<jint>(TransitionResult::SUCCESS);
    }
    if (ctx->state != NativeState::DETACHED) {
        setLastError("nativeEnable: context is not detached");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    if (ctx->savedContext == nullptr) {
        ctx->state = NativeState::FAILED;
        setLastError("nativeEnable: no saved input context");
        return static_cast<jint>(TransitionResult::PERMANENT_FAILURE);
    }

    // 必须先切状态：ImmAssociateContext 可能同步重入 imeWndProc。
    ctx->state = NativeState::ENABLING;
    ImmAssociateContext(ctx->hwnd, ctx->savedContext);
    HIMC actual = nullptr;
    bool associated = hasAssociatedContext(ctx->hwnd, &actual);
    if (!associated || actual != ctx->savedContext) {
        bool detached = detachForGameplayLocked(ctx, false);
        bool stillAssociated = hasAssociatedContext(ctx->hwnd);
        ctx->state = stillAssociated ? NativeState::FAILED : NativeState::DETACHED;
        setLastError("nativeEnable: input-context postcondition failed");
        return static_cast<jint>(ctx->state == NativeState::FAILED || !detached
                ? TransitionResult::PERMANENT_FAILURE
                : TransitionResult::RETRYABLE_FAILURE);
    }
    if (!restoreSavedOpenStatusLocked(ctx)) {
        bool detached = detachForGameplayLocked(ctx, false);
        bool stillAssociated = hasAssociatedContext(ctx->hwnd);
        ctx->state = detached && !stillAssociated
                ? NativeState::DETACHED : NativeState::FAILED;
        ctx->imeEnabled = false;
        setLastError("nativeEnable: failed to restore input open status");
        return static_cast<jint>(ctx->state == NativeState::FAILED
                ? TransitionResult::PERMANENT_FAILURE
                : TransitionResult::RETRYABLE_FAILURE);
    }
    ctx->imeEnabled = true;
    ctx->state = NativeState::ENABLED;
    updateSpotLocked(ctx);
    debugLine("nativeEnable complete ctx=%p savedHimc=%p restoredOpen=%d actualOpen=%d",
              (void*) ctx, (void*) ctx->savedContext,
              ctx->savedOpenStatus ? 1 : 0,
              ImmGetOpenStatus(actual) != FALSE ? 1 : 0);
    return static_cast<jint>(TransitionResult::SUCCESS);
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeBeginCancel(JNIEnv*, jclass,
                                                             jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    // 紧急清理正是在普通状态机已经 FAILED 时最重要，因此该入口仍允许尝试解绑；
    // 线程、窗口和 RETIRED 校验依然严格执行。
    TransitionResult validation = validateTransition(ctx, "nativeBeginCancel", true);
    if (validation != TransitionResult::SUCCESS) {
        return static_cast<jint>(validation);
    }
    ImeLock lock(ctx->mutex);
    if (ctx->state == NativeState::CANCELLING) {
        bool closed = detachForGameplayLocked(ctx, false);
        if (!closed || hasAssociatedContext(ctx->hwnd)) {
            setLastError("nativeBeginCancel: gameplay postcondition still not met");
            return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
        }
        return static_cast<jint>(TransitionResult::SUCCESS);
    }

    // 先进入 CANCELLING，确保下面 IMM 调用同步重入时只能走丢弃路径。
    ctx->state = NativeState::CANCELLING;
    bool closed = detachForGameplayLocked(ctx, true);
    ctx->composing = false;
    ctx->preedit.clear();
    ctx->committed.clear();
    if (hasAssociatedContext(ctx->hwnd) || !closed) {
        setLastError("nativeBeginCancel: gameplay postcondition failed");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    debugLine("nativeBeginCancel complete ctx=%p savedHimc=%p savedOpen=%d "
              "savedOpenValid=%d actualOpen=%d", (void*) ctx,
              (void*) ctx->savedContext, ctx->savedOpenStatus ? 1 : 0,
              ctx->hasSavedOpenStatus ? 1 : 0,
              ctx->savedContext == nullptr ? -1
                      : (ImmGetOpenStatus(ctx->savedContext) != FALSE ? 1 : 0));
    return static_cast<jint>(TransitionResult::SUCCESS);
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeFinishCancel(JNIEnv*, jclass,
                                                              jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    TransitionResult validation = validateTransition(ctx, "nativeFinishCancel");
    if (validation != TransitionResult::SUCCESS) {
        return static_cast<jint>(validation);
    }
    ImeLock lock(ctx->mutex);
    if (ctx->state == NativeState::DETACHED) {
        return static_cast<jint>(TransitionResult::SUCCESS);
    }
    if (ctx->state != NativeState::CANCELLING) {
        setLastError("nativeFinishCancel: context is not cancelling");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    bool closed = detachForGameplayLocked(ctx, false);
    if (hasAssociatedContext(ctx->hwnd) || !closed) {
        setLastError("nativeFinishCancel: gameplay postcondition failed");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    ctx->state = NativeState::DETACHED;
    ctx->imeEnabled = false;
    return static_cast<jint>(TransitionResult::SUCCESS);
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeRetire(JNIEnv*, jclass,
                                                        jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    TransitionResult validation = validateTransition(ctx, "nativeRetire");
    if (validation != TransitionResult::SUCCESS) {
        return static_cast<jint>(validation);
    }
    ImeLock lock(ctx->mutex);
    bool captureOpenStatus = ctx->state == NativeState::ENABLED
            || ctx->state == NativeState::ENABLING;
    ctx->state = NativeState::CANCELLING;
    detachForGameplayLocked(ctx, captureOpenStatus);
    ctx->composing = false;
    ctx->preedit.clear();
    ctx->committed.clear();
    if (hasAssociatedContext(ctx->hwnd)) {
        setLastError("nativeRetire: failed to detach old window input context");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    if (!restoreSavedOpenStatusLocked(ctx)) {
        setLastError("nativeRetire: failed to restore user input open status");
        return static_cast<jint>(TransitionResult::RETRYABLE_FAILURE);
    }
    ctx->imeEnabled = false;
    ctx->state = NativeState::RETIRED;
    return static_cast<jint>(TransitionResult::SUCCESS);
}

JNIEXPORT jint JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeState(JNIEnv*, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        return static_cast<jint>(NativeState::FAILED);
    }
    ImeLock lock(ctx->mutex);
    if (ctx->state != NativeState::WINDOW_GONE
            && (ctx->hwnd == nullptr || !IsWindow(ctx->hwnd))) {
        ctx->state = NativeState::WINDOW_GONE;
        ctx->imeEnabled = false;
    }
    return static_cast<jint>(ctx->state);
}

// 旧 ABI 保留一版，避免单独替换 DLL 时旧 Jar 直接出现 UnsatisfiedLinkError。
JNIEXPORT void JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeSetFocused(JNIEnv* env, jclass type,
                                                           jlong ctxValue, jboolean focused) {
    if (focused == JNI_TRUE) {
        Java_org_fossic_starsector_ime_ImeNatives_nativeEnable(env, type, ctxValue);
    } else {
        Java_org_fossic_starsector_ime_ImeNatives_nativeBeginCancel(env, type, ctxValue);
        Java_org_fossic_starsector_ime_ImeNatives_nativeFinishCancel(env, type, ctxValue);
    }
}

JNIEXPORT void JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeSetSpot(JNIEnv*, jclass, jlong ctxValue,
                                                        jint x, jint y, jint height) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        return;
    }
    ImeLock lock(ctx->mutex);
    ctx->spotX = x;
    ctx->spotY = y;
    ctx->spotHeight = height;
    if (ctx->state == NativeState::ENABLED) {
        updateSpotLocked(ctx);
    }
}

JNIEXPORT jstring JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativePoll(JNIEnv* env, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        return nullptr;
    }
    std::wstring value;
    {
        ImeLock lock(ctx->mutex);
        if (ctx->state != NativeState::ENABLED || ctx->committed.empty()) {
            return nullptr;
        }
        value = ctx->committed.front();
        ctx->committed.pop_front();
    }
    return toJString(env, value);
}

JNIEXPORT jstring JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativePreedit(JNIEnv* env, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        // JNI 规范未保证 NewString(nullptr, 0) 合法，统一走 toJString（空串 data() 非空）。
        return toJString(env, std::wstring());
    }
    std::wstring value;
    {
        ImeLock lock(ctx->mutex);
        if (ctx->state == NativeState::ENABLED) {
            value = ctx->preedit;
        }
    }
    return toJString(env, value);
}

JNIEXPORT jboolean JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeComposing(JNIEnv*, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        return JNI_FALSE;
    }
    ImeLock lock(ctx->mutex);
    return ctx->state == NativeState::ENABLED && ctx->composing ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeLastError(JNIEnv* env, jclass) {
    std::string value;
    {
        std::lock_guard<std::mutex> lock(g_logMutex);
        value = g_lastError;
    }
    return env->NewStringUTF(value.c_str());
}

} // extern "C"

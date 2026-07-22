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
constexpr char LOG_FILE[] = "starsector_ime_native.log";

std::mutex g_logMutex;
std::string g_lastError;

// 每个被接管的窗口对应一个上下文。
//
// mutex 必须是递归锁：Imm* API（如 ImmAssociateContext 在组合中途解除上下文时）
// 会以同线程 SendMessage 方式同步回调本窗口过程（WM_IME_ENDCOMPOSITION 等），
// 形成"持锁 → Imm 调用 → WndProc 重入 → 再次加锁"的同线程重入，非递归锁在此
// 场景下是未定义行为（实测表现为永久卡死）。
struct ImeContext {
    HWND hwnd = nullptr;
    WNDPROC originalWndProc = nullptr;
    HIMC savedContext = nullptr;   // 焦点离开时解除并保存的 IME 上下文
    bool imeEnabled = false;       // 当前是否已恢复 IME 上下文（输入框聚焦中）
    bool composing = false;
    int spotX = 0;
    int spotY = 0;
    int spotHeight = 16;
    std::deque<std::wstring> committed;
    std::wstring preedit;
    std::recursive_mutex mutex;
};

using ImeLock = std::lock_guard<std::recursive_mutex>;

// 每次库加载时清空日志，避免跨会话无限增长。
void resetLog() {
    std::lock_guard<std::mutex> lock(g_logMutex);
    FILE* f = std::fopen(LOG_FILE, "w");
    if (f != nullptr) {
        std::fclose(f);
    }
}

void logLine(const char* fmt, ...) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    FILE* f = std::fopen(LOG_FILE, "a");
    if (f == nullptr) {
        return;
    }
    SYSTEMTIME st;
    GetLocalTime(&st);
    std::fprintf(f, "[%02d:%02d:%02d.%03d] ", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    va_list args;
    va_start(args, fmt);
    std::vfprintf(f, fmt, args);
    va_end(args);
    std::fputc('\n', f);
    std::fclose(f);
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
bool g_winComboPending = false;

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
    logLine("resyncModifiers: 修饰键状态已复位（Win 组合结束）");
}

LRESULT CALLBACK imeWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(GetPropW(hwnd, CONTEXT_PROP));
    WNDPROC original = (ctx != nullptr) ? ctx->originalWndProc : nullptr;

    // 修饰键卡死防护（详见 g_winComboPending 注释）。
    switch (msg) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
            if (wParam == VK_LWIN || wParam == VK_RWIN) {
                g_winComboPending = true;
            }
            [[fallthrough]];
        case WM_KEYUP:
        case WM_SYSKEYUP:
        case WM_INPUTLANGCHANGE:
            if (g_winComboPending
                    && (GetAsyncKeyState(VK_LWIN) & 0x8000) == 0
                    && (GetAsyncKeyState(VK_RWIN) & 0x8000) == 0) {
                g_winComboPending = false;
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
                    ctx->composing = true;
                    ctx->preedit.clear();
                    updateSpotLocked(ctx);
                }
                logLine("WM_IME_STARTCOMPOSITION");
                break;
            }
            case WM_IME_COMPOSITION: {
                if (lParam & GCS_RESULTSTR) {
                    std::wstring result = readCompositionString(hwnd, GCS_RESULTSTR);
                    if (!result.empty()) {
                        size_t queueSize;
                        {
                            ImeLock lock(ctx->mutex);
                            ctx->committed.push_back(result);
                            queueSize = ctx->committed.size();
                        }
                        logLine("WM_IME_COMPOSITION result len=%d queue=%zu",
                                (int) result.size(), queueSize);
                    }
                }
                if (lParam & GCS_COMPSTR) {
                    std::wstring comp = readCompositionString(hwnd, GCS_COMPSTR);
                    ImeLock lock(ctx->mutex);
                    ctx->preedit = comp;
                    ctx->composing = !comp.empty();
                    updateSpotLocked(ctx);
                }
                break;
            }
            case WM_IME_ENDCOMPOSITION: {
                {
                    ImeLock lock(ctx->mutex);
                    ctx->composing = false;
                    ctx->preedit.clear();
                }
                logLine("WM_IME_ENDCOMPOSITION");
                break;
            }
            default:
                break;
        }
    }

    if (original != nullptr) {
        return CallWindowProcW(original, hwnd, msg, wParam, lParam);
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeInit(JNIEnv*, jclass) {
    resetLog();
    logLine("nativeInit: ssime native library loaded");
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeAttach(JNIEnv*, jclass, jlong hwndValue) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<uintptr_t>(hwndValue));
    if (hwnd == nullptr || !IsWindow(hwnd)) {
        setLastError("nativeAttach: invalid HWND");
        logLine("nativeAttach: invalid HWND %p", (void*) hwnd);
        return 0;
    }
    if (GetPropW(hwnd, CONTEXT_PROP) != nullptr) {
        setLastError("nativeAttach: already attached");
        logLine("nativeAttach: window already attached");
        return 0;
    }

    ImeContext* ctx = new ImeContext();
    ctx->hwnd = hwnd;

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

    // 默认解除 IME 上下文：未聚焦输入框时按键不被输入法截获。
    ctx->savedContext = ImmAssociateContext(hwnd, nullptr);
    ctx->imeEnabled = false;

    logLine("nativeAttach: attached hwnd=%p ctx=%p originalWndProc=%p",
            (void*) hwnd, (void*) ctx, (void*) ctx->originalWndProc);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

JNIEXPORT void JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeDetach(JNIEnv*, jclass, jlong ctxValue) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr) {
        return;
    }
    HWND hwnd = ctx->hwnd;
    if (hwnd != nullptr && IsWindow(hwnd)) {
        if (!ctx->imeEnabled && ctx->savedContext != nullptr) {
            ImmAssociateContext(hwnd, ctx->savedContext);
        }
        if (GetPropW(hwnd, CONTEXT_PROP) == reinterpret_cast<HANDLE>(ctx)) {
            RemovePropW(hwnd, CONTEXT_PROP);
        }
        if (ctx->originalWndProc != nullptr) {
            SetWindowLongPtrW(hwnd, GWLP_WNDPROC,
                              reinterpret_cast<LONG_PTR>(ctx->originalWndProc));
        }
    }
    logLine("nativeDetach: detached ctx=%p", (void*) ctx);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_org_fossic_starsector_ime_ImeNatives_nativeSetFocused(JNIEnv*, jclass, jlong ctxValue,
                                                           jboolean focused) {
    ImeContext* ctx = reinterpret_cast<ImeContext*>(static_cast<uintptr_t>(ctxValue));
    if (ctx == nullptr || ctx->hwnd == nullptr || !IsWindow(ctx->hwnd)) {
        return;
    }
    int change = 0;  // 0=无变化 1=启用 -1=解除
    {
        ImeLock lock(ctx->mutex);
        if (focused == JNI_TRUE) {
            if (!ctx->imeEnabled) {
                // 恢复此前解除的 IME 上下文，启用输入法。
                // 注意：ImmAssociateContext 可能同步重入本窗口过程（见 ImeContext 注释）。
                ImmAssociateContext(ctx->hwnd, ctx->savedContext);
                ctx->imeEnabled = true;
                change = 1;
            }
            updateSpotLocked(ctx);
        } else {
            if (ctx->imeEnabled) {
                // 解除并保存 IME 上下文，避免按键被输入法截获。
                ctx->savedContext = ImmAssociateContext(ctx->hwnd, nullptr);
                ctx->imeEnabled = false;
                ctx->composing = false;
                ctx->preedit.clear();
                change = -1;
            }
        }
    }
    if (change != 0) {
        logLine(change > 0 ? "nativeSetFocused: enabled ime" : "nativeSetFocused: disabled ime");
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
    if (ctx->imeEnabled) {
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
        if (ctx->committed.empty()) {
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
        value = ctx->preedit;
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
    return ctx->composing ? JNI_TRUE : JNI_FALSE;
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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <imm.h>

#include <cstdio>
#include <cstdint>
#include <cstring>

namespace {

constexpr int RESULT_SUCCESS = 0;
constexpr int RESULT_WRONG_THREAD = 2;
constexpr int STATE_DETACHED = 0;
constexpr int STATE_ENABLED = 2;
constexpr int STATE_CANCELLING = 3;
constexpr int STATE_WINDOW_GONE = 4;
constexpr int STATE_RETIRED = 6;

using InitFn = unsigned char (*)(void*, void*, void*, unsigned char);
using LegacyInitFn = unsigned char (*)(void*, void*, void*);
using AbiVersionFn = int (*)(void*, void*);
using AttachFn = std::int64_t (*)(void*, void*, std::int64_t);
using AttachStatusFn = int (*)(void*, void*);
using TransitionFn = int (*)(void*, void*, std::int64_t);
using StateFn = int (*)(void*, void*, std::int64_t);
using ComposingFn = unsigned char (*)(void*, void*, std::int64_t);
using PollFn = void* (*)(void*, void*, std::int64_t);

int fail(const char* message) {
    std::fprintf(stderr, "FAIL: %s\n", message);
    return 1;
}

bool hasAssociatedContext(HWND window) {
    HIMC context = ImmGetContext(window);
    if (context == nullptr) {
        return false;
    }
    ImmReleaseContext(window, context);
    return true;
}

HIMC getAssociatedContext(HWND window) {
    HIMC context = ImmGetContext(window);
    if (context != nullptr) {
        ImmReleaseContext(window, context);
    }
    return context;
}

template<typename T>
T load(HMODULE module, const char* name) {
    FARPROC raw = GetProcAddress(module, name);
    static_assert(sizeof(raw) == sizeof(T));
    T typed = nullptr;
    std::memcpy(&typed, &raw, sizeof(typed));
    return typed;
}

struct ThreadWindowData {
    HINSTANCE instance;
    const wchar_t* className;
    HANDLE ready;
    HANDLE done;
    HWND window = nullptr;
};

struct ThreadTransitionData {
    TransitionFn function;
    std::int64_t context;
    int result = -1;
};

DWORD WINAPI createWindowOnWorker(void* argument) {
    auto* data = static_cast<ThreadWindowData*>(argument);
    data->window = CreateWindowExW(0, data->className, L"ssime worker", WS_OVERLAPPED,
                                   0, 0, 160, 100, nullptr, nullptr, data->instance, nullptr);
    SetEvent(data->ready);
    WaitForSingleObject(data->done, INFINITE);
    if (data->window != nullptr) {
        DestroyWindow(data->window);
    }
    return 0;
}

DWORD WINAPI invokeTransitionOnWorker(void* argument) {
    auto* data = static_cast<ThreadTransitionData*>(argument);
    data->result = data->function(nullptr, nullptr, data->context);
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        return fail("expected absolute path to freshly built ssime.dll");
    }
    HMODULE module = LoadLibraryA(argv[1]);
    if (module == nullptr) {
        return fail("could not load ssime.dll");
    }

    AbiVersionFn abiVersion = load<AbiVersionFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeAbiVersion");
    LegacyInitFn legacyInit = load<LegacyInitFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeInit");
    InitFn init = load<InitFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeInitV2");
    AttachFn attach = load<AttachFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeAttach");
    AttachStatusFn attachStatus = load<AttachStatusFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeLastAttachStatus");
    TransitionFn enable = load<TransitionFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeEnable");
    TransitionFn beginCancel = load<TransitionFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeBeginCancel");
    TransitionFn finishCancel = load<TransitionFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeFinishCancel");
    TransitionFn retire = load<TransitionFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeRetire");
    StateFn state = load<StateFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeState");
    ComposingFn composing = load<ComposingFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativeComposing");
    PollFn poll = load<PollFn>(
            module, "Java_org_fossic_starsector_ime_ImeNatives_nativePoll");
    if (abiVersion == nullptr || abiVersion(nullptr, nullptr) != 2
            || legacyInit == nullptr || init == nullptr
            || attach == nullptr || attachStatus == nullptr
            || enable == nullptr || beginCancel == nullptr
            || finishCancel == nullptr || retire == nullptr || state == nullptr
            || composing == nullptr || poll == nullptr) {
        FreeLibrary(module);
        return fail("state-machine JNI exports are missing");
    }

    const wchar_t className[] = L"FossicSsimeSmokeWindow";
    WNDCLASSW windowClass{};
    windowClass.lpfnWndProc = DefWindowProcW;
    windowClass.hInstance = GetModuleHandleW(nullptr);
    windowClass.lpszClassName = className;
    if (RegisterClassW(&windowClass) == 0) {
        FreeLibrary(module);
        return fail("RegisterClassW failed");
    }

    if (legacyInit(nullptr, nullptr, nullptr) == 0) {
        UnregisterClassW(className, windowClass.hInstance);
        FreeLibrary(module);
        return fail("legacy nativeInit export failed");
    }

    HWND window = CreateWindowExW(0, className, L"ssime smoke", WS_OVERLAPPED,
                                  0, 0, 320, 200, nullptr, nullptr,
                                  windowClass.hInstance, nullptr);
    if (window == nullptr) {
        UnregisterClassW(className, windowClass.hInstance);
        FreeLibrary(module);
        return fail("CreateWindowExW failed");
    }

    HIMC createdContext = nullptr;
    if (!hasAssociatedContext(window)) {
        createdContext = ImmCreateContext();
        ImmAssociateContext(window, createdContext);
    }
    if (!hasAssociatedContext(window)) {
        DestroyWindow(window);
        UnregisterClassW(className, windowClass.hInstance);
        FreeLibrary(module);
        return fail("test window has no input context");
    }
    HIMC initialContext = getAssociatedContext(window);
    // This assertion is conditional because a build machine may not have an IME layout
    // installed.  When the active context exposes an open/closed state, gameplay attach
    // must close it and text-field enable must restore the user's previous state.
    bool canControlOpenStatus = ImmSetOpenStatus(initialContext, TRUE) != FALSE;
    if (!canControlOpenStatus) {
        std::fputs("SKIP: active layout does not expose controllable IME open status\n", stderr);
    }

    if (attach(nullptr, nullptr, 0) != 0 || attachStatus(nullptr, nullptr) != 1) {
        return fail("attach accepted a null HWND");
    }

    ThreadWindowData workerData{
            windowClass.hInstance,
            className,
            CreateEventW(nullptr, TRUE, FALSE, nullptr),
            CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    HANDLE worker = CreateThread(nullptr, 0, createWindowOnWorker, &workerData, 0, nullptr);
    if (worker == nullptr || workerData.ready == nullptr || workerData.done == nullptr) {
        return fail("could not create worker-window test resources");
    }
    if (WaitForSingleObject(workerData.ready, 5000) != WAIT_OBJECT_0) {
        SetEvent(workerData.done);
        WaitForSingleObject(worker, 5000);
        return fail("worker window creation timed out");
    }
    if (workerData.window == nullptr) {
        return fail("worker window creation failed");
    }
    if (attach(nullptr, nullptr,
               static_cast<std::int64_t>(reinterpret_cast<std::uintptr_t>(workerData.window))) != 0
            || attachStatus(nullptr, nullptr) != 1) {
        return fail("attach accepted a window owned by another thread");
    }
    SetEvent(workerData.done);
    if (WaitForSingleObject(worker, 5000) != WAIT_OBJECT_0) {
        return fail("worker window shutdown timed out");
    }
    CloseHandle(worker);
    CloseHandle(workerData.ready);
    CloseHandle(workerData.done);

    // 清除预期失败 attach 产生的错误日志，并明确验证发布默认 debug=false。
    if (init(nullptr, nullptr, nullptr, 0) == 0) {
        return fail("nativeInit(debug=false) failed");
    }

    std::int64_t context = attach(nullptr, nullptr,
            static_cast<std::int64_t>(reinterpret_cast<std::uintptr_t>(window)));
    if (context == 0 || hasAssociatedContext(window) || state(nullptr, nullptr, context) != STATE_DETACHED) {
        return fail("attach did not establish verified DETACHED state");
    }
    ThreadTransitionData transitionData{enable, context};
    HANDLE transitionWorker = CreateThread(
            nullptr, 0, invokeTransitionOnWorker, &transitionData, 0, nullptr);
    if (transitionWorker == nullptr
            || WaitForSingleObject(transitionWorker, 5000) != WAIT_OBJECT_0) {
        return fail("wrong-thread transition test timed out");
    }
    CloseHandle(transitionWorker);
    if (transitionData.result != RESULT_WRONG_THREAD
            || state(nullptr, nullptr, context) != STATE_DETACHED) {
        return fail("wrong-thread transition touched mutable native state");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) != FALSE) {
        return fail("attach left the saved IME context open during gameplay");
    }
    if (enable(nullptr, nullptr, context) != RESULT_SUCCESS
            || !hasAssociatedContext(window)
            || state(nullptr, nullptr, context) != STATE_ENABLED) {
        return fail("enable did not restore the input context");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) == FALSE) {
        return fail("enable did not restore the user's IME open state");
    }

    SendMessageW(window, WM_IME_STARTCOMPOSITION, 0, 0);
    if (composing(nullptr, nullptr, context) == 0) {
        return fail("enabled state did not accept composition start");
    }
    if (beginCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || hasAssociatedContext(window)
            || state(nullptr, nullptr, context) != STATE_CANCELLING
            || composing(nullptr, nullptr, context) != 0) {
        return fail("beginCancel did not cancel and detach");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) != FALSE) {
        return fail("beginCancel left the saved IME context open during gameplay");
    }
    SendMessageW(window, WM_IME_STARTCOMPOSITION, 0, 0);
    if (composing(nullptr, nullptr, context) != 0) {
        return fail("CANCELLING accepted a late composition message");
    }
    if (poll(nullptr, nullptr, context) != nullptr) {
        return fail("CANCELLING retained a late committed result");
    }
    if (finishCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || state(nullptr, nullptr, context) != STATE_DETACHED) {
        return fail("finishCancel did not establish DETACHED state");
    }
    SendMessageW(window, WM_SETFOCUS, 0, 0);
    SendMessageW(window, WM_INPUTLANGCHANGE, 0,
                 reinterpret_cast<LPARAM>(GetKeyboardLayout(0)));
    if (hasAssociatedContext(window) || state(nullptr, nullptr, context) != STATE_DETACHED) {
        return fail("focus/language lifecycle message re-associated HIMC while detached");
    }

    // Windows/TSF may restore the thread's default HIMC as part of activating the window,
    // before delivering WM_IME_SETCONTEXT.  DETACHED is a persistent gameplay invariant,
    // not merely a postcondition of nativeAttach/nativeFinishCancel.
    ImmAssociateContext(window, initialContext);
    if (!hasAssociatedContext(window)) {
        return fail("could not simulate OS input-context reassociation");
    }
    SendMessageW(window, WM_IME_SETCONTEXT, TRUE, ISC_SHOWUIALL);
    if (hasAssociatedContext(window) || state(nullptr, nullptr, context) != STATE_DETACHED) {
        return fail("WM_IME_SETCONTEXT left a system-reassociated HIMC attached");
    }
    if (enable(nullptr, nullptr, context) != RESULT_SUCCESS) {
        return fail("could not re-enable after WM_IME_SETCONTEXT lifecycle recovery");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) == FALSE) {
        return fail("lifecycle recovery forgot the user's saved IME open state");
    }
    if (canControlOpenStatus) {
        ImmSetOpenStatus(initialContext, FALSE);
    }
    if (beginCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || finishCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || enable(nullptr, nullptr, context) != RESULT_SUCCESS) {
        return fail("could not cycle IME after user changed its open state");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) != FALSE) {
        return fail("enable overrode the user's switch to direct-input mode");
    }
    if (beginCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || finishCancel(nullptr, nullptr, context) != RESULT_SUCCESS) {
        return fail("could not return to DETACHED after open-state preservation test");
    }

    // 再模拟玩家在文本框内切回中文模式，后续窗口替换必须移交这一最新选择。
    if (enable(nullptr, nullptr, context) != RESULT_SUCCESS) {
        return fail("could not enable before replacement-state test");
    }
    if (canControlOpenStatus && ImmSetOpenStatus(initialContext, TRUE) == FALSE) {
        return fail("could not switch IME open for replacement-state test");
    }
    if (beginCancel(nullptr, nullptr, context) != RESULT_SUCCESS
            || finishCancel(nullptr, nullptr, context) != RESULT_SUCCESS) {
        return fail("could not capture latest IME state before replacement");
    }

    // 新旧 HWND 短暂重叠时，退役旧上下文必须恢复用户状态，并允许新窗口立即接管。
    if (retire(nullptr, nullptr, context) != RESULT_SUCCESS
            || state(nullptr, nullptr, context) != STATE_RETIRED) {
        return fail("retire did not establish RETIRED state");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) == FALSE) {
        return fail("retire did not restore the user's IME open state");
    }
    HWND replacement = CreateWindowExW(0, className, L"ssime replacement", WS_OVERLAPPED,
                                       0, 0, 320, 200, nullptr, nullptr,
                                       windowClass.hInstance, nullptr);
    if (replacement == nullptr) {
        return fail("replacement window creation failed");
    }
    ImmAssociateContext(replacement, initialContext);
    std::int64_t replacementContext = attach(nullptr, nullptr,
            static_cast<std::int64_t>(reinterpret_cast<std::uintptr_t>(replacement)));
    if (replacementContext == 0 || hasAssociatedContext(replacement)
            || state(nullptr, nullptr, replacementContext) != STATE_DETACHED) {
        return fail("replacement window did not attach in DETACHED state");
    }

    DestroyWindow(window);
    if (state(nullptr, nullptr, context) != STATE_WINDOW_GONE) {
        return fail("WM_NCDESTROY did not establish WINDOW_GONE state");
    }
    if (enable(nullptr, nullptr, replacementContext) != RESULT_SUCCESS) {
        return fail("replacement window could not restore text input");
    }
    if (canControlOpenStatus && ImmGetOpenStatus(initialContext) == FALSE) {
        return fail("replacement window lost the user's saved IME open state");
    }
    if (beginCancel(nullptr, nullptr, replacementContext) != RESULT_SUCCESS
            || finishCancel(nullptr, nullptr, replacementContext) != RESULT_SUCCESS) {
        return fail("replacement window could not return to gameplay state");
    }
    DestroyWindow(replacement);

    // debug=false 下合法键盘消息不应写入逐键诊断；预期失败 attach 的错误已在 init 清空。
    FILE* log = std::fopen("starsector_ime_native.log", "rb");
    if (log != nullptr) {
        std::fseek(log, 0, SEEK_END);
        long size = std::ftell(log);
        std::fclose(log);
        if (size != 0) {
            return fail("debug=false unexpectedly produced native diagnostics");
        }
    }
    if (createdContext != nullptr) {
        ImmDestroyContext(createdContext);
    }
    UnregisterClassW(className, windowClass.hInstance);
    FreeLibrary(module);
    std::puts("ssime smoke test passed");
    return 0;
}

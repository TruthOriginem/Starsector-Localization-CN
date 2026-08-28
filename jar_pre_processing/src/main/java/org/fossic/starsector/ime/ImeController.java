package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

import java.lang.ref.WeakReference;

/**
 * 输入法支持的核心协调器（单例）。
 *
 * <p>职责：
 * <ul>
 *   <li>延迟初始化：首次被调用时反射获取 LWJGL 窗口句柄（HWND）并接管窗口过程；</li>
 *   <li>焦点跟踪：每帧根据文本框 {@code hasFocus()} 收敛输入法启用状态；</li>
 *   <li>文本注入：轮询原生上屏队列，逐字符写入当前聚焦的文本框；</li>
 *   <li>候选窗定位：把系统候选/组合窗定位到输入框光标处。</li>
 * </ul>
 *
 * <p>本类仅由游戏主线程（UI 输入处理）调用，原生 WndProc 亦在同线程被消息泵触发，
 * 因此无需复杂同步。所有对外入口在 {@link ImeHooks} 中以异常隔离方式调用。
 */
final class ImeController {
    private static final int MAX_CLEANUP_ATTEMPTS_PER_PHASE = 4;

    enum InitState {
        UNINITIALIZED,
        ATTACHED,
        RETRY_WAIT,
        PERMANENT_FAILURE,
        UNSAFE_CLEANUP
    }

    enum InputState {
        NONE,
        ACTIVE,
        SUSPENDED,
        CANCELLING
    }

    private enum CleanupPhase {
        NONE,
        BEGIN_CANCEL,
        FINISH_CANCEL
    }

    private static final ImeController INSTANCE = new ImeController(
            new SystemImeNativeFacade(), new LwjglHwndResolver(), ImeLog::error,
            new GameImeSpotResolver());

    private final ImeNativeFacade nativeFacade;
    private final HwndResolver hwndResolver;
    private final ImeLogSink log;
    private final ImeSpotResolver spotResolver;

    private volatile InitState initState = InitState.UNINITIALIZED;
    private volatile long ctx;
    private long attachedHwnd;

    private final WeakIdentityRegistry<TextFieldAPI> registeredFields =
            new WeakIdentityRegistry<>();
    private WeakReference<TextFieldAPI> focusedField;
    private WeakReference<TextFieldAPI> requestedNextField;
    private InputState inputState = InputState.NONE;
    private long frameId;
    private long cancellationFrame = -1L;
    private boolean suspendAfterCancel;
    private CleanupPhase cleanupPhase = CleanupPhase.NONE;
    private int cleanupBeginAttempts;
    private int cleanupFinishAttempts;
    private boolean cleanupExhaustionLogged;
    private volatile Thread ownerThread;
    private volatile boolean wrongThreadLogged;

    // 候选窗定位去重：坐标未变化时跳过原生调用（每帧 4+ 次系统调用）与日志
    private int lastSpotX = Integer.MIN_VALUE;
    private int lastSpotY = Integer.MIN_VALUE;
    private int lastSpotHeight = Integer.MIN_VALUE;
    private final WeakIdentityRegistry<TextFieldAPI> brokenSpotFields =
            new WeakIdentityRegistry<>();
    private boolean nativeSpotBroken;

    ImeController(ImeNativeFacade nativeFacade, HwndResolver hwndResolver, ImeLogSink log) {
        this(nativeFacade, hwndResolver, log, new GameImeSpotResolver());
    }

    ImeController(ImeNativeFacade nativeFacade, HwndResolver hwndResolver, ImeLogSink log,
                  ImeSpotResolver spotResolver) {
        if (nativeFacade == null || hwndResolver == null || log == null || spotResolver == null) {
            throw new IllegalArgumentException("IME dependencies must not be null");
        }
        this.nativeFacade = nativeFacade;
        this.hwndResolver = hwndResolver;
        this.log = log;
        this.spotResolver = spotResolver;
    }

    static ImeController get() {
        return INSTANCE;
    }

    /** 全局输入帧入口；负责初始化并推进跨帧取消屏障。 */
    void onGlobalInputFrame(Object focusOwner) {
        if (!acceptGlobalFrameThread()) {
            return;
        }
        frameId++;
        if (initState == InitState.UNSAFE_CLEANUP) {
            advanceUnsafeCleanup();
            return;
        }
        if (!ensureAttached(true)) {
            return;
        }
        if (inputState == InputState.CANCELLING && frameId > cancellationFrame) {
            ImeNativeFacade.TransitionResult result = nativeFacade.finishCancel(ctx);
            handleNormalFinishResult(result);
            if (initState != InitState.ATTACHED) {
                return;
            }
        }
        convergeGlobalFocus(focusOwner);
    }

    /** Hook 熔断后仍由全局帧调用；只推进原生解绑，不再接触游戏 UI 对象。 */
    void onEmergencyCleanupFrame() {
        if (!acceptGlobalFrameThread()) {
            return;
        }
        frameId++;
        if (initState == InitState.UNSAFE_CLEANUP) {
            advanceUnsafeCleanup();
        } else if (ctx != 0L && initState == InitState.ATTACHED) {
            disableAfterFailure();
        }
    }

    /** 焦点栈底层修改完成后的即时通知；不推进跨帧取消屏障。 */
    void onGlobalFocusChanged(Object focusOwner) {
        if (!acceptCurrentThread() || !ensureAttached(false)) {
            return;
        }
        convergeGlobalFocus(focusOwner);
    }

    InitState initStateForTest() {
        return initState;
    }

    long contextForTest() {
        return ctx;
    }

    boolean isAttachedForTest() {
        return initState == InitState.ATTACHED;
    }

    boolean isRegisteredForTest(TextFieldAPI field) {
        return registeredFields.contains(field);
    }

    InputState inputStateForTest() {
        return inputState;
    }

    TextFieldAPI inputOwnerForTest() {
        return focusedField == null ? null : focusedField.get();
    }

    /** 每帧对每个文本框调用（注入点：ui.new.processInputImpl 开头）。 */
    void onProcessInput(TextFieldAPI field) {
        if (field == null || !acceptCurrentThread()) {
            return;
        }
        registeredFields.add(field);
        if (!ensureAttached(false)) {
            return;
        }

        boolean hasFocus = field.hasFocus();
        TextFieldAPI current = focusedField != null ? focusedField.get() : null;

        if (hasFocus) {
            if (current != field) {
                activateIfEligible(field);
            }
            if (inputState == InputState.ACTIVE && inputOwnerForTest() == field) {
                drainCommittedText(field);
                updateSpot(field);
            }
        } else if (current == field || (current == null && focusedField != null)) {
            beginCancellation(current, null, true);
        }
    }

    /** {@code releaseFocus} 正常返回后调用，覆盖文本框同帧关闭、此后不再 advance 的情况。 */
    void onFocusReleased(TextFieldAPI field) {
        if (!acceptCurrentThread() || initState != InitState.ATTACHED || field == null) {
            return;
        }
        TextFieldAPI current = focusedField != null ? focusedField.get() : null;
        if (current == null || current == field) {
            if (inputState == InputState.CANCELLING) {
                suspendAfterCancel = false;
            } else {
                beginCancellation(current, null, false);
            }
        }
    }

    /** 钩子发生不可恢复错误时尽力解除 IME，随后彻底停用本模块。 */
    void disableAfterFailure() {
        requestedNextField = null;
        suspendAfterCancel = false;
        resetSpot();
        if (ctx == 0L) {
            focusedField = null;
            inputState = InputState.NONE;
            cleanupPhase = CleanupPhase.NONE;
            initState = InitState.PERMANENT_FAILURE;
            attachedHwnd = 0L;
            return;
        }

        inputState = InputState.CANCELLING;
        try {
            ImeNativeFacade.TransitionResult result = nativeFacade.beginCancel(ctx);
            if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS) {
                cleanupPhase = CleanupPhase.FINISH_CANCEL;
                cleanupBeginAttempts = 0;
                cleanupFinishAttempts = 0;
                cancellationFrame = frameId;
                initState = InitState.UNSAFE_CLEANUP;
            } else if (result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
                finishAfterWindowGone();
            } else {
                enterUnsafeCleanup(result.message(), CleanupPhase.BEGIN_CANCEL, 1);
            }
        } catch (Throwable cleanupFailure) {
            // 原始错误可能正是 JNI 故障；保持 Hook 活着，仅用于有限次数紧急清理。
            enterUnsafeCleanup("输入法异常后的解绑调用失败",
                    CleanupPhase.BEGIN_CANCEL, 1);
        }
    }

    private void beginCancellation(TextFieldAPI previous, TextFieldAPI requestedNext,
                                   boolean suspendPrevious) {
        if (inputState != InputState.ACTIVE) {
            return;
        }
        if (previous != null) {
            drainCommittedText(previous);
        }
        ImeNativeFacade.TransitionResult result = nativeFacade.beginCancel(ctx);
        if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS) {
            requestedNextField = requestedNext == null ? null : new WeakReference<>(requestedNext);
            suspendAfterCancel = suspendPrevious;
            inputState = InputState.CANCELLING;
            cancellationFrame = frameId;
            resetSpot();
        } else if (result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
            finishAfterWindowGone();
        } else {
            enterUnsafeCleanup(result.message(), CleanupPhase.BEGIN_CANCEL, 1);
        }
    }

    private void enterUnsafeCleanup(String message) {
        enterUnsafeCleanup(message, CleanupPhase.BEGIN_CANCEL, 0);
    }

    private void enterUnsafeCleanup(String message, CleanupPhase phase, int attempts) {
        requestedNextField = null;
        suspendAfterCancel = false;
        inputState = InputState.CANCELLING;
        cleanupPhase = phase;
        cleanupBeginAttempts = phase == CleanupPhase.BEGIN_CANCEL ? attempts : 0;
        cleanupFinishAttempts = phase == CleanupPhase.FINISH_CANCEL ? attempts : 0;
        cleanupExhaustionLogged = false;
        initState = InitState.UNSAFE_CLEANUP;
        resetSpot();
        log.error(message == null || message.isEmpty()
                ? "输入法解绑未确认，进入紧急清理" : message, null);
    }

    private void advanceUnsafeCleanup() {
        if (ctx == 0L) {
            finishAfterWindowGone();
            return;
        }
        if (cleanupPhase == CleanupPhase.BEGIN_CANCEL) {
            if (cleanupBeginAttempts >= MAX_CLEANUP_ATTEMPTS_PER_PHASE) {
                logCleanupExhausted("beginCancel");
                return;
            }
            cleanupBeginAttempts++;
            ImeNativeFacade.TransitionResult result;
            try {
                result = nativeFacade.beginCancel(ctx);
            } catch (Throwable failure) {
                logCleanupExhaustedIfFinal("beginCancel 抛出异常");
                return;
            }
            if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS) {
                cleanupPhase = CleanupPhase.FINISH_CANCEL;
                cleanupFinishAttempts = 0;
                cancellationFrame = frameId;
            } else if (result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
                finishAfterWindowGone();
            } else if (result.status() == ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE
                    || result.status() == ImeNativeFacade.TransitionStatus.WRONG_THREAD) {
                cleanupBeginAttempts = MAX_CLEANUP_ATTEMPTS_PER_PHASE;
                logCleanupExhausted(result.message());
            }
            return;
        }
        if (cleanupPhase == CleanupPhase.FINISH_CANCEL && frameId > cancellationFrame) {
            if (cleanupFinishAttempts >= MAX_CLEANUP_ATTEMPTS_PER_PHASE) {
                logCleanupExhausted("finishCancel");
                return;
            }
            cleanupFinishAttempts++;
            ImeNativeFacade.TransitionResult result;
            try {
                result = nativeFacade.finishCancel(ctx);
            } catch (Throwable failure) {
                logCleanupExhaustedIfFinal("finishCancel 抛出异常");
                return;
            }
            if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS
                    || result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
                focusedField = null;
                requestedNextField = null;
                inputState = InputState.NONE;
                cleanupPhase = CleanupPhase.NONE;
                cleanupBeginAttempts = 0;
                cleanupFinishAttempts = 0;
                cancellationFrame = -1L;
                ctx = 0L;
                attachedHwnd = 0L;
                initState = InitState.PERMANENT_FAILURE;
                resetSpot();
            } else if (result.status() == ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE
                    || result.status() == ImeNativeFacade.TransitionStatus.WRONG_THREAD) {
                cleanupFinishAttempts = MAX_CLEANUP_ATTEMPTS_PER_PHASE;
                logCleanupExhausted(result.message());
            }
        }
    }

    private void logCleanupExhaustedIfFinal(String phase) {
        if (cleanupBeginAttempts >= MAX_CLEANUP_ATTEMPTS_PER_PHASE
                || cleanupFinishAttempts >= MAX_CLEANUP_ATTEMPTS_PER_PHASE) {
            logCleanupExhausted(phase);
        }
    }

    private void logCleanupExhausted(String detail) {
        if (cleanupExhaustionLogged) {
            return;
        }
        cleanupExhaustionLogged = true;
        log.error("输入法紧急解绑已耗尽重试，仍未确认安全状态：" + detail, null);
    }

    private void finishAfterWindowGone() {
        focusedField = null;
        requestedNextField = null;
        inputState = InputState.NONE;
        cleanupPhase = CleanupPhase.NONE;
        cleanupBeginAttempts = 0;
        cleanupFinishAttempts = 0;
        cleanupExhaustionLogged = false;
        suspendAfterCancel = false;
        cancellationFrame = -1L;
        ctx = 0L;
        attachedHwnd = 0L;
        initState = InitState.RETRY_WAIT;
        resetSpot();
    }

    private void resetSpot() {
        lastSpotX = Integer.MIN_VALUE;
        lastSpotY = Integer.MIN_VALUE;
        lastSpotHeight = Integer.MIN_VALUE;
    }

    private void handleNormalFinishResult(ImeNativeFacade.TransitionResult result) {
        switch (result.status()) {
            case SUCCESS -> completeNormalCancellation();
            case WINDOW_GONE -> finishAfterWindowGone();
            case RETRYABLE_FAILURE -> enterUnsafeCleanup(
                    result.message(), CleanupPhase.FINISH_CANCEL, 1);
            // finish 已经判定当前 native 状态不能沿原路径完成；重新从允许修复
            // FAILED 状态的 beginCancel 开始，而不是继续调用必然失败的 finishCancel。
            case WRONG_THREAD, PERMANENT_FAILURE -> enterUnsafeCleanup(
                    result.message(), CleanupPhase.BEGIN_CANCEL, 0);
        }
    }

    private void completeNormalCancellation() {
        TextFieldAPI previous = inputOwnerForTest();
        TextFieldAPI requestedNext = requestedNextField == null
                ? null : requestedNextField.get();
        requestedNextField = null;
        cancellationFrame = -1L;
        boolean keepSuspended = suspendAfterCancel && previous != null;
        suspendAfterCancel = false;
        resetSpot();
        if (requestedNext != null
                && registeredFields.contains(requestedNext)
                && requestedNext.hasFocus()) {
            focusedField = null;
            inputState = InputState.NONE;
            activateIfEligible(requestedNext);
        } else if (keepSuspended) {
            inputState = InputState.SUSPENDED;
        } else {
            focusedField = null;
            inputState = InputState.NONE;
        }
    }

    private synchronized boolean ensureAttached(boolean auditWindow) {
        if (initState == InitState.ATTACHED) {
            if (!auditWindow) {
                return true;
            }
            HwndResolver.Resolution currentResolution = hwndResolver.resolve();
            if (currentResolution.status() == HwndResolver.Status.PERMANENT_FAILURE) {
                failPermanently(currentResolution.message());
                return false;
            }
            if (currentResolution.status() == HwndResolver.Status.RETRY_LATER) {
                ImeNativeFacade.NativeState state = nativeFacade.state(ctx);
                if (state == ImeNativeFacade.NativeState.WINDOW_GONE) {
                    prepareForWindowRetry();
                    return false;
                }
                if (state == ImeNativeFacade.NativeState.FAILED) {
                    enterUnsafeCleanup("ssime 原生状态已失败");
                    return false;
                }
                return true;
            }
            ImeNativeFacade.NativeState state = nativeFacade.state(ctx);
            if (currentResolution.hwnd() == attachedHwnd
                    && state != ImeNativeFacade.NativeState.WINDOW_GONE
                    && state != ImeNativeFacade.NativeState.RETIRED) {
                if (state == ImeNativeFacade.NativeState.FAILED) {
                    enterUnsafeCleanup("ssime 原生状态已失败");
                    return false;
                }
                return true;
            }
            if (state == ImeNativeFacade.NativeState.WINDOW_GONE) {
                prepareForWindowRetry();
                return attachResolvedWindow(currentResolution);
            }
            if (state == ImeNativeFacade.NativeState.RETIRED) {
                prepareForWindowRetry();
                return attachResolvedWindow(currentResolution);
            }
            return retireAndAttach(currentResolution);
        }
        if (initState == InitState.PERMANENT_FAILURE
                || initState == InitState.UNSAFE_CLEANUP) {
            return false;
        }

        if (!nativeFacade.isLoaded()) {
            failPermanently("ssime 原生库不可用");
            return false;
        }

        HwndResolver.Resolution resolution = hwndResolver.resolve();
        switch (resolution.status()) {
            case RETRY_LATER -> {
                initState = InitState.RETRY_WAIT;
                return false;
            }
            case PERMANENT_FAILURE -> {
                failPermanently(resolution.message());
                return false;
            }
            case READY -> {
                return attachResolvedWindow(resolution);
            }
            default -> throw new IllegalStateException("unknown HWND resolution status");
        }
    }

    private boolean retireAndAttach(HwndResolver.Resolution resolution) {
        ImeNativeFacade.TransitionResult result = nativeFacade.retire(ctx);
        if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS
                || result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
            prepareForWindowRetry();
            return attachResolvedWindow(resolution);
        }
        enterUnsafeCleanup(result.message());
        return false;
    }

    private boolean attachResolvedWindow(HwndResolver.Resolution resolution) {
        ImeNativeFacade.AttachResult result = nativeFacade.attach(resolution.hwnd());
        if (result.status() == ImeNativeFacade.AttachStatus.SUCCESS) {
            ctx = result.context();
            attachedHwnd = resolution.hwnd();
            ownerThread = Thread.currentThread();
            initState = InitState.ATTACHED;
            return true;
        }
        if (result.status() == ImeNativeFacade.AttachStatus.RETRYABLE_FAILURE) {
            initState = InitState.RETRY_WAIT;
            return false;
        }
        failPermanently(result.message());
        return false;
    }

    private void prepareForWindowRetry() {
        focusedField = null;
        requestedNextField = null;
        inputState = InputState.NONE;
        cleanupPhase = CleanupPhase.NONE;
        cleanupBeginAttempts = 0;
        cleanupFinishAttempts = 0;
        cleanupExhaustionLogged = false;
        suspendAfterCancel = false;
        cancellationFrame = -1L;
        ctx = 0L;
        attachedHwnd = 0L;
        initState = InitState.RETRY_WAIT;
        resetSpot();
    }

    /**
     * 文本框显式取得焦点后的入口。未经过 {@link #onProcessInput(TextFieldAPI)} 验证的实例
     * 一律忽略，避免只继承焦点方法、却没有消费中文上屏队列的 mod 控件误开启 IME。
     *
     * <p>由文本框 {@code grabFocus(boolean)} 正常出口的 ASM Hook 调用。
     */
    void onTextFieldFocusGained(TextFieldAPI field) {
        if (!acceptCurrentThread() || field == null || !registeredFields.contains(field)) {
            return;
        }
        if (!field.hasFocus() || !ensureAttached(false)) {
            return;
        }
        activateIfEligible(field);
    }

    private void activateIfEligible(TextFieldAPI field) {
        TextFieldAPI current = inputOwnerForTest();
        if (inputState == InputState.ACTIVE && current == field) {
            return;
        }
        if (inputState == InputState.ACTIVE) {
            beginCancellation(current, field, false);
            return;
        }
        if (inputState == InputState.SUSPENDED && current == field) {
            focusedField = null;
            inputState = InputState.NONE;
        }
        if (inputState != InputState.NONE || current != null) {
            return;
        }

        updateSpot(field);
        ImeNativeFacade.TransitionResult result = nativeFacade.enable(ctx);
        if (result.status() == ImeNativeFacade.TransitionStatus.SUCCESS) {
            focusedField = new WeakReference<>(field);
            inputState = InputState.ACTIVE;
        } else if (result.status() == ImeNativeFacade.TransitionStatus.WINDOW_GONE) {
            prepareForWindowRetry();
        } else if (result.status() == ImeNativeFacade.TransitionStatus.WRONG_THREAD) {
            failPermanently(result.message());
        } else if (result.status() == ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE) {
            enterUnsafeCleanup(result.message());
        }
    }

    private void convergeGlobalFocus(Object focusOwner) {
        TextFieldAPI verifiedFocus = focusOwner instanceof TextFieldAPI field
                && registeredFields.contains(field) ? field : null;
        TextFieldAPI current = inputOwnerForTest();

        if (inputState == InputState.ACTIVE && (current == null || current != focusOwner)) {
            beginCancellation(current, verifiedFocus, verifiedFocus == null);
            return;
        }
        if (inputState == InputState.SUSPENDED && verifiedFocus != null
                && verifiedFocus.hasFocus()) {
            focusedField = null;
            inputState = InputState.NONE;
            activateIfEligible(verifiedFocus);
            return;
        }
        if (inputState == InputState.NONE && verifiedFocus != null
                && verifiedFocus.hasFocus()) {
            activateIfEligible(verifiedFocus);
        }
    }

    private void failPermanently(String message) {
        if (ctx != 0L) {
            enterUnsafeCleanup(message);
            return;
        }
        ctx = 0L;
        attachedHwnd = 0L;
        initState = InitState.PERMANENT_FAILURE;
        log.error(message == null || message.isEmpty() ? "输入法初始化失败" : message, null);
    }

    private boolean acceptCurrentThread() {
        Thread expected = ownerThread;
        if (expected == null || expected == Thread.currentThread()) {
            return true;
        }
        if (!wrongThreadLogged) {
            wrongThreadLogged = true;
            log.error("输入法 Hook 被非游戏窗口线程调用，已忽略该次调用", null);
        }
        return false;
    }

    /**
     * 启动器窗口和游戏窗口可能分别由不同 Java/Win32 线程持有。只有全局帧入口允许
     * 在线程改变且 HWND 已切换时移交 owner；普通文本框 Hook 永远不能自行夺取状态机。
     *
     * <p>旧上下文必须已经处于 gameplay 安全态，或者旧窗口已经消失/退役。此时不从
     * 新线程调用旧窗口的 IMM API，只遗忘 Java 句柄；旧 WndProc 会在其窗口销毁时恢复
     * 保存的用户状态并由进程最终回收上下文。
     */
    private boolean acceptGlobalFrameThread() {
        Thread expected = ownerThread;
        Thread current = Thread.currentThread();
        if (expected == null || expected == current) {
            return true;
        }
        if (transferToReplacementWindowThread(current)) {
            return true;
        }
        if (!wrongThreadLogged) {
            wrongThreadLogged = true;
            log.error("输入法全局 Hook 来自非窗口线程，且未发现可安全接管的新窗口，已忽略",
                    null);
        }
        return false;
    }

    private synchronized boolean transferToReplacementWindowThread(Thread current) {
        if (ownerThread == null || ownerThread == current) {
            return true;
        }

        HwndResolver.Resolution resolution;
        ImeNativeFacade.NativeState nativeState;
        try {
            resolution = hwndResolver.resolve();
            if (resolution.status() != HwndResolver.Status.READY) {
                return false;
            }
            nativeState = ctx == 0L
                    ? ImeNativeFacade.NativeState.WINDOW_GONE
                    : nativeFacade.state(ctx);
        } catch (Throwable failure) {
            return false;
        }

        boolean replacementWindow = resolution.hwnd() != attachedHwnd
                || nativeState == ImeNativeFacade.NativeState.WINDOW_GONE
                || nativeState == ImeNativeFacade.NativeState.RETIRED;
        boolean oldContextSafe = ctx == 0L
                || nativeState == ImeNativeFacade.NativeState.DETACHED
                || nativeState == ImeNativeFacade.NativeState.WINDOW_GONE
                || nativeState == ImeNativeFacade.NativeState.RETIRED;
        if (!replacementWindow || !oldContextSafe) {
            return false;
        }

        prepareForWindowRetry();
        ownerThread = current;
        wrongThreadLogged = false;
        return true;
    }

    private void drainCommittedText(TextFieldAPI field) {
        String text;
        while ((text = nativeFacade.poll(ctx)) != null) {
            if (text.isEmpty()) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20) {
                    continue;
                }
                // appendCharIfPossible 返回 false 表示输入框拒绝该字符（超出长度/宽度
                // 限制或字体无字形），尊重游戏自身的约束与反馈（提示音），不强行写入。
                field.appendCharIfPossible(c);
            }
        }
    }

    /**
     * 把候选/组合窗定位到输入框光标处。GL 坐标（原点左下）转换为窗口坐标（原点左上）。
     *
     * <p>定位基于文本 label（{@code getTextLabelAPI()}）自身的 position，而非外层文本框的
     * position。文本 label 经 {@code autoSize} 后其 position 即文本的实际渲染框，位置由布局
     * 系统按对齐方式（左对齐 {@code inLMid} / 居中 {@code inMid}）设置，因此其右边缘即光标
     * 位置——对左对齐与居中对齐（如舰船命名框）均正确。用外层文本框的 position 会假设
     * 左对齐，导致居中框错位。
     */
    private void updateSpot(TextFieldAPI field) {
        if (nativeSpotBroken || brokenSpotFields.contains(field)) {
            return;
        }
        ImeSpot spot;
        try {
            spot = spotResolver.resolve(field);
            if (spot == null) {
                return;
            }
        } catch (Throwable t) {
            brokenSpotFields.add(field);
            log.error("计算候选窗位置失败，已只停用当前文本框的定位", t);
            return;
        }

        // 坐标未变化时跳过原生调用（否则每帧产生 4+ 次 Imm* 系统调用）。
        if (spot.x() == lastSpotX
                && spot.y() == lastSpotY
                && spot.height() == lastSpotHeight) {
            return;
        }
        try {
            nativeFacade.setSpot(ctx, spot.x(), spot.y(), spot.height());
            lastSpotX = spot.x();
            lastSpotY = spot.y();
            lastSpotHeight = spot.height();
        } catch (Throwable t) {
            nativeSpotBroken = true;
            log.error("原生候选窗定位失败，已停用本次会话的定位功能", t);
        }
    }
}

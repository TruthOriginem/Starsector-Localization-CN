package org.fossic.starsector.ime;

/** 生产环境 JNI facade。 */
final class SystemImeNativeFacade implements ImeNativeFacade {
    @Override
    public boolean isLoaded() {
        return ImeNatives.isLoaded();
    }

    @Override
    public AttachResult attach(long hwnd) {
        long context = ImeNatives.nativeAttach(hwnd);
        if (context != 0L) {
            return AttachResult.success(context);
        }
        String error = ImeNatives.nativeLastError();
        String message = error == null || error.isEmpty() ? "接管窗口过程失败" : error;
        return mapAttachFailure(ImeNatives.nativeLastAttachStatus(), message);
    }

    @Override
    public TransitionResult enable(long context) {
        return transition(ImeNatives.nativeEnable(context));
    }

    @Override
    public TransitionResult beginCancel(long context) {
        return transition(ImeNatives.nativeBeginCancel(context));
    }

    @Override
    public TransitionResult finishCancel(long context) {
        return transition(ImeNatives.nativeFinishCancel(context));
    }

    @Override
    public TransitionResult retire(long context) {
        return transition(ImeNatives.nativeRetire(context));
    }

    @Override
    public NativeState state(long context) {
        return mapNativeState(ImeNatives.nativeState(context));
    }

    @Override
    public void setSpot(long context, int x, int y, int height) {
        ImeNatives.nativeSetSpot(context, x, y, height);
    }

    @Override
    public String poll(long context) {
        return ImeNatives.nativePoll(context);
    }

    static TransitionResult mapTransitionResult(int code, String message) {
        TransitionStatus status = switch (code) {
            case 0 -> TransitionStatus.SUCCESS;
            case 1 -> TransitionStatus.WINDOW_GONE;
            case 2 -> TransitionStatus.WRONG_THREAD;
            case 3 -> TransitionStatus.RETRYABLE_FAILURE;
            case 4 -> TransitionStatus.PERMANENT_FAILURE;
            default -> TransitionStatus.PERMANENT_FAILURE;
        };
        return new TransitionResult(status, status == TransitionStatus.SUCCESS ? null : message);
    }

    static AttachResult mapAttachFailure(int code, String message) {
        return code == 1 ? AttachResult.retryableFailure(message)
                : AttachResult.permanentFailure(message);
    }

    static NativeState mapNativeState(int code) {
        return switch (code) {
            case 0 -> NativeState.DETACHED;
            case 1 -> NativeState.ENABLING;
            case 2 -> NativeState.ENABLED;
            case 3 -> NativeState.CANCELLING;
            case 4 -> NativeState.WINDOW_GONE;
            case 5 -> NativeState.FAILED;
            case 6 -> NativeState.RETIRED;
            default -> NativeState.FAILED;
        };
    }

    private static TransitionResult transition(int code) {
        return mapTransitionResult(code, code == 0 ? null : lastError());
    }

    private static String lastError() {
        String message = ImeNatives.nativeLastError();
        return message == null || message.isEmpty() ? "ssime 状态转换失败" : message;
    }
}

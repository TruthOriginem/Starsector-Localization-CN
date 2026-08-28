package org.fossic.starsector.ime;

/** JNI 调用的可测试适配层；后续 native 状态码扩展集中在这里。 */
interface ImeNativeFacade {
    boolean isLoaded();

    AttachResult attach(long hwnd);

    TransitionResult enable(long context);

    TransitionResult beginCancel(long context);

    TransitionResult finishCancel(long context);

    /**
     * 当前显示窗口被另一个 HWND 取代时退役旧上下文。生产实现还会把用户原有的
     * IME 开关状态交还给线程默认输入上下文；默认实现供测试 fake 使用。
     */
    default TransitionResult retire(long context) {
        TransitionResult begin = beginCancel(context);
        return begin.status() == TransitionStatus.SUCCESS ? finishCancel(context) : begin;
    }

    default NativeState state(long context) {
        return NativeState.DETACHED;
    }

    default void setSpot(long context, int x, int y, int height) {
        throw new UnsupportedOperationException("setSpot is not implemented");
    }

    default String poll(long context) {
        throw new UnsupportedOperationException("poll is not implemented");
    }

    enum AttachStatus {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    enum TransitionStatus {
        SUCCESS,
        WINDOW_GONE,
        WRONG_THREAD,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    enum NativeState {
        DETACHED,
        ENABLING,
        ENABLED,
        CANCELLING,
        WINDOW_GONE,
        FAILED,
        RETIRED
    }

    record TransitionResult(TransitionStatus status, String message) {
        public TransitionResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
        }

        static TransitionResult success() {
            return new TransitionResult(TransitionStatus.SUCCESS, null);
        }
    }

    record AttachResult(AttachStatus status, long context, String message) {
        public AttachResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if (status == AttachStatus.SUCCESS && context == 0L) {
                throw new IllegalArgumentException("SUCCESS requires a non-zero context");
            }
            if (status != AttachStatus.SUCCESS && context != 0L) {
                throw new IllegalArgumentException("failure must not carry a context");
            }
        }

        static AttachResult success(long context) {
            return new AttachResult(AttachStatus.SUCCESS, context, null);
        }

        static AttachResult retryableFailure(String message) {
            return new AttachResult(AttachStatus.RETRYABLE_FAILURE, 0L, message);
        }

        static AttachResult permanentFailure(String message) {
            return new AttachResult(AttachStatus.PERMANENT_FAILURE, 0L, message);
        }
    }
}

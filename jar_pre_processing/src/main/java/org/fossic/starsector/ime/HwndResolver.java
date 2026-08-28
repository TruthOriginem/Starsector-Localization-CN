package org.fossic.starsector.ime;

/** 解析 LWJGL 游戏窗口句柄，并区分暂时未就绪与永久不兼容。 */
interface HwndResolver {
    Resolution resolve();

    enum Status {
        READY,
        RETRY_LATER,
        PERMANENT_FAILURE
    }

    record Resolution(Status status, long hwnd, String message) {
        public Resolution {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if (status == Status.READY && hwnd == 0L) {
                throw new IllegalArgumentException("READY requires a non-zero HWND");
            }
            if (status != Status.READY && hwnd != 0L) {
                throw new IllegalArgumentException("non-READY result must not carry an HWND");
            }
        }

        static Resolution ready(long hwnd) {
            return new Resolution(Status.READY, hwnd, null);
        }

        static Resolution retryLater() {
            return new Resolution(Status.RETRY_LATER, 0L, null);
        }

        static Resolution permanentFailure(String message) {
            return new Resolution(Status.PERMANENT_FAILURE, 0L, message);
        }
    }
}

package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.AtomicLong;

/** 图片预读精确路径去重计数。 */
public final class PreloadPathDedupDiagnostics {
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong DEDUPLICATED = new AtomicLong();

    private PreloadPathDedupDiagnostics() {
    }

    static void record(boolean accepted) {
        REQUESTS.incrementAndGet();
        if (accepted) {
            ACCEPTED.incrementAndGet();
        } else {
            DEDUPLICATED.incrementAndGet();
        }
    }

    public static String json() {
        return "{\"requests\":" + REQUESTS.get()
                + ",\"accepted\":" + ACCEPTED.get()
                + ",\"deduplicated\":" + DEDUPLICATED.get()
                + "}";
    }

    static void resetForTests() {
        REQUESTS.set(0L);
        ACCEPTED.set(0L);
        DEDUPLICATED.set(0L);
    }
}

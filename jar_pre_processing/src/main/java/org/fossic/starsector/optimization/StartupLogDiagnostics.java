package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.LongAdder;

/** 记录 GUI 启动时控制台日志优化的低成本诊断信息。 */
public final class StartupLogDiagnostics {
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder DETACHED = new LongAdder();
    private static final LongAdder KEPT_FOR_CONSOLE = new LongAdder();
    private static final LongAdder KEPT_BY_PROPERTY = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private StartupLogDiagnostics() {
    }

    static void recordAttempt() {
        ATTEMPTS.increment();
    }

    static void recordDetached() {
        DETACHED.increment();
    }

    static void recordKeptForConsole() {
        KEPT_FOR_CONSOLE.increment();
    }

    static void recordKeptByProperty() {
        KEPT_BY_PROPERTY.increment();
    }

    static void recordFailure() {
        FAILURES.increment();
    }

    public static String json() {
        return "{"
                + "\"attempts\":" + ATTEMPTS.sum()
                + ",\"detached\":" + DETACHED.sum()
                + ",\"keptForConsole\":" + KEPT_FOR_CONSOLE.sum()
                + ",\"keptByProperty\":" + KEPT_BY_PROPERTY.sum()
                + ",\"failures\":" + FAILURES.sum()
                + "}";
    }

    static void resetForTests() {
        ATTEMPTS.reset();
        DETACHED.reset();
        KEPT_FOR_CONSOLE.reset();
        KEPT_BY_PROPERTY.reset();
        FAILURES.reset();
    }
}

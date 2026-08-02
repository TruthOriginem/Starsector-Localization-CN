package org.fossic.starsector.optimization;

/** 为 ResourceLoaderState 的原版声音解码池选择有界并行度。 */
public final class SoundDecodeWorkerPolicy {
    public static final String WORKER_COUNT_PROPERTY =
            "starsector.optimization.soundDecodeWorkers";

    private static final int DEFAULT_WORKERS = 2;
    private static final int MAX_CONFIGURED_WORKERS = 8;

    private SoundDecodeWorkerPolicy() {
    }

    /** 由 ASM bridge 调用。 */
    public static int workerCount() {
        return workerCount(Runtime.getRuntime().availableProcessors());
    }

    static int workerCount(int ignoredAvailableProcessors) {
        int configured = Integer.getInteger(
                WORKER_COUNT_PROPERTY, DEFAULT_WORKERS);
        return Math.max(
                1,
                Math.min(MAX_CONFIGURED_WORKERS, configured));
    }
}

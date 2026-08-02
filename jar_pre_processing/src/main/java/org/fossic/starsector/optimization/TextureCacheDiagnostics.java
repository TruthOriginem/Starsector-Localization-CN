package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.LongAdder;

/** 低成本累计纹理缓存数据，供启动 profiler 在首帧一次性读取。 */
public final class TextureCacheDiagnostics {
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder STORES = new LongAdder();
    private static final LongAdder CORRUPTIONS = new LongAdder();
    private static final LongAdder RESTORED_BYTES = new LongAdder();
    private static final LongAdder READ_BYTES = new LongAdder();
    private static final LongAdder STORED_RAW_BYTES = new LongAdder();
    private static final LongAdder STORED_FILE_BYTES = new LongAdder();

    private TextureCacheDiagnostics() {
    }

    static void recordHit(long restoredBytes, long fileBytes) {
        HITS.increment();
        RESTORED_BYTES.add(restoredBytes);
        READ_BYTES.add(fileBytes);
    }

    static void recordMiss() {
        MISSES.increment();
    }

    static void recordStore(long rawBytes, long fileBytes) {
        STORES.increment();
        STORED_RAW_BYTES.add(rawBytes);
        STORED_FILE_BYTES.add(fileBytes);
    }

    static void recordCorruption() {
        CORRUPTIONS.increment();
    }

    public static String json() {
        return "{"
                + "\"hits\":" + HITS.sum()
                + ",\"misses\":" + MISSES.sum()
                + ",\"stores\":" + STORES.sum()
                + ",\"corruptions\":" + CORRUPTIONS.sum()
                + ",\"restoredBytes\":" + RESTORED_BYTES.sum()
                + ",\"cacheFileBytesRead\":" + READ_BYTES.sum()
                + ",\"storedRawBytes\":" + STORED_RAW_BYTES.sum()
                + ",\"storedFileBytes\":" + STORED_FILE_BYTES.sum()
                + "}";
    }

    static void resetForTests() {
        HITS.reset();
        MISSES.reset();
        STORES.reset();
        CORRUPTIONS.reset();
        RESTORED_BYTES.reset();
        READ_BYTES.reset();
        STORED_RAW_BYTES.reset();
        STORED_FILE_BYTES.reset();
    }
}

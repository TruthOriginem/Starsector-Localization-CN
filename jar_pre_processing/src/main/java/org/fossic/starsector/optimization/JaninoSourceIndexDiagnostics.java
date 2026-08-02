package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.AtomicLong;

/** Janino 逻辑源码索引的查找、快照和复用计数。 */
public final class JaninoSourceIndexDiagnostics {
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong DELEGATE_LOOKUPS = new AtomicLong();
    private static final AtomicLong POSITIVE = new AtomicLong();
    private static final AtomicLong NEGATIVE = new AtomicLong();
    private static final AtomicLong SOURCE_SNAPSHOTS = new AtomicLong();
    private static final AtomicLong SOURCE_REUSES = new AtomicLong();
    private static final AtomicLong METADATA_SNAPSHOTS = new AtomicLong();
    private static final AtomicLong METADATA_REUSES = new AtomicLong();

    private JaninoSourceIndexDiagnostics() {
    }

    static void recordRequest(boolean cacheHit) {
        REQUESTS.incrementAndGet();
        if (cacheHit) {
            CACHE_HITS.incrementAndGet();
        }
    }

    static void recordLookup(boolean found) {
        DELEGATE_LOOKUPS.incrementAndGet();
        if (found) {
            POSITIVE.incrementAndGet();
        } else {
            NEGATIVE.incrementAndGet();
        }
    }

    static void recordSourceAccess(boolean reused) {
        if (reused) {
            SOURCE_REUSES.incrementAndGet();
        } else {
            SOURCE_SNAPSHOTS.incrementAndGet();
        }
    }

    static void recordMetadataAccess(boolean reused) {
        if (reused) {
            METADATA_REUSES.incrementAndGet();
        } else {
            METADATA_SNAPSHOTS.incrementAndGet();
        }
    }

    public static String json() {
        return "{\"requests\":" + REQUESTS.get()
                + ",\"cacheHits\":" + CACHE_HITS.get()
                + ",\"delegateLookups\":" + DELEGATE_LOOKUPS.get()
                + ",\"positive\":" + POSITIVE.get()
                + ",\"negative\":" + NEGATIVE.get()
                + ",\"sourceSnapshots\":" + SOURCE_SNAPSHOTS.get()
                + ",\"sourceReuses\":" + SOURCE_REUSES.get()
                + ",\"metadataSnapshots\":" + METADATA_SNAPSHOTS.get()
                + ",\"metadataReuses\":" + METADATA_REUSES.get()
                + "}";
    }

    static long requestCountForTests() {
        return REQUESTS.get();
    }

    static void resetForTests() {
        REQUESTS.set(0L);
        CACHE_HITS.set(0L);
        DELEGATE_LOOKUPS.set(0L);
        POSITIVE.set(0L);
        NEGATIVE.set(0L);
        SOURCE_SNAPSHOTS.set(0L);
        SOURCE_REUSES.set(0L);
        METADATA_SNAPSHOTS.set(0L);
        METADATA_REUSES.set(0L);
    }
}

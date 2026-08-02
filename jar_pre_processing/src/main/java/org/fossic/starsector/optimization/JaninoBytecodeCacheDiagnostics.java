package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.AtomicLong;

/** Janino 整代 bytecode 缓存的验证、命中、生成与发布计数。 */
public final class JaninoBytecodeCacheDiagnostics {
    private static final AtomicLong ENVIRONMENT_FAILURES = new AtomicLong();
    private static final AtomicLong PACK_LOADS = new AtomicLong();
    private static final AtomicLong VALID_PACKS = new AtomicLong();
    private static final AtomicLong INVALID_PACKS = new AtomicLong();
    private static final AtomicLong CORRUPTIONS = new AtomicLong();
    private static final AtomicLong SOURCE_VALIDATIONS = new AtomicLong();
    private static final AtomicLong SOURCE_VALIDATION_FAILURES =
            new AtomicLong();
    private static final AtomicLong CLASS_HITS = new AtomicLong();
    private static final AtomicLong CLASS_MISSES = new AtomicLong();
    private static final AtomicLong GENERATED_CLASSES = new AtomicLong();
    private static final AtomicLong PUBLISHED_PACKS = new AtomicLong();
    private static final AtomicLong PUBLISH_FAILURES = new AtomicLong();

    private JaninoBytecodeCacheDiagnostics() {
    }

    static void recordEnvironmentFailure() {
        ENVIRONMENT_FAILURES.incrementAndGet();
    }

    static void recordPackLoad() {
        PACK_LOADS.incrementAndGet();
    }

    static void recordValidPack() {
        VALID_PACKS.incrementAndGet();
    }

    static void recordInvalidPack(boolean corruption) {
        INVALID_PACKS.incrementAndGet();
        if (corruption) {
            CORRUPTIONS.incrementAndGet();
        }
    }

    static void recordSourceValidation(boolean valid) {
        SOURCE_VALIDATIONS.incrementAndGet();
        if (!valid) {
            SOURCE_VALIDATION_FAILURES.incrementAndGet();
        }
    }

    static void recordClassHit() {
        CLASS_HITS.incrementAndGet();
    }

    static void recordClassMiss() {
        CLASS_MISSES.incrementAndGet();
    }

    static void recordGeneratedClasses(int count) {
        GENERATED_CLASSES.addAndGet(count);
    }

    static void recordPublishedPack() {
        PUBLISHED_PACKS.incrementAndGet();
    }

    static void recordPublishFailure() {
        PUBLISH_FAILURES.incrementAndGet();
    }

    public static String json() {
        return "{\"environmentFailures\":" + ENVIRONMENT_FAILURES.get()
                + ",\"packLoads\":" + PACK_LOADS.get()
                + ",\"validPacks\":" + VALID_PACKS.get()
                + ",\"invalidPacks\":" + INVALID_PACKS.get()
                + ",\"corruptions\":" + CORRUPTIONS.get()
                + ",\"sourceValidations\":" + SOURCE_VALIDATIONS.get()
                + ",\"sourceValidationFailures\":"
                + SOURCE_VALIDATION_FAILURES.get()
                + ",\"classHits\":" + CLASS_HITS.get()
                + ",\"classMisses\":" + CLASS_MISSES.get()
                + ",\"generatedClasses\":" + GENERATED_CLASSES.get()
                + ",\"publishedPacks\":" + PUBLISHED_PACKS.get()
                + ",\"publishFailures\":" + PUBLISH_FAILURES.get()
                + "}";
    }

    static void resetForTests() {
        ENVIRONMENT_FAILURES.set(0L);
        PACK_LOADS.set(0L);
        VALID_PACKS.set(0L);
        INVALID_PACKS.set(0L);
        CORRUPTIONS.set(0L);
        SOURCE_VALIDATIONS.set(0L);
        SOURCE_VALIDATION_FAILURES.set(0L);
        CLASS_HITS.set(0L);
        CLASS_MISSES.set(0L);
        GENERATED_CLASSES.set(0L);
        PUBLISHED_PACKS.set(0L);
        PUBLISH_FAILURES.set(0L);
    }
}

package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PersistentCacheMaintenanceTest {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void prepare() {
        PersistentCacheMaintenance.resetForTests();
    }

    @AfterEach
    void reset() {
        PersistentCacheMaintenance.resetForTests();
    }

    @Test
    void cleansOnlyNamespacesRegisteredByEnabledCacheGroups()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path textureRoot = temporaryDirectory
                .resolve("textures").resolve("v1");
        Path pcmRoot = temporaryDirectory.resolve("pcm").resolve("v1");
        Path activeTexture = entry(
                textureRoot, 'a', ".sstexc.zst", now - 90 * DAY_MILLIS);
        Path staleTexture = entry(
                textureRoot, 'b', ".sstexc.zst", now - 90 * DAY_MILLIS);
        Path untouchedPcm = entry(
                pcmRoot, 'c', ".sspcm.zst", now - 90 * DAY_MILLIS);

        PersistentCacheCleaner.Policy texturePolicy = policy(
                "textures", textureRoot, ".sstexc.zst");
        PersistentCacheMaintenance.recordUse(
                texturePolicy, activeTexture);

        PersistentCacheMaintenance.cleanNowForTests(now);

        assertEquals(Set.of("textures"),
                PersistentCacheMaintenance.registeredNamespacesForTests());
        assertTrue(Files.exists(activeTexture));
        assertFalse(Files.exists(staleTexture));
        assertTrue(Files.exists(untouchedPcm));
    }

    @Test
    void registrationWithoutHitStillReclaimsThatCacheNamespace()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("janino").resolve("v1");
        String hash = "d".repeat(64);
        Path stale = root.resolve("pack-" + hash + ".bin");
        Files.createDirectories(root);
        Files.write(stale, new byte[] {1});
        Files.setLastModifiedTime(
                stale, FileTime.fromMillis(now - 90 * DAY_MILLIS));

        PersistentCacheMaintenance.register(new PersistentCacheCleaner.Policy(
                "janino",
                root,
                PersistentCacheCleaner.Layout.FLAT_HASH,
                "pack-",
                ".bin",
                30 * DAY_MILLIS,
                1024,
                8,
                false));

        PersistentCacheMaintenance.cleanNowForTests(now);

        assertFalse(Files.exists(stale));
        assertEquals(1,
                PersistentCacheMaintenance.lastResultsForTests()
                        .get("janino").expiredFilesDeleted());
    }

    @Test
    void registrationDefersMaintenanceUntilStartupCompletes() {
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "3600");
        Path root = temporaryDirectory.resolve("early").resolve("v1");

        PersistentCacheMaintenance.register(
                policy("early", root, ".cache"));

        assertFalse(PersistentCacheMaintenance.scheduledForTests());

        PersistentCacheMaintenance.onStartupComplete();

        assertTrue(PersistentCacheMaintenance.scheduledForTests());
    }

    @Test
    void cacheRegisteredAfterStartupStillSchedulesMaintenance() {
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "3600");
        PersistentCacheMaintenance.onStartupComplete();

        PersistentCacheMaintenance.register(policy(
                "late", temporaryDirectory.resolve("late/v1"), ".cache"));

        assertTrue(PersistentCacheMaintenance.scheduledForTests());
    }

    @Test
    void shutdownStyleSecondCleanupSkipsUnchangedNamespaces()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("once").resolve("v1");
        Path active = entry(
                root, 'a', ".cache", now - DAY_MILLIS);
        PersistentCacheMaintenance.recordUse(
                policy("once", root, ".cache"), active);

        PersistentCacheMaintenance.cleanNowForTests(now);
        PersistentCacheCleaner.Result first =
                PersistentCacheMaintenance.lastResultsForTests().get("once");
        PersistentCacheMaintenance.cleanNowForTests(now + 1);
        PersistentCacheCleaner.Result second =
                PersistentCacheMaintenance.lastResultsForTests().get("once");

        assertSame(first, second);
    }

    @Test
    void lateUseWhileWorkerIsUnschedulingIsNotLost()
            throws IOException, InterruptedException {
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "0");
        long now = System.currentTimeMillis();
        Path root = temporaryDirectory.resolve("late/v1");
        PersistentCacheCleaner.Policy policy =
                policy("late", root, ".cache");
        CountDownLatch firstCleanupFinished = new CountDownLatch(1);
        CountDownLatch allowFirstWorkerToExit = new CountDownLatch(1);
        AtomicInteger workerExits = new AtomicInteger();
        PersistentCacheMaintenance
                .setBeforeWorkerUnscheduleHookForTests(() -> {
                    if (workerExits.incrementAndGet() == 1) {
                        firstCleanupFinished.countDown();
                        awaitUnchecked(allowFirstWorkerToExit);
                    }
                });

        PersistentCacheMaintenance.register(policy);
        PersistentCacheMaintenance.onStartupComplete();
        assertTrue(firstCleanupFinished.await(5, TimeUnit.SECONDS));

        Path active = entry(
                root, 'e', ".cache", now - 90 * DAY_MILLIS);
        Path stale = entry(
                root, 'f', ".cache", now - 90 * DAY_MILLIS);
        PersistentCacheMaintenance.recordUse(policy, active);
        allowFirstWorkerToExit.countDown();

        assertTrue(awaitCondition(() -> !Files.exists(stale)
                && !PersistentCacheMaintenance.scheduledForTests()));
        assertTrue(Files.exists(active));
        assertTrue(workerExits.get() >= 2);
    }

    @Test
    void interruptDuringDelayDoesNotWedgeFutureMaintenance()
            throws IOException, InterruptedException {
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "3600");
        PersistentCacheMaintenance.register(policy(
                "interrupted",
                temporaryDirectory.resolve("interrupted/v1"),
                ".cache"));
        PersistentCacheMaintenance.onStartupComplete();
        assertTrue(PersistentCacheMaintenance.scheduledForTests());

        System.setProperty(
                PersistentCacheMaintenance.DISABLE_PROPERTY, "true");
        assertTrue(PersistentCacheMaintenance
                .interruptMaintenanceWorkerForTests());
        assertTrue(awaitCondition(() ->
                !PersistentCacheMaintenance.scheduledForTests()));

        System.clearProperty(PersistentCacheMaintenance.DISABLE_PROPERTY);
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "0");
        long now = System.currentTimeMillis();
        Path retryRoot = temporaryDirectory.resolve("retry/v1");
        Path stale = entry(
                retryRoot, 'a', ".cache", now - 90 * DAY_MILLIS);
        PersistentCacheMaintenance.register(
                policy("retry", retryRoot, ".cache"));

        assertTrue(awaitCondition(() -> !Files.exists(stale)
                && !PersistentCacheMaintenance.scheduledForTests()));
    }

    @Test
    void traversalWithoutProgressDoesNotCreateABusyRetryRequest()
            throws IOException, InterruptedException {
        System.setProperty(
                PersistentCacheMaintenance.START_DELAY_SECONDS_PROPERTY,
                "0");
        System.setProperty(
                PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY,
                "16");
        long now = System.currentTimeMillis();
        Path root = temporaryDirectory.resolve("bounded/v1");
        PersistentCacheCleaner.Policy policy =
                policy("bounded", root, ".cache");
        for (int index = 0; index < 16; index++) {
            PersistentCacheMaintenance.recordUse(
                    policy,
                    indexedEntry(
                            root,
                            index,
                            ".cache",
                            now - DAY_MILLIS));
        }
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        CountDownLatch allowWorkerToExit = new CountDownLatch(1);
        PersistentCacheMaintenance
                .setBeforeWorkerUnscheduleHookForTests(() -> {
                    cleanupFinished.countDown();
                    awaitUnchecked(allowWorkerToExit);
                });

        PersistentCacheMaintenance.onStartupComplete();
        assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS));

        PersistentCacheCleaner.Result result =
                PersistentCacheMaintenance.lastResultsForTests()
                        .get("bounded");
        assertTrue(result.traversalLimitReached());
        assertFalse(PersistentCacheMaintenance
                .workRequestedForTests());
        allowWorkerToExit.countDown();
        assertTrue(awaitCondition(() ->
                !PersistentCacheMaintenance.scheduledForTests()));
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitCondition(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private PersistentCacheCleaner.Policy policy(
            String namespace, Path root, String suffix) {
        return new PersistentCacheCleaner.Policy(
                namespace,
                root,
                PersistentCacheCleaner.Layout.HASH_SHARDED,
                "",
                suffix,
                30 * DAY_MILLIS,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                false);
    }

    private Path entry(
            Path root, char digit, String suffix, long modified)
            throws IOException {
        String hash = String.valueOf(digit).repeat(64);
        Path path = root.resolve(hash.substring(0, 2))
                .resolve(hash + suffix)
                .toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1});
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        return path;
    }

    private Path indexedEntry(
            Path root, int index, String suffix, long modified)
            throws IOException {
        String hash = String.format("%064x", index);
        Path path = root.resolve(hash.substring(0, 2))
                .resolve(hash + suffix)
                .toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1});
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        return path;
    }
}

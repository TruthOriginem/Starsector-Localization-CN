package org.fossic.starsector.optimization;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ParallelImagePreloaderTest {
    private static final BufferedImage SENTINEL =
            new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR);

    @AfterEach
    void cleanUp() {
        ParallelImagePreloader.stop();
        PreloadResultCoordinator.clear();
        System.clearProperty(ParallelImagePreloader.WORKER_COUNT_PROPERTY);
    }

    @Test
    void decodesDifferentImagesConcurrentlyAndExactlyOnce()
            throws Exception {
        List<String> soundQueue = synchronizedQueue();
        Map<String, byte[]> soundResults = new ConcurrentHashMap<>();
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        Set<String> decoded = ConcurrentHashMap.newKeySet();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int index = 0; index < 8; index++) {
            PreloadResultCoordinator.queueImage(
                    imageQueue, "image-" + index);
        }

        Thread primary = ParallelImagePreloader.startWorkers(
                3,
                soundQueue,
                soundResults,
                new byte[0],
                imageQueue,
                imageResults,
                SENTINEL,
                path -> new byte[0],
                path -> {
                    assertTrue(decoded.add(path), path);
                    int now = active.incrementAndGet();
                    maximumActive.accumulateAndGet(now, Math::max);
                    twoStarted.countDown();
                    assertTrue(release.await(1, SECONDS));
                    active.decrementAndGet();
                    return image(path.hashCode());
                },
                failure -> {
                    throw new AssertionError(failure);
                });

        assertTrue(twoStarted.await(1, SECONDS));
        release.countDown();
        primary.join(2000);
        awaitWorkerCount(0);

        assertFalse(primary.isAlive());
        assertTrue(maximumActive.get() >= 2);
        assertEquals(8, decoded.size());
        assertEquals(8, imageResults.size());
        assertEquals(List.of(), imageQueue);
    }

    @Test
    void primaryWorkerKeepsSoundBeforeImageOrder() throws Exception {
        List<String> soundQueue = synchronizedQueue();
        Map<String, byte[]> soundResults = new ConcurrentHashMap<>();
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        List<String> events = Collections.synchronizedList(
                new ArrayList<>());
        byte[] soundSentinel = new byte[0];
        PreloadResultCoordinator.queueSound(soundQueue, "sound");
        PreloadResultCoordinator.queueImage(imageQueue, "image");

        Thread primary = ParallelImagePreloader.startWorkers(
                1,
                soundQueue,
                soundResults,
                soundSentinel,
                imageQueue,
                imageResults,
                SENTINEL,
                path -> {
                    events.add(path);
                    return new byte[] {1};
                },
                path -> {
                    events.add(path);
                    return image(1);
                },
                failure -> {
                    throw new AssertionError(failure);
                });
        primary.join(1000);

        assertEquals(List.of("sound", "image"), events);
    }

    @Test
    void onlyAddedImageWorkersUseSpeculativeResourceContext()
            throws Exception {
        List<String> soundQueue = synchronizedQueue();
        List<String> imageQueue = synchronizedQueue();
        PreloadResultCoordinator.queueSound(soundQueue, "sound");
        PreloadResultCoordinator.queueImage(imageQueue, "image");
        CountDownLatch primaryEntered = new CountDownLatch(1);
        CountDownLatch secondaryObserved = new CountDownLatch(1);
        CountDownLatch releasePrimary = new CountDownLatch(1);
        List<Boolean> primaryContext = Collections.synchronizedList(
                new ArrayList<>());
        List<Boolean> secondaryContext = Collections.synchronizedList(
                new ArrayList<>());

        Thread primary = ParallelImagePreloader.startWorkers(
                2,
                soundQueue,
                new ConcurrentHashMap<>(),
                new byte[0],
                imageQueue,
                new ConcurrentHashMap<>(),
                SENTINEL,
                path -> {
                    primaryContext.add(
                            SpeculativeResourceContext.isActive());
                    primaryEntered.countDown();
                    assertTrue(releasePrimary.await(1, SECONDS));
                    return new byte[]{1};
                },
                path -> {
                    secondaryContext.add(
                            SpeculativeResourceContext.isActive());
                    secondaryObserved.countDown();
                    return image(1);
                },
                failure -> {
                    throw new AssertionError(failure);
                });

        assertTrue(primaryEntered.await(1, SECONDS));
        assertTrue(secondaryObserved.await(1, SECONDS));
        releasePrimary.countDown();
        primary.join(1000);
        awaitWorkerCount(0);

        assertEquals(List.of(false), primaryContext);
        assertEquals(List.of(true), secondaryContext);
    }

    @Test
    void reportsOneDecodeFailureAndContinuesWithTheQueue()
            throws Exception {
        List<String> soundQueue = synchronizedQueue();
        Map<String, byte[]> soundResults = new ConcurrentHashMap<>();
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        List<Throwable> failures = Collections.synchronizedList(
                new ArrayList<>());
        PreloadResultCoordinator.queueImage(imageQueue, "bad");
        PreloadResultCoordinator.queueImage(imageQueue, "good");
        BufferedImage good = image(2);

        Thread primary = ParallelImagePreloader.startWorkers(
                1,
                soundQueue,
                soundResults,
                new byte[0],
                imageQueue,
                imageResults,
                SENTINEL,
                path -> new byte[0],
                path -> {
                    if ("bad".equals(path)) {
                        throw new IOException("broken image");
                    }
                    return good;
                },
                failures::add);
        primary.join(1000);

        assertEquals(1, failures.size());
        assertEquals("broken image", failures.get(0).getMessage());
        assertNull(PreloadResultCoordinator.awaitImage(
                "bad", imageResults, SENTINEL));
        assertSame(good, PreloadResultCoordinator.awaitImage(
                "good", imageResults, SENTINEL));
    }

    @Test
    void decoderErrorClearsSentinelAndEscapesLikeOriginalWorker()
            throws Exception {
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        List<Throwable> failures = Collections.synchronizedList(
                new ArrayList<>());
        List<Throwable> uncaught = Collections.synchronizedList(
                new ArrayList<>());
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseError = new CountDownLatch(1);
        PreloadResultCoordinator.queueImage(imageQueue, "bad");
        PreloadResultCoordinator.queueImage(imageQueue, "good");

        Thread primary = ParallelImagePreloader.startWorkers(
                1,
                synchronizedQueue(),
                new ConcurrentHashMap<>(),
                new byte[0],
                imageQueue,
                imageResults,
                SENTINEL,
                path -> new byte[0],
                path -> {
                    if ("bad".equals(path)) {
                        decoderEntered.countDown();
                        assertTrue(releaseError.await(1, SECONDS));
                        throw new AssertionError("broken backend");
                    }
                    return image(3);
                },
                failures::add);
        assertTrue(decoderEntered.await(1, SECONDS));
        primary.setUncaughtExceptionHandler(
                (thread, failure) -> uncaught.add(failure));
        releaseError.countDown();
        primary.join(1000);

        assertFalse(primary.isAlive());
        assertEquals(List.of(), failures);
        assertEquals(1, uncaught.size());
        assertTrue(uncaught.get(0) instanceof AssertionError);
        assertNull(PreloadResultCoordinator.awaitImage(
                "bad", imageResults, SENTINEL));
        assertTrue(imageQueue.contains("good"));
        assertFalse(imageResults.containsKey("good"));
    }

    @Test
    void stopKeepsUncooperativeWorkerTrackedUntilItActuallyExits()
            throws Exception {
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PreloadResultCoordinator.queueImage(imageQueue, "slow");

        ParallelImagePreloader.startWorkers(
                1,
                synchronizedQueue(),
                new ConcurrentHashMap<>(),
                new byte[0],
                imageQueue,
                imageResults,
                SENTINEL,
                path -> new byte[0],
                path -> {
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a mod decoder that ignores interruption.
                        }
                    }
                    return image(4);
                },
                failure -> {
                    throw new AssertionError(failure);
                });
        assertTrue(entered.await(1, SECONDS));

        ParallelImagePreloader.stop();

        assertEquals(1, ParallelImagePreloader.activeWorkerCount());
        release.countDown();
        awaitWorkerCount(0);
    }

    @Test
    void duplicatePathWaitDoesNotRetireTheOtherImageWorkers()
            throws Exception {
        List<String> soundQueue = synchronizedQueue();
        Map<String, byte[]> soundResults = new ConcurrentHashMap<>();
        List<String> imageQueue = synchronizedQueue();
        Map<String, BufferedImage> imageResults =
                new ConcurrentHashMap<>();
        Map<String, AtomicInteger> decodeCounts =
                new ConcurrentHashMap<>();
        CountDownLatch firstDuplicateStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDuplicate = new CountDownLatch(1);
        CountDownLatch twoFollowingDecodes = new CountDownLatch(2);
        CountDownLatch releaseFollowingDecodes = new CountDownLatch(1);
        PreloadResultCoordinator.queueImage(imageQueue, "duplicate");
        PreloadResultCoordinator.queueImage(imageQueue, "duplicate");
        PreloadResultCoordinator.queueImage(imageQueue, "other-1");
        PreloadResultCoordinator.queueImage(imageQueue, "other-2");

        Thread primary = ParallelImagePreloader.startWorkers(
                3,
                soundQueue,
                soundResults,
                new byte[0],
                imageQueue,
                imageResults,
                SENTINEL,
                path -> new byte[0],
                path -> {
                    int count = decodeCounts.computeIfAbsent(
                            path, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    if ("duplicate".equals(path) && count == 1) {
                        firstDuplicateStarted.countDown();
                        assertTrue(releaseFirstDuplicate.await(1, SECONDS));
                    } else {
                        twoFollowingDecodes.countDown();
                        assertTrue(releaseFollowingDecodes.await(1, SECONDS));
                    }
                    return image(count);
                },
                failure -> {
                    throw new AssertionError(failure);
                });

        assertTrue(firstDuplicateStarted.await(1, SECONDS));
        releaseFirstDuplicate.countDown();
        assertTrue(twoFollowingDecodes.await(1, SECONDS));
        releaseFollowingDecodes.countDown();
        primary.join(2000);
        awaitWorkerCount(0);

        assertEquals(2, decodeCounts.get("duplicate").get());
        assertEquals(1, decodeCounts.get("other-1").get());
        assertEquals(1, decodeCounts.get("other-2").get());
        assertEquals(List.of(), imageQueue);
    }

    @Test
    void reflectionSetupFailureRunsTheOriginalWorker() throws Exception {
        System.setProperty(
                ParallelImagePreloader.WORKER_COUNT_PROPERTY, "3");
        CountDownLatch fallbackRan = new CountDownLatch(1);
        TestLogger logger = new TestLogger();

        Thread primary = ParallelImagePreloader.start(
                fallbackRan::countDown,
                synchronizedQueue(),
                new ConcurrentHashMap<>(),
                new byte[0],
                synchronizedQueue(),
                new ConcurrentHashMap<>(),
                SENTINEL,
                InvalidLoader.class,
                "missingSoundLoader",
                "missingImageLoader",
                logger);

        assertTrue(fallbackRan.await(1, SECONDS));
        primary.join(1000);
        assertEquals(1, logger.failures.size());
        assertTrue(logger.failures.get(0) instanceof NoSuchMethodException);
    }

    private static <T> List<T> synchronizedQueue() {
        return Collections.synchronizedList(new LinkedList<>());
    }

    private static BufferedImage image(int value) {
        BufferedImage image = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, value);
        return image;
    }

    private static void awaitWorkerCount(int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(2);
        while (ParallelImagePreloader.activeWorkerCount() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, ParallelImagePreloader.activeWorkerCount());
    }

    private static final class InvalidLoader {
    }

    public static final class TestLogger {
        private final List<Throwable> failures = new ArrayList<>();

        public void error(Object message, Throwable failure) {
            failures.add(failure);
        }
    }
}

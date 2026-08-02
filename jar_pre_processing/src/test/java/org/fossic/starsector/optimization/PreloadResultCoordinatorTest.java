package org.fossic.starsector.optimization;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

final class PreloadResultCoordinatorTest {
    private static final String PATH = "graphics/test.png";
    private static final String LOADING = "loading";

    @BeforeEach
    void resetDiagnostics() {
        PreloadPathDedupDiagnostics.resetForTests();
    }

    @Test
    void returnsNullImmediatelyForAPathThatWasNeverQueued() {
        var channel = new PreloadResultCoordinator.Channel<String>();

        assertNull(channel.await(
                PATH, new ConcurrentHashMap<>(), LOADING));
    }

    @Test
    void uniqueEnqueueDropsOnlyExactDuplicatesInTheSameCycle() {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();

        assertTrue(channel.enqueueUnique(queue, PATH));
        assertFalse(channel.enqueueUnique(queue, PATH));
        assertTrue(channel.enqueueUnique(
                queue, "Graphics/test.png"));
        assertTrue(channel.enqueueUnique(
                queue, "graphics\\test.png"));

        assertEquals(List.of(
                PATH,
                "Graphics/test.png",
                "graphics\\test.png"), queue);
    }

    @Test
    void uniqueFailureAllowsASecondExplicitQueueCycle() {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();

        assertTrue(channel.enqueueUnique(queue, PATH));
        assertFalse(channel.enqueueUnique(queue, PATH));
        queue.remove(0);
        channel.started(results, PATH, LOADING);
        channel.failed(results, PATH);
        assertNull(channel.await(PATH, results, LOADING));

        assertTrue(channel.enqueueUnique(queue, PATH));
        assertEquals(List.of(PATH), queue);
    }

    @Test
    void publicUniqueImageQueueReportsAcceptedAndDroppedRequests() {
        List<String> queue = new ArrayList<>();

        PreloadResultCoordinator.queueImageUnique(queue, PATH);
        PreloadResultCoordinator.queueImageUnique(queue, PATH);

        assertEquals(List.of(PATH), queue);
        assertEquals(
                "{\"requests\":2,\"accepted\":1,\"deduplicated\":1}",
                PreloadPathDedupDiagnostics.json());
    }

    @Test
    void pollsForACompletedResultAndConsumesIt() throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            assertEquals(List.of(PATH), queue);
            Future<String> waiting =
                    executor.submit(() -> channel.await(
                            PATH, results, LOADING));
            assertStillWaiting(waiting);

            queue.remove(0);
            channel.started(results, PATH, LOADING);
            assertStillWaiting(waiting);

            String pixels = "pixels";
            channel.completed(results, PATH, pixels);

            assertSame(pixels, waiting.get(1, SECONDS));
            assertFalse(results.containsKey(PATH));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void waitsForTheRemainingDuplicateAfterTheFirstAttemptFails()
            throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            channel.enqueue(queue, PATH);
            Future<String> waiting =
                    executor.submit(() -> channel.await(
                            PATH, results, LOADING));

            queue.remove(0);
            channel.started(results, PATH, LOADING);
            channel.failed(results, PATH);
            assertStillWaiting(waiting);

            queue.remove(0);
            channel.started(results, PATH, LOADING);
            String secondResult = "second-result";
            channel.completed(results, PATH, secondResult);

            assertSame(secondResult, waiting.get(1, SECONDS));
            assertEquals(List.of(), queue);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsNullAfterTheLastQueuedAttemptFails() throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            Future<String> waiting =
                    executor.submit(() -> channel.await(
                            PATH, results, LOADING));

            queue.remove(0);
            channel.started(results, PATH, LOADING);
            channel.failed(results, PATH);

            assertNull(waiting.get(1, SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptionReturnsNullAndKeepsTheOriginalClearedFlag()
            throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        channel.enqueue(queue, PATH);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<String> returned = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean(true);
        Thread thread = new Thread(() -> {
            entered.countDown();
            returned.set(channel.await(PATH, results, LOADING));
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        entered.await(1, SECONDS);
        awaitPollingState(thread);
        thread.interrupt();
        thread.join(1000);

        assertFalse(thread.isAlive());
        assertNull(returned.get());
        assertFalse(interruptedAfterReturn.get());
    }

    @Test
    void clearStopsPollingAndAllowsAFreshQueueCycle() throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = new ArrayList<>();
        Map<String, String> results = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            Future<String> first =
                    executor.submit(() -> channel.await(
                            PATH, results, LOADING));
            assertStillWaiting(first);

            results.clear();
            channel.clear();
            assertNull(first.get(1, SECONDS));

            queue.clear();
            channel.enqueue(queue, PATH);
            queue.remove(0);
            channel.started(results, PATH, LOADING);
            channel.completed(results, PATH, "fresh");
            assertEquals("fresh", channel.await(PATH, results, LOADING));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lateCompletionFromClearedCycleCannotOverwriteFreshResult()
            throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = Collections.synchronizedList(
                new LinkedList<>());
        Map<String, String> results = new ConcurrentHashMap<>();
        CountDownLatch oldClaimed = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            Future<?> oldWorker = executor.submit(() -> {
                assertEquals(PATH, channel.claim(
                        queue, results, LOADING));
                oldClaimed.countDown();
                assertTrue(releaseOld.await(1, SECONDS));
                channel.completed(results, PATH, "stale");
                return null;
            });
            assertTrue(oldClaimed.await(1, SECONDS));

            results.clear();
            channel.clear();
            queue.clear();
            channel.enqueue(queue, PATH);
            assertEquals(PATH, channel.claim(
                    queue, results, LOADING));
            channel.completed(results, PATH, "fresh");

            releaseOld.countDown();
            oldWorker.get(1, SECONDS);

            assertEquals("fresh", channel.await(
                    PATH, results, LOADING));
        } finally {
            releaseOld.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lateFailureFromClearedCycleCannotDeleteFreshResult()
            throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = Collections.synchronizedList(
                new LinkedList<>());
        Map<String, String> results = new ConcurrentHashMap<>();
        CountDownLatch oldClaimed = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            channel.enqueue(queue, PATH);
            Future<?> oldWorker = executor.submit(() -> {
                assertEquals(PATH, channel.claim(
                        queue, results, LOADING));
                oldClaimed.countDown();
                assertTrue(releaseOld.await(1, SECONDS));
                channel.failed(results, PATH);
                return null;
            });
            assertTrue(oldClaimed.await(1, SECONDS));

            results.clear();
            channel.clear();
            queue.clear();
            channel.enqueue(queue, PATH);
            assertEquals(PATH, channel.claim(
                    queue, results, LOADING));
            channel.completed(results, PATH, "fresh");

            releaseOld.countDown();
            oldWorker.get(1, SECONDS);

            assertEquals("fresh", channel.await(
                    PATH, results, LOADING));
        } finally {
            releaseOld.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void claimsQueuedPathsAtomicallyAcrossWorkers() throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = Collections.synchronizedList(
                new LinkedList<>());
        Map<String, String> results = new ConcurrentHashMap<>();
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        for (int index = 0; index < 1000; index++) {
            channel.enqueue(queue, "image-" + index);
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                workers.add(executor.submit(() -> {
                    String path;
                    while ((path = channel.claim(
                            queue, results, LOADING)) != null) {
                        assertFalse(!claimed.add(path), path);
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(1, SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1000, claimed.size());
        assertEquals(1000, results.size());
        assertEquals(List.of(), queue);
    }

    @Test
    void waitsBeforeClaimingTheSamePathWhileItsFirstDecodeIsInFlight()
            throws Exception {
        var channel = new PreloadResultCoordinator.Channel<String>();
        List<String> queue = Collections.synchronizedList(
                new LinkedList<>());
        Map<String, String> results = new ConcurrentHashMap<>();
        channel.enqueue(queue, PATH);
        channel.enqueue(queue, PATH);

        assertEquals(PATH, channel.claim(queue, results, LOADING));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> duplicate = executor.submit(
                    () -> channel.claim(queue, results, LOADING));
            assertStillWaiting(duplicate);

            channel.completed(results, PATH, "first");

            assertEquals(PATH, duplicate.get(1, SECONDS));
            assertEquals(List.of(), queue);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertStillWaiting(Future<?> future) {
        assertThrows(
                TimeoutException.class,
                () -> future.get(50, MILLISECONDS));
    }

    private static void awaitPollingState(Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, thread.getState());
    }
}

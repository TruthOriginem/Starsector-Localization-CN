package org.fossic.starsector.optimization;

import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class OrderedParallelLoaderTest {
    @AfterEach
    void cleanUp() {
        OrderedParallelLoader.abort();
        System.clearProperty(OrderedParallelLoader.WORKERS_PROPERTY);
        System.clearProperty(OrderedParallelLoader.WINDOW_PROPERTY);
        Thread.interrupted();
    }

    @Test
    void preparesOutOfOrderButReturnsInInputOrder() throws Throwable {
        Fixture fixture = new Fixture(List.of(80L, 20L, 0L));
        begin(List.of("a", "b", "c"), fixture);

        List<String> committed = new ArrayList<>();
        committed.add((String) OrderedParallelLoader.load("a", handle(fixture)));
        committed.add((String) OrderedParallelLoader.load("b", handle(fixture)));
        committed.add((String) OrderedParallelLoader.load("c", handle(fixture)));

        assertEquals(List.of("A", "B", "C"), committed);
        assertTrue(fixture.maxActive.get() > 1);
        assertFalse(OrderedParallelLoader.activeForTests());
    }

    @Test
    void preservesDuplicateInputOrdinals() throws Throwable {
        Fixture fixture = new Fixture(List.of(0L, 0L, 0L));
        begin(List.of("same", "same", "same"), fixture);

        assertEquals("SAME", OrderedParallelLoader.load("same", handle(fixture)));
        assertEquals("SAME", OrderedParallelLoader.load("same", handle(fixture)));
        assertEquals("SAME", OrderedParallelLoader.load("same", handle(fixture)));
        assertEquals(3, fixture.calls.get());
    }

    @Test
    void earliestInputFailureWinsEvenWhenLaterFailureFinishesFirst()
            throws Throwable {
        IOException earliest = new IOException("first input");
        IllegalStateException later = new IllegalStateException("later input");
        FailureFixture fixture = new FailureFixture(earliest, later);
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(FailureFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
        System.setProperty(OrderedParallelLoader.WORKERS_PROPERTY, "2");
        System.setProperty(OrderedParallelLoader.WINDOW_PROPERTY, "2");
        OrderedParallelLoader.begin(List.of("first", "second"), handle);

        Throwable thrown = assertThrows(Throwable.class,
                () -> OrderedParallelLoader.load("first", handle));

        assertSame(earliest, thrown);
        assertTrue(fixture.secondFinished.await(1, TimeUnit.SECONDS));
        assertFalse(OrderedParallelLoader.activeForTests());
    }

    @Test
    void limitsWorkersAndQueuedResults() throws Throwable {
        System.setProperty(OrderedParallelLoader.WORKERS_PROPERTY, "2");
        System.setProperty(OrderedParallelLoader.WINDOW_PROPERTY, "4");
        Fixture fixture = new Fixture(java.util.Collections.nCopies(20, 5L));
        List<String> paths = java.util.stream.IntStream.range(0, 20)
                .mapToObj(Integer::toString)
                .toList();
        begin(paths, fixture);

        assertEquals(4, OrderedParallelLoader.inFlightForTests());
        for (String path : paths) {
            assertEquals(path.toUpperCase(),
                    OrderedParallelLoader.load(path, handle(fixture)));
            assertTrue(OrderedParallelLoader.inFlightForTests() <= 4);
        }
        assertTrue(fixture.maxActive.get() <= 2);
    }

    @Test
    void waitingIgnoresInterruptButRestoresCallerFlag() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        InterruptFixture fixture = new InterruptFixture(release);
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(InterruptFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
        OrderedParallelLoader.begin(List.of("value"), handle);
        Thread.currentThread().interrupt();

        Thread releaser = new Thread(release::countDown);
        releaser.start();
        assertEquals("value", OrderedParallelLoader.load("value", handle));
        releaser.join();

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void mismatchedConsumptionAbortsStage() throws Throwable {
        Fixture fixture = new Fixture(List.of(0L, 0L));
        begin(List.of("a", "b"), fixture);

        assertThrows(IllegalStateException.class,
                () -> OrderedParallelLoader.load("b", handle(fixture)));
        assertFalse(OrderedParallelLoader.activeForTests());
    }

    @Test
    void zeroWorkersUsesExactSynchronousFallback() throws Throwable {
        System.setProperty(OrderedParallelLoader.WORKERS_PROPERTY, "0");
        Fixture fixture = new Fixture(List.of(0L, 0L));
        begin(List.of("a", "b"), fixture);

        assertEquals(0, fixture.calls.get());
        assertEquals("A", OrderedParallelLoader.load("a", handle(fixture)));
        assertEquals("B", OrderedParallelLoader.load("b", handle(fixture)));
        assertEquals(1, fixture.maxActive.get());
    }

    @Test
    void capturesWorkerInfoAndReplaysItInConsumptionOrder()
            throws Throwable {
        LogFixture fixture = new LogFixture();
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(LogFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
        OrderedParallelLoader.begin(List.of("a", "b"), handle);

        OrderedParallelLoader.load("a", handle);
        OrderedParallelLoader.load("b", handle);

        assertEquals(List.of("a", "b"), fixture.logger.messages);
        assertEquals(List.of(
                        Thread.currentThread().getName(),
                        Thread.currentThread().getName()),
                fixture.logger.threads);
    }

    @Test
    void infoPassesThroughOutsideParallelStage() throws Throwable {
        RecordingLogger logger = new RecordingLogger();

        OrderedParallelLoader.info(logger, "direct");

        assertEquals(List.of("direct"), logger.messages);
    }

    @Test
    void retriesFailedPrefetchOnConsumerThreadAfterEarlierMutation()
            throws Throwable {
        GeneratedInputFixture fixture = new GeneratedInputFixture();
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(GeneratedInputFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
        System.setProperty(OrderedParallelLoader.WORKERS_PROPERTY, "2");
        System.setProperty(OrderedParallelLoader.WINDOW_PROPERTY, "2");
        OrderedParallelLoader.begin(List.of("first", "generated"), handle);
        assertTrue(fixture.failedPrefetch.await(1, TimeUnit.SECONDS));

        assertEquals("FIRST", OrderedParallelLoader.load("first", handle));
        fixture.generated = true;
        assertEquals("GENERATED",
                OrderedParallelLoader.load("generated", handle));

        assertEquals(2, fixture.generatedCalls.get());
        assertEquals(Thread.currentThread().getName(), fixture.retryThread);
    }

    @Test
    void discardsLogsFromFailedPrefetchAndLogsSynchronousRetryOnce()
            throws Throwable {
        RetryLogFixture fixture = new RetryLogFixture();
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(RetryLogFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
        OrderedParallelLoader.begin(List.of("value"), handle);
        assertTrue(fixture.failedPrefetch.await(1, TimeUnit.SECONDS));

        assertEquals("value", OrderedParallelLoader.load("value", handle));

        assertEquals(List.of("main:value"), fixture.logger.messages);
        assertEquals(List.of(Thread.currentThread().getName()),
                fixture.logger.threads);
    }

    @Test
    void workerErrorsArePropagatedWithoutSynchronousRetry()
            throws Throwable {
        for (Error fatal : List.of(
                new OutOfMemoryError("simulated exhaustion"),
                new ThreadDeath(),
                new AssertionError("simulated assertion"),
                new NoClassDefFoundError("simulated linkage failure"))) {
            AtomicInteger calls = new AtomicInteger();
            RecordingLogger logger = new RecordingLogger();
            MethodHandle handle = MethodHandles.lookup()
                    .findStatic(
                            OrderedParallelLoaderTest.class,
                            "throwError",
                            methodType(Object.class, Error.class,
                                    AtomicInteger.class,
                                    RecordingLogger.class,
                                    String.class))
                    .bindTo(fatal)
                    .bindTo(calls)
                    .bindTo(logger);
            OrderedParallelLoader.begin(List.of("value"), handle);

            Error thrown = assertThrows(Error.class,
                    () -> OrderedParallelLoader.load("value", handle));

            assertSame(fatal, thrown);
            assertEquals(1, calls.get());
            assertTrue(logger.messages.isEmpty());
            assertFalse(OrderedParallelLoader.activeForTests());
        }
    }

    @Test
    void workerMarksOnlyTheSpeculativeInvocationContext()
            throws Throwable {
        MethodHandle handle = MethodHandles.lookup().findStatic(
                OrderedParallelLoaderTest.class,
                "reportSpeculativeContext",
                methodType(Object.class, String.class));
        assertFalse(SpeculativeResourceContext.isActive());

        OrderedParallelLoader.begin(List.of("value"), handle);

        assertEquals(Boolean.TRUE,
                OrderedParallelLoader.load("value", handle));
        assertFalse(SpeculativeResourceContext.isActive());
    }

    @Test
    void beginningNextStageRecoversAnAbandonedPreviousStage()
            throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        InterruptFixture abandoned = new InterruptFixture(release);
        MethodHandle abandonedHandle = MethodHandles.lookup()
                .findVirtual(InterruptFixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(abandoned);
        OrderedParallelLoader.begin(List.of("old"), abandonedHandle);

        Fixture replacement = new Fixture(List.of(0L));
        MethodHandle replacementHandle = handle(replacement);
        OrderedParallelLoader.begin(List.of("new"), replacementHandle);

        assertEquals("NEW",
                OrderedParallelLoader.load("new", replacementHandle));
        release.countDown();
        assertFalse(OrderedParallelLoader.activeForTests());
    }

    private static void begin(List<String> paths, Fixture fixture)
            throws ReflectiveOperationException {
        OrderedParallelLoader.begin(paths, handle(fixture));
    }

    private static MethodHandle handle(Fixture fixture)
            throws ReflectiveOperationException {
        return MethodHandles.lookup()
                .findVirtual(Fixture.class, "load",
                        methodType(Object.class, String.class))
                .bindTo(fixture);
    }

    private static Object throwError(
            Error fatal,
            AtomicInteger calls,
            RecordingLogger logger,
            String ignored) throws Throwable {
        calls.incrementAndGet();
        OrderedParallelLoader.info(logger, "discarded fatal log");
        throw fatal;
    }

    private static Object reportSpeculativeContext(String ignored) {
        return SpeculativeResourceContext.isActive();
    }

    private static class Fixture {
        private final List<Long> delays;
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private Fixture(List<Long> delays) {
            this.delays = delays;
        }

        public Object load(String input) throws InterruptedException {
            int index = next.getAndIncrement();
            calls.incrementAndGet();
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(delays.get(index));
                return input.toUpperCase();
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static final class FailureFixture {
        private final IOException first;
        private final IllegalStateException second;
        private final CountDownLatch secondFinished = new CountDownLatch(1);

        private FailureFixture(IOException first, IllegalStateException second) {
            this.first = first;
            this.second = second;
        }

        public Object load(String input) throws Throwable {
            if ("second".equals(input)) {
                secondFinished.countDown();
                throw second;
            }
            secondFinished.await(1, TimeUnit.SECONDS);
            throw first;
        }
    }

    private static final class InterruptFixture {
        private final CountDownLatch release;

        private InterruptFixture(CountDownLatch release) {
            this.release = release;
        }

        public Object load(String input) throws InterruptedException {
            release.await();
            return input;
        }
    }

    private static final class LogFixture {
        private final RecordingLogger logger = new RecordingLogger();

        public Object load(String input) throws Throwable {
            if ("a".equals(input)) {
                Thread.sleep(30);
            }
            OrderedParallelLoader.info(logger, input);
            return input;
        }
    }

    private static final class GeneratedInputFixture {
        private final CountDownLatch failedPrefetch = new CountDownLatch(1);
        private final AtomicInteger generatedCalls = new AtomicInteger();
        private volatile boolean generated;
        private volatile String retryThread;

        public Object load(String input) throws IOException {
            if ("first".equals(input)) {
                return "FIRST";
            }
            generatedCalls.incrementAndGet();
            if (!generated) {
                failedPrefetch.countDown();
                throw new IOException("not generated yet");
            }
            retryThread = Thread.currentThread().getName();
            return "GENERATED";
        }
    }

    private static final class RetryLogFixture {
        private final RecordingLogger logger = new RecordingLogger();
        private final CountDownLatch failedPrefetch = new CountDownLatch(1);

        public Object load(String input) throws Throwable {
            boolean worker = Thread.currentThread().getName()
                    .startsWith("fossic-spec-json-");
            OrderedParallelLoader.info(
                    logger, (worker ? "worker:" : "main:") + input);
            if (worker) {
                failedPrefetch.countDown();
                throw new IOException("worker snapshot was stale");
            }
            return input;
        }
    }

    static final class RecordingLogger {
        private final List<String> messages =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> threads =
                java.util.Collections.synchronizedList(new ArrayList<>());

        public void info(Object message) {
            messages.add((String) message);
            threads.add(Thread.currentThread().getName());
        }
    }
}

package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JaninoSourceIndexTest {
    @BeforeEach
    void resetDiagnostics() {
        JaninoSourceIndexDiagnostics.resetForTests();
    }

    @Test
    void cachesPositiveLookupAndSnapshotsSourceBytesOnce() throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of(
                "sample/Example.java", "package sample; class Example {}"));
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        Resource first = index.findResource("sample/Example.java");
        Resource second = index.findResource("sample/Example.java");

        assertNotNull(first);
        assertSame(first, second);
        assertArrayEquals(delegate.bytes("sample/Example.java"), read(first));
        assertArrayEquals(delegate.bytes("sample/Example.java"), read(second));
        assertEquals(1, delegate.lookupCount.get());
        assertEquals(1, delegate.openCount.get());
        assertEquals(1, delegate.closeCount.get());
    }

    @Test
    void negativeLookupIsRetriedAndSeesAFileAddedDuringLoading()
            throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of());
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        assertNull(index.findResource("missing/Example.java"));
        delegate.put(
                "missing/Example.java",
                "package missing; class Example {}");
        assertNotNull(index.findResource("missing/Example.java"));

        assertEquals(2, delegate.lookupCount.get());
        assertEquals(1, index.snapshotAll().size());
        assertTrue(index.snapshotAll().get(0).present());
    }

    @Test
    void freshNegativeSnapshotDoesNotPoisonTheLiveIndex()
            throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of());
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        JaninoSourceIndex.SourceSnapshot validation =
                index.snapshotFresh("sample/Added.java");
        delegate.put(
                "sample/Added.java",
                "package sample; class Added {}");

        Resource live = index.findResource("sample/Added.java");
        assertFalse(validation.present());
        assertNotNull(live);
        assertArrayEquals(
                delegate.bytes("sample/Added.java"), read(live));
        assertEquals(
                List.of(index.snapshot("sample/Added.java")),
                index.snapshotAll());
    }

    @Test
    void freshPositiveSnapshotDoesNotFreezeLaterLiveBytes()
            throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of(
                "sample/Example.java", "class Example { int v = 1; }"));
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        JaninoSourceIndex.SourceSnapshot validation =
                index.snapshotFresh("sample/Example.java");
        delegate.put(
                "sample/Example.java", "class Example { int v = 2; }");

        Resource live = index.findResource("sample/Example.java");
        assertTrue(validation.present());
        assertNotNull(live);
        assertArrayEquals(
                delegate.bytes("sample/Example.java"), read(live));
        assertEquals(2, delegate.lookupCount.get());
    }

    @Test
    void snapshotsMetadataLazilyAndOnlyOnce() {
        CountingFinder delegate = new CountingFinder(Map.of(
                "sample/Example.java", "class Example {}"));
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);
        Resource resource = index.findResource("sample/Example.java");

        assertNotNull(resource);
        assertEquals("sample/Example.java", resource.getFileName());
        assertEquals(1_234_567_890L, resource.lastModified());
        assertEquals(1_234_567_890L, resource.lastModified());
        assertEquals(1, delegate.lookupCount.get());
        assertEquals(1, delegate.lastModifiedCount.get());
        assertEquals(0, delegate.openCount.get());
    }

    @Test
    void concurrentLookupUsesOneDelegateCall() throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of(
                "sample/Example.java", "class Example {}"));
        delegate.blockLookups();
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resource>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    return index.findResource("sample/Example.java");
                }));
            }
            start.countDown();
            delegate.lookupEntered.await(5, TimeUnit.SECONDS);
            delegate.releaseLookup.countDown();

            Resource expected = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Resource> future : futures) {
                assertSame(expected, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, delegate.lookupCount.get());
        } finally {
            delegate.releaseLookup.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentNegativeLookupIsSingleFlightButLaterCallsRetry()
            throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of());
        delegate.blockLookups();
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resource>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    return index.findResource("missing/Concurrent.java");
                }));
            }
            start.countDown();
            assertTrue(delegate.lookupEntered.await(5, TimeUnit.SECONDS));
            assertTrue(awaitRequestCount(8, 5, TimeUnit.SECONDS));
            delegate.releaseLookup.countDown();
            for (Future<Resource> future : futures) {
                assertNull(future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, delegate.lookupCount.get());

            assertNull(index.findResource("missing/Concurrent.java"));
            assertEquals(2, delegate.lookupCount.get());
        } finally {
            delegate.releaseLookup.countDown();
            workers.shutdownNow();
        }
    }

    private static boolean awaitRequestCount(
            long expected, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (JaninoSourceIndexDiagnostics.requestCountForTests()
                < expected) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(1L);
        }
        return true;
    }

    @Test
    void failedDelegateLookupIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        ResourceFinder delegate = new ResourceFinder() {
            @Override
            public Resource findResource(String resourceName) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("first lookup fails");
                }
                return new CountingFinder(Map.of(
                        resourceName, "class Example {}"))
                        .findResource(resourceName);
            }
        };
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        assertThrows(IllegalStateException.class,
                () -> index.findResource("sample/Example.java"));
        assertNotNull(index.findResource("sample/Example.java"));
        assertNotNull(index.findResource("sample/Example.java"));
        assertEquals(2, calls.get());
    }

    @Test
    void reportsLookupAndSnapshotDiagnostics() throws Exception {
        CountingFinder delegate = new CountingFinder(Map.of(
                "sample/Example.java", "class Example {}"));
        JaninoSourceIndex index = new JaninoSourceIndex(delegate);

        Resource first = index.findResource("sample/Example.java");
        Resource second = index.findResource("sample/Example.java");
        index.findResource("missing/Example.java");
        index.findResource("missing/Example.java");
        read(first);
        read(second);
        first.lastModified();
        second.lastModified();

        assertEquals(
                "{\"requests\":4,\"cacheHits\":1,\"delegateLookups\":3,"
                        + "\"positive\":1,\"negative\":2,"
                        + "\"sourceSnapshots\":1,\"sourceReuses\":1,"
                        + "\"metadataSnapshots\":1,\"metadataReuses\":1}",
                JaninoSourceIndexDiagnostics.json());
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream input = resource.open()) {
            return input.readAllBytes();
        }
    }

    private static final class CountingFinder extends ResourceFinder {
        private final Map<String, byte[]> sources;
        private final AtomicInteger lookupCount = new AtomicInteger();
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger lastModifiedCount = new AtomicInteger();
        private CountDownLatch lookupEntered = new CountDownLatch(0);
        private CountDownLatch releaseLookup = new CountDownLatch(0);

        private CountingFinder(Map<String, String> sources) {
            Map<String, byte[]> encoded = new LinkedHashMap<>();
            sources.forEach((name, source) -> encoded.put(
                    name, source.getBytes(StandardCharsets.UTF_8)));
            this.sources = new ConcurrentHashMap<>(encoded);
        }

        private byte[] bytes(String name) {
            return sources.get(name).clone();
        }

        private void put(String name, String source) {
            sources.put(name, source.getBytes(StandardCharsets.UTF_8));
        }

        private void blockLookups() {
            lookupEntered = new CountDownLatch(1);
            releaseLookup = new CountDownLatch(1);
        }

        @Override
        public Resource findResource(String resourceName) {
            lookupCount.incrementAndGet();
            lookupEntered.countDown();
            try {
                if (!releaseLookup.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("lookup was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            byte[] content = sources.get(resourceName);
            if (content == null) {
                return null;
            }
            byte[] snapshot = content.clone();
            return new Resource() {
                @Override
                public InputStream open() {
                    openCount.incrementAndGet();
                    return new ByteArrayInputStream(snapshot) {
                        @Override
                        public void close() throws IOException {
                            closeCount.incrementAndGet();
                            super.close();
                        }
                    };
                }

                @Override
                public String getFileName() {
                    return resourceName;
                }

                @Override
                public long lastModified() {
                    lastModifiedCount.incrementAndGet();
                    return 1_234_567_890L;
                }
            };
        }
    }
}

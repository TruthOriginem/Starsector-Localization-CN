package org.fossic.starsector.dynfont;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DynFontOverridesTest {
    private static final double HD_SCALE = 1.05;

    @AfterEach
    void resetGate() {
        DynFontOverrides.resetGameContextGateForTests();
        DynFontOverrides.releaseCacheLeasesForTests();
    }

    @Test
    void ownsOnlyExactGraphicsFontsLogicalPaths() {
        assertTrue(DynFontOverrides.isOwnedFntPath(
                "graphics/fonts/victor10.fnt"));
        assertTrue(DynFontOverrides.isOwnedFntPath(
                "Graphics\\Fonts\\VICTOR10.FNT"));

        assertFalse(DynFontOverrides.isOwnedFntPath(
                "victor10.fnt"));
        assertFalse(DynFontOverrides.isOwnedFntPath(
                "mods/example/graphics/fonts/victor10.fnt"));
        assertFalse(DynFontOverrides.isOwnedFntPath(
                "graphics/fonts/../victor10.fnt"));
    }

    @Test
    void generatedClaimsUseFullLogicalPaths(@TempDir Path output)
            throws IOException {
        for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
            Files.write(output.resolve(name), new byte[]{1});
        }
        Files.write(output.resolve("modfont.fnt"), new byte[]{3});
        Files.write(output.resolve("modfont.png"), new byte[]{4});

        Map<String, Path> claims = DynFontOverrides.buildClaims(output, HD_SCALE);

        assertTrue(claims.containsKey("graphics/fonts/victor10.fnt"));
        assertTrue(claims.containsKey(
                "graphics/fonts/victor10_0.png"));
        assertTrue(claims.containsKey(
                "graphics/fonts/victor10_exact.fnt"));
        assertTrue(claims.containsKey(
                "graphics/fonts/victor10.dfnt"));
        assertFalse(claims.containsKey("victor10.fnt"));
        assertFalse(claims.containsKey("graphics/fonts/modfont.fnt"));
        assertFalse(claims.containsKey("graphics/fonts/modfont.png"));
        assertEquals(DynFontOverrides.expectedOutputNames(HD_SCALE).size(),
                claims.size());
    }

    @Test
    void internalAndResourceClaimNamesUseTheSameIndexKey() {
        assertEquals("graphics/fonts/victor10.dfnt",
                DynFontOverrides.cacheClaimKey("victor10.dfnt"));
        assertEquals("graphics/fonts/victor10.dfnt",
                DynFontOverrides.cacheClaimKey(
                        "Graphics\\Fonts\\VICTOR10.DFNT"));
        assertEquals("graphics/fonts/victor10_exact.fnt",
                DynFontOverrides.cacheClaimKey("victor10_exact.fnt"));
        assertEquals(null, DynFontOverrides.cacheClaimKey(
                "mods/example/graphics/fonts/victor10.dfnt"));
    }

    @Test
    void generatedClaimsRejectAConcurrentPartialCache(
            @TempDir Path output) throws IOException {
        for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
            Files.write(output.resolve(name), new byte[]{1});
        }
        Files.delete(output.resolve(
                DynFontOverrides.expectedOutputNames(HD_SCALE).iterator().next()));

        assertThrows(IOException.class,
                () -> DynFontOverrides.buildClaims(output, HD_SCALE));
    }

    @Test
    void expectedOutputsMatchNativeExactProxyContract() {
        for (double scale : List.of(1.0, 1.001, 1.05, 1.95, 2.0)) {
            var names = DynFontOverrides.expectedOutputNames(scale);
            assertEquals(55, names.size());
            assertTrue(names.contains("victor10.fnt"));
            assertTrue(names.contains("victor10_0.png"));
            assertTrue(names.contains("victor10_exact.fnt"));
            assertTrue(names.contains("victor10_exact_0.png"));
            assertTrue(names.contains("victor10.dfnt"));
            assertFalse(names.stream().anyMatch(name -> name.contains("_hd")));
        }
    }

    @Test
    void oneXCacheRequiresExactProxyOutputs(@TempDir Path output)
            throws IOException {
        String fingerprint = "1123456789abcdef";
        populateCompleteCache(output, fingerprint, (byte) 7, 1.0);

        assertTrue(DynFontOverrides.isCompleteCache(
                output, fingerprint, 1.0));
        assertEquals(55, DynFontOverrides.buildClaims(output, 1.0).size());
        assertTrue(DynFontOverrides.buildClaims(output, 1.0)
                .containsKey("graphics/fonts/victor10_exact.fnt"));
        assertTrue(DynFontOverrides.buildClaims(output, 1.0)
                .containsKey("graphics/fonts/victor10.dfnt"));
        assertFalse(DynFontOverrides.isCompleteCache(
                output, fingerprint, 1.05));
        assertEquals(55,
                DynFontOverrides.buildClaims(output, 1.05).size());
    }

    @Test
    void fingerprintFramesTupleBoundaries(@TempDir Path root)
            throws Exception {
        Path left = root.resolve("left");
        Path right = root.resolve("right");
        Files.createDirectories(left);
        Files.createDirectories(right);
        Path leftA = Files.writeString(left.resolve("a"), "Xb");
        Path leftC = Files.writeString(left.resolve("c"), "Y");
        Path rightA = Files.writeString(right.resolve("a"), "X");
        Path rightBc = Files.writeString(right.resolve("bc"), "Y");

        assertEquals(
                leftA.getFileName() + Files.readString(leftA)
                        + leftC.getFileName() + Files.readString(leftC),
                rightA.getFileName() + Files.readString(rightA)
                        + rightBc.getFileName() + Files.readString(rightBc));
        assertNotEquals(
                DynFontOverrides.framedInputFingerprint(leftA, leftC),
                DynFontOverrides.framedInputFingerprint(rightA, rightBc));
    }

    @Test
    void completionMarkerRequiresFingerprintAndEveryExpectedOutput(
            @TempDir Path output) throws IOException {
        String fingerprint = "0123456789abcdef";
        populateCompleteCache(output, fingerprint, (byte) 1);

        assertTrue(DynFontOverrides.isCompleteCache(
                output, fingerprint, HD_SCALE));

        Path changed = output.resolve(
                DynFontOverrides.expectedOutputNames(HD_SCALE).iterator().next());
        long originalModified = Files.getLastModifiedTime(changed).toMillis();
        Files.write(changed, new byte[]{2});
        Files.setLastModifiedTime(
                changed, FileTime.fromMillis(originalModified + 2000L));
        assertFalse(DynFontOverrides.isCompleteCache(
                output, fingerprint, HD_SCALE));

        Files.write(changed, new byte[]{1});
        assertTrue(DynFontOverrides.isCompleteCache(
                output, fingerprint, HD_SCALE));

        Files.writeString(output.resolve(".complete"), "tampered");
        assertFalse(DynFontOverrides.isCompleteCache(
                output, fingerprint, HD_SCALE));
    }

    @Test
    void completedPublisherNeverDeletesAnExistingWinner(@TempDir Path root)
            throws IOException {
        String fingerprint = "0123456789abcdef";
        Path cacheRoot = root.resolve("cache");
        Path target = cacheRoot.resolve("s1.05-" + fingerprint);
        Path loser = cacheRoot.resolve(".loser");
        Files.createDirectories(target);
        Files.createDirectories(loser);
        populateCompleteCache(target, fingerprint, (byte) 1);
        populateCompleteCache(loser, fingerprint, (byte) 2);

        DynFontOverrides.publishGeneratedDirectory(
                cacheRoot, loser, target, fingerprint, HD_SCALE);

        assertTrue(DynFontOverrides.isCompleteCache(
                target, fingerprint, HD_SCALE));
        for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
            assertEquals(1, Files.readAllBytes(target.resolve(name))[0]);
        }
        assertTrue(Files.isDirectory(loser));
    }

    @Test
    void concurrentPublishersProduceOneCompleteDirectory(@TempDir Path root)
            throws Exception {
        String fingerprint = "0123456789abcdef";
        Path cacheRoot = root.resolve("cache");
        Path target = cacheRoot.resolve("s1.05-" + fingerprint);
        Path first = cacheRoot.resolve(".first");
        Path second = cacheRoot.resolve(".second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        populateCompleteCache(first, fingerprint, (byte) 1);
        populateCompleteCache(second, fingerprint, (byte) 2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread one = publisherThread(
                cacheRoot, first, target, fingerprint, ready, start, failure);
        Thread two = publisherThread(
                cacheRoot, second, target, fingerprint, ready, start, failure);
        one.start();
        two.start();
        ready.await();
        start.countDown();
        one.join(5000);
        two.join(5000);

        assertFalse(one.isAlive());
        assertFalse(two.isAlive());
        assertEquals(null, failure.get());
        assertTrue(DynFontOverrides.isCompleteCache(
                target, fingerprint, HD_SCALE));
        Byte winner = null;
        for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
            byte value = Files.readAllBytes(target.resolve(name))[0];
            if (winner == null) {
                winner = value;
            }
            assertEquals(winner.byteValue(), value);
        }
        assertNotNull(winner);
    }

    @Test
    void concurrentJvmPublishersProduceOneCompleteDirectory(
            @TempDir Path root) throws Exception {
        String fingerprint = "fedcba9876543210";
        Path cacheRoot = root.resolve("cache");
        Path target = cacheRoot.resolve("s1.05-" + fingerprint);
        Path first = cacheRoot.resolve(".first-jvm");
        Path second = cacheRoot.resolve(".second-jvm");
        Path startSignal = root.resolve("start.signal");
        Files.createDirectories(first);
        Files.createDirectories(second);
        populateCompleteCache(first, fingerprint, (byte) 3);
        populateCompleteCache(second, fingerprint, (byte) 4);

        Process one = startPublisherProcess(
                cacheRoot, first, target, fingerprint, startSignal);
        Process two = startPublisherProcess(
                cacheRoot, second, target, fingerprint, startSignal);
        Files.write(startSignal, new byte[]{1});
        awaitPublisher(one);
        awaitPublisher(two);

        assertTrue(DynFontOverrides.isCompleteCache(
                target, fingerprint, HD_SCALE));
        Byte winner = null;
        for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
            byte value = Files.readAllBytes(target.resolve(name))[0];
            if (winner == null) {
                winner = value;
            }
            assertEquals(winner.byteValue(), value);
        }
        assertNotNull(winner);
    }

    @Test
    void pruneDoesNotDeleteCacheClaimedByAnotherJvm(
            @TempDir Path root) throws Exception {
        String oldFingerprint = "aaaaaaaaaaaaaaaa";
        String newFingerprint = "bbbbbbbbbbbbbbbb";
        Path cacheRoot = root.resolve("cache");
        Path active = cacheRoot.resolve("s1.05-" + oldFingerprint);
        Path keep = cacheRoot.resolve("s1.05-" + newFingerprint);
        Path ready = root.resolve("lease.ready");
        Path stop = root.resolve("lease.stop");
        populateCompleteCache(active, oldFingerprint, (byte) 5);
        populateCompleteCache(keep, newFingerprint, (byte) 6);

        Process child = startLeaseProcess(
                cacheRoot, active, oldFingerprint, HD_SCALE, ready, stop);
        try {
            awaitSignal(ready, child);
            DynFontOverrides.pruneCaches(
                    cacheRoot, newFingerprint, keep.getFileName().toString());
            assertTrue(Files.isDirectory(active));
            for (String name : DynFontOverrides.expectedOutputNames(HD_SCALE)) {
                assertTrue(Files.isRegularFile(active.resolve(name)), name);
            }
        } finally {
            Files.write(stop, new byte[]{1});
            awaitLeaseChild(child);
        }

        DynFontOverrides.pruneCaches(
                cacheRoot, newFingerprint, keep.getFileName().toString());
        assertFalse(Files.exists(active));
    }

    @Test
    void twoJvmsCanHoldSharedLeaseOnSameCache(
            @TempDir Path root) throws Exception {
        String fingerprint = "dddddddddddddddd";
        Path cacheRoot = root.resolve("cache");
        Path target = cacheRoot.resolve("s1.05-" + fingerprint);
        populateCompleteCache(target, fingerprint, (byte) 8);
        Path firstReady = root.resolve("first-lease.ready");
        Path firstStop = root.resolve("first-lease.stop");
        Path secondReady = root.resolve("second-lease.ready");
        Path secondStop = root.resolve("second-lease.stop");
        Process first = startLeaseProcess(
                cacheRoot,
                target,
                fingerprint,
                HD_SCALE,
                firstReady,
                firstStop);
        Process second = null;
        try {
            awaitSignal(firstReady, first);
            second = startLeaseProcess(
                    cacheRoot,
                    target,
                    fingerprint,
                    HD_SCALE,
                    secondReady,
                    secondStop);
            awaitSignal(secondReady, second);
            assertTrue(first.isAlive());
            assertTrue(second.isAlive());
        } finally {
            Files.write(firstStop, new byte[]{1});
            if (second != null) {
                Files.write(secondStop, new byte[]{1});
            }
            awaitLeaseChild(first);
            if (second != null) {
                awaitLeaseChild(second);
            }
        }
    }

    @Test
    void scaleCapEvictsInactiveCacheBeforeActiveCache(
            @TempDir Path root) throws Exception {
        String fingerprint = "cccccccccccccccc";
        Path cacheRoot = root.resolve("cache");
        Path active = cacheRoot.resolve("s1.00-" + fingerprint);
        Path middleOne = cacheRoot.resolve("s1.05-" + fingerprint);
        Path middleTwo = cacheRoot.resolve("s1.10-" + fingerprint);
        Path keep = cacheRoot.resolve("s1.15-" + fingerprint);
        populateCompleteCache(active, fingerprint, (byte) 1, 1.0);
        populateCompleteCache(middleOne, fingerprint, (byte) 2, 1.05);
        populateCompleteCache(middleTwo, fingerprint, (byte) 3, 1.10);
        populateCompleteCache(keep, fingerprint, (byte) 4, 1.15);
        Path ready = root.resolve("scale-lease.ready");
        Path stop = root.resolve("scale-lease.stop");
        Process child = startLeaseProcess(
                cacheRoot, active, fingerprint, 1.0, ready, stop);
        try {
            awaitSignal(ready, child);
            DynFontOverrides.pruneCaches(
                    cacheRoot, fingerprint, keep.getFileName().toString());
            assertTrue(Files.isDirectory(active));
            try (var directories = Files.list(cacheRoot)) {
                assertEquals(3L, directories
                        .filter(path -> Files.isDirectory(
                                path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .count());
            }
        } finally {
            Files.write(stop, new byte[]{1});
            awaitLeaseChild(child);
        }
    }

    @Test
    void claimAndPruneNeverTraverseWindowsJunction(
            @TempDir Path root) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String oldFingerprint = "eeeeeeeeeeeeeeee";
        String currentFingerprint = "ffffffffffffffff";
        Path cacheRoot = root.resolve("cache");
        Path outside = root.resolve("outside-cache-root");
        Path sentinel = outside.resolve("sentinel.txt");
        Files.createDirectories(cacheRoot);
        Files.createDirectories(outside);
        Files.writeString(sentinel, "must-survive");
        Path junction = cacheRoot.resolve("s1.05-" + oldFingerprint);
        createWindowsJunction(junction, outside);
        Path incompleteCache = cacheRoot.resolve("s1.10-" + oldFingerprint);
        Files.createDirectories(incompleteCache);
        Files.writeString(incompleteCache.resolve("local.txt"), "delete-me");
        Path nestedJunction = incompleteCache.resolve("nested-junction");
        createWindowsJunction(nestedJunction, outside);
        assertTrue(Files.isDirectory(junction));

        assertFalse(DynFontOverrides.claimCompleteCacheForTests(
                cacheRoot, junction, oldFingerprint, HD_SCALE));
        DynFontOverrides.pruneCaches(
                cacheRoot,
                currentFingerprint,
                "s1.05-" + currentFingerprint);

        assertFalse(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(
                incompleteCache, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(outside));
        assertEquals("must-survive", Files.readString(sentinel));
    }

    @Test
    void gameContextGateHasExactlyOneConcurrentWinner()
            throws Exception {
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (DynFontOverrides.markGameContextReady()) {
                    winners.incrementAndGet();
                }
            });
            workers.add(worker);
            worker.start();
        }
        ready.await();
        start.countDown();
        for (Thread worker : workers) {
            worker.join(1000);
        }

        assertEquals(1, winners.get());
        assertTrue(DynFontOverrides.isGameContextReady());
    }

    private static Thread publisherThread(
            Path cacheRoot,
            Path source,
            Path target,
            String fingerprint,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                DynFontOverrides.publishGeneratedDirectory(
                        cacheRoot, source, target, fingerprint, HD_SCALE);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private static Process startPublisherProcess(
            Path cacheRoot,
            Path source,
            Path target,
            String fingerprint,
            Path startSignal) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        return new ProcessBuilder(
                java,
                "-Dfile.encoding=UTF-8",
                "-cp",
                classPath,
                DynFontPublishChildMain.class.getName(),
                cacheRoot.toString(),
                source.toString(),
                target.toString(),
                fingerprint,
                Double.toString(HD_SCALE),
                startSignal.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static void createWindowsJunction(Path junction, Path target)
            throws IOException, InterruptedException {
        Process createJunction = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                junction.toString(),
                target.toString())
                .redirectErrorStream(true)
                .start();
        String commandOutput = new String(
                createJunction.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, createJunction.waitFor(), commandOutput);
    }

    private static Process startLeaseProcess(
            Path cacheRoot,
            Path target,
            String fingerprint,
            double scale,
            Path readySignal,
            Path stopSignal) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        return new ProcessBuilder(
                java,
                "-Dfile.encoding=UTF-8",
                "-cp",
                classPath,
                DynFontLeaseChildMain.class.getName(),
                cacheRoot.toString(),
                target.toString(),
                fingerprint,
                Double.toString(scale),
                readySignal.toString(),
                stopSignal.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitSignal(Path signal, Process child)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(signal)) {
            if (!child.isAlive()) {
                awaitLeaseChild(child);
                throw new AssertionError("lease child exited before ready signal");
            }
            if (System.nanoTime() >= deadline) {
                child.destroyForcibly();
                throw new AssertionError("lease child ready signal timed out");
            }
            Thread.sleep(5L);
        }
    }

    private static void awaitLeaseChild(Process process)
            throws IOException, InterruptedException {
        boolean finished = process.waitFor(
                Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("dynfont lease child timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    private static void awaitPublisher(Process process)
            throws IOException, InterruptedException {
        boolean finished = process.waitFor(
                Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("dynfont publisher child timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    private static void populateCompleteCache(
            Path directory, String fingerprint, byte value) throws IOException {
        populateCompleteCache(directory, fingerprint, value, HD_SCALE);
    }

    private static void populateCompleteCache(
            Path directory,
            String fingerprint,
            byte value,
            double scale) throws IOException {
        Files.createDirectories(directory);
        for (String name : DynFontOverrides.expectedOutputNames(scale)) {
            Files.write(directory.resolve(name), new byte[]{value});
        }
        DynFontOverrides.writeCompletionMarker(
                directory, fingerprint, scale);
    }
}

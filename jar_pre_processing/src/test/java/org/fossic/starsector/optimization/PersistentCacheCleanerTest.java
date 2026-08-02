package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PersistentCacheCleanerTest {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidTraversalLimitsUseDefaultAndLargeValuesAreCapped() {
        for (String invalid : List.of(
                "-1", "0", "not-a-number", "9223372036854775808")) {
            System.setProperty(
                    PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY,
                    invalid);
            assertEquals(250_000,
                    PersistentCacheCleaner.configuredMaximumScannedPaths());
        }
        System.setProperty(
                PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY,
                "9999999");
        assertEquals(1_000_000,
                PersistentCacheCleaner.configuredMaximumScannedPaths());
        System.setProperty(
                PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY,
                "7");
        assertEquals(16,
                PersistentCacheCleaner.configuredMaximumScannedPaths());
        System.clearProperty(
                PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY);
    }

    @Test
    void removesExpiredMalformedTemporaryAndObsoleteVersionFiles()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path family = temporaryDirectory.resolve("textures");
        Path current = family.resolve("v2");
        Path active = hashedEntry(
                current, 'a', ".sstexc.zst", 10, now - 90 * DAY_MILLIS);
        Path expired = hashedEntry(
                current, 'b', ".sstexc.zst", 20, now - 31 * DAY_MILLIS);
        Path recent = hashedEntry(
                current, 'c', ".sstexc.zst", 30, now - DAY_MILLIS);
        Path malformed = write(
                current.resolve("de").resolve("not-a-hash.sstexc.zst"),
                7, now);
        String tempHash = "e".repeat(64);
        Path staleTemporary = write(
                current.resolve("ee").resolve(
                        tempHash + ".sstexc.zst.12.tmp"),
                5, now - 2 * DAY_MILLIS);
        Path recentTemporary = write(
                current.resolve("ee").resolve(
                        tempHash + ".sstexc.zst.13.tmp"),
                5, now);
        Path unrelated = write(
                current.resolve("notes.txt"), 3, now - 100 * DAY_MILLIS);
        Path obsolete = write(
                family.resolve("v1").resolve("old.bin"),
                11, now - 100 * DAY_MILLIS);
        Path versionLookalike = write(
                family.resolve("v0"), 1, now - 100 * DAY_MILLIS);
        Path future = write(
                family.resolve("v3").resolve("future.bin"), 13, now);

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "textures",
                        current,
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "",
                        ".sstexc.zst",
                        30 * DAY_MILLIS,
                        Long.MAX_VALUE,
                        Integer.MAX_VALUE,
                        true),
                Set.of(active),
                now);

        assertTrue(Files.exists(active));
        assertEquals(now, Files.getLastModifiedTime(active).toMillis());
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(malformed));
        assertFalse(Files.exists(staleTemporary));
        assertTrue(Files.exists(recentTemporary));
        assertTrue(Files.exists(unrelated));
        assertFalse(Files.exists(obsolete));
        assertTrue(Files.exists(versionLookalike));
        assertTrue(Files.exists(future));
        assertEquals(1, result.expiredFilesDeleted());
        assertEquals(1, result.malformedFilesDeleted());
        assertEquals(1, result.temporaryFilesDeleted());
        assertEquals(1, result.obsoleteVersionDirectoriesDeleted());
        assertEquals(0, result.failures());
    }

    @Test
    void capacityEvictsOldestUntouchedEntriesButNeverCurrentSessionEntries()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("pcm").resolve("v1");
        Path protectedOldest = hashedEntry(
                root, '1', ".sspcm.zst", 60, now - 100 * DAY_MILLIS);
        Path oldestCandidate = hashedEntry(
                root, '2', ".sspcm.zst", 60, now - 3 * DAY_MILLIS);
        Path middleCandidate = hashedEntry(
                root, '3', ".sspcm.zst", 60, now - 2 * DAY_MILLIS);
        Path newestCandidate = hashedEntry(
                root, '4', ".sspcm.zst", 60, now - DAY_MILLIS);

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "pcm",
                        root,
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "",
                        ".sspcm.zst",
                        365 * DAY_MILLIS,
                        120,
                        Integer.MAX_VALUE,
                        false),
                Set.of(protectedOldest),
                now);

        assertTrue(Files.exists(protectedOldest));
        assertFalse(Files.exists(oldestCandidate));
        assertFalse(Files.exists(middleCandidate));
        assertTrue(Files.exists(newestCandidate));
        assertEquals(2, result.capacityFilesDeleted());
        assertEquals(120, result.remainingBytes());
        assertEquals(2, result.remainingEntries());
        assertEquals(0, result.overLimitBytes());
    }

    @Test
    void janinoGenerationLimitRetainsTouchedAndNewestPacks()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("janino").resolve("v1");
        Path protectedOldest = flatEntry(
                root, '5', 10, now - 4 * DAY_MILLIS);
        Path second = flatEntry(root, '6', 10, now - 3 * DAY_MILLIS);
        Path third = flatEntry(root, '7', 10, now - 2 * DAY_MILLIS);
        Path newest = flatEntry(root, '8', 10, now - DAY_MILLIS);

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "janino",
                        root,
                        PersistentCacheCleaner.Layout.FLAT_HASH,
                        "pack-",
                        ".bin",
                        365 * DAY_MILLIS,
                        1024,
                        2,
                        false),
                Set.of(protectedOldest),
                now);

        assertTrue(Files.exists(protectedOldest));
        assertFalse(Files.exists(second));
        assertFalse(Files.exists(third));
        assertTrue(Files.exists(newest));
        assertEquals(2, result.capacityFilesDeleted());
        assertEquals(2, result.remainingEntries());
    }

    @Test
    void softLimitReportsProtectedBytesInsteadOfEvictingThem()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("active").resolve("v1");
        Path first = hashedEntry(root, '9', ".cache", 80, now - DAY_MILLIS);
        Path second = hashedEntry(root, 'a', ".cache", 80, now - DAY_MILLIS);

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "active",
                        root,
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "",
                        ".cache",
                        0,
                        100,
                        Integer.MAX_VALUE,
                        false),
                Set.of(first, second),
                now);

        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertEquals(160, result.remainingBytes());
        assertEquals(60, result.overLimitBytes());
    }

    @Test
    void traversalBudgetBoundsEachBatchAndMakesDestructiveProgress()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("bounded").resolve("v1");
        for (char digit : "0123456789abcdef".toCharArray()) {
            hashedEntry(root, digit, ".cache", 8, now - DAY_MILLIS);
        }

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "bounded",
                        root,
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "",
                        ".cache",
                        365 * DAY_MILLIS,
                        0,
                        0,
                        false),
                Set.of(),
                now,
                5);

        assertTrue(result.traversalLimitReached());
        assertTrue(result.scannedPaths() <= 5);
        assertEquals(0, result.capacityFilesDeleted());
        assertTrue(result.overflowFilesDeleted() > 0);
        try (Stream<Path> paths = Files.walk(root)) {
            assertTrue(paths
                    .filter(Files::isRegularFile)
                    .findAny()
                    .isPresent());
        }
    }

    @Test
    void repeatedBoundedBatchesEventuallyReachTheTreeTail()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("progress").resolve("v1");
        for (char digit : "0123456789abcdef".toCharArray()) {
            hashedEntry(root, digit, ".cache", 8, now - DAY_MILLIS);
        }
        PersistentCacheCleaner.Policy policy =
                new PersistentCacheCleaner.Policy(
                        "progress", root,
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "", ".cache", 365 * DAY_MILLIS,
                        0, 0, false);

        int rounds = 0;
        PersistentCacheCleaner.Result result;
        do {
            result = PersistentCacheCleaner.clean(
                    policy, Set.of(), now, 5);
            rounds++;
        } while (result.traversalLimitReached() && rounds < 100);

        assertFalse(result.traversalLimitReached());
        assertTrue(rounds > 1);
        try (Stream<Path> paths = Files.walk(root)) {
            assertTrue(paths.noneMatch(Files::isRegularFile));
        }
    }

    @Test
    void unrelatedNestedTreeDoesNotConsumeFlatCacheTraversalBudget()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("flat-depth").resolve("v1");
        Path nested = write(
                root.resolve("00-unrelated")
                        .resolve("a").resolve("b").resolve("c")
                        .resolve("notes.bin"),
                8,
                now);
        Path owned = flatEntry(root, 'f', 16, now - DAY_MILLIS);

        PersistentCacheCleaner.Result result = PersistentCacheCleaner.clean(
                new PersistentCacheCleaner.Policy(
                        "flat-depth",
                        root,
                        PersistentCacheCleaner.Layout.FLAT_HASH,
                        "pack-",
                        ".bin",
                        365 * DAY_MILLIS,
                        0,
                        0,
                        false),
                Set.of(),
                now,
                5);

        assertFalse(Files.exists(owned));
        assertTrue(Files.exists(nested));
        assertFalse(result.traversalLimitReached());
        assertTrue(result.scannedPaths() <= 3);
    }

    @Test
    void flatScanNeverTraversesAJunctionCacheRoot()
            throws IOException {
        assumeWindows();
        long now = 2_000_000_000_000L;
        Path external = temporaryDirectory.resolve("flat-external");
        Path sentinel = flatEntry(
                external, 'a', 16, now - DAY_MILLIS);
        Path root = temporaryDirectory.resolve("flat-family").resolve("v1");
        createJunction(root, external);
        try {
            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "flat-junction-root",
                                    root,
                                    PersistentCacheCleaner.Layout.FLAT_HASH,
                                    "pack-",
                                    ".bin",
                                    0,
                                    0,
                                    0,
                                    false),
                            Set.of(),
                            now);

            assertTrue(Files.exists(sentinel));
            assertTrue(Files.exists(
                    root, LinkOption.NOFOLLOW_LINKS));
            assertEquals(0, result.scannedEntries());
        } finally {
            deleteLinkBestEffort(root);
        }
    }

    @Test
    void flatScanNeverTraversesAnIntermediateJunction()
            throws IOException {
        assumeWindows();
        long now = 2_000_000_000_000L;
        Path externalFamily = temporaryDirectory.resolve(
                "intermediate-external-family");
        Path externalRoot = externalFamily.resolve("v1");
        Path sentinel = flatEntry(
                externalRoot, 'c', 16, now - DAY_MILLIS);
        Path familyJunction = temporaryDirectory.resolve(
                "intermediate-family-link");
        Path root = familyJunction.resolve("v1");
        createJunction(familyJunction, externalFamily);
        try {
            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "flat-intermediate-junction",
                                    root,
                                    PersistentCacheCleaner.Layout.FLAT_HASH,
                                    "pack-",
                                    ".bin",
                                    0,
                                    0,
                                    0,
                                    false),
                            Set.of(),
                            now);

            assertTrue(Files.exists(sentinel));
            assertTrue(Files.exists(
                    familyJunction,
                    LinkOption.NOFOLLOW_LINKS));
            assertEquals(0, result.scannedEntries());
        } finally {
            deleteLinkBestEffort(familyJunction);
        }
    }

    @Test
    void hashScanNeverTraversesAJunctionShard()
            throws IOException {
        assumeWindows();
        long now = 2_000_000_000_000L;
        Path root = temporaryDirectory.resolve("hash-family").resolve("v1");
        Files.createDirectories(root);
        Path external = temporaryDirectory.resolve("hash-external");
        String hash = "a".repeat(64);
        Path sentinel = write(
                external.resolve(hash + ".cache"),
                16,
                now - DAY_MILLIS);
        Path shard = root.resolve("aa");
        createJunction(shard, external);
        try {
            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "hash-junction-shard",
                                    root,
                                    PersistentCacheCleaner.Layout.HASH_SHARDED,
                                    "",
                                    ".cache",
                                    0,
                                    0,
                                    0,
                                    false),
                            Set.of(),
                            now);

            assertTrue(Files.exists(sentinel));
            assertTrue(Files.exists(
                    shard, LinkOption.NOFOLLOW_LINKS));
            assertEquals(0, result.scannedEntries());
        } finally {
            deleteLinkBestEffort(shard);
        }
    }

    @Test
    void protectedEntryThroughAJunctionIsNotTouchedOrScanned()
            throws IOException {
        assumeWindows();
        long now = 2_000_000_000_000L;
        long originalModified = now - 7 * DAY_MILLIS;
        Path root = temporaryDirectory.resolve(
                "protected-junction-family").resolve("v1");
        Files.createDirectories(root);
        Path external = temporaryDirectory.resolve(
                "protected-junction-external");
        String hash = "b".repeat(64);
        String name = hash + ".cache";
        Path sentinel = write(
                external.resolve(name), 16, originalModified);
        Path shard = root.resolve("bb");
        createJunction(shard, external);
        try {
            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "protected-junction",
                                    root,
                                    PersistentCacheCleaner.Layout.HASH_SHARDED,
                                    "",
                                    ".cache",
                                    0,
                                    0,
                                    0,
                                    false),
                            Set.of(shard.resolve(name)),
                            now);

            assertTrue(Files.exists(sentinel));
            assertEquals(
                    originalModified,
                    Files.getLastModifiedTime(sentinel).toMillis());
            assertEquals(0, result.touchedFiles());
            assertEquals(0, result.scannedEntries());
        } finally {
            deleteLinkBestEffort(shard);
        }
    }

    @Test
    void obsoleteVersionCleanupDeletesOnlyJunctionsNotTheirTargets()
            throws IOException {
        assumeWindows();
        long now = 2_000_000_000_000L;
        Path family = temporaryDirectory.resolve("obsolete-junctions");
        Path current = family.resolve("v2");
        Files.createDirectories(current);

        Path externalRoot = temporaryDirectory.resolve("external-root");
        Path rootSentinel = write(
                externalRoot.resolve("root-sentinel.bin"),
                11,
                now);
        Path obsoleteRootJunction = family.resolve("v0");
        Path obsoleteTree = family.resolve("v1");
        Path nestedJunction = obsoleteTree.resolve("nested");
        createJunction(obsoleteRootJunction, externalRoot);
        try {
            write(obsoleteTree.resolve("owned.bin"), 7, now);
            Path externalNested = temporaryDirectory.resolve(
                    "external-nested");
            Path nestedSentinel = write(
                    externalNested.resolve("nested-sentinel.bin"),
                    13,
                    now);
            createJunction(nestedJunction, externalNested);

            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "obsolete-junctions",
                                    current,
                                    PersistentCacheCleaner.Layout.FLAT_HASH,
                                    "pack-",
                                    ".bin",
                                    365 * DAY_MILLIS,
                                    Long.MAX_VALUE,
                                    Integer.MAX_VALUE,
                                    true),
                            Set.of(),
                            now);

            assertTrue(Files.exists(rootSentinel));
            assertTrue(Files.exists(nestedSentinel));
            assertFalse(Files.exists(
                    obsoleteRootJunction,
                    LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(
                    obsoleteTree, LinkOption.NOFOLLOW_LINKS));
            assertEquals(
                    2,
                    result.obsoleteVersionDirectoriesDeleted());
        } finally {
            deleteLinkBestEffort(nestedJunction);
            deleteLinkBestEffort(obsoleteRootJunction);
        }
    }

    @Test
    void obsoleteVersionSymlinkRootDeletesOnlyTheLink()
            throws IOException {
        long now = 2_000_000_000_000L;
        Path family = temporaryDirectory.resolve("obsolete-symlink");
        Path current = family.resolve("v2");
        Files.createDirectories(current);
        Path external = temporaryDirectory.resolve("symlink-external");
        Path sentinel = write(
                external.resolve("sentinel.bin"), 9, now);
        Path obsolete = family.resolve("v1");
        try {
            Files.createSymbolicLink(obsolete, external);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, () -> "symbolic links unavailable: "
                    + unavailable.getMessage());
        }
        try {
            PersistentCacheCleaner.Result result =
                    PersistentCacheCleaner.clean(
                            new PersistentCacheCleaner.Policy(
                                    "obsolete-symlink",
                                    current,
                                    PersistentCacheCleaner.Layout.FLAT_HASH,
                                    "pack-",
                                    ".bin",
                                    365 * DAY_MILLIS,
                                    Long.MAX_VALUE,
                                    Integer.MAX_VALUE,
                                    true),
                            Set.of(),
                            now);

            assertTrue(Files.exists(sentinel));
            assertFalse(Files.exists(
                    obsolete, LinkOption.NOFOLLOW_LINKS));
            assertEquals(
                    1,
                    result.obsoleteVersionDirectoriesDeleted());
        } finally {
            deleteLinkBestEffort(obsolete);
        }
    }

    private Path hashedEntry(
            Path root,
            char digit,
            String suffix,
            int bytes,
            long modified) throws IOException {
        String hash = String.valueOf(digit).repeat(64);
        return write(
                root.resolve(hash.substring(0, 2)).resolve(hash + suffix),
                bytes,
                modified);
    }

    private Path flatEntry(
            Path root, char digit, int bytes, long modified)
            throws IOException {
        String hash = String.valueOf(digit).repeat(64);
        return write(
                root.resolve("pack-" + hash + ".bin"),
                bytes,
                modified);
    }

    private Path write(Path path, int bytes, long modified)
            throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[bytes]);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        return path.toAbsolutePath().normalize();
    }

    private static void assumeWindows() {
        assumeTrue(
                System.getProperty("os.name", "").startsWith("Windows"),
                "requires a real Windows directory junction");
    }

    private static void createJunction(Path link, Path target)
            throws IOException {
        Files.createDirectories(link.getParent());
        Files.createDirectories(target);
        boolean completed = false;
        try {
            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    link.toString(),
                    target.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "Interrupted while creating junction", interrupted);
            }
            assertEquals(
                    0,
                    exitCode,
                    () -> "mklink /J failed: "
                            + new String(
                                    output, StandardCharsets.UTF_8));
            assertTrue(Files.exists(
                    link, LinkOption.NOFOLLOW_LINKS));
            completed = true;
        } finally {
            if (!completed) {
                deleteLinkBestEffort(link);
            }
        }
    }

    private static void deleteLinkBestEffort(Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException | RuntimeException ignored) {
            // 测试清理不得因一个 link 失败而跳过其它 link。
        }
    }
}

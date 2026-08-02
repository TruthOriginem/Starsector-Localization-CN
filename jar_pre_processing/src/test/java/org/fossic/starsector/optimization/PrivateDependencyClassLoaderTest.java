package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.matthiasmann.twl.utils.PNGDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PrivateDependencyClassLoaderTest {
    @Test
    void pngProviderUsesAClassDistinctFromParentClasspathDependency()
            throws Exception {
        PrivatePngCodec codec = PrivateDependencyClassLoader.loadProvider(
                "META-INF/starsector-optimization/private/png/",
                "org.fossic.starsector.privateimpl.png.TwlPngProvider",
                PrivatePngCodec.class,
                new PrivateDependencyClassLoader.OwnedPackage(
                        "org.fossic.starsector.privateimpl.png.",
                        "org.fossic.starsector.privateimpl.png."
                                + "TwlPngProvider"),
                new PrivateDependencyClassLoader.OwnedPackage(
                        "de.matthiasmann.twl.utils.",
                        "de.matthiasmann.twl.utils.PNGDecoder"));
        ClassLoader isolated = codec.getClass().getClassLoader();
        Class<?> privateDecoder = isolated.loadClass(
                "de.matthiasmann.twl.utils.PNGDecoder");

        assertTrue(isolated instanceof PrivateDependencyClassLoader);
        assertNotSame(PNGDecoder.class, privateDecoder);
        assertNotSame(PNGDecoder.class.getClassLoader(),
                privateDecoder.getClassLoader());
        // 构造一次，证明私有类的关联 Format/匿名类也由同一 loader 解析。
        byte[] malformed = {1, 2, 3};
        assertThrows(java.io.IOException.class,
                () -> codec.decode(malformed));
    }

    @Test
    void topLevelAndIsolatedZstdCanRunInTheSameChildJvm(
            @TempDir Path tempDir) throws Exception {
        Path nativeRoot = tempDir.resolve("stable");
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);

        runChild(nativeRoot, processTemp, "top-first");

        try (var files = Files.list(nativeRoot)) {
            assertTrue(files.filter(path -> path.getFileName().toString()
                            .startsWith("lib-"))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".dll"))
                    .count() == 1L);
        }
    }

    @Test
    void repeatedJvmStartsReuseOneStableNativeWithoutRandomTempDlls(
            @TempDir Path tempDir) throws Exception {
        Path stable = tempDir.resolve("stable");
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);

        runChild(stable, processTemp, "isolated-only");
        runChild(stable, processTemp, "isolated-only");

        try (var files = Files.list(stable)) {
            List<Path> libraries = files
                    .filter(path -> path.getFileName().toString()
                            .matches("lib-[0-9a-f]{64}\\.dll"))
                    .toList();
            assertTrue(libraries.size() == 1,
                    () -> "unexpected stable native files: " + libraries);
        }
        try (var files = Files.list(processTemp)) {
            List<Path> randomLibraries = files
                    .filter(path -> path.getFileName().toString()
                            .startsWith("libzstd-jni-"))
                    .toList();
            assertTrue(randomLibraries.isEmpty(),
                    () -> "zstd-jni extracted random files: "
                            + randomLibraries);
        }
    }

    @Test
    void concurrentJvmStartsPublishOneStableNative(
            @TempDir Path tempDir) throws Exception {
        Path stable = tempDir.resolve("stable");
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);

        Process first = startChild(
                stable, processTemp, "isolated-only");
        Process second = startChild(
                stable, processTemp, "isolated-only");
        try {
            awaitChild(first);
            awaitChild(second);
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }

        try (var files = Files.list(stable)) {
            List<Path> libraries = files
                    .filter(path -> path.getFileName().toString()
                            .matches("lib-[0-9a-f]{64}\\.dll"))
                    .toList();
            assertEquals(1, libraries.size(),
                    () -> "unexpected stable native files: " + libraries);
        }
    }

    @Test
    void secondPrivateZstdLoaderFailsClosedWithDiagnostic(
            @TempDir Path tempDir) throws Exception {
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);

        runChild(
                tempDir.resolve("stable"),
                processTemp,
                "two-isolated-loaders");
    }

    @Test
    void corruptStableNativeIsReplacedWithEmbeddedBytes(
            @TempDir Path tempDir) throws Exception {
        String oldConfigured = System.getProperty(
                StableZstdNativeLibrary.DIRECTORY_PROPERTY);
        try {
            PersistentCacheMaintenance.resetForTests();
            System.setProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    tempDir.resolve("stable").toString());
            byte[] expected;
            try (var input = getClass().getClassLoader()
                    .getResourceAsStream(
                            "win/amd64/libzstd-jni-1.5.7-4.dll")) {
                assertTrue(input != null);
                expected = input.readAllBytes();
            }
            Path first = prepareStableNative();
            Files.write(
                    first,
                    new byte[]{1, 2, 3},
                    StandardOpenOption.TRUNCATE_EXISTING);

            Path repaired = prepareStableNative();

            assertEquals(first, repaired);
            assertArrayEquals(expected, Files.readAllBytes(repaired));
        } finally {
            PersistentCacheMaintenance.resetForTests();
            restoreProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    oldConfigured);
        }
    }

    @Test
    void reusingStableNativeRefreshesItsModificationTimeUnderLock(
            @TempDir Path tempDir) throws Exception {
        String oldConfigured = System.getProperty(
                StableZstdNativeLibrary.DIRECTORY_PROPERTY);
        try {
            PersistentCacheMaintenance.resetForTests();
            System.setProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    tempDir.resolve("stable").toString());
            Path first = prepareStableNative();
            long oldTime = System.currentTimeMillis()
                    - TimeUnit.DAYS.toMillis(7);
            Files.setLastModifiedTime(first, FileTime.fromMillis(oldTime));

            Path reused = prepareStableNative();

            assertEquals(first, reused);
            assertTrue(Files.getLastModifiedTime(reused).toMillis()
                    > oldTime);
        } finally {
            PersistentCacheMaintenance.resetForTests();
            restoreProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    oldConfigured);
        }
    }

    @Test
    void zstdCleanerEvictsOldestThirdHashButPreservesLockAndUnknownFiles(
            @TempDir Path tempDir) throws Exception {
        String oldConfigured = System.getProperty(
                StableZstdNativeLibrary.DIRECTORY_PROPERTY);
        String oldRetention = System.getProperty(
                PersistentCacheMaintenance.RETENTION_DAYS_PROPERTY);
        try {
            PersistentCacheMaintenance.resetForTests();
            Path root = tempDir.resolve("stable");
            System.setProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    root.toString());
            System.setProperty(
                    PersistentCacheMaintenance.RETENTION_DAYS_PROPERTY,
                    "3650");
            Path active = prepareStableNative();
            Path oldest = root.resolve(
                    "lib-" + "0".repeat(64) + ".dll");
            Path newer = root.resolve(
                    "lib-" + "1".repeat(64) + ".dll");
            Files.write(oldest, new byte[]{0});
            Files.write(newer, new byte[]{1});
            long now = System.currentTimeMillis();
            Files.setLastModifiedTime(
                    oldest,
                    FileTime.fromMillis(
                            now - TimeUnit.DAYS.toMillis(20)));
            Files.setLastModifiedTime(
                    newer,
                    FileTime.fromMillis(
                            now - TimeUnit.DAYS.toMillis(10)));
            Path lock = root.resolve(".publish.lock");
            Path unknown = root.resolve("keep-me.txt");
            Files.writeString(unknown, "future format", StandardCharsets.UTF_8);

            PersistentCacheMaintenance.cleanNowForTests(now);

            assertTrue(Files.exists(active));
            assertFalse(Files.exists(oldest));
            assertTrue(Files.exists(newer));
            assertTrue(Files.exists(lock));
            assertTrue(Files.exists(unknown));
        } finally {
            PersistentCacheMaintenance.resetForTests();
            restoreProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    oldConfigured);
            restoreProperty(
                    PersistentCacheMaintenance.RETENTION_DAYS_PROPERTY,
                    oldRetention);
        }
    }

    @Test
    void invalidConfiguredNativeDirectoryFallsBackToStableTempCache(
            @TempDir Path tempDir) {
        String oldConfigured = System.getProperty(
                StableZstdNativeLibrary.DIRECTORY_PROPERTY);
        String oldTemp = System.getProperty("java.io.tmpdir");
        try {
            System.setProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    "invalid\0path");
            System.setProperty("java.io.tmpdir", tempDir.toString());

            Path prepared = StableZstdNativeLibrary.prepare(
                    PrivateDependencyClassLoaderTest.class.getClassLoader(),
                    "win/amd64/libzstd-jni-1.5.7-4.dll");

            Path expectedRoot = tempDir.resolve(
                            "starsector-startup-optimization")
                    .resolve("zstd-native")
                    .resolve("v1")
                    .toAbsolutePath()
                    .normalize();
            assertTrue(prepared.getParent().equals(expectedRoot));
            assertTrue(Files.isRegularFile(prepared));
        } finally {
            restoreProperty(
                    StableZstdNativeLibrary.DIRECTORY_PROPERTY,
                    oldConfigured);
            restoreProperty("java.io.tmpdir", oldTemp);
        }
    }

    private static void runChild(
            Path stable, Path processTemp, String mode)
            throws IOException, InterruptedException {
        awaitChild(startChild(stable, processTemp, mode));
    }

    private static Process startChild(
            Path stable, Path processTemp, String mode)
            throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        return new ProcessBuilder(
                java,
                "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=" + processTemp,
                "-D" + StableZstdNativeLibrary.DIRECTORY_PROPERTY
                        + "=" + stable,
                "-cp",
                classPath,
                PrivateDependencyChildMain.class.getName(),
                mode)
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitChild(Process process)
            throws IOException, InterruptedException {
        boolean finished = process.waitFor(
                Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("private zstd child timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(process.exitValue() == 0,
                () -> "private zstd child failed: " + output);
    }

    private static Path prepareStableNative() {
        return StableZstdNativeLibrary.prepare(
                PrivateDependencyClassLoaderTest.class.getClassLoader(),
                "win/amd64/libzstd-jni-1.5.7-4.dll");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

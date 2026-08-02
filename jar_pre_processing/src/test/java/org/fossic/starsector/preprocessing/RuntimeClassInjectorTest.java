package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeClassInjectorTest {
    @Test
    void dynfontRuntimeFollowsItsIndependentPatchGroup(
            @TempDir Path tempDir) throws IOException {
        Path disabledJar = emptyJar(tempDir.resolve("dynfont-disabled.jar"));
        Map<String, Integer> disabledCounts = new java.util.LinkedHashMap<>();
        JarPreProcessorMain.injectDynFontRuntime(
                disabledJar,
                PatchSelection.fromOptions(
                        "none", List.of("dynfont"), false),
                disabledCounts);

        assertFalse(disabledCounts.containsKey("dynfont"));
        try (ZipFile actual = new ZipFile(disabledJar.toFile())) {
            assertNull(actual.getEntry(
                    "org/fossic/starsector/dynfont/DynFontOverrides.class"));
            assertNull(actual.getEntry(
                    "org/fossic/starsector/dynfont/DynFontQuadHooks.class"));
        }

        Path enabledJar = emptyJar(tempDir.resolve("dynfont-enabled.jar"));
        Map<String, Integer> enabledCounts = new java.util.LinkedHashMap<>();
        JarPreProcessorMain.injectDynFontRuntime(
                enabledJar,
                PatchSelection.fromOptions(
                        "none", List.of(), false),
                enabledCounts);

        assertTrue(enabledCounts.get("dynfont") > 0);
        try (ZipFile actual = new ZipFile(enabledJar.toFile())) {
            assertNotNull(actual.getEntry(
                    "org/fossic/starsector/dynfont/DynFontOverrides.class"));
            assertNotNull(actual.getEntry(
                    "org/fossic/starsector/dynfont/DynFontQuadHooks.class"));
        }
    }

    @Test
    void profilingRuntimeFollowsItsIndependentPatchGroup(
            @TempDir Path tempDir) throws IOException {
        Path releaseJar = emptyJar(tempDir.resolve("release.jar"));
        Map<String, Integer> releaseCounts = new java.util.LinkedHashMap<>();
        JarPreProcessorMain.injectStartupProfilerRuntime(
                releaseJar,
                PatchSelection.fromOptions(
                        "none", List.of(), false),
                releaseCounts);

        assertFalse(releaseCounts.containsKey("startupProfiler"));
        try (ZipFile actual = new ZipFile(releaseJar.toFile())) {
            assertNull(actual.getEntry(
                    "org/fossic/starsector/startup/StartupProfiler.class"));
        }

        Path profilingJar = emptyJar(tempDir.resolve("profiling.jar"));
        Map<String, Integer> profilingCounts = new java.util.LinkedHashMap<>();
        JarPreProcessorMain.injectStartupProfilerRuntime(
                profilingJar,
                PatchSelection.fromOptions(
                        "none", List.of(), true),
                profilingCounts);

        assertTrue(profilingCounts.get("startupProfiler") > 0);
        try (ZipFile actual = new ZipFile(profilingJar.toFile())) {
            assertNotNull(actual.getEntry(
                    "org/fossic/starsector/startup/StartupProfiler.class"));
        }
    }

    @Test
    void injectsPngDependencyOnlyUnderPrivateResourceRoot(
            @TempDir Path tempDir) throws IOException {
        Path jar = emptyJar(tempDir.resolve("game.jar"));

        int count = new RuntimeClassInjector(
                "de/matthiasmann/twl/utils/", "PNGDecoder.class")
                .injectPrivatelyInto(
                        jar,
                        "META-INF/starsector-optimization/private/png/",
                        new String[0],
                        "META-INF/LICENSE-pngdecoder.txt");

        assertEquals(3, count);
        try (ZipFile actual = new ZipFile(jar.toFile())) {
            assertNotNull(actual.getEntry("original.txt"));
            assertNull(actual.getEntry(
                    "de/matthiasmann/twl/utils/PNGDecoder.class"));
            assertNotNull(actual.getEntry(
                    "META-INF/starsector-optimization/private/png/"
                            + "de/matthiasmann/twl/utils/PNGDecoder.class"));
            assertNotNull(actual.getEntry(
                    "META-INF/starsector-optimization/private/png/"
                            + "de/matthiasmann/twl/utils/PNGDecoder$Format.class"));
            assertNotNull(actual.getEntry(
                    "META-INF/LICENSE-pngdecoder.txt"));
        }
    }

    @Test
    void injectsZstdClassesAndNativeOnlyUnderPrivateResourceRoot(
            @TempDir Path tempDir) throws IOException {
        Path jar = emptyJar(tempDir.resolve("game.jar"));

        int count = new RuntimeClassInjector(
                "com/github/luben/zstd/", "Zstd.class")
                .injectPrivatelyInto(
                        jar,
                        "META-INF/starsector-optimization/private/zstd/",
                        new String[]{
                            "win/amd64/libzstd-jni-1.5.7-4.dll"
                        },
                        "META-INF/LICENSE-zstd-jni.txt");

        assertEquals(37, count);
        try (ZipFile actual = new ZipFile(jar.toFile())) {
            assertNull(actual.getEntry(
                    "com/github/luben/zstd/Zstd.class"));
            assertNotNull(actual.getEntry(
                    "META-INF/starsector-optimization/private/zstd/"
                            + "com/github/luben/zstd/Zstd.class"));
            assertNotNull(actual.getEntry(
                    "META-INF/starsector-optimization/private/zstd/"
                            + "com/github/luben/zstd/util/Native.class"));
            assertNull(actual.getEntry(
                    "win/amd64/libzstd-jni-1.5.7-4.dll"));
            assertNotNull(actual.getEntry(
                    "META-INF/starsector-optimization/private/zstd/"
                            + "win/amd64/libzstd-jni-1.5.7-4.dll"));
            assertNotNull(actual.getEntry(
                    "META-INF/LICENSE-zstd-jni.txt"));
        }
    }

    @Test
    void privateResourceCannotOverwritePrivateClass(
            @TempDir Path tempDir) throws IOException {
        Path jar = emptyJar(tempDir.resolve("collision.jar"));

        PatchException failure = assertThrows(
                PatchException.class,
                () -> new RuntimeClassInjector(
                        "de/matthiasmann/twl/utils/",
                        "PNGDecoder.class")
                        .injectPrivatelyInto(
                                jar,
                                "META-INF/starsector-optimization/private/png/",
                                new String[]{
                                    "de/matthiasmann/twl/utils/"
                                            + "PNGDecoder.class"
                                }));

        assertTrue(failure.getMessage().contains("与 class 重名"));
        try (ZipFile unchanged = new ZipFile(jar.toFile())) {
            assertNotNull(unchanged.getEntry("original.txt"));
            assertNull(unchanged.getEntry(
                    "META-INF/starsector-optimization/private/png/"
                            + "de/matthiasmann/twl/utils/PNGDecoder.class"));
        }
    }

    @Test
    void dependencyResourcesFollowIndependentPatchGroups(
            @TempDir Path tempDir) throws IOException {
        Path none = emptyJar(tempDir.resolve("none.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                none,
                PatchSelection.fromOptions("none", List.of(), false));
        assertDependencyPresence(none, false, false);

        Path png = emptyJar(tempDir.resolve("png.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                png,
                PatchSelection.fromOptions("fast-png", List.of(), false));
        assertDependencyPresence(png, true, false);

        Path zstd = emptyJar(tempDir.resolve("zstd.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                zstd,
                PatchSelection.fromOptions("pcm-cache", List.of(), false));
        assertDependencyPresence(zstd, false, true);

        Path texture = emptyJar(tempDir.resolve("texture.jar"));
        PatchSelection textureSelection = PatchSelection.fromOptions(
                "texture-cache", List.of(), false);
        assertTrue(textureSelection.enabled(PatchGroup.TEXTURE_CACHE));
        assertTrue(textureSelection.enabled(PatchGroup.FAST_PNG));
        JarPreProcessorMain.injectRuntimeClasses(
                texture, textureSelection);
        assertDependencyPresence(texture, true, true);

        Path textureWithoutPng = emptyJar(
                tempDir.resolve("texture-without-png.jar"));
        PatchSelection reverseDisabledTexture = PatchSelection.fromOptions(
                "texture-cache", List.of("fast-png"), false);
        assertFalse(reverseDisabledTexture.enabled(PatchGroup.FAST_PNG));
        assertFalse(reverseDisabledTexture.enabled(PatchGroup.TEXTURE_CACHE));
        JarPreProcessorMain.injectRuntimeClasses(
                textureWithoutPng, reverseDisabledTexture);
        assertDependencyPresence(textureWithoutPng, false, false);

        Path allWithoutPng = emptyJar(
                tempDir.resolve("all-without-png.jar"));
        PatchSelection allReverseDisabled = PatchSelection.fromOptions(
                "all", List.of("fast-png"), false);
        assertFalse(allReverseDisabled.enabled(PatchGroup.TEXTURE_CACHE));
        assertTrue(allReverseDisabled.enabled(PatchGroup.PCM_CACHE));
        JarPreProcessorMain.injectRuntimeClasses(
                allWithoutPng, allReverseDisabled);
        assertDependencyPresence(allWithoutPng, false, true);

        Path pngWithoutCaches = emptyJar(
                tempDir.resolve("png-without-caches.jar"));
        PatchSelection independentPng = PatchSelection.fromOptions(
                "all", List.of("texture-cache", "pcm-cache"), false);
        assertTrue(independentPng.enabled(PatchGroup.FAST_PNG));
        assertFalse(independentPng.enabled(PatchGroup.TEXTURE_CACHE));
        assertFalse(independentPng.enabled(PatchGroup.PCM_CACHE));
        JarPreProcessorMain.injectRuntimeClasses(
                pngWithoutCaches, independentPng);
        assertDependencyPresence(pngWithoutCaches, true, false);
    }

    @Test
    void packagedPngProviderLoadsOnlyFromPrivateResourceTree(
            @TempDir Path tempDir) throws Exception {
        Path jar = emptyJar(tempDir.resolve("private-png.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                jar,
                PatchSelection.fromOptions(
                        "fast-png", List.of(), false));

        try (URLClassLoader runtime = new URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class,
                    () -> runtime.loadClass(
                            "de.matthiasmann.twl.utils.PNGDecoder"));
            Class<?> decoder = runtime.loadClass(
                    "org.fossic.starsector.optimization.TwlPngDecoder");
            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> decoder.getMethod("decode", byte[].class)
                            .invoke(null, (Object) new byte[]{1, 2, 3}));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    @Test
    void packagedPngIgnoresShadowPrivateTreeOutsideItsDefiningJar(
            @TempDir Path tempDir) throws Exception {
        Path game = emptyJar(tempDir.resolve("private-png.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                game,
                PatchSelection.fromOptions(
                        "fast-png", List.of(), false));
        Path hostile = singleEntryJar(
                tempDir.resolve("hostile.jar"),
                "META-INF/starsector-optimization/private/png/"
                        + "de/matthiasmann/twl/utils/PNGDecoder.class",
                new byte[]{0});

        // hostile 排在 URL 搜索顺序之前；loader 仍必须固定到定义自身的 game jar。
        try (URLClassLoader runtime = new URLClassLoader(
                new java.net.URL[]{
                    hostile.toUri().toURL(), game.toUri().toURL()
                },
                ClassLoader.getPlatformClassLoader())) {
            Class<?> decoder = runtime.loadClass(
                    "org.fossic.starsector.optimization.TwlPngDecoder");
            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> decoder.getMethod("decode", byte[].class)
                            .invoke(null, (Object) new byte[]{1, 2, 3}));
            assertTrue(failure.getCause() instanceof IOException,
                    () -> "unexpected shadow-resource failure: "
                            + failure.getCause());
        }
    }

    @Test
    void packagedZstdProviderLoadsOnlyFromPrivateResourceTree(
            @TempDir Path tempDir) throws Exception {
        Path jar = emptyJar(tempDir.resolve("private-zstd.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                jar,
                PatchSelection.fromOptions(
                        "pcm-cache", List.of(), false));
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);
        runPackagedZstdChild(
                jar,
                tempDir.resolve("native-cache"),
                processTemp,
                null,
                false);
    }

    @Test
    void packagedZstdFailsClosedWhenNativeExistsOnlyInAnotherJar(
            @TempDir Path tempDir) throws Exception {
        String nativeName = "win/amd64/libzstd-jni-1.5.7-4.dll";
        String privateNative =
                "META-INF/starsector-optimization/private/zstd/"
                        + nativeName;
        Path complete = emptyJar(tempDir.resolve("complete-zstd.jar"));
        JarPreProcessorMain.injectRuntimeClasses(
                complete,
                PatchSelection.fromOptions(
                        "pcm-cache", List.of(), false));
        Path missingNative = copyJarWithout(
                complete,
                tempDir.resolve("missing-native.jar"),
                privateNative);
        byte[] nativeBytes;
        try (var input = RuntimeClassInjectorTest.class.getClassLoader()
                .getResourceAsStream(nativeName)) {
            assertNotNull(input);
            nativeBytes = input.readAllBytes();
        }
        Path hostile = singleEntryJar(
                tempDir.resolve("hostile-native.jar"),
                privateNative,
                nativeBytes);
        Path processTemp = tempDir.resolve("process-temp");
        Files.createDirectories(processTemp);

        runPackagedZstdChild(
                missingNative,
                tempDir.resolve("native-cache"),
                processTemp,
                hostile,
                true);
    }

    private static void assertDependencyPresence(
            Path jar, boolean png, boolean zstd) throws IOException {
        try (ZipFile actual = new ZipFile(jar.toFile())) {
            assertEquals(png, actual.getEntry(
                    "META-INF/starsector-optimization/private/png/"
                            + "de/matthiasmann/twl/utils/PNGDecoder.class")
                    != null);
            assertEquals(zstd, actual.getEntry(
                    "META-INF/starsector-optimization/private/zstd/"
                            + "com/github/luben/zstd/Zstd.class") != null);
            assertNull(actual.getEntry(
                    "de/matthiasmann/twl/utils/PNGDecoder.class"));
            assertNull(actual.getEntry(
                    "com/github/luben/zstd/Zstd.class"));
            assertNull(actual.getEntry(
                    "win/amd64/libzstd-jni-1.5.7-4.dll"));
        }
    }

    private static void runPackagedZstdChild(
            Path jar,
            Path nativeCache,
            Path processTemp,
            Path precedingJar,
            boolean expectUnavailable)
            throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new java.util.ArrayList<>(List.of(
                java,
                "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=" + processTemp,
                "-Dstarsector.optimization.zstdNativeDirectory="
                        + nativeCache,
                "-cp",
                classPath,
                PackagedPrivateDependencyChildMain.class.getName(),
                jar.toString()));
        if (precedingJar != null) {
            command.add(precedingJar.toString());
            command.add(expectUnavailable
                    ? "expect-unavailable" : "expect-available");
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("packaged zstd child timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    private static Path emptyJar(Path jar) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("original.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        return jar;
    }

    private static Path singleEntryJar(
            Path jar, String name, byte[] contents) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(name));
            output.write(contents);
            output.closeEntry();
        }
        return jar;
    }

    private static Path copyJarWithout(
            Path source, Path target, String removedEntry)
            throws IOException {
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(
                     Files.newOutputStream(target))) {
            for (ZipEntry original : input.stream().toList()) {
                if (original.getName().equals(removedEntry)) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(original.getName());
                copy.setTime(original.getTime());
                output.putNextEntry(copy);
                try (var contents = input.getInputStream(original)) {
                    contents.transferTo(output);
                }
                output.closeEntry();
            }
        }
        return target;
    }
}

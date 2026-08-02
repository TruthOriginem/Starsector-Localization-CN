package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CachingIndexedDeduplicatingJavaSourceClassLoaderTest {
    @TempDir
    Path cacheDirectory;

    @BeforeEach
    void resetDiagnostics() {
        PersistentCacheMaintenance.resetForTests();
        JaninoBytecodeCacheDiagnostics.resetForTests();
        JaninoSourceIndexDiagnostics.resetForTests();
    }

    @AfterEach
    void resetMaintenance() {
        PersistentCacheMaintenance.resetForTests();
    }

    @Test
    void fillsThenLoadsTheSameClassFromThePack() throws Exception {
        Map<String, String> sources = Map.of(
                "sample/Example.java", sourceReturning(41));
        CountingFinder firstFinder = new CountingFinder(sources);
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                firstFinder, "same-environment", true);

        Class<?> firstClass = first.loadClass("sample.Example");
        assertEquals(41, value(firstClass));
        first.finishCacheForTests();
        Path pack = first.cachePathForTests();
        assertTrue(Files.isRegularFile(pack));

        JaninoBytecodeCacheDiagnostics.resetForTests();
        CountingFinder secondFinder = new CountingFinder(sources);
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                secondFinder, "same-environment", true);
        Class<?> secondClass = second.loadClass("sample.Example");

        assertNotSame(firstClass, secondClass);
        assertEquals(41, value(secondClass));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"validPacks\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"generatedClasses\":0"));
        assertTrue(secondFinder.lookupCount.get() > 0,
                "warm hit must still validate the complete source graph");
        assertEquals(java.util.Set.of("janino"),
                PersistentCacheMaintenance.registeredNamespacesForTests());
    }

    @Test
    void sourceContentChangeInvalidatesEvenWithTheSameLength()
            throws Exception {
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(Map.of(
                        "sample/Example.java", sourceReturning(1))),
                "same-environment", true);
        first.loadClass("sample.Example");
        first.finishCacheForTests();

        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(Map.of(
                        "sample/Example.java", sourceReturning(2))),
                "same-environment", true);
        Class<?> changed = second.loadClass("sample.Example");

        assertEquals(2, value(changed));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"sourceValidationFailures\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":0"));
    }

    @Test
    void aPreviouslyMissingSourceInvalidatesTheWholeGeneration()
            throws Exception {
        Map<String, String> original = Map.of(
                "sample/Example.java", sourceReturning(1));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(original), "same-environment", true);
        first.loadClass("sample.Example");
        assertEquals(null, first.sourceIndexForTests().findResource(
                "sample/Added.java"));
        first.finishCacheForTests();

        Map<String, String> changed = new LinkedHashMap<>(original);
        changed.put("sample/Added.java",
                "package sample; public class Added {}");
        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(changed), "same-environment", true);
        second.loadClass("sample.Example");

        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"sourceValidationFailures\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":0"));
    }

    @Test
    void mixedHitAndMissCompilesAgainstCachedClassMetadata()
            throws Exception {
        Map<String, String> firstSources = Map.of(
                "sample/A.java", """
                        package sample;
                        public class A { public int value() { return 7; } }
                        """);
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(firstSources), "same-environment", true);
        first.loadClass("sample.A");
        first.finishCacheForTests();

        Map<String, String> expanded = new LinkedHashMap<>(firstSources);
        expanded.put("sample/B.java", """
                package sample;
                public class B {
                    public A create() { return new A(); }
                }
                """);
        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(expanded), "same-environment", true);
        Class<?> cachedA = second.loadClass("sample.A");
        Class<?> liveB = second.loadClass("sample.B");
        Object b = liveB.getDeclaredConstructor().newInstance();
        Object a = liveB.getMethod("create").invoke(b);

        assertSame(cachedA, a.getClass());
        assertEquals(7, cachedA.getMethod("value").invoke(a));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classMisses\":1"));
        second.finishCacheForTests();

        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader third = loader(
                new CountingFinder(expanded), "same-environment", true);
        assertEquals("sample.B", third.loadClass("sample.B").getName());
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":1"));
    }

    @Test
    void warmExtensionPublishesValidatedAndLiveSourceGraphs()
            throws Exception {
        Map<String, String> initial = Map.of(
                "sample/A.java", """
                        package sample;
                        public class A { public int value() { return 7; } }
                        """);
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(initial), "same-environment", true);
        first.loadClass("sample.A");
        first.finishCacheForTests();

        Map<String, String> expanded = new LinkedHashMap<>(initial);
        expanded.put("sample/B.java", """
                package sample;
                public class B {
                    public A create() { return new A(); }
                }
                """);
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(expanded), "same-environment", true);
        second.loadClass("sample.A");
        second.loadClass("sample.B");
        second.finishCacheForTests();

        JaninoBytecodePack expandedPack = JaninoBytecodePack.read(
                second.cachePathForTests(),
                JaninoCacheFingerprint.forSeed(
                        "same-environment", "UTF-8", true, true, true));
        List<String> sourcePaths = expandedPack.sources().stream()
                .map(JaninoSourceIndex.SourceSnapshot::logicalPath)
                .toList();
        assertTrue(sourcePaths.contains("sample/A.java"));
        assertTrue(sourcePaths.contains("sample/B.java"));

        Map<String, String> changed = new LinkedHashMap<>(expanded);
        changed.put("sample/A.java", """
                package sample;
                public class A { public int value() { return 8; } }
                """);
        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader third = loader(
                new CountingFinder(changed), "same-environment", true);
        Class<?> bType = third.loadClass("sample.B");
        Object b = bType.getDeclaredConstructor().newInstance();
        Object a = bType.getMethod("create").invoke(b);

        assertEquals(8, a.getClass().getMethod("value").invoke(a));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"sourceValidationFailures\":1"));
    }

    @Test
    void warmValidationDoesNotRewriteAnUnchangedPack() throws Exception {
        Map<String, String> sources = Map.of(
                "sample/Example.java", sourceReturning(31));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(sources), "same-environment", true);
        first.loadClass("sample.Example");
        first.finishCacheForTests();
        Path path = first.cachePathForTests();
        byte[] original = Files.readAllBytes(path);

        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(sources), "same-environment", true);
        second.loadClass("sample.Example");
        second.finishCacheForTests();

        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"publishedPacks\":0"));
    }

    @Test
    void lateSourceAfterValidationLoadsButConflictingGraphIsNotPublished()
            throws Exception {
        Map<String, String> initial = Map.of(
                "sample/Example.java", sourceReturning(11));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(initial), "same-environment", true);
        assertEquals(null, first.sourceIndexForTests().findResource(
                "sample/Added.java"));
        first.loadClass("sample.Example");
        first.finishCacheForTests();
        Path path = first.cachePathForTests();
        byte[] originalPack = Files.readAllBytes(path);

        CountingFinder mutable = new CountingFinder(initial);
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                mutable, "same-environment", true);
        second.cachePathForTests();
        mutable.put("sample/Added.java", """
                package sample;
                public class Added {}
                """);

        assertEquals("sample.Added",
                second.loadClass("sample.Added").getName());
        assertThrows(IOException.class, second::finishCacheForTests);
        assertArrayEquals(originalPack, Files.readAllBytes(path));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"publishFailures\":1"));
    }

    @Test
    void invalidPackDoesNotCarryOldValidatedPathsIntoReplacement()
            throws Exception {
        Map<String, String> initial = Map.of(
                "sample/Example.java", sourceReturning(21));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(initial), "same-environment", true);
        assertEquals(null, first.sourceIndexForTests().findResource(
                "sample/AUnused.java"));
        first.loadClass("sample.Example");
        first.finishCacheForTests();

        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(Map.of(
                        "sample/Example.java", sourceReturning(22))),
                "same-environment", true);
        second.cachePathForTests();
        assertEquals(22, value(second.loadClass("sample.Example")));
        second.finishCacheForTests();

        JaninoBytecodePack replacement = JaninoBytecodePack.read(
                second.cachePathForTests(),
                JaninoCacheFingerprint.forSeed(
                        "same-environment", "UTF-8", true, true, true));
        assertFalse(replacement.sources().stream().anyMatch(snapshot ->
                snapshot.logicalPath().equals("sample/AUnused.java")));
    }

    @Test
    void corruptPackIsDeletedAndFallsBackToLiveCompilation()
            throws Exception {
        Map<String, String> sources = Map.of(
                "sample/Example.java", sourceReturning(5));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(sources), "same-environment", true);
        first.loadClass("sample.Example");
        first.finishCacheForTests();
        Path pack = first.cachePathForTests();
        byte[] corrupt = Files.readAllBytes(pack);
        corrupt[corrupt.length - 1] ^= 0x33;
        Files.write(pack, corrupt);

        JaninoBytecodeCacheDiagnostics.resetForTests();
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(sources), "same-environment", true);
        assertEquals(5, value(second.loadClass("sample.Example")));

        assertFalse(Files.exists(pack));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"corruptions\":1"));
        assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                "\"classHits\":0"));
    }

    @Test
    void cacheHitUsesTheCurrentProtectionDomainFactory()
            throws Exception {
        Map<String, String> sources = Map.of(
                "sample/Example.java", sourceReturning(3));
        CachingIndexedDeduplicatingJavaSourceClassLoader first = loader(
                new CountingFinder(sources), "same-environment", true);
        first.loadClass("sample.Example");
        first.finishCacheForTests();

        ProtectionDomain expected = new ProtectionDomain(
                null, new Permissions());
        CachingIndexedDeduplicatingJavaSourceClassLoader second = loader(
                new CountingFinder(sources), "same-environment", true);
        second.setProtectionDomainFactory(resourceName -> {
            assertEquals("sample/Example.java", resourceName);
            return expected;
        });

        assertSame(expected,
                second.loadClass("sample.Example").getProtectionDomain());
    }

    @Test
    void disabledOrFailedSessionNeverPublishes() throws Exception {
        Map<String, String> sources = Map.of(
                "sample/Example.java", sourceReturning(9));
        CachingIndexedDeduplicatingJavaSourceClassLoader disabled = loader(
                new CountingFinder(sources), "disabled", false);
        disabled.loadClass("sample.Example");
        JaninoBytecodeCacheHooks.finish(disabled, null);
        assertFalse(Files.exists(disabled.cachePathForTests()));

        CachingIndexedDeduplicatingJavaSourceClassLoader failed = loader(
                new CountingFinder(sources), "failed", true);
        failed.loadClass("sample.Example");
        JaninoBytecodeCacheHooks.finish(
                failed, new IllegalStateException("script failure"));
        assertFalse(Files.exists(failed.cachePathForTests()));
    }

    @Test
    void runtimeFingerprintLinkageFailureFallsBackToLiveCompilation()
            throws Exception {
        String previousClassPath = System.getProperty("java.class.path");
        String previousDirectory = System.getProperty(
                CachingIndexedDeduplicatingJavaSourceClassLoader
                        .DIRECTORY_PROPERTY);
        Path coreJar = Files.write(
                cacheDirectory.resolve("core.jar"), new byte[]{1});
        System.setProperty("java.class.path", coreJar.toString());
        System.setProperty(
                CachingIndexedDeduplicatingJavaSourceClassLoader
                        .DIRECTORY_PROPERTY,
                cacheDirectory.resolve("runtime-cache").toString());
        try (URLClassLoader brokenParent = new URLClassLoader(
                new URL[0], getClass().getClassLoader()) {
            @Override
            public URL[] getURLs() {
                throw new NoClassDefFoundError("optional loader unavailable");
            }
        }) {
            CachingIndexedDeduplicatingJavaSourceClassLoader loader =
                    new CachingIndexedDeduplicatingJavaSourceClassLoader(
                            brokenParent,
                            new CountingFinder(Map.of(
                                    "sample/Example.java", sourceReturning(17))),
                            "UTF-8");
            loader.setDebuggingInfo(true, true, true);

            assertEquals(17, value(loader.loadClass("sample.Example")));
            assertTrue(JaninoBytecodeCacheDiagnostics.json().contains(
                    "\"environmentFailures\":1"));
        } finally {
            restoreProperty("java.class.path", previousClassPath);
            restoreProperty(
                    CachingIndexedDeduplicatingJavaSourceClassLoader
                            .DIRECTORY_PROPERTY,
                    previousDirectory);
        }
    }

    private CachingIndexedDeduplicatingJavaSourceClassLoader loader(
            ResourceFinder finder, String environment, boolean enabled) {
        CachingIndexedDeduplicatingJavaSourceClassLoader loader =
                new CachingIndexedDeduplicatingJavaSourceClassLoader(
                        getClass().getClassLoader(), finder, "UTF-8",
                        cacheDirectory, environment, enabled);
        loader.setDebuggingInfo(true, true, true);
        return loader;
    }

    private static int value(Class<?> type) throws Exception {
        Object instance = type.getDeclaredConstructor().newInstance();
        return (Integer) type.getMethod("value").invoke(instance);
    }

    private static String sourceReturning(int value) {
        return "package sample; public class Example { "
                + "public int value() { return " + value + "; } }";
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static final class CountingFinder extends ResourceFinder {
        private final Map<String, byte[]> sources;
        private final AtomicInteger lookupCount = new AtomicInteger();

        private CountingFinder(Map<String, String> sources) {
            this.sources = new ConcurrentHashMap<>();
            sources.forEach((name, source) -> this.sources.put(
                    name, source.getBytes(StandardCharsets.UTF_8)));
        }

        private void put(String name, String source) {
            sources.put(name, source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Resource findResource(String resourceName) {
            lookupCount.incrementAndGet();
            byte[] bytes = sources.get(resourceName);
            if (bytes == null) {
                return null;
            }
            byte[] snapshot = bytes.clone();
            return new Resource() {
                @Override
                public InputStream open() {
                    return new ByteArrayInputStream(snapshot);
                }

                @Override
                public String getFileName() {
                    return resourceName;
                }

                @Override
                public long lastModified() {
                    return 0L;
                }
            };
        }
    }
}

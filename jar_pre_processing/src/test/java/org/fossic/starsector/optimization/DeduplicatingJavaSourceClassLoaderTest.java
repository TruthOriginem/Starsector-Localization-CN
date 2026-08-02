package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.codehaus.janino.JavaSourceClassLoader;
import org.codehaus.janino.util.resource.MapResourceFinder;
import org.codehaus.janino.util.resource.ResourceFinder;
import org.junit.jupiter.api.Test;

final class DeduplicatingJavaSourceClassLoaderTest {
    @Test
    void doesNotCompileAnEarlierCompilationUnitAgainForTheNextClass()
            throws ClassNotFoundException {
        DeduplicatingJavaSourceClassLoader loader = loader(Map.of(
                "sample/First.java",
                "package sample; public class First {}",
                "sample/Second.java",
                "package sample; public class Second {}"));

        Map<String, byte[]> first = loader.generateForTests("sample.First");
        Map<String, byte[]> second = loader.generateForTests("sample.Second");

        assertEquals(Set.of("sample.First"), first.keySet());
        assertEquals(Set.of("sample.Second"), second.keySet());
    }

    @Test
    void firstCompilationProducesExactlyTheVanillaBytecode() throws Exception {
        Map<String, String> sources = Map.of(
                "sample/First.java", """
                        package sample;
                        public class First {
                            public int value() { return 42; }
                            static class Inner {}
                        }
                        """);
        DeduplicatingJavaSourceClassLoader optimized = loader(sources);
        ExposedVanillaLoader vanilla = vanillaLoader(sources);
        optimized.setDebuggingInfo(true, true, true);
        vanilla.setDebuggingInfo(true, true, true);

        Map<String, byte[]> expected = vanilla.generate("sample.First");
        Map<String, byte[]> actual = optimized.generateForTests("sample.First");

        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytecode) ->
                assertArrayEquals(bytecode, actual.get(name), name));
    }

    @Test
    void dependenciesAndNestedOutputsAreNotEmittedAgain() throws Exception {
        DeduplicatingJavaSourceClassLoader loader = loader(Map.of(
                "sample/First.java", """
                        package sample;
                        public class First {
                            private final Dependency dependency = new Dependency();
                            public static class Inner {}
                        }
                        """,
                "sample/Dependency.java",
                "package sample; public class Dependency {}",
                "sample/Second.java",
                "package sample; public class Second {}"));

        Map<String, byte[]> first = loader.generateForTests("sample.First");
        Map<String, byte[]> second = loader.generateForTests("sample.Second");

        assertEquals(
                Set.of("sample.First", "sample.First$Inner", "sample.Dependency"),
                first.keySet());
        assertEquals(Set.of("sample.Second"), second.keySet());
        second.values().forEach(bytecode -> assertNotNull(bytecode));
    }

    private static DeduplicatingJavaSourceClassLoader loader(
            Map<String, String> sources) {
        return new DeduplicatingJavaSourceClassLoader(
                DeduplicatingJavaSourceClassLoaderTest.class.getClassLoader(),
                finder(sources),
                StandardCharsets.UTF_8.name());
    }

    private static ExposedVanillaLoader vanillaLoader(
            Map<String, String> sources) {
        return new ExposedVanillaLoader(
                DeduplicatingJavaSourceClassLoaderTest.class.getClassLoader(),
                finder(sources),
                StandardCharsets.UTF_8.name());
    }

    private static ResourceFinder finder(Map<String, String> sources) {
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        sources.forEach((path, source) -> encoded.put(
                path, source.getBytes(StandardCharsets.UTF_8)));
        return new MapResourceFinder(encoded);
    }

    private static final class ExposedVanillaLoader
            extends JavaSourceClassLoader {
        private ExposedVanillaLoader(
                ClassLoader parent, ResourceFinder finder, String encoding) {
            super(parent, finder, encoding);
        }

        private Map<String, byte[]> generate(String className)
                throws ClassNotFoundException {
            return generateBytecodes(className);
        }
    }
}

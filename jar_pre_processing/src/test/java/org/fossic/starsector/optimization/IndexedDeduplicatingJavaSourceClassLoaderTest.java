package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.codehaus.janino.util.resource.MapResourceFinder;
import org.junit.jupiter.api.Test;

final class IndexedDeduplicatingJavaSourceClassLoaderTest {
    @Test
    void sourceIndexDoesNotChangeGeneratedBytecode() throws Exception {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        sources.put("sample/Example.java", """
                package sample;
                public class Example {
                    private final Dependency dependency = new Dependency();
                    public int value() { return 42; }
                    static class Inner {}
                }
                """.getBytes(StandardCharsets.UTF_8));
        sources.put("sample/Dependency.java", """
                package sample;
                public class Dependency {}
                """.getBytes(StandardCharsets.UTF_8));

        DeduplicatingJavaSourceClassLoader control =
                new DeduplicatingJavaSourceClassLoader(
                        getClass().getClassLoader(),
                        new MapResourceFinder(sources), "UTF-8");
        IndexedDeduplicatingJavaSourceClassLoader candidate =
                new IndexedDeduplicatingJavaSourceClassLoader(
                        getClass().getClassLoader(),
                        new MapResourceFinder(sources), "UTF-8");
        control.setDebuggingInfo(true, true, true);
        candidate.setDebuggingInfo(true, true, true);

        Map<String, byte[]> expected = control.generateForTests(
                "sample.Example");
        Map<String, byte[]> actual = candidate.generateForTests(
                "sample.Example");

        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytecode) ->
                assertArrayEquals(bytecode, actual.get(name), name));
    }
}

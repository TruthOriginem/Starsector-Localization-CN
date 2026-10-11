package org.fossic.starsector.preprocessing;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real input, actual registry order, ClassWriter(0), and the same read-only checker as production. */
final class PatchCompositionTest {
    @TempDir Path output;
    record Configuration(String name, String optimizations, List<String> disabled, boolean profiling) {}

    @TestFactory Stream<DynamicTest> registryCompositions() {
        return Stream.of(
                new Configuration("localization", "none", List.of(), false),
                new Configuration("unpatched", "none", List.of("localization"), false)
        ).map(config -> DynamicTest.dynamicTest(config.name(), () -> {
            List<JarPatch> patches = PatchRegistry.patches(PatchSelection.fromOptions(config.optimizations(), config.disabled(), config.profiling()));
            JarRewriter rewriter = new JarRewriter(patches);
            for (String jar : JarWorkspace.allJars()) {
                Path generated = output.resolve(config.name()).resolve(jar);
                rewriter.rewrite(jar, Path.of("..", "game data", jar), generated);
                assertTrue(BytecodeVerifier.verifyJar(jar, generated, patches) > 0);
            }
        }));
    }
}

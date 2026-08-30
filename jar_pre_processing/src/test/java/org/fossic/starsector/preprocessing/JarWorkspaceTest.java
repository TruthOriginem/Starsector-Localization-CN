package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class JarWorkspaceTest {
    private static final long EARLY_TIME = 1_000_000_000_000L;
    private static final long LATE_TIME = 1_600_000_000_000L;
    private static final Map<String, byte[]> ORIGINAL_ENTRIES = entries(
            "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
            "example/Test.class", "original-class");
    private static final Map<String, byte[]> LOCALIZED_ENTRIES = entries(
            "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
            "example/Test.class", "translated-class");

    @TempDir
    Path tempDir;

    @Test
    void preservesExistingOutputsWhenOnlyJarMetadataDiffers() throws Exception {
        Fixture fixture = fixture();
        Map<String, byte[]> reordered = entries(
                "example/Test.class", "original-class",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        writeJar(fixture.generated(JarWorkspace.API_JAR),
                reordered, LATE_TIME, "generated-in-another-order");
        byte[] expectedOriginal = Files.readAllBytes(fixture.original(JarWorkspace.API_JAR));
        byte[] expectedLocalization = Files.readAllBytes(
                fixture.localization(JarWorkspace.API_JAR));

        fixture.workspace().writeOutputs();

        assertArrayEquals(expectedOriginal,
                Files.readAllBytes(fixture.original(JarWorkspace.API_JAR)));
        assertArrayEquals(expectedLocalization,
                Files.readAllBytes(fixture.localization(JarWorkspace.API_JAR)));
    }

    @Test
    void resetsBothOutputsWhenAnEntryContentChanges() throws Exception {
        Fixture fixture = fixture();
        Map<String, byte[]> changed = entries(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                "example/Test.class", "patched-class");
        writeJar(fixture.generated(JarWorkspace.OBF_JAR), changed, LATE_TIME, "new");
        byte[] expected = Files.readAllBytes(fixture.generated(JarWorkspace.OBF_JAR));

        fixture.workspace().writeOutputs();

        assertArrayEquals(expected,
                Files.readAllBytes(fixture.original(JarWorkspace.OBF_JAR)));
        assertArrayEquals(expected,
                Files.readAllBytes(fixture.localization(JarWorkspace.OBF_JAR)));
    }

    @Test
    void resetsOnlyLocalizationWhenItsResourcesAreIncompatible() throws Exception {
        Fixture fixture = fixture();
        Map<String, byte[]> incompatible = entries(
                "META-INF/MANIFEST.MF", "changed-resource\n",
                "example/Test.class", "translated-class");
        writeJar(fixture.localization(JarWorkspace.API_JAR),
                incompatible, LATE_TIME, "incompatible");
        byte[] expectedOriginal = Files.readAllBytes(fixture.original(JarWorkspace.API_JAR));

        fixture.workspace().writeOutputs();

        assertArrayEquals(expectedOriginal,
                Files.readAllBytes(fixture.original(JarWorkspace.API_JAR)));
        assertArrayEquals(expectedOriginal,
                Files.readAllBytes(fixture.localization(JarWorkspace.API_JAR)));
    }

    @Test
    void repairsAMissingOrCorruptLocalizationFromTheExistingOriginal()
            throws Exception {
        Fixture fixture = fixture();
        Path localization = fixture.localization(JarWorkspace.API_JAR);
        Files.writeString(localization, "not a zip", StandardCharsets.UTF_8);
        byte[] expectedOriginal = Files.readAllBytes(fixture.original(JarWorkspace.API_JAR));

        fixture.workspace().writeOutputs();

        assertArrayEquals(expectedOriginal, Files.readAllBytes(localization));
    }

    @Test
    void createsBothOutputsWhenTheExistingOriginalIsMissing() throws Exception {
        Fixture fixture = fixture();
        Path original = fixture.original(JarWorkspace.API_JAR);
        Path localization = fixture.localization(JarWorkspace.API_JAR);
        Files.delete(original);
        Files.delete(localization);
        byte[] expected = Files.readAllBytes(fixture.generated(JarWorkspace.API_JAR));

        fixture.workspace().writeOutputs();

        assertArrayEquals(expected, Files.readAllBytes(original));
        assertArrayEquals(expected, Files.readAllBytes(localization));
    }

    private Fixture fixture() throws IOException {
        Path repo = tempDir.resolve("repo");
        Path project = repo.resolve("jar_pre_processing");
        JarWorkspace workspace = new JarWorkspace(project);
        for (String jarName : JarWorkspace.allJars()) {
            writeJar(generated(workspace, jarName), ORIGINAL_ENTRIES,
                    LATE_TIME, "generated");
            writeJar(repo.resolve("original").resolve(jarName), ORIGINAL_ENTRIES,
                    EARLY_TIME, "existing-original");
            writeJar(repo.resolve("localization").resolve(jarName), LOCALIZED_ENTRIES,
                    EARLY_TIME, "existing-localization");
        }
        return new Fixture(repo, workspace);
    }

    private static Path generated(JarWorkspace workspace, String jarName) {
        return Arrays.asList(JarWorkspace.jars()).contains(jarName)
                ? workspace.decoupledJar(jarName)
                : workspace.patchedJar(jarName);
    }

    private static void writeJar(
            Path path, Map<String, byte[]> entries, long time, String comment)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTime(time);
                entry.setComment(comment);
                output.putNextEntry(entry);
                output.write(item.getValue());
                output.closeEntry();
            }
        }
    }

    private static Map<String, byte[]> entries(String... namesAndValues) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            result.put(namesAndValues[index],
                    namesAndValues[index + 1].getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private record Fixture(Path repo, JarWorkspace workspace) {
        Path generated(String jarName) {
            return JarWorkspaceTest.generated(workspace, jarName);
        }

        Path original(String jarName) {
            return repo.resolve("original").resolve(jarName);
        }

        Path localization(String jarName) {
            return repo.resolve("localization").resolve(jarName);
        }
    }
}

package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JarWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void prepareDeletesAWorkRootJunctionWithoutEnteringItsTarget()
            throws Exception {
        assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows"));
        Path repository = temporaryDirectory.resolve("repository");
        Path project = repository.resolve("jar_pre_processing");
        Path work = project.resolve("target/preprocess-work");
        Path outside = temporaryDirectory.resolve("outside");
        Path sentinel = outside.resolve("must-survive.txt");
        prepareMinimumRepository(repository, project);
        Files.createDirectories(work.getParent());
        Files.createDirectories(outside);
        Files.writeString(sentinel, "outside", StandardCharsets.UTF_8);

        try {
            createWindowsJunction(work, outside);
            assertTrue(Files.isDirectory(work));

            new JarWorkspace(project).prepare();

            assertTrue(Files.isRegularFile(sentinel));
            assertEquals(
                    "outside",
                    Files.readString(sentinel, StandardCharsets.UTF_8));
            BasicFileAttributes attributes = Files.readAttributes(
                    work,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            assertTrue(attributes.isDirectory());
            assertFalse(attributes.isOther());
            assertFalse(attributes.isSymbolicLink());
        } finally {
            removeJunctionIfPresent(work);
        }
    }

    private static void prepareMinimumRepository(
            Path repository, Path project) throws IOException {
        Path vendor = project.resolve(
                "vendor/jar-string-decoupler-1.0.0-all.jar");
        Files.createDirectories(vendor.getParent());
        Files.write(vendor, new byte[] {1});
        Path gameData = repository.resolve("game data");
        Files.createDirectories(gameData);
        for (String jar : JarWorkspace.allJars()) {
            Files.write(gameData.resolve(jar), new byte[] {1});
        }
    }

    private static void createWindowsJunction(Path junction, Path target)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                junction.toString(),
                target.toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        assertEquals(
                0,
                exitCode,
                new String(output, StandardCharsets.UTF_8));
    }

    private static void removeJunctionIfPresent(Path junction)
            throws IOException {
        if (!Files.exists(junction, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                junction,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther() || attributes.isSymbolicLink()) {
            Files.deleteIfExists(junction);
        }
    }
}

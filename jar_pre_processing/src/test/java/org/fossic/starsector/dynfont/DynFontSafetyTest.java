package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class DynFontSafetyTest {
    @Test
    void createsAndAcceptsOnlyAPlainCacheRoot(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");

        assertEquals(cache.toAbsolutePath().normalize(),
                DynFontOverrides.prepareCacheRoot(cache, root));
        assertTrue(Files.isDirectory(cache));
    }

    @Test
    void rejectsAWindowsJunctionAsTheCacheRoot(@TempDir Path root) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = root.resolve("outside");
        Path sentinel = outside.resolve("sentinel.txt");
        Path junction = root.resolve("cache");
        Files.createDirectories(outside);
        Files.writeString(sentinel, "must survive", StandardCharsets.UTF_8);
        createWindowsJunction(junction, outside);

        assertThrows(IOException.class,
                () -> DynFontOverrides.prepareCacheRoot(junction, root));
        assertEquals("must survive", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAWindowsParentJunctionEscapingTheAllowedRoot(@TempDir Path root)
            throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path allowed = root.resolve("game");
        Path outside = root.resolve("outside");
        Path sentinel = outside.resolve("sentinel.txt");
        Path parentJunction = allowed.resolve("dyn_font");
        Path cache = parentJunction.resolve("cache");
        Files.createDirectories(allowed);
        Files.createDirectories(outside);
        Files.writeString(sentinel, "must survive", StandardCharsets.UTF_8);
        createWindowsJunction(parentJunction, outside);

        assertThrows(IOException.class,
                () -> DynFontOverrides.prepareCacheRoot(cache, allowed));
        assertEquals("must survive", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertTrue(Files.notExists(outside.resolve("cache")));
    }

    @Test
    void normalizesOnlyFiniteGameSupportedScales() {
        assertEquals(1.0, DynFontOverrides.normalizeScreenScale(0.5));
        assertEquals(1.95, DynFontOverrides.normalizeScreenScale(1.949));
        assertEquals(3.0, DynFontOverrides.normalizeScreenScale(3.0));

        assertThrows(IllegalArgumentException.class,
                () -> DynFontOverrides.normalizeScreenScale(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> DynFontOverrides.normalizeScreenScale(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> DynFontOverrides.normalizeScreenScale(3.01));
    }

    private static void createWindowsJunction(Path junction, Path target)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}

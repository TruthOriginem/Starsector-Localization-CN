package org.fossic.starsector.ime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImeRuntimeDisabledTest {
    @TempDir
    Path tempDir;

    @Test
    void disabledRuntimeDoesNotLoadNativeLibraryOrCreateNativeLog() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        Process process = new ProcessBuilder(List.of(
                java,
                "-Dfile.encoding=UTF-8",
                "-Dfossic.ime.enabled=false",
                "-Djava.library.path=" + tempDir,
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()))
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "disabled-runtime probe timed out");
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();

        assertEquals(0, process.exitValue(), output);
        assertEquals("runtime-disabled-ok", output);
        assertFalse(Files.exists(tempDir.resolve("starsector_ime_native.log")));
    }

    public static final class Probe {
        private Probe() {
        }

        public static void main(String[] args) {
            ImeHooks.onGlobalInputFrame(null);
            ImeHooks.onGlobalFocusChanged(null);
            ImeHooks.onProcessInput(null);
            ImeHooks.onFocusReleased(null);
            ImeHooks.onTextFieldFocusGained(null);
            System.out.println("runtime-disabled-ok");
        }
    }
}

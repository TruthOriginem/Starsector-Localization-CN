package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JaninoCacheFingerprintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void dependsOnOrderedContentsButNotAbsoluteInstallationPath()
            throws Exception {
        Path firstRoot = Files.createDirectory(
                temporaryDirectory.resolve("first"));
        Path secondRoot = Files.createDirectory(
                temporaryDirectory.resolve("second"));
        Path firstA = write(firstRoot.resolve("a.jar"), "alpha");
        Path firstB = write(firstRoot.resolve("b.jar"), "beta");
        Path secondA = write(secondRoot.resolve("renamed-a.jar"), "alpha");
        Path secondB = write(secondRoot.resolve("renamed-b.jar"), "beta");

        String first = fingerprint(List.of(firstA), List.of(firstB), true);
        String relocated = fingerprint(
                List.of(secondA), List.of(secondB), true);
        String reordered = fingerprint(
                List.of(firstB), List.of(firstA), true);

        assertEquals(first, relocated);
        assertNotEquals(first, reordered);
    }

    @Test
    void detectsContentChangesWithTheSameSizeAndTimestamp()
            throws Exception {
        Path jar = write(temporaryDirectory.resolve("same.jar"), "aaaa");
        FileTime timestamp = Files.getLastModifiedTime(jar);
        String before = fingerprint(List.of(jar), List.of(), true);

        Files.writeString(jar, "bbbb");
        Files.setLastModifiedTime(jar, timestamp);
        String after = fingerprint(List.of(jar), List.of(), true);

        assertNotEquals(before, after);
    }

    @Test
    void includesCompilerOptionsAndLoaderChain() throws Exception {
        Path jar = write(temporaryDirectory.resolve("compiler.jar"), "bytes");
        String baseline = JaninoCacheFingerprint.forInputs(
                List.of(jar), frames(List.of(), "parent", "sandbox"),
                List.of("bootstrap"),
                "UTF-8", true, true, true);

        assertNotEquals(baseline, JaninoCacheFingerprint.forInputs(
                List.of(jar), frames(List.of(), "parent", "sandbox"),
                List.of("bootstrap"),
                "UTF-8", false, true, true));
        assertNotEquals(baseline, JaninoCacheFingerprint.forInputs(
                List.of(jar), frames(List.of(), "sandbox", "parent"),
                List.of("bootstrap"),
                "UTF-8", true, true, true));
    }

    @Test
    void preservesWhichUrlsBelongToEachLoaderFrame() throws Exception {
        Path first = write(temporaryDirectory.resolve("first.jar"), "alpha");
        Path second = write(temporaryDirectory.resolve("second.jar"), "beta");
        List<String> environment = List.of("bootstrap");

        String split = JaninoCacheFingerprint.forInputs(
                List.of(first),
                List.of(
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of(first)),
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of(second))),
                environment, "UTF-8", true, true, true);
        String flattened = JaninoCacheFingerprint.forInputs(
                List.of(first),
                List.of(
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of(first, second)),
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of())),
                environment, "UTF-8", true, true, true);
        String reversed = JaninoCacheFingerprint.forInputs(
                List.of(first),
                List.of(
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of(second)),
                        new JaninoCacheFingerprint.LoaderFrame(
                                "same.Loader", List.of(first))),
                environment, "UTF-8", true, true, true);

        assertNotEquals(split, flattened);
        assertNotEquals(split, reversed);
    }

    @Test
    void failsClosedForClasspathDirectories() throws Exception {
        Path directory = Files.createDirectory(
                temporaryDirectory.resolve("classes"));

        assertThrows(IOException.class, () -> fingerprint(
                List.of(directory), List.of(), true));
    }

    @Test
    void followsManifestClassPathRecursively() throws Exception {
        Path owner = writeJar(
                temporaryDirectory.resolve("owner.jar"),
                "middle.jar", "owner");
        writeJar(temporaryDirectory.resolve("middle.jar"),
                "dependency.jar", "middle");
        Path dependency = writeJar(
                temporaryDirectory.resolve("dependency.jar"),
                null, "aaaa");
        FileTime timestamp = Files.getLastModifiedTime(dependency);
        String before = fingerprint(List.of(owner), List.of(), true);

        writeJar(dependency, null, "bbbb");
        Files.setLastModifiedTime(dependency, timestamp);
        String after = fingerprint(List.of(owner), List.of(), true);

        assertNotEquals(before, after);
    }

    @Test
    void distinguishesMissingAndLaterPresentManifestDependency()
            throws Exception {
        Path owner = writeJar(
                temporaryDirectory.resolve("owner.jar"),
                "optional.jar", "owner");
        String missing = fingerprint(List.of(owner), List.of(), true);

        writeJar(temporaryDirectory.resolve("optional.jar"), null, "added");
        String present = fingerprint(List.of(owner), List.of(), true);

        assertNotEquals(missing, present);
    }

    @Test
    void manifestDependencyGraphIsRelocatableAndCycleSafe()
            throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(
                temporaryDirectory.resolve("second"));
        Path firstA = writeJar(first.resolve("a.jar"), "b.jar", "a");
        writeJar(first.resolve("b.jar"), "a.jar", "b");
        Path secondA = writeJar(second.resolve("a.jar"), "b.jar", "a");
        writeJar(second.resolve("b.jar"), "a.jar", "b");

        String original = fingerprint(List.of(firstA), List.of(), true);
        String relocated = fingerprint(List.of(secondA), List.of(), true);

        assertEquals(original, relocated);
    }

    @Test
    void failsClosedForUnsupportedManifestDependencies() throws Exception {
        Path directory = Files.createDirectory(
                temporaryDirectory.resolve("classes"));
        Path directoryOwner = writeJar(
                temporaryDirectory.resolve("directory-owner.jar"),
                directory.getFileName().toString(), "owner");
        Path remoteOwner = writeJar(
                temporaryDirectory.resolve("remote-owner.jar"),
                "https://example.invalid/dependency.jar", "owner");

        assertThrows(IOException.class, () -> fingerprint(
                List.of(directoryOwner), List.of(), true));
        assertThrows(IOException.class, () -> fingerprint(
                List.of(remoteOwner), List.of(), true));
    }

    @Test
    void runtimeFingerprintIncludesMultiReleaseProperties()
            throws Exception {
        String previousClassPath = System.getProperty("java.class.path");
        String previousEnabled = System.getProperty(
                "jdk.util.jar.enableMultiRelease");
        String previousVersion = System.getProperty(
                "jdk.util.jar.version");
        Path classPath = write(
                temporaryDirectory.resolve("runtime.bin"), "runtime");
        try {
            System.setProperty("java.class.path", classPath.toString());
            System.clearProperty("jdk.util.jar.enableMultiRelease");
            System.clearProperty("jdk.util.jar.version");
            String baseline = JaninoCacheFingerprint.forRuntime(
                    null, "UTF-8", true, true, true);

            System.setProperty("jdk.util.jar.enableMultiRelease", "false");
            String disabled = JaninoCacheFingerprint.forRuntime(
                    null, "UTF-8", true, true, true);
            System.clearProperty("jdk.util.jar.enableMultiRelease");
            System.setProperty("jdk.util.jar.version", "8");
            String pinnedVersion = JaninoCacheFingerprint.forRuntime(
                    null, "UTF-8", true, true, true);

            assertNotEquals(baseline, disabled);
            assertNotEquals(baseline, pinnedVersion);
        } finally {
            restoreProperty("java.class.path", previousClassPath);
            restoreProperty(
                    "jdk.util.jar.enableMultiRelease", previousEnabled);
            restoreProperty("jdk.util.jar.version", previousVersion);
        }
    }

    private static String fingerprint(
            List<Path> core, List<Path> mods, boolean debugSource)
            throws IOException {
        return JaninoCacheFingerprint.forInputs(
                core, frames(mods, "sandbox", "application"),
                List.of("bootstrap"),
                "UTF-8", debugSource, true, true);
    }

    private static List<JaninoCacheFingerprint.LoaderFrame> frames(
            List<Path> urls, String... names) {
        ArrayList<JaninoCacheFingerprint.LoaderFrame> frames =
                new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            frames.add(new JaninoCacheFingerprint.LoaderFrame(
                    names[index], index == 0 ? urls : List.of()));
        }
        return List.copyOf(frames);
    }

    private static Path write(Path path, String value) throws IOException {
        Files.writeString(path, value);
        return path;
    }

    private static Path writeJar(
            Path path, String manifestClassPath, String payload)
            throws IOException {
        byte[] manifest = ("Manifest-Version: 1.0\r\n"
                + (manifestClassPath == null
                        ? "" : "Class-Path: " + manifestClassPath + "\r\n")
                + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (OutputStream file = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            writeStoredEntry(zip, "META-INF/MANIFEST.MF", manifest);
            writeStoredEntry(zip, "payload.bin", payload.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return path;
    }

    private static void writeStoredEntry(
            ZipOutputStream zip, String name, byte[] contents)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(contents);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(contents.length);
        entry.setCompressedSize(contents.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

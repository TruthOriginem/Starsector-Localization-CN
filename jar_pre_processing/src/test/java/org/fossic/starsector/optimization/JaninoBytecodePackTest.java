package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JaninoBytecodePackTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsPositiveNegativeSourcesAndClassBytes() throws Exception {
        byte[] bytecode = classBytes(FixtureOne.class);
        JaninoBytecodePack expected = new JaninoBytecodePack(
                "environment-one",
                List.of(
                        JaninoSourceIndex.SourceSnapshot.present(
                                "sample/Example.java", digest("source-one")),
                        JaninoSourceIndex.SourceSnapshot.missing(
                                "sample/Missing.java")),
                Map.of(FixtureOne.class.getName(), bytecode));
        Path path = temporaryDirectory.resolve("pack.bin");

        expected.writeAtomically(path);
        JaninoBytecodePack actual = JaninoBytecodePack.read(
                path, "environment-one");

        assertEquals("environment-one", actual.fingerprint());
        assertEquals(2, actual.sources().size());
        assertEquals("sample/Example.java",
                actual.sources().get(0).logicalPath());
        assertTrue(actual.sources().get(0).present());
        assertArrayEquals(digest("source-one"),
                actual.sources().get(0).sha256());
        assertFalse(actual.sources().get(1).present());
        assertArrayEquals(bytecode,
                actual.classBytecodes().get(FixtureOne.class.getName()));
    }

    @Test
    void atomicallyReplacesAnOlderCompleteGeneration() throws Exception {
        Path path = temporaryDirectory.resolve("pack.bin");
        pack("environment-one", FixtureOne.class).writeAtomically(path);
        pack("environment-two", FixtureTwo.class).writeAtomically(path);

        JaninoBytecodePack actual = JaninoBytecodePack.read(
                path, "environment-two");

        assertEquals(
                List.of(FixtureTwo.class.getName()),
                actual.classBytecodes().keySet().stream().toList());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test
    void rejectsChecksumCorruptionTruncationAndTrailingBytes()
            throws Exception {
        Path path = temporaryDirectory.resolve("pack.bin");
        pack("environment", FixtureOne.class).writeAtomically(path);
        byte[] original = Files.readAllBytes(path);

        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 0x55;
        Files.write(path, corrupt);
        assertThrows(IOException.class,
                () -> JaninoBytecodePack.read(path, "environment"));

        Files.write(path, java.util.Arrays.copyOf(
                original, original.length - 7));
        assertThrows(IOException.class,
                () -> JaninoBytecodePack.read(path, "environment"));

        byte[] trailing = java.util.Arrays.copyOf(
                original, original.length + 1);
        Files.write(path, trailing);
        assertThrows(IOException.class,
                () -> JaninoBytecodePack.read(path, "environment"));
    }

    @Test
    void rejectsWrongFingerprintUnsafePathsDuplicatesAndClassNames()
            throws Exception {
        Path path = temporaryDirectory.resolve("pack.bin");
        JaninoBytecodePack valid = pack("environment", FixtureOne.class);
        valid.writeAtomically(path);

        assertThrows(IOException.class,
                () -> JaninoBytecodePack.read(path, "other-environment"));
        assertThrows(IllegalArgumentException.class,
                () -> new JaninoBytecodePack(
                        "environment",
                        List.of(JaninoSourceIndex.SourceSnapshot.missing(
                                "../escape.java")),
                        valid.classBytecodes()));
        assertThrows(IllegalArgumentException.class,
                () -> new JaninoBytecodePack(
                        "environment",
                        List.of(
                                JaninoSourceIndex.SourceSnapshot.missing(
                                        "sample/Same.java"),
                                JaninoSourceIndex.SourceSnapshot.missing(
                                        "sample/Same.java")),
                        valid.classBytecodes()));
        assertThrows(IllegalArgumentException.class,
                () -> new JaninoBytecodePack(
                        "environment",
                        List.of(),
                        Map.of("wrong.BinaryName",
                                classBytes(FixtureOne.class))));
    }

    @Test
    void returnedArraysAndMapsCannotMutateThePack() throws Exception {
        JaninoBytecodePack pack = pack("environment", FixtureOne.class);
        byte[] first = pack.classBytecodes().get(FixtureOne.class.getName());
        byte original = first[0];
        first[0] ^= 0x7f;

        assertEquals(original,
                pack.classBytecodes().get(FixtureOne.class.getName())[0]);
        assertThrows(UnsupportedOperationException.class,
                () -> pack.classBytecodes().clear());
    }

    private static JaninoBytecodePack pack(
            String fingerprint, Class<?> fixture) throws Exception {
        return new JaninoBytecodePack(
                fingerprint,
                List.of(JaninoSourceIndex.SourceSnapshot.present(
                        "sample/Example.java", digest("source"))),
                Map.of(fixture.getName(), classBytes(fixture)));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static byte[] digest(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FixtureOne {
    }

    private static final class FixtureTwo {
    }
}

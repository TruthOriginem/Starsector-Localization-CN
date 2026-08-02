package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DecodedPcmCacheTest {
    @TempDir
    Path cacheDirectory;

    @BeforeEach
    void configureCache() {
        System.setProperty(
                DecodedPcmCache.DIRECTORY_PROPERTY,
                cacheDirectory.toString());
        System.setProperty(
                DecodedPcmCache.MINIMUM_BYTES_PROPERTY, "0");
        System.clearProperty(DecodedPcmCache.DISABLE_PROPERTY);
        DecodedPcmCache.resetForTests();
    }

    @AfterEach
    void resetCache() {
        System.clearProperty(DecodedPcmCache.DIRECTORY_PROPERTY);
        System.clearProperty(DecodedPcmCache.MINIMUM_BYTES_PROPERTY);
        System.clearProperty(DecodedPcmCache.MAXIMUM_BYTES_PROPERTY);
        System.clearProperty(
                DecodedPcmCache.MAXIMUM_ENCODED_BYTES_PROPERTY);
        System.clearProperty(DecodedPcmCache.DISABLE_PROPERTY);
        DecodedPcmCache.resetForTests();
    }

    @Test
    void secondDecodeRestoresDirectPcmAndMetadataWithoutDecoder()
            throws IOException {
        FakeDecoder decoder = new FakeDecoder();
        byte[] encoded = {1, 2, 3, 4, 5};
        TrackingInputStream firstSource =
                new TrackingInputStream(encoded);
        FakePcm first = (FakePcm) DecodedPcmCache.decode(
                decoder, firstSource);
        TrackingInputStream secondSource =
                new TrackingInputStream(encoded);
        FakePcm second = (FakePcm) DecodedPcmCache.decode(
                decoder, secondSource);

        assertEquals(1, decoder.invocations);
        assertEquals(1, firstSource.closeCount);
        assertEquals(1, secondSource.closeCount);
        assertTrue(second.Object.isDirect());
        assertEquals(44_100, second.\u00d200000);
        assertEquals(2, second.o00000);
        assertArrayEquals(bytes(first.Object), bytes(second.Object));
        assertTrue(PcmCacheDiagnostics.json().contains("\"hits\":1"));
        assertTrue(PcmCacheDiagnostics.json().contains("\"stores\":1"));
        assertEquals(List.of("pcm"),
                PersistentCacheMaintenance.registeredNamespacesForTests()
                        .stream().sorted().toList());
    }

    @Test
    void sameLengthContentChangeCannotReuseOldPcm() throws IOException {
        FakeDecoder decoder = new FakeDecoder();

        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(new byte[]{4, 3, 2, 1}));

        assertEquals(2, decoder.invocations);
        assertEquals(2, cacheFiles().size());
    }

    @Test
    void corruptEntryIsDeletedAndFallsBackToOriginalDecoder()
            throws IOException {
        FakeDecoder decoder = new FakeDecoder();
        byte[] encoded = {9, 8, 7, 6};
        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));
        Path cacheFile = cacheFiles().get(0);
        Files.write(cacheFile, new byte[]{0, 1, 2, 3});

        FakePcm recovered = (FakePcm) DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));

        assertEquals(2, decoder.invocations);
        assertArrayEquals(decoder.pcm(encoded), bytes(recovered.Object));
        assertTrue(PcmCacheDiagnostics.json().contains(
                "\"corruptions\":1"));
    }

    @Test
    void disabledCachePreservesOriginalInvocationAndWritesNothing()
            throws IOException {
        System.setProperty(DecodedPcmCache.DISABLE_PROPERTY, "true");
        DecodedPcmCache.resetForTests();
        FakeDecoder decoder = new FakeDecoder();
        byte[] encoded = {5, 5, 5};

        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));
        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));

        assertEquals(2, decoder.invocations);
        assertTrue(cacheFiles().isEmpty());
        assertTrue(PersistentCacheMaintenance
                .registeredNamespacesForTests().isEmpty());
    }

    @Test
    void invalidConfiguredDirectoryFallsBackToOriginalDecoder()
            throws IOException {
        System.setProperty(
                DecodedPcmCache.DIRECTORY_PROPERTY, "\0");
        DecodedPcmCache.resetForTests();
        FakeDecoder decoder = new FakeDecoder();
        byte[] encoded = {8, 6, 7, 5};

        FakePcm decoded = (FakePcm) DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));

        assertEquals(1, decoder.invocations);
        assertArrayEquals(decoder.pcm(encoded), bytes(decoded.Object));
    }

    @Test
    void temporaryNamesAreUniqueEvenForTheSameThread() {
        Path target = cacheDirectory.resolve("entry.sspcm.zst");

        Path first = DecodedPcmCache.temporaryFile(target);
        Path second = DecodedPcmCache.temporaryFile(target);

        assertNotEquals(first, second);
        assertTrue(first.getFileName().toString().endsWith(".tmp"));
        assertTrue(second.getFileName().toString().endsWith(".tmp"));
    }

    @Test
    void implementationGenerationParticipatesInSourceHash() {
        byte[] encoded = {1, 2, 3};

        assertNotEquals(
                DecodedPcmCache.sourceHashForIdentity(
                        encoded, "pcm-v1"),
                DecodedPcmCache.sourceHashForIdentity(
                        encoded, "pcm-v2"));
    }

    @Test
    void symbolicLinkShardCannotDeleteAnExternalFile(
            @TempDir Path externalDirectory) throws IOException {
        byte[] encoded = {2, 7, 1, 8};
        String sourceHash = DecodedPcmCache.sourceHashForTests(encoded);
        Files.createDirectories(cacheDirectory);
        Path externalFile = externalDirectory.resolve(
                sourceHash + ".sspcm.zst");
        Files.write(externalFile, new byte[]{0, 1, 2, 3});
        try {
            Files.createSymbolicLink(
                    cacheDirectory.resolve(sourceHash.substring(0, 2)),
                    externalDirectory);
        } catch (IOException | UnsupportedOperationException failure) {
            assumeTrue(false, "symbolic links unavailable: " + failure);
        }

        FakeDecoder decoder = new FakeDecoder();
        DecodedPcmCache.decode(
                decoder, new ByteArrayInputStream(encoded));

        assertEquals(1, decoder.invocations);
        assertTrue(Files.exists(externalFile));
    }

    @Test
    void oversizedSourceFallsBackWithoutUnboundedPreRead()
            throws IOException {
        System.setProperty(
                DecodedPcmCache.MAXIMUM_ENCODED_BYTES_PROPERTY, "4");
        DecodedPcmCache.resetForTests();
        byte[] encoded = new byte[1024 * 1024];
        CountingInputStream source = new CountingInputStream(encoded);
        PartialDecoder decoder = new PartialDecoder();

        DecodedPcmCache.decode(decoder, source);

        assertEquals(1, decoder.invocations);
        assertTrue(source.bytesRead <= 5,
                "cache probe must remain bounded");
        assertEquals(1, source.closeCount);
    }

    @Test
    void sourceExactlyAtProbeLimitCanStillBeCachedAndIsClosedOnce()
            throws IOException {
        System.setProperty(
                DecodedPcmCache.MAXIMUM_ENCODED_BYTES_PROPERTY, "4");
        DecodedPcmCache.resetForTests();
        byte[] encoded = {1, 2, 3, 4};
        TrackingInputStream first = new TrackingInputStream(encoded);
        TrackingInputStream second = new TrackingInputStream(encoded);
        FakeDecoder decoder = new FakeDecoder();

        DecodedPcmCache.decode(decoder, first);
        DecodedPcmCache.decode(decoder, second);

        assertEquals(1, decoder.invocations);
        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
    }

    @Test
    void readFailureRemainsPrimaryAndCloseFailureIsSuppressed() {
        IOException readFailure = new IOException("read failed");
        IOException closeFailure = new IOException("close failed");
        InputStream source = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }

            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };

        IOException thrown = assertThrows(
                IOException.class,
                () -> DecodedPcmCache.decode(new FakeDecoder(), source));

        assertEquals(readFailure, thrown);
        assertArrayEquals(
                new Throwable[]{closeFailure}, thrown.getSuppressed());
    }

    @Test
    void corruptedCompressedEnvelopeIsRejectedBeforeDecompression() {
        int rawLength = 512 * 1024 * 1024;
        assertTrue(DecodedPcmCache.validCompressedEnvelopeForTests(
                103L, rawLength, 1));
        assertFalse(DecodedPcmCache.zstdFrameMatchesForTests(
                new byte[]{1}, rawLength));

        byte[] valid = IsolatedZstdCodec.compress(new byte[64], 1);
        assertTrue(DecodedPcmCache.zstdFrameMatchesForTests(valid, 64));
        assertFalse(DecodedPcmCache.zstdFrameMatchesForTests(valid, 32));
        assertFalse(DecodedPcmCache.validCompressedEnvelopeForTests(
                102L + Integer.MAX_VALUE, 16, Integer.MAX_VALUE));
    }

    private List<Path> cacheFiles() throws IOException {
        if (!Files.isDirectory(cacheDirectory)) {
            return List.of();
        }
        try (var paths = Files.walk(cacheDirectory)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        copy.position(0);
        byte[] result = new byte[copy.limit()];
        copy.get(result);
        return result;
    }

    private static final class FakeDecoder {
        private int invocations;

        @SuppressWarnings("unused")
        private FakePcm starsector$decodePcmUncached(InputStream input)
                throws IOException {
            invocations++;
            byte[] encoded;
            try (input) {
                encoded = input.readAllBytes();
            }
            FakePcm result = new FakePcm();
            result.Object = ByteBuffer.allocateDirect(encoded.length * 4);
            result.Object.put(pcm(encoded));
            result.Object.flip();
            result.\u00d200000 = 44_100;
            result.o00000 = 2;
            return result;
        }

        private byte[] pcm(byte[] encoded) {
            byte[] result = new byte[encoded.length * 4];
            for (int index = 0; index < result.length; index++) {
                result[index] = (byte) (encoded[index % encoded.length]
                        + index * 3);
            }
            return result;
        }
    }

    @SuppressWarnings("unused")
    private static final class FakePcm {
        public ByteBuffer Object;
        public int \u00d200000;
        public int o00000;
    }

    private static final class TrackingInputStream
            extends ByteArrayInputStream {
        private int closeCount;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            super.close();
        }
    }

    private static final class CountingInputStream
            extends ByteArrayInputStream {
        private int bytesRead;
        private int closeCount;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public synchronized int read(
                byte[] bytes, int offset, int length) {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class PartialDecoder {
        private int invocations;

        @SuppressWarnings("unused")
        private FakePcm starsector$decodePcmUncached(InputStream input)
                throws IOException {
            invocations++;
            input.readNBytes(2);
            FakePcm result = new FakePcm();
            result.Object = ByteBuffer.allocateDirect(4);
            result.Object.put(new byte[]{1, 2, 3, 4});
            result.Object.flip();
            result.\u00d200000 = 44_100;
            result.o00000 = 2;
            return result;
        }
    }
}

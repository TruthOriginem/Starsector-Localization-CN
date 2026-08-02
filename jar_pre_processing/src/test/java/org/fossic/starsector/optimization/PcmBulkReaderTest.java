package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class PcmBulkReaderTest {
    @Test
    void drainsOnlyTheCurrentBlockAndHonorsDestinationOffset()
            throws IOException {
        var decoder = new FakeDecoder(16, 10, 20, 30, 40);
        decoder.readPosition = 1;
        decoder.refillBytes = new byte[] {50, 60};
        byte[] destination = new byte[6];
        Arrays.fill(destination, (byte) 99);

        int read = PcmBulkReader.drain(
                decoder, destination, 2, 2);

        assertEquals(2, read);
        assertArrayEquals(
                new byte[] {99, 99, 20, 30, 99, 99},
                destination);
        assertEquals(3, decoder.readPosition);
        assertEquals(0, decoder.refillCalls);
    }

    @Test
    void doesNotRefillAgainAfterDrainingAvailableBytes()
            throws IOException {
        var decoder = new FakeDecoder(16, 1, 2);
        decoder.refillBytes = new byte[] {3, 4};
        byte[] destination = new byte[8];

        int read = PcmBulkReader.drain(
                decoder, destination, 0, destination.length);

        assertEquals(2, read);
        assertArrayEquals(
                new byte[] {1, 2, 0, 0, 0, 0, 0, 0},
                destination);
        assertEquals(2, decoder.readPosition);
        assertEquals(0, decoder.refillCalls);
    }

    @Test
    void refillsOnceWhenTheCurrentBlockIsExhausted()
            throws IOException {
        var decoder = new FakeDecoder(16, 1, 2);
        decoder.readPosition = 2;
        decoder.refillBytes = new byte[] {5, 6, 7};
        byte[] destination = new byte[8];

        int read = PcmBulkReader.drain(
                decoder, destination, 1, 6);

        assertEquals(3, read);
        assertArrayEquals(
                new byte[] {0, 5, 6, 7, 0, 0, 0, 0},
                destination);
        assertEquals(3, decoder.readPosition);
        assertEquals(1, decoder.refillCalls);
    }

    @Test
    void returnsMinusOneAfterOneTransientlyEmptyRefill()
            throws IOException {
        var decoder = new FakeDecoder(16);

        int read = PcmBulkReader.drain(
                decoder, new byte[8], 0, 8);

        assertEquals(-1, read);
        assertEquals(0, decoder.readPosition);
        assertEquals(1, decoder.refillCalls);
    }

    @Test
    void propagatesRefillFailureWithoutResettingTheCursor() {
        var decoder = new FakeDecoder(16, 1, 2);
        decoder.readPosition = 2;
        IOException expected = new IOException("decode failed");
        decoder.refillFailure = expected;

        IOException actual = assertThrows(
                IOException.class,
                () -> PcmBulkReader.drain(
                        decoder, new byte[8], 0, 8));

        assertSame(expected, actual);
        assertEquals(2, decoder.readPosition);
        assertEquals(0, decoder.buffer.position());
        assertEquals(1, decoder.refillCalls);
    }

    @Test
    void returnsMinusOneWithoutRefillingWhenTheBufferIsMissing()
            throws IOException {
        var decoder = new FakeDecoder(0);
        decoder.buffer = null;

        int read = PcmBulkReader.drain(
                decoder, new byte[8], 0, 8);

        assertEquals(-1, read);
        assertEquals(0, decoder.refillCalls);
    }

    private static final class FakeDecoder implements PcmDecoderAccess {
        private ByteBuffer buffer;
        private int readPosition;
        private byte[] refillBytes = new byte[0];
        private IOException refillFailure;
        private int refillCalls;

        private FakeDecoder(int capacity, int... initialBytes) {
            buffer = ByteBuffer.allocateDirect(capacity);
            for (int value : initialBytes) {
                buffer.put((byte) value);
            }
        }

        @Override
        public ByteBuffer pcmBuffer() {
            return buffer;
        }

        @Override
        public int pcmReadPosition() {
            return readPosition;
        }

        @Override
        public void pcmReadPosition(int position) {
            readPosition = position;
        }

        @Override
        public void decodeNextPcmBlock() throws IOException {
            refillCalls++;
            if (refillFailure != null) {
                throw refillFailure;
            }
            buffer.put(refillBytes);
        }
    }
}

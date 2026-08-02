package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class DecodedPcmBufferTest {
    @Test
    void finishesAnEmptyDefaultOrderDirectBuffer() {
        var accumulator = new DecodedPcmBuffer();

        ByteBuffer result = accumulator.finish();

        assertTrue(result.isDirect());
        assertEquals(ByteOrder.BIG_ENDIAN, result.order());
        assertEquals(0, result.position());
        assertEquals(0, result.limit());
        assertEquals(0, result.capacity());
    }

    @Test
    void preservesBytesAcrossMultipleChunksWithExactFinalCapacity() {
        byte[] expected = new byte[2 * 64 * 1024 + 137];
        new Random(0x0_14_2026L).nextBytes(expected);
        var accumulator = new DecodedPcmBuffer();
        for (byte value : expected) {
            accumulator.write(value);
        }

        ByteBuffer result = accumulator.finish();

        assertEquals(expected.length, result.capacity());
        assertEquals(0, result.position());
        assertEquals(expected.length, result.limit());
        assertArrayEquals(expected, bytes(result));
    }

    @Test
    void writesTheLowEightBitsLikeByteArrayOutputStream() {
        var accumulator = new DecodedPcmBuffer();

        accumulator.write(-1);
        accumulator.write(0x123);

        assertArrayEquals(
                new byte[] {(byte) 0xff, 0x23},
                bytes(accumulator.finish()));
    }

    @Test
    void appendsBulkPcmAndPreservesATransientMinusOneByte()
            throws IOException {
        var accumulator = new DecodedPcmBuffer();
        var decoder = new TestDecoder(1, 2, 3);
        accumulator.write(9);

        assertEquals(3, accumulator.readFrom(decoder));
        assertEquals(-1, accumulator.readFrom(decoder));

        assertArrayEquals(
                new byte[] {9, 1, 2, 3, (byte) 0xff},
                bytes(accumulator.finish()));
        assertEquals(1, decoder.refillCalls);
    }

    @Test
    void rejectsWritesAndASecondFinishAfterOwnershipTransfer() {
        var accumulator = new DecodedPcmBuffer();
        accumulator.write(42);
        accumulator.finish();

        assertThrows(IllegalStateException.class, () -> accumulator.write(1));
        assertThrows(IllegalStateException.class, accumulator::finish);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.duplicate().get(result);
        return result;
    }

    private static final class TestDecoder implements PcmDecoderAccess {
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(16);
        private int readPosition;
        private int refillCalls;

        private TestDecoder(int... bytes) {
            for (int value : bytes) {
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
        public void decodeNextPcmBlock() {
            refillCalls++;
        }
    }
}

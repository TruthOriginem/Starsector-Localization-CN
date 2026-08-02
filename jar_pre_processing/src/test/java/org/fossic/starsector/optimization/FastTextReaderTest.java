package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class FastTextReaderTest {
    @Test
    void decodesUtf8AcrossByteBoundariesAndRemovesEveryCarriageReturn()
            throws IOException {
        String source = "甲\r\n乙\r丙🙂\r\n终";
        ChunkedInputStream input = new ChunkedInputStream(
                source.getBytes(StandardCharsets.UTF_8), 1);

        String actual = FastTextReader.read(input);

        assertEquals("甲\n乙丙🙂\n终", actual);
        assertEquals(1, input.closeCount);
    }

    @Test
    void handlesContentLargerThanTheReusableCharacterBuffer() throws IOException {
        String source = ("中文🙂\r\n").repeat(5_000);
        ChunkedInputStream input = new ChunkedInputStream(
                source.getBytes(StandardCharsets.UTF_8), 37);

        String actual = FastTextReader.read(input);

        assertEquals(("中文🙂\n").repeat(5_000), actual);
        assertEquals(1, input.closeCount);
    }

    @Test
    void propagatesReadFailureAndStillClosesInput() {
        IOException readFailure = new IOException("read failed");
        FailingInputStream input = new FailingInputStream(readFailure, null);

        IOException actual = assertThrows(
                IOException.class, () -> FastTextReader.read(input));

        assertSame(readFailure, actual);
        assertTrue(input.closed);
    }

    @Test
    void closeFailureTakesPrecedenceWhenReadAndCloseBothFail() {
        IOException readFailure = new IOException("read failed");
        IOException closeFailure = new IOException("close failed");
        FailingInputStream input =
                new FailingInputStream(readFailure, closeFailure);

        IOException actual = assertThrows(
                IOException.class, () -> FastTextReader.read(input));

        assertSame(closeFailure, actual);
        assertTrue(input.closed);
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int maxChunk;
        private int offset;
        private int closeCount;

        private ChunkedInputStream(byte[] data, int maxChunk) {
            this.data = data;
            this.maxChunk = maxChunk;
        }

        @Override
        public int read() {
            if (offset == data.length) {
                return -1;
            }
            return data[offset++] & 0xff;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset == data.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, maxChunk), data.length - offset);
            System.arraycopy(data, offset, target, targetOffset, count);
            offset += count;
            return count;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class FailingInputStream extends InputStream {
        private final IOException readFailure;
        private final IOException closeFailure;
        private boolean closed;

        private FailingInputStream(
                IOException readFailure, IOException closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() throws IOException {
            throw readFailure;
        }

        @Override
        public int read(byte[] target, int offset, int length)
                throws IOException {
            throw readFailure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}

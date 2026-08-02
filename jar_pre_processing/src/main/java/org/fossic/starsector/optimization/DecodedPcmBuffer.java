package org.fossic.starsector.optimization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 把解码器产生的 PCM 收集为 OpenAL 所需的 direct buffer。
 *
 * <p>原版先写入 {@code ByteArrayOutputStream}，然后依次执行扩容复制、
 * {@code toByteArray()} 复制和 heap-to-direct 复制。本实现用固定块累加，并可直接批量搬运
 * 解码块；总长度确定后只分配一次精确容量 direct buffer。解码器的终止条件和关闭时机仍由
 * 调用方保持。
 */
public final class DecodedPcmBuffer {
    private static final int CHUNK_BYTES = 64 * 1024;

    private final List<byte[]> fullChunks = new ArrayList<>();
    private byte[] currentChunk;
    private int currentChunkBytes;
    private int totalBytes;
    private boolean finished;

    public DecodedPcmBuffer() {
    }

    public void write(int value) {
        ensureOpen();
        ensureWritableChunk();

        currentChunk[currentChunkBytes++] = (byte) value;
        totalBytes = addLength(1);
    }

    /**
     * 把解码器当前 PCM 块直接写入累加块，避免中间数组和逐字节调用。
     *
     * <p>返回 {@code -1} 时仍按原版 {@code ByteArrayOutputStream.write(-1)} 的语义追加
     * {@code 0xff}；调用方继续负责完成条件和关闭时机。
     */
    public int readFrom(PcmDecoderAccess decoder) throws IOException {
        ensureOpen();
        ensureWritableChunk();

        int read = PcmBulkReader.drain(
                decoder,
                currentChunk,
                currentChunkBytes,
                currentChunk.length - currentChunkBytes);
        if (read < 0) {
            write(read);
            return read;
        }

        currentChunkBytes += read;
        totalBytes = addLength(read);
        return read;
    }

    private void ensureWritableChunk() {
        if (currentChunk == null) {
            currentChunk = new byte[CHUNK_BYTES];
        } else if (currentChunkBytes == currentChunk.length) {
            fullChunks.add(currentChunk);
            currentChunk = new byte[CHUNK_BYTES];
            currentChunkBytes = 0;
        }
    }

    private int addLength(int length) {
        try {
            return Math.addExact(totalBytes, length);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Decoded PCM exceeds the maximum Java buffer size",
                    exception);
        }
    }

    public ByteBuffer finish() {
        ensureOpen();
        finished = true;

        ByteBuffer result = ByteBuffer.allocateDirect(totalBytes);
        for (byte[] chunk : fullChunks) {
            result.put(chunk);
        }
        if (currentChunkBytes > 0) {
            result.put(currentChunk, 0, currentChunkBytes);
        }
        result.flip();

        fullChunks.clear();
        currentChunk = null;
        currentChunkBytes = 0;
        return result;
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException(
                    "Decoded PCM buffer ownership was already transferred");
        }
    }
}

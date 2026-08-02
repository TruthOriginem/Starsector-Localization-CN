package org.fossic.starsector.optimization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** 批量搬运原版 OGG 解码器已经生成的 PCM，避免逐字节虚调用。 */
public final class PcmBulkReader {
    private PcmBulkReader() {
    }

    /**
     * 最多搬运当前解码块；仅当调用时块已耗尽，才触发一次解码。
     *
     * <p>不在一次调用内跨越第二个块，保证调用方仍能在每个解码块之间执行原版完成条件检查。
     * 绝对批量读取不会改变 {@link ByteBuffer#position()}，该位置仍表示解码器写入终点。
     */
    public static int drain(
            PcmDecoderAccess decoder,
            byte[] destination,
            int offset,
            int length)
            throws IOException {
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(destination, "destination");
        Objects.checkFromIndexSize(offset, length, destination.length);

        ByteBuffer buffer = decoder.pcmBuffer();
        if (buffer == null) {
            return -1;
        }

        int readPosition = decoder.pcmReadPosition();
        int writePosition = buffer.position();
        if (readPosition >= writePosition) {
            buffer.clear();
            decoder.decodeNextPcmBlock();
            decoder.pcmReadPosition(0);
            readPosition = 0;
            writePosition = buffer.position();
        }

        if (readPosition >= writePosition) {
            return -1;
        }

        int count = Math.min(length, writePosition - readPosition);
        buffer.get(readPosition, destination, offset, count);
        decoder.pcmReadPosition(readPosition + count);
        return count;
    }
}

package org.fossic.starsector.optimization;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 与游戏 {@code LoadingUtils} 文本读取语义兼容的低分配 UTF-8 reader。
 *
 * <p>原实现为每个资源固定分配 1 MiB byte array，把每个 byte chunk 单独解码成
 * String，最后再用正则删除所有 CR。本实现让 {@link InputStreamReader} 跨底层读取边界
 * 保持 UTF-8 decoder 状态，复用每线程 char buffer，并在追加时直接跳过 CR。
 *
 * <p>和原实现一样，本方法始终关闭传入的 stream；若读取与关闭同时失败，关闭异常优先。
 */
public final class FastTextReader {
    private static final int BUFFER_SIZE = 8192;
    private static final ThreadLocal<char[]> CHAR_BUFFER =
            ThreadLocal.withInitial(() -> new char[BUFFER_SIZE]);

    private FastTextReader() {
    }

    public static String read(InputStream input) throws IOException {
        return read(input, false);
    }

    /** resource-stream-safety 组使用；仅在 close 成功后释放 high-level 所有权。 */
    public static String readTracked(InputStream input) throws IOException {
        return read(input, true);
    }

    private static String read(InputStream input, boolean tracked)
            throws IOException {
        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        char[] buffer = CHAR_BUFFER.get();
        try {
            int count;
            while ((count = reader.read(buffer, 0, buffer.length)) != -1) {
                appendWithoutCarriageReturns(result, buffer, count);
            }
        } finally {
            if (tracked) {
                OwnedResourceStreams.closeAndForgetCurrentPairStream(input);
            } else {
                input.close();
            }
        }
        return result.toString();
    }

    private static void appendWithoutCarriageReturns(
            StringBuilder target, char[] buffer, int count) {
        int segmentStart = 0;
        for (int i = 0; i < count; i++) {
            if (buffer[i] != '\r') {
                continue;
            }
            target.append(buffer, segmentStart, i - segmentStart);
            segmentStart = i + 1;
        }
        target.append(buffer, segmentStart, count - segmentStart);
    }
}

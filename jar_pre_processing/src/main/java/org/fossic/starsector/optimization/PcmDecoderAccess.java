package org.fossic.starsector.optimization;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 暴露原版 OGG 解码器中批量取出 PCM 所需的最小状态。
 *
 * <p>实现由 ASM 以薄桥接方式注入 {@code sound.F}；解码、边界和异常语义均集中在可测试的
 * {@link PcmBulkReader} 中。
 */
public interface PcmDecoderAccess {
    ByteBuffer pcmBuffer();

    int pcmReadPosition();

    void pcmReadPosition(int position);

    void decodeNextPcmBlock() throws IOException;
}

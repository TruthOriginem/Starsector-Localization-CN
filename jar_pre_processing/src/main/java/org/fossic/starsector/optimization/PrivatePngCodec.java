package org.fossic.starsector.optimization;

import java.io.IOException;

/** 私有 PNG 后端与父 classloader 之间只传递 JDK/本项目类型。 */
public interface PrivatePngCodec {
    PngDecodedImage decode(byte[] encoded) throws IOException;
}

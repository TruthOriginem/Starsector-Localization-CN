package org.fossic.starsector.optimization;

/** 私有 zstd-jni 后端边界；不得暴露任何第三方类型。 */
public interface PrivateZstdCodec {
    byte[] compress(byte[] source, int level);

    byte[] decompress(byte[] source, int destinationSize);

    long compressBound(long sourceSize);

    long frameContentSize(byte[] source);
}

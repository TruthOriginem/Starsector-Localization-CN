package org.fossic.starsector.privateimpl.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.util.Native;
import java.nio.file.Path;
import org.fossic.starsector.optimization.PrivateZstdCodec;
import org.fossic.starsector.optimization.StableZstdNativeLibrary;

/** 只由 PrivateDependencyClassLoader 定义并持有私有 native library。 */
public final class ZstdProvider implements PrivateZstdCodec {
    public ZstdProvider() {
        Path library = StableZstdNativeLibrary.prepare(
                getClass().getClassLoader(),
                "win/amd64/libzstd-jni-1.5.7-4.dll");
        try {
            System.load(library.toString());
        } catch (UnsatisfiedLinkError failure) {
            // 正常游戏只创建一个 core 私有 loader。同一路径不能再绑定到第二个
            // classloader；明确失败，禁止退回随机 DLL 副本掩盖架构错误。
            throw new LinkageError(
                    "加载私有 zstd native 失败；该 provider 只支持单一核心 "
                            + "classloader: " + library,
                    failure);
        }
        Native.assumeLoaded();
    }

    @Override
    public byte[] compress(byte[] source, int level) {
        return Zstd.compress(source, level);
    }

    @Override
    public byte[] decompress(byte[] source, int destinationSize) {
        return Zstd.decompress(source, destinationSize);
    }

    @Override
    public long compressBound(long sourceSize) {
        return Zstd.compressBound(sourceSize);
    }

    @Override
    public long frameContentSize(byte[] source) {
        return Zstd.getFrameContentSize(source);
    }
}

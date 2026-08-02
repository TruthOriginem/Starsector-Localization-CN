package org.fossic.starsector.optimization;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 独立 JVM 中验证私有 zstd native 发布；由单元测试启动。 */
public final class PrivateDependencyChildMain {
    private PrivateDependencyChildMain() {
    }

    public static void main(String[] args) {
        if (args.length > 0
                && "two-isolated-loaders".equals(args[0])) {
            verifySecondPrivateLoaderFailsClosed();
            return;
        }
        byte[] source = "private-zstd-child"
                .getBytes(StandardCharsets.UTF_8);
        byte[] parentCompressed = null;
        if (args.length > 0 && "top-first".equals(args[0])) {
            parentCompressed = com.github.luben.zstd.Zstd.compress(
                    source, 1);
            requireRoundTrip(
                    source,
                    com.github.luben.zstd.Zstd.decompress(
                            parentCompressed, source.length));
        }
        byte[] compressed = IsolatedZstdCodec.compress(source, 1);
        requireRoundTrip(
                source,
                IsolatedZstdCodec.decompress(compressed, source.length));
        if (parentCompressed != null) {
            requireRoundTrip(
                    source,
                    com.github.luben.zstd.Zstd.decompress(
                            parentCompressed, source.length));
            Class<?> privateZstd;
            try {
                privateZstd = IsolatedZstdCodec
                        .providerClassLoaderForTests()
                        .loadClass("com.github.luben.zstd.Zstd");
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
            if (privateZstd == com.github.luben.zstd.Zstd.class) {
                throw new AssertionError("private zstd leaked to parent");
            }
        }
    }

    private static void requireRoundTrip(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("isolated zstd round trip failed");
        }
    }

    private static void verifySecondPrivateLoaderFailsClosed() {
        byte[] source = "two-private-zstd-loaders"
                .getBytes(StandardCharsets.UTF_8);
        PrivateZstdCodec first = newPrivateCodec();
        byte[] compressed = first.compress(source, 1);
        requireRoundTrip(
                source, first.decompress(compressed, source.length));
        try {
            newPrivateCodec();
            throw new AssertionError(
                    "second private zstd loader unexpectedly succeeded");
        } catch (LinkageError expected) {
            if (!expected.getMessage().contains(
                    "单一核心 classloader")) {
                throw new AssertionError(
                        "missing private-loader diagnostic", expected);
            }
        }
    }

    private static PrivateZstdCodec newPrivateCodec() {
        return PrivateDependencyClassLoader.loadProvider(
                "META-INF/starsector-optimization/private/zstd/",
                "org.fossic.starsector.privateimpl.zstd.ZstdProvider",
                PrivateZstdCodec.class,
                new PrivateDependencyClassLoader.OwnedPackage(
                        "org.fossic.starsector.privateimpl.zstd.",
                        "org.fossic.starsector.privateimpl.zstd."
                                + "ZstdProvider"),
                new PrivateDependencyClassLoader.OwnedPackage(
                        "com.github.luben.zstd.",
                        "com.github.luben.zstd.Zstd"));
    }
}

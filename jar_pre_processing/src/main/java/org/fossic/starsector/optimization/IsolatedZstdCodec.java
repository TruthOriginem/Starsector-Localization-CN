package org.fossic.starsector.optimization;

/** zstd 私有 classloader 的无第三方类型门面。 */
public final class IsolatedZstdCodec {
    private static final String ROOT =
            "META-INF/starsector-optimization/private/zstd/";

    private IsolatedZstdCodec() {
    }

    public static byte[] compress(byte[] source, int level) {
        return Holder.CODEC.compress(source, level);
    }

    public static byte[] decompress(byte[] source, int destinationSize) {
        return Holder.CODEC.decompress(source, destinationSize);
    }

    public static long compressBound(long sourceSize) {
        return Holder.CODEC.compressBound(sourceSize);
    }

    public static long frameContentSize(byte[] source) {
        return Holder.CODEC.frameContentSize(source);
    }

    static ClassLoader providerClassLoaderForTests() {
        return Holder.CODEC.getClass().getClassLoader();
    }

    private static final class Holder {
        private static final PrivateZstdCodec CODEC =
                PrivateDependencyClassLoader.loadProvider(
                        ROOT,
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

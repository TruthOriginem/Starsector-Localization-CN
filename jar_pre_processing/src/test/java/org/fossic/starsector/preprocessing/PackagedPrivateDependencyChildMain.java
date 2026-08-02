package org.fossic.starsector.preprocessing;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/** 在独立 JVM 中加载合成游戏 jar，验证生产形态的私有 zstd 资源树。 */
public final class PackagedPrivateDependencyChildMain {
    private PackagedPrivateDependencyChildMain() {
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        boolean hasPrecedingJar = args.length >= 2;
        boolean expectUnavailable = args.length >= 3
                && "expect-unavailable".equals(args[2]);
        java.net.URL[] urls = hasPrecedingJar
                ? new java.net.URL[]{
                    Path.of(args[1]).toUri().toURL(),
                    jar.toUri().toURL()
                }
                : new java.net.URL[]{jar.toUri().toURL()};
        try (URLClassLoader runtime = new URLClassLoader(
                urls,
                ClassLoader.getPlatformClassLoader())) {
            try {
                runtime.loadClass("com.github.luben.zstd.Zstd");
                throw new AssertionError("top-level zstd class was packaged");
            } catch (ClassNotFoundException expected) {
                // 只能由 PrivateDependencyClassLoader 从私有资源树定义。
            }
            Class<?> codec = runtime.loadClass(
                    "org.fossic.starsector.optimization.IsolatedZstdCodec");
            byte[] source = "packaged-private-zstd"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] compressed;
            try {
                compressed = (byte[]) codec
                        .getMethod("compress", byte[].class, int.class)
                        .invoke(null, source, 1);
            } catch (InvocationTargetException failure) {
                if (expectUnavailable
                        && containsLinkageError(failure.getCause())) {
                    return;
                }
                throw failure;
            }
            if (expectUnavailable) {
                throw new AssertionError(
                        "private zstd native leaked from preceding jar");
            }
            byte[] restored = (byte[]) codec
                    .getMethod("decompress", byte[].class, int.class)
                    .invoke(null, compressed, source.length);
            if (!Arrays.equals(source, restored)) {
                throw new AssertionError("packaged zstd round trip failed");
            }
        }
    }

    private static boolean containsLinkageError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof LinkageError) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

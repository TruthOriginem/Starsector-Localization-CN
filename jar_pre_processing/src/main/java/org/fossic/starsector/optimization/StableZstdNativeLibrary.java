package org.fossic.starsector.optimization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 把内嵌 zstd DLL 内容寻址地稳定发布，避免 zstd-jni 每次启动泄漏随机临时文件。 */
public final class StableZstdNativeLibrary {
    public static final String DIRECTORY_PROPERTY =
            "starsector.optimization.zstdNativeDirectory";

    private static final int MAXIMUM_LIBRARY_BYTES = 32 * 1024 * 1024;
    private static final String CACHE_SUFFIX = ".dll";
    private static final Object PUBLICATION_MONITOR = new Object();

    private StableZstdNativeLibrary() {
    }

    public static Path prepare(ClassLoader resources, String resourceName) {
        byte[] embedded;
        try (InputStream input = resources.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("内嵌 zstd native 缺失: " + resourceName);
            }
            embedded = input.readNBytes(MAXIMUM_LIBRARY_BYTES + 1);
            if (embedded.length == 0
                    || embedded.length > MAXIMUM_LIBRARY_BYTES
                    || input.read() != -1) {
                throw new IOException("内嵌 zstd native 大小异常");
            }
        } catch (IOException | RuntimeException failure) {
            throw new LinkageError("读取内嵌 zstd native 失败", failure);
        }

        String hash = sha256(embedded);
        Path configured = null;
        Throwable primaryFailure;
        try {
            configured = configuredDirectory();
            return publishAndRegister(configured, embedded, hash,
                    usesDefaultDirectory());
        } catch (IOException | RuntimeException failure) {
            primaryFailure = failure;
        }
        try {
            Path fallback = fallbackDirectory();
            if (fallback.equals(configured)) {
                throw new LinkageError(
                        "发布 zstd native 失败: " + configured,
                        primaryFailure);
            }
            return publishAndRegister(fallback, embedded, hash, true);
        } catch (IOException | RuntimeException fallbackFailure) {
            fallbackFailure.addSuppressed(primaryFailure);
            throw new LinkageError(
                    "发布 zstd native 失败（配置目录="
                            + configured + "）",
                    fallbackFailure);
        }
    }

    private static Path publishAndRegister(
            Path root,
            byte[] embedded,
            String hash,
            boolean pruneObsoleteVersions) throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IOException("zstd native 缓存根不安全: " + root);
        }
        Path target = root.resolve("lib-" + hash + CACHE_SUFFIX)
                .toAbsolutePath().normalize();
        if (!root.equals(target.getParent())) {
            throw new IOException("zstd native 发布路径越界");
        }

        synchronized (PUBLICATION_MONITOR) {
            Path lockPath = root.resolve(".publish.lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (!matches(target, hash)) {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(target)
                                || !Files.deleteIfExists(target)) {
                            throw new IOException(
                                    "无法替换损坏的 zstd native: " + target);
                        }
                    }
                    publish(target, embedded);
                    if (!matches(target, hash)) {
                        throw new IOException(
                                "zstd native 发布后校验失败: " + target);
                    }
                }
                // 在发布锁内刷新活跃版本，缩小其它进程按容量清理时从
                // prepare 返回到 System.load 之间误删旧 hash 的窗口。
                Files.setLastModifiedTime(
                        target, FileTime.fromMillis(
                                System.currentTimeMillis()));
            }
        }

        PersistentCacheCleaner.Policy policy =
                new PersistentCacheCleaner.Policy(
                        "zstd-native",
                        root,
                        PersistentCacheCleaner.Layout.FLAT_HASH,
                        "lib-",
                        CACHE_SUFFIX,
                        PersistentCacheMaintenance.retentionMillis(),
                        8L * 1024L * 1024L,
                        2,
                        pruneObsoleteVersions);
        PersistentCacheMaintenance.recordUse(policy, target);
        return target;
    }

    private static void publish(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(
                    temporary,
                    bytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException raced) {
                // 文件锁覆盖正常路径；保留此分支应对不遵守锁的旧进程。
                if (!Files.isRegularFile(
                        target, LinkOption.NOFOLLOW_LINKS)) {
                    throw raced;
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matches(Path path, String expectedHash) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path)
                    && expectedHash.equals(sha256(path));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static Path configuredDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        Path directory = configured == null || configured.isBlank()
                ? Path.of(
                        System.getProperty("user.dir", "."),
                        "cache",
                        "startup-optimization",
                        "zstd-native",
                        "v1")
                : Path.of(configured);
        return directory.toAbsolutePath().normalize();
    }

    private static boolean usesDefaultDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        return configured == null || configured.isBlank();
    }

    private static Path fallbackDirectory() {
        return Path.of(
                System.getProperty("java.io.tmpdir", "."),
                "starsector-startup-optimization",
                "zstd-native",
                "v1").toAbsolutePath().normalize();
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}

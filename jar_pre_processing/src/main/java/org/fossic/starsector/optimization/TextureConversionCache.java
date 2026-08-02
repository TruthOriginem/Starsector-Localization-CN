package org.fossic.starsector.optimization;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * 以编码图片内容的 SHA-256 为唯一身份，持久化游戏最终上传的纹理像素和代表色。
 *
 * <p>这里刻意不使用路径、mtime 或文件大小作为命中依据。每次启动仍读取编码图片并计算
 * 内容摘要，只有摘要完全相同才跳过图片解码和像素转换。因此 mod 覆盖顺序、同大小同时间
 * 替换和动态字体生成内容都不会造成错误命中。缓存文件损坏、格式变化或 native codec
 * 不可用时一律返回 miss，让调用方执行原解码路径。
 */
public final class TextureConversionCache {
    public static final String DIRECTORY_PROPERTY =
            "starsector.optimization.textureCacheDirectory";
    public static final String MINIMUM_BYTES_PROPERTY =
            "starsector.optimization.textureCacheMinimumBytes";
    public static final String MAXIMUM_BYTES_PROPERTY =
            "starsector.optimization.textureCacheMaximumBytes";
    public static final String DISABLE_PROPERTY =
            "starsector.optimization.disableTextureCache";

    private static final long MAGIC = 0x5353544558433031L; // SSTEXC01
    private static final int VERSION = 1;
    /** 转换、解码或颜色统计语义变化时必须递增。 */
    private static final String CACHE_IDENTITY =
            "texture-conversion-0.98a-RC8-v2";
    private static final int DEFAULT_MINIMUM_BYTES = 64 * 1024;
    private static final long DEFAULT_MAXIMUM_BYTES =
            2L * 1024L * 1024L * 1024L;
    private static final int MAX_PIXEL_BYTES = 512 * 1024 * 1024;
    /** 固定字段 + 64 位 ASCII SHA-256 的 DataOutputStream 编码长度。 */
    private static final int CACHE_HEADER_BYTES = 123;
    private static final int HASH_LENGTH = 64;
    private static final String CACHE_SUFFIX = ".sstexc.zst";
    private static final Object[] LOCKS = new Object[64];

    private static volatile Boolean zstdAvailable;
    private static volatile PersistentCacheCleaner.Policy cachedCleanupPolicy;

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private TextureConversionCache() {
    }

    public static boolean isEnabled() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        Boolean available = zstdAvailable;
        if (available != null) {
            return available;
        }
        synchronized (TextureConversionCache.class) {
            available = zstdAvailable;
            if (available == null) {
                available = probeZstd();
                zstdAvailable = available;
            }
        }
        return available;
    }

    public static String sourceHash(byte[] encodedSource) {
        return sourceHashForIdentity(encodedSource, CACHE_IDENTITY);
    }

    static String sourceHashForIdentity(
            byte[] encodedSource, String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(encodedSource);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    static CachedTexture load(String sourceHash) {
        if (!isEnabled() || !validHash(sourceHash)) {
            return null;
        }
        PersistentCacheCleaner.Policy policy;
        Path target;
        try {
            policy = cleanupPolicy();
            PersistentCacheMaintenance.register(policy);
            target = safeCacheFile(
                    policy.directory(), sourceHash, false);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return null;
        }
        if (target == null || !Files.isRegularFile(
                target, LinkOption.NOFOLLOW_LINKS)) {
            TextureCacheDiagnostics.recordMiss();
            return null;
        }

        synchronized (lockFor(sourceHash)) {
            if (!Files.isRegularFile(
                    target, LinkOption.NOFOLLOW_LINKS)) {
                TextureCacheDiagnostics.recordMiss();
                return null;
            }
            PersistentCacheMaintenance.recordUse(policy, target);
            try {
                CachedTexture cached = read(target, sourceHash);
                TextureCacheDiagnostics.recordHit(
                        cached.pixelBytes().length,
                        Files.size(target));
                PersistentCacheMaintenance.recordUse(policy, target);
                return cached;
            } catch (IOException | RuntimeException | LinkageError failure) {
                deleteQuietly(target);
                TextureCacheDiagnostics.recordCorruption();
                return null;
            }
        }
    }

    static boolean store(
            String sourceHash,
            int imageWidth,
            int imageHeight,
            boolean hasAlpha,
            TexturePixelConverter.Result result) {
        if (!isEnabled()
                || !validHash(sourceHash)
                || result == null
                || result.buffer().capacity() < minimumBytes()) {
            return false;
        }

        if (!isCacheablePayload(
                result.paddedWidth(),
                result.paddedHeight(),
                hasAlpha,
                result.buffer().capacity())) {
            return false;
        }
        CachedTexture cached;
        PersistentCacheCleaner.Policy policy;
        Path target;
        try {
            cached = snapshot(
                    imageWidth, imageHeight, hasAlpha, result);
            policy = cleanupPolicy();
            PersistentCacheMaintenance.register(policy);
            target = safeCacheFile(
                    policy.directory(), sourceHash, true);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return false;
        }
        if (target == null) {
            return false;
        }
        PersistentCacheMaintenance.recordUse(policy, target);
        synchronized (lockFor(sourceHash)) {
            if (Files.isRegularFile(
                    target, LinkOption.NOFOLLOW_LINKS)) {
                PersistentCacheMaintenance.recordUse(policy, target);
                return true;
            }

            Path temporary = temporaryFile(target);
            try {
                byte[] compressed = IsolatedZstdCodec.compress(
                        cached.pixelBytes(), 1);
                write(temporary, sourceHash, cached, compressed);
                moveAtomic(temporary, target);
                TextureCacheDiagnostics.recordStore(
                        cached.pixelBytes().length,
                        Files.size(target));
                PersistentCacheMaintenance.recordUse(policy, target);
                return true;
            } catch (IOException | RuntimeException | LinkageError failure) {
                deleteQuietly(temporary);
                return false;
            }
        }
    }

    static void resetForTests() {
        zstdAvailable = null;
        cachedCleanupPolicy = null;
        TextureCacheDiagnostics.resetForTests();
        TextureSourceTracker.resetForTests();
        PersistentCacheMaintenance.resetForTests();
    }

    private static boolean probeZstd() {
        try {
            byte[] compressed = IsolatedZstdCodec.compress(new byte[0], 1);
            return IsolatedZstdCodec.decompress(compressed, 0).length == 0;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static CachedTexture snapshot(
            int imageWidth,
            int imageHeight,
            boolean hasAlpha,
            TexturePixelConverter.Result result) {
        int expected = expectedPixelBytes(
                result.paddedWidth(), result.paddedHeight(), hasAlpha);
        if (imageWidth <= 0
                || imageHeight <= 0
                || result.paddedWidth() < imageWidth
                || result.paddedHeight() < imageHeight
                || result.buffer().capacity() != expected) {
            throw new IllegalArgumentException(
                    "inconsistent texture conversion result");
        }
        byte[] pixels = new byte[expected];
        ByteBuffer source = result.buffer().duplicate();
        source.position(0);
        source.limit(expected);
        source.get(pixels);
        return new CachedTexture(
                imageWidth,
                imageHeight,
                hasAlpha,
                result.paddedWidth(),
                result.paddedHeight(),
                result.averageColor(),
                result.brightColor(),
                result.medianColor(),
                pixels);
    }

    private static CachedTexture read(Path source, String expectedHash)
            throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(source)))) {
            if (input.readLong() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("texture cache header mismatch");
            }
            String storedHash = input.readUTF();
            if (!expectedHash.equals(storedHash)) {
                throw new IOException("texture cache source hash mismatch");
            }
            int imageWidth = input.readInt();
            int imageHeight = input.readInt();
            boolean hasAlpha = input.readBoolean();
            int paddedWidth = input.readInt();
            int paddedHeight = input.readInt();
            Color average = color(input.readInt());
            Color bright = color(input.readInt());
            Color median = color(input.readInt());
            int rawLength = input.readInt();
            long expectedCrc = input.readLong();
            int compressedLength = input.readInt();

            int expectedLength = expectedPixelBytes(
                    paddedWidth, paddedHeight, hasAlpha);
            long fileBytes = Files.size(source);
            if (imageWidth <= 0
                    || imageHeight <= 0
                    || paddedWidth < imageWidth
                    || paddedHeight < imageHeight
                    || !isPowerOfTwoAtLeastTwo(paddedWidth)
                    || !isPowerOfTwoAtLeastTwo(paddedHeight)
                    || rawLength != expectedLength
                    || !validCompressedEnvelope(
                            fileBytes, rawLength, compressedLength)) {
                throw new IOException("invalid texture cache dimensions");
            }

            byte[] compressed = input.readNBytes(compressedLength);
            if (compressed.length != compressedLength || input.read() != -1) {
                throw new EOFException("truncated or trailing texture cache data");
            }
            if (!zstdFrameMatches(compressed, rawLength)) {
                throw new IOException("texture cache Zstd frame size mismatch");
            }
            byte[] pixels = IsolatedZstdCodec.decompress(
                    compressed, rawLength);
            if (pixels.length != rawLength || crc32(pixels) != expectedCrc) {
                throw new IOException("texture cache checksum mismatch");
            }
            return new CachedTexture(
                    imageWidth,
                    imageHeight,
                    hasAlpha,
                    paddedWidth,
                    paddedHeight,
                    average,
                    bright,
                    median,
                    pixels);
        }
    }

    private static void write(
            Path target,
            String sourceHash,
            CachedTexture cached,
            byte[] compressed) throws IOException {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)))) {
            output.writeLong(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(sourceHash);
            output.writeInt(cached.imageWidth());
            output.writeInt(cached.imageHeight());
            output.writeBoolean(cached.hasAlpha());
            output.writeInt(cached.paddedWidth());
            output.writeInt(cached.paddedHeight());
            output.writeInt(cached.averageColor().getRGB());
            output.writeInt(cached.brightColor().getRGB());
            output.writeInt(cached.medianColor().getRGB());
            output.writeInt(cached.pixelBytes().length);
            output.writeLong(crc32(cached.pixelBytes()));
            output.writeInt(compressed.length);
            output.write(compressed);
        }
    }

    private static int expectedPixelBytes(
            int paddedWidth, int paddedHeight, boolean hasAlpha) {
        if (!isPowerOfTwoAtLeastTwo(paddedWidth)
                || !isPowerOfTwoAtLeastTwo(paddedHeight)) {
            throw new IllegalArgumentException("invalid padded dimensions");
        }
        try {
            int components = hasAlpha ? 4 : 3;
            int result = Math.multiplyExact(
                    Math.multiplyExact(paddedWidth, paddedHeight),
                    components);
            if (result <= 0 || result > MAX_PIXEL_BYTES) {
                throw new IllegalArgumentException(
                        "texture payload exceeds safe limit");
            }
            return result;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "texture payload size overflow", overflow);
        }
    }

    static boolean isCacheablePayload(
            int paddedWidth,
            int paddedHeight,
            boolean hasAlpha,
            int bufferCapacity) {
        if (!isPowerOfTwoAtLeastTwo(paddedWidth)
                || !isPowerOfTwoAtLeastTwo(paddedHeight)
                || bufferCapacity <= 0) {
            return false;
        }
        long components = hasAlpha ? 4L : 3L;
        long expected = (long) paddedWidth
                * (long) paddedHeight * components;
        return expected > 0L
                && expected <= MAX_PIXEL_BYTES
                && expected == bufferCapacity;
    }

    static boolean validCompressedEnvelopeForTests(
            long fileBytes, int rawLength, int compressedLength) {
        return validCompressedEnvelope(
                fileBytes, rawLength, compressedLength);
    }

    static boolean zstdFrameMatchesForTests(
            byte[] compressed, int rawLength) {
        return zstdFrameMatches(compressed, rawLength);
    }

    private static boolean validCompressedEnvelope(
            long fileBytes, int rawLength, int compressedLength) {
        if (rawLength <= 0 || rawLength > MAX_PIXEL_BYTES
                || compressedLength <= 0
                || fileBytes != CACHE_HEADER_BYTES + (long) compressedLength) {
            return false;
        }
        try {
            long bound = IsolatedZstdCodec.compressBound(rawLength);
            return bound >= 0L && compressedLength <= bound;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean zstdFrameMatches(
            byte[] compressed, int rawLength) {
        try {
            return IsolatedZstdCodec.frameContentSize(compressed) == rawLength;
        } catch (RuntimeException | LinkageError invalid) {
            return false;
        }
    }

    private static boolean isPowerOfTwoAtLeastTwo(int value) {
        return value >= 2 && (value & (value - 1)) == 0;
    }

    private static int minimumBytes() {
        return Math.max(0, Integer.getInteger(
                MINIMUM_BYTES_PROPERTY, DEFAULT_MINIMUM_BYTES));
    }

    private static Path safeCacheFile(
            Path directory, String sourceHash, boolean createDirectories)
            throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (createDirectories) {
            Files.createDirectories(root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return null;
        }
        Path shard = root.resolve(sourceHash.substring(0, 2));
        if (createDirectories) {
            try {
                Files.createDirectory(shard);
            } catch (FileAlreadyExistsException existing) {
                // Validate the existing path without following it below.
            }
        }
        if (!Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(shard)) {
            return null;
        }
        Path target = shard.resolve(sourceHash + CACHE_SUFFIX)
                .toAbsolutePath().normalize();
        return target.startsWith(root) ? target : null;
    }

    static Path temporaryFile(Path target) {
        return target.resolveSibling(
                target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    }

    private static Path cacheDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        Path directory = configured == null || configured.isBlank()
                ? Path.of("cache", "startup-optimization", "textures", "v2")
                : Path.of(configured);
        return directory.toAbsolutePath().normalize();
    }

    private static PersistentCacheCleaner.Policy cleanupPolicy() {
        PersistentCacheCleaner.Policy cached = cachedCleanupPolicy;
        if (cached != null) {
            return cached;
        }
        synchronized (TextureConversionCache.class) {
            cached = cachedCleanupPolicy;
            if (cached == null) {
                String configured = System.getProperty(DIRECTORY_PROPERTY);
                boolean usesDefaultDirectory = configured == null
                        || configured.isBlank();
                cached = new PersistentCacheCleaner.Policy(
                        "textures",
                        cacheDirectory(),
                        PersistentCacheCleaner.Layout.HASH_SHARDED,
                        "",
                        CACHE_SUFFIX,
                        PersistentCacheMaintenance.retentionMillis(),
                        PersistentCacheMaintenance.configuredMaximumBytes(
                                MAXIMUM_BYTES_PROPERTY,
                                DEFAULT_MAXIMUM_BYTES),
                        Integer.MAX_VALUE,
                        usesDefaultDirectory);
                cachedCleanupPolicy = cached;
            }
            return cached;
        }
    }

    private static Object lockFor(String sourceHash) {
        return LOCKS[(sourceHash.hashCode() & Integer.MAX_VALUE)
                % LOCKS.length];
    }

    private static boolean validHash(String sourceHash) {
        if (sourceHash == null || sourceHash.length() != HASH_LENGTH) {
            return false;
        }
        for (int index = 0; index < sourceHash.length(); index++) {
            char character = sourceHash.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static Color color(int argb) throws IOException {
        Color color = new Color(argb, true);
        if (color.getAlpha() != 255) {
            throw new IOException("cached representative color is not opaque");
        }
        return color;
    }

    private static long crc32(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        return checksum.getValue();
    }

    private static void moveAtomic(Path source, Path target)
            throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    record CachedTexture(
            int imageWidth,
            int imageHeight,
            boolean hasAlpha,
            int paddedWidth,
            int paddedHeight,
            Color averageColor,
            Color brightColor,
            Color medianColor,
            byte[] pixelBytes) {
    }
}

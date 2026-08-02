package org.fossic.starsector.optimization;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * 按 OGG 编码内容缓存游戏解码完成、即将上传 OpenAL 的 PCM 数据。
 *
 * <p>ASM wrapper 把原解码方法重命名为 {@code starsector$decodePcmUncached}，本类只通过
 * 反射接触混淆的声音类。缓存命中会恢复 direct {@link ByteBuffer}、采样率和声道数；
 * 文件损坏、格式不符或结果恢复失败时均重新调用原解码器，因此不改变 mod 资源覆盖语义。
 */
public final class DecodedPcmCache {
    public static final String DIRECTORY_PROPERTY =
            "starsector.optimization.pcmCacheDirectory";
    public static final String MINIMUM_BYTES_PROPERTY =
            "starsector.optimization.pcmCacheMinimumBytes";
    public static final String MAXIMUM_BYTES_PROPERTY =
            "starsector.optimization.pcmCacheMaximumBytes";
    public static final String MAXIMUM_ENCODED_BYTES_PROPERTY =
            "starsector.optimization.pcmCacheMaximumEncodedBytes";
    public static final String DISABLE_PROPERTY =
            "starsector.optimization.disablePcmCache";

    private static final long MAGIC = 0x535350434d303031L; // SSPCM001
    private static final int VERSION = 1;
    /** 游戏解码器或 PCM 恢复语义变化时必须递增。 */
    private static final String CACHE_IDENTITY =
            "decoded-pcm-0.98a-RC8-v2";
    private static final int DEFAULT_MINIMUM_BYTES = 32 * 1024;
    private static final int DEFAULT_MAXIMUM_ENCODED_BYTES =
            64 * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_BYTES =
            1024L * 1024L * 1024L;
    private static final int MAX_PCM_BYTES = 512 * 1024 * 1024;
    /** 固定字段 + 64 位 ASCII SHA-256 的 DataOutputStream 编码长度。 */
    private static final int CACHE_HEADER_BYTES = 102;
    private static final int HASH_LENGTH = 64;
    private static final String CACHE_SUFFIX = ".sspcm.zst";
    private static final String ORIGINAL_METHOD =
            "starsector$decodePcmUncached";
    private static final String BUFFER_FIELD = "Object";
    private static final String SAMPLE_RATE_FIELD = "\u00d200000";
    private static final String CHANNELS_FIELD = "o00000";
    private static final Object[] LOCKS = new Object[64];

    private static volatile Boolean zstdAvailable;
    private static volatile PersistentCacheCleaner.Policy cachedCleanupPolicy;

    private static final ClassValue<Method> DECODER_METHODS =
            new ClassValue<>() {
                @Override
                protected Method computeValue(Class<?> type) {
                    return findDecoderMethod(type);
                }
            };

    private static final ClassValue<ResultAccess> RESULT_ACCESS =
            new ClassValue<>() {
                @Override
                protected ResultAccess computeValue(Class<?> type) {
                    return createResultAccess(type);
                }
            };

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private DecodedPcmCache() {
    }

    /** 由注入到声音解码器的最小 wrapper 调用。 */
    public static Object decode(Object decoder, InputStream source)
            throws IOException {
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(source, "source");
        if (!isEnabled()) {
            return invokeOriginal(decoder, source);
        }

        int maximumEncodedBytes = Math.max(
                1,
                Math.min(
                        MAX_PCM_BYTES,
                        Integer.getInteger(
                                MAXIMUM_ENCODED_BYTES_PROPERTY,
                                DEFAULT_MAXIMUM_ENCODED_BYTES)));
        // 原版 OGG 解码器取得 InputStream 所有权并在成功路径关闭。缓存先读
        // 原始流后改用内存副本，必须在 wrapper 层维持同一所有权，否则命中和
        // 小文件 miss 都会永久泄漏 mod 文件句柄。多读一个字节可区分“恰好达到
        // 上限”与“确实超限”，同时仍保持有界预读。
        try (source) {
            byte[] encoded = source.readNBytes(maximumEncodedBytes + 1);
            if (encoded.length > maximumEncodedBytes) {
                return invokeOriginal(
                        decoder,
                        new SequenceInputStream(
                                new ByteArrayInputStream(encoded),
                                new NonClosingInputStream(source)));
            }

            String sourceHash = sourceHash(encoded);
            CachedPcm cached = load(sourceHash);
            if (cached != null) {
                try {
                    Object restored = restore(decoder, cached);
                    PcmCacheDiagnostics.recordHit(
                            cached.pcmBytes().length, cached.fileBytes());
                    return restored;
                } catch (ReflectiveOperationException
                         | RuntimeException
                         | LinkageError incompatible) {
                    deleteQuietly(cached.source());
                    PcmCacheDiagnostics.recordCorruption();
                }
            }

            Object decoded = invokeOriginal(
                    decoder, new ByteArrayInputStream(encoded));
            try {
                store(sourceHash, snapshot(decoder, decoded));
            } catch (IOException
                     | ReflectiveOperationException
                     | RuntimeException
                     | LinkageError ignored) {
                // 缓存只是优化；无法观察混淆结果时仍返回原解码结果。
            }
            return decoded;
        }
    }

    public static boolean isEnabled() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        Boolean available = zstdAvailable;
        if (available != null) {
            return available;
        }
        synchronized (DecodedPcmCache.class) {
            available = zstdAvailable;
            if (available == null) {
                available = probeZstd();
                zstdAvailable = available;
            }
        }
        return available;
    }

    static void resetForTests() {
        zstdAvailable = null;
        cachedCleanupPolicy = null;
        PcmCacheDiagnostics.resetForTests();
        PersistentCacheMaintenance.resetForTests();
    }

    private static Object invokeOriginal(Object decoder, InputStream input)
            throws IOException {
        Method method = DECODER_METHODS.get(decoder.getClass());
        try {
            return method.invoke(decoder, input);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "unexpected checked exception from PCM decoder", cause);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException(
                    "cannot invoke original PCM decoder", impossible);
        }
    }

    private static Method findDecoderMethod(Class<?> decoderClass) {
        Class<?> current = decoderClass;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(
                        ORIGINAL_METHOD, InputStream.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException(
                "cannot find original PCM decoder on "
                        + decoderClass.getName());
    }

    private static ResultAccess resultAccess(Object decoder) {
        return RESULT_ACCESS.get(
                DECODER_METHODS.get(decoder.getClass()).getReturnType());
    }

    private static ResultAccess createResultAccess(Class<?> resultClass) {
        try {
            Constructor<?> constructor = resultClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Field buffer = findField(resultClass, BUFFER_FIELD);
            Field sampleRate = findField(resultClass, SAMPLE_RATE_FIELD);
            Field channels = findField(resultClass, CHANNELS_FIELD);
            return new ResultAccess(
                    constructor, buffer, sampleRate, channels);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "cannot access decoded PCM result "
                            + resultClass.getName(), failure);
        }
    }

    private static Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object restore(Object decoder, CachedPcm cached)
            throws ReflectiveOperationException {
        ResultAccess access = resultAccess(decoder);
        Object result = access.constructor().newInstance();
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                cached.pcmBytes().length);
        buffer.put(cached.pcmBytes());
        buffer.flip();
        access.buffer().set(result, buffer);
        access.sampleRate().setInt(result, cached.sampleRate());
        access.channels().setInt(result, cached.channels());
        return result;
    }

    private static PcmPayload snapshot(Object decoder, Object result)
            throws IOException, ReflectiveOperationException {
        if (result == null) {
            throw new IllegalArgumentException("PCM decoder returned null");
        }
        ResultAccess access = resultAccess(decoder);
        Object rawBuffer = access.buffer().get(result);
        if (!(rawBuffer instanceof ByteBuffer buffer)) {
            throw new IllegalArgumentException(
                    "PCM result buffer is not a ByteBuffer");
        }
        int length = buffer.limit();
        if (length <= 0 || length > MAX_PCM_BYTES) {
            throw new IllegalArgumentException("invalid PCM payload length");
        }
        byte[] pcm = new byte[length];
        ByteBuffer source = buffer.duplicate();
        source.position(0);
        source.limit(length);
        source.get(pcm);
        int sampleRate = access.sampleRate().getInt(result);
        int channels = access.channels().getInt(result);
        validateMetadata(sampleRate, channels, length);
        return new PcmPayload(sampleRate, channels, pcm);
    }

    private static CachedPcm load(String sourceHash) {
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
            PcmCacheDiagnostics.recordMiss();
            return null;
        }
        synchronized (lockFor(sourceHash)) {
            if (!Files.isRegularFile(
                    target, LinkOption.NOFOLLOW_LINKS)) {
                PcmCacheDiagnostics.recordMiss();
                return null;
            }
            PersistentCacheMaintenance.recordUse(policy, target);
            try {
                CachedPcm cached = read(target, sourceHash);
                PersistentCacheMaintenance.recordUse(policy, target);
                return cached;
            } catch (IOException | RuntimeException | LinkageError failure) {
                deleteQuietly(target);
                PcmCacheDiagnostics.recordCorruption();
                return null;
            }
        }
    }

    private static CachedPcm read(Path source, String expectedHash)
            throws IOException {
        long fileBytes = Files.size(source);
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(source)))) {
            if (input.readLong() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("PCM cache header mismatch");
            }
            String storedHash = input.readUTF();
            if (!expectedHash.equals(storedHash)) {
                throw new IOException("PCM cache source hash mismatch");
            }
            int sampleRate = input.readInt();
            int channels = input.readInt();
            int rawLength = input.readInt();
            long expectedCrc = input.readLong();
            int compressedLength = input.readInt();
            validateMetadata(sampleRate, channels, rawLength);
            if (!validCompressedEnvelope(
                    fileBytes, rawLength, compressedLength)) {
                throw new IOException("invalid PCM cache compressed length");
            }
            byte[] compressed = input.readNBytes(compressedLength);
            if (compressed.length != compressedLength || input.read() != -1) {
                throw new EOFException(
                        "truncated or trailing PCM cache data");
            }
            if (!zstdFrameMatches(compressed, rawLength)) {
                throw new IOException("PCM cache Zstd frame size mismatch");
            }
            byte[] pcm = IsolatedZstdCodec.decompress(
                    compressed, rawLength);
            if (pcm.length != rawLength || crc32(pcm) != expectedCrc) {
                throw new IOException("PCM cache checksum mismatch");
            }
            return new CachedPcm(
                    source, sampleRate, channels, pcm, fileBytes);
        }
    }

    private static void store(String sourceHash, PcmPayload payload) {
        if (payload.pcmBytes().length < minimumBytes()) {
            return;
        }
        PersistentCacheCleaner.Policy policy;
        Path target;
        try {
            policy = cleanupPolicy();
            PersistentCacheMaintenance.register(policy);
            target = safeCacheFile(
                    policy.directory(), sourceHash, true);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return;
        }
        if (target == null) {
            return;
        }
        PersistentCacheMaintenance.recordUse(policy, target);
        synchronized (lockFor(sourceHash)) {
            if (Files.isRegularFile(
                    target, LinkOption.NOFOLLOW_LINKS)) {
                PersistentCacheMaintenance.recordUse(policy, target);
                return;
            }
            Path temporary = temporaryFile(target);
            try {
                byte[] compressed = IsolatedZstdCodec.compress(
                        payload.pcmBytes(), 1);
                write(temporary, sourceHash, payload, compressed);
                moveAtomic(temporary, target);
                PcmCacheDiagnostics.recordStore(
                        payload.pcmBytes().length, Files.size(target));
                PersistentCacheMaintenance.recordUse(policy, target);
            } catch (IOException | RuntimeException | LinkageError failure) {
                deleteQuietly(temporary);
            }
        }
    }

    private static void write(
            Path target,
            String sourceHash,
            PcmPayload payload,
            byte[] compressed) throws IOException {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)))) {
            output.writeLong(MAGIC);
            output.writeInt(VERSION);
            output.writeUTF(sourceHash);
            output.writeInt(payload.sampleRate());
            output.writeInt(payload.channels());
            output.writeInt(payload.pcmBytes().length);
            output.writeLong(crc32(payload.pcmBytes()));
            output.writeInt(compressed.length);
            output.write(compressed);
        }
    }

    private static void validateMetadata(
            int sampleRate, int channels, int rawLength) throws IOException {
        if (sampleRate <= 0
                || sampleRate > 1_536_000
                || channels <= 0
                || channels > 64
                || rawLength <= 0
                || rawLength > MAX_PCM_BYTES) {
            throw new IOException("invalid PCM cache metadata");
        }
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
        if (rawLength <= 0 || rawLength > MAX_PCM_BYTES
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

    private static int minimumBytes() {
        return Math.max(0, Integer.getInteger(
                MINIMUM_BYTES_PROPERTY, DEFAULT_MINIMUM_BYTES));
    }

    private static String sourceHash(byte[] encodedSource) {
        return sourceHashForIdentity(encodedSource, CACHE_IDENTITY);
    }

    static String sourceHashForTests(byte[] encodedSource) {
        return sourceHash(encodedSource);
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
                ? Path.of("cache", "startup-optimization", "pcm", "v2")
                : Path.of(configured);
        return directory.toAbsolutePath().normalize();
    }

    private static PersistentCacheCleaner.Policy cleanupPolicy() {
        PersistentCacheCleaner.Policy cached = cachedCleanupPolicy;
        if (cached != null) {
            return cached;
        }
        synchronized (DecodedPcmCache.class) {
            cached = cachedCleanupPolicy;
            if (cached == null) {
                String configured = System.getProperty(DIRECTORY_PROPERTY);
                boolean usesDefaultDirectory = configured == null
                        || configured.isBlank();
                cached = new PersistentCacheCleaner.Policy(
                        "pcm",
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

    private static boolean probeZstd() {
        try {
            byte[] compressed = IsolatedZstdCodec.compress(new byte[0], 1);
            return IsolatedZstdCodec.decompress(compressed, 0).length == 0;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
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

    /** 让原解码器可照常关闭重放流，底层原始流统一由外层 try-with-resources 关闭一次。 */
    private static final class NonClosingInputStream
            extends FilterInputStream {
        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // ownership remains with decode(InputStream)'s outer scope
        }
    }

    private record ResultAccess(
            Constructor<?> constructor,
            Field buffer,
            Field sampleRate,
            Field channels) {
    }

    private record PcmPayload(
            int sampleRate, int channels, byte[] pcmBytes) {
    }

    private record CachedPcm(
            Path source,
            int sampleRate,
            int channels,
            byte[] pcmBytes,
            long fileBytes) {
    }
}

package org.fossic.starsector.optimization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.codehaus.janino.util.ClassFile;

/**
 * 一个完整 Janino 编译代的源码图和 class bytecode 包。
 *
 * <p>格式有固定 magic/schema、严格尺寸上限和 SHA-256 校验；读取时同时验证逻辑路径、
 * 重复项以及 class 文件声明的二进制名。发布只允许同目录原子替换，因而崩溃不会把半包
 * 暴露给下一次启动。
 */
public final class JaninoBytecodePack {
    private static final byte[] MAGIC = "SSJBCACH".getBytes(
            StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int CHECKSUM_LENGTH = 32;
    private static final int MAX_STRING_BYTES = 16 * 1024;
    private static final int MAX_SOURCE_COUNT = 100_000;
    private static final int MAX_CLASS_COUNT = 100_000;
    private static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES * 2;

    private final String fingerprint;
    private final List<JaninoSourceIndex.SourceSnapshot> sources;
    private final Map<String, byte[]> classBytecodes;

    public JaninoBytecodePack(
            String fingerprint,
            List<JaninoSourceIndex.SourceSnapshot> sources,
            Map<String, byte[]> classBytecodes) {
        this.fingerprint = requireString(fingerprint, "fingerprint");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classBytecodes, "classBytecodes");
        if (sources.size() > MAX_SOURCE_COUNT) {
            throw new IllegalArgumentException("Too many source snapshots");
        }
        if (classBytecodes.size() > MAX_CLASS_COUNT) {
            throw new IllegalArgumentException("Too many cached classes");
        }

        TreeMap<String, JaninoSourceIndex.SourceSnapshot> sortedSources =
                new TreeMap<>();
        for (JaninoSourceIndex.SourceSnapshot source : sources) {
            Objects.requireNonNull(source, "source snapshot");
            validateLogicalPath(source.logicalPath());
            JaninoSourceIndex.SourceSnapshot copy = source.present()
                    ? JaninoSourceIndex.SourceSnapshot.present(
                            source.logicalPath(), source.sha256())
                    : JaninoSourceIndex.SourceSnapshot.missing(
                            source.logicalPath());
            if (sortedSources.put(source.logicalPath(), copy) != null) {
                throw new IllegalArgumentException(
                        "Duplicate source path " + source.logicalPath());
            }
        }
        this.sources = List.copyOf(sortedSources.values());

        TreeMap<String, byte[]> sortedClasses = new TreeMap<>();
        long byteCount = 0L;
        for (Map.Entry<String, byte[]> entry : classBytecodes.entrySet()) {
            String binaryName = requireString(entry.getKey(), "binary name");
            validateBinaryName(binaryName);
            byte[] bytecode = Objects.requireNonNull(
                    entry.getValue(), "class bytecode").clone();
            if (bytecode.length == 0 || bytecode.length > MAX_CLASS_BYTES) {
                throw new IllegalArgumentException(
                        "Invalid class bytecode length for " + binaryName);
            }
            validateClassName(binaryName, bytecode);
            byteCount += bytecode.length;
            if (byteCount > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Cached class bytecodes exceed the size limit");
            }
            sortedClasses.put(binaryName, bytecode);
        }
        this.classBytecodes = Collections.unmodifiableMap(sortedClasses);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public List<JaninoSourceIndex.SourceSnapshot> sources() {
        return sources;
    }

    /** 每次返回新的 value 数组，调用方无法修改包内状态。 */
    public Map<String, byte[]> classBytecodes() {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        classBytecodes.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return Collections.unmodifiableMap(copy);
    }

    public void writeAtomically(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null) {
            throw new IOException("Cache target has no parent directory");
        }
        Files.createDirectories(directory);
        byte[] encoded = encode();
        Path temporary = directory.resolve(
                absolute.getFileName() + ".tmp-" + UUID.randomUUID());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic cache publication is unsupported", exception);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static JaninoBytecodePack read(
            Path path, String expectedFingerprint) throws IOException {
        Objects.requireNonNull(path, "path");
        requireString(expectedFingerprint, "expectedFingerprint");
        long size = Files.size(path);
        long maximum = (long) HEADER_BYTES
                + MAX_PAYLOAD_BYTES + CHECKSUM_LENGTH;
        if (size < HEADER_BYTES + CHECKSUM_LENGTH || size > maximum) {
            throw new IOException("Invalid Janino cache pack size " + size);
        }
        byte[] encoded = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("Invalid Janino cache magic");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException(
                        "Unsupported Janino cache schema " + version);
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid Janino cache payload length");
            }
            if (encoded.length != HEADER_BYTES
                    + payloadLength + CHECKSUM_LENGTH) {
                throw new IOException(
                        "Truncated or trailing Janino cache bytes");
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] expectedChecksum = input.readNBytes(CHECKSUM_LENGTH);
            if (payload.length != payloadLength
                    || expectedChecksum.length != CHECKSUM_LENGTH
                    || !MessageDigest.isEqual(
                            digest(payload), expectedChecksum)) {
                throw new IOException("Janino cache checksum mismatch");
            }
            return decode(payload, expectedFingerprint);
        } catch (EOFException exception) {
            throw new IOException("Truncated Janino cache pack", exception);
        }
    }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBuffer)) {
            writeString(output, fingerprint);
            output.writeInt(sources.size());
            for (JaninoSourceIndex.SourceSnapshot source : sources) {
                writeString(output, source.logicalPath());
                output.writeBoolean(source.present());
                if (source.present()) {
                    output.write(source.sha256());
                }
            }
            output.writeInt(classBytecodes.size());
            for (Map.Entry<String, byte[]> entry
                    : classBytecodes.entrySet()) {
                writeString(output, entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
        }
        byte[] payload = payloadBuffer.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Janino cache payload exceeds size limit");
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream(
                HEADER_BYTES + payload.length + CHECKSUM_LENGTH);
        try (DataOutputStream output = new DataOutputStream(result)) {
            output.write(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(payload.length);
            output.write(payload);
            output.write(digest(payload));
        }
        return result.toByteArray();
    }

    private static JaninoBytecodePack decode(
            byte[] payload, String expectedFingerprint) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String fingerprint = readString(input);
            if (!expectedFingerprint.equals(fingerprint)) {
                throw new IOException("Janino cache fingerprint mismatch");
            }
            int sourceCount = readCount(input, MAX_SOURCE_COUNT, "sources");
            List<JaninoSourceIndex.SourceSnapshot> sources =
                    new ArrayList<>(sourceCount);
            for (int index = 0; index < sourceCount; index++) {
                String path = readString(input);
                boolean present = input.readBoolean();
                sources.add(present
                        ? JaninoSourceIndex.SourceSnapshot.present(
                                path, readExact(input, CHECKSUM_LENGTH))
                        : JaninoSourceIndex.SourceSnapshot.missing(path));
            }
            int classCount = readCount(input, MAX_CLASS_COUNT, "classes");
            LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
            long classBytes = 0L;
            for (int index = 0; index < classCount; index++) {
                String name = readString(input);
                int length = input.readInt();
                if (length <= 0 || length > MAX_CLASS_BYTES) {
                    throw new IOException(
                            "Invalid cached class length for " + name);
                }
                classBytes += length;
                if (classBytes > MAX_PAYLOAD_BYTES) {
                    throw new IOException("Cached classes exceed size limit");
                }
                if (classes.put(name, readExact(input, length)) != null) {
                    throw new IOException("Duplicate cached class " + name);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Trailing cache payload bytes");
            }
            try {
                return new JaninoBytecodePack(
                        fingerprint, sources, classes);
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Invalid Janino cache contents", exception);
            }
        } catch (EOFException exception) {
            throw new IOException("Truncated Janino cache payload", exception);
        }
    }

    private static int readCount(
            DataInputStream input, int maximum, String label)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid cached " + label + " count");
        }
        return count;
    }

    private static byte[] readExact(DataInputStream input, int length)
            throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return bytes;
    }

    private static void writeString(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Invalid cache string length");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input)
            throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid cache string length");
        }
        byte[] bytes = readExact(input, length);
        String result = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, result.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Invalid UTF-8 cache string");
        }
        return result;
    }

    private static String requireString(String value, String label) {
        Objects.requireNonNull(value, label);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (value.isEmpty() || bytes > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }

    private static void validateLogicalPath(String path) {
        requireString(path, "logical source path");
        if (!path.endsWith(".java")
                || path.startsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Unsafe logical source path " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Unsafe logical source path " + path);
            }
        }
    }

    private static void validateBinaryName(String name) {
        if (name.startsWith(".")
                || name.endsWith(".")
                || name.contains("..")
                || name.contains("/")
                || name.contains("\\")
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid cached binary name " + name);
        }
    }

    private static void validateClassName(String name, byte[] bytes) {
        if (bytes.length < 4
                || bytes[0] != (byte) 0xca
                || bytes[1] != (byte) 0xfe
                || bytes[2] != (byte) 0xba
                || bytes[3] != (byte) 0xbe) {
            throw new IllegalArgumentException(
                    "Invalid class magic for " + name);
        }
        try {
            String actual = new ClassFile(
                    new ByteArrayInputStream(bytes)).getThisClassName();
            if (!name.equals(actual)) {
                throw new IllegalArgumentException(
                        "Cached class name mismatch: expected " + name
                                + ", found " + actual);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Invalid class file for " + name, exception);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

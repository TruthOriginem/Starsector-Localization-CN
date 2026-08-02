package org.fossic.starsector.optimization;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

/**
 * 为一个 Janino source loader 缓存逻辑源码路径的正解析结果，并记录负解析源码图。
 *
 * <p>游戏的 finder 会在 {@code findResource()} 内完成资源覆盖解析和源码规范化，所以缓存
 * delegate 返回的 {@link Resource} 可以保持原覆盖语义，同时避免同一路径再次遍历全部资源
 * root。首次 {@code open()} 才把规范化后的 source bytes 做不可变快照；metadata 同样惰性
 * 快照，二者都不会为同一解析结果再次调用 delegate。并发请求通过每路径 FutureTask
 * single-flight。异常和负结果都会移除 live task：前者允许恢复重试，后者保证加载期后来
 * 新增的源码仍可见；负路径只留在持久缓存校验所需的观察集合中。
 */
public final class JaninoSourceIndex extends ResourceFinder {
    private final ResourceFinder delegate;
    private final ConcurrentHashMap<String, FutureTask<Resolution>> lookups =
            new ConcurrentHashMap<>();
    /** 负结果进入持久化源码图，但不冻结实时查找；加载期新增源码必须可见。 */
    private final Set<String> observedMissing =
            ConcurrentHashMap.newKeySet();

    public JaninoSourceIndex(ResourceFinder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Resource findResource(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName");
        FutureTask<Resolution> created = new FutureTask<>(
                () -> resolve(resourceName));
        FutureTask<Resolution> lookup = lookups.putIfAbsent(
                resourceName, created);
        boolean cacheHit = lookup != null;
        JaninoSourceIndexDiagnostics.recordRequest(cacheHit);
        if (!cacheHit) {
            lookup = created;
            created.run();
        }

        try {
            Resolution resolution = await(resourceName, lookup);
            if (resolution.resource() == null) {
                forgetNegative(resourceName, lookup);
                return null;
            }
            observedMissing.remove(resourceName);
            return resolution.resource();
        } catch (RuntimeException | Error exception) {
            throw exception;
        }
    }

    /** 返回某个逻辑路径当前解析结果的不可变内容指纹。 */
    public SourceSnapshot snapshot(String resourceName) throws IOException {
        Resource resource = findResource(resourceName);
        if (resource == null) {
            return SourceSnapshot.missing(resourceName);
        }
        return SourceSnapshot.present(
                resourceName, ((IndexedResource) resource).sha256());
    }

    /**
     * 直接探测 delegate 并返回内容指纹，不把验证结果写入实时索引。
     *
     * <p>持久化缓存验证发生在 Janino 实际解析源码之前。若验证使用普通
     * {@link #snapshot(String)}，一次最终失败的旧包验证也会把之前探测到的正、负结果
     * 冻结在本次会话中。fresh probe 保证失败验证对随后实时编译完全无副作用。
     */
    SourceSnapshot snapshotFresh(String resourceName) throws IOException {
        Objects.requireNonNull(resourceName, "resourceName");
        Resource resource = delegate.findResource(resourceName);
        if (resource == null) {
            return SourceSnapshot.missing(resourceName);
        }
        InputStream input = resource.open();
        if (input == null) {
            throw new IOException("Resolved Janino source returned no stream");
        }
        try (input) {
            return SourceSnapshot.present(resourceName, digest(input));
        }
    }

    /** 返回索引迄今观察到的完整正、负源码图，按逻辑路径稳定排序。 */
    public List<SourceSnapshot> snapshotAll() throws IOException {
        Set<String> observedNames = new HashSet<>(observedMissing);
        observedNames.addAll(lookups.keySet());
        List<String> names = new ArrayList<>(observedNames);
        names.sort(Comparator.naturalOrder());
        List<SourceSnapshot> snapshots = new ArrayList<>(names.size());
        for (String name : names) {
            FutureTask<Resolution> task = lookups.get(name);
            if (task == null) {
                if (observedMissing.contains(name)) {
                    snapshots.add(SourceSnapshot.missing(name));
                }
                continue;
            }
            Resolution resolution = await(name, task);
            snapshots.add(resolution.resource() == null
                    ? SourceSnapshot.missing(name)
                    : SourceSnapshot.present(
                            name, resolution.resource().sha256()));
        }
        return List.copyOf(snapshots);
    }

    private void forgetNegative(
            String resourceName, FutureTask<Resolution> lookup) {
        // 对同一 Future 的所有 waiter 只允许一个线程记录并移除。compute 的键级
        // 原子性还保证新一代 positive task 不会被旧 waiter 的 missing 记录覆盖。
        lookups.compute(resourceName, (name, current) -> {
            if (current == lookup) {
                observedMissing.add(name);
                return null;
            }
            return current;
        });
    }

    private Resolution await(
            String resourceName, FutureTask<Resolution> lookup) {
        try {
            return lookup.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while resolving Janino source "
                            + resourceName,
                    exception);
        } catch (ExecutionException exception) {
            lookups.remove(resourceName, lookup);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Unexpected checked failure resolving Janino source "
                            + resourceName,
                    cause);
        }
    }

    private Resolution resolve(String resourceName) {
        Resource resource = delegate.findResource(resourceName);
        JaninoSourceIndexDiagnostics.recordLookup(resource != null);
        return new Resolution(resource == null
                ? null
                : new IndexedResource(resource));
    }

    private record Resolution(IndexedResource resource) {
    }

    private static final class IndexedResource implements Resource {
        private final Resource delegate;
        private byte[] sourceBytes;
        private boolean metadataLoaded;
        private long lastModified;
        private byte[] sha256;

        private IndexedResource(Resource delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized InputStream open() throws IOException {
            if (sourceBytes != null) {
                JaninoSourceIndexDiagnostics.recordSourceAccess(true);
                return new ByteArrayInputStream(sourceBytes);
            }

            loadSourceBytes();
            return new ByteArrayInputStream(sourceBytes);
        }

        private void loadSourceBytes() throws IOException {
            if (sourceBytes != null) {
                return;
            }

            byte[] loaded;
            InputStream input = delegate.open();
            if (input == null) {
                throw new IOException("Resolved Janino source returned no stream");
            }
            try (input) {
                loaded = input.readAllBytes();
            }
            sourceBytes = loaded;
            JaninoSourceIndexDiagnostics.recordSourceAccess(false);
        }

        private synchronized byte[] sha256() throws IOException {
            loadSourceBytes();
            if (sha256 == null) {
                sha256 = digest(sourceBytes);
            }
            return sha256.clone();
        }

        @Override
        public String getFileName() {
            return delegate.getFileName();
        }

        @Override
        public synchronized long lastModified() {
            if (metadataLoaded) {
                JaninoSourceIndexDiagnostics.recordMetadataAccess(true);
                return lastModified;
            }
            long loaded = delegate.lastModified();
            lastModified = loaded;
            metadataLoaded = true;
            JaninoSourceIndexDiagnostics.recordMetadataAccess(false);
            return loaded;
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] digest(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    /** 一条源码正解析或负解析的持久化快照。 */
    public static final class SourceSnapshot {
        private static final int SHA256_LENGTH = 32;

        private final String logicalPath;
        private final boolean present;
        private final byte[] sha256;

        private SourceSnapshot(
                String logicalPath, boolean present, byte[] sha256) {
            this.logicalPath = Objects.requireNonNull(
                    logicalPath, "logicalPath");
            this.present = present;
            if (present) {
                Objects.requireNonNull(sha256, "sha256");
                if (sha256.length != SHA256_LENGTH) {
                    throw new IllegalArgumentException(
                            "Expected a 32-byte SHA-256 digest");
                }
                this.sha256 = sha256.clone();
            } else {
                if (sha256 != null) {
                    throw new IllegalArgumentException(
                            "A missing source cannot have a digest");
                }
                this.sha256 = null;
            }
        }

        public static SourceSnapshot present(
                String logicalPath, byte[] sha256) {
            return new SourceSnapshot(logicalPath, true, sha256);
        }

        public static SourceSnapshot missing(String logicalPath) {
            return new SourceSnapshot(logicalPath, false, null);
        }

        public String logicalPath() {
            return logicalPath;
        }

        public boolean present() {
            return present;
        }

        public byte[] sha256() {
            return sha256 == null ? null : sha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SourceSnapshot snapshot)) {
                return false;
            }
            return logicalPath.equals(snapshot.logicalPath)
                    && present == snapshot.present
                    && Arrays.equals(sha256, snapshot.sha256);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * logicalPath.hashCode()
                    + Boolean.hashCode(present)) + Arrays.hashCode(sha256);
        }
    }
}

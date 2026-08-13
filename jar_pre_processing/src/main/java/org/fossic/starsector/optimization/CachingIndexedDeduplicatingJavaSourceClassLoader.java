package org.fossic.starsector.optimization;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.codehaus.commons.compiler.ErrorHandler;
import org.codehaus.commons.compiler.WarningHandler;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.JavaSourceIClassLoader;
import org.codehaus.janino.ResourceFinderIClassLoader;
import org.codehaus.janino.util.ClassFile;
import org.codehaus.janino.util.resource.ResourceFinder;

/**
 * 带完整源码图验证的 Janino 整代 bytecode 缓存 loader。
 *
 * <p>缓存 class 与实时编译 class 始终由本 loader 定义；已缓存 class 同时通过
 * {@link ResourceFinderIClassLoader} 提供给 Janino 的类型系统，因此允许一代中出现“旧 class
 * 命中、新源码实时编译”的兼容场景。任一环境识别、读取、验证或发布问题都只禁用缓存并回退
 * 原实时编译路径。
 */
public final class CachingIndexedDeduplicatingJavaSourceClassLoader
        extends DeduplicatingJavaSourceClassLoader {
    public static final String DISABLE_PROPERTY =
            "starsector.optimization.disableJaninoBytecodeCache";
    public static final String DIRECTORY_PROPERTY =
            "starsector.optimization.janinoBytecodeCacheDirectory";
    public static final String MAXIMUM_BYTES_PROPERTY =
            "starsector.optimization.janinoBytecodeCacheMaximumBytes";
    public static final String MAXIMUM_PACKS_PROPERTY =
            "starsector.optimization.janinoBytecodeCacheMaximumPacks";

    private static final long DEFAULT_MAXIMUM_BYTES =
            32L * 1024L * 1024L;
    private static final int DEFAULT_MAXIMUM_PACKS = 8;
    private static final Object[] CACHE_PATH_LOCKS = cachePathLocks();

    private final JaninoSourceIndex sourceIndex;
    private final MutableBytecodeResourceFinder cachedClassFinder;
    private final String characterEncoding;
    private final Path cacheDirectory;
    private final PersistentCacheCleaner.Policy cleanupPolicy;
    private final String environmentSeed;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Map<String, byte[]> generationBytecodes =
            new LinkedHashMap<>();

    private volatile boolean persistenceEnabled;
    private boolean initialized;
    private boolean validPack;
    private boolean generatedThisSession;
    private String fingerprint;
    private Path cachePath;
    private List<JaninoSourceIndex.SourceSnapshot> validatedSources = List.of();
    private Map<String, byte[]> cachedForDefinition = Map.of();

    public CachingIndexedDeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            ResourceFinder sourceFinder,
            String characterEncoding) {
        this(parentClassLoader,
                Components.create(
                        parentClassLoader, sourceFinder, characterEncoding),
                characterEncoding,
                Configuration.fromSystemProperties(),
                null);
    }

    CachingIndexedDeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            ResourceFinder sourceFinder,
            String characterEncoding,
            Path cacheDirectory,
            String environmentSeed,
            boolean enabled) {
        this(parentClassLoader,
                Components.create(
                        parentClassLoader, sourceFinder, characterEncoding),
                characterEncoding,
                new Configuration(
                        Objects.requireNonNull(cacheDirectory,
                                "cacheDirectory"),
                        enabled,
                        false),
                Objects.requireNonNull(environmentSeed, "environmentSeed"));
    }

    private CachingIndexedDeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            Components components,
            String characterEncoding,
            Configuration configuration,
            String environmentSeed) {
        super(parentClassLoader, components.sourceLoader());
        this.sourceIndex = components.sourceIndex();
        this.cachedClassFinder = components.cachedClassFinder();
        this.characterEncoding = characterEncoding;
        this.cacheDirectory = configuration.directory();
        this.cleanupPolicy = new PersistentCacheCleaner.Policy(
                "janino",
                cacheDirectory,
                PersistentCacheCleaner.Layout.FLAT_HASH,
                "pack-",
                ".bin",
                PersistentCacheMaintenance.retentionMillis(),
                PersistentCacheMaintenance.configuredMaximumBytes(
                        MAXIMUM_BYTES_PROPERTY,
                        DEFAULT_MAXIMUM_BYTES),
                PersistentCacheMaintenance.configuredMaximumEntries(
                        MAXIMUM_PACKS_PROPERTY,
                        DEFAULT_MAXIMUM_PACKS),
                configuration.pruneObsoleteVersions());
        this.persistenceEnabled = configuration.enabled();
        this.environmentSeed = environmentSeed;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        initializeCache();
        byte[] cached;
        synchronized (this) {
            cached = cachedForDefinition.get(name);
        }
        if (cached != null) {
            JaninoBytecodeCacheDiagnostics.recordClassHit();
            ProtectionDomain protectionDomain =
                    optionalProtectionDomainFactory == null
                            ? null
                            : optionalProtectionDomainFactory
                                    .getProtectionDomain(
                                            ClassFile.getSourceResourceName(
                                                    name));
            return defineClass(
                    name, cached, 0, cached.length, protectionDomain);
        }
        if (persistenceEnabled && initialized) {
            JaninoBytecodeCacheDiagnostics.recordClassMiss();
        }
        return super.findClass(name);
    }

    @Override
    protected Map<String, byte[]> generateBytecodes(String className)
            throws ClassNotFoundException {
        Map<String, byte[]> generated = super.generateBytecodes(className);
        if (generated != null && !generated.isEmpty()
                && persistenceEnabled && initialized) {
            synchronized (this) {
                generated.forEach((name, bytes) -> generationBytecodes.put(
                        name, bytes.clone()));
                generatedThisSession = true;
            }
            JaninoBytecodeCacheDiagnostics.recordGeneratedClasses(
                    generated.size());
        }
        return generated;
    }

    @Override
    public void setDebuggingInfo(
            boolean debugSource, boolean debugLines, boolean debugVars) {
        boolean changed = debugSource() != debugSource
                || debugLines() != debugLines
                || debugVars() != debugVars;
        super.setDebuggingInfo(debugSource, debugLines, debugVars);
        if (changed && initialized) {
            disablePersistence();
        }
    }

    @Override
    public void setSourcePath(File[] sourcePath) {
        super.setSourcePath(sourcePath);
        disablePersistence();
    }

    @Override
    public void setSourceFileCharacterEncoding(String encoding) {
        super.setSourceFileCharacterEncoding(encoding);
        disablePersistence();
    }

    @Override
    public void setCompileErrorHandler(ErrorHandler compileErrorHandler) {
        super.setCompileErrorHandler(compileErrorHandler);
        disablePersistence();
    }

    @Override
    public void setWarningHandler(WarningHandler warningHandler) {
        super.setWarningHandler(warningHandler);
        disablePersistence();
    }

    private synchronized void initializeCache() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!persistenceEnabled) {
            return;
        }
        synchronized (cachePathLock(cacheDirectory)) {
            long useToken = 0L;
            try {
                fingerprint = computeFingerprint();
                cachePath = cacheDirectory.resolve(
                        "pack-" + fingerprint + ".bin");
                if (!Files.isRegularFile(cachePath)) {
                    PersistentCacheMaintenance.register(cleanupPolicy);
                    return;
                }
                // 在打开和完整源码图验证前 pin，避免异步清理删除正在使用的 warm pack。
                useToken = PersistentCacheMaintenance.recordUse(
                        cleanupPolicy, cachePath);
                JaninoBytecodeCacheDiagnostics.recordPackLoad();
                JaninoBytecodePack pack;
                try {
                    pack = JaninoBytecodePack.read(cachePath, fingerprint);
                } catch (IOException | RuntimeException exception) {
                    JaninoBytecodeCacheDiagnostics.recordInvalidPack(true);
                    deleteInvalidPack(useToken);
                    return;
                }

                boolean valid = validateSources(pack.sources());
                JaninoBytecodeCacheDiagnostics.recordSourceValidation(valid);
                if (!valid) {
                    JaninoBytecodeCacheDiagnostics.recordInvalidPack(false);
                    deleteInvalidPack(useToken);
                    return;
                }

                Map<String, byte[]> bytecodes = pack.classBytecodes();
                cachedClassFinder.install(bytecodes);
                cachedForDefinition = bytecodes;
                generationBytecodes.putAll(bytecodes);
                validatedSources = pack.sources();
                validPack = true;
                JaninoBytecodeCacheDiagnostics.recordValidPack();
            } catch (IOException | RuntimeException | LinkageError exception) {
                if (cachePath != null) {
                    PersistentCacheMaintenance.discardUse(
                            cleanupPolicy, cachePath, useToken);
                } else {
                    PersistentCacheMaintenance.register(cleanupPolicy);
                }
                persistenceEnabled = false;
                cachedForDefinition = Map.of();
                cachedClassFinder.install(Map.of());
                JaninoBytecodeCacheDiagnostics.recordEnvironmentFailure();
            }
        }
    }

    private boolean validateSources(
            List<JaninoSourceIndex.SourceSnapshot> expected)
            throws IOException {
        for (JaninoSourceIndex.SourceSnapshot snapshot : expected) {
            if (!snapshot.equals(
                    sourceIndex.snapshotFresh(snapshot.logicalPath()))) {
                return false;
            }
        }
        return true;
    }

    private String computeFingerprint() throws IOException {
        String encodingKey = characterEncoding == null
                ? "<platform-default>" : characterEncoding;
        if (environmentSeed != null) {
            return JaninoCacheFingerprint.forSeed(
                    environmentSeed, encodingKey,
                    debugSource(), debugLines(), debugVars());
        }
        return JaninoCacheFingerprint.forRuntime(
                getParent(), encodingKey,
                debugSource(), debugLines(), debugVars());
    }

    private void deleteInvalidPack(long useToken) {
        try {
            Files.deleteIfExists(cachePath);
        } catch (IOException | RuntimeException ignored) {
            // 旧包无法删除也不能妨碍本次实时编译；成功发布时会原子替换。
        } finally {
            PersistentCacheMaintenance.discardUse(
                    cleanupPolicy, cachePath, useToken);
        }
    }

    private synchronized void disablePersistence() {
        persistenceEnabled = false;
        validPack = false;
        validatedSources = List.of();
        cachedForDefinition = Map.of();
        cachedClassFinder.install(Map.of());
    }

    void finishCacheForTests() throws IOException {
        finishSuccessfulSession();
    }

    void abortCache() {
        finished.set(true);
    }

    void finishSuccessfulSession() throws IOException {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        initializeCache();
        if (!persistenceEnabled || generationBytecodes.isEmpty()) {
            return;
        }
        if (validPack && !generatedThisSession) {
            return;
        }
        synchronized (cachePathLock(cacheDirectory)) {
            // 惰性编译可能发生在标题画面之后；在原子替换前先保护目标，避免
            // cleaner 在 move 与 publication 记录之间删除刚生成的 pack。
            long useToken = PersistentCacheMaintenance.recordUse(
                    cleanupPolicy, cachePath);
            boolean published = false;
            try {
                List<JaninoSourceIndex.SourceSnapshot> liveSources =
                        sourceIndex.snapshotAll();
                List<JaninoSourceIndex.SourceSnapshot> currentSources =
                        validPack
                                ? mergeSourceGraphs(
                                        validatedSources, liveSources)
                                : liveSources;
                Map<String, byte[]> bytecodes;
                synchronized (this) {
                    bytecodes = new LinkedHashMap<>(generationBytecodes);
                }
                new JaninoBytecodePack(
                        fingerprint, currentSources, bytecodes)
                        .writeAtomically(cachePath);
                PersistentCacheMaintenance.recordPublication(
                        cleanupPolicy, cachePath);
                published = true;
                JaninoBytecodeCacheDiagnostics.recordPublishedPack();
            } catch (IOException | RuntimeException | LinkageError exception) {
                // 已验证的旧 pack 即使替换失败仍可复用，应继续受保护；首次发布
                // 失败则撤销不存在/不完整目标的预 pin。
                if (!published && !validPack) {
                    PersistentCacheMaintenance.discardUse(
                            cleanupPolicy, cachePath, useToken);
                }
                JaninoBytecodeCacheDiagnostics.recordPublishFailure();
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException(
                        "Failed to publish Janino bytecode cache", exception);
            }
        }
    }

    private static List<JaninoSourceIndex.SourceSnapshot> mergeSourceGraphs(
            List<JaninoSourceIndex.SourceSnapshot> validated,
            List<JaninoSourceIndex.SourceSnapshot> live) throws IOException {
        TreeMap<String, JaninoSourceIndex.SourceSnapshot> merged =
                new TreeMap<>();
        for (JaninoSourceIndex.SourceSnapshot snapshot : validated) {
            JaninoSourceIndex.SourceSnapshot previous = merged.put(
                    snapshot.logicalPath(), snapshot);
            if (previous != null && !previous.equals(snapshot)) {
                throw new IOException(
                        "Conflicting validated Janino source snapshots for "
                                + snapshot.logicalPath());
            }
        }
        for (JaninoSourceIndex.SourceSnapshot snapshot : live) {
            JaninoSourceIndex.SourceSnapshot previous = merged.get(
                    snapshot.logicalPath());
            if (previous != null && !previous.equals(snapshot)) {
                throw new IOException(
                        "Janino source changed during cache session: "
                                + snapshot.logicalPath());
            }
            merged.put(snapshot.logicalPath(), snapshot);
        }
        return List.copyOf(merged.values());
    }

    private static Object[] cachePathLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static Object cachePathLock(Path path) {
        return CACHE_PATH_LOCKS[(path.toAbsolutePath().normalize().hashCode()
                & Integer.MAX_VALUE) % CACHE_PATH_LOCKS.length];
    }

    Path cachePathForTests() {
        initializeCache();
        if (cachePath == null && environmentSeed != null) {
            fingerprint = JaninoCacheFingerprint.forSeed(
                    environmentSeed,
                    characterEncoding == null
                            ? "<platform-default>" : characterEncoding,
                    debugSource(), debugLines(), debugVars());
            cachePath = cacheDirectory.resolve(
                    "pack-" + fingerprint + ".bin");
        }
        return cachePath == null
                ? cacheDirectory.resolve("cache-disabled.bin")
                : cachePath;
    }

    JaninoSourceIndex sourceIndexForTests() {
        return sourceIndex;
    }

    private record Components(
            JaninoSourceIndex sourceIndex,
            MutableBytecodeResourceFinder cachedClassFinder,
            JavaSourceIClassLoader sourceLoader) {
        private static Components create(
                ClassLoader parent,
                ResourceFinder sourceFinder,
                String characterEncoding) {
            JaninoSourceIndex sourceIndex = new JaninoSourceIndex(
                    Objects.requireNonNull(sourceFinder, "sourceFinder"));
            MutableBytecodeResourceFinder cachedFinder =
                    new MutableBytecodeResourceFinder();
            ResourceFinderIClassLoader cachedMetadata =
                    new ResourceFinderIClassLoader(
                            cachedFinder,
                            new ClassLoaderIClassLoader(parent));
            JavaSourceIClassLoader sourceLoader =
                    new JavaSourceIClassLoader(
                            sourceIndex, characterEncoding, cachedMetadata);
            return new Components(sourceIndex, cachedFinder, sourceLoader);
        }
    }

    private record Configuration(
            Path directory,
            boolean enabled,
            boolean pruneObsoleteVersions) {
        private Configuration {
            Objects.requireNonNull(directory, "directory");
        }

        private static Configuration fromSystemProperties() {
            boolean enabled = !Boolean.getBoolean(DISABLE_PROPERTY);
            String configured = System.getProperty(DIRECTORY_PROPERTY);
            try {
                Path directory;
                boolean usesDefaultDirectory;
                if (configured != null && !configured.isBlank()) {
                    directory = Path.of(configured);
                    usesDefaultDirectory = false;
                } else {
                    String workingDirectory = System.getProperty(
                            "user.dir", ".");
                    directory = Path.of(workingDirectory)
                            .resolve("cache")
                            .resolve("startup-optimization")
                            .resolve("janino")
                            .resolve("v2");
                    usesDefaultDirectory = true;
                }
                return new Configuration(
                        directory, enabled, usesDefaultDirectory);
            } catch (InvalidPathException exception) {
                JaninoBytecodeCacheDiagnostics.recordEnvironmentFailure();
                return new Configuration(Path.of("."), false, false);
            }
        }
    }
}

package org.fossic.starsector.optimization;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 对内容寻址缓存执行安全的过期、容量和旧版本清理。
 *
 * <p>调用方在启动关键路径只记录本进程实际使用的文件；清理阶段再批量更新这些文件的
 * mtime，把它作为近似 LRU 时间。mtime 不参与缓存正确性或命中判断。软上限永不删除当前
 * 进程访问过的项，即使活跃集合本身超过配置容量。
 */
public final class PersistentCacheCleaner {
    public static final String MAXIMUM_SCANNED_PATHS_PROPERTY =
            "starsector.optimization.cacheCleanupMaximumScannedPaths";
    private static final long TEMPORARY_RETENTION_MILLIS =
            24L * 60L * 60L * 1000L;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final int DEFAULT_MAXIMUM_SCANNED_PATHS = 250_000;
    private static final int HARD_MAXIMUM_SCANNED_PATHS = 1_000_000;
    private static final int MINIMUM_MAXIMUM_SCANNED_PATHS = 16;

    private PersistentCacheCleaner() {
    }

    public static Result clean(
            Policy policy,
            Set<Path> protectedFiles,
            long nowMillis) {
        int maximumScannedPaths = configuredMaximumScannedPaths();
        return clean(
                policy, protectedFiles, nowMillis, maximumScannedPaths);
    }

    static int configuredMaximumScannedPaths() {
        String value = System.getProperty(MAXIMUM_SCANNED_PATHS_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_MAXIMUM_SCANNED_PATHS;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0L) {
                return DEFAULT_MAXIMUM_SCANNED_PATHS;
            }
            return (int) Math.max(
                    MINIMUM_MAXIMUM_SCANNED_PATHS,
                    Math.min(HARD_MAXIMUM_SCANNED_PATHS, parsed));
        } catch (NumberFormatException invalid) {
            return DEFAULT_MAXIMUM_SCANNED_PATHS;
        }
    }

    static Result clean(
            Policy policy,
            Set<Path> protectedFiles,
            long nowMillis,
            int maximumScannedPaths) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(protectedFiles, "protectedFiles");
        if (maximumScannedPaths <= 0) {
            throw new IllegalArgumentException(
                    "maximumScannedPaths must be positive");
        }
        MutableResult result = new MutableResult();
        TraversalBudget budget = new TraversalBudget(maximumScannedPaths);
        Set<Path> protectedNormalized = normalizeProtected(
                policy, protectedFiles);

        cleanObsoleteVersions(policy, result, budget);
        if (!isTraversableDirectoryPath(policy.directory())) {
            return result.freeze(0L, 0, policy, budget);
        }

        touchProtected(
                policy, protectedNormalized, nowMillis, result, budget);
        List<Entry> entries = scan(
                policy, protectedNormalized, nowMillis, result, budget);

        long remainingBytes = 0L;
        int remainingEntries = 0;
        List<Entry> capacityCandidates = new ArrayList<>();
        for (Entry entry : entries) {
            boolean protectedNow = isProtected(
                    entry.path(), protectedNormalized, protectedFiles);
            if (!protectedNow
                    && expired(
                            entry.lastModifiedMillis(),
                            nowMillis,
                            policy.retentionMillis())) {
                if (deleteUnchangedEntry(
                        entry,
                        protectedNormalized,
                        protectedFiles,
                        result)) {
                    result.expiredFilesDeleted++;
                    result.bytesDeleted = saturatedAdd(
                            result.bytesDeleted, entry.bytes());
                    continue;
                }
            }
            remainingBytes = saturatedAdd(remainingBytes, entry.bytes());
            remainingEntries++;
            if (!protectedNow) {
                capacityCandidates.add(entry);
            }
        }

        if (!budget.limitReached()) {
            capacityCandidates.sort(Comparator
                    .comparingLong(Entry::lastModifiedMillis)
                    .thenComparing(entry -> entry.path().toString()));
            for (Entry candidate : capacityCandidates) {
                if (remainingBytes <= policy.maximumBytes()
                        && remainingEntries <= policy.maximumEntries()) {
                    break;
                }
                if (isProtected(
                        candidate.path(), protectedNormalized, protectedFiles)) {
                    continue;
                }
                if (deleteUnchangedEntry(
                        candidate,
                        protectedNormalized,
                        protectedFiles,
                        result)) {
                    result.capacityFilesDeleted++;
                    result.bytesDeleted = saturatedAdd(
                            result.bytesDeleted, candidate.bytes());
                    remainingBytes = Math.max(
                            0L, remainingBytes - candidate.bytes());
                    remainingEntries--;
                }
            }
        } else {
            // 超大/敌意缓存树无法在一批内得到完整 LRU 视图。缓存可重建，故宁可
            // 淘汰本批已确认未被本进程使用的项，也不能永远从同一前缀重扫而失控增长。
            for (Entry candidate : capacityCandidates) {
                if (deleteUnchangedEntry(
                        candidate,
                        protectedNormalized,
                        protectedFiles,
                        result)) {
                    result.overflowFilesDeleted++;
                    result.bytesDeleted = saturatedAdd(
                            result.bytesDeleted, candidate.bytes());
                    remainingBytes = Math.max(
                            0L, remainingBytes - candidate.bytes());
                    remainingEntries--;
                }
            }
        }

        removeEmptyHashDirectories(policy, result, budget);
        return result.freeze(
                remainingBytes, remainingEntries, policy, budget);
    }

    private static Set<Path> normalizeProtected(
            Policy policy, Set<Path> protectedFiles) {
        Set<Path> normalized = new HashSet<>();
        for (Path path : protectedFiles) {
            if (path == null) {
                continue;
            }
            Path candidate = path.toAbsolutePath().normalize();
            if (candidate.startsWith(policy.directory())
                    && classify(policy, candidate) == FileKind.ENTRY) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }

    private static void touchProtected(
            Policy policy,
            Set<Path> protectedFiles,
            long nowMillis,
            MutableResult result,
            TraversalBudget budget) {
        FileTime now = FileTime.fromMillis(nowMillis);
        for (Path path : protectedFiles) {
            if (!budget.tryVisit()) {
                break;
            }
            if (!hasTraversableCacheParent(policy, path)) {
                continue;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()
                        || isLinkOrReparse(attributes)
                        || classify(policy, path) != FileKind.ENTRY) {
                    continue;
                }
                Files.getFileAttributeView(
                        path,
                        BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS)
                        .setTimes(now, null, null);
                result.touchedFiles++;
            } catch (IOException | RuntimeException failure) {
                result.failures++;
            }
        }
    }

    private static List<Entry> scan(
            Policy policy,
            Set<Path> protectedFiles,
            long nowMillis,
            MutableResult result,
            TraversalBudget budget) {
        List<Entry> entries = new ArrayList<>();
        // 缓存格式只拥有根目录直属项（flat）或一层两位 shard。不要递归进入
        // 无关目录：用户/未来版本放入的任意子树既不归本清理器所有，也不能消耗
        // 全部扫描预算而让真实缓存项长期饥饿。不用 Files.walk：Windows 会把
        // junction 当普通目录打开并枚举其目标。
        if (!budget.tryVisit()) {
            return entries;
        }
        Path root = policy.directory();
        BasicFileAttributes rootAttributes = readAttributes(
                root, result);
        if (!isTraversableDirectory(rootAttributes)) {
            return entries;
        }
        try (Stream<Path> paths = Files.list(root)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next().toAbsolutePath().normalize();
                if (!budget.tryVisit()) {
                    break;
                }
                BasicFileAttributes attributes = readAttributes(
                        path, result);
                if (attributes == null || isLinkOrReparse(attributes)) {
                    continue;
                }
                if (attributes.isRegularFile()) {
                    inspect(
                            policy,
                            path,
                            attributes,
                            protectedFiles,
                            nowMillis,
                            entries,
                            result);
                    continue;
                }
                if (policy.layout() == Layout.HASH_SHARDED
                        && isTraversableDirectory(attributes)
                        && isHex(path.getFileName().toString(), 2)
                        && !scanShard(
                                policy,
                                path,
                                protectedFiles,
                                nowMillis,
                                entries,
                                result,
                                budget)) {
                    break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            result.failures++;
        }
        return entries;
    }

    private static boolean scanShard(
            Policy policy,
            Path shard,
            Set<Path> protectedFiles,
            long nowMillis,
            List<Entry> entries,
            MutableResult result,
            TraversalBudget budget) {
        // 二次 NOFOLLOW 校验，避免在根目录枚举后 shard 已被替换为
        // junction 时还去 Files.list 它。
        if (!isTraversableDirectory(shard)) {
            return true;
        }
        boolean sawChild = false;
        try (Stream<Path> paths = Files.list(shard)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                sawChild = true;
                Path path = iterator.next().toAbsolutePath().normalize();
                if (!budget.tryVisit()) {
                    return false;
                }
                BasicFileAttributes attributes = readAttributes(
                        path, result);
                if (attributes == null || isLinkOrReparse(attributes)) {
                    continue;
                }
                if (attributes.isRegularFile()) {
                    inspect(
                            policy,
                            path,
                            attributes,
                            protectedFiles,
                            nowMillis,
                            entries,
                            result);
                }
                // shard 中的目录、junction 和未来格式项只占一个预算，
                // 不向下递归。
            }
        } catch (IOException | RuntimeException failure) {
            result.failures++;
            return true;
        }
        if (!sawChild && isTraversableDirectory(shard)) {
            try {
                if (Files.deleteIfExists(shard)) {
                    result.emptyDirectoriesDeleted++;
                }
            } catch (IOException | RuntimeException failure) {
                result.failures++;
            }
        }
        return true;
    }

    private static void inspect(
            Policy policy,
            Path path,
            BasicFileAttributes attributes,
            Set<Path> protectedFiles,
            long nowMillis,
            List<Entry> entries,
            MutableResult result) {
        if (!attributes.isRegularFile()
                || isLinkOrReparse(attributes)) {
            return;
        }

        FileKind kind = classify(policy, path);
        switch (kind) {
            case ENTRY -> {
                result.scannedEntries++;
                entries.add(new Entry(
                        path,
                        attributes.size(),
                        protectedFiles.contains(path)
                                ? nowMillis
                                : attributes.lastModifiedTime().toMillis(),
                        attributes.fileKey()));
            }
            case TEMPORARY -> {
                if (expired(
                        attributes.lastModifiedTime().toMillis(),
                        nowMillis,
                        TEMPORARY_RETENTION_MILLIS)
                        && delete(path, result)) {
                    result.temporaryFilesDeleted++;
                    result.bytesDeleted = saturatedAdd(
                            result.bytesDeleted, attributes.size());
                }
            }
            case MALFORMED_OWNED -> {
                if (delete(path, result)) {
                    result.malformedFilesDeleted++;
                    result.bytesDeleted = saturatedAdd(
                            result.bytesDeleted, attributes.size());
                }
            }
            case UNRELATED -> {
                // 当前缓存目录中的用户或未来格式文件不在本清理器权限范围内。
            }
        }
    }

    private static FileKind classify(Policy policy, Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return FileKind.UNRELATED;
        }
        String name = path.getFileName().toString();
        return switch (policy.layout()) {
            case HASH_SHARDED -> classifyHashSharded(
                    policy, parent, name);
            case FLAT_HASH -> classifyFlatHash(policy, parent, name);
        };
    }

    private static FileKind classifyHashSharded(
            Policy policy, Path parent, String name) {
        Path grandparent = parent.getParent();
        if (grandparent == null
                || !grandparent.equals(policy.directory())
                || !isHex(parent.getFileName().toString(), 2)) {
            return FileKind.UNRELATED;
        }
        String suffix = policy.suffix();
        if (name.length() == SHA256_HEX_LENGTH + suffix.length()
                && name.endsWith(suffix)) {
            String hash = name.substring(0, SHA256_HEX_LENGTH);
            return isHex(hash, SHA256_HEX_LENGTH)
                    && parent.getFileName().toString().equals(
                            hash.substring(0, 2))
                    ? FileKind.ENTRY
                    : FileKind.MALFORMED_OWNED;
        }
        int suffixIndex = name.indexOf(suffix);
        if (suffixIndex == SHA256_HEX_LENGTH
                && name.endsWith(".tmp")) {
            String hash = name.substring(0, SHA256_HEX_LENGTH);
            return isHex(hash, SHA256_HEX_LENGTH)
                    && parent.getFileName().toString().equals(
                            hash.substring(0, 2))
                    ? FileKind.TEMPORARY
                    : FileKind.UNRELATED;
        }
        return name.endsWith(suffix)
                ? FileKind.MALFORMED_OWNED
                : FileKind.UNRELATED;
    }

    private static FileKind classifyFlatHash(
            Policy policy, Path parent, String name) {
        if (!parent.equals(policy.directory())) {
            return FileKind.UNRELATED;
        }
        String basePrefix = policy.prefix();
        String suffix = policy.suffix();
        int expectedLength = basePrefix.length()
                + SHA256_HEX_LENGTH + suffix.length();
        if (name.length() == expectedLength
                && name.startsWith(basePrefix)
                && name.endsWith(suffix)) {
            String hash = name.substring(
                    basePrefix.length(),
                    basePrefix.length() + SHA256_HEX_LENGTH);
            return isHex(hash, SHA256_HEX_LENGTH)
                    ? FileKind.ENTRY
                    : FileKind.MALFORMED_OWNED;
        }
        String temporaryMarker = suffix + ".tmp-";
        int temporaryIndex = name.indexOf(temporaryMarker);
        if (name.startsWith(basePrefix)
                && temporaryIndex == basePrefix.length()
                        + SHA256_HEX_LENGTH) {
            String hash = name.substring(
                    basePrefix.length(), temporaryIndex);
            return isHex(hash, SHA256_HEX_LENGTH)
                    ? FileKind.TEMPORARY
                    : FileKind.UNRELATED;
        }
        return name.startsWith(basePrefix) && name.endsWith(suffix)
                ? FileKind.MALFORMED_OWNED
                : FileKind.UNRELATED;
    }

    private static boolean isHex(String value, int expectedLength) {
        if (value.length() != expectedLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static BasicFileAttributes readAttributes(
            Path path, MutableResult result) {
        try {
            return Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            result.failures++;
            return null;
        }
    }

    /**
     * Windows junction/mount point 在 NOFOLLOW 下同时报告 directory 和
     * other；普通 symlink 则报告 symbolicLink。两者都不能被当作本地
     * 缓存目录枚举。
     */
    private static boolean isLinkOrReparse(
            BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static boolean isTraversableDirectory(
            BasicFileAttributes attributes) {
        return attributes != null
                && attributes.isDirectory()
                && !isLinkOrReparse(attributes);
    }

    private static boolean isTraversableDirectory(Path path) {
        try {
            return isTraversableDirectory(Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /** NOFOLLOW 只作用于最后路径组件，因此必须逐级拒绝中间 junction。 */
    private static boolean isTraversableDirectoryPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null || !isTraversableDirectory(current)) {
            return false;
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (!isTraversableDirectory(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTraversableCacheParent(
            Policy policy, Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }
        return switch (policy.layout()) {
            case FLAT_HASH -> parent.equals(policy.directory());
            case HASH_SHARDED -> parent.getParent() != null
                    && parent.getParent().equals(policy.directory())
                    && isTraversableDirectory(parent);
        };
    }

    private static boolean expired(
            long modifiedMillis, long nowMillis, long retentionMillis) {
        if (modifiedMillis > nowMillis) {
            return false;
        }
        if (retentionMillis == 0L) {
            return true;
        }
        try {
            return Math.subtractExact(nowMillis, modifiedMillis)
                    >= retentionMillis;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    private static boolean delete(Path path, MutableResult result) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException failure) {
            result.failures++;
            return false;
        }
    }

    private static boolean isProtected(
            Path path, Set<Path> normalizedSnapshot, Set<Path> liveSource) {
        return normalizedSnapshot.contains(path) || liveSource.contains(path);
    }

    /** 扫描后若另一进程 touch/替换了文件，放弃本轮删除。 */
    private static boolean deleteUnchangedEntry(
            Entry entry,
            Set<Path> normalizedSnapshot,
            Set<Path> liveSource,
            MutableResult result) {
        if (isProtected(entry.path(), normalizedSnapshot, liveSource)) {
            return false;
        }
        try {
            BasicFileAttributes current = Files.readAttributes(
                    entry.path(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile()
                    || isLinkOrReparse(current)
                    || current.size() != entry.bytes()
                    || current.lastModifiedTime().toMillis()
                            != entry.lastModifiedMillis()
                    || (entry.fileKey() != null
                        && current.fileKey() != null
                        && !entry.fileKey().equals(current.fileKey()))
                    || isProtected(
                            entry.path(), normalizedSnapshot, liveSource)) {
                return false;
            }
            return Files.deleteIfExists(entry.path());
        } catch (IOException | RuntimeException failure) {
            result.failures++;
            return false;
        }
    }

    private static void cleanObsoleteVersions(
            Policy policy,
            MutableResult result,
            TraversalBudget budget) {
        if (!policy.pruneObsoleteVersions()) {
            return;
        }
        Integer currentVersion = parseVersion(
                policy.directory().getFileName().toString());
        Path family = policy.directory().getParent();
        if (currentVersion == null || family == null
                || !isTraversableDirectoryPath(family)) {
            return;
        }
        try (Stream<Path> children = Files.list(family)) {
            Iterator<Path> iterator = children.iterator();
            while (iterator.hasNext()) {
                Path child = iterator.next();
                if (!budget.tryVisit()) {
                    break;
                }
                Path normalized = child.toAbsolutePath().normalize();
                Integer version = parseVersion(
                        normalized.getFileName().toString());
                if (version == null || version >= currentVersion
                        || normalized.equals(policy.directory())) {
                    continue;
                }
                BasicFileAttributes attributes = readAttributes(
                        normalized, result);
                if (attributes == null
                        || (!attributes.isDirectory()
                            && !isLinkOrReparse(attributes))) {
                    continue;
                }
                DeleteTreeResult deleted = deleteTree(normalized, budget);
                result.bytesDeleted = saturatedAdd(
                        result.bytesDeleted, deleted.bytesDeleted());
                result.failures += deleted.failures();
                if (deleted.removedRoot()) {
                    result.obsoleteVersionDirectoriesDeleted++;
                }
            }
        } catch (IOException | RuntimeException failure) {
            result.failures++;
        }
    }

    private static Integer parseVersion(String name) {
        if (name.length() < 2 || name.charAt(0) != 'v') {
            return null;
        }
        try {
            int version = Integer.parseInt(name.substring(1));
            return version >= 0 ? version : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static DeleteTreeResult deleteTree(
            Path root, TraversalBudget budget) {
        long[] bytes = {0L};
        int[] failures = {0};
        ArrayDeque<DeleteFrame> stack = new ArrayDeque<>();
        Path next = root;
        try {
            while (next != null || !stack.isEmpty()) {
                if (next != null) {
                    Path candidate = next;
                    next = null;
                    if (!budget.tryVisit()) {
                        break;
                    }
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(
                                candidate,
                                BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException | RuntimeException failure) {
                        failures[0]++;
                        continue;
                    }
                    if (isLinkOrReparse(attributes)
                            || !attributes.isDirectory()) {
                        deleteTreeLeaf(
                                candidate, attributes, bytes, failures);
                        continue;
                    }

                    // Files.walkFileTree/Files.walk 会在 visitor 能跳过
                    // junction 之前先打开其目标目录。手动栈先做
                    // NOFOLLOW 判定，只对普通目录调用 newDirectoryStream。
                    DirectoryStream<Path> children = null;
                    try {
                        BasicFileAttributes current = Files.readAttributes(
                                candidate,
                                BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (isLinkOrReparse(current)
                                || !current.isDirectory()) {
                            deleteTreeLeaf(
                                    candidate, current, bytes, failures);
                            continue;
                        }
                        children = Files.newDirectoryStream(candidate);
                        DeleteFrame frame = new DeleteFrame(
                                candidate,
                                children,
                                children.iterator());
                        stack.push(frame);
                        children = null;
                    } catch (IOException | RuntimeException failure) {
                        failures[0]++;
                    } finally {
                        if (children != null) {
                            closeDirectoryStream(children, failures);
                        }
                    }
                    continue;
                }

                DeleteFrame frame = stack.peek();
                try {
                    if (frame.iterator().hasNext()) {
                        next = frame.iterator().next()
                                .toAbsolutePath().normalize();
                        continue;
                    }
                } catch (RuntimeException iterationFailure) {
                    failures[0]++;
                }
                stack.pop();
                closeDirectoryStream(frame.children(), failures);
                try {
                    Files.deleteIfExists(frame.directory());
                } catch (IOException | RuntimeException failure) {
                    failures[0]++;
                }
            }
        } finally {
            while (!stack.isEmpty()) {
                closeDirectoryStream(
                        stack.pop().children(), failures);
            }
        }
        return new DeleteTreeResult(
                bytes[0], failures[0], !Files.exists(
                        root, LinkOption.NOFOLLOW_LINKS));
    }

    private static void deleteTreeLeaf(
            Path path,
            BasicFileAttributes attributes,
            long[] bytes,
            int[] failures) {
        try {
            if (Files.deleteIfExists(path)
                    && attributes.isRegularFile()
                    && !isLinkOrReparse(attributes)) {
                bytes[0] = saturatedAdd(bytes[0], attributes.size());
            }
        } catch (IOException | RuntimeException failure) {
            failures[0]++;
        }
    }

    private static void closeDirectoryStream(
            DirectoryStream<Path> stream, int[] failures) {
        try {
            stream.close();
        } catch (IOException | RuntimeException failure) {
            failures[0]++;
        }
    }

    private static void removeEmptyHashDirectories(
            Policy policy,
            MutableResult result,
            TraversalBudget budget) {
        if (policy.layout() != Layout.HASH_SHARDED) {
            return;
        }
        try (Stream<Path> children = Files.list(policy.directory())) {
            Iterator<Path> iterator = children.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!budget.tryVisit()) {
                    break;
                }
                BasicFileAttributes attributes = readAttributes(
                        path, result);
                if (!isHex(path.getFileName().toString(), 2)
                        || !isTraversableDirectory(attributes)
                        || !isTraversableDirectory(path)) {
                    continue;
                }
                try (Stream<Path> contents = Files.list(path)) {
                    if (contents.findAny().isEmpty()) {
                        if (Files.deleteIfExists(path)) {
                            result.emptyDirectoriesDeleted++;
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    result.failures++;
                }
            }
        } catch (IOException | RuntimeException failure) {
            result.failures++;
        }
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    public enum Layout {
        HASH_SHARDED,
        FLAT_HASH
    }

    public record Policy(
            String namespace,
            Path directory,
            Layout layout,
            String prefix,
            String suffix,
            long retentionMillis,
            long maximumBytes,
            int maximumEntries,
            boolean pruneObsoleteVersions) {
        public Policy {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(suffix, "suffix");
            if (namespace.isBlank()
                    || suffix.isEmpty()
                    || retentionMillis < 0L
                    || maximumBytes < 0L
                    || maximumEntries < 0) {
                throw new IllegalArgumentException(
                        "Invalid persistent cache cleanup policy");
            }
            if (layout == Layout.HASH_SHARDED && !prefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "Hash-sharded caches cannot use a filename prefix");
            }
            directory = directory.toAbsolutePath().normalize();
        }
    }

    public record Result(
            int scannedEntries,
            int scannedPaths,
            boolean traversalLimitReached,
            int touchedFiles,
            int expiredFilesDeleted,
            int capacityFilesDeleted,
            int overflowFilesDeleted,
            int malformedFilesDeleted,
            int temporaryFilesDeleted,
            int obsoleteVersionDirectoriesDeleted,
            int emptyDirectoriesDeleted,
            long bytesDeleted,
            long remainingBytes,
            int remainingEntries,
            long overLimitBytes,
            int overLimitEntries,
            int failures) {
    }

    private record Entry(
            Path path,
            long bytes,
            long lastModifiedMillis,
            Object fileKey) {
    }

    private record DeleteFrame(
            Path directory,
            DirectoryStream<Path> children,
            Iterator<Path> iterator) {
    }

    private record DeleteTreeResult(
            long bytesDeleted, int failures, boolean removedRoot) {
    }

    private enum FileKind {
        ENTRY,
        TEMPORARY,
        MALFORMED_OWNED,
        UNRELATED
    }

    private static final class TraversalBudget {
        private final int maximumPaths;
        private int visitedPaths;
        private boolean limitReached;

        private TraversalBudget(int maximumPaths) {
            this.maximumPaths = maximumPaths;
        }

        private boolean tryVisit() {
            if (visitedPaths >= maximumPaths) {
                limitReached = true;
                return false;
            }
            visitedPaths++;
            return true;
        }

        private int visitedPaths() {
            return visitedPaths;
        }

        private boolean limitReached() {
            return limitReached;
        }
    }

    private static final class MutableResult {
        private int scannedEntries;
        private int touchedFiles;
        private int expiredFilesDeleted;
        private int capacityFilesDeleted;
        private int overflowFilesDeleted;
        private int malformedFilesDeleted;
        private int temporaryFilesDeleted;
        private int obsoleteVersionDirectoriesDeleted;
        private int emptyDirectoriesDeleted;
        private long bytesDeleted;
        private int failures;

        private Result freeze(
                long remainingBytes,
                int remainingEntries,
                Policy policy,
                TraversalBudget budget) {
            return new Result(
                    scannedEntries,
                    budget.visitedPaths(),
                    budget.limitReached(),
                    touchedFiles,
                    expiredFilesDeleted,
                    capacityFilesDeleted,
                    overflowFilesDeleted,
                    malformedFilesDeleted,
                    temporaryFilesDeleted,
                    obsoleteVersionDirectoriesDeleted,
                    emptyDirectoriesDeleted,
                    bytesDeleted,
                    remainingBytes,
                    remainingEntries,
                    Math.max(0L,
                            remainingBytes - policy.maximumBytes()),
                    Math.max(0,
                            remainingEntries - policy.maximumEntries()),
                    failures);
        }
    }
}

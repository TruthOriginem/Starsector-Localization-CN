package org.fossic.starsector.optimization;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 汇总本进程实际启用的持久缓存，并在启动完成后异步执行批量维护。
 *
 * <p>各缓存只有在自己的优化 helper 确实运行时才注册 namespace。因此构建只启用纹理缓存
 * 时，不会创建、扫描或清理 PCM/Janino 目录。命中和发布的热路径只向并发集合记录 Path，
 * mtime 更新和目录遍历全部延迟到标题画面准备完成之后；JVM shutdown hook 仅作兜底。
 */
public final class PersistentCacheMaintenance {
    public static final String DISABLE_PROPERTY =
            "starsector.optimization.disablePersistentCacheCleanup";
    public static final String RETENTION_DAYS_PROPERTY =
            "starsector.optimization.cacheRetentionDays";
    public static final String START_DELAY_SECONDS_PROPERTY =
            "starsector.optimization.cacheCleanupDelaySeconds";

    private static final long DEFAULT_RETENTION_DAYS = 30L;
    private static final long DEFAULT_START_DELAY_SECONDS = 60L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long SECOND_MILLIS = 1000L;

    private static final ConcurrentHashMap<String, NamespaceState> STATES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<
            String, PersistentCacheCleaner.Result> LAST_RESULTS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean STARTUP_COMPLETE = new AtomicBoolean();
    /**
     * 与 {@link #SCHEDULED} 分离的工作请求握手。
     *
     * <p>注册/发布可能恰好发生在 worker 完成本轮遍历、尚未清除
     * {@code SCHEDULED} 的窗口内。调用方此时无法另起 worker，因此必须留下
     * 请求，让当前 worker 再跑一轮或在撤销 scheduled 后重新调度。
     */
    private static final AtomicBoolean WORK_REQUESTED = new AtomicBoolean();
    private static final AtomicLong USE_GENERATION = new AtomicLong();
    private static final Object CLEAN_LOCK = new Object();

    private static volatile Thread shutdownHook;
    private static volatile Thread maintenanceWorker;
    private static volatile Runnable beforeWorkerUnscheduleHookForTests;
    private static volatile Runnable beforeMaintenanceRequestHookForTests;

    private PersistentCacheMaintenance() {
    }

    /** 注册一个已实际启用的缓存；没有命中时也要注册，以便回收其旧内容。 */
    public static void register(PersistentCacheCleaner.Policy policy) {
        Registration registration = registerState(policy);
        if (registration.changed()) {
            requestMaintenance();
        }
    }

    private static Registration registerState(
            PersistentCacheCleaner.Policy policy) {
        Objects.requireNonNull(policy, "policy");
        NamespaceState existing = STATES.get(policy.namespace());
        boolean changed = false;
        if (existing == null || !existing.policy().equals(policy)) {
            NamespaceState replacement = new NamespaceState(policy);
            NamespaceState installed = STATES.compute(
                    policy.namespace(), (namespace, previous) ->
                            previous != null
                                    && previous.policy().equals(policy)
                                            ? previous : replacement);
            changed = installed == replacement;
            existing = installed;
        }
        // 即使重复注册也重试 hook；首次尝试可能因 JVM shutdown 状态或策略失败。
        ensureShutdownHook();
        return new Registration(existing, changed);
    }

    /**
     * 在打开缓存前保护文件，维护阶段不会删除它。
     *
     * <p>该操作刻意不标记 namespace 为 dirty，也不调度目录遍历。读取失败的
     * 调用方必须用对应 token 调用 {@link #discardUse} 撤销；
     * 读取成功则保留到进程结束，shutdown 维护会统一刷新 mtime。
     * 重复 pin 返回零 token；失败调用不能借此撤销另一个消费者或已成功发布者
     * 持有的长期保护。
     */
    public static long recordUse(
            PersistentCacheCleaner.Policy policy, Path path) {
        Objects.requireNonNull(path, "path");
        Registration registration = registerState(policy);
        NamespaceState state = registration.state();
        if (state.policy().equals(policy)) {
            Path normalized = path.toAbsolutePath().normalize();
            long generation = nextUseGeneration();
            Long existing = state.touched().putIfAbsent(
                    normalized, generation);
            long token = existing == null ? generation : 0L;
            // 首次注册必须在 pre-pin 可见之后才允许 worker 启动。
            if (registration.changed()) {
                requestMaintenance();
            }
            return token;
        }
        return 0L;
    }

    /** 记录成功发布的新文件，并请求一次容量/过期维护。 */
    public static void recordPublication(
            PersistentCacheCleaner.Policy policy, Path path) {
        Objects.requireNonNull(path, "path");
        Registration registration = registerState(policy);
        NamespaceState state = registration.state();
        if (state.policy().equals(policy)) {
            state.touched().put(
                    path.toAbsolutePath().normalize(), nextUseGeneration());
            state.dirty().set(true);
            requestMaintenance();
        }
    }

    /** 撤销读取/发布前的保护，避免失败或不存在的路径被永久保留。 */
    public static void discardUse(
            PersistentCacheCleaner.Policy policy, Path path, long token) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(path, "path");
        NamespaceState state = STATES.get(policy.namespace());
        if (token != 0L
                && state != null
                && state.policy().equals(policy)) {
            state.touched().remove(
                    path.toAbsolutePath().normalize(), token);
        }
    }

    private static long nextUseGeneration() {
        long generation = USE_GENERATION.incrementAndGet();
        if (generation != 0L) {
            return generation;
        }
        return USE_GENERATION.incrementAndGet();
    }

    /** 标题 prepare 返回后才允许后台清理，避免与慢启动中的缓存读写竞争。 */
    public static void onStartupComplete() {
        STARTUP_COMPLETE.set(true);
        scheduleMaintenance();
    }

    private static void requestMaintenance() {
        Runnable hook = beforeMaintenanceRequestHookForTests;
        if (hook != null) {
            hook.run();
        }
        WORK_REQUESTED.set(true);
        if (STARTUP_COMPLETE.get()) {
            scheduleMaintenance();
        }
    }

    private static void scheduleMaintenance() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)
                || STATES.isEmpty()
                || !WORK_REQUESTED.get()
                || !SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Thread worker;
        try {
            worker = new Thread(() -> {
                try {
                    long delayMillis = configuredMillis(
                            START_DELAY_SECONDS_PROPERTY,
                            DEFAULT_START_DELAY_SECONDS,
                            SECOND_MILLIS);
                    if (delayMillis > 0L) {
                        Thread.sleep(delayMillis);
                    }
                    boolean more;
                    do {
                        // 先认领此前的全部请求。并发调用若发生在这之后会重新置位，
                        // 从而要求本 worker 再清一轮。
                        WORK_REQUESTED.set(false);
                        more = cleanAll(System.currentTimeMillis());
                        if (more) {
                            Thread.yield();
                        }
                    } while ((more || WORK_REQUESTED.get())
                            && !Thread.currentThread().isInterrupted());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        Runnable hook = beforeWorkerUnscheduleHookForTests;
                        if (hook != null) {
                            hook.run();
                        }
                    } finally {
                        maintenanceWorker = null;
                        SCHEDULED.set(false);
                        // 先撤销 scheduled 再复查请求：旧 worker 临近退出时到达的
                        // 请求要么由这里接走，要么会看到 false 并自行成功调度。
                        if (!Thread.currentThread().isInterrupted()
                                && WORK_REQUESTED.get()) {
                            scheduleMaintenance();
                        }
                    }
                }
            }, "Starsector persistent cache cleanup");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            maintenanceWorker = worker;
            worker.start();
        } catch (RuntimeException | LinkageError unavailable) {
            maintenanceWorker = null;
            SCHEDULED.set(false);
            // 调度失败不能妨碍启动；已注册的 shutdown hook 仍可兜底。
        }
    }

    static long retentionMillis() {
        return configuredMillis(
                RETENTION_DAYS_PROPERTY,
                DEFAULT_RETENTION_DAYS,
                DAY_MILLIS);
    }

    static long configuredMaximumBytes(
            String property, long defaultValue) {
        return configuredNonNegativeLong(property, defaultValue);
    }

    static int configuredMaximumEntries(
            String property, int defaultValue) {
        long value = configuredNonNegativeLong(property, defaultValue);
        return value > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) value;
    }

    private static boolean cleanAll(long nowMillis) {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        boolean continueTraversal = false;
        synchronized (CLEAN_LOCK) {
            for (Map.Entry<String, NamespaceState> entry
                    : STATES.entrySet()) {
                NamespaceState state = entry.getValue();
                if (!state.dirty().compareAndSet(true, false)) {
                    continue;
                }
                try {
                    PersistentCacheCleaner.Result result =
                            PersistentCacheCleaner.clean(
                                    state.policy(),
                                    state.touched().keySet(),
                                    nowMillis);
                    LAST_RESULTS.put(entry.getKey(), result);
                    if (result.traversalLimitReached()
                            || result.failures() != 0) {
                        state.dirty().set(true);
                    }
                    if (result.traversalLimitReached()
                            && deletedFiles(result) > 0) {
                        continueTraversal = true;
                    }
                } catch (RuntimeException | LinkageError failure) {
                    state.dirty().set(true);
                }
            }
        }
        return continueTraversal;
    }

    private static int deletedFiles(PersistentCacheCleaner.Result result) {
        return result.expiredFilesDeleted()
                + result.capacityFilesDeleted()
                + result.overflowFilesDeleted()
                + result.malformedFilesDeleted()
                + result.temporaryFilesDeleted()
                + result.obsoleteVersionDirectoriesDeleted()
                + result.emptyDirectoriesDeleted();
    }

    private static long configuredMillis(
            String property, long defaultUnits, long millisPerUnit) {
        long units = configuredNonNegativeLong(property, defaultUnits);
        if (units > Long.MAX_VALUE / millisPerUnit) {
            return Long.MAX_VALUE;
        }
        return units * millisPerUnit;
    }

    private static long configuredNonNegativeLong(
            String property, long defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured.trim());
            return value >= 0L ? value : defaultValue;
        } catch (NumberFormatException invalid) {
            return defaultValue;
        }
    }

    private static void ensureShutdownHook() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)
                || shutdownHook != null) {
            return;
        }
        synchronized (PersistentCacheMaintenance.class) {
            if (shutdownHook != null) {
                return;
            }
            Thread hook = new Thread(
                    () -> cleanAtShutdown(System.currentTimeMillis()),
                    "Starsector persistent cache cleanup shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
                shutdownHook = hook;
            } catch (IllegalStateException | SecurityException ignored) {
                // JVM 已关闭或策略禁止 hook；标题后的 daemon 仍可负责维护。
            }
        }
    }

    static void cleanNowForTests(long nowMillis) {
        // 模拟 worker 在进入 cleanAll 前认领已有请求。
        WORK_REQUESTED.set(false);
        cleanAll(nowMillis);
    }

    private static void cleanAtShutdown(long nowMillis) {
        // 标题画面后的普通命中不触发后台扫描，但其近似 LRU mtime 仍需在退出时
        // 落盘。dirty 的并发 set 足以和正在退出的后台 worker 安全汇合。
        for (NamespaceState state : STATES.values()) {
            if (!state.touched().isEmpty()) {
                state.dirty().set(true);
            }
        }
        cleanAll(nowMillis);
    }

    static void cleanAtShutdownForTests(long nowMillis) {
        cleanAtShutdown(nowMillis);
    }

    static Set<Path> protectedPathsForTests(String namespace) {
        NamespaceState state = STATES.get(namespace);
        return state == null ? Set.of() : Set.copyOf(state.touched().keySet());
    }

    static Set<String> registeredNamespacesForTests() {
        return Set.copyOf(STATES.keySet());
    }

    static Map<String, PersistentCacheCleaner.Result>
            lastResultsForTests() {
        return Map.copyOf(new LinkedHashMap<>(LAST_RESULTS));
    }

    static boolean scheduledForTests() {
        return SCHEDULED.get();
    }

    static boolean workRequestedForTests() {
        return WORK_REQUESTED.get();
    }

    static void setBeforeWorkerUnscheduleHookForTests(Runnable hook) {
        beforeWorkerUnscheduleHookForTests = hook;
    }

    static void setBeforeMaintenanceRequestHookForTests(Runnable hook) {
        beforeMaintenanceRequestHookForTests = hook;
    }

    static boolean interruptMaintenanceWorkerForTests() {
        Thread worker = maintenanceWorker;
        if (worker == null) {
            return false;
        }
        worker.interrupt();
        return true;
    }

    static void resetForTests() {
        STARTUP_COMPLETE.set(false);
        WORK_REQUESTED.set(false);
        beforeWorkerUnscheduleHookForTests = null;
        beforeMaintenanceRequestHookForTests = null;
        Thread worker = maintenanceWorker;
        maintenanceWorker = null;
        if (worker != null) {
            worker.interrupt();
            if (worker != Thread.currentThread()) {
                try {
                    worker.join(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | SecurityException ignored) {
            }
        }
        STATES.clear();
        LAST_RESULTS.clear();
        USE_GENERATION.set(0L);
        SCHEDULED.set(false);
        System.clearProperty(DISABLE_PROPERTY);
        System.clearProperty(RETENTION_DAYS_PROPERTY);
        System.clearProperty(START_DELAY_SECONDS_PROPERTY);
        System.clearProperty(
                PersistentCacheCleaner.MAXIMUM_SCANNED_PATHS_PROPERTY);
    }

    private record NamespaceState(
            PersistentCacheCleaner.Policy policy,
            ConcurrentHashMap<Path, Long> touched,
            AtomicBoolean dirty) {
        private NamespaceState(PersistentCacheCleaner.Policy policy) {
            this(
                    policy,
                    new ConcurrentHashMap<>(),
                    new AtomicBoolean(true));
        }
    }

    private record Registration(NamespaceState state, boolean changed) {
    }
}

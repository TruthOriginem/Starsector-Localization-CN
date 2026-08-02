package org.fossic.starsector.optimization;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界并行准备、调用线程按输入顺序消费的通用加载协调器。
 *
 * <p>工作线程只执行传入的纯加载 {@link MethodHandle}；对象构造、跨规格查询和注册仍由
 * 调用者在原线程中执行。结果以输入序号而不是路径为键，因此重复路径也保持原有次数和顺序。
 */
public final class OrderedParallelLoader {
    public static final String WORKERS_PROPERTY =
            "starsector.optimization.specParseWorkers";
    public static final String WINDOW_PROPERTY =
            "starsector.optimization.specParseWindow";

    private static final int DEFAULT_WORKERS = 3;
    private static final int MAX_WORKERS = 8;
    private static final int MAX_WINDOW = 64;
    private static final long ABORT_WAIT_MILLIS = 250L;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<List<LogRecord>> CAPTURED_LOGS =
            new ThreadLocal<>();
    private static final Map<Class<?>, MethodHandle> INFO_METHODS =
            new ConcurrentHashMap<>();

    private OrderedParallelLoader() {
    }

    /** 开始一个按 {@code inputs} 顺序消费的加载阶段。 */
    public static void begin(List<?> inputs, MethodHandle loader) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(loader, "loader");
        Session previous = CURRENT.get();
        if (previous != null) {
            previous.abort();
            CURRENT.remove();
        }

        ArrayList<String> paths = new ArrayList<>(inputs.size());
        for (Object input : inputs) {
            if (!(input instanceof String path)) {
                throw new IllegalArgumentException(
                        "ordered parallel load input is not a String: " + input);
            }
            paths.add(path);
        }
        if (paths.isEmpty()) {
            return;
        }

        int workers = configuredWorkers();
        int window = configuredWindow(workers);
        Session session = new Session(paths, loader, workers, window,
                Thread.currentThread().getContextClassLoader());
        CURRENT.set(session);
        try {
            session.start();
        } catch (RuntimeException | Error failure) {
            CURRENT.remove();
            session.abort();
            throw failure;
        }
    }

    /**
     * 返回当前输入序号的准备结果；没有活动阶段时精确回退到传入 loader。
     */
    public static Object load(String input, MethodHandle fallback)
            throws Throwable {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fallback, "fallback");
        Session session = CURRENT.get();
        if (session == null) {
            return fallback.invoke(input);
        }
        try {
            Object result = session.take(input, fallback);
            if (session.complete()) {
                CURRENT.remove();
            }
            return result;
        } catch (Throwable failure) {
            session.abort();
            CURRENT.remove();
            throw failure;
        }
    }

    /** 正常结束阶段；未消费完全部输入属于 patch/调用结构错误。 */
    public static void finish() {
        Session session = CURRENT.get();
        if (session == null) {
            return;
        }
        CURRENT.remove();
        if (!session.complete()) {
            int consumed = session.consumed();
            int total = session.size();
            session.abort();
            throw new IllegalStateException(
                    "ordered parallel load stage ended early: "
                            + consumed + "/" + total);
        }
        session.shutdown();
    }

    /** 取消当前阶段；供异常路径和测试清理调用。 */
    public static void abort() {
        Session session = CURRENT.get();
        CURRENT.remove();
        if (session != null) {
            session.abort();
        }
    }

    /**
     * LoadingUtils 的 INFO 桥接。工作线程只记录，结果按序消费时才在调用线程回放。
     */
    public static void info(Object logger, Object message) throws Throwable {
        Objects.requireNonNull(logger, "logger");
        List<LogRecord> captured = CAPTURED_LOGS.get();
        if (captured != null) {
            captured.add(new LogRecord(logger, message));
            return;
        }
        infoMethod(logger.getClass()).invoke(logger, message);
    }

    static boolean activeForTests() {
        return CURRENT.get() != null;
    }

    static int inFlightForTests() {
        Session session = CURRENT.get();
        return session == null ? 0 : session.inFlight();
    }

    private static int configuredWorkers() {
        int configured = Integer.getInteger(
                WORKERS_PROPERTY, DEFAULT_WORKERS);
        return Math.max(0, Math.min(MAX_WORKERS, configured));
    }

    private static int configuredWindow(int workers) {
        if (workers == 0) {
            return 0;
        }
        int configured = Integer.getInteger(
                WINDOW_PROPERTY, workers * 2);
        return Math.max(workers, Math.min(MAX_WINDOW, configured));
    }

    private static final class Session {
        private final List<String> paths;
        private final MethodHandle loader;
        private final int window;
        private final ExecutorService executor;
        private final Map<Integer, Future<Result>> futures = new HashMap<>();
        private int nextToSubmit;
        private int nextToConsume;

        private Session(
                List<String> paths,
                MethodHandle loader,
                int workers,
                int window,
                ClassLoader contextClassLoader) {
            this.paths = paths;
            this.loader = loader;
            this.window = window;
            if (workers == 0) {
                this.executor = null;
            } else {
                ThreadPoolExecutor pool = new ThreadPoolExecutor(
                        workers,
                        workers,
                        1,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        threadFactory(contextClassLoader));
                pool.allowCoreThreadTimeOut(true);
                this.executor = pool;
            }
        }

        private void start() {
            submitUntilWindowFull();
        }

        private Object take(String input, MethodHandle fallback)
                throws Throwable {
            if (nextToConsume >= paths.size()) {
                throw new IllegalStateException(
                        "ordered parallel load consumed past end: " + input);
            }
            String expected = paths.get(nextToConsume);
            if (!expected.equals(input)) {
                throw new IllegalStateException(
                        "ordered parallel load order mismatch at "
                                + nextToConsume + ": expected [" + expected
                                + "] but got [" + input + "]");
            }

            Object value;
            if (executor == null) {
                value = loader.invoke(input);
            } else {
                Future<Result> future = futures.remove(nextToConsume);
                if (future == null) {
                    throw new IllegalStateException(
                            "ordered parallel result missing at "
                                    + nextToConsume);
                }
                Result result = awaitUninterruptibly(future);
                if (result.failure != null) {
                    if (result.failure instanceof Error fatal) {
                        // Error 表示 VM/链接/断言级故障；串行再跑只会放大内存压力或
                        // 吞掉 worker 的致命状态，必须在消费顺序点原样传播。
                        throw fatal;
                    }
                    // A preceding specification can generate or repair a later
                    // resource while the original serial loop is running.  A
                    // failed speculative read is therefore not authoritative:
                    // retry it at the exact original call site and do not replay
                    // logs from the discarded attempt.
                    value = fallback.invoke(input);
                } else {
                    replayLogs(result.logs);
                    value = result.value;
                }
            }

            nextToConsume++;
            submitUntilWindowFull();
            if (complete()) {
                shutdown();
            }
            return value;
        }

        private void submitUntilWindowFull() {
            if (executor == null) {
                return;
            }
            while (nextToSubmit < paths.size()
                    && nextToSubmit - nextToConsume < window) {
                int ordinal = nextToSubmit++;
                String path = paths.get(ordinal);
                futures.put(ordinal, executor.submit(() -> {
                    ArrayList<LogRecord> logs = new ArrayList<>();
                    SpeculativeResourceContext.enter();
                    CAPTURED_LOGS.set(logs);
                    try {
                        return new Result(loader.invoke(path), null, logs);
                    } catch (Throwable failure) {
                        return new Result(null, failure, logs);
                    } finally {
                        CAPTURED_LOGS.remove();
                        SpeculativeResourceContext.exit();
                    }
                }));
            }
        }

        private void shutdown() {
            if (executor != null) {
                executor.shutdown();
            }
        }

        private void abort() {
            for (Future<Result> future : futures.values()) {
                future.cancel(true);
            }
            futures.clear();
            if (executor != null) {
                executor.shutdownNow();
                awaitTerminationAfterAbort(executor);
            }
        }

        private boolean complete() {
            return nextToConsume == paths.size();
        }

        private int consumed() {
            return nextToConsume;
        }

        private int size() {
            return paths.size();
        }

        private int inFlight() {
            return futures.size();
        }
    }

    private static Result awaitUninterruptibly(Future<Result> future)
            throws Throwable {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException impossible) {
                    Throwable cause = impossible.getCause();
                    throw cause == null ? impossible : cause;
                } catch (CancellationException cancelled) {
                    throw new IllegalStateException(
                            "ordered parallel result was cancelled", cancelled);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ThreadFactory threadFactory(ClassLoader contextClassLoader) {
        return task -> {
            Thread thread = new Thread(
                    task,
                    "fossic-spec-json-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(contextClassLoader);
            return thread;
        };
    }

    private static void awaitTerminationAfterAbort(ExecutorService executor) {
        boolean interrupted = false;
        try {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(ABORT_WAIT_MILLIS);
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return;
                }
                try {
                    executor.awaitTermination(
                            remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void replayLogs(List<LogRecord> logs) throws Throwable {
        for (LogRecord log : logs) {
            info(log.logger, log.message);
        }
    }

    private static MethodHandle infoMethod(Class<?> loggerClass) {
        MethodHandle existing = INFO_METHODS.get(loggerClass);
        if (existing != null) {
            return existing;
        }
        try {
            MethodHandle resolved = MethodHandles.lookup().findVirtual(
                    loggerClass,
                    "info",
                    MethodType.methodType(void.class, Object.class));
            MethodHandle raced = INFO_METHODS.putIfAbsent(
                    loggerClass, resolved);
            return raced == null ? resolved : raced;
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException(
                    "logger has no accessible info(Object): "
                            + loggerClass.getName(), failure);
        }
    }

    private record Result(
            Object value, Throwable failure, List<LogRecord> logs) {
    }

    private record LogRecord(Object logger, Object message) {
    }
}

package org.fossic.starsector.optimization;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在不扫描文件系统、不改变资源覆盖顺序的前提下，并行处理游戏已经建立的图片预读队列。
 *
 * <p>一个 primary worker 保持原版“先声音字节、后图片”的次序；其余 worker 只处理图片。
 * 路径领取和结果状态协调委托给 {@link PreloadResultCoordinator}。反射只用于绑定原版两个私有
 * loader；绑定失败或显式配置为一个 worker 时，立即退回 patch 传入的原版 Runnable。
 */
public final class ParallelImagePreloader {
    public static final String WORKER_COUNT_PROPERTY =
            "starsector.optimization.imagePreloadWorkers";

    private static final int MAX_WORKERS = 3;
    private static final long STOP_JOIN_MILLIS = 250L;
    private static final Object WORKER_LOCK = new Object();
    private static final List<Thread> WORKERS = new ArrayList<>();

    private ParallelImagePreloader() {
    }

    /** 由 ASM bridge 调用；返回 primary thread 以保留原版静态字段。 */
    public static Thread start(
            Runnable fallbackPrimary,
            List<String> soundQueue,
            Map<String, byte[]> soundResults,
            byte[] soundSentinel,
            List<String> imageQueue,
            Map<String, BufferedImage> imageResults,
            BufferedImage imageSentinel,
            Class<?> loaderOwner,
            String soundLoaderName,
            String imageLoaderName,
            Object logger) {
        Objects.requireNonNull(fallbackPrimary, "fallbackPrimary");
        int workerCount = configuredWorkerCount();
        if (workerCount == 1) {
            return startFallback(fallbackPrimary);
        }

        try {
            Decoder<byte[]> soundDecoder = reflectiveDecoder(
                    loaderOwner, soundLoaderName, byte[].class);
            Decoder<BufferedImage> imageDecoder = reflectiveDecoder(
                    loaderOwner, imageLoaderName, BufferedImage.class);
            ErrorReporter reporter = reflectiveReporter(logger);
            return startWorkers(
                    workerCount,
                    soundQueue,
                    soundResults,
                    soundSentinel,
                    imageQueue,
                    imageResults,
                    imageSentinel,
                    soundDecoder,
                    imageDecoder,
                    reporter);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportSetupFailure(logger, failure);
            return startFallback(fallbackPrimary);
        }
    }

    static Thread startWorkers(
            int workerCount,
            List<String> soundQueue,
            Map<String, byte[]> soundResults,
            byte[] soundSentinel,
            List<String> imageQueue,
            Map<String, BufferedImage> imageResults,
            BufferedImage imageSentinel,
            Decoder<byte[]> soundDecoder,
            Decoder<BufferedImage> imageDecoder,
            ErrorReporter reporter) {
        if (workerCount < 1 || workerCount > MAX_WORKERS) {
            throw new IllegalArgumentException(
                    "workerCount must be between 1 and " + MAX_WORKERS);
        }
        WorkChannel<byte[]> sounds = new WorkChannel<>(
                soundQueue,
                soundResults,
                soundSentinel,
                soundDecoder,
                PreloadResultCoordinator::claimSound,
                PreloadResultCoordinator::soundCompleted,
                PreloadResultCoordinator::soundFailed);
        WorkChannel<BufferedImage> images = new WorkChannel<>(
                imageQueue,
                imageResults,
                imageSentinel,
                imageDecoder,
                PreloadResultCoordinator::claimImage,
                PreloadResultCoordinator::imageCompleted,
                PreloadResultCoordinator::imageFailed);
        Session session = new Session(sounds, images, reporter);

        stop();
        Thread primary = new Thread(
                session::runPrimary,
                "Starsector-Preload-Primary");
        ArrayList<Thread> created = new ArrayList<>();
        created.add(primary);
        for (int index = 1; index < workerCount; index++) {
            Thread secondary = new Thread(
                    session::runImages,
                    "Starsector-Image-Preload-" + index);
            secondary.setDaemon(true);
            created.add(secondary);
        }
        synchronized (WORKER_LOCK) {
            WORKERS.addAll(created);
        }
        for (Thread worker : created) {
            worker.start();
        }
        return primary;
    }

    public static void stop() {
        List<Thread> snapshot;
        synchronized (WORKER_LOCK) {
            snapshot = List.copyOf(WORKERS);
        }
        for (Thread worker : snapshot) {
            worker.interrupt();
        }
        boolean interrupted = false;
        long deadline = System.nanoTime()
                + STOP_JOIN_MILLIS * 1_000_000L;
        for (Thread worker : snapshot) {
            if (worker == Thread.currentThread()) {
                continue;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                long millis = Math.max(
                        1L, remainingNanos / 1_000_000L);
                worker.join(millis);
            } catch (InterruptedException stopInterrupted) {
                interrupted = true;
                break;
            }
        }
        synchronized (WORKER_LOCK) {
            WORKERS.removeIf(worker -> !worker.isAlive());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static int activeWorkerCount() {
        synchronized (WORKER_LOCK) {
            WORKERS.removeIf(worker -> !worker.isAlive());
            return WORKERS.size();
        }
    }

    private static Thread startFallback(Runnable fallbackPrimary) {
        stop();
        Thread primary = new Thread(fallbackPrimary);
        synchronized (WORKER_LOCK) {
            WORKERS.add(primary);
        }
        primary.start();
        return primary;
    }

    private static int configuredWorkerCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        int defaultWorkers = Math.min(
                MAX_WORKERS, Math.max(1, processors / 2));
        int configured = Integer.getInteger(
                WORKER_COUNT_PROPERTY, defaultWorkers);
        return Math.max(1, Math.min(MAX_WORKERS, configured));
    }

    private static <T> Decoder<T> reflectiveDecoder(
            Class<?> owner, String methodName, Class<T> returnType)
            throws ReflectiveOperationException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        Method method = owner.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        if (!returnType.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException(
                    owner.getName() + "." + methodName
                            + " has incompatible return type "
                            + method.getReturnType().getName());
        }
        return path -> {
            try {
                return returnType.cast(method.invoke(null, path));
            } catch (InvocationTargetException invocation) {
                Throwable cause = invocation.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(cause);
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException(inaccessible);
            }
        };
    }

    private static ErrorReporter reflectiveReporter(Object logger)
            throws ReflectiveOperationException {
        Objects.requireNonNull(logger, "logger");
        Method error = logger.getClass().getMethod(
                "error", Object.class, Throwable.class);
        error.setAccessible(true);
        return failure -> {
            try {
                error.invoke(logger, failure.getMessage(), failure);
            } catch (ReflectiveOperationException loggingFailure) {
                failure.printStackTrace(System.err);
                loggingFailure.printStackTrace(System.err);
            }
        };
    }

    private static void reportSetupFailure(
            Object logger, Exception failure) {
        try {
            reflectiveReporter(logger).report(failure);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            failure.printStackTrace(System.err);
        }
    }

    private static boolean interrupted(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @FunctionalInterface
    interface Decoder<T> {
        T decode(String path) throws Exception;
    }

    @FunctionalInterface
    interface ErrorReporter {
        void report(Throwable failure);
    }

    @FunctionalInterface
    private interface Claimer<T> {
        String claim(List<String> queue, Map<String, T> results, T sentinel);
    }

    @FunctionalInterface
    private interface Completer<T> {
        T complete(Map<String, T> results, String path, T result);
    }

    @FunctionalInterface
    private interface Failer<T> {
        T fail(Map<String, T> results, String path);
    }

    private record WorkChannel<T>(
            List<String> queue,
            Map<String, T> results,
            T sentinel,
            Decoder<T> decoder,
            Claimer<T> claimer,
            Completer<T> completer,
            Failer<T> failer) {
        private String claim() {
            return claimer.claim(queue, results, sentinel);
        }

        private void complete(String path, T result) {
            completer.complete(results, path, result);
        }

        private void fail(String path) {
            failer.fail(results, path);
        }
    }

    private record Session(
            WorkChannel<byte[]> sounds,
            WorkChannel<BufferedImage> images,
            ErrorReporter reporter) {
        private void runPrimary() {
            // 这条线程替代原版唯一 preload worker，必须保留其消费一次性
            // resource selector/skip-mod 状态的语义。只有新增的 secondary
            // 图片 worker 属于推测读取，不能抢走该全局状态。
            if (drain(sounds)) {
                drain(images);
            }
        }

        private void runImages() {
            SpeculativeResourceContext.enter();
            try {
                drain(images);
            } finally {
                SpeculativeResourceContext.exit();
            }
        }

        private <T> boolean drain(WorkChannel<T> channel) {
            while (true) {
                String path = channel.claim();
                if (path == null) {
                    return !Thread.currentThread().isInterrupted();
                }
                try {
                    T result = channel.decoder().decode(path);
                    channel.complete(path, result);
                } catch (Throwable failure) {
                    channel.fail(path);
                    if (interrupted(failure)) {
                        return false;
                    }
                    // 原版 worker 只捕获 Exception；任何 Error 都会终止该
                    // worker。先释放协调器中的 sentinel，避免等待者永久阻塞，
                    // 再保持原有的 Error 传播语义。
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    reporter.report(failure);
                }
                if (Thread.interrupted()) {
                    return false;
                }
            }
        }
    }
}

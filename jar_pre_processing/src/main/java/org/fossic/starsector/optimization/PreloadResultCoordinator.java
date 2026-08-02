package org.fossic.starsector.optimization;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协调纹理和声音预读队列，同时保留原版主线程每 10 ms 轮询一次的等待节奏。
 *
 * <p>普通入队保留原版允许重复路径的语义；独立优化组可为图片改用同一预读周期内的精确
 * 路径去重。worker 把路径从队列移入结果 map 的 loading sentinel 后减少计数，并在完成或
 * 失败时更新状态。主线程在每次轮询时于同一状态锁内检查结果和排队计数；worker 间仍使用
 * 通知协调同路径领取和 shutdown，以保证 O16/O27 的并发安全。
 */
public final class PreloadResultCoordinator {
    private static final long POLL_INTERVAL_MILLIS = 10L;
    private static final Channel<byte[]> SOUNDS = new Channel<>();
    private static final Channel<BufferedImage> IMAGES = new Channel<>();

    private PreloadResultCoordinator() {
    }

    public static void queueSound(List<String> queue, String path) {
        SOUNDS.enqueue(queue, path);
    }

    public static void queueImage(List<String> queue, String path) {
        IMAGES.enqueue(queue, path);
    }

    public static void queueImageUnique(
            List<String> queue, String path) {
        boolean accepted = IMAGES.enqueueUnique(queue, path);
        PreloadPathDedupDiagnostics.record(accepted);
    }

    public static byte[] soundStarted(
            Map<String, byte[]> results, String path, byte[] loadingSentinel) {
        return SOUNDS.started(results, path, loadingSentinel);
    }

    public static BufferedImage imageStarted(
            Map<String, BufferedImage> results,
            String path,
            BufferedImage loadingSentinel) {
        return IMAGES.started(results, path, loadingSentinel);
    }

    public static String claimSound(
            List<String> queue,
            Map<String, byte[]> results,
            byte[] loadingSentinel) {
        return SOUNDS.claim(queue, results, loadingSentinel);
    }

    public static String claimImage(
            List<String> queue,
            Map<String, BufferedImage> results,
            BufferedImage loadingSentinel) {
        return IMAGES.claim(queue, results, loadingSentinel);
    }

    public static byte[] soundCompleted(
            Map<String, byte[]> results, String path, byte[] result) {
        return SOUNDS.completed(results, path, result);
    }

    public static BufferedImage imageCompleted(
            Map<String, BufferedImage> results,
            String path,
            BufferedImage result) {
        return IMAGES.completed(results, path, result);
    }

    public static byte[] soundFailed(
            Map<String, byte[]> results, String path) {
        return SOUNDS.failed(results, path);
    }

    public static BufferedImage imageFailed(
            Map<String, BufferedImage> results, String path) {
        return IMAGES.failed(results, path);
    }

    public static byte[] awaitSound(
            String path,
            Map<String, byte[]> results,
            byte[] loadingSentinel) {
        return SOUNDS.await(path, results, loadingSentinel);
    }

    public static BufferedImage awaitImage(
            String path,
            Map<String, BufferedImage> results,
            BufferedImage loadingSentinel) {
        return IMAGES.await(path, results, loadingSentinel);
    }

    public static void clear() {
        SOUNDS.clear();
        IMAGES.clear();
    }

    /**
     * 单类预读结果的独立状态机。包内可见，以便不依赖游戏类测试全部并发语义。
     */
    static final class Channel<T> {
        private final ConcurrentHashMap<String, PathState> states =
                new ConcurrentHashMap<>();
        private final AtomicLong generation = new AtomicLong();
        private final ThreadLocal<Lease> inFlight = new ThreadLocal<>();

        void enqueue(List<String> queue, String path) {
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(path, "path");
            PathState state = activeState(path);
            synchronized (state) {
                if (!isActive(path, state)) {
                    enqueue(queue, path);
                    return;
                }
                state.queued++;
            }

            boolean added = false;
            try {
                added = queue.add(path);
            } finally {
                if (!added) {
                    synchronized (state) {
                        state.queued--;
                        state.notifyAll();
                    }
                }
            }
        }

        boolean enqueueUnique(List<String> queue, String path) {
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(path, "path");
            PathState state = activeState(path);
            synchronized (state) {
                if (!isActive(path, state)) {
                    return enqueueUnique(queue, path);
                }
                if (state.uniqueReservation) {
                    return false;
                }
                state.uniqueReservation = true;
                state.queued++;
            }

            boolean added = false;
            try {
                added = queue.add(path);
                return added;
            } finally {
                if (!added) {
                    synchronized (state) {
                        state.queued--;
                        state.uniqueReservation = false;
                        state.notifyAll();
                    }
                }
            }
        }

        T started(
                Map<String, T> results, String path, T loadingSentinel) {
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(loadingSentinel, "loadingSentinel");
            PathState state = activeState(path);
            synchronized (state) {
                if (!isActive(path, state)) {
                    return results.get(path);
                }
                if (state.queued <= 0) {
                    throw new IllegalStateException(
                            "preload started without a queued path: " + path);
                }
                T previous = results.put(path, loadingSentinel);
                state.queued--;
                inFlight.set(new Lease(path, state));
                state.notifyAll();
                return previous;
            }
        }

        /**
         * 在队列锁内完成 empty 检查、移除和 loading 状态转换，使多个 worker 不会
         * 在 {@code isEmpty()} 与 {@code remove(0)} 之间竞争。队首同路径已在解码时，
         * 等待前一次完成后再领取：这既避免并发解码同一路径，也不会把
         * “暂时不可领取”误当作“队列已空”而让 worker 提前退出。
         */
        String claim(
                List<String> queue,
                Map<String, T> results,
                T loadingSentinel) {
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(loadingSentinel, "loadingSentinel");
            synchronized (queue) {
                if (queue.isEmpty()) {
                    return null;
                }
                String path = queue.get(0);
                PathState state = activeState(path);
                synchronized (state) {
                    while (!state.cleared
                            && results.get(path) == loadingSentinel) {
                        try {
                            state.wait();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                    if (state.cleared) {
                        return null;
                    }
                    if (!isActive(path, state)) {
                        return null;
                    }
                    if (state.queued <= 0) {
                        throw new IllegalStateException(
                                "preload claimed without a queued path: "
                                        + path);
                    }
                    queue.remove(0);
                    results.put(path, loadingSentinel);
                    state.queued--;
                    inFlight.set(new Lease(path, state));
                    state.notifyAll();
                    return path;
                }
            }
        }

        T completed(Map<String, T> results, String path, T result) {
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(path, "path");
            Lease lease = takeLease(path);
            if (lease == null) {
                return results.get(path);
            }
            PathState state = lease.state();
            synchronized (state) {
                if (!isActive(path, state)) {
                    return results.get(path);
                }
                T previous = results.put(path, result);
                state.notifyAll();
                return previous;
            }
        }

        T failed(Map<String, T> results, String path) {
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(path, "path");
            Lease lease = takeLease(path);
            if (lease == null) {
                return results.get(path);
            }
            PathState state = lease.state();
            synchronized (state) {
                if (!isActive(path, state)) {
                    return results.get(path);
                }
                T previous = results.remove(path);
                state.notifyAll();
                return previous;
            }
        }

        T await(
                String path,
                Map<String, T> results,
                T loadingSentinel) {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(loadingSentinel, "loadingSentinel");
            PathState state = activeState(path);
            while (true) {
                synchronized (state) {
                    if (state.cleared) {
                        return null;
                    }

                    T result = results.get(path);
                    if (result != null && result != loadingSentinel) {
                        results.remove(path);
                        state.uniqueReservation = false;
                        state.notifyAll();
                        return result;
                    }
                    if (state.queued == 0
                            && !results.containsKey(path)) {
                        state.uniqueReservation = false;
                        state.notifyAll();
                        return null;
                    }

                }

                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    return null;
                }
            }
        }

        void clear() {
            generation.incrementAndGet();
            for (PathState state : states.values()) {
                synchronized (state) {
                    state.cleared = true;
                    state.queued = 0;
                    state.notifyAll();
                }
            }
            states.clear();
            inFlight.remove();
        }

        private PathState activeState(String path) {
            while (true) {
                long currentGeneration = generation.get();
                PathState state =
                        states.computeIfAbsent(
                                path,
                                ignored -> new PathState(currentGeneration));
                synchronized (state) {
                    if (isActive(path, state)) {
                        return state;
                    }
                }
                states.remove(path, state);
            }
        }

        private boolean isActive(String path, PathState state) {
            return !state.cleared
                    && state.generation == generation.get()
                    && states.get(path) == state;
        }

        private Lease takeLease(String path) {
            Lease lease = inFlight.get();
            inFlight.remove();
            if (lease == null || !path.equals(lease.path())) {
                return null;
            }
            return lease;
        }

        private record Lease(String path, PathState state) {
        }
    }

    private static final class PathState {
        private final long generation;
        private int queued;
        private boolean uniqueReservation;
        private boolean cleared;

        private PathState(long generation) {
            this.generation = generation;
        }
    }
}

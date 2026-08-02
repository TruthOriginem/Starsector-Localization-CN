package org.fossic.starsector.optimization;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * 为原版已明确转移所有权的资源流提供集中、可测试的关闭语义。
 *
 * <p>图片和声音入口同步地把流完全物化，故返回后没有合法的延迟读取者。LoadingUtils
 * 则一次性打开所有来源；{@link PairStreamScope} 在解析失败时关闭尚未消费的来源，并把
 * 任何关闭失败挂到原异常上，绝不替换原始加载错误。
 */
public final class OwnedResourceStreams {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final ThreadLocal<Deque<PairStreamScope>> PAIR_SCOPES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<PartialOpenScope>>
            PARTIAL_OPEN_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ClassValue<Field> PAIR_SECOND_FIELD =
            new ClassValue<>() {
                @Override
                protected Field computeValue(Class<?> type) {
                    try {
                        return type.getField("two");
                    } catch (NoSuchFieldException missing) {
                        throw new IllegalStateException(
                                "Resource pair type has no public 'two' field: "
                                        + type.getName(),
                                missing);
                    }
                }
            };

    private OwnedResourceStreams() {
    }

    public static BufferedImage readImageAndClose(InputStream input)
            throws IOException {
        try (InputStream owned = Objects.requireNonNull(input, "input")) {
            return ImageIO.read(owned);
        }
    }

    public static BufferedImage decodePngAndClose(InputStream input)
            throws IOException {
        try (InputStream owned = Objects.requireNonNull(input, "input")) {
            return FastPngDecoder.decode(owned);
        }
    }

    public static BufferedImage decodeTrackedPngAndClose(
            String path, InputStream input) throws IOException {
        try (InputStream owned = Objects.requireNonNull(input, "input")) {
            return FastPngDecoder.decodeTracked(path, owned);
        }
    }

    public static byte[] readAllAndClose(InputStream input)
            throws IOException {
        try (InputStream owned = Objects.requireNonNull(input, "input")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            while ((count = owned.read(buffer, 0, buffer.length)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                    continue;
                }
                // InputStream 的常规契约不应在 len > 0 时返回 0，
                // 但部分 mod 自定义流可能违反该约定；退化为单字节读取以
                // 保证进度，避免启动线程永久空转。
                int single = owned.read();
                if (single == -1) {
                    break;
                }
                output.write(single);
            }
            output.flush();
            return output.toByteArray();
        }
    }

    /**
     * 关闭资源加载器已经判定不会返回给调用方的重复来源流，并原样返回该判定。
     *
     * <p>原版成功路径此前忽略该流，故任何 close 失败都不得改变加载结果。
     */
    public static boolean closeIfDiscarded(
            InputStream input, boolean discarded) {
        if (!discarded || input == null) {
            return discarded;
        }
        Deque<PartialOpenScope> scopes = PARTIAL_OPEN_SCOPES.get();
        PartialOpenScope current = scopes.peek();
        if (current == null) {
            PARTIAL_OPEN_SCOPES.remove();
        }
        try {
            input.close();
        } catch (Throwable ignored) {
            if (current != null) {
                try {
                    current.markDiscardedCloseFailure(input);
                } catch (Throwable trackingFailure) {
                    // 成功路径不能因 cleanup bookkeeping 改变结果。
                }
            }
            return true;
        }
        if (current != null) {
            try {
                current.forget(input);
            } catch (Throwable ignored) {
                // close 已成功；重复保留只会让异常路径做幂等重试。
            }
        }
        return true;
    }

    /** 在 {@code C.new(path)} 创建任何流前建立可嵌套的所有权暂存区。 */
    public static void enterPartialOpen() {
        PARTIAL_OPEN_SCOPES.get().push(new PartialOpenScope());
    }

    /**
     * 在 leaf open 返回后的第一条指令登记流，覆盖 Pair 分配/add 失败窗口。
     */
    public static InputStream trackPartialOpenStream(InputStream input) {
        Deque<PartialOpenScope> scopes = PARTIAL_OPEN_SCOPES.get();
        PartialOpenScope current = scopes.peek();
        if (current == null) {
            PARTIAL_OPEN_SCOPES.remove();
            IllegalStateException failure = new IllegalStateException(
                    "No active resource-loader partial-open scope");
            closeOneAfterFailure(failure, input);
            throw failure;
        }
        if (input == null) {
            return null;
        }
        try {
            current.track(input);
        } catch (RuntimeException | Error failure) {
            closeOneAfterFailure(failure, input);
            throw failure;
        }
        return input;
    }

    /** 成功返回仅释放追踪状态；所有流的所有权继续移交给返回列表。 */
    public static void releasePartialOpen() {
        Deque<PartialOpenScope> scopes = PARTIAL_OPEN_SCOPES.get();
        PartialOpenScope current = scopes.poll();
        removeEmptyPartialOpenStack(scopes);
        if (current == null) {
            throw new IllegalStateException(
                    "No active resource-loader partial-open scope");
        }
        current.release();
    }

    /** 异常退出关闭当前 open 已经返回的全部 identity-distinct 流。 */
    public static void closePartialOpenAfterFailure(Throwable primary) {
        Objects.requireNonNull(primary, "primary");
        Deque<PartialOpenScope> scopes = PARTIAL_OPEN_SCOPES.get();
        PartialOpenScope current = scopes.poll();
        removeEmptyPartialOpenStack(scopes);
        if (current == null) {
            suppress(primary, new IllegalStateException(
                    "No active resource-loader partial-open scope"));
            return;
        }
        current.closeAfterFailure(primary);
    }

    /**
     * 捕获 {@code com.fs.util.container.Pair.two} 中的所有流。
     *
     * <p>通过反射隔离游戏内部 Pair 类型，避免 optimization helper 对混淆引擎 Jar
     * 增加编译期依赖。Pair 实现 DoNotObfuscate，目标版本的公共字段由 ASM patch 的
     * 真实 Jar 测试共同约束。
     */
    public static PairStreamScope capturePairStreams(Iterable<?> pairs) {
        Objects.requireNonNull(pairs, "pairs");
        ArrayList<InputStream> streams = new ArrayList<>();
        InputStream pending = null;
        try {
            for (Object pair : pairs) {
                if (pair == null) {
                    throw new IllegalStateException(
                            "Resource pair list contains null");
                }
                Object second = PAIR_SECOND_FIELD.get(pair.getClass())
                        .get(pair);
                if (!(second instanceof InputStream stream)) {
                    throw new IllegalStateException(
                            "Resource pair 'two' is not an InputStream: "
                                    + (second == null
                                            ? "null"
                                            : second.getClass().getName()));
                }
                pending = stream;
                if (!containsIdentity(streams, stream)) {
                    streams.add(stream);
                }
                pending = null;
            }
            return new PairStreamScope(streams);
        } catch (IllegalAccessException inaccessible) {
            IllegalStateException failure = new IllegalStateException(
                    "Cannot read resource pair public 'two' field",
                    inaccessible);
            closeCaptureFailure(failure, pending, streams);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeCaptureFailure(failure, pending, streams);
            throw failure;
        }
    }

    /** 由 LoadingUtils ASM bridge 在所有来源流创建后调用。 */
    public static void enterPairStreams(Iterable<?> pairs) {
        PairStreamScope scope = capturePairStreams(pairs);
        Deque<PairStreamScope> scopes = null;
        try {
            scopes = PAIR_SCOPES.get();
            scopes.push(scope);
        } catch (RuntimeException | Error failure) {
            // capture 已经取得所有权；即使 ThreadLocal/栈登记在
            // OOME 下失败，也不能让刚打开的整批流脱离追踪。
            scope.closeAfterFailure(failure);
            if (scopes != null && scopes.isEmpty()) {
                PAIR_SCOPES.remove();
            }
            throw failure;
        }
    }

    /** reader 已成功消费并关闭流后，从当前 high-level scope 按 identity 移除。 */
    public static void forgetCurrentPairStream(InputStream input) {
        if (input == null) {
            return;
        }
        Deque<PairStreamScope> scopes = PAIR_SCOPES.get();
        PairStreamScope current = scopes.peek();
        if (current == null) {
            PAIR_SCOPES.remove();
            return;
        }
        try {
            current.forget(input);
        } catch (Throwable ignored) {
            // 成功读取路径不能因 bookkeeping 改变结果。
        }
    }

    /** 显式 close 成功后才 forget；close 失败时保留给 high-level 异常清理重试。 */
    public static void closeAndForgetCurrentPairStream(InputStream input)
            throws IOException {
        input.close();
        forgetCurrentPairStream(input);
    }

    /** 关闭并弹出当前 LoadingUtils 调用所拥有的流。 */
    public static void closeCurrentBeforeReturn() {
        Deque<PairStreamScope> scopes = PAIR_SCOPES.get();
        PairStreamScope current = scopes.peek();
        if (current == null) {
            PAIR_SCOPES.remove();
            throw new IllegalStateException(
                    "No active LoadingUtils resource stream scope");
        }
        // 若 fatal close 失败，保留栈顶，让 ASM catch-all 走异常清理并弹出同一 scope。
        current.closeBeforeReturn();
        scopes.pop();
        removeEmptyScopeStack(scopes);
    }

    /** 异常路径关闭当前 scope；关闭失败只作为 suppressed 附到原异常。 */
    public static void closeCurrentAfterFailure(Throwable primary) {
        Objects.requireNonNull(primary, "primary");
        Deque<PairStreamScope> scopes = PAIR_SCOPES.get();
        PairStreamScope current = scopes.poll();
        removeEmptyScopeStack(scopes);
        if (current == null) {
            suppress(primary, new IllegalStateException(
                    "No active LoadingUtils resource stream scope"));
            return;
        }
        current.closeAfterFailure(primary);
    }

    /** 正常返回前 best-effort 关闭；普通 close 异常不改变原加载结果。 */
    public static void closeBeforeReturn(PairStreamScope scope) {
        if (scope != null) {
            scope.closeBeforeReturn();
        }
    }

    /** 异常路径关闭，并始终保留传入的原异常为主异常。 */
    public static void closeAfterFailure(
            Throwable primary, PairStreamScope scope) {
        Objects.requireNonNull(primary, "primary");
        if (scope != null) {
            scope.closeAfterFailure(primary);
        }
    }

    private static void removeEmptyScopeStack(
            Deque<PairStreamScope> scopes) {
        if (scopes.isEmpty()) {
            PAIR_SCOPES.remove();
        }
    }

    private static void removeEmptyPartialOpenStack(
            Deque<PartialOpenScope> scopes) {
        if (scopes.isEmpty()) {
            PARTIAL_OPEN_SCOPES.remove();
        }
    }

    private static void closeOneAfterFailure(
            Throwable primary, InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Throwable closeFailure) {
            suppress(primary, closeFailure);
        }
    }

    private static void closeCaptureFailure(
            Throwable primary,
            InputStream pending,
            ArrayList<InputStream> streams) {
        if (pending != null && !containsIdentity(streams, pending)) {
            closeOneAfterFailure(primary, pending);
        }
        for (int index = 0; index < streams.size(); index++) {
            closeOneAfterFailure(primary, streams.get(index));
        }
    }

    private static boolean containsIdentity(
            List<InputStream> streams, InputStream candidate) {
        for (int index = 0; index < streams.size(); index++) {
            if (streams.get(index) == candidate) {
                return true;
            }
        }
        return false;
    }

    private static void suppress(
            Throwable primary, Throwable secondary) {
        if (secondary == primary) {
            return;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // 清理/诊断失败绝不能覆盖原始资源加载异常。
        }
    }

    /** 一次 LoadingUtils 多来源加载所拥有的流集合。 */
    public static final class PairStreamScope {
        private final ArrayList<InputStream> streams;
        private boolean closed;

        private PairStreamScope(ArrayList<InputStream> streams) {
            this.streams = streams;
        }

        private synchronized void forget(InputStream input) {
            if (closed) {
                return;
            }
            for (int index = 0; index < streams.size(); index++) {
                if (streams.get(index) == input) {
                    streams.remove(index);
                    return;
                }
            }
        }

        private void closeBeforeReturn() {
            ArrayList<InputStream> claimed = claim();
            if (claimed == null) {
                return;
            }
            Throwable fatal = null;
            for (int index = 0; index < claimed.size(); index++) {
                InputStream stream = claimed.get(index);
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // 兼容原版正常加载结果；其余流仍必须继续关闭。
                } catch (Error closeFailure) {
                    if (fatal == null) {
                        fatal = closeFailure;
                    } else if (fatal != closeFailure) {
                        suppress(fatal, closeFailure);
                    }
                }
            }
            if (fatal instanceof ThreadDeath death) {
                throw death;
            }
            if (fatal instanceof VirtualMachineError vmFailure) {
                throw vmFailure;
            }
            if (fatal instanceof Error error) {
                throw error;
            }
        }

        private void closeAfterFailure(Throwable primary) {
            ArrayList<InputStream> claimed = claim();
            if (claimed == null) {
                return;
            }
            for (int index = 0; index < claimed.size(); index++) {
                InputStream stream = claimed.get(index);
                try {
                    stream.close();
                } catch (Throwable closeFailure) {
                    suppress(primary, closeFailure);
                }
            }
        }

        private synchronized ArrayList<InputStream> claim() {
            if (closed) {
                return null;
            }
            closed = true;
            return streams;
        }
    }

    /** 一次 C.new 多来源 open 尚未移交给成功返回值的流集合。 */
    private static final class PartialOpenScope {
        private final ArrayList<InputStream> streams = new ArrayList<>();
        private InputStream firstDiscardedCloseFailure;
        private ArrayList<InputStream> moreDiscardedCloseFailures;
        private boolean finished;

        private synchronized void track(InputStream input) {
            if (finished) {
                throw new IllegalStateException(
                        "Resource-loader partial-open scope is finished");
            }
            for (InputStream existing : streams) {
                if (existing == input) {
                    return;
                }
            }
            streams.add(input);
        }

        private synchronized void forget(InputStream input) {
            if (finished) {
                return;
            }
            for (int index = 0; index < streams.size(); index++) {
                if (streams.get(index) == input) {
                    streams.remove(index);
                    forgetDiscardedCloseFailure(input);
                    return;
                }
            }
        }

        private synchronized void markDiscardedCloseFailure(
                InputStream input) {
            if (finished || firstDiscardedCloseFailure == input) {
                return;
            }
            if (firstDiscardedCloseFailure == null) {
                firstDiscardedCloseFailure = input;
                return;
            }
            if (moreDiscardedCloseFailures == null) {
                moreDiscardedCloseFailures = new ArrayList<>();
            }
            if (!containsIdentity(moreDiscardedCloseFailures, input)) {
                moreDiscardedCloseFailures.add(input);
            }
        }

        private void release() {
            InputStream first;
            ArrayList<InputStream> more;
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                streams.clear();
                first = firstDiscardedCloseFailure;
                more = moreDiscardedCloseFailures;
                firstDiscardedCloseFailure = null;
                moreDiscardedCloseFailures = null;
            }
            closeDiscardedBestEffort(first);
            if (more != null) {
                for (int index = 0; index < more.size(); index++) {
                    closeDiscardedBestEffort(more.get(index));
                }
            }
        }

        private void closeAfterFailure(Throwable primary) {
            ArrayList<InputStream> claimed = claim();
            if (claimed == null) {
                return;
            }
            for (int index = 0; index < claimed.size(); index++) {
                closeOneAfterFailure(primary, claimed.get(index));
            }
        }

        private synchronized ArrayList<InputStream> claim() {
            if (finished) {
                return null;
            }
            finished = true;
            firstDiscardedCloseFailure = null;
            moreDiscardedCloseFailures = null;
            // 异常本身可能是 OOME；直接移交现有列表，清理路径不再分配副本。
            return streams;
        }

        private void forgetDiscardedCloseFailure(InputStream input) {
            if (firstDiscardedCloseFailure == input) {
                firstDiscardedCloseFailure = null;
            }
            if (moreDiscardedCloseFailures == null) {
                return;
            }
            for (int index = 0;
                    index < moreDiscardedCloseFailures.size();
                    index++) {
                if (moreDiscardedCloseFailures.get(index) == input) {
                    moreDiscardedCloseFailures.remove(index);
                    return;
                }
            }
        }

        private static void closeDiscardedBestEffort(InputStream input) {
            if (input == null) {
                return;
            }
            try {
                input.close();
            } catch (Throwable ignored) {
                // C.new 成功返回不能因丢弃流的重试失败而改变结果。
            }
        }
    }
}

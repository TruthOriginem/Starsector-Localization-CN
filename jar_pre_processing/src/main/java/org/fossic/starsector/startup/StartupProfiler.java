package org.fossic.starsector.startup;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无优化逻辑的 Starsector 启动阶段计时器。
 *
 * <p>ASM hook 只调用 {@link #initialize()}、{@link #start(String)}、
 * {@link #end(String)} 与 {@link #onFrame(Object)}。加载过程中仅在内存记录时间线并提交
 * 自定义 JFR 事件；标题画面首个 {@code Display.update(true)} 返回后才一次性写文件，
 * 避免同步调试日志 I/O 污染阶段计时。
 */
public final class StartupProfiler {
    public static final String OUTPUT_DIR_PROPERTY = "starsector.startup.profile.dir";

    private static final String TITLE_SCREEN_CLASS = "com.fs.starfarer.title.TitleScreenState";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_TITLE_FRAME = new AtomicBoolean(false);
    private static final AtomicBoolean COMPLETE_PERSISTED = new AtomicBoolean(false);
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);
    private static final Map<String, PhaseStart> ACTIVE_PHASES = new ConcurrentHashMap<>();
    private static final List<TimelineRecord> TIMELINE = new ArrayList<>();
    private static final Object TIMELINE_LOCK = new Object();
    private static final Object PERSIST_LOCK = new Object();

    private static volatile long mainStartNanos;
    private static volatile long mainStartEpochMillis;
    private static volatile long jvmStartEpochMillis;

    private StartupProfiler() {
    }

    /**
     * 由 {@code CombatMain.main(String[])} 的第一条指令调用。
     */
    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        mainStartNanos = System.nanoTime();
        mainStartEpochMillis = System.currentTimeMillis();
        jvmStartEpochMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        String mainThread = Thread.currentThread().getName();
        recordMarker("combat_main.main.entry", mainStartNanos);
        startAt("startup.total_to_first_title_frame", mainStartNanos, mainThread);
        startAt("combat_main.bootstrap_before_app_driver", mainStartNanos, mainThread);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!COMPLETE_PERSISTED.get()) {
                persist(false);
            }
        }, "Starsector-StartupProfiler-Shutdown"));
    }

    public static void mark(String marker) {
        ensureInitialized();
        recordMarker(marker);
    }

    public static void start(String phase) {
        ensureInitialized();
        startAt(phase, System.nanoTime(), Thread.currentThread().getName());
    }

    private static void startAt(String phase, long now, String thread) {
        PhaseStart start = new PhaseStart(now, thread);
        PhaseStart existing = ACTIVE_PHASES.putIfAbsent(phase, start);
        if (existing == null) {
            recordMarker(phase + ".start", now);
        } else {
            recordMarker(phase + ".duplicate_start", now);
        }
    }

    public static void end(String phase) {
        ensureInitialized();
        long now = System.nanoTime();
        PhaseStart start = ACTIVE_PHASES.remove(phase);
        if (start == null) {
            recordMarker(phase + ".missing_start", now);
            return;
        }

        long durationNanos = Math.max(0L, now - start.nanoTime());
        long startElapsedNanos = Math.max(0L, start.nanoTime() - mainStartNanos);
        recordDuration(phase, startElapsedNanos, durationNanos, start.thread(), now);
    }

    /**
     * 由 {@code BaseGameState.traverse()} 中唯一的 {@code Display.update(true)} 返回后调用。
     */
    public static void onFrame(Object state) {
        if (state == null
                || !TITLE_SCREEN_CLASS.equals(state.getClass().getName())
                || !FIRST_TITLE_FRAME.compareAndSet(false, true)) {
            return;
        }

        end("title.prepare_to_first_frame");
        end("startup.total_to_first_title_frame");
        recordMarker("title.first_frame.displayed");
        persist(true);
    }

    private static void ensureInitialized() {
        if (!INITIALIZED.get()) {
            initialize();
        }
    }

    private static void recordMarker(String marker) {
        recordMarker(marker, System.nanoTime());
    }

    private static void recordMarker(String marker, long nowNanos) {
        long elapsedNanos = Math.max(0L, nowNanos - mainStartNanos);
        String thread = Thread.currentThread().getName();
        StartupMarkerEvent event = new StartupMarkerEvent();
        event.marker = marker;
        event.elapsedNanos = elapsedNanos;
        event.thread = thread;
        event.commit();

        addRecord(new TimelineRecord(
                SEQUENCE.incrementAndGet(),
                "marker",
                marker,
                thread,
                mainStartEpochMillis + elapsedNanos / 1_000_000L,
                elapsedNanos,
                -1L,
                -1L
        ));
    }

    private static void recordDuration(String phase, long startElapsedNanos, long durationNanos,
                                       String startThread, long nowNanos) {
        StartupDurationEvent event = new StartupDurationEvent();
        event.phase = phase;
        event.startElapsedNanos = startElapsedNanos;
        event.durationNanos = durationNanos;
        event.thread = startThread;
        event.commit();

        addRecord(new TimelineRecord(
                SEQUENCE.incrementAndGet(),
                "duration",
                phase,
                startThread,
                mainStartEpochMillis + Math.max(0L, nowNanos - mainStartNanos) / 1_000_000L,
                Math.max(0L, nowNanos - mainStartNanos),
                startElapsedNanos,
                durationNanos
        ));
    }

    private static void addRecord(TimelineRecord record) {
        synchronized (TIMELINE_LOCK) {
            TIMELINE.add(record);
        }
    }

    private static void persist(boolean complete) {
        synchronized (PERSIST_LOCK) {
            if (complete && COMPLETE_PERSISTED.get()) {
                return;
            }
            if (!complete && COMPLETE_PERSISTED.get()) {
                return;
            }

            Path outputDir = outputDirectory();
            List<TimelineRecord> records;
            synchronized (TIMELINE_LOCK) {
                records = new ArrayList<>(TIMELINE);
            }
            records.sort(Comparator.comparingLong(TimelineRecord::sequence));

            try {
                Files.createDirectories(outputDir);
                writeAtomic(outputDir.resolve("startup-timeline.jsonl"), timelineJsonLines(records));
                writeAtomic(outputDir.resolve("startup-summary.json"), summaryJson(records, complete));
                if (complete) {
                    writeAtomic(outputDir.resolve("startup-complete.marker"),
                            Instant.now() + System.lineSeparator());
                    COMPLETE_PERSISTED.set(true);
                }
            } catch (IOException exception) {
                System.err.println("[StartupProfiler] Failed to persist startup timeline: " + exception);
            }
        }
    }

    private static Path outputDirectory() {
        String configured = System.getProperty(OUTPUT_DIR_PROPERTY, ".");
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String timelineJsonLines(List<TimelineRecord> records) {
        StringBuilder result = new StringBuilder(records.size() * 180);
        for (TimelineRecord record : records) {
            result.append(record.toJson()).append('\n');
        }
        return result.toString();
    }

    private static String summaryJson(List<TimelineRecord> records, boolean complete) {
        Map<String, TimelineRecord> durations = new LinkedHashMap<>();
        for (TimelineRecord record : records) {
            if ("duration".equals(record.kind())) {
                durations.put(record.name(), record);
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("{\n");
        result.append("  \"complete\": ").append(complete).append(",\n");
        result.append("  \"jvmStartEpochMillis\": ").append(jvmStartEpochMillis).append(",\n");
        result.append("  \"mainStartEpochMillis\": ").append(mainStartEpochMillis).append(",\n");
        result.append("  \"mainEnteredAfterJvmStartMillis\": ")
                .append(Math.max(0L, mainStartEpochMillis - jvmStartEpochMillis)).append(",\n");
        result.append("  \"generatedAt\": ").append(quote(Instant.now().toString())).append(",\n");
        result.append("  \"optimizationDiagnostics\": {\n");
        result.append("    \"textureCache\": ")
                .append(optimizationDiagnosticsJson(
                        "TextureCacheDiagnostics"))
                .append(",\n");
        result.append("    \"pcmCache\": ")
                .append(optimizationDiagnosticsJson(
                        "PcmCacheDiagnostics"))
                .append(",\n");
        result.append("    \"startupLog\": ")
                .append(optimizationDiagnosticsJson(
                        "StartupLogDiagnostics"))
                .append(",\n");
        result.append("    \"glyphArrayGrowth\": ")
                .append(optimizationDiagnosticsJson(
                        "GlyphArrayGrowthDiagnostics"))
                .append(",\n");
        result.append("    \"preloadPathDedup\": ")
                .append(optimizationDiagnosticsJson(
                        "PreloadPathDedupDiagnostics"))
                .append(",\n");
        result.append("    \"janinoSourceIndex\": ")
                .append(optimizationDiagnosticsJson(
                        "JaninoSourceIndexDiagnostics"))
                .append(",\n");
        result.append("    \"janinoBytecodeCache\": ")
                .append(optimizationDiagnosticsJson(
                        "JaninoBytecodeCacheDiagnostics"))
                .append("\n");
        result.append("  },\n");
        result.append("  \"durations\": {\n");
        int index = 0;
        for (Map.Entry<String, TimelineRecord> entry : durations.entrySet()) {
            TimelineRecord record = entry.getValue();
            result.append("    ").append(quote(entry.getKey())).append(": {\n");
            result.append("      \"startSinceMainNanos\": ").append(record.startElapsedNanos()).append(",\n");
            result.append("      \"durationNanos\": ").append(record.durationNanos()).append(",\n");
            result.append("      \"durationMillis\": ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", record.durationNanos() / 1_000_000.0))
                    .append(",\n");
            result.append("      \"thread\": ").append(quote(record.thread())).append("\n");
            result.append("    }");
            result.append(++index == durations.size() ? "\n" : ",\n");
        }
        result.append("  }\n");
        result.append("}\n");
        return result.toString();
    }

    private static String optimizationDiagnosticsJson(
            String simpleClassName) {
        try {
            Class<?> diagnostics = Class.forName(
                    "org.fossic.starsector.optimization."
                            + simpleClassName);
            Object value = diagnostics.getMethod("json").invoke(null);
            if (value instanceof String json
                    && json.startsWith("{")
                    && json.endsWith("}")) {
                return json;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return "{}";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private record PhaseStart(long nanoTime, String thread) {
    }

    private record TimelineRecord(long sequence, String kind, String name, String thread,
                                  long epochMillis, long elapsedNanos,
                                  long startElapsedNanos, long durationNanos) {
        private String toJson() {
            return "{"
                    + "\"sequence\":" + sequence
                    + ",\"kind\":" + quote(kind)
                    + ",\"name\":" + quote(name)
                    + ",\"thread\":" + quote(thread)
                    + ",\"epochMillis\":" + epochMillis
                    + ",\"elapsedSinceMainNanos\":" + elapsedNanos
                    + ",\"startSinceMainNanos\":" + startElapsedNanos
                    + ",\"durationNanos\":" + durationNanos
                    + "}";
        }
    }
}

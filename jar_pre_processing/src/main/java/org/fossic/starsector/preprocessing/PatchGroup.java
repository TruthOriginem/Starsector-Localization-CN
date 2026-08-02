package org.fossic.starsector.preprocessing;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 可独立选择的 ASM patch 功能组。
 *
 * <p>同组内的 patch 必须原子启停。例如资源 leaf 与上层短锁共同构成完整的同步语义。
 */
public enum PatchGroup {
    LOCALIZATION("localization", false),
    IME("ime", false),
    DYNFONT("dynfont", false),
    GUI_CONSOLE_LOG("gui-console-log", true),
    FONT_GLYPH_COPY("font-glyph-copy", true),
    FONT_LINE_PARSER("font-line-parser", true),
    FONT_TOKEN_CURSOR("font-token-cursor", true),
    FAST_TEXT("fast-text", true),
    RESOURCE_LOCKS("resource-locks", true),
    RESOURCE_STREAM_SAFETY("resource-stream-safety", true),
    FAST_PNG("fast-png", true),
    TEXTURE_CACHE("texture-cache", true),
    CSV_ERROR_FORMATTING("csv-error-formatting", true),
    CSV_MERGE_LINEAR("csv-merge-linear", true),
    PARALLEL_SPEC_PARSE("parallel-spec-parse", true),
    RULES_ID_INDEX("rules-id-index", true),
    TEXTURE_PIPELINE("texture-pipeline", true),
    PCM_BUFFER("pcm-buffer", true),
    PCM_BULK_READ("pcm-bulk-read", true),
    PCM_CACHE("pcm-cache", true),
    SOUND_DECODE_WORKERS("sound-decode-workers", true),
    RESOURCE_PARTITION("resource-partition", true),
    PRELOAD_COORDINATION("preload-coordination", true),
    PRELOAD_PATH_DEDUP("preload-path-dedup", true),
    PARALLEL_IMAGE_PRELOAD("parallel-image-preload", true),
    JANINO_CU_DEDUP("janino-cu-dedup", true),
    JANINO_SOURCE_INDEX("janino-source-index", true),
    JANINO_BYTECODE_CACHE("janino-bytecode-cache", true),
    PROFILING("profiling", false);

    private final String id;
    private final boolean optimization;

    PatchGroup(String id, boolean optimization) {
        this.id = id;
        this.optimization = optimization;
    }

    public String id() {
        return id;
    }

    public boolean optimization() {
        return optimization;
    }

    public Set<PatchGroup> dependencies() {
        return switch (this) {
            case PCM_BULK_READ -> Set.of(PCM_BUFFER);
            case PCM_CACHE -> Set.of(PCM_BULK_READ);
            case FONT_TOKEN_CURSOR -> Set.of(FONT_LINE_PARSER);
            case PARALLEL_SPEC_PARSE -> Set.of(RESOURCE_LOCKS);
            case PRELOAD_PATH_DEDUP, PARALLEL_IMAGE_PRELOAD ->
                    Set.of(PRELOAD_COORDINATION);
            case TEXTURE_CACHE ->
                    Set.of(FAST_PNG, TEXTURE_PIPELINE);
            case JANINO_SOURCE_INDEX -> Set.of(JANINO_CU_DEDUP);
            case JANINO_BYTECODE_CACHE -> Set.of(JANINO_SOURCE_INDEX);
            default -> Set.of();
        };
    }

    public static List<PatchGroup> optimizationGroups() {
        return Arrays.stream(values())
                .filter(PatchGroup::optimization)
                .toList();
    }

    public static PatchGroup fromId(String id) {
        return Arrays.stream(values())
                .filter(group -> group.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知 patch 组 '" + id + "'；可用组: "
                                + String.join(", ", allIds())));
    }

    public static PatchGroup fromOptimizationId(String id) {
        PatchGroup group = fromId(id);
        if (!group.optimization) {
            throw new IllegalArgumentException(
                    "'" + id + "' 不是优化组；可用优化组: "
                            + String.join(", ", optimizationIds()));
        }
        return group;
    }

    public static List<String> allIds() {
        return Arrays.stream(values()).map(PatchGroup::id).toList();
    }

    public static List<String> optimizationIds() {
        return optimizationGroups().stream().map(PatchGroup::id).toList();
    }
}

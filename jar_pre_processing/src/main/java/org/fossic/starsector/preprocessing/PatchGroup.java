package org.fossic.starsector.preprocessing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A stable, independently selectable group of cooperating ASM patches. */
public enum PatchGroup {
    LOCALIZATION("localization", PatchGroupKind.BASELINE),
    IME("ime", PatchGroupKind.BASELINE),
    DYNFONT("dynfont", PatchGroupKind.BASELINE),
    GUI_CONSOLE_LOG("gui-console-log", PatchGroupKind.OPTIMIZATION),
    FONT_GLYPH_COPY("font-glyph-copy", PatchGroupKind.OPTIMIZATION),
    FONT_LINE_PARSER("font-line-parser", PatchGroupKind.OPTIMIZATION),
    FONT_TOKEN_CURSOR("font-token-cursor", PatchGroupKind.OPTIMIZATION),
    FAST_TEXT("fast-text", PatchGroupKind.OPTIMIZATION),
    RESOURCE_LOCKS("resource-locks", PatchGroupKind.OPTIMIZATION),
    RESOURCE_STREAM_SAFETY(
            "resource-stream-safety", PatchGroupKind.OPTIMIZATION),
    FAST_PNG("fast-png", PatchGroupKind.OPTIMIZATION),
    TEXTURE_CACHE("texture-cache", PatchGroupKind.OPTIMIZATION),
    CSV_ERROR_FORMATTING(
            "csv-error-formatting", PatchGroupKind.OPTIMIZATION),
    CSV_MERGE_LINEAR("csv-merge-linear", PatchGroupKind.OPTIMIZATION),
    PARALLEL_SPEC_PARSE(
            "parallel-spec-parse", PatchGroupKind.OPTIMIZATION),
    RULES_ID_INDEX("rules-id-index", PatchGroupKind.OPTIMIZATION),
    TEXTURE_PIPELINE("texture-pipeline", PatchGroupKind.OPTIMIZATION),
    PCM_BUFFER("pcm-buffer", PatchGroupKind.OPTIMIZATION),
    PCM_BULK_READ("pcm-bulk-read", PatchGroupKind.OPTIMIZATION),
    PCM_CACHE("pcm-cache", PatchGroupKind.OPTIMIZATION),
    SOUND_DECODE_WORKERS(
            "sound-decode-workers", PatchGroupKind.OPTIMIZATION),
    RESOURCE_PARTITION("resource-partition", PatchGroupKind.OPTIMIZATION),
    PRELOAD_COORDINATION(
            "preload-coordination", PatchGroupKind.OPTIMIZATION),
    PRELOAD_PATH_DEDUP(
            "preload-path-dedup", PatchGroupKind.OPTIMIZATION),
    PARALLEL_IMAGE_PRELOAD(
            "parallel-image-preload", PatchGroupKind.OPTIMIZATION),
    JANINO_CU_DEDUP("janino-cu-dedup", PatchGroupKind.OPTIMIZATION),
    JANINO_SOURCE_INDEX("janino-source-index", PatchGroupKind.OPTIMIZATION),
    JANINO_BYTECODE_CACHE(
            "janino-bytecode-cache", PatchGroupKind.OPTIMIZATION),
    CACHE_MAINTENANCE("cache-maintenance", PatchGroupKind.OPTIMIZATION),
    PROFILING("profiling", PatchGroupKind.PROFILING);

    private final String id;
    private final PatchGroupKind kind;

    PatchGroup(String id, PatchGroupKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public String id() {
        return id;
    }

    public PatchGroupKind kind() {
        return kind;
    }

    /** Returns direct dependencies only. Selection validates but never expands them. */
    public Set<PatchGroup> dependencies() {
        return switch (this) {
            case FONT_TOKEN_CURSOR -> Set.of(FONT_LINE_PARSER);
            case PARALLEL_SPEC_PARSE -> Set.of(RESOURCE_LOCKS);
            case TEXTURE_CACHE -> Set.of(
                    FAST_PNG, TEXTURE_PIPELINE, CACHE_MAINTENANCE);
            case PCM_BULK_READ -> Set.of(PCM_BUFFER);
            case PCM_CACHE -> Set.of(
                    PCM_BULK_READ, CACHE_MAINTENANCE);
            case PRELOAD_PATH_DEDUP, PARALLEL_IMAGE_PRELOAD ->
                    Set.of(PRELOAD_COORDINATION);
            case JANINO_SOURCE_INDEX -> Set.of(JANINO_CU_DEDUP);
            case JANINO_BYTECODE_CACHE -> Set.of(
                    JANINO_SOURCE_INDEX, CACHE_MAINTENANCE);
            default -> Set.of();
        };
    }

    public static List<PatchGroup> baselineGroups() {
        return groupsOfKind(PatchGroupKind.BASELINE);
    }

    public static List<PatchGroup> optimizationGroups() {
        return groupsOfKind(PatchGroupKind.OPTIMIZATION);
    }

    public static List<PatchGroup> profilingGroups() {
        return groupsOfKind(PatchGroupKind.PROFILING);
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
        if (group.kind != PatchGroupKind.OPTIMIZATION) {
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

    /** Validates enum metadata once at each selection boundary. */
    public static void validateDefinitions() {
        Set<String> ids = new HashSet<>();
        for (PatchGroup group : values()) {
            if (!ids.add(group.id)) {
                throw new IllegalStateException("重复 patch 组 ID: " + group.id);
            }
        }

        EnumSet<PatchGroup> visited = EnumSet.noneOf(PatchGroup.class);
        EnumSet<PatchGroup> visiting = EnumSet.noneOf(PatchGroup.class);
        ArrayList<PatchGroup> path = new ArrayList<>();
        for (PatchGroup group : values()) {
            validateAcyclic(group, visited, visiting, path);
        }
    }

    private static List<PatchGroup> groupsOfKind(PatchGroupKind kind) {
        return Arrays.stream(values())
                .filter(group -> group.kind == kind)
                .toList();
    }

    private static void validateAcyclic(
            PatchGroup group,
            Set<PatchGroup> visited,
            Set<PatchGroup> visiting,
            List<PatchGroup> path) {
        if (visited.contains(group)) {
            return;
        }
        if (!visiting.add(group)) {
            int start = path.indexOf(group);
            List<String> cycle = new ArrayList<>();
            for (int i = start; i < path.size(); i++) {
                cycle.add(path.get(i).id);
            }
            cycle.add(group.id);
            throw new IllegalStateException(
                    "patch 组依赖存在环: " + String.join(" -> ", cycle));
        }

        path.add(group);
        for (PatchGroup dependency : ordered(group.dependencies())) {
            validateAcyclic(dependency, visited, visiting, path);
        }
        path.remove(path.size() - 1);
        visiting.remove(group);
        visited.add(group);
    }

    static List<PatchGroup> ordered(Set<PatchGroup> groups) {
        return groups.stream()
                .sorted((left, right) ->
                        Integer.compare(left.ordinal(), right.ordinal()))
                .toList();
    }
}

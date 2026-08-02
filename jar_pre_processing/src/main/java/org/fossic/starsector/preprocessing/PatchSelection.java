package org.fossic.starsector.preprocessing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 解析构建期 patch 组选项，并完成依赖加入与反向禁用。 */
public final class PatchSelection {
    public static final String OPTIMIZATIONS_PROPERTY =
            "starsector.preprocess.optimizations";
    public static final String DISABLED_GROUPS_PROPERTY =
            "starsector.preprocess.disabledPatchGroups";
    public static final String PROFILING_PROPERTY =
            "starsector.preprocess.profiling";

    private final String requestedOptimizationSpec;
    private final boolean requestedProfiling;
    private final Set<PatchGroup> disabledGroups;
    private final Set<PatchGroup> enabledGroups;

    private PatchSelection(
            String requestedOptimizationSpec,
            boolean requestedProfiling,
            Set<PatchGroup> disabledGroups,
            Set<PatchGroup> enabledGroups) {
        this.requestedOptimizationSpec = requestedOptimizationSpec;
        this.requestedProfiling = requestedProfiling;
        this.disabledGroups = EnumSet.copyOf(disabledGroups);
        this.enabledGroups = EnumSet.copyOf(enabledGroups);
    }

    public static PatchSelection defaults() {
        return fromOptions("all", List.of(), false);
    }

    public static PatchSelection fromSystemProperties() {
        String optimizationSpec = System.getProperty(
                OPTIMIZATIONS_PROPERTY, "all");
        List<String> disabled = splitList(System.getProperty(
                DISABLED_GROUPS_PROPERTY, ""));
        boolean profiling = parseBooleanProperty(
                PROFILING_PROPERTY,
                System.getProperty(PROFILING_PROPERTY, "false"));
        return fromOptions(optimizationSpec, disabled, profiling);
    }

    public static PatchSelection fromOptions(
            String optimizationSpec,
            Collection<String> disabledGroupIds,
            boolean profiling) {
        String normalizedSpec = normalizeSpec(optimizationSpec);
        EnumSet<PatchGroup> enabled = EnumSet.of(
                PatchGroup.LOCALIZATION,
                PatchGroup.IME,
                PatchGroup.DYNFONT);
        enabled.addAll(parseOptimizations(normalizedSpec));
        if (profiling) {
            enabled.add(PatchGroup.PROFILING);
        }
        addDependencies(enabled);

        EnumSet<PatchGroup> disabled = EnumSet.noneOf(PatchGroup.class);
        for (String id : disabledGroupIds) {
            String normalized = normalizeId(id);
            if (!normalized.isEmpty()) {
                disabled.add(PatchGroup.fromId(normalized));
            }
        }
        enabled.removeAll(disabled);
        removeGroupsWithDisabledDependencies(enabled);

        return new PatchSelection(
                normalizedSpec, profiling, disabled, enabled);
    }

    public boolean enabled(PatchGroup group) {
        return enabledGroups.contains(group);
    }

    public String requestedOptimizationSpec() {
        return requestedOptimizationSpec;
    }

    public boolean requestedProfiling() {
        return requestedProfiling;
    }

    public List<String> enabledOptimizationIds() {
        return PatchGroup.optimizationGroups().stream()
                .filter(enabledGroups::contains)
                .map(PatchGroup::id)
                .toList();
    }

    public List<String> enabledGroupIds() {
        return List.of(PatchGroup.values()).stream()
                .filter(enabledGroups::contains)
                .map(PatchGroup::id)
                .toList();
    }

    public List<String> disabledGroupIds() {
        return List.of(PatchGroup.values()).stream()
                .filter(disabledGroups::contains)
                .map(PatchGroup::id)
                .toList();
    }

    private static EnumSet<PatchGroup> parseOptimizations(String spec) {
        if ("all".equals(spec)) {
            return EnumSet.copyOf(PatchGroup.optimizationGroups());
        }
        if ("none".equals(spec)) {
            return EnumSet.noneOf(PatchGroup.class);
        }

        EnumSet<PatchGroup> result = EnumSet.noneOf(PatchGroup.class);
        for (String id : splitList(spec)) {
            if ("all".equals(id) || "none".equals(id)) {
                throw new IllegalArgumentException(
                        "all/none 不能与具体优化组混用: " + spec);
            }
            result.add(PatchGroup.fromOptimizationId(id));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "优化组列表不能为空；请使用 all 或 none");
        }
        return result;
    }

    private static void addDependencies(Set<PatchGroup> enabled) {
        boolean changed;
        do {
            changed = false;
            for (PatchGroup group : List.copyOf(enabled)) {
                changed |= enabled.addAll(group.dependencies());
            }
        } while (changed);
    }

    private static void removeGroupsWithDisabledDependencies(
            Set<PatchGroup> enabled) {
        boolean changed;
        do {
            changed = false;
            for (PatchGroup group : List.copyOf(enabled)) {
                if (!enabled.containsAll(group.dependencies())) {
                    enabled.remove(group);
                    changed = true;
                }
            }
        } while (changed);
    }

    private static String normalizeSpec(String value) {
        if (value == null) {
            throw new IllegalArgumentException("optimizations 不能为 null");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("patch 组名不能为 null");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> splitList(String value) {
        ArrayList<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = normalizeId(item);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean parseBooleanProperty(String name, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "系统属性 " + name + " 必须为 true 或 false，实际为: " + value);
    }
}

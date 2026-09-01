package org.fossic.starsector.preprocessing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses patch-group options and strictly validates, but never changes, dependencies. */
public final class PatchSelection {
    public static final String OPTIMIZATIONS_PROPERTY =
            "starsector.preprocess.optimizations";
    public static final String DISABLED_GROUPS_PROPERTY =
            "starsector.preprocess.disabledPatchGroups";
    public static final String PROFILING_PROPERTY =
            "starsector.preprocess.profiling";

    private final String requestedOptimizationSpec;
    private final boolean requestedProfiling;
    private final Set<PatchGroup> requestedDisabledGroups;
    private final Set<PatchGroup> enabledGroups;

    private PatchSelection(
            String requestedOptimizationSpec,
            boolean requestedProfiling,
            Set<PatchGroup> requestedDisabledGroups,
            Set<PatchGroup> enabledGroups) {
        this.requestedOptimizationSpec = requestedOptimizationSpec;
        this.requestedProfiling = requestedProfiling;
        this.requestedDisabledGroups = copyOf(requestedDisabledGroups);
        this.enabledGroups = copyOf(enabledGroups);
    }

    public static PatchSelection defaults() {
        return fromOptions(defaultOptimizationSpec(), List.of(), false);
    }

    public static PatchSelection fromSystemProperties() {
        String optimizationSpec = System.getProperty(
                OPTIMIZATIONS_PROPERTY, defaultOptimizationSpec());
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
        PatchGroup.validateDefinitions();
        String normalizedSpec = normalizeSpec(optimizationSpec);

        EnumSet<PatchGroup> requested = EnumSet.noneOf(PatchGroup.class);
        requested.addAll(PatchGroup.baselineGroups());
        requested.addAll(parseOptimizations(normalizedSpec));
        if (profiling) {
            List<PatchGroup> profilingGroups = PatchGroup.profilingGroups();
            if (profilingGroups.isEmpty()) {
                throw new IllegalArgumentException(
                        "当前产品未定义 profiling patch 组");
            }
            requested.addAll(profilingGroups);
        }

        EnumSet<PatchGroup> disabled = parseDisabledGroups(disabledGroupIds);
        List<String> problems = new ArrayList<>();
        for (PatchGroup group : PatchGroup.values()) {
            if (disabled.contains(group) && !requested.contains(group)) {
                problems.add("patch 组 '" + group.id()
                        + "' 未处于请求启用集合中，不能显式禁用");
            }
        }

        EnumSet<PatchGroup> enabled = copyOf(requested);
        enabled.removeAll(disabled);
        problems.addAll(dependencyProblems(enabled, disabled));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "patch 组配置无效:\n  " + String.join("\n  ", problems));
        }

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

    public List<String> requestedDisabledGroupIds() {
        return PatchGroup.ordered(requestedDisabledGroups).stream()
                .map(PatchGroup::id)
                .toList();
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

    private static String defaultOptimizationSpec() {
        return PatchGroup.optimizationGroups().isEmpty() ? "none" : "all";
    }

    private static EnumSet<PatchGroup> parseOptimizations(String spec) {
        List<PatchGroup> available = PatchGroup.optimizationGroups();
        if ("all".equals(spec)) {
            if (available.isEmpty()) {
                throw new IllegalArgumentException(
                        "当前产品未定义可启用的优化组；请使用 none");
            }
            EnumSet<PatchGroup> result = EnumSet.noneOf(PatchGroup.class);
            result.addAll(available);
            return result;
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
            PatchGroup group = PatchGroup.fromOptimizationId(id);
            if (!result.add(group)) {
                throw new IllegalArgumentException("重复优化组: " + id);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "优化组列表不能为空；请使用 all 或 none");
        }
        return result;
    }

    private static EnumSet<PatchGroup> parseDisabledGroups(
            Collection<String> disabledGroupIds) {
        if (disabledGroupIds == null) {
            throw new IllegalArgumentException("禁用 patch 组列表不能为 null");
        }
        EnumSet<PatchGroup> result = EnumSet.noneOf(PatchGroup.class);
        for (String value : disabledGroupIds) {
            for (String id : splitList(value)) {
                PatchGroup group = PatchGroup.fromId(id);
                if (!result.add(group)) {
                    throw new IllegalArgumentException("重复禁用 patch 组: " + id);
                }
            }
        }
        return result;
    }

    private static List<String> dependencyProblems(
            Set<PatchGroup> enabled,
            Set<PatchGroup> disabled) {
        LinkedHashSet<String> problems = new LinkedHashSet<>();
        for (PatchGroup root : PatchGroup.values()) {
            if (!enabled.contains(root)) {
                continue;
            }
            ArrayList<PatchGroup> path = new ArrayList<>();
            path.add(root);
            collectDependencyProblems(
                    root, enabled, disabled, path, problems);
        }
        return List.copyOf(problems);
    }

    private static void collectDependencyProblems(
            PatchGroup current,
            Set<PatchGroup> enabled,
            Set<PatchGroup> disabled,
            List<PatchGroup> path,
            Set<String> problems) {
        for (PatchGroup dependency : PatchGroup.ordered(current.dependencies())) {
            path.add(dependency);
            if (!enabled.contains(dependency)) {
                String reason = disabled.contains(dependency)
                        ? "被显式禁用"
                        : "未启用";
                problems.add(formatPath(path) + "：" + reason);
            }
            collectDependencyProblems(
                    dependency, enabled, disabled, path, problems);
            path.remove(path.size() - 1);
        }
    }

    private static String formatPath(List<PatchGroup> path) {
        return String.join(" -> ", path.stream()
                .map(PatchGroup::id)
                .toList());
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
        for (String item : normalizeId(value).split(",")) {
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

    private static EnumSet<PatchGroup> copyOf(Collection<PatchGroup> groups) {
        EnumSet<PatchGroup> result = EnumSet.noneOf(PatchGroup.class);
        result.addAll(groups);
        return result;
    }
}

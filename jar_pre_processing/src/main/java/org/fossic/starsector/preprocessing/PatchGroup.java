package org.fossic.starsector.preprocessing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A stable, independently selectable group of cooperating ASM patches. */
public enum PatchGroup {
    LOCALIZATION("localization", PatchGroupKind.BASELINE);

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
        return Set.of();
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
                .sorted((left, right) -> Integer.compare(left.ordinal(), right.ordinal()))
                .toList();
    }
}

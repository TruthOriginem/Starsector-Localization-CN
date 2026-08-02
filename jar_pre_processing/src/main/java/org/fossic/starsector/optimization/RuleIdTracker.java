package org.fossic.starsector.optimization;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为一次 Rules 加载维护 trigger -> rule id 集合。
 *
 * <p>{@link #candidates(String, String, Object)} 返回值刻意适配原版的 for-each 重复检查：
 * 首次出现返回空列表，重复时返回只包含当前 rule 的列表，使原版循环继续生成完全相同的
 * 首个重复错误。正式 rule 列表仍由游戏按原顺序维护。
 */
public final class RuleIdTracker {
    private static final ThreadLocal<Map<String, Set<String>>> IDS_BY_TRIGGER =
            new ThreadLocal<>();

    private RuleIdTracker() {
    }

    public static void reset() {
        IDS_BY_TRIGGER.set(new HashMap<>());
    }

    public static List<Object> candidates(
            String trigger, String id, Object currentRule) {
        Map<String, Set<String>> idsByTrigger = IDS_BY_TRIGGER.get();
        if (idsByTrigger == null) {
            throw new IllegalStateException("RuleIdTracker.reset() was not called");
        }
        Set<String> ids = idsByTrigger.computeIfAbsent(
                trigger, ignored -> new HashSet<>());
        if (ids.add(id)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(currentRule);
    }

    public static void finish() {
        IDS_BY_TRIGGER.remove();
    }
}

package org.fossic.starsector.optimization;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 对已经由原列表稳定筛选出的对象引用子序列执行线性稳定分区。
 *
 * <p>游戏的资源优先列表是单次遍历完整资源列表、直接加入原对象引用得到的，因此它一定是
 * 原列表的身份子序列。利用这个约束可以同时线性扫描两个列表，避免
 * {@code ArrayList.removeAll()} 对每个资源反复调用优先列表的线性 {@code contains()}。
 */
public final class StableListPartition {
    private StableListPartition() {
    }

    /**
     * 把 {@code prioritized} 移到 {@code values} 前部，同时保留优先项和其余项各自的顺序。
     *
     * <p>两个参数必须是不同的列表对象，且 {@code prioritized} 中的每一项必须按相同顺序
     * 引用 {@code values} 中的同一个对象。方法先验证完整子序列，只有验证通过后才修改
     * {@code values}。
     */
    public static <T> void prioritizedSubsequenceFirst(
            List<T> values, List<? extends T> prioritized) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(prioritized, "prioritized");
        if (values == prioritized) {
            throw new IllegalArgumentException(
                    "prioritized must be a separate list");
        }
        if (prioritized.isEmpty()) {
            return;
        }

        ArrayList<T> remaining = new ArrayList<>(
                Math.max(0, values.size() - prioritized.size()));
        Iterator<? extends T> prioritizedIterator =
                prioritized.iterator();
        T nextPrioritized = prioritizedIterator.next();
        boolean hasNextPrioritized = true;

        for (T value : values) {
            if (hasNextPrioritized && value == nextPrioritized) {
                if (prioritizedIterator.hasNext()) {
                    nextPrioritized = prioritizedIterator.next();
                } else {
                    hasNextPrioritized = false;
                }
            } else {
                remaining.add(value);
            }
        }

        if (hasNextPrioritized) {
            throw new IllegalArgumentException(
                    "prioritized is not an identity subsequence of values");
        }

        values.clear();
        values.addAll(prioritized);
        values.addAll(remaining);
    }
}

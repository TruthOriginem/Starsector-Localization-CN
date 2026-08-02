package org.fossic.starsector.optimization;

import java.util.Arrays;

/** 用 JVM 批量数组复制替代原版字体字形数组的 Java 逐元素扩容循环。 */
public final class GlyphArrayGrowth {
    private static final int ORIGINAL_SPARE_CAPACITY = 100;

    private GlyphArrayGrowth() {
    }

    /**
     * 保持原版的精确容量规则：只有索引越界时才扩到 {@code glyphId + 100}。
     *
     * <p>{@link Arrays#copyOf(Object[], int)} 保留数组的运行时组件类型，并通过 JVM 的
     * 批量复制路径搬运相同的旧元素。负索引、null 和整数溢出的异常行为由随后原版逻辑或
     * JDK 保持，不在这里修正。
     */
    public static Object[] ensureCapacity(
            Object[] current, int glyphId) {
        if (glyphId < current.length) {
            return current;
        }
        Object[] grown = Arrays.copyOf(
                current, glyphId + ORIGINAL_SPARE_CAPACITY);
        GlyphArrayGrowthDiagnostics.recordGrowth(current.length);
        return grown;
    }

    static void resetForTests() {
        GlyphArrayGrowthDiagnostics.resetForTests();
    }
}

package org.fossic.starsector.optimization;

import java.util.concurrent.atomic.LongAdder;

/** 记录字体字形数组批量扩容次数与避免的逐元素复制量。 */
public final class GlyphArrayGrowthDiagnostics {
    private static final LongAdder GROWTHS = new LongAdder();
    private static final LongAdder COPIED_ELEMENTS = new LongAdder();

    private GlyphArrayGrowthDiagnostics() {
    }

    static void recordGrowth(int copiedElements) {
        GROWTHS.increment();
        COPIED_ELEMENTS.add(copiedElements);
    }

    public static String json() {
        return "{"
                + "\"growths\":" + GROWTHS.sum()
                + ",\"copiedElements\":" + COPIED_ELEMENTS.sum()
                + "}";
    }

    static void resetForTests() {
        GROWTHS.reset();
        COPIED_ELEMENTS.reset();
    }
}

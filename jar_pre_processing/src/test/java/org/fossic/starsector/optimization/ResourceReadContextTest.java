package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

final class ResourceReadContextTest {
    @Test
    void ordinaryReadsDoNotAllocateThreadLocalEntriesRepeatedly() {
        var context = new ResourceReadContext();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 10000; i++) ordinaryRead(context);
        long threadId = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 100000; i++) ordinaryRead(context);
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        assertTrue(allocated < 16384, "ordinary context reads allocated " + allocated + " bytes");
    }

    private static void ordinaryRead(ResourceReadContext context) {
        if (context.selector() != null || ResourceReadContext.consumeSkipMods(false)) {
            throw new AssertionError("unexpected context");
        }
        context.select(null);
    }
}

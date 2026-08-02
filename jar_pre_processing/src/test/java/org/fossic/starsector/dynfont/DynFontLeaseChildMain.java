package org.fossic.starsector.dynfont;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 独立 JVM 缓存认领进程；用于验证生命周期文件锁与剪枝互斥。 */
public final class DynFontLeaseChildMain {
    private DynFontLeaseChildMain() {
    }

    public static void main(String[] args) throws Exception {
        Path cacheRoot = Path.of(args[0]);
        Path target = Path.of(args[1]);
        String fingerprint = args[2];
        double scale = Double.parseDouble(args[3]);
        Path readySignal = Path.of(args[4]);
        Path stopSignal = Path.of(args[5]);
        if (!DynFontOverrides.claimCompleteCacheForTests(
                cacheRoot, target, fingerprint, scale)) {
            throw new IllegalStateException("child could not claim cache " + target);
        }
        Files.write(readySignal, new byte[]{1});
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(stopSignal)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("lease stop signal timed out");
            }
            Thread.sleep(5L);
        }
    }
}

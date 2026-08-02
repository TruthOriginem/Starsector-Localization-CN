package org.fossic.starsector.dynfont;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 独立 JVM 字体缓存发布进程；由 DynFontOverridesTest 启动。 */
public final class DynFontPublishChildMain {
    private DynFontPublishChildMain() {
    }

    public static void main(String[] args) throws Exception {
        Path cacheRoot = Path.of(args[0]);
        Path source = Path.of(args[1]);
        Path target = Path.of(args[2]);
        String fingerprint = args[3];
        double scale = Double.parseDouble(args[4]);
        Path startSignal = Path.of(args[5]);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(startSignal)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("publish start signal timed out");
            }
            Thread.sleep(5L);
        }
        DynFontOverrides.publishGeneratedDirectory(
                cacheRoot, source, target, fingerprint, scale);
    }
}

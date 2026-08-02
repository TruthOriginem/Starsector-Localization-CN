package org.fossic.starsector.optimization;

import org.codehaus.janino.JavaSourceClassLoader;

/** ScriptStore worker 成功 join 后发布缓存；所有缓存故障均与游戏启动隔离。 */
public final class JaninoBytecodeCacheHooks {
    private JaninoBytecodeCacheHooks() {
    }

    public static void finish(
            JavaSourceClassLoader loader, Throwable workerFailure) {
        if (!(loader
                instanceof CachingIndexedDeduplicatingJavaSourceClassLoader
                        caching)) {
            return;
        }
        try {
            if (workerFailure == null) {
                caching.finishSuccessfulSession();
            } else {
                caching.abortCache();
            }
        } catch (Exception | LinkageError ignored) {
            // 缓存绝不能改变 ScriptStore 原有成功/失败语义。
        }
    }
}

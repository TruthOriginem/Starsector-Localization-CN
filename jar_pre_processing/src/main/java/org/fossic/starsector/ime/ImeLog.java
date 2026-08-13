package org.fossic.starsector.ime;

import java.lang.reflect.Method;

/**
 * 输入法支持模块的日志封装。
 *
 * <p>只记录错误。优先使用游戏自带的 log4j 1.2（日志进入
 * {@code starsector.log}）；若 log4j 不可用则回退到标准错误。所有方法均不抛异常。
 */
final class ImeLog {
    private static final String PREFIX = "[SS-IME] ";

    /** log4j 反射句柄；不可用时为 null，日志回退到标准输出。 */
    private static final Log4j LOG4J = Log4j.create();

    private ImeLog() {
    }

    static void error(String message, Throwable error) {
        if (LOG4J != null) {
            try {
                LOG4J.error.invoke(LOG4J.logger, PREFIX + message, error);
                return;
            } catch (Throwable ignored) {
                // 回退到标准错误
            }
        }
        System.err.println(PREFIX + message);
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }

    /** 不可变的 log4j 反射句柄组。 */
    private static final class Log4j {
        final Object logger;
        final Method error;

        private Log4j(Object logger, Method error) {
            this.logger = logger;
            this.error = error;
        }

        static Log4j create() {
            try {
                Class<?> loggerClass = Class.forName("org.apache.log4j.Logger");
                Object logger = loggerClass.getMethod("getLogger", String.class)
                        .invoke(null, "org.fossic.starsector.ime");
                Method error = loggerClass.getMethod("error", Object.class, Throwable.class);
                return new Log4j(logger, error);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}

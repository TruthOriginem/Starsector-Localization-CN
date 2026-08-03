package org.fossic.starsector.dynfont;

import java.lang.reflect.Method;

/**
 * 动态字体模块的日志封装。
 *
 * <p>优先使用游戏自带的 log4j 1.2（日志进入 {@code starsector.log}，玩家易于查看
 * 与反馈，可用 {@code findstr "[SS-DYNFONT]" starsector.log} 检索）；log4j 不可用时
 * 回退到标准输出。所有方法均不抛异常。
 */
final class DynFontLog {
    private static final String PREFIX = "[SS-DYNFONT] ";

    /** log4j 反射句柄；不可用时为 null，日志回退到标准输出。 */
    private static final Log4j LOG4J = Log4j.create();

    private DynFontLog() {
    }

    static void info(String message) {
        log(message, null);
    }

    static void error(String message, Throwable error) {
        log(message, error);
    }

    private static void log(String message, Throwable error) {
        if (LOG4J != null) {
            try {
                if (error == null) {
                    LOG4J.info.invoke(LOG4J.logger, PREFIX + message);
                } else {
                    LOG4J.error.invoke(LOG4J.logger, PREFIX + message, error);
                }
                return;
            } catch (Throwable ignored) {
                // 回退到标准输出
            }
        }
        System.out.println(PREFIX + message);
        if (error != null) {
            error.printStackTrace(System.out);
        }
    }

    /** 不可变的 log4j 反射句柄组。 */
    private static final class Log4j {
        final Object logger;
        final Method info;
        final Method error;

        private Log4j(Object logger, Method info, Method error) {
            this.logger = logger;
            this.info = info;
            this.error = error;
        }

        static Log4j create() {
            try {
                Class<?> loggerClass = Class.forName("org.apache.log4j.Logger");
                Object logger = loggerClass.getMethod("getLogger", String.class)
                        .invoke(null, "org.fossic.starsector.dynfont");
                Method info = loggerClass.getMethod("info", Object.class);
                Method error = loggerClass.getMethod("error", Object.class, Throwable.class);
                return new Log4j(logger, info, error);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}

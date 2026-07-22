package org.fossic.starsector.ime;

import java.lang.reflect.Method;

/**
 * 输入法支持模块的日志封装。
 *
 * <p>优先使用游戏自带的 log4j 1.2（日志进入 {@code starsector.log}，玩家易于查看
 * 与反馈）；若 log4j 不可用则回退到标准输出。所有方法均不抛异常。
 */
final class ImeLog {
    private static final String PREFIX = "[SS-IME] ";
    /** 调试日志统一前缀，便于在 starsector.log 中检索（如 findstr "[SS-IME][DEBUG]"）。 */
    private static final String DEBUG_PREFIX = "[SS-IME][DEBUG] ";

    /** log4j 反射句柄；不可用时为 null，日志回退到标准输出。 */
    private static final Log4j LOG4J = Log4j.create();

    private ImeLog() {
    }

    static void info(String message) {
        log(PREFIX, message, null);
    }

    /**
     * 交互级调试日志（焦点切换、上屏注入、候选窗定位等高频事件）。
     *
     * <p>仍以 info 级别输出（log4j 默认配置会过滤 debug 级别），
     * 用 {@code [SS-IME][DEBUG]} 前缀与常规日志区分，便于统一检索或后续移除。
     */
    static void debug(String message) {
        log(DEBUG_PREFIX, message, null);
    }

    static void error(String message, Throwable error) {
        log(PREFIX, message, error);
    }

    private static void log(String prefix, String message, Throwable error) {
        if (LOG4J != null) {
            try {
                if (error == null) {
                    LOG4J.info.invoke(LOG4J.logger, prefix + message);
                } else {
                    LOG4J.error.invoke(LOG4J.logger, prefix + message, error);
                }
                return;
            } catch (Throwable ignored) {
                // 回退到标准输出
            }
        }
        System.out.println(prefix + message);
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
                        .invoke(null, "org.fossic.starsector.ime");
                Method info = loggerClass.getMethod("info", Object.class);
                Method error = loggerClass.getMethod("error", Object.class, Throwable.class);
                return new Log4j(logger, info, error);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}

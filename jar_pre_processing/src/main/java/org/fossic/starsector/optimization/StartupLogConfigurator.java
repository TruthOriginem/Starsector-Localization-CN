package org.fossic.starsector.optimization;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * GUI 启动没有可见控制台时，移除 log4j 配置中重复的 ConsoleAppender。
 *
 * <p>文件 appender、logger 级别和消息构造均不修改，starsector.log 因而仍保留完整诊断。
 * 只有根 logger 中名字精确为 {@code ConsoleAppender}、实现类也精确为 log4j 标准
 * {@code ConsoleAppender} 的实例会被移除。检测到真实 {@link System#console()}、显式保留
 * 属性或任何反射不兼容时均保持原配置。
 */
public final class StartupLogConfigurator {
    public static final String KEEP_CONSOLE_PROPERTY =
            "starsector.optimization.keepConsoleLogging";
    private static final String LOGGER_CLASS = "org.apache.log4j.Logger";
    private static final String APPENDER_CLASS = "org.apache.log4j.Appender";
    private static final String CONSOLE_APPENDER_CLASS =
            "org.apache.log4j.ConsoleAppender";
    private static final String APPENDER_NAME = "ConsoleAppender";

    private StartupLogConfigurator() {
    }

    /** 由 {@code CombatMain.main} 入口的 ASM bridge 调用。 */
    public static void configure() {
        configure(
                System.console() != null,
                new ReflectiveConsoleAppenderBackend());
    }

    static boolean configure(
            boolean consoleAvailable,
            ConsoleAppenderBackend backend) {
        if (consoleAvailable) {
            StartupLogDiagnostics.recordKeptForConsole();
            return false;
        }
        if (Boolean.getBoolean(KEEP_CONSOLE_PROPERTY)) {
            StartupLogDiagnostics.recordKeptByProperty();
            return false;
        }
        StartupLogDiagnostics.recordAttempt();
        try {
            boolean detached = backend.detachNamedConsoleAppender();
            if (detached) {
                StartupLogDiagnostics.recordDetached();
            }
            return detached;
        } catch (Exception | LinkageError incompatible) {
            StartupLogDiagnostics.recordFailure();
            return false;
        }
    }

    static void resetForTests() {
        StartupLogDiagnostics.resetForTests();
    }

    static Object invokeReflectively(
            Method method, Object target, Object... arguments)
            throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new ReflectiveOperationException(
                    "反射目标抛出非标准 Throwable", cause);
        }
    }

    interface ConsoleAppenderBackend {
        boolean detachNamedConsoleAppender() throws Exception;
    }

    private static final class ReflectiveConsoleAppenderBackend
            implements ConsoleAppenderBackend {
        @Override
        public boolean detachNamedConsoleAppender() throws Exception {
            Class<?> loggerClass = Class.forName(LOGGER_CLASS);
            Method getRootLogger = loggerClass.getMethod("getRootLogger");
            Object rootLogger = invokeReflectively(getRootLogger, null);
            Method getAppender = loggerClass.getMethod(
                    "getAppender", String.class);
            Object existing = invokeReflectively(
                    getAppender, rootLogger, APPENDER_NAME);
            if (existing == null) {
                return false;
            }
            Class<?> consoleAppenderClass = Class.forName(
                    CONSOLE_APPENDER_CLASS);
            if (existing.getClass() != consoleAppenderClass) {
                return false;
            }
            Class<?> appenderClass = Class.forName(APPENDER_CLASS);
            Method removeAppender = loggerClass.getMethod(
                    "removeAppender", appenderClass);
            invokeReflectively(removeAppender, rootLogger, existing);
            return invokeReflectively(
                    getAppender, rootLogger, APPENDER_NAME) != existing;
        }
    }
}

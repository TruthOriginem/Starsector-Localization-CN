package org.fossic.starsector.ime;

/** 可注入的错误日志出口，避免状态机测试依赖全局 stderr。 */
@FunctionalInterface
interface ImeLogSink {
    void error(String message, Throwable cause);
}

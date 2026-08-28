package org.fossic.starsector.ime;

/** 不触发 native 类初始化的轻量运行时配置。 */
final class ImeRuntimeConfig {
    static final String ENABLED_PROPERTY = "fossic.ime.enabled";
    static final String DEBUG_PROPERTY = "fossic.ime.debug";

    private ImeRuntimeConfig() {
    }

    static boolean enabled() {
        try {
            return parseEnabled(System.getProperty(ENABLED_PROPERTY));
        } catch (SecurityException ignored) {
            return true;
        }
    }

    static boolean parseEnabled(String value) {
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    static boolean debug() {
        try {
            return parseDebug(System.getProperty(DEBUG_PROPERTY));
        } catch (SecurityException ignored) {
            return false;
        }
    }

    static boolean parseDebug(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }
}

package org.fossic.starsector.optimization;

/** One-shot source selection belongs to the caller, not the next racing reader. */
public final class ResourceReadContext {
    private final ThreadLocal<String> selector = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SKIP_MODS = new ThreadLocal<>();

    public String selector() {
        return selector.get();
    }

    public void select(String source) {
        if (source == null) {
            // Keep the empty entry on ordinary reads: get/remove would allocate a
            // new ThreadLocalMap.Entry on every resource open.
            if (selector.get() != null) {
                selector.remove();
            }
        } else {
            selector.set(source);
        }
    }

    public static void skipMods(boolean skip) {
        if (skip) {
            SKIP_MODS.set(Boolean.TRUE);
        } else {
            clearSkipMods();
        }
    }

    public static void clearSkipMods() {
        if (SKIP_MODS.get() != null) {
            SKIP_MODS.remove();
        }
    }

    /** Legacy public-field writes retain their historical behavior. */
    public static boolean consumeSkipMods(boolean legacy) {
        boolean skip = SKIP_MODS.get() == Boolean.TRUE;
        if (skip) {
            SKIP_MODS.remove();
        }
        return legacy || skip;
    }

    /** Failure-only, opt-in logging; never replace the original loading exception. */
    public static void reportMissing(String path, String source, boolean includeMods) {
        if (!Boolean.getBoolean("starsector.resourceContext.diagnostics")) {
            return;
        }
        String message = "[SS-RESOURCE-CONTEXT] thread=" + Thread.currentThread().getName()
                + ", path=" + path + ", selector=" + source + ", includeMods=" + includeMods;
        try {
            Class<?> loggerType = Class.forName("org.apache.log4j.Logger");
            Object logger = loggerType.getMethod("getLogger", String.class)
                    .invoke(null, ResourceReadContext.class.getName());
            loggerType.getMethod("warn", Object.class).invoke(logger, message);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            System.err.println(message);
        }
    }
}

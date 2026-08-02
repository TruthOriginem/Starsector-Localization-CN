package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class StartupLogConfiguratorTest {
    @BeforeEach
    void reset() {
        System.clearProperty(
                StartupLogConfigurator.KEEP_CONSOLE_PROPERTY);
        StartupLogConfigurator.resetForTests();
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(
                StartupLogConfigurator.KEEP_CONSOLE_PROPERTY);
        StartupLogConfigurator.resetForTests();
    }

    @Test
    void detachesOnlyTheNamedConsoleAppenderWhenNoConsoleExists() {
        FakeBackend backend = new FakeBackend(true, false);

        assertTrue(StartupLogConfigurator.configure(false, backend));

        assertEquals(1, backend.invocations);
        assertTrue(StartupLogDiagnostics.json().contains(
                "\"detached\":1"));
    }

    @Test
    void keepsConsoleLoggingForAnAttachedConsole() {
        FakeBackend backend = new FakeBackend(true, false);

        assertFalse(StartupLogConfigurator.configure(true, backend));

        assertEquals(0, backend.invocations);
        assertTrue(StartupLogDiagnostics.json().contains(
                "\"keptForConsole\":1"));
    }

    @Test
    void explicitPropertyKeepsConsoleLoggingForTroubleshooting() {
        System.setProperty(
                StartupLogConfigurator.KEEP_CONSOLE_PROPERTY, "true");
        FakeBackend backend = new FakeBackend(true, false);

        assertFalse(StartupLogConfigurator.configure(false, backend));

        assertEquals(0, backend.invocations);
        assertTrue(StartupLogDiagnostics.json().contains(
                "\"keptByProperty\":1"));
    }

    @Test
    void reflectionFailureIsFailOpenAndNeverEscapes() {
        FakeBackend backend = new FakeBackend(false, true);

        assertFalse(StartupLogConfigurator.configure(false, backend));

        assertEquals(1, backend.invocations);
        assertTrue(StartupLogDiagnostics.json().contains(
                "\"failures\":1"));
    }

    @Test
    void fatalJvmErrorsAreNotHiddenAsReflectionIncompatibility() {
        AssertionError fatal = new AssertionError("broken invariant");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StartupLogConfigurator.configure(
                        false,
                        () -> {
                            throw fatal;
                        }));

        assertSame(fatal, thrown);
    }

    @Test
    void reflectiveInvocationUnwrapsFatalJvmErrors() throws Exception {
        AssertionError fatal = ReflectiveFailureTarget.FATAL;
        Method method = ReflectiveFailureTarget.class.getDeclaredMethod(
                "throwFatal");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StartupLogConfigurator.invokeReflectively(
                        method, null));

        assertSame(fatal, thrown);
    }

    private static final class FakeBackend
            implements StartupLogConfigurator.ConsoleAppenderBackend {
        private final boolean result;
        private final boolean fail;
        private int invocations;

        private FakeBackend(boolean result, boolean fail) {
            this.result = result;
            this.fail = fail;
        }

        @Override
        public boolean detachNamedConsoleAppender() throws Exception {
            invocations++;
            if (fail) {
                throw new ReflectiveOperationException("test failure");
            }
            return result;
        }
    }

    static final class ReflectiveFailureTarget {
        private static final AssertionError FATAL =
                new AssertionError("wrapped fatal");

        public static void throwFatal() {
            throw FATAL;
        }
    }
}

package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImeHooksTest {
    private final ImeController controller = ImeController.get();

    private boolean originalInitAttempted;
    private boolean originalAvailable;
    private long originalContext;
    private Object originalFocusedField;
    private boolean originalBroken;
    private int originalSpotX;
    private int originalSpotY;
    private int originalSpotHeight;
    private boolean originalSpotBroken;

    @BeforeEach
    void saveState() throws ReflectiveOperationException {
        originalInitAttempted = booleanField(controller, "initAttempted");
        originalAvailable = booleanField(controller, "available");
        originalContext = longField(controller, "ctx");
        originalFocusedField = field(ImeController.class, "focusedField").get(controller);
        originalBroken = booleanField(null, ImeHooks.class, "broken");
        originalSpotX = intField(controller, "lastSpotX");
        originalSpotY = intField(controller, "lastSpotY");
        originalSpotHeight = intField(controller, "lastSpotHeight");
        originalSpotBroken = booleanField(controller, "spotBroken");
    }

    @AfterEach
    void restoreState() throws ReflectiveOperationException {
        setField(controller, "initAttempted", originalInitAttempted);
        setField(controller, "available", originalAvailable);
        setField(controller, "ctx", originalContext);
        setField(controller, "focusedField", originalFocusedField);
        setField(null, ImeHooks.class, "broken", originalBroken);
        setField(controller, "lastSpotX", originalSpotX);
        setField(controller, "lastSpotY", originalSpotY);
        setField(controller, "lastSpotHeight", originalSpotHeight);
        setField(controller, "spotBroken", originalSpotBroken);
    }

    @Test
    void permanentlyShortCircuitsAfterFirstUnexpectedFailure() throws ReflectiveOperationException {
        AtomicInteger focusReads = new AtomicInteger();
        TextFieldAPI field = textField((proxy, method, args) -> {
            if ("hasFocus".equals(method.getName())) {
                focusReads.incrementAndGet();
                throw new IllegalStateException("test failure");
            }
            return defaultValue(method.getReturnType());
        });
        prepareAvailableController(null);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            assertDoesNotThrow(() -> ImeHooks.onProcessInput(field));
            assertDoesNotThrow(() -> ImeHooks.onProcessInput(field));
        } finally {
            System.setErr(originalError);
        }

        assertTrue(booleanField(null, ImeHooks.class, "broken"));
        assertFalse(booleanField(controller, "available"));
        assertEquals(1, focusReads.get());
        assertEquals(1, occurrences(errors.toString(StandardCharsets.UTF_8),
                "输入法钩子异常，已永久停用"));
    }

    @Test
    void releaseHookClearsOnlyTheTrackedField() throws ReflectiveOperationException {
        TextFieldAPI tracked = textField((proxy, method, args) -> defaultValue(method.getReturnType()));
        TextFieldAPI other = textField((proxy, method, args) -> defaultValue(method.getReturnType()));
        prepareAvailableController(tracked);

        ImeHooks.onFocusReleased(other);
        assertSame(tracked, focusedField());

        ImeHooks.onFocusReleased(tracked);
        assertNull(focusedField());
        assertFalse(booleanField(null, ImeHooks.class, "broken"));
    }

    private void prepareAvailableController(TextFieldAPI focused) throws ReflectiveOperationException {
        setField(controller, "initAttempted", true);
        setField(controller, "available", true);
        setField(controller, "ctx", 0L);
        setField(controller, "focusedField", focused == null ? null : new WeakReference<>(focused));
        setField(null, ImeHooks.class, "broken", false);
    }

    private TextFieldAPI focusedField() throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        WeakReference<TextFieldAPI> reference = (WeakReference<TextFieldAPI>)
                field(ImeController.class, "focusedField").get(controller);
        return reference == null ? null : reference.get();
    }

    private static TextFieldAPI textField(java.lang.reflect.InvocationHandler handler) {
        return (TextFieldAPI) Proxy.newProxyInstance(
                TextFieldAPI.class.getClassLoader(),
                new Class<?>[]{TextFieldAPI.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static boolean booleanField(Object target, String name) throws ReflectiveOperationException {
        return booleanField(target, target.getClass(), name);
    }

    private static boolean booleanField(Object target, Class<?> owner, String name)
            throws ReflectiveOperationException {
        return field(owner, name).getBoolean(target);
    }

    private static long longField(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getLong(target);
    }

    private static int intField(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getInt(target);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        setField(target, target.getClass(), name, value);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        field(owner, name).set(target, value);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

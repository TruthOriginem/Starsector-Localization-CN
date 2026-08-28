package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImeRegistrationTest {
    @Test
    void processingAFieldRegistersOnlyThatExactInstance() {
        TextFieldAPI registered = textField();
        TextFieldAPI distinct = textField();
        ImeController controller = controller(new CountingResolver());

        controller.onProcessInput(registered);

        assertTrue(controller.isRegisteredForTest(registered));
        assertFalse(controller.isRegisteredForTest(distinct));
    }

    @Test
    void focusGainFromUnregisteredFieldDoesNotEvenInitializeNativeSupport() {
        CountingResolver resolver = new CountingResolver();
        ImeController controller = controller(resolver);

        controller.onTextFieldFocusGained(textField());

        assertFalse(controller.isAttachedForTest());
        assertTrue(resolver.calls == 0);
    }

    private static ImeController controller(CountingResolver resolver) {
        return new ImeController(new LoadedNative(), resolver, (message, cause) -> {
        });
    }

    private static TextFieldAPI textField() {
        return (TextFieldAPI) Proxy.newProxyInstance(
                TextFieldAPI.class.getClassLoader(),
                new Class<?>[]{TextFieldAPI.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static final class LoadedNative implements ImeNativeFacade {
        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public AttachResult attach(long hwnd) {
            return AttachResult.success(22L);
        }

        @Override
        public TransitionResult enable(long context) {
            return TransitionResult.success();
        }

        @Override
        public TransitionResult beginCancel(long context) {
            return TransitionResult.success();
        }

        @Override
        public TransitionResult finishCancel(long context) {
            return TransitionResult.success();
        }
    }

    private static final class CountingResolver implements HwndResolver {
        private int calls;

        @Override
        public Resolution resolve() {
            calls++;
            return Resolution.ready(11L);
        }
    }
}

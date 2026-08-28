package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ImeActivationTest {
    @Test
    void registeredFocusedFieldSetsSpotBeforeEnablingExactlyOnce() {
        RecordingNative nativeFacade = new RecordingNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        controller.onTextFieldFocusGained(field);

        assertEquals(List.of("spot:10,20,30", "enable"), nativeFacade.events);
        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertSame(field, controller.inputOwnerForTest());
    }

    @Test
    void registeredButNotActuallyFocusedFieldDoesNotEnable() {
        RecordingNative nativeFacade = new RecordingNative();
        ImeController controller = controller(nativeFacade);
        TextFieldAPI field = textField(new AtomicBoolean(false));

        controller.onProcessInput(field);
        controller.onTextFieldFocusGained(field);

        assertEquals(List.of(), nativeFacade.events);
        assertEquals(ImeController.InputState.NONE, controller.inputStateForTest());
    }

    @Test
    void retryableEnableFailureRemainsDetachedAndCanRetry() {
        FailingEnableNative nativeFacade = new FailingEnableNative(
                ImeNativeFacade.TransitionStatus.RETRYABLE_FAILURE);
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        assertEquals(ImeController.InputState.NONE, controller.inputStateForTest());

        controller.onTextFieldFocusGained(field);
        assertEquals(2, nativeFacade.enableCalls);
        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertSame(field, controller.inputOwnerForTest());
    }

    @Test
    void windowGoneDuringEnableDropsTheOldContextAndWaitsForANewWindow() {
        FailingEnableNative nativeFacade = new FailingEnableNative(
                ImeNativeFacade.TransitionStatus.WINDOW_GONE);
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);

        assertEquals(ImeController.InitState.RETRY_WAIT, controller.initStateForTest());
        assertEquals(0L, controller.contextForTest());
        assertEquals(ImeController.InputState.NONE, controller.inputStateForTest());
    }

    @Test
    void spotFailureIsLoggedOnceButTextInputStillEnables() {
        RecordingNative nativeFacade = new RecordingNative();
        List<String> errors = new ArrayList<>();
        ImeController controller = new ImeController(
                nativeFacade,
                () -> HwndResolver.Resolution.ready(11L),
                (message, cause) -> errors.add(message),
                field -> {
                    throw new IllegalStateException("broken mod position");
                });
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        controller.onTextFieldFocusGained(field);

        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertEquals(1, nativeFacade.events.stream().filter("enable"::equals).count());
        assertEquals(List.of("计算候选窗位置失败，已只停用当前文本框的定位"), errors);
    }

    @Test
    void oneBrokenModFieldDoesNotDisableSpotUpdatesForOtherFields() {
        RecordingNative nativeFacade = new RecordingNative();
        List<String> errors = new ArrayList<>();
        AtomicBoolean badFocused = new AtomicBoolean();
        AtomicBoolean goodFocused = new AtomicBoolean();
        TextFieldAPI bad = textField(badFocused);
        TextFieldAPI good = textField(goodFocused);
        ImeController controller = new ImeController(
                nativeFacade,
                () -> HwndResolver.Resolution.ready(11L),
                (message, cause) -> errors.add(message),
                field -> {
                    if (field == bad) {
                        throw new IllegalStateException("broken mod position");
                    }
                    return new ImeSpot(40, 50, 60);
                });

        controller.onProcessInput(bad);
        badFocused.set(true);
        controller.onTextFieldFocusGained(bad);
        badFocused.set(false);
        controller.onFocusReleased(bad);
        controller.onGlobalInputFrame(null);

        controller.onProcessInput(good);
        goodFocused.set(true);
        controller.onTextFieldFocusGained(good);

        assertEquals(1, errors.size());
        assertEquals(1, nativeFacade.events.stream()
                .filter("spot:40,50,60"::equals).count());
        assertSame(good, controller.inputOwnerForTest());
    }

    private static ImeController controller(ImeNativeFacade nativeFacade) {
        return new ImeController(
                nativeFacade,
                () -> HwndResolver.Resolution.ready(11L),
                (message, cause) -> {
                },
                field -> new ImeSpot(10, 20, 30));
    }

    private static TextFieldAPI textField(AtomicBoolean focused) {
        return (TextFieldAPI) Proxy.newProxyInstance(
                TextFieldAPI.class.getClassLoader(),
                new Class<?>[]{TextFieldAPI.class},
                (proxy, method, args) -> {
                    if ("hasFocus".equals(method.getName())) {
                        return focused.get();
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static final class RecordingNative implements ImeNativeFacade {
        private final List<String> events = new ArrayList<>();

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public AttachResult attach(long hwnd) {
            return AttachResult.success(22L);
        }

        @Override
        public void setSpot(long context, int x, int y, int height) {
            events.add("spot:" + x + "," + y + "," + height);
        }

        @Override
        public TransitionResult enable(long context) {
            events.add("enable");
            return TransitionResult.success();
        }

        @Override
        public TransitionResult beginCancel(long context) {
            events.add("beginCancel");
            return TransitionResult.success();
        }

        @Override
        public TransitionResult finishCancel(long context) {
            events.add("finishCancel");
            return TransitionResult.success();
        }

        @Override
        public String poll(long context) {
            return null;
        }
    }

    private static final class FailingEnableNative implements ImeNativeFacade {
        private final TransitionStatus firstFailure;
        private int enableCalls;

        private FailingEnableNative(TransitionStatus firstFailure) {
            this.firstFailure = firstFailure;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public AttachResult attach(long hwnd) {
            return AttachResult.success(22L);
        }

        @Override
        public void setSpot(long context, int x, int y, int height) {
        }

        @Override
        public TransitionResult enable(long context) {
            enableCalls++;
            if (enableCalls == 1) {
                return new TransitionResult(firstFailure, "enable failed");
            }
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
}

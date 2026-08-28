package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ImeFailureRecoveryTest {
    @Test
    void finishCancelFailureEntersBoundedEmergencyCleanup() {
        SequenceResolver resolver = new SequenceResolver(HwndResolver.Resolution.ready(41L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        nativeFacade.finishResults.addLast(result(
                ImeNativeFacade.TransitionStatus.RETRYABLE_FAILURE, "finish failed"));
        ImeController controller = controller(nativeFacade, resolver);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(1, nativeFacade.finishCalls);
    }

    @Test
    void finishCancelWindowGoneNeverReenablesUsingClearedContext() {
        SequenceResolver resolver = new SequenceResolver(HwndResolver.Resolution.ready(41L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        nativeFacade.finishResults.addLast(result(
                ImeNativeFacade.TransitionStatus.WINDOW_GONE, "window gone"));
        ImeController controller = controller(nativeFacade, resolver);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);
        controller.onGlobalInputFrame(field);

        assertEquals(ImeController.InitState.RETRY_WAIT, controller.initStateForTest());
        assertEquals(0L, controller.contextForTest());
        assertEquals(1, nativeFacade.enableCalls);
    }

    @Test
    void permanentFinishFailureKeepsContextForEmergencyCleanup() {
        SequenceResolver resolver = new SequenceResolver(HwndResolver.Resolution.ready(41L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        nativeFacade.finishResults.addLast(result(
                ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE, "native failed"));
        ImeController controller = controller(nativeFacade, resolver);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(22L, controller.contextForTest());

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(2, nativeFacade.beginCalls);
        assertEquals(2, nativeFacade.finishCalls);
        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(0L, controller.contextForTest());
    }

    @Test
    void activeResolverFailureKeepsContextUntilDetachIsConfirmed() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.permanentFailure("reflection failed"));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        ImeController controller = controller(nativeFacade, resolver);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        controller.onGlobalInputFrame(field);

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(22L, controller.contextForTest());
    }

    @Test
    void overlappingWindowReplacementRetiresOldContextAndAttachesNewWindow() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(42L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        ImeController controller = controller(nativeFacade, resolver);

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(List.of(41L, 42L), nativeFacade.attachedHwnds);
        assertEquals(1, nativeFacade.beginCalls);
        assertEquals(1, nativeFacade.finishCalls);
        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
    }

    @Test
    void windowGoneDuringCancelReturnsToRetryableWindowLifecycle() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(42L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        nativeFacade.beginResults.addLast(result(
                ImeNativeFacade.TransitionStatus.WINDOW_GONE, "window gone"));
        ImeController controller = controller(nativeFacade, resolver);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);

        assertEquals(ImeController.InitState.RETRY_WAIT, controller.initStateForTest());
        controller.onGlobalInputFrame(null);
        assertEquals(List.of(41L, 42L), nativeFacade.attachedHwnds);
        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
    }

    @Test
    void externallyRetiredContextIsReattachedWithoutRetiringItAgain() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(41L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        ImeController controller = controller(nativeFacade, resolver);

        controller.onGlobalInputFrame(null);
        nativeFacade.state = ImeNativeFacade.NativeState.RETIRED;
        controller.onGlobalInputFrame(null);

        assertEquals(List.of(41L, 41L), nativeFacade.attachedHwnds);
        assertEquals(0, nativeFacade.beginCalls);
        assertEquals(0, nativeFacade.finishCalls);
        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
    }

    @Test
    void globalFrameTransfersDetachedLauncherContextToGameWindowThread()
            throws InterruptedException {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(42L),
                HwndResolver.Resolution.ready(42L));
        ProgrammableNative nativeFacade = new ProgrammableNative();
        ImeController controller = controller(nativeFacade, resolver);

        controller.onGlobalInputFrame(null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread gameThread = new Thread(() -> {
            try {
                controller.onGlobalInputFrame(null);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "game-window-test");
        gameThread.start();
        gameThread.join();

        assertNull(failure.get());
        assertEquals(List.of(41L, 42L), nativeFacade.attachedHwnds);
        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
    }

    private static ImeController controller(
            ImeNativeFacade nativeFacade, HwndResolver resolver) {
        return new ImeController(nativeFacade, resolver, (message, cause) -> {
        }, field -> new ImeSpot(10, 20, 30));
    }

    private static ImeNativeFacade.TransitionResult result(
            ImeNativeFacade.TransitionStatus status, String message) {
        return new ImeNativeFacade.TransitionResult(status, message);
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

    private static final class SequenceResolver implements HwndResolver {
        private final Deque<Resolution> values = new ArrayDeque<>();
        private Resolution last;

        private SequenceResolver(Resolution... values) {
            for (Resolution value : values) {
                this.values.addLast(value);
                last = value;
            }
        }

        @Override
        public Resolution resolve() {
            if (!values.isEmpty()) {
                last = values.removeFirst();
            }
            return last;
        }
    }

    private static final class ProgrammableNative implements ImeNativeFacade {
        private final List<Long> attachedHwnds = new ArrayList<>();
        private final Deque<TransitionResult> beginResults = new ArrayDeque<>();
        private final Deque<TransitionResult> finishResults = new ArrayDeque<>();
        private int beginCalls;
        private int finishCalls;
        private int enableCalls;
        private NativeState state = NativeState.DETACHED;

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public AttachResult attach(long hwnd) {
            attachedHwnds.add(hwnd);
            return AttachResult.success(21L + attachedHwnds.size());
        }

        @Override
        public TransitionResult enable(long context) {
            enableCalls++;
            return TransitionResult.success();
        }

        @Override
        public TransitionResult beginCancel(long context) {
            beginCalls++;
            return beginResults.isEmpty() ? TransitionResult.success()
                    : beginResults.removeFirst();
        }

        @Override
        public TransitionResult finishCancel(long context) {
            finishCalls++;
            return finishResults.isEmpty() ? TransitionResult.success()
                    : finishResults.removeFirst();
        }

        @Override
        public NativeState state(long context) {
            return state;
        }

        @Override
        public void setSpot(long context, int x, int y, int height) {
        }

        @Override
        public String poll(long context) {
            return null;
        }
    }
}

package org.fossic.starsector.ime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImeInitializationTest {
    @Test
    void retriesNotReadyWindowThenAttachesExactlyOnce() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.retryLater(),
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(41L));
        FakeNative nativeFacade = new FakeNative(true,
                ImeNativeFacade.AttachResult.success(73L));
        RecordingLog log = new RecordingLog();
        ImeController controller = new ImeController(nativeFacade, resolver, log);

        controller.onGlobalInputFrame(null);
        assertEquals(ImeController.InitState.RETRY_WAIT, controller.initStateForTest());
        assertEquals(0, nativeFacade.attachCalls);

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
        assertEquals(1, nativeFacade.attachCalls);
        assertEquals(41L, nativeFacade.lastHwnd);
        assertEquals(73L, controller.contextForTest());
        assertTrue(log.errors.isEmpty());
    }

    @Test
    void permanentResolverFailureIsReportedOnceAndNeverRetried() {
        CountingResolver resolver = new CountingResolver(
                HwndResolver.Resolution.permanentFailure("unsupported LWJGL layout"));
        FakeNative nativeFacade = new FakeNative(true,
                ImeNativeFacade.AttachResult.success(73L));
        RecordingLog log = new RecordingLog();
        ImeController controller = new ImeController(nativeFacade, resolver, log);

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(1, resolver.calls);
        assertEquals(0, nativeFacade.attachCalls);
        assertEquals(List.of("unsupported LWJGL layout"), log.errors);
    }

    @Test
    void unavailableNativeIsReportedOnceWithoutResolvingWindow() {
        CountingResolver resolver = new CountingResolver(
                HwndResolver.Resolution.ready(41L));
        FakeNative nativeFacade = new FakeNative(false,
                ImeNativeFacade.AttachResult.success(73L));
        RecordingLog log = new RecordingLog();
        ImeController controller = new ImeController(nativeFacade, resolver, log);

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(0, resolver.calls);
        assertEquals(0, nativeFacade.attachCalls);
        assertEquals(1, log.errors.size());
    }

    @Test
    void attachFailureIsReportedOnceAndNeverPretendsToBeAttached() {
        CountingResolver resolver = new CountingResolver(
                HwndResolver.Resolution.ready(41L));
        FakeNative nativeFacade = new FakeNative(true,
                ImeNativeFacade.AttachResult.permanentFailure("attach failed"));
        RecordingLog log = new RecordingLog();
        ImeController controller = new ImeController(nativeFacade, resolver, log);

        controller.onGlobalInputFrame(null);
        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(1, resolver.calls);
        assertEquals(1, nativeFacade.attachCalls);
        assertEquals(0L, controller.contextForTest());
        assertEquals(List.of("attach failed"), log.errors);
    }

    @Test
    void reattachesWhenHwndChangesAfterNativeConfirmsOldWindowIsGone() {
        SequenceResolver resolver = new SequenceResolver(
                HwndResolver.Resolution.ready(41L),
                HwndResolver.Resolution.ready(42L));
        RecreatingNative nativeFacade = new RecreatingNative();
        RecordingLog log = new RecordingLog();
        ImeController controller = new ImeController(nativeFacade, resolver, log);

        controller.onGlobalInputFrame(null);
        nativeFacade.oldState = ImeNativeFacade.NativeState.WINDOW_GONE;
        controller.onGlobalInputFrame(null);

        assertEquals(List.of(41L, 42L), nativeFacade.attachedHwnds);
        assertEquals(102L, controller.contextForTest());
        assertEquals(ImeController.InitState.ATTACHED, controller.initStateForTest());
        assertTrue(log.errors.isEmpty());
    }

    @Test
    void runtimePropertyDefaultsToEnabledAndRecognizesFalseCaseInsensitively() {
        assertTrue(ImeRuntimeConfig.parseEnabled(null));
        assertTrue(ImeRuntimeConfig.parseEnabled("true"));
        assertTrue(ImeRuntimeConfig.parseEnabled("unexpected"));
        assertFalse(ImeRuntimeConfig.parseEnabled("false"));
        assertFalse(ImeRuntimeConfig.parseEnabled("FALSE"));
    }

    @Test
    void debugPropertyDefaultsOffAndOnlyRecognizesTrue() {
        assertFalse(ImeRuntimeConfig.parseDebug(null));
        assertFalse(ImeRuntimeConfig.parseDebug("false"));
        assertFalse(ImeRuntimeConfig.parseDebug("unexpected"));
        assertTrue(ImeRuntimeConfig.parseDebug("true"));
        assertTrue(ImeRuntimeConfig.parseDebug("TRUE"));
    }

    private static final class SequenceResolver implements HwndResolver {
        private final Deque<Resolution> resolutions = new ArrayDeque<>();
        private Resolution last;

        private SequenceResolver(Resolution... values) {
            for (Resolution value : values) {
                resolutions.addLast(value);
                last = value;
            }
        }

        @Override
        public Resolution resolve() {
            if (!resolutions.isEmpty()) {
                last = resolutions.removeFirst();
            }
            return last;
        }
    }

    private static final class CountingResolver implements HwndResolver {
        private final Resolution resolution;
        private int calls;

        private CountingResolver(Resolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public Resolution resolve() {
            calls++;
            return resolution;
        }
    }

    private static final class FakeNative implements ImeNativeFacade {
        private final boolean loaded;
        private final AttachResult result;
        private int attachCalls;
        private long lastHwnd;

        private FakeNative(boolean loaded, AttachResult result) {
            this.loaded = loaded;
            this.result = result;
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public AttachResult attach(long hwnd) {
            attachCalls++;
            lastHwnd = hwnd;
            return result;
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

    private static final class RecordingLog implements ImeLogSink {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void error(String message, Throwable cause) {
            errors.add(message);
        }
    }

    private static final class RecreatingNative implements ImeNativeFacade {
        private final List<Long> attachedHwnds = new ArrayList<>();
        private NativeState oldState = NativeState.DETACHED;

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public AttachResult attach(long hwnd) {
            attachedHwnds.add(hwnd);
            return AttachResult.success(100L + attachedHwnds.size());
        }

        @Override
        public NativeState state(long context) {
            return oldState;
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
}

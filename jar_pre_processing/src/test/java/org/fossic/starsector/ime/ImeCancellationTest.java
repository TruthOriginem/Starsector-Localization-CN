package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImeCancellationTest {
    @Test
    void releaseDrainsCommittedTextThenCancelsOnceAndFinishesOnNextFrame() {
        RecordingNative nativeFacade = new RecordingNative();
        nativeFacade.committed.addLast("中文");
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        List<Character> appended = new ArrayList<>();
        TextFieldAPI field = textField(focused, appended);

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        nativeFacade.events.clear();

        focused.set(false);
        controller.onFocusReleased(field);
        controller.onFocusReleased(field);

        assertEquals(List.of('中', '文'), appended);
        assertEquals(List.of("poll", "poll", "beginCancel"), nativeFacade.events);
        assertEquals(ImeController.InputState.CANCELLING, controller.inputStateForTest());

        controller.onGlobalInputFrame(null);

        assertEquals(List.of("poll", "poll", "beginCancel", "finishCancel"),
                nativeFacade.events);
        assertEquals(ImeController.InputState.NONE, controller.inputStateForTest());
        assertNull(controller.inputOwnerForTest());
    }

    @Test
    void switchingFieldsDefersTheNewOwnerUntilCancellationBarrierCompletes() {
        RecordingNative nativeFacade = new RecordingNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean aFocused = new AtomicBoolean();
        AtomicBoolean bFocused = new AtomicBoolean();
        List<Character> appendedToA = new ArrayList<>();
        List<Character> appendedToB = new ArrayList<>();
        TextFieldAPI fieldA = textField(aFocused, appendedToA);
        TextFieldAPI fieldB = textField(bFocused, appendedToB);

        controller.onProcessInput(fieldA);
        aFocused.set(true);
        controller.onTextFieldFocusGained(fieldA);
        controller.onProcessInput(fieldB);
        nativeFacade.events.clear();
        nativeFacade.committed.addLast("甲");

        aFocused.set(false);
        bFocused.set(true);
        controller.onTextFieldFocusGained(fieldB);

        assertEquals(List.of('甲'), appendedToA);
        assertEquals(List.of(), appendedToB);
        assertEquals(List.of("poll", "poll", "beginCancel"), nativeFacade.events);
        assertEquals(ImeController.InputState.CANCELLING, controller.inputStateForTest());
        assertSame(fieldA, controller.inputOwnerForTest());

        controller.onGlobalInputFrame(fieldB);

        assertEquals(List.of("poll", "poll", "beginCancel", "finishCancel",
                "spot:10,20,30", "enable"), nativeFacade.events);
        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertSame(fieldB, controller.inputOwnerForTest());

        controller.onFocusReleased(fieldA);
        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertSame(fieldB, controller.inputOwnerForTest());
    }

    @Test
    void ordinaryFocusOwnerSuspendsThenRestoresTheRegisteredField() {
        RecordingNative nativeFacade = new RecordingNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused, new ArrayList<>());
        Object ordinaryControl = new Object();

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        nativeFacade.events.clear();

        focused.set(false);
        controller.onGlobalInputFrame(ordinaryControl);
        assertEquals(ImeController.InputState.CANCELLING, controller.inputStateForTest());

        controller.onGlobalInputFrame(ordinaryControl);
        assertEquals(ImeController.InputState.SUSPENDED, controller.inputStateForTest());
        assertSame(field, controller.inputOwnerForTest());

        focused.set(true);
        controller.onGlobalInputFrame(field);

        assertEquals(List.of("poll", "beginCancel", "finishCancel",
                "spot:10,20,30", "enable"), nativeFacade.events);
        assertEquals(ImeController.InputState.ACTIVE, controller.inputStateForTest());
        assertSame(field, controller.inputOwnerForTest());
    }

    @Test
    void failedDetachEntersUnsafeCleanupAndOnlyStopsAfterDetachIsConfirmed() {
        CleanupRetryNative nativeFacade = new CleanupRetryNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused, new ArrayList<>());

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(1, nativeFacade.beginCalls);

        controller.onGlobalInputFrame(null);
        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(2, nativeFacade.beginCalls);
        assertEquals(0, nativeFacade.finishCalls);

        controller.onGlobalInputFrame(null);
        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(ImeController.InputState.NONE, controller.inputStateForTest());
        assertEquals(1, nativeFacade.finishCalls);

        controller.onGlobalInputFrame(field);
        assertEquals(1, nativeFacade.enableCalls);
    }

    @Test
    void unsafeCleanupRetriesAreBoundedWhenNativeNeverConfirmsDetach() {
        AlwaysFailingCleanupNative nativeFacade = new AlwaysFailingCleanupNative();
        List<String> errors = new ArrayList<>();
        ImeController controller = new ImeController(
                nativeFacade,
                () -> HwndResolver.Resolution.ready(11L),
                (message, cause) -> errors.add(message),
                field -> new ImeSpot(10, 20, 30));
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused, new ArrayList<>());

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        focused.set(false);
        controller.onFocusReleased(field);
        for (int frame = 0; frame < 20; frame++) {
            controller.onGlobalInputFrame(null);
        }

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(4, nativeFacade.beginCalls);
        assertEquals(2, errors.size());
        assertTrue(errors.get(1).contains("耗尽重试"));
    }

    @Test
    void brokenHookStillAdvancesCleanupUntilDetachIsConfirmed() {
        CleanupRetryNative nativeFacade = new CleanupRetryNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused, new ArrayList<>());

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        controller.disableAfterFailure();

        assertEquals(ImeController.InitState.UNSAFE_CLEANUP,
                controller.initStateForTest());
        assertEquals(1, nativeFacade.beginCalls);

        ImeHooks.advanceEmergencyCleanup(controller);
        ImeHooks.advanceEmergencyCleanup(controller);

        assertEquals(ImeController.InitState.PERMANENT_FAILURE,
                controller.initStateForTest());
        assertEquals(1, nativeFacade.finishCalls);
    }

    @Test
    void clearedOwnerWeakReferenceStillTriggersCancellation() throws Exception {
        RecordingNative nativeFacade = new RecordingNative();
        ImeController controller = controller(nativeFacade);
        AtomicBoolean focused = new AtomicBoolean();
        TextFieldAPI field = textField(focused, new ArrayList<>());

        controller.onProcessInput(field);
        focused.set(true);
        controller.onTextFieldFocusGained(field);
        nativeFacade.events.clear();

        Field ownerField = ImeController.class.getDeclaredField("focusedField");
        ownerField.setAccessible(true);
        @SuppressWarnings("unchecked")
        WeakReference<TextFieldAPI> owner =
                (WeakReference<TextFieldAPI>) ownerField.get(controller);
        owner.clear();

        controller.onGlobalInputFrame(null);

        assertEquals(ImeController.InputState.CANCELLING, controller.inputStateForTest());
        assertEquals(List.of("beginCancel"), nativeFacade.events);
    }

    private static ImeController controller(ImeNativeFacade nativeFacade) {
        return new ImeController(
                nativeFacade,
                () -> HwndResolver.Resolution.ready(11L),
                (message, cause) -> {
                },
                field -> new ImeSpot(10, 20, 30));
    }

    private static TextFieldAPI textField(AtomicBoolean focused, List<Character> appended) {
        return (TextFieldAPI) Proxy.newProxyInstance(
                TextFieldAPI.class.getClassLoader(),
                new Class<?>[]{TextFieldAPI.class},
                (proxy, method, args) -> {
                    if ("hasFocus".equals(method.getName())) {
                        return focused.get();
                    }
                    if ("appendCharIfPossible".equals(method.getName())) {
                        appended.add((Character) args[0]);
                        return true;
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
        private final Deque<String> committed = new ArrayDeque<>();
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
        public String poll(long context) {
            events.add("poll");
            return committed.pollFirst();
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
    }

    private static final class CleanupRetryNative implements ImeNativeFacade {
        private int enableCalls;
        private int beginCalls;
        private int finishCalls;

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
            return TransitionResult.success();
        }

        @Override
        public String poll(long context) {
            return null;
        }

        @Override
        public TransitionResult beginCancel(long context) {
            beginCalls++;
            if (beginCalls == 1) {
                return new TransitionResult(TransitionStatus.RETRYABLE_FAILURE,
                        "detach not confirmed");
            }
            return TransitionResult.success();
        }

        @Override
        public TransitionResult finishCancel(long context) {
            finishCalls++;
            return TransitionResult.success();
        }
    }

    private static final class AlwaysFailingCleanupNative implements ImeNativeFacade {
        private int beginCalls;

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
            return TransitionResult.success();
        }

        @Override
        public String poll(long context) {
            return null;
        }

        @Override
        public TransitionResult beginCancel(long context) {
            beginCalls++;
            return new TransitionResult(TransitionStatus.RETRYABLE_FAILURE,
                    "still associated");
        }

        @Override
        public TransitionResult finishCancel(long context) {
            return TransitionResult.success();
        }
    }
}

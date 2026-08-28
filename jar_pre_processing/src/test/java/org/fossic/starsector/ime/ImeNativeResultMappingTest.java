package org.fossic.starsector.ime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ImeNativeResultMappingTest {
    @Test
    void mapsOnlyExplicitRetryableAttachCodeAsRetryable() {
        assertEquals(ImeNativeFacade.AttachStatus.RETRYABLE_FAILURE,
                SystemImeNativeFacade.mapAttachFailure(1, "retry").status());
        assertEquals(ImeNativeFacade.AttachStatus.PERMANENT_FAILURE,
                SystemImeNativeFacade.mapAttachFailure(2, "failed").status());
        assertEquals(ImeNativeFacade.AttachStatus.PERMANENT_FAILURE,
                SystemImeNativeFacade.mapAttachFailure(999, "unknown").status());
    }

    @Test
    void mapsEveryNativeTransitionCodeWithoutCollapsingFailureKinds() {
        assertEquals(ImeNativeFacade.TransitionStatus.SUCCESS,
                SystemImeNativeFacade.mapTransitionResult(0, "ignored").status());
        assertEquals(ImeNativeFacade.TransitionStatus.WINDOW_GONE,
                SystemImeNativeFacade.mapTransitionResult(1, "gone").status());
        assertEquals(ImeNativeFacade.TransitionStatus.WRONG_THREAD,
                SystemImeNativeFacade.mapTransitionResult(2, "thread").status());
        assertEquals(ImeNativeFacade.TransitionStatus.RETRYABLE_FAILURE,
                SystemImeNativeFacade.mapTransitionResult(3, "retry").status());
        assertEquals(ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE,
                SystemImeNativeFacade.mapTransitionResult(4, "failed").status());
        assertEquals(ImeNativeFacade.TransitionStatus.PERMANENT_FAILURE,
                SystemImeNativeFacade.mapTransitionResult(999, "unknown").status());
    }

    @Test
    void mapsEveryNativeStateCode() {
        assertEquals(ImeNativeFacade.NativeState.DETACHED,
                SystemImeNativeFacade.mapNativeState(0));
        assertEquals(ImeNativeFacade.NativeState.ENABLING,
                SystemImeNativeFacade.mapNativeState(1));
        assertEquals(ImeNativeFacade.NativeState.ENABLED,
                SystemImeNativeFacade.mapNativeState(2));
        assertEquals(ImeNativeFacade.NativeState.CANCELLING,
                SystemImeNativeFacade.mapNativeState(3));
        assertEquals(ImeNativeFacade.NativeState.WINDOW_GONE,
                SystemImeNativeFacade.mapNativeState(4));
        assertEquals(ImeNativeFacade.NativeState.FAILED,
                SystemImeNativeFacade.mapNativeState(5));
        assertEquals(ImeNativeFacade.NativeState.RETIRED,
                SystemImeNativeFacade.mapNativeState(6));
        assertEquals(ImeNativeFacade.NativeState.FAILED,
                SystemImeNativeFacade.mapNativeState(999));
    }
}

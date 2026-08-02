package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class RuleIdTrackerTest {
    @AfterEach
    void removeThreadLocalState() {
        RuleIdTracker.finish();
    }

    @Test
    void requiresAnExplicitResetBeforeTracking() {
        assertThrows(
                IllegalStateException.class,
                () -> RuleIdTracker.candidates("trigger", "id", new Object()));
    }

    @Test
    void returnsOnlyTheCurrentRuleForADuplicateInTheSameTrigger() {
        Object firstRule = new Object();
        Object duplicateRule = new Object();
        RuleIdTracker.reset();

        List<Object> first =
                RuleIdTracker.candidates("trigger", "id", firstRule);
        List<Object> duplicate =
                RuleIdTracker.candidates("trigger", "id", duplicateRule);

        assertTrue(first.isEmpty());
        assertEquals(1, duplicate.size());
        assertSame(duplicateRule, duplicate.get(0));
    }

    @Test
    void tracksTheSameIdIndependentlyForDifferentTriggers() {
        RuleIdTracker.reset();

        assertTrue(RuleIdTracker
                .candidates("trigger-a", "id", new Object())
                .isEmpty());
        assertTrue(RuleIdTracker
                .candidates("trigger-b", "id", new Object())
                .isEmpty());
    }

    @Test
    void resetDiscardsIdsFromAPreviousLoad() {
        RuleIdTracker.reset();
        RuleIdTracker.candidates("trigger", "id", new Object());

        RuleIdTracker.reset();

        assertTrue(RuleIdTracker
                .candidates("trigger", "id", new Object())
                .isEmpty());
    }

    @Test
    void finishRemovesState() {
        RuleIdTracker.reset();
        RuleIdTracker.finish();

        assertThrows(
                IllegalStateException.class,
                () -> RuleIdTracker.candidates("trigger", "id", new Object()));
    }

    @Test
    void stateIsIsolatedBetweenThreads()
            throws ExecutionException, InterruptedException {
        RuleIdTracker.reset();
        RuleIdTracker.candidates("trigger", "id", new Object());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<Object>> future = executor.submit(() -> {
                assertThrows(
                        IllegalStateException.class,
                        () -> RuleIdTracker.candidates(
                                "trigger", "id", new Object()));
                RuleIdTracker.reset();
                try {
                    return RuleIdTracker.candidates(
                            "trigger", "id", new Object());
                } finally {
                    RuleIdTracker.finish();
                }
            });

            assertTrue(future.get().isEmpty());
            Object duplicate = new Object();
            assertSame(
                    duplicate,
                    RuleIdTracker
                            .candidates("trigger", "id", duplicate)
                            .get(0));
        } finally {
            executor.shutdownNow();
        }
    }
}

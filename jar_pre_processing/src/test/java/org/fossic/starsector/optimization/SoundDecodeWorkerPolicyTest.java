package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class SoundDecodeWorkerPolicyTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(
                SoundDecodeWorkerPolicy.WORKER_COUNT_PROPERTY);
    }

    @Test
    void defaultsToOriginalTwoWorkersRegardlessOfProcessorCount() {
        assertEquals(2, SoundDecodeWorkerPolicy.workerCount(1));
        assertEquals(2, SoundDecodeWorkerPolicy.workerCount(2));
        assertEquals(2, SoundDecodeWorkerPolicy.workerCount(4));
        assertEquals(2, SoundDecodeWorkerPolicy.workerCount(8));
        assertEquals(2, SoundDecodeWorkerPolicy.workerCount(32));
    }

    @Test
    void explicitOverrideSupportsExactABAndStaysBounded() {
        System.setProperty(
                SoundDecodeWorkerPolicy.WORKER_COUNT_PROPERTY, "1");
        assertEquals(1, SoundDecodeWorkerPolicy.workerCount(32));

        System.setProperty(
                SoundDecodeWorkerPolicy.WORKER_COUNT_PROPERTY, "0");
        assertEquals(1, SoundDecodeWorkerPolicy.workerCount(32));

        System.setProperty(
                SoundDecodeWorkerPolicy.WORKER_COUNT_PROPERTY, "6");
        assertEquals(6, SoundDecodeWorkerPolicy.workerCount(32));

        System.setProperty(
                SoundDecodeWorkerPolicy.WORKER_COUNT_PROPERTY, "99");
        assertEquals(8, SoundDecodeWorkerPolicy.workerCount(32));
    }
}

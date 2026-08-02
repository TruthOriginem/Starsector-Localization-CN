package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OwnedResourceStreamsTest {
    @Test
    void readAllClosesTheOwnedStreamOnSuccess() throws IOException {
        TrackingInputStream input = new TrackingInputStream(
                new byte[]{1, 2, 3, 4});

        assertArrayEquals(
                new byte[]{1, 2, 3, 4},
                OwnedResourceStreams.readAllAndClose(input));
        assertEquals(1, input.closeCount);
    }

    @Test
    void readFailureRemainsPrimaryWhenCloseAlsoFails() {
        IOException readFailure = new IOException("read");
        IOException closeFailure = new IOException("close");
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }

            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };

        IOException actual = assertThrows(
                IOException.class,
                () -> OwnedResourceStreams.readAllAndClose(input));

        assertSame(readFailure, actual);
        assertArrayEquals(
                new Throwable[]{closeFailure}, actual.getSuppressed());
    }

    @Test
    void zeroLengthBulkReadsFallBackToProgressMakingSingleReads()
            throws IOException {
        InputStream input = new InputStream() {
            private final byte[] bytes = {7, 8};
            private int index;
            private int zeroBulkReads;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (zeroBulkReads++ >= 3) {
                    throw new AssertionError(
                            "bulk-read loop made no progress");
                }
                return 0;
            }

            @Override
            public int read() {
                return index < bytes.length
                        ? bytes[index++] & 0xff
                        : -1;
            }
        };

        assertArrayEquals(
                new byte[]{7, 8},
                OwnedResourceStreams.readAllAndClose(input));
    }

    @Test
    void imageReaderOwnsItsInputEvenWhenNoReaderAcceptsTheData()
            throws IOException {
        TrackingInputStream input = new TrackingInputStream(new byte[0]);

        assertNull(OwnedResourceStreams.readImageAndClose(input));
        assertEquals(1, input.closeCount);
    }

    @Test
    void pairScopeClosesEveryDistinctStreamAndIsIdempotent() {
        TrackingInputStream first = new TrackingInputStream(new byte[0]);
        TrackingInputStream second = new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.PairStreamScope scope =
                OwnedResourceStreams.capturePairStreams(List.of(
                        new PublicPair(first),
                        new PublicPair(second),
                        new PublicPair(first)));

        OwnedResourceStreams.closeBeforeReturn(scope);
        OwnedResourceStreams.closeBeforeReturn(scope);

        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
    }

    @Test
    void discardedStreamCleanupPreservesTheDuplicateDecision() {
        TrackingInputStream retained = new TrackingInputStream(new byte[0]);
        FailingCloseInputStream ordinaryFailure =
                new FailingCloseInputStream(new IOException("close"));
        FailingCloseInputStream fatalFailure =
                new FailingCloseInputStream(new AssertionError("close"));

        assertFalse(OwnedResourceStreams.closeIfDiscarded(
                retained, false));
        assertTrue(OwnedResourceStreams.closeIfDiscarded(
                ordinaryFailure, true));
        assertTrue(OwnedResourceStreams.closeIfDiscarded(
                fatalFailure, true));
        assertTrue(OwnedResourceStreams.closeIfDiscarded(null, true));

        assertEquals(0, retained.closeCount);
        assertEquals(1, ordinaryFailure.closeCount);
        assertEquals(1, fatalFailure.closeCount);
    }

    @Test
    void failedDiscardCloseRemainsTrackedUntilSuccessfulReleaseRetry() {
        FailOnceCloseInputStream input = new FailOnceCloseInputStream();
        OwnedResourceStreams.enterPartialOpen();
        OwnedResourceStreams.trackPartialOpenStream(input);

        assertTrue(OwnedResourceStreams.closeIfDiscarded(input, true));
        OwnedResourceStreams.releasePartialOpen();

        assertEquals(2, input.closeCount);
    }

    @Test
    void failedDiscardCloseIsSuppressedIfTheOpenLaterFails() {
        IOException closeFailure = new IOException("discard close");
        FailingCloseInputStream input =
                new FailingCloseInputStream(closeFailure);
        OwnedResourceStreams.enterPartialOpen();
        OwnedResourceStreams.trackPartialOpenStream(input);

        assertTrue(OwnedResourceStreams.closeIfDiscarded(input, true));
        RuntimeException primary = new RuntimeException("later source");
        OwnedResourceStreams.closePartialOpenAfterFailure(primary);

        assertEquals(2, input.closeCount);
        assertArrayEquals(
                new Throwable[]{closeFailure}, primary.getSuppressed());
    }

    @Test
    void exceptionalCleanupClosesAllAndNeverReplacesThePrimaryFailure() {
        IOException closeFailure = new IOException("close-one");
        AssertionError fatalCloseFailure =
                new AssertionError("close-two");
        FailingCloseInputStream first =
                new FailingCloseInputStream(closeFailure);
        FailingCloseInputStream second =
                new FailingCloseInputStream(fatalCloseFailure);
        TrackingInputStream third = new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.PairStreamScope scope =
                OwnedResourceStreams.capturePairStreams(List.of(
                        new PublicPair(first),
                        new PublicPair(second),
                        new PublicPair(third)));
        RuntimeException primary = new RuntimeException("parse");

        OwnedResourceStreams.closeAfterFailure(primary, scope);

        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
        assertEquals(1, third.closeCount);
        assertArrayEquals(
                new Throwable[]{closeFailure, fatalCloseFailure},
                primary.getSuppressed());
    }

    @Test
    void normalBatchCleanupIgnoresOrdinaryCloseFailuresAndContinues() {
        FailingCloseInputStream failing = new FailingCloseInputStream(
                new IOException("close"));
        TrackingInputStream following =
                new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.PairStreamScope scope =
                OwnedResourceStreams.capturePairStreams(List.of(
                        new PublicPair(failing),
                        new PublicPair(following)));

        OwnedResourceStreams.closeBeforeReturn(scope);

        assertEquals(1, failing.closeCount);
        assertEquals(1, following.closeCount);
    }

    @Test
    void nestedThreadScopesCloseOnlyTheCurrentInvocation() {
        TrackingInputStream outer = new TrackingInputStream(new byte[0]);
        TrackingInputStream inner = new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(outer)));
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(inner)));

        OwnedResourceStreams.closeCurrentBeforeReturn();

        assertEquals(0, outer.closeCount);
        assertEquals(1, inner.closeCount);

        RuntimeException primary = new RuntimeException("outer parse");
        OwnedResourceStreams.closeCurrentAfterFailure(primary);

        assertEquals(1, outer.closeCount);
        assertEquals(0, primary.getSuppressed().length);
        assertThrows(
                IllegalStateException.class,
                OwnedResourceStreams::closeCurrentBeforeReturn);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pairScopeRegistrationFailureClosesAlreadyCapturedStreams()
            throws Exception {
        Field field = OwnedResourceStreams.class
                .getDeclaredField("PAIR_SCOPES");
        field.setAccessible(true);
        ThreadLocal<Deque<OwnedResourceStreams.PairStreamScope>> local =
                (ThreadLocal<Deque<OwnedResourceStreams.PairStreamScope>>)
                        field.get(null);
        AssertionError registrationFailure =
                new AssertionError("scope push");
        local.set(new ArrayDeque<>() {
            @Override
            public void push(
                    OwnedResourceStreams.PairStreamScope scope) {
                throw registrationFailure;
            }
        });
        TrackingInputStream input =
                new TrackingInputStream(new byte[0]);
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> OwnedResourceStreams.enterPairStreams(
                            List.of(new PublicPair(input))));

            assertSame(registrationFailure, actual);
            assertEquals(1, input.closeCount);
        } finally {
            local.remove();
        }
    }

    @Test
    void successfullyConsumedPairStreamIsNotClosedTwice() throws IOException {
        NonIdempotentCloseInputStream consumed =
                new NonIdempotentCloseInputStream();
        NonIdempotentCloseInputStream unconsumed =
                new NonIdempotentCloseInputStream();
        OwnedResourceStreams.enterPairStreams(List.of(
                new PublicPair(consumed), new PublicPair(unconsumed)));

        consumed.close();
        OwnedResourceStreams.forgetCurrentPairStream(consumed);
        OwnedResourceStreams.closeCurrentBeforeReturn();
        OwnedResourceStreams.forgetCurrentPairStream(consumed);

        assertEquals(1, consumed.closeCount);
        assertEquals(1, unconsumed.closeCount);
    }

    @Test
    void explicitSuccessfulCloseForgetsThePairStream() throws IOException {
        NonIdempotentCloseInputStream input =
                new NonIdempotentCloseInputStream();
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(input)));

        OwnedResourceStreams.closeAndForgetCurrentPairStream(input);
        OwnedResourceStreams.closeCurrentBeforeReturn();

        assertEquals(1, input.closeCount);
    }

    @Test
    void failedExplicitCloseRemainsTrackedForExceptionalRetry() {
        FailOnceCloseInputStream input = new FailOnceCloseInputStream();
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(input)));

        IOException primary = assertThrows(
                IOException.class,
                () -> OwnedResourceStreams
                        .closeAndForgetCurrentPairStream(input));
        OwnedResourceStreams.closeCurrentAfterFailure(primary);

        assertEquals(2, input.closeCount);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void trackedFastReadFailureWithSuccessfulCloseIsNotClosedTwice() {
        ReadFailingNonIdempotentCloseInputStream input =
                new ReadFailingNonIdempotentCloseInputStream(false);
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(input)));

        IOException primary = assertThrows(
                IOException.class,
                () -> FastTextReader.readTracked(input));
        OwnedResourceStreams.closeCurrentAfterFailure(primary);

        assertEquals(1, input.closeCount);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void trackedFastCloseFailureStaysTrackedForRetry() {
        ReadFailingNonIdempotentCloseInputStream input =
                new ReadFailingNonIdempotentCloseInputStream(true);
        OwnedResourceStreams.enterPairStreams(
                List.of(new PublicPair(input)));

        IOException primary = assertThrows(
                IOException.class,
                () -> FastTextReader.readTracked(input));
        OwnedResourceStreams.closeCurrentAfterFailure(primary);

        assertEquals("close", primary.getMessage());
        assertEquals(2, input.closeCount);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void successfulPartialOpenReleaseKeepsOwnershipWithTheCaller() {
        TrackingInputStream input = new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.enterPartialOpen();

        assertSame(
                input,
                OwnedResourceStreams.trackPartialOpenStream(input));
        OwnedResourceStreams.releasePartialOpen();

        assertEquals(0, input.closeCount);
        assertThrows(
                IllegalStateException.class,
                OwnedResourceStreams::releasePartialOpen);
    }

    @Test
    void partialOpenFailureClosesIdentityDistinctStreamsOnce() {
        IOException closeFailure = new IOException("close-one");
        AssertionError fatalCloseFailure = new AssertionError("close-two");
        FailingCloseInputStream first =
                new FailingCloseInputStream(closeFailure);
        FailingCloseInputStream second =
                new FailingCloseInputStream(fatalCloseFailure);
        OwnedResourceStreams.enterPartialOpen();
        OwnedResourceStreams.trackPartialOpenStream(first);
        OwnedResourceStreams.trackPartialOpenStream(second);
        OwnedResourceStreams.trackPartialOpenStream(first);
        OutOfMemoryError primary = new OutOfMemoryError("Pair allocation");

        OwnedResourceStreams.closePartialOpenAfterFailure(primary);

        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
        assertArrayEquals(
                new Throwable[]{closeFailure, fatalCloseFailure},
                primary.getSuppressed());
    }

    @Test
    void nestedPartialOpenScopesAreIsolated() {
        TrackingInputStream outer = new TrackingInputStream(new byte[0]);
        TrackingInputStream inner = new TrackingInputStream(new byte[0]);
        OwnedResourceStreams.enterPartialOpen();
        OwnedResourceStreams.trackPartialOpenStream(outer);
        OwnedResourceStreams.enterPartialOpen();
        OwnedResourceStreams.trackPartialOpenStream(inner);

        OwnedResourceStreams.releasePartialOpen();
        RuntimeException primary = new RuntimeException("outer open");
        OwnedResourceStreams.closePartialOpenAfterFailure(primary);

        assertEquals(1, outer.closeCount);
        assertEquals(0, inner.closeCount);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void trackingWithoutAScopeClosesTheUnownedCurrentStream() {
        TrackingInputStream input = new TrackingInputStream(new byte[0]);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OwnedResourceStreams.trackPartialOpenStream(input));

        assertTrue(failure.getMessage().contains("partial-open"));
        assertEquals(1, input.closeCount);
    }

    @Test
    void malformedPairShapeFailsBeforeReturningAnIncompleteScope() {
        TrackingInputStream captured = new TrackingInputStream(new byte[0]);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OwnedResourceStreams.capturePairStreams(List.of(
                        new PublicPair(captured), new Object())));

        assertTrue(failure.getMessage().contains("two"));
        assertEquals(1, captured.closeCount);
        assertFalse(failure.getSuppressed().length > 0);
    }

    @Test
    void fatalPairIterationClosesAlreadyCapturedStreamsBeforeRethrow() {
        TrackingInputStream captured = new TrackingInputStream(new byte[0]);
        AssertionError iterationFailure = new AssertionError("iterator");
        Iterable<Object> pairs = () -> new Iterator<>() {
            private boolean first = true;

            @Override
            public boolean hasNext() {
                if (first) {
                    return true;
                }
                throw iterationFailure;
            }

            @Override
            public Object next() {
                first = false;
                return new PublicPair(captured);
            }
        };
        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> OwnedResourceStreams.capturePairStreams(pairs));

        assertSame(iterationFailure, actual);
        assertEquals(1, captured.closeCount);
    }

    public static final class PublicPair {
        public Object two;

        PublicPair(Object two) {
            this.two = two;
        }
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        int closeCount;

        TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            super.close();
        }
    }

    private static final class FailingCloseInputStream extends InputStream {
        private final Throwable failure;
        private int closeCount;

        private FailingCloseInputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure);
        }
    }

    private static final class NonIdempotentCloseInputStream
            extends InputStream {
        private int closeCount;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCount++;
            if (closeCount > 1) {
                throw new AssertionError("stream closed twice");
            }
        }
    }

    private static final class FailOnceCloseInputStream extends InputStream {
        private int closeCount;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("first close");
            }
        }
    }

    private static final class ReadFailingNonIdempotentCloseInputStream
            extends InputStream {
        private final boolean failFirstClose;
        private int closeCount;

        private ReadFailingNonIdempotentCloseInputStream(
                boolean failFirstClose) {
            this.failFirstClose = failFirstClose;
        }

        @Override
        public int read() throws IOException {
            throw new IOException("read");
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            throw new IOException("read");
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("close");
            }
            if (closeCount > (failFirstClose ? 2 : 1)) {
                throw new AssertionError("stream closed too many times");
            }
        }
    }
}

package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class CsvErrorFormatterTest {
    @Test
    void returnsNullWithoutInvokingAFormatter() {
        assertNull(CsvErrorFormatter.formatLastRow(null));
    }

    @Test
    void invokesPrettyPrinterWithIndentTwoOnlyOnDemand() {
        FormattingRow row = new FormattingRow();

        String actual = CsvErrorFormatter.formatLastRow(row);

        assertEquals("formatted:2", actual);
        assertEquals(1, row.calls);
        assertEquals(2, row.lastIndent);
    }

    @Test
    void rethrowsRuntimeFailureFromPrettyPrinterWithoutWrapping() {
        IllegalArgumentException failure =
                new IllegalArgumentException("bad row");
        ThrowingRow row = new ThrowingRow(failure);

        IllegalArgumentException actual = assertThrows(
                IllegalArgumentException.class,
                () -> CsvErrorFormatter.formatLastRow(row));

        assertSame(failure, actual);
    }

    @Test
    void rethrowsCheckedFailureFromPrettyPrinterWithoutWrapping() {
        IOException failure = new IOException("bad row");
        CheckedThrowingRow row = new CheckedThrowingRow(failure);

        IOException actual = assertThrows(
                IOException.class,
                () -> CsvErrorFormatter.formatLastRow(row));

        assertSame(failure, actual);
    }

    @Test
    void wrapsReflectionContractFailures() {
        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> CsvErrorFormatter.formatLastRow(new Object()));

        assertEquals(
                "Unable to format the last parsed CSV row",
                actual.getMessage());
        assertEquals(NoSuchMethodException.class, actual.getCause().getClass());
    }

    public static final class FormattingRow {
        private int calls;
        private int lastIndent = -1;

        public String toString(int indent) {
            calls++;
            lastIndent = indent;
            return "formatted:" + indent;
        }
    }

    public static final class ThrowingRow {
        private final RuntimeException failure;

        private ThrowingRow(RuntimeException failure) {
            this.failure = failure;
        }

        public String toString(int indent) {
            throw failure;
        }
    }

    public static final class CheckedThrowingRow {
        private final IOException failure;

        private CheckedThrowingRow(IOException failure) {
            this.failure = failure;
        }

        public String toString(int indent) throws IOException {
            throw failure;
        }
    }
}

package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class CsvMergeOptimizerTest {
    @Test
    void growingPrefixInsertionKeepsBaseRowsBeforeExistingModRows() {
        List<String> rows = CsvMergeOptimizer.baseFirstRows();
        rows.add("mod-a");
        rows.add("mod-b");

        rows.add(0, "base-a");
        rows.add(1, "base-b");

        assertEquals(
                List.of("base-a", "base-b", "mod-a", "mod-b"),
                rows);
    }

    @Test
    void ordinaryAppendAndIndexedInsertionKeepListSemantics() {
        List<String> rows = CsvMergeOptimizer.baseFirstRows();
        rows.add("a");
        rows.add("c");
        rows.add(1, "b");
        rows.add(3, "d");

        assertEquals(List.of("a", "b", "c", "d"), rows);
    }

    @Test
    void duplicateKeyMovesReplacementToEnd() {
        List<Row> rows = CsvMergeOptimizer.overrideRows();
        rows.add(new Row("a", "base-a"));
        rows.add(new Row("b", "base-b"));
        CsvMergeOptimizer.putMovingToEnd(
                rows, "id", "a", new Row("a", "mod-a"));

        assertEquals(
                List.of(new Row("b", "base-b"),
                        new Row("a", "mod-a")),
                rows);
    }

    @Test
    void repeatedOverridesRetainOnlyTheLastRowInExactOrder() {
        List<Row> rows = CsvMergeOptimizer.overrideRows();
        rows.add(new Row("a", "base-a"));
        rows.add(new Row("b", "base-b"));
        rows.add(new Row("c", "base-c"));
        put(rows, "b", "mod1-b");
        put(rows, "a", "mod2-a");
        put(rows, "b", "mod2-b");

        assertEquals(
                List.of(new Row("c", "base-c"),
                        new Row("a", "mod2-a"),
                        new Row("b", "mod2-b")),
                rows);
    }

    @Test
    void duplicateBaseKeysPreserveFirstMatchRemoval() {
        List<Row> rows = CsvMergeOptimizer.overrideRows();
        rows.add(new Row("a", "base-a1"));
        rows.add(new Row("a", "base-a2"));
        rows.add(new Row("b", "base-b"));

        put(rows, "a", "mod-a1");
        put(rows, "a", "mod-a2");

        assertEquals(
                List.of(new Row("b", "base-b"),
                        new Row("a", "mod-a1"),
                        new Row("a", "mod-a2")),
                rows);
    }

    @Test
    void keyedOperationRejectsAnUnrelatedList() {
        List<String> rows = new java.util.ArrayList<>();

        assertThrows(
                IllegalArgumentException.class,
                () -> CsvMergeOptimizer.putMovingToEnd(
                        rows, "id", "a", "row"));
    }

    @Test
    void randomizedOverridesMatchTheOriginalFirstMatchAlgorithm() {
        for (int seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            List<Row> expected = new ArrayList<>();
            List<Row> actual = CsvMergeOptimizer.overrideRows();
            for (int i = 0; i < 40; i++) {
                Row base = randomRow(random, "base-" + i);
                expected.add(base);
                actual.add(base);
            }
            for (int i = 0; i < 120; i++) {
                Row replacement = randomRow(random, "mod-" + i);
                for (int index = 0; index < expected.size(); index++) {
                    if (replacement.id().equals(
                            expected.get(index).id())) {
                        expected.remove(index);
                        break;
                    }
                }
                expected.add(replacement);
                CsvMergeOptimizer.putMovingToEnd(
                        actual,
                        "id",
                        replacement.id(),
                        replacement);
                assertEquals(expected, actual, "seed=" + seed
                        + ", step=" + i);
            }
        }
    }

    @Test
    void keyFailureIsNotObservedBeforeOriginalScanReachesIt() {
        List<Object> rows = CsvMergeOptimizer.overrideRows();
        rows.add(new Row("a", "first"));
        rows.add(new ExplodingRow());
        rows.add(new Row("b", "last"));

        CsvMergeOptimizer.putMovingToEnd(
                rows, "id", "a", new Row("a", "replacement"));

        assertThrows(
                ExpectedKeyFailure.class,
                () -> CsvMergeOptimizer.putMovingToEnd(
                        rows,
                        "id",
                        "b",
                        new Row("b", "unreached")));
        assertEquals(
                List.of(new ExplodingRow(), new Row("b", "last"),
                        new Row("a", "replacement")),
                rows);
    }

    private static void put(List<Row> rows, String key, String value) {
        CsvMergeOptimizer.putMovingToEnd(
                rows, "id", key, new Row(key, value));
    }

    private static Row randomRow(Random random, String value) {
        return new Row("key-" + random.nextInt(12), value);
    }

    public record Row(String id, String value) {
        public String getString(String column) {
            if (!"id".equals(column)) {
                throw new IllegalArgumentException(column);
            }
            return id;
        }
    }

    public record ExplodingRow() {
        public String getString(String column) {
            throw new ExpectedKeyFailure();
        }
    }

    private static final class ExpectedKeyFailure
            extends RuntimeException {
    }
}

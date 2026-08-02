package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class StableListPartitionTest {
    @Test
    void movesPrioritizedSubsequenceToFrontWithoutChangingEitherOrder() {
        Token first = new Token("first");
        Token priorityOne = new Token("priority-one");
        Token middle = new Token("middle");
        Token priorityTwo = new Token("priority-two");
        Token last = new Token("last");
        ArrayList<Token> values = new ArrayList<>(List.of(
                first, priorityOne, middle, priorityTwo, last));
        List<Token> prioritized = List.of(priorityOne, priorityTwo);

        StableListPartition.prioritizedSubsequenceFirst(
                values, prioritized);

        assertIterableEquals(
                List.of(priorityOne, priorityTwo, first, middle, last),
                values);
        assertSame(priorityOne, values.get(0));
        assertSame(priorityTwo, values.get(1));
    }

    @Test
    void handlesRepeatedReferencesInThePrioritizedSubsequence() {
        Token repeated = new Token("repeated");
        Token other = new Token("other");
        Token last = new Token("last");
        ArrayList<Token> values = new ArrayList<>(List.of(
                repeated, other, repeated, last));
        List<Token> prioritized = List.of(repeated, repeated);

        StableListPartition.prioritizedSubsequenceFirst(
                values, prioritized);

        assertIterableEquals(
                List.of(repeated, repeated, other, last), values);
        assertSame(repeated, values.get(0));
        assertSame(repeated, values.get(1));
    }

    @Test
    void leavesTheOriginalListInstanceUsableForEmptyAndFullPartitions() {
        Token first = new Token("first");
        Token second = new Token("second");
        ArrayList<Token> emptySelection =
                new ArrayList<>(List.of(first, second));
        ArrayList<Token> fullSelection =
                new ArrayList<>(List.of(first, second));

        StableListPartition.prioritizedSubsequenceFirst(
                emptySelection, List.of());
        StableListPartition.prioritizedSubsequenceFirst(
                fullSelection, List.of(first, second));

        assertIterableEquals(List.of(first, second), emptySelection);
        assertIterableEquals(List.of(first, second), fullSelection);
    }

    @Test
    void rejectsASelectionThatIsNotAnIdentitySubsequence() {
        Token first = new Token("same-value");
        Token equalLookingButDistinct = new Token("same-value");
        ArrayList<Token> values = new ArrayList<>(List.of(first));

        assertThrows(
                IllegalArgumentException.class,
                () -> StableListPartition.prioritizedSubsequenceFirst(
                        values, List.of(equalLookingButDistinct)));
        assertIterableEquals(List.of(first), values);
    }

    @Test
    void matchesOriginalRemoveAllThenAddAllForResourceLikeObjects() {
        Random random = new Random(0x10_2026L);
        ArrayList<Token> values = new ArrayList<>();
        ArrayList<Token> prioritized = new ArrayList<>();
        for (int index = 0; index < 4096; index++) {
            Token token = new Token("resource-" + index);
            values.add(token);
            if (random.nextInt(5) == 0) {
                prioritized.add(token);
            }
        }
        ArrayList<Token> expected = new ArrayList<>(values);
        expected.removeAll(prioritized);
        expected.addAll(0, prioritized);

        StableListPartition.prioritizedSubsequenceFirst(
                values, prioritized);

        assertIterableEquals(expected, values);
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), values.get(index));
        }
    }

    private record Token(String value) {
    }
}

package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class FontDefinitionCursorTest {
    private static final char[] RANDOM_CHARACTERS = {
        ' ', '\t', '\n', '\u000B', '\f', '\r',
        '=', '"', '-', '+', '0', '1', '9', 'a', 'Z',
        '\u2003', '\u3000', '\u4e2d'
    };

    @Test
    void matchesOriginalSequentialStringParsingAndCursorAdvance() {
        List<String> lines = List.of(
                "char id=65 x=1 y=2",
                "char  id=65\t x=1  ",
                "info face=\"Victor Sans\" size=-16",
                "char missing x=1",
                "char a=b=c a==b a=b= a===c",
                "no-space",
                "",
                " ",
                "chars    ",
                "char\u2003id=65\u3000x=1");

        for (String line : lines) {
            assertStringSequence(line, false);
            assertStringSequence(line, true);
        }
    }

    @Test
    void reusesAnExistingCursorAndResetsAllLineState() {
        FontDefinitionCursor cursor =
                FontDefinitionCursor.fromLine("char id=1 x=2");
        assertEquals(1, cursor.nextInt());

        FontDefinitionCursor reused = FontDefinitionCursor.reset(
                cursor, "char  id=3");

        assertSame(cursor, reused);
        assertSameOutcome(
                () -> referenceString("", false),
                () -> reused.nextString(false),
                "leading empty token after reset");
        assertEquals(3, reused.nextInt());
    }

    @Test
    void failedResetRewindsPreviousCursorLikeOriginalStaticIndex() {
        FontDefinitionCursor cursor =
                FontDefinitionCursor.fromLine("char id=1 x=2");
        assertEquals(1, cursor.nextInt());

        assertThrows(
                NullPointerException.class,
                () -> FontDefinitionCursor.reset(cursor, null));

        assertEquals(1, cursor.nextInt());
    }

    @Test
    void matchesEveryTokenInEveryBundledFontDefinition()
            throws IOException {
        Path fonts = Path.of("..", "localization", "graphics", "fonts");
        List<Path> definitions;
        try (Stream<Path> paths = Files.list(fonts)) {
            definitions = paths
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".fnt"))
                    .sorted()
                    .toList();
        }
        assertEquals(11, definitions.size());

        for (Path definition : definitions) {
            try (Stream<String> lines = Files.lines(definition)) {
                lines.forEach(line -> {
                    String[] tokens = referenceTokens(line);
                    FontDefinitionCursor cursor =
                            FontDefinitionCursor.fromLine(line);
                    for (String token : tokens) {
                        assertEquals(
                                referenceString(token, false),
                                cursor.nextString(false),
                                () -> definition.getFileName()
                                        + ": " + line);
                    }
                    assertSameOutcome(
                            () -> tokens[tokens.length],
                            () -> cursor.nextString(false),
                            definition.getFileName() + ": exhausted " + line);
                });
            }
        }
    }

    @Test
    void matchesOriginalIntegerParsingWithoutChangingFailureTypes() {
        List<String> tokens = List.of(
                "id=0",
                "id=-1",
                "id=+1",
                "id=2147483647",
                "id=-2147483648",
                "id=2147483648",
                "id=-2147483649",
                "id=",
                "missing",
                "=65",
                "id=65=ignored",
                "id==65",
                "id= 65",
                "id=\u4e2d");

        for (String token : tokens) {
            FontDefinitionCursor cursor =
                    FontDefinitionCursor.fromLine("char " + token);
            assertSameOutcome(
                    () -> referenceFirstInt("char " + token),
                    cursor::nextInt,
                    "token=" + escape(token));
        }
    }

    @Test
    void randomizedDifferentialComparisonPreservesBoundaries() {
        Random random = new Random(0xC012_2026L);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            String line = randomText(random, 80);
            assertStringSequence(line, random.nextBoolean());

            String token = randomText(random, 32);
            FontDefinitionCursor cursor =
                    FontDefinitionCursor.fromLine("char " + token);
            assertSameOutcome(
                    () -> referenceFirstInt("char " + token),
                    cursor::nextInt,
                    "int token=" + escape(token));
        }
    }

    private static void assertStringSequence(
            String line, boolean stripQuotes) {
        String[] tokens = referenceTokens(line);
        FontDefinitionCursor cursor = FontDefinitionCursor.fromLine(line);
        for (int index = 0; index < tokens.length + 2; index++) {
            int tokenIndex = index;
            assertSameOutcome(
                    () -> referenceString(
                            tokens[tokenIndex], stripQuotes),
                    () -> cursor.nextString(stripQuotes),
                    "line=" + escape(line) + ", index=" + index
                            + ", stripQuotes=" + stripQuotes);
        }
    }

    private static String[] referenceTokens(String line) {
        String content = line.substring(line.indexOf(" ") + 1);
        content = content.replaceAll("\\s+", " ");
        return content.split(" ");
    }

    private static int referenceInt(String token) {
        return Integer.parseInt(token.split("=")[1]);
    }

    private static int referenceFirstInt(String line) {
        return referenceInt(referenceTokens(line)[0]);
    }

    private static String referenceString(
            String token, boolean stripQuotes) {
        String value = token.split("=")[1];
        return stripQuotes ? value.replaceAll("\"", "") : value;
    }

    private static void assertSameOutcome(
            ThrowingSupplier expected,
            ThrowingSupplier actual,
            String context) {
        assertEquals(capture(expected), capture(actual), context);
    }

    private static Outcome capture(ThrowingSupplier supplier) {
        try {
            return new Outcome(supplier.get(), null);
        } catch (RuntimeException exception) {
            return new Outcome(null, exception.getClass());
        }
    }

    private static String randomText(Random random, int maxLength) {
        int length = random.nextInt(maxLength + 1);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(RANDOM_CHARACTERS[
                    random.nextInt(RANDOM_CHARACTERS.length)]);
        }
        return result.toString();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\u000B", "\\v")
                .replace("\f", "\\f");
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get();
    }

    private record Outcome(
            Object value, Class<? extends RuntimeException> failure) {
    }
}

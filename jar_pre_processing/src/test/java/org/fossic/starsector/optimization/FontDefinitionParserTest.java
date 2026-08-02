package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class FontDefinitionParserTest {
    private static final char[] RANDOM_CHARACTERS = {
        ' ', '\t', '\n', '\u000B', '\f', '\r',
        '=', '"', '-', '+', '0', '1', '9', 'a', 'Z',
        '\u2003', '\u3000', '\u4e2d'
    };

    @Test
    void matchesTheOriginalTokenizerOnRepresentativeLines() {
        List<String> lines = List.of(
                "char id=65 x=1 y=2",
                "char  id=65\t x=1  ",
                "char\tid=65\u000Bx=1\fy=2\r",
                "info face=\"Victor\" size=-16",
                "info face=\"name with spaces\" size=16",
                "no-space",
                "",
                " ",
                "chars    ",
                "char\u2003id=65\u3000x=1");

        for (String line : lines) {
            assertArrayEquals(
                    referenceTokens(line),
                    FontDefinitionParser.tokenizeAfterKeyword(line),
                    () -> "line=" + escape(line));
        }
    }

    @Test
    void matchesTheOriginalTokenizerForEveryBundledFontLine()
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
                lines.forEach(line -> assertArrayEquals(
                        referenceTokens(line),
                        FontDefinitionParser.tokenizeAfterKeyword(line),
                        () -> definition.getFileName() + ": " + line));
            }
        }
    }

    @Test
    void matchesOriginalValueParsingEdgeCases() {
        List<String> tokens = List.of(
                "id=65",
                "x=-1",
                "value=",
                "missing",
                "=leading",
                "a=b=c",
                "a==b",
                "a==",
                "a=b=",
                "a===c",
                "face=\"Victor\"",
                "face=\"a\"\"b\"");

        for (String token : tokens) {
            assertSameOutcome(
                    () -> referenceInt(token),
                    () -> FontDefinitionParser.parseIntToken(token),
                    "int token=" + escape(token));
            for (boolean stripQuotes : List.of(false, true)) {
                assertSameOutcome(
                        () -> referenceString(token, stripQuotes),
                        () -> FontDefinitionParser.parseStringToken(
                                token, stripQuotes),
                        "string token=" + escape(token)
                                + ", stripQuotes=" + stripQuotes);
            }
        }
    }

    @Test
    void randomizedDifferentialComparisonPreservesGameSemantics() {
        Random random = new Random(0x5A17_2026L);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            String line = randomText(random, 80);
            assertArrayEquals(
                    referenceTokens(line),
                    FontDefinitionParser.tokenizeAfterKeyword(line),
                    () -> "line=" + escape(line));

            String token = randomText(random, 32);
            assertSameOutcome(
                    () -> referenceInt(token),
                    () -> FontDefinitionParser.parseIntToken(token),
                    "int token=" + escape(token));
            boolean stripQuotes = random.nextBoolean();
            assertSameOutcome(
                    () -> referenceString(token, stripQuotes),
                    () -> FontDefinitionParser.parseStringToken(
                            token, stripQuotes),
                    "string token=" + escape(token)
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

    private static String referenceString(
            String token, boolean stripQuotes) {
        String value = token.split("=")[1];
        return stripQuotes ? value.replaceAll("\"", "") : value;
    }

    private static void assertSameOutcome(
            ThrowingSupplier expected,
            ThrowingSupplier actual,
            String context) {
        Outcome expectedOutcome = capture(expected);
        Outcome actualOutcome = capture(actual);
        assertEquals(expectedOutcome, actualOutcome, context);
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

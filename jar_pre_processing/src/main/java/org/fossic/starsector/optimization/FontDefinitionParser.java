package org.fossic.starsector.optimization;

/**
 * 低分配解析 AngelCode BMFont 文本定义。
 *
 * <p>这些方法逐项复现原版字体加载器的 {@code substring + replaceAll +
 * split} 语义，包括 ASCII {@code \s}、开头空 token、丢弃末尾空 token，
 * 以及 {@code token.split("=")[1]} 的异常边界。
 */
public final class FontDefinitionParser {
    private FontDefinitionParser() {
    }

    public static String[] tokenizeAfterKeyword(String line) {
        int length = line.length();
        int contentStart = line.indexOf(' ') + 1;
        if (contentStart == length) {
            return new String[]{""};
        }

        int cursor = contentStart;
        int tokenCount = 0;
        if (isAsciiRegexWhitespace(line.charAt(cursor))) {
            tokenCount++;
            cursor = skipWhitespace(line, cursor, length);
            if (cursor == length) {
                return new String[0];
            }
        }

        while (cursor < length) {
            tokenCount++;
            cursor = skipToken(line, cursor, length);
            cursor = skipWhitespace(line, cursor, length);
        }

        String[] tokens = new String[tokenCount];
        cursor = contentStart;
        int output = 0;
        if (isAsciiRegexWhitespace(line.charAt(cursor))) {
            tokens[output++] = "";
            cursor = skipWhitespace(line, cursor, length);
        }
        while (cursor < length) {
            int tokenStart = cursor;
            cursor = skipToken(line, cursor, length);
            tokens[output++] = line.substring(tokenStart, cursor);
            cursor = skipWhitespace(line, cursor, length);
        }
        return tokens;
    }

    public static int parseIntToken(String token) {
        return Integer.parseInt(valueAfterFirstEquals(token));
    }

    public static String parseStringToken(
            String token, boolean stripQuotes) {
        String value = valueAfterFirstEquals(token);
        return stripQuotes ? value.replace("\"", "") : value;
    }

    private static String valueAfterFirstEquals(String token) {
        int firstEquals = token.indexOf('=');
        if (firstEquals < 0) {
            throw missingValue();
        }

        int valueStart = firstEquals + 1;
        int secondEquals = token.indexOf('=', valueStart);
        if (secondEquals < 0) {
            if (valueStart == token.length()) {
                throw missingValue();
            }
            return token.substring(valueStart);
        }
        if (secondEquals > valueStart) {
            return token.substring(valueStart, secondEquals);
        }

        int laterValue = secondEquals + 1;
        while (laterValue < token.length()
                && token.charAt(laterValue) == '=') {
            laterValue++;
        }
        if (laterValue == token.length()) {
            throw missingValue();
        }
        return "";
    }

    private static ArrayIndexOutOfBoundsException missingValue() {
        return new ArrayIndexOutOfBoundsException(1);
    }

    private static int skipToken(
            String line, int cursor, int length) {
        while (cursor < length
                && !isAsciiRegexWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int skipWhitespace(
            String line, int cursor, int length) {
        while (cursor < length
                && isAsciiRegexWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isAsciiRegexWhitespace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\n'
                || character == '\u000B'
                || character == '\f'
                || character == '\r';
    }
}

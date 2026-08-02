package org.fossic.starsector.optimization;

/**
 * 顺序解析 AngelCode BMFont 行，不建立中间 token 数组或整数值子串。
 *
 * <p>边界逐项复现原版的 {@code substring + replaceAll("\\s+") +
 * split(" ")} 和 {@code token.split("=")[1]}。有效整数直接使用 JDK 的
 * {@link Integer#parseInt(CharSequence, int, int, int)}；失败时才建立子串并
 * 调用原入口，以保留原异常类型和验证规则。
 */
public final class FontDefinitionCursor {
    private String line;
    private int length;
    private int cursor;
    private boolean pendingEmptyToken;

    private FontDefinitionCursor() {
    }

    public static FontDefinitionCursor fromLine(String line) {
        return reset(null, line);
    }

    public static FontDefinitionCursor reset(
            FontDefinitionCursor existing, String line) {
        FontDefinitionCursor result = existing == null
                ? new FontDefinitionCursor()
                : existing;
        if (existing != null) {
            // 原方法在建立新 token 数组前先把独立的静态索引写成 0；即使
            // 随后的 line 解引用失败，旧数组也会从头重新读取。
            result.initialize(result.line, result.length);
        }
        int lineLength = line.length();
        result.initialize(line, lineLength);
        return result;
    }

    private void initialize(String newLine, int newLength) {
        line = newLine;
        length = newLength;
        int contentStart = newLine.indexOf(' ') + 1;
        cursor = contentStart;
        pendingEmptyToken = false;

        if (contentStart == newLength) {
            pendingEmptyToken = true;
        } else if (isAsciiRegexWhitespace(newLine.charAt(contentStart))) {
            cursor = skipWhitespace(contentStart);
            pendingEmptyToken = cursor < newLength;
        }
    }

    public int nextInt() {
        long value = nextValueRange();
        int start = rangeStart(value);
        int end = rangeEnd(value);
        try {
            return Integer.parseInt(line, start, end, 10);
        } catch (NumberFormatException invalid) {
            // 只在错误路径分配，复现 String 入口的验证与异常行为。
            return Integer.parseInt(line.substring(start, end));
        }
    }

    public String nextString(boolean stripQuotes) {
        long value = nextValueRange();
        int start = rangeStart(value);
        int end = rangeEnd(value);
        if (!stripQuotes) {
            return line.substring(start, end);
        }

        int quote = line.indexOf('"', start);
        if (quote < 0 || quote >= end) {
            return line.substring(start, end);
        }
        StringBuilder result = new StringBuilder(end - start);
        for (int index = start; index < end; index++) {
            char character = line.charAt(index);
            if (character != '"') {
                result.append(character);
            }
        }
        return result.toString();
    }

    private long nextValueRange() {
        long token = nextTokenRange();
        int tokenStart = rangeStart(token);
        int tokenEnd = rangeEnd(token);
        int firstEquals = line.indexOf('=', tokenStart);
        if (firstEquals < 0 || firstEquals >= tokenEnd) {
            throw missingValue();
        }

        int valueStart = firstEquals + 1;
        int secondEquals = line.indexOf('=', valueStart);
        if (secondEquals < 0 || secondEquals >= tokenEnd) {
            if (valueStart == tokenEnd) {
                throw missingValue();
            }
            return range(valueStart, tokenEnd);
        }
        if (secondEquals > valueStart) {
            return range(valueStart, secondEquals);
        }

        int laterValue = secondEquals + 1;
        while (laterValue < tokenEnd
                && line.charAt(laterValue) == '=') {
            laterValue++;
        }
        if (laterValue == tokenEnd) {
            throw missingValue();
        }
        return range(valueStart, valueStart);
    }

    private long nextTokenRange() {
        if (pendingEmptyToken) {
            pendingEmptyToken = false;
            return range(cursor, cursor);
        }
        if (cursor >= length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        int start = cursor;
        while (cursor < length
                && !isAsciiRegexWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        int end = cursor;
        cursor = skipWhitespace(cursor);
        return range(start, end);
    }

    private int skipWhitespace(int start) {
        int result = start;
        while (result < length
                && isAsciiRegexWhitespace(line.charAt(result))) {
            result++;
        }
        return result;
    }

    private static boolean isAsciiRegexWhitespace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\n'
                || character == '\u000B'
                || character == '\f'
                || character == '\r';
    }

    private static ArrayIndexOutOfBoundsException missingValue() {
        return new ArrayIndexOutOfBoundsException(1);
    }

    private static long range(int start, int end) {
        return ((long) start << 32) | (end & 0xffff_ffffL);
    }

    private static int rangeStart(long range) {
        return (int) (range >>> 32);
    }

    private static int rangeEnd(long range) {
        return (int) range;
    }
}

package com.truthoriginem.starsector.preprocessing;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    public static String stringArray(List<String> values) {
        return values.stream().map(JsonUtil::quote).collect(Collectors.joining(", ", "[", "]"));
    }

    public static String stringMap(Map<String, String> values) {
        return stringMap(values, 2);
    }

    public static String stringMap(Map<String, String> values, int indent) {
        String child = " ".repeat(indent + 2);
        return values.entrySet().stream()
                .map(e -> child + quote(e.getKey()) + ": " + quote(e.getValue()))
                .collect(Collectors.joining(",\n", "{\n", "\n" + " ".repeat(indent) + "}"));
    }
}

/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.core.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free RFC 8259 JSON parser and serializer for {@code haikyo-core}.
 */
public final class JsonCodec {
    private static final int MAX_DEPTH = 64;

    private JsonCodec() {
    }

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        Parser p = new Parser(json);
        Object value = p.parseValue(0);
        p.skipWs();
        if (p.pos != p.len) {
            throw new IllegalArgumentException("Trailing characters at index " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object val = parse(json);
        if (!(val instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    public static String stringifyPretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Canonical scalar formatting shared with Rust {@code haikyo-link} and Python vectors
     * for {@code integrity_report} HMAC computation:
     * integers render without {@code .0}, decimals strip trailing zeros, booleans render
     * {@code true}/{@code false}, strings render raw.
     */
    public static String formatCanonicalScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return Long.toString(((Number) value).longValue());
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                return "0";
            }
            if (d == 0.0) {
                return "0";
            }
            BigDecimal bd = BigDecimal.valueOf(d).stripTrailingZeros();
            if (bd.scale() <= 0) {
                return bd.toPlainString();
            }
            return bd.toPlainString();
        }
        return value.toString();
    }

    public static String asString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultVal;
    }

    public static String asNullableString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    public static int asInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : defaultVal;
    }

    public static long asLong(Map<String, Object> map, String key, long defaultVal) {
        Object v = map.get(key);
        return v instanceof Number n ? n.longValue() : defaultVal;
    }

    public static double asDouble(Map<String, Object> map, String key, double defaultVal) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : defaultVal;
    }

    public static boolean asBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : defaultVal;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static List<String> asStringList(Map<String, Object> map, String key) {
        List<Object> raw = asList(map, key);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                appendIndent(sb, indent + 2);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 2);
            }
            sb.append('\n');
            appendIndent(sb, indent);
            sb.append('}');
        } else if (value instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : c) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                appendIndent(sb, indent + 2);
                writePretty(sb, item, indent + 2);
            }
            sb.append('\n');
            appendIndent(sb, indent);
            sb.append(']');
        } else {
            writeValue(sb, value);
        }
    }

    private static void appendIndent(StringBuilder sb, int spaces) {
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            sb.append(n.longValue());
        } else {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        final int len;
        int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
        }

        void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting depth exceeded");
            }
            skipWs();
            if (pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at index " + pos);
                }
            };
        }

        Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= len || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected object key string at index " + pos);
                }
                String key = parseString();
                skipWs();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at index " + pos);
                }
                pos++;
                Object val = parseValue(depth);
                map.put(key, val);
                skipWs();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated JSON object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at index " + (pos - 1));
                }
            }
        }

        List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth));
                skipWs();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated JSON array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at index " + (pos - 1));
                }
            }
        }

        String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Truncated \\u escape");
                            }
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("Unescaped control char in JSON string");
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        Object parseLiteral(String literal, Object value) {
            if (s.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw new IllegalArgumentException("Expected '" + literal + "' at index " + pos);
        }

        Number parseNumber() {
            int start = pos;
            boolean isFloat = false;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= len || !Character.isDigit(s.charAt(pos))) {
                throw new IllegalArgumentException("Invalid number at index " + start);
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < len && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < len && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= len || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid fraction at index " + pos);
                }
                while (pos < len && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= len || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent at index " + pos);
                }
                while (pos < len && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String num = s.substring(start, pos);
            if (!isFloat) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException ignored) {
                    return Double.parseDouble(num);
                }
            }
            return Double.parseDouble(num);
        }
    }
}

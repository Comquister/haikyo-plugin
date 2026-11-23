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
import java.util.Collections;
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
        if (!(val instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) val;
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

    public static String formatCanonicalScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "true" : "false";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return Long.toString(((Number) value).longValue());
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d == 0.0) {
                return "0";
            }
            BigDecimal bd = BigDecimal.valueOf(d).stripTrailingZeros();
            return bd.toPlainString();
        }
        return value.toString();
    }

    public static String asString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : defaultVal;
    }

    public static String asNullableString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    public static int asInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    public static long asLong(Map<String, Object> map, String key, long defaultVal) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : defaultVal;
    }

    public static double asDouble(Map<String, Object> map, String key, double defaultVal) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultVal;
    }

    public static boolean asBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : defaultVal;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List<?> ? (List<Object>) v : Collections.<Object>emptyList();
    }

    public static List<String> asStringList(Map<String, Object> map, String key) {
        List<Object> raw = asList(map, key);
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(raw.size());
        for (Object item : raw) {
            if (item instanceof String) {
                out.add((String) item);
            }
        }
        return out;
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            writeNumber(sb, (Number) value);
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) value;
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
        } else if (value instanceof Collection<?>) {
            Collection<?> c = (Collection<?>) value;
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
        if (value instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) value;
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
        } else if (value instanceof Collection<?>) {
            Collection<?> c = (Collection<?>) value;
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
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
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
            switch (c) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    return parseLiteral("true", Boolean.TRUE);
                case 'f':
                    return parseLiteral("false", Boolean.FALSE);
                case 'n':
                    return parseLiteral("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at index " + pos);
            }
        }

        Map<String, Object> parseObject(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
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
            pos++;
            List<Object> list = new ArrayList<Object>();
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
            pos++;
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
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Truncated \\u escape");
                            }
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
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

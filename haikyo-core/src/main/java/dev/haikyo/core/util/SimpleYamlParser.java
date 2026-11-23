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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free indented YAML subset parser supporting nested mappings, sequences,
 * inline lists/maps, comments, quoted strings, numbers, and booleans.
 * Used identically across Paper, Folia, Velocity, and pure-Java unit tests.
 */
public final class SimpleYamlParser {
    private SimpleYamlParser() {
    }

    public static Map<String, Object> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new LinkedHashMap<>();
        }
        List<Line> lines = preprocess(yaml);
        if (lines.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Cursor c = new Cursor(lines);
        Object parsed = parseBlock(c, lines.get(0).indent);
        if (parsed instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m;
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static Object parseBlock(Cursor c, int baseIndent) {
        if (!c.hasNext()) {
            return new LinkedHashMap<>();
        }
        Line first = c.peek();
        if (first.text.startsWith("- ") || first.text.equals("-")) {
            return parseList(c, baseIndent);
        }
        return parseMap(c, baseIndent);
    }

    private static List<Object> parseList(Cursor c, int baseIndent) {
        List<Object> list = new ArrayList<>();
        while (c.hasNext()) {
            Line line = c.peek();
            if (line.indent < baseIndent) {
                break;
            }
            if (line.indent > baseIndent) {
                break;
            }
            if (!line.text.startsWith("- ") && !line.text.equals("-")) {
                break;
            }
            c.next();
            String rest = line.text.equals("-") ? "" : line.text.substring(2).trim();
            if (rest.isEmpty()) {
                if (c.hasNext() && c.peek().indent > baseIndent) {
                    list.add(parseBlock(c, c.peek().indent));
                } else {
                    list.add(null);
                }
            } else {
                int colon = findMappingColon(rest);
                if (colon > 0) {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    String k = unquote(rest.substring(0, colon).trim());
                    String v = rest.substring(colon + 1).trim();
                    int itemIndent = line.indent + 2;
                    if (v.isEmpty()) {
                        if (c.hasNext() && c.peek().indent > itemIndent) {
                            itemMap.put(k, parseBlock(c, c.peek().indent));
                        } else {
                            itemMap.put(k, new LinkedHashMap<>());
                        }
                    } else {
                        itemMap.put(k, parseScalar(v));
                    }
                    if (c.hasNext() && c.peek().indent >= itemIndent) {
                        Map<String, Object> more = parseMap(c, c.peek().indent);
                        itemMap.putAll(more);
                    }
                    list.add(itemMap);
                } else {
                    list.add(parseScalar(rest));
                }
            }
        }
        return list;
    }

    private static Map<String, Object> parseMap(Cursor c, int baseIndent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (c.hasNext()) {
            Line line = c.peek();
            if (line.indent < baseIndent) {
                break;
            }
            if (line.indent > baseIndent) {
                break;
            }
            if (line.text.startsWith("- ") || line.text.equals("-")) {
                break;
            }
            int colon = findMappingColon(line.text);
            if (colon < 0) {
                c.next();
                continue;
            }
            c.next();
            String key = unquote(line.text.substring(0, colon).trim());
            String val = line.text.substring(colon + 1).trim();
            if (val.isEmpty()) {
                if (c.hasNext() && c.peek().indent > baseIndent) {
                    map.put(key, parseBlock(c, c.peek().indent));
                } else {
                    map.put(key, new LinkedHashMap<>());
                }
            } else {
                map.put(key, parseScalar(val));
            }
        }
        return map;
    }

    private static Object parseScalar(String raw) {
        if (raw.equals("[]")) {
            return new ArrayList<>();
        }
        if (raw.equals("{}")) {
            return new LinkedHashMap<>();
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            String inner = raw.substring(1, raw.length() - 1).trim();
            List<Object> list = new ArrayList<>();
            if (!inner.isEmpty()) {
                for (String part : splitTopLevel(inner, ',')) {
                    list.add(parseScalar(part.trim()));
                }
            }
            return list;
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            String inner = raw.substring(1, raw.length() - 1).trim();
            Map<String, Object> map = new LinkedHashMap<>();
            if (!inner.isEmpty()) {
                for (String part : splitTopLevel(inner, ',')) {
                    int col = findMappingColon(part.trim());
                    if (col > 0) {
                        String k = unquote(part.trim().substring(0, col).trim());
                        String v = part.trim().substring(col + 1).trim();
                        map.put(k, parseScalar(v));
                    }
                }
            }
            return map;
        }
        if ((raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2)
                || (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2)) {
            return unquote(raw);
        }
        if (raw.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (raw.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (raw.equalsIgnoreCase("null") || raw.equals("~")) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
        }
        return raw;
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && (i == 0 || s.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                } else if (c == sep && depth == 0) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int findMappingColon(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && (i == 0 || text.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && c == ':') {
                if (i + 1 == text.length() || text.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            String inner = s.substring(1, s.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }

    private static List<Line> preprocess(String yaml) {
        List<Line> out = new ArrayList<>();
        String[] rawLines = yaml.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : rawLines) {
            String stripped = stripComment(raw);
            if (stripped.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < stripped.length() && stripped.charAt(indent) == ' ') {
                indent++;
            }
            out.add(new Line(indent, stripped.substring(indent).trim()));
        }
        return out;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && (i == 0 || line.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && c == '#') {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private record Line(int indent, String text) {
    }

    private static final class Cursor {
        final List<Line> lines;
        int idx;

        Cursor(List<Line> lines) {
            this.lines = lines;
        }

        boolean hasNext() {
            return idx < lines.size();
        }

        Line peek() {
            return lines.get(idx);
        }

        Line next() {
            return lines.get(idx++);
        }
    }
}

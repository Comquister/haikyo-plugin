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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small Java 8 compatibility helpers replacing JDK 9-11 convenience methods so
 * {@code haikyo-core} and {@code haikyo-paper} compile cleanly to Java 8 bytecode
 * (running on Minecraft 1.8 through 26.3).
 */
public final class Java8Compat {
    private Java8Compat() {
    }

    public static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String readString(Path path, Charset charset) throws IOException {
        return new String(Files.readAllBytes(path), charset);
    }

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static Path writeString(Path path, CharSequence content, Charset charset, OpenOption... options) throws IOException {
        return Files.write(path, content.toString().getBytes(charset), options);
    }

    public static Path writeString(Path path, CharSequence content, OpenOption... options) throws IOException {
        return writeString(path, content, StandardCharsets.UTF_8, options);
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> List<T> listOf(T... items) {
        if (items == null || items.length == 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(items)));
    }

    public static <T> List<T> copyOfList(Collection<? extends T> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(items));
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> Set<T> setOf(T... items) {
        if (items == null || items.length == 0) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(items)));
    }

    public static <T> Set<T> copyOfSet(Collection<? extends T> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<T>(items));
    }

    public static <K, V> Map<K, V> copyOfMap(Map<? extends K, ? extends V> map) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(map));
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        return Collections.unmodifiableMap(m);
    }
}

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
package dev.haikyo.core.i18n;

import dev.haikyo.core.util.Java8Compat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Multi-language message formatter supporting {@code en_us}, {@code pt_br}, and {@code es_es}
 * with clean fallback to {@code en_us}.
 */
public final class Localization {
    public static final List<String> SUPPORTED_LOCALES = Java8Compat.listOf("en_us", "pt_br", "es_es");
    public static final String DEFAULT_LOCALE = "en_us";

    private final String defaultLocale;
    private final Map<String, Map<String, String>> bundles;

    public Localization(String defaultLocale, Map<String, Map<String, String>> bundles) {
        this.defaultLocale = normalizeLocale(defaultLocale);
        this.bundles = bundles;
    }

    public static Localization loadDefault(String defaultLocale) {
        return loadDefault(defaultLocale, null);
    }

    public static Localization loadDefault(String defaultLocale, Path overrideDir) {
        Map<String, Map<String, String>> bundles = new HashMap<String, Map<String, String>>();
        for (String loc : SUPPORTED_LOCALES) {
            Map<String, String> entries = new HashMap<String, String>();
            loadClasspathBundle("/lang/" + loc + ".properties", entries);
            if (overrideDir != null) {
                Path diskFile = overrideDir.resolve(loc + ".properties");
                if (Files.isRegularFile(diskFile)) {
                    loadDiskBundle(diskFile, entries);
                }
            }
            bundles.put(loc, Java8Compat.copyOfMap(entries));
        }
        return new Localization(defaultLocale, Java8Compat.copyOfMap(bundles));
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public String get(String key, Object... args) {
        return getForLocale(defaultLocale, key, args);
    }

    public String getForLocale(String locale, String key, Object... args) {
        String norm = normalizeLocale(locale);
        String template = lookup(norm, key);
        if (template == null && !norm.equals(defaultLocale)) {
            template = lookup(defaultLocale, key);
        }
        if (template == null && !DEFAULT_LOCALE.equals(norm)) {
            template = lookup(DEFAULT_LOCALE, key);
        }
        if (template == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        String formatted = template;
        for (int i = 0; i < args.length; i++) {
            formatted = formatted.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return formatted;
    }

    public static String normalizeLocale(String locale) {
        if (Java8Compat.isBlank(locale)) {
            return DEFAULT_LOCALE;
        }
        String lower = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (lower.startsWith("pt")) {
            return "pt_br";
        }
        if (lower.startsWith("es")) {
            return "es_es";
        }
        return "en_us";
    }

    private String lookup(String locale, String key) {
        Map<String, String> bundle = bundles.get(locale);
        return bundle == null ? null : bundle.get(key);
    }

    private static void loadClasspathBundle(String resourcePath, Map<String, String> target) {
        try (InputStream is = Localization.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return;
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            for (String name : props.stringPropertyNames()) {
                target.put(name, props.getProperty(name));
            }
        } catch (IOException ignored) {
        }
    }

    private static void loadDiskBundle(Path file, Map<String, String> target) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            for (String name : props.stringPropertyNames()) {
                target.put(name, props.getProperty(name));
            }
        } catch (IOException ignored) {
        }
    }
}

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
package dev.haikyo.core.crypto;

import java.util.Locale;

/**
 * Canonicalizes Minecraft server addresses consistently across Rust, Java, and Python:
 * trimmed, lowercase, trailing DNS dot stripped, default {@code :25565} appended when port is absent.
 */
public final class ServerAddressNormalizer {
    public static final int DEFAULT_PORT = 25565;

    private ServerAddressNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }

        // IPv6 bracketed form: [::1] or [::1]:25565
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                return s;
            }
            String host = s.substring(0, close + 1);
            String rest = s.substring(close + 1);
            if (rest.startsWith(":") && rest.length() > 1) {
                return host + rest;
            }
            return host + ":" + DEFAULT_PORT;
        }

        int lastColon = s.lastIndexOf(':');
        String host;
        String portStr = null;
        if (lastColon > 0 && s.indexOf(':') == lastColon) {
            host = s.substring(0, lastColon);
            portStr = s.substring(lastColon + 1);
        } else {
            host = s;
        }

        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return "";
        }
        if (portStr == null || portStr.isEmpty()) {
            return host + ":" + DEFAULT_PORT;
        }
        return host + ":" + portStr;
    }
}

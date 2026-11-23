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
package dev.haikyo.core.mods;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-mod server policy rule ({@code allow}, {@code limit} with arbitrary mod-declared params, or {@code block}).
 */
public record ModRule(Action action, Map<String, Object> params, String reason) {
    public enum Action {
        ALLOW,
        LIMIT,
        BLOCK;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Action fromWireName(String raw) {
            if (raw == null) {
                return ALLOW;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "block" -> BLOCK;
                case "limit" -> LIMIT;
                default -> ALLOW;
            };
        }
    }

    public ModRule {
        Objects.requireNonNull(action, "action");
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static ModRule allow() {
        return new ModRule(Action.ALLOW, Map.of(), null);
    }

    public static ModRule block(String reason) {
        return new ModRule(Action.BLOCK, Map.of(), reason);
    }

    public static ModRule limit(Map<String, Object> params, String reason) {
        return new ModRule(Action.LIMIT, params, reason);
    }
}

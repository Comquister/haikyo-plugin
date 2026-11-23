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
public final class ModRule {
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
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            if ("block".equals(lower)) {
                return BLOCK;
            }
            if ("limit".equals(lower)) {
                return LIMIT;
            }
            return ALLOW;
        }
    }

    private final Action action;
    private final Map<String, Object> params;
    private final String reason;

    public ModRule(Action action, Map<String, Object> params, String reason) {
        this.action = Objects.requireNonNull(action, "action");
        this.params = params == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(params));
        this.reason = reason;
    }

    public Action action() { return action; }
    public Map<String, Object> params() { return params; }
    public String reason() { return reason; }

    public static ModRule allow() {
        return new ModRule(Action.ALLOW, Collections.<String, Object>emptyMap(), null);
    }

    public static ModRule block(String reason) {
        return new ModRule(Action.BLOCK, Collections.<String, Object>emptyMap(), reason);
    }

    public static ModRule limit(Map<String, Object> params, String reason) {
        return new ModRule(Action.LIMIT, params, reason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModRule)) return false;
        ModRule that = (ModRule) o;
        return action == that.action
                && Objects.equals(params, that.params)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, params, reason);
    }

    @Override
    public String toString() {
        return "ModRule[action=" + action + ", params=" + params + ", reason=" + reason + "]";
    }
}

/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.core.trust;

import java.util.Locale;

/**
 * Whether an anticheat check runs unconditionally ({@link #ALWAYS}) or may be relaxed
 * for non-sampled {@link dev.haikyo.api.TrustLevel#ATTESTED} players ({@link #RELAXED}).
 */
public enum CheckPolicy {
    ALWAYS,
    RELAXED;

    public static CheckPolicy fromConfig(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("relaxed")) {
            return RELAXED;
        }
        return ALWAYS;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}

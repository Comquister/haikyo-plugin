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
package dev.haikyo.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of the latest verified {@code integrity_report} received from a Haikyo client.
 */
public record IntegritySnapshot(
        UUID playerId,
        long sequence,
        Instant reportTimestamp,
        Instant receivedAt,
        Map<String, ModState> mods,
        boolean agentControl,
        List<String> signals
) {
    /**
     * Reported state and declared parameters of a single client mod.
     */
    public record ModState(boolean enabled, Map<String, Object> params) {
        public ModState {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    public IntegritySnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reportTimestamp, "reportTimestamp");
        Objects.requireNonNull(receivedAt, "receivedAt");
        mods = mods == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}

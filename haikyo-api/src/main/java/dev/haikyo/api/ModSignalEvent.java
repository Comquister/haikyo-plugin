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
package dev.haikyo.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event emitted when a player triggers a mod-compliance or integrity signal.
 */
public record ModSignalEvent(
        UUID playerId,
        String modId,
        String signalCode,
        String detail,
        Instant timestamp
) {
    public ModSignalEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(signalCode, "signalCode");
        Objects.requireNonNull(timestamp, "timestamp");
        modId = modId == null ? "" : modId;
        detail = detail == null ? "" : detail;
    }
}

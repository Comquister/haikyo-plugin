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
import java.util.Objects;
import java.util.UUID;

/**
 * Event emitted when a player triggers a mod-compliance or integrity signal.
 */
public final class ModSignalEvent {
    private final UUID playerId;
    private final String modId;
    private final String signalCode;
    private final String detail;
    private final Instant timestamp;

    public ModSignalEvent(
            UUID playerId,
            String modId,
            String signalCode,
            String detail,
            Instant timestamp
    ) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.modId = modId == null ? "" : modId;
        this.signalCode = Objects.requireNonNull(signalCode, "signalCode");
        this.detail = detail == null ? "" : detail;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public UUID playerId() { return playerId; }
    public String modId() { return modId; }
    public String signalCode() { return signalCode; }
    public String detail() { return detail; }
    public Instant timestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModSignalEvent)) return false;
        ModSignalEvent that = (ModSignalEvent) o;
        return Objects.equals(playerId, that.playerId)
                && Objects.equals(modId, that.modId)
                && Objects.equals(signalCode, that.signalCode)
                && Objects.equals(detail, that.detail)
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, modId, signalCode, detail, timestamp);
    }

    @Override
    public String toString() {
        return "ModSignalEvent[playerId=" + playerId
                + ", modId=" + modId
                + ", signalCode=" + signalCode
                + ", detail=" + detail
                + ", timestamp=" + timestamp + "]";
    }
}

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
 * Event emitted whenever a player's Haikyo {@link TrustLevel} transitions.
 */
public final class TrustChangeEvent {
    private final UUID playerId;
    private final TrustLevel previous;
    private final TrustLevel current;
    private final String reason;
    private final Instant timestamp;

    public TrustChangeEvent(
            UUID playerId,
            TrustLevel previous,
            TrustLevel current,
            String reason,
            Instant timestamp
    ) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.previous = Objects.requireNonNull(previous, "previous");
        this.current = Objects.requireNonNull(current, "current");
        this.reason = reason == null ? "" : reason;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public UUID playerId() { return playerId; }
    public TrustLevel previous() { return previous; }
    public TrustLevel current() { return current; }
    public String reason() { return reason; }
    public Instant timestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrustChangeEvent)) return false;
        TrustChangeEvent that = (TrustChangeEvent) o;
        return Objects.equals(playerId, that.playerId)
                && previous == that.previous
                && current == that.current
                && Objects.equals(reason, that.reason)
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, previous, current, reason, timestamp);
    }

    @Override
    public String toString() {
        return "TrustChangeEvent[playerId=" + playerId
                + ", previous=" + previous
                + ", current=" + current
                + ", reason=" + reason
                + ", timestamp=" + timestamp + "]";
    }
}

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of the latest verified {@code integrity_report} received from a Haikyo client.
 */
public final class IntegritySnapshot {
    /**
     * Reported state and declared parameters of a single client mod.
     */
    public static final class ModState {
        private final boolean enabled;
        private final Map<String, Object> params;

        public ModState(boolean enabled, Map<String, Object> params) {
            this.enabled = enabled;
            this.params = params == null
                    ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(params));
        }

        public boolean enabled() { return enabled; }
        public Map<String, Object> params() { return params; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ModState)) return false;
            ModState modState = (ModState) o;
            return enabled == modState.enabled && Objects.equals(params, modState.params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, params);
        }

        @Override
        public String toString() {
            return "ModState[enabled=" + enabled + ", params=" + params + "]";
        }
    }

    private final UUID playerId;
    private final long sequence;
    private final Instant reportTimestamp;
    private final Instant receivedAt;
    private final Map<String, ModState> mods;
    private final boolean agentControl;
    private final List<String> signals;

    public IntegritySnapshot(
            UUID playerId,
            long sequence,
            Instant reportTimestamp,
            Instant receivedAt,
            Map<String, ModState> mods,
            boolean agentControl,
            List<String> signals
    ) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.sequence = sequence;
        this.reportTimestamp = Objects.requireNonNull(reportTimestamp, "reportTimestamp");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.mods = mods == null
                ? Collections.<String, ModState>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, ModState>(mods));
        this.agentControl = agentControl;
        this.signals = signals == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(signals));
    }

    public UUID playerId() { return playerId; }
    public long sequence() { return sequence; }
    public Instant reportTimestamp() { return reportTimestamp; }
    public Instant receivedAt() { return receivedAt; }
    public Map<String, ModState> mods() { return mods; }
    public boolean agentControl() { return agentControl; }
    public List<String> signals() { return signals; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntegritySnapshot)) return false;
        IntegritySnapshot that = (IntegritySnapshot) o;
        return sequence == that.sequence
                && agentControl == that.agentControl
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(reportTimestamp, that.reportTimestamp)
                && Objects.equals(receivedAt, that.receivedAt)
                && Objects.equals(mods, that.mods)
                && Objects.equals(signals, that.signals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, sequence, reportTimestamp, receivedAt, mods, agentControl, signals);
    }

    @Override
    public String toString() {
        return "IntegritySnapshot[playerId=" + playerId
                + ", sequence=" + sequence
                + ", agentControl=" + agentControl
                + ", signals=" + signals + "]";
    }
}

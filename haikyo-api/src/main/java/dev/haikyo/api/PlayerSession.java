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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of a connected player's Haikyo session.
 */
public record PlayerSession(
        UUID playerId,
        boolean haikyoClient,
        int protocolVersion,
        String clientVersion,
        String clientChannel,
        TrustLevel trustLevel,
        boolean sampledUnrelaxed,
        Set<String> negotiatedChannels,
        Map<String, String> modCapabilities,
        Set<String> consentedScopes,
        List<String> signals,
        Instant connectedAt,
        Instant attestedAt,
        Instant lastReportAt,
        String proxyId
) {
    public PlayerSession {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(trustLevel, "trustLevel");
        Objects.requireNonNull(connectedAt, "connectedAt");
        negotiatedChannels = negotiatedChannels == null ? Set.of() : Set.copyOf(negotiatedChannels);
        modCapabilities = modCapabilities == null ? Map.of() : Map.copyOf(modCapabilities);
        consentedScopes = consentedScopes == null ? Set.of() : Set.copyOf(consentedScopes);
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    /**
     * Returns whether anticheat checks marked {@code relaxed} may be relaxed for this player.
     */
    public boolean isRelaxedEligible() {
        return trustLevel == TrustLevel.ATTESTED && !sampledUnrelaxed;
    }
}

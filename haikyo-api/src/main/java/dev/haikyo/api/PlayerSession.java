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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of a connected player's Haikyo session.
 */
public final class PlayerSession {
    private final UUID playerId;
    private final boolean haikyoClient;
    private final int protocolVersion;
    private final String clientVersion;
    private final String clientChannel;
    private final TrustLevel trustLevel;
    private final boolean sampledUnrelaxed;
    private final Set<String> negotiatedChannels;
    private final Map<String, String> modCapabilities;
    private final Set<String> consentedScopes;
    private final List<String> signals;
    private final Instant connectedAt;
    private final Instant attestedAt;
    private final Instant lastReportAt;
    private final String proxyId;

    public PlayerSession(
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
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.haikyoClient = haikyoClient;
        this.protocolVersion = protocolVersion;
        this.clientVersion = clientVersion;
        this.clientChannel = clientChannel;
        this.trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
        this.sampledUnrelaxed = sampledUnrelaxed;
        this.negotiatedChannels = negotiatedChannels == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(negotiatedChannels));
        this.modCapabilities = modCapabilities == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(modCapabilities));
        this.consentedScopes = consentedScopes == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(consentedScopes));
        this.signals = signals == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(signals));
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        this.attestedAt = attestedAt;
        this.lastReportAt = lastReportAt;
        this.proxyId = proxyId;
    }

    public UUID playerId() { return playerId; }
    public boolean haikyoClient() { return haikyoClient; }
    public int protocolVersion() { return protocolVersion; }
    public String clientVersion() { return clientVersion; }
    public String clientChannel() { return clientChannel; }
    public TrustLevel trustLevel() { return trustLevel; }
    public boolean sampledUnrelaxed() { return sampledUnrelaxed; }
    public Set<String> negotiatedChannels() { return negotiatedChannels; }
    public Map<String, String> modCapabilities() { return modCapabilities; }
    public Set<String> consentedScopes() { return consentedScopes; }
    public List<String> signals() { return signals; }
    public Instant connectedAt() { return connectedAt; }
    public Instant attestedAt() { return attestedAt; }
    public Instant lastReportAt() { return lastReportAt; }
    public String proxyId() { return proxyId; }

    /**
     * Returns whether anticheat checks marked {@code relaxed} may be relaxed for this player.
     */
    public boolean isRelaxedEligible() {
        return trustLevel == TrustLevel.ATTESTED && !sampledUnrelaxed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerSession)) return false;
        PlayerSession that = (PlayerSession) o;
        return haikyoClient == that.haikyoClient
                && protocolVersion == that.protocolVersion
                && sampledUnrelaxed == that.sampledUnrelaxed
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(clientVersion, that.clientVersion)
                && Objects.equals(clientChannel, that.clientChannel)
                && trustLevel == that.trustLevel
                && Objects.equals(negotiatedChannels, that.negotiatedChannels)
                && Objects.equals(modCapabilities, that.modCapabilities)
                && Objects.equals(consentedScopes, that.consentedScopes)
                && Objects.equals(signals, that.signals)
                && Objects.equals(connectedAt, that.connectedAt)
                && Objects.equals(attestedAt, that.attestedAt)
                && Objects.equals(lastReportAt, that.lastReportAt)
                && Objects.equals(proxyId, that.proxyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, haikyoClient, protocolVersion, clientVersion, clientChannel,
                trustLevel, sampledUnrelaxed, negotiatedChannels, modCapabilities, consentedScopes,
                signals, connectedAt, attestedAt, lastReportAt, proxyId);
    }

    @Override
    public String toString() {
        return "PlayerSession[playerId=" + playerId
                + ", haikyoClient=" + haikyoClient
                + ", protocolVersion=" + protocolVersion
                + ", clientVersion=" + clientVersion
                + ", clientChannel=" + clientChannel
                + ", trustLevel=" + trustLevel
                + ", sampledUnrelaxed=" + sampledUnrelaxed
                + ", proxyId=" + proxyId + "]";
    }
}

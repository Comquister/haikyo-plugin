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
package dev.haikyo.core.voice;

import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.util.Java8Compat;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java voice configuration & moderation manager for Phase 8 Mode A ({@code haikyo:voice} +
 * Simple Voice Chat integration). Zero audio recording by default.
 */
public final class VoiceModerationManager {

    public static final class ModerationState {
        public static final ModerationState NONE = new ModerationState(false, false, 0L, null, false);

        private final boolean muted;
        private final boolean forceMuted;
        private final long muteUntilEpochSeconds;
        private final String muteReason;
        private final boolean adminVoiceActive;

        public ModerationState(
                boolean muted,
                boolean forceMuted,
                long muteUntilEpochSeconds,
                String muteReason,
                boolean adminVoiceActive
        ) {
            this.muted = muted;
            this.forceMuted = forceMuted;
            this.muteUntilEpochSeconds = muteUntilEpochSeconds;
            this.muteReason = muteReason;
            this.adminVoiceActive = adminVoiceActive;
        }

        public boolean muted() { return muted; }
        public boolean forceMuted() { return forceMuted; }
        public long muteUntilEpochSeconds() { return muteUntilEpochSeconds; }
        public String muteReason() { return muteReason; }
        public boolean adminVoiceActive() { return adminVoiceActive; }

        public boolean isEffectivelyMuted(Instant now) {
            if (forceMuted) {
                return true;
            }
            if (!muted) {
                return false;
            }
            return muteUntilEpochSeconds == 0L || now.getEpochSecond() < muteUntilEpochSeconds;
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<UUID, ModerationState> states = new ConcurrentHashMap<UUID, ModerationState>();
    private volatile boolean svcDetected;

    public VoiceModerationManager(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void setSvcDetected(boolean svcDetected) {
        this.svcDetected = svcDetected;
    }

    public boolean isSvcDetected() {
        return svcDetected;
    }

    public String effectiveMode(HaikyoConfig.VoiceConfig cfg) {
        if (cfg == null) {
            return "off";
        }
        String mode = cfg.mode() == null ? "off" : cfg.mode().trim().toLowerCase(Locale.ROOT);
        if ("svc".equals(mode) || "auto".equals(mode)) {
            return svcDetected ? "svc" : "off";
        }
        return "off";
    }

    public ModerationState mute(UUID playerId, long durationSeconds, String reason, boolean forceMute) {
        long until = durationSeconds > 0 ? clock.instant().getEpochSecond() + durationSeconds : 0L;
        ModerationState prev = states.getOrDefault(playerId, ModerationState.NONE);
        ModerationState next = new ModerationState(
                true,
                forceMute || prev.forceMuted(),
                until,
                Java8Compat.isBlank(reason) ? null : reason,
                prev.adminVoiceActive()
        );
        states.put(playerId, next);
        return next;
    }

    public ModerationState unmute(UUID playerId) {
        ModerationState prev = states.getOrDefault(playerId, ModerationState.NONE);
        ModerationState next = new ModerationState(false, false, 0L, null, prev.adminVoiceActive());
        if (!next.adminVoiceActive()) {
            states.remove(playerId);
        } else {
            states.put(playerId, next);
        }
        return next;
    }

    public boolean isPlayerMuted(UUID playerId) {
        ModerationState st = states.get(playerId);
        if (st == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!st.isEffectivelyMuted(now)) {
            if (st.muted() && !st.forceMuted() && st.muteUntilEpochSeconds() > 0 && now.getEpochSecond() >= st.muteUntilEpochSeconds()) {
                unmute(playerId);
            }
            return false;
        }
        return true;
    }

    public ModerationState getState(UUID playerId) {
        ModerationState st = states.get(playerId);
        if (st == null) {
            return ModerationState.NONE;
        }
        Instant now = clock.instant();
        if (st.muted() && !st.forceMuted() && st.muteUntilEpochSeconds() > 0 && now.getEpochSecond() >= st.muteUntilEpochSeconds()) {
            return unmute(playerId);
        }
        return st;
    }

    public ProtocolMessages.VoiceConfigMessage buildMessageFor(UUID playerId, HaikyoConfig.VoiceConfig cfg) {
        String mode = effectiveMode(cfg);
        ModerationState st = getState(playerId);
        boolean effectivelyMuted = st.isEffectivelyMuted(clock.instant());
        return new ProtocolMessages.VoiceConfigMessage(
                mode,
                cfg.maxDistance(),
                cfg.whisperDistance(),
                cfg.falloffMode(),
                cfg.falloffFactor(),
                cfg.allowGroups(),
                cfg.recordingAllowed(),
                effectivelyMuted,
                st.forceMuted(),
                effectivelyMuted ? st.muteUntilEpochSeconds() : 0L,
                effectivelyMuted ? st.muteReason() : null,
                st.adminVoiceActive()
        );
    }

    public byte[] encodeConfigFrame(UUID playerId, HaikyoConfig.VoiceConfig cfg) {
        ProtocolMessages.VoiceConfigMessage msg = buildMessageFor(playerId, cfg);
        return WireFrame.encodeUnfragmented(HaikyoChannels.PKT_VOICE_CONFIG, msg.encodeJson());
    }

    public void clearPlayer(UUID playerId) {
        states.remove(playerId);
    }
}

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
package dev.haikyo.fabric;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.trust.TrustManager;

import java.util.UUID;

/**
 * Simple Voice Chat Fabric entrypoint ({@code entrypoints.voicechat} in {@code fabric.mod.json}).
 * Implements Phase 8 Mode A on Fabric with zero audio recording by default.
 */
public final class HaikyoFabricVoicePlugin implements VoicechatPlugin {
    public static final String PLUGIN_ID = "haikyo";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        TrustManager tm = HaikyoFabricMod.trustManager();
        if (tm != null) {
            tm.voiceModeration().setSvcDetected(true);
            HaikyoFabricMod.LOGGER.info("[Haikyo] Hooked into Simple Voice Chat on Fabric (Mode A active).");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoiceDistanceEvent.class, this::onVoiceDistance);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
    }

    private void onVoiceDistance(VoiceDistanceEvent event) {
        TrustManager tm = HaikyoFabricMod.trustManager();
        if (tm == null) {
            return;
        }
        HaikyoConfig.VoiceConfig vcfg = tm.config().voice();
        if ("off".equals(tm.voiceModeration().effectiveMode(vcfg))) {
            return;
        }
        boolean whispering = event.getPacket().isWhispering();
        if (whispering && vcfg.whisperDistance() != null && vcfg.whisperDistance() > 0) {
            event.setDistance(vcfg.whisperDistance().floatValue());
        } else if (!whispering && vcfg.maxDistance() != null && vcfg.maxDistance() > 0) {
            event.setDistance(vcfg.maxDistance().floatValue());
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        TrustManager tm = HaikyoFabricMod.trustManager();
        if (tm == null) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || sender.getPlayer() == null) {
            return;
        }
        if (tm.voiceModeration().isPlayerMuted(sender.getPlayer().getUuid())) {
            event.cancel();
        }
    }

    private void onJoinGroup(JoinGroupEvent event) {
        TrustManager tm = HaikyoFabricMod.trustManager();
        if (tm != null && Boolean.FALSE.equals(tm.config().voice().allowGroups())) {
            event.cancel();
        }
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        TrustManager tm = HaikyoFabricMod.trustManager();
        if (tm == null) {
            return;
        }
        VoicechatConnection conn = event.getConnection();
        if (conn == null || conn.getPlayer() == null) {
            return;
        }
        UUID uuid = conn.getPlayer().getUuid();
        if (tm.isHaikyoClient(uuid)) {
            tm.sendVoiceConfig(uuid);
        }
    }
}

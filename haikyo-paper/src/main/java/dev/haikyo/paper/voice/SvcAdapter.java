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
package dev.haikyo.paper.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
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
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Phase 8 Mode A Simple Voice Chat adapter (soft-depend).
 * Outer class does NOT implement {@code VoicechatPlugin}, guaranteeing zero
 * {@link NoClassDefFoundError} when Simple Voice Chat is not installed.
 */
public final class SvcAdapter {
    public static final String PLUGIN_ID = "haikyo";

    private SvcAdapter() {
    }

    public static boolean tryRegister(TrustManager trustManager, Logger logger) {
        HaikyoConfig.VoiceConfig vcfg = trustManager.config().voice();
        if ("off".equalsIgnoreCase(vcfg.mode())) {
            trustManager.voiceModeration().setSvcDetected(false);
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("voicechat")) {
            trustManager.voiceModeration().setSvcDetected(false);
            return false;
        }
        try {
            return Registrar.register(trustManager);
        } catch (Throwable t) {
            trustManager.voiceModeration().setSvcDetected(false);
            logger.fine("Simple Voice Chat API unavailable: " + t.getMessage());
            return false;
        }
    }

    private static final class Registrar {
        static boolean register(TrustManager trustManager) {
            BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
            if (service == null) {
                trustManager.voiceModeration().setSvcDetected(false);
                return false;
            }
            service.registerPlugin(new PluginImpl(trustManager));
            trustManager.voiceModeration().setSvcDetected(true);
            return true;
        }
    }

    private static final class PluginImpl implements VoicechatPlugin {
        private final TrustManager trustManager;

        PluginImpl(TrustManager trustManager) {
            this.trustManager = trustManager;
        }

        @Override
        public String getPluginId() {
            return PLUGIN_ID;
        }

        @Override
        public void initialize(VoicechatApi api) {
            // Audio stays on SVC's native encrypted UDP transport; zero audio recording.
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(VoiceDistanceEvent.class, this::onVoiceDistance);
            registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
            registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
            registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        }

        private void onVoiceDistance(VoiceDistanceEvent event) {
            HaikyoConfig.VoiceConfig vcfg = trustManager.config().voice();
            if ("off".equals(trustManager.voiceModeration().effectiveMode(vcfg))) {
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
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null || sender.getPlayer() == null) {
                return;
            }
            UUID uuid = sender.getPlayer().getUuid();
            if (trustManager.voiceModeration().isPlayerMuted(uuid)) {
                event.cancel();
            }
        }

        private void onJoinGroup(JoinGroupEvent event) {
            HaikyoConfig.VoiceConfig vcfg = trustManager.config().voice();
            if (Boolean.FALSE.equals(vcfg.allowGroups())) {
                event.cancel();
            }
        }

        private void onPlayerConnected(PlayerConnectedEvent event) {
            VoicechatConnection conn = event.getConnection();
            if (conn == null || conn.getPlayer() == null) {
                return;
            }
            UUID uuid = conn.getPlayer().getUuid();
            if (trustManager.isHaikyoClient(uuid)) {
                trustManager.sendVoiceConfig(uuid);
            }
        }
    }
}

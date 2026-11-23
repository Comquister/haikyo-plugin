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

import dev.haikyo.api.HaikyoApiProvider;
import dev.haikyo.core.admin.AuditLogger;
import dev.haikyo.core.admin.ScreenshotDeliveryService;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.JsonCodec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fabric 26.3 server mod entrypoint reusing {@code haikyo-core} and {@code haikyo-api}:
 * full protocol handshake, Ed25519 attestation, {@code mods.rules}, trust levels,
 * {@code haikyo:mod/waypoints}, {@code haikyo:admin}, and {@code haikyo:voice}.
 */
public final class HaikyoFabricMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Haikyo");
    private static final String WAYPOINTS_CHANNEL = HaikyoChannels.modChannel("waypoints");

    private static volatile HaikyoFabricMod instance;

    private final ConcurrentHashMap<String, CustomPacketPayload.Type<RawHaikyoPayload>> channelTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> pendingHelloTick = new ConcurrentHashMap<>();

    private Path dataDir;
    private volatile MinecraftServer currentServer;
    private volatile Localization localization;
    private TrustManager trustManager;
    private AuditLogger auditLogger;
    private ScreenshotDeliveryService screenshotService;
    private long serverTicks;

    @Override
    public void onInitialize() {
        instance = this;
        this.dataDir = FabricLoader.getInstance().getConfigDir().resolve("haikyo");
        ensureDefaultConfigOnDisk();

        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), dataDir.resolve("lang"));

        RequiresHaikyoStore store = new RequiresHaikyoStore(dataDir.resolve("requires-haikyo.json"));
        try {
            store.load();
        } catch (IOException e) {
            LOGGER.warn("Failed to load requires-haikyo.json: {}", e.getMessage());
        }

        String modVersion = FabricLoader.getInstance()
                .getModContainer("haikyo")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.1.0-SNAPSHOT");

        this.trustManager = new TrustManager(
                cfg,
                TrustManager.ServerRole.STANDALONE,
                modVersion,
                store,
                this::sendPacket,
                this::kickPlayerLocalized,
                (ch, reg) -> { },
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble()
        );

        this.auditLogger = new AuditLogger(
                dataDir.resolve("audit.log"),
                cfg.admin().auditRetentionDays(),
                Clock.systemUTC()
        );
        this.screenshotService = new ScreenshotDeliveryService(
                dataDir.resolve("screens"),
                cfg.admin().screenshotDelivery(),
                Clock.systemUTC()
        );
        this.screenshotService.reconfigure(cfg.admin().screenshotDelivery());

        // Register all core haikyo:* + waypoints payload types up front (required before server start on Fabric)
        for (String ch : HaikyoChannels.CORE_CHANNELS) {
            registerChannel(ch);
        }
        registerChannel(WAYPOINTS_CHANNEL);

        configureWaypoints(cfg);
        trustManager.addHandshakeListener(playerId -> {
            if (trustManager.config().waypointsEnabled()) {
                sendWaypointsSync(playerId);
            }
        });

        HaikyoApiProvider.register(trustManager);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                HaikyoFabricCommand.register(dispatcher, this));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.currentServer = server;
            this.serverTicks = 0L;
            LOGGER.info("Haikyo Fabric enabled (require-haikyo={}, mod-rules={})",
                    trustManager.config().requireHaikyo(), trustManager.config().modRules().size());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.currentServer = null;
            pendingHelloTick.clear();
            if (screenshotService != null) {
                screenshotService.close();
            }
            HaikyoApiProvider.unregister(trustManager);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            trustManager.onPlayerJoin(player.getUUID(), player.getGameProfile().name());
            pendingHelloTick.put(player.getUUID(), serverTicks + 2L);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            pendingHelloTick.remove(uuid);
            trustManager.onPlayerQuit(uuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    public static TrustManager trustManager() {
        HaikyoFabricMod mod = instance;
        return mod == null ? null : mod.trustManager;
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    public Localization localization() {
        return localization;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public ScreenshotDeliveryService screenshotService() {
        return screenshotService;
    }

    public MinecraftServer server() {
        return currentServer;
    }

    public void reloadPluginConfig() {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), dataDir.resolve("lang"));
        trustManager.reloadConfig(cfg, TrustManager.ServerRole.STANDALONE);
        auditLogger.setRetentionDays(cfg.admin().auditRetentionDays());
        screenshotService.reconfigure(cfg.admin().screenshotDelivery());
        configureWaypoints(cfg);

        MinecraftServer srv = currentServer;
        if (srv != null) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (trustManager.isHaikyoClient(p.getUUID())) {
                    trustManager.sendModRules(p.getUUID());
                    trustManager.sendVoiceConfig(p.getUUID());
                    if (cfg.waypointsEnabled()) {
                        sendWaypointsSync(p.getUUID());
                    }
                }
            }
        }
    }

    private void onServerTick(MinecraftServer server) {
        serverTicks++;

        // Send delayed hello 2 ticks after join
        if (!pendingHelloTick.isEmpty()) {
            var it = pendingHelloTick.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (serverTicks >= e.getValue()) {
                    it.remove();
                    if (server.getPlayerList().getPlayer(e.getKey()) != null) {
                        trustManager.sendHello(e.getKey());
                    }
                }
            }
        }

        // Check require-haikyo / attestation timeouts every second (20 ticks)
        if (serverTicks % 20L == 0L) {
            for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                Optional<String> kickKey = trustManager.checkAttestationTimeout(p.getUUID());
                kickKey.ifPresent(key -> kickPlayerLocalized(p.getUUID(), key, ""));
            }
        }

        // Sweep missing reports, expired screenshots, and audit log every 10 seconds (200 ticks)
        if (serverTicks % 200L == 0L) {
            trustManager.checkReportTimeouts();
            screenshotService.purgeExpired();
            auditLogger.pruneExpired();
        }
    }

    private void registerChannel(String channel) {
        CustomPacketPayload.Type<RawHaikyoPayload> type = RawHaikyoPayload.typeFor(channel);
        var codec = RawHaikyoPayload.codecFor(type);
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        channelTypes.put(channel, type);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            UUID uuid = context.player().getUUID();
            trustManager.handleClientMessage(uuid, channel, payload.data());
        });
    }

    private void configureWaypoints(HaikyoConfig cfg) {
        if (cfg.waypointsEnabled()) {
            trustManager.registerModEndpoint("waypoints", "1", (playerId, modId, payload) -> {
                try {
                    Map<String, Object> obj = JsonCodec.parseObject(new String(payload, StandardCharsets.UTF_8));
                    if ("request_sync".equals(JsonCodec.asString(obj, "op", ""))) {
                        sendWaypointsSync(playerId);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            });
        } else {
            trustManager.unregisterModEndpoint("waypoints");
        }
    }

    private void sendWaypointsSync(UUID playerId) {
        List<Map<String, Object>> markerMaps = new ArrayList<>();
        for (HaikyoConfig.WaypointMarker m : trustManager.config().waypointMarkers()) {
            markerMaps.add(m.toJsonMap());
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "sync");
        msg.put("markers", markerMaps);
        trustManager.sendToPlayer(playerId, "waypoints", JsonCodec.stringify(msg).getBytes(StandardCharsets.UTF_8));
    }

    private boolean sendPacket(UUID playerId, String channel, byte[] wireFrame) {
        MinecraftServer srv = currentServer;
        if (srv == null) {
            return false;
        }
        ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        CustomPacketPayload.Type<RawHaikyoPayload> type = channelTypes.get(channel);
        if (type == null) {
            return false;
        }
        try {
            ServerPlayNetworking.send(player, new RawHaikyoPayload(type, wireFrame));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void kickPlayerLocalized(UUID playerId, String reasonKey, String detail) {
        MinecraftServer srv = currentServer;
        if (srv == null) {
            return;
        }
        srv.execute(() -> {
            ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            String locale = Localization.normalizeLocale(player.clientInformation().language());
            String url = trustManager.config().downloadUrl();
            String msg = "kick.attestation_failed".equals(reasonKey)
                    ? localization.getForLocale(locale, reasonKey, detail, url)
                    : localization.getForLocale(locale, reasonKey, url);
            player.connection.disconnect(Component.literal(msg));
        });
    }

    private void ensureDefaultConfigOnDisk() {
        try {
            Files.createDirectories(dataDir);
            Path cfgFile = dataDir.resolve("config.yml");
            if (!Files.exists(cfgFile)) {
                try (InputStream is = HaikyoConfig.class.getResourceAsStream("/config.yml")) {
                    if (is != null) {
                        Files.copy(is, cfgFile);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write default config.yml: {}", e.getMessage());
        }
    }

    private HaikyoConfig loadConfigFromDisk() {
        Path cfgFile = dataDir.resolve("config.yml");
        if (Files.isRegularFile(cfgFile)) {
            try {
                return HaikyoConfig.loadFromFile(cfgFile);
            } catch (IOException e) {
                LOGGER.warn("Failed to parse config.yml, using defaults: {}", e.getMessage());
            }
        }
        return HaikyoConfig.loadDefault();
    }
}

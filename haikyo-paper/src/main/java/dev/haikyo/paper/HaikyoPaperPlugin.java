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
package dev.haikyo.paper;

import dev.haikyo.api.HaikyoApi;
import dev.haikyo.api.HaikyoApiProvider;
import dev.haikyo.core.admin.AuditLogger;
import dev.haikyo.core.admin.ScreenshotDeliveryService;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.Java8Compat;
import dev.haikyo.paper.anticheat.GenericAnticheatAdapter;
import dev.haikyo.paper.anticheat.GrimAdapter;
import dev.haikyo.paper.anticheat.VulcanAdapter;
import dev.haikyo.paper.compat.PlatformCompat;
import dev.haikyo.paper.compat.PlatformScheduler;
import dev.haikyo.paper.mods.WaypointsMod;
import dev.haikyo.paper.permissions.HaikyoPermissionManager;
import dev.haikyo.paper.permissions.LuckPermsHook;
import dev.haikyo.paper.voice.SvcAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multi-version CraftBukkit / Spigot / Paper / Purpur / Folia entrypoint (Minecraft 1.8 - 26.3).
 *
 * <p>Uses {@link PlatformScheduler} to run natively on Folia/Paper 1.19.4+ regionized schedulers
 * when available and cleanly fall back to legacy Bukkit scheduling on 1.8-1.19.3 servers.</p>
 */
public final class HaikyoPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private final Set<String> registeredDynamicChannels = ConcurrentHashMap.newKeySet();

    private volatile Localization localization;
    private TrustManager trustManager;
    private WaypointsMod waypointsMod;
    private AuditLogger auditLogger;
    private ScreenshotDeliveryService screenshotService;
    private HaikyoPermissionManager permissionManager;
    private LuckPermsHook.Handle luckPermsHandle;
    private PlatformScheduler.CancellableTask reportSweepTask;

    @Override
    public void onEnable() {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), getDataFolder().toPath().resolve("lang"));

        TrustManager.ServerRole role = resolveEffectiveRole(cfg);
        RequiresHaikyoStore store = new RequiresHaikyoStore(getDataFolder().toPath().resolve("requires-haikyo.json"));
        try {
            store.load();
        } catch (IOException e) {
            getLogger().warning("Failed to load requires-haikyo.json: " + e.getMessage());
        }

        this.trustManager = new TrustManager(
                cfg,
                role,
                PlatformCompat.getPluginVersion(this),
                store,
                this::sendPluginPacket,
                this::kickPlayerLocalized,
                this::onModChannelLifecycle,
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble()
        );
        this.trustManager.setBackendLinkSyncSender(this::sendBackendLinkSync);

        registerCoreChannels();

        // Expose HaikyoApi via both Bukkit ServicesManager and HaikyoApiProvider
        Bukkit.getServicesManager().register(HaikyoApi.class, trustManager, this, ServicePriority.Normal);
        HaikyoApiProvider.register(trustManager);

        // Optional example server mod: waypoints (haikyo:mod/waypoints)
        this.waypointsMod = new WaypointsMod(trustManager, cfg.waypointMarkers());
        if (cfg.waypointsEnabled()) {
            waypointsMod.enable(cfg.waypointMarkers());
        }
        trustManager.addHandshakeListener(playerId -> {
            if (waypointsMod != null && waypointsMod.isRegistered()) {
                waypointsMod.sendSync(playerId);
            }
            if (permissionManager != null) {
                permissionManager.syncPlayer(playerId);
            }
            if (luckPermsHandle != null) {
                luckPermsHandle.signalPlayerUpdate(playerId);
            }
        });
        trustManager.addTrustChangeListener(ev -> {
            if (permissionManager != null) {
                permissionManager.syncPlayer(ev.playerId());
            }
            if (luckPermsHandle != null) {
                luckPermsHandle.signalPlayerUpdate(ev.playerId());
            }
        });

        // Built-in extra permissions (Folia-safe & 1.8-compatible) & optional LuckPerms contexts
        this.permissionManager = new HaikyoPermissionManager(this, trustManager);
        this.luckPermsHandle = LuckPermsHook.tryRegister(trustManager, getLogger());
        if (this.luckPermsHandle != null) {
            getLogger().info("Hooked into LuckPerms context calculator (haikyo-client, haikyo-attested, haikyo-trust, haikyo-channel).");
        }

        // Register listeners & commands
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new GenericAnticheatAdapter(trustManager), this);

        HaikyoCommand cmd = new HaikyoCommand(this);
        PluginCommand haikyoCmd = getCommand("haikyo");
        if (haikyoCmd != null) {
            haikyoCmd.setExecutor(cmd);
            haikyoCmd.setTabCompleter(cmd);
        }

        // Phase 7: audit.log & temporary screenshot delivery service
        this.auditLogger = new AuditLogger(
                getDataFolder().toPath().resolve("audit.log"),
                cfg.admin().auditRetentionDays(),
                Clock.systemUTC()
        );
        this.screenshotService = new ScreenshotDeliveryService(
                getDataFolder().toPath().resolve("screens"),
                cfg.admin().screenshotDelivery(),
                Clock.systemUTC()
        );
        this.screenshotService.reconfigure(cfg.admin().screenshotDelivery());

        // Anticheat & Simple Voice Chat soft-depend adapters
        if (GrimAdapter.tryRegister(this, trustManager, getLogger())) {
            getLogger().info(localization.get("log.anticheat_hooked", "GrimAC"));
        } else {
            getLogger().info(localization.get("log.anticheat_unavailable", "GrimAC"));
        }
        if (VulcanAdapter.tryRegister(this, trustManager, getLogger())) {
            getLogger().info(localization.get("log.anticheat_hooked", "Vulcan"));
        } else {
            getLogger().info(localization.get("log.anticheat_unavailable", "Vulcan"));
        }
        if (SvcAdapter.tryRegister(trustManager, getLogger())) {
            getLogger().info(localization.get("log.voice_hooked"));
        } else {
            getLogger().info(localization.get("log.voice_unavailable"));
        }

        // Periodic async sweep for missing integrity reports, expired screenshots, and audit log retention
        this.reportSweepTask = PlatformScheduler.runAsyncAtFixedRate(
                this,
                () -> {
                    trustManager.checkReportTimeouts();
                    screenshotService.purgeExpired();
                    auditLogger.pruneExpired();
                },
                10L,
                10L
        );

        // Initialize any already-online players (e.g. hot-reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            trustManager.onPlayerJoin(player.getUniqueId(), player.getName());
            scheduleHandshake(player);
        }

        getLogger().info("Haikyo enabled (platform=" + PlatformCompat.serverVersionSummary()
                + ", scheduler=" + PlatformScheduler.backendName()
                + ", role=" + role.wireName()
                + ", require-haikyo=" + cfg.requireHaikyo()
                + ", mod-rules=" + cfg.modRules().size() + ")");
    }

    @Override
    public void onDisable() {
        if (reportSweepTask != null) {
            reportSweepTask.cancel();
            reportSweepTask = null;
        }
        if (luckPermsHandle != null) {
            luckPermsHandle.close();
            luckPermsHandle = null;
        }
        if (permissionManager != null) {
            permissionManager.clearAll();
        }
        if (screenshotService != null) {
            screenshotService.close();
        }
        if (waypointsMod != null) {
            waypointsMod.disable();
        }
        if (trustManager != null) {
            HaikyoApiProvider.unregister(trustManager);
            Bukkit.getServicesManager().unregister(HaikyoApi.class, trustManager);
        }
        unregisterAllChannels();
    }

    public TrustManager trustManager() {
        return trustManager;
    }

    public Localization localization() {
        return localization;
    }

    public WaypointsMod waypointsMod() {
        return waypointsMod;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public ScreenshotDeliveryService screenshotService() {
        return screenshotService;
    }

    public void reloadPluginConfig() {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), getDataFolder().toPath().resolve("lang"));
        TrustManager.ServerRole role = resolveEffectiveRole(cfg);
        trustManager.reloadConfig(cfg, role);
        auditLogger.setRetentionDays(cfg.admin().auditRetentionDays());
        screenshotService.reconfigure(cfg.admin().screenshotDelivery());
        if (cfg.waypointsEnabled()) {
            waypointsMod.enable(cfg.waypointMarkers());
        } else {
            waypointsMod.disable();
        }
        if (permissionManager != null) {
            permissionManager.syncAllOnline();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (trustManager.isHaikyoClient(p.getUniqueId())) {
                final Player target = p;
                PlatformScheduler.runForPlayer(this, target, () -> {
                    trustManager.sendModRules(target.getUniqueId());
                    trustManager.sendVoiceConfig(target.getUniqueId());
                    if (waypointsMod.isRegistered()) {
                        waypointsMod.sendSync(target.getUniqueId());
                    }
                });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player & Plugin Messaging events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (trustManager != null) {
            PlatformCompat.appendPingInsertion(event, trustManager.encodePingInsertion());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        trustManager.onPlayerJoin(player.getUniqueId(), player.getName());
        scheduleHandshake(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (permissionManager != null) {
            permissionManager.clearPlayer(event.getPlayer());
        }
        trustManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (HaikyoChannels.LINK.equals(channel)) {
            if (trustManager.effectiveRole() != TrustManager.ServerRole.BACKEND) {
                return;
            }
            HaikyoLinkCodec.DecodeResult res = trustManager.handleIncomingLink(player.getUniqueId(), message);
            if (!res.valid()) {
                getLogger().warning(localization.get("log.proxy_fallback_bad_hmac", player.getName())
                        + " (" + res.failureCode() + ")");
                // Safe fallback: perform standalone hello + attestation verification
                PlatformScheduler.runForPlayer(this, player, () -> {
                    trustManager.sendHello(player.getUniqueId());
                    trustManager.issueAttestChallenge(player.getUniqueId());
                });
            } else {
                if (permissionManager != null) {
                    permissionManager.syncPlayer(player.getUniqueId());
                }
                if (luckPermsHandle != null) {
                    luckPermsHandle.signalPlayerUpdate(player.getUniqueId());
                }
                if (waypointsMod.isRegistered()) {
                    PlatformScheduler.runForPlayer(this, player, () -> waypointsMod.sendSync(player.getUniqueId()));
                }
            }
            return;
        }
        trustManager.handleClientMessage(player.getUniqueId(), channel, message);
    }

    // -------------------------------------------------------------------------
    // Multi-version scheduling & transport
    // -------------------------------------------------------------------------

    private void scheduleHandshake(Player player) {
        final UUID playerId = player.getUniqueId();
        // Wait 2 ticks so client completes channel registration before receiving hello / link_request
        PlatformScheduler.runForPlayerDelayed(this, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            trustManager.sendHello(playerId);
            if (trustManager.effectiveRole() == TrustManager.ServerRole.BACKEND) {
                byte[] reqFrame = WireFrame.encodeUnfragmented(
                        HaikyoChannels.PKT_LINK_REQUEST,
                        HaikyoLinkCodec.encodeLinkRequest(playerId)
                );
                sendPluginPacket(playerId, HaikyoChannels.LINK, reqFrame);
            }
        }, 2L);

        // Enforce require-haikyo / requires-haikyo.json timeout
        long timeoutTicks = Math.max(60L, trustManager.config().attestationTimeoutSeconds() * 20L + 10L);
        PlatformScheduler.runForPlayerDelayed(this, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            Optional<String> kickKey = trustManager.checkAttestationTimeout(playerId);
            if (kickKey.isPresent()) {
                kickPlayerLocalized(playerId, kickKey.get(), "");
            }
        }, timeoutTicks);
    }

    private boolean sendPluginPacket(UUID playerId, String channel, byte[] wireFrame) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        try {
            player.sendPluginMessage(this, channel, wireFrame);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void sendBackendLinkSync(UUID preferredPlayerId, byte[] wireFrame) {
        Player carrier = preferredPlayerId != null ? Bukkit.getPlayer(preferredPlayerId) : null;
        if (carrier == null || !carrier.isOnline()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.isOnline()) {
                    carrier = online;
                    break;
                }
            }
        }
        if (carrier == null || !carrier.isOnline()) {
            return;
        }
        final Player targetCarrier = carrier;
        PlatformScheduler.runForPlayer(this, targetCarrier, () -> {
            if (targetCarrier.isOnline()) {
                try {
                    targetCarrier.sendPluginMessage(this, HaikyoChannels.LINK, wireFrame);
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
    }

    private void kickPlayerLocalized(UUID playerId, String reasonKey, String detail) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        PlatformScheduler.runForPlayer(this, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String locale = PlatformCompat.getPlayerLocale(player, localization.defaultLocale());
            String downloadUrl = trustManager.config().downloadUrl();
            String msg = "kick.attestation_failed".equals(reasonKey)
                    ? localization.getForLocale(locale, reasonKey, detail, downloadUrl)
                    : localization.getForLocale(locale, reasonKey, downloadUrl);
            PlatformCompat.kickPlayer(player, msg);
        });
    }

    // -------------------------------------------------------------------------
    // Channel registration & proxy forwarding auto-detection (1.8 - 26.3)
    // -------------------------------------------------------------------------

    private void registerCoreChannels() {
        Messenger messenger = Bukkit.getMessenger();
        for (String ch : HaikyoChannels.CORE_CHANNELS) {
            safeRegisterChannel(messenger, ch);
        }
        safeRegisterChannel(messenger, HaikyoChannels.LINK);
    }

    private void safeRegisterChannel(Messenger messenger, String channel) {
        try {
            messenger.registerOutgoingPluginChannel(this, channel);
            messenger.registerIncomingPluginChannel(this, channel, this);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Could not register plugin channel '" + channel
                    + "' on " + PlatformCompat.serverVersionSummary() + ": " + e.getMessage());
        }
    }

    private void onModChannelLifecycle(String channel, boolean register) {
        Messenger messenger = Bukkit.getMessenger();
        if (register) {
            if (registeredDynamicChannels.add(channel)) {
                safeRegisterChannel(messenger, channel);
            }
        } else {
            if (registeredDynamicChannels.remove(channel)) {
                try {
                    messenger.unregisterOutgoingPluginChannel(this, channel);
                    messenger.unregisterIncomingPluginChannel(this, channel, this);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void unregisterAllChannels() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.unregisterIncomingPluginChannel(this);
        messenger.unregisterOutgoingPluginChannel(this);
        registeredDynamicChannels.clear();
    }

    private TrustManager.ServerRole resolveEffectiveRole(HaikyoConfig cfg) {
        String configured = cfg.configuredRole();
        boolean wantsBackend = "backend".equals(configured)
                || ("auto".equals(configured) && detectProxyForwarding());
        if (wantsBackend) {
            if (Java8Compat.isBlank(cfg.linkSharedSecret())) {
                getLogger().warning(localization.get("log.proxy_fallback_no_secret"));
                return TrustManager.ServerRole.STANDALONE;
            }
            return TrustManager.ServerRole.BACKEND;
        }
        return TrustManager.ServerRole.STANDALONE;
    }

    private boolean detectProxyForwarding() {
        Path root = Bukkit.getWorldContainer().toPath();
        // Paper 1.19 - 26.3: config/paper-global.yml -> proxies.velocity.enabled
        if (readYamlBoolean(root.resolve("config").resolve("paper-global.yml"), "proxies.velocity.enabled")
                || readYamlBoolean(Paths.get("config", "paper-global.yml"), "proxies.velocity.enabled")) {
            return true;
        }
        // Paper 1.13 - 1.18.2: paper.yml -> settings.velocity-support.enabled
        if (readYamlBoolean(root.resolve("paper.yml"), "settings.velocity-support.enabled")
                || readYamlBoolean(Paths.get("paper.yml"), "settings.velocity-support.enabled")) {
            return true;
        }
        // Spigot / Paper 1.8 - 1.18.2 legacy forwarding: spigot.yml -> settings.bungeecord
        return readYamlBoolean(root.resolve("spigot.yml"), "settings.bungeecord")
                || readYamlBoolean(Paths.get("spigot.yml"), "settings.bungeecord");
    }

    private static boolean readYamlBoolean(Path path, String key) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(path.toFile());
            return yml.getBoolean(key, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void ensureDefaultConfigOnDisk() {
        try {
            Path dir = getDataFolder().toPath();
            Files.createDirectories(dir);
            Path cfgFile = dir.resolve("config.yml");
            if (!Files.exists(cfgFile)) {
                try (InputStream is = HaikyoConfig.class.getResourceAsStream("/config.yml")) {
                    if (is != null) {
                        Files.copy(is, cfgFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().warning("Could not write default config.yml: " + e.getMessage());
        }
    }

    private HaikyoConfig loadConfigFromDisk() {
        Path cfgFile = getDataFolder().toPath().resolve("config.yml");
        if (Files.isRegularFile(cfgFile)) {
            try {
                return HaikyoConfig.loadFromFile(cfgFile);
            } catch (IOException e) {
                getLogger().warning("Failed to parse config.yml, using defaults: " + e.getMessage());
            }
        }
        return HaikyoConfig.loadDefault();
    }
}

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
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.paper.anticheat.GenericAnticheatAdapter;
import dev.haikyo.paper.anticheat.GrimAdapter;
import dev.haikyo.paper.anticheat.VulcanAdapter;
import dev.haikyo.paper.mods.WaypointsMod;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Paper & Folia entrypoint for the Haikyo Server Plugin.
 *
 * <p><strong>STRICT FOLIA COMPATIBILITY:</strong> This plugin NEVER calls
 * {@code Bukkit.getScheduler()}. All player tasks run on {@code player.getScheduler()}
 * and periodic sweeps run on {@code Bukkit.getAsyncScheduler()} with lock-free
 * {@link ConcurrentHashMap} session state inside {@link TrustManager}.</p>
 */
public final class HaikyoPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private final Set<String> registeredDynamicChannels = ConcurrentHashMap.newKeySet();

    private volatile Localization localization;
    private TrustManager trustManager;
    private WaypointsMod waypointsMod;
    private ScheduledTask reportSweepTask;

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
                getPluginMeta().getVersion(),
                store,
                this::sendPluginPacket,
                this::kickPlayerLocalized,
                this::onModChannelLifecycle,
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble()
        );

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
        });

        // Register listeners & commands
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new GenericAnticheatAdapter(trustManager), this);

        HaikyoCommand cmd = new HaikyoCommand(this);
        PluginCommand haikyoCmd = getCommand("haikyo");
        if (haikyoCmd != null) {
            haikyoCmd.setExecutor(cmd);
            haikyoCmd.setTabCompleter(cmd);
        }

        // Anticheat soft-depend adapters
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

        // Folia-safe async periodic sweep for missing integrity reports
        this.reportSweepTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                this,
                task -> trustManager.checkReportTimeouts(),
                10L,
                10L,
                TimeUnit.SECONDS
        );

        // Initialize any already-online players (e.g. hot-reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            trustManager.onPlayerJoin(player.getUniqueId(), player.getName());
            scheduleHandshake(player);
        }

        getLogger().info("Haikyo enabled (role=" + role.wireName()
                + ", require-haikyo=" + cfg.requireHaikyo()
                + ", mod-rules=" + cfg.modRules().size() + ")");
    }

    @Override
    public void onDisable() {
        if (reportSweepTask != null) {
            reportSweepTask.cancel();
            reportSweepTask = null;
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

    public void reloadPluginConfig() {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), getDataFolder().toPath().resolve("lang"));
        TrustManager.ServerRole role = resolveEffectiveRole(cfg);
        trustManager.reloadConfig(cfg, role);
        if (cfg.waypointsEnabled()) {
            waypointsMod.enable(cfg.waypointMarkers());
        } else {
            waypointsMod.disable();
        }
        // Push refreshed mod rules to all online Haikyo clients on their own Folia entity schedulers
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (trustManager.isHaikyoClient(p.getUniqueId())) {
                p.getScheduler().run(this, t -> {
                    trustManager.sendModRules(p.getUniqueId());
                    if (waypointsMod.isRegistered()) {
                        waypointsMod.sendSync(p.getUniqueId());
                    }
                }, null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player & Plugin Messaging events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        trustManager.onPlayerJoin(player.getUniqueId(), player.getName());
        scheduleHandshake(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
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
                player.getScheduler().run(this, t -> {
                    trustManager.sendHello(player.getUniqueId());
                    trustManager.issueAttestChallenge(player.getUniqueId());
                }, null);
            } else if (waypointsMod.isRegistered()) {
                player.getScheduler().run(this, t -> waypointsMod.sendSync(player.getUniqueId()), null);
            }
            return;
        }
        trustManager.handleClientMessage(player.getUniqueId(), channel, message);
    }

    // -------------------------------------------------------------------------
    // Folia-safe scheduling & transport
    // -------------------------------------------------------------------------

    private void scheduleHandshake(Player player) {
        UUID playerId = player.getUniqueId();
        // Wait 2 ticks so client completes channel registration before receiving hello / link_request
        player.getScheduler().runDelayed(this, task -> {
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
        }, null, 2L);

        // Enforce require-haikyo / requires-haikyo.json timeout on the player's entity scheduler
        long timeoutTicks = Math.max(60L, trustManager.config().attestationTimeoutSeconds() * 20L + 10L);
        player.getScheduler().runDelayed(this, task -> {
            if (!player.isOnline()) {
                return;
            }
            Optional<String> kickKey = trustManager.checkAttestationTimeout(playerId);
            kickKey.ifPresent(key -> kickPlayerLocalized(playerId, key, ""));
        }, null, timeoutTicks);
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

    private void kickPlayerLocalized(UUID playerId, String reasonKey, String detail) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) {
                return;
            }
            String locale = Localization.normalizeLocale(player.locale().toLanguageTag());
            String downloadUrl = trustManager.config().downloadUrl();
            String msg = "kick.attestation_failed".equals(reasonKey)
                    ? localization.getForLocale(locale, reasonKey, detail, downloadUrl)
                    : localization.getForLocale(locale, reasonKey, downloadUrl);
            player.kick(Component.text(msg));
        }, null);
    }

    // -------------------------------------------------------------------------
    // Channel registration & Velocity forwarding auto-detection
    // -------------------------------------------------------------------------

    private void registerCoreChannels() {
        var messenger = Bukkit.getMessenger();
        for (String ch : HaikyoChannels.CORE_CHANNELS) {
            messenger.registerOutgoingPluginChannel(this, ch);
            messenger.registerIncomingPluginChannel(this, ch, this);
        }
        messenger.registerOutgoingPluginChannel(this, HaikyoChannels.LINK);
        messenger.registerIncomingPluginChannel(this, HaikyoChannels.LINK, this);
    }

    private void onModChannelLifecycle(String channel, boolean register) {
        var messenger = Bukkit.getMessenger();
        if (register) {
            if (registeredDynamicChannels.add(channel)) {
                messenger.registerOutgoingPluginChannel(this, channel);
                messenger.registerIncomingPluginChannel(this, channel, this);
            }
        } else {
            if (registeredDynamicChannels.remove(channel)) {
                messenger.unregisterOutgoingPluginChannel(this, channel);
                messenger.unregisterIncomingPluginChannel(this, channel, this);
            }
        }
    }

    private void unregisterAllChannels() {
        var messenger = Bukkit.getMessenger();
        messenger.unregisterIncomingPluginChannel(this);
        messenger.unregisterOutgoingPluginChannel(this);
        registeredDynamicChannels.clear();
    }

    private TrustManager.ServerRole resolveEffectiveRole(HaikyoConfig cfg) {
        String configured = cfg.configuredRole();
        boolean wantsBackend = "backend".equals(configured)
                || ("auto".equals(configured) && detectVelocityForwarding());
        if (wantsBackend) {
            if (cfg.linkSharedSecret() == null || cfg.linkSharedSecret().isBlank()) {
                getLogger().warning(localization.get("log.proxy_fallback_no_secret"));
                return TrustManager.ServerRole.STANDALONE;
            }
            return TrustManager.ServerRole.BACKEND;
        }
        return TrustManager.ServerRole.STANDALONE;
    }

    private boolean detectVelocityForwarding() {
        Path paperGlobal = Bukkit.getWorldContainer().toPath().resolve("config").resolve("paper-global.yml");
        if (!Files.isRegularFile(paperGlobal)) {
            paperGlobal = Path.of("config", "paper-global.yml");
        }
        if (!Files.isRegularFile(paperGlobal)) {
            return false;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(paperGlobal.toFile());
        return yml.getBoolean("proxies.velocity.enabled", false);
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

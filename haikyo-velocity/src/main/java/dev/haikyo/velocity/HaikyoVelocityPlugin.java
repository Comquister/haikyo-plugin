/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.haikyo.api.HaikyoApiProvider;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Velocity 4.x proxy plugin implementing the Haikyo {@code proxy} role:
 * <ul>
 *   <li>Handles {@code haikyo:control} ({@code hello}/{@code hello_ack}) and {@code haikyo:attest}
 *       (attestation, consent union, {@code require-haikyo}, integrity reports).</li>
 *   <li>Blocks client-sourced {@code haikyo:link} frames and forwards HMAC-SHA256 signed
 *       {@code haikyo:link} sessions to backend servers on connect / server switch and on backend
 *       {@code 0x02} {@code link_request}.</li>
 *   <li>Leaves {@code haikyo:mods}, {@code haikyo:mod/*}, and {@code haikyo:ac} unregistered so
 *       Velocity forwards them transparently between client and backend.</li>
 * </ul>
 */
@Plugin(
        id = "haikyo",
        name = "Haikyo",
        version = "0.1.0-SNAPSHOT",
        url = "https://haikyo.conquister.net",
        description = "Velocity proxy integration for the Haikyo Minecraft Client",
        authors = {"Haikyo Project Contributors"}
)
public final class HaikyoVelocityPlugin {

    private static final MinecraftChannelIdentifier CONTROL_ID = MinecraftChannelIdentifier.from(HaikyoChannels.CONTROL);
    private static final MinecraftChannelIdentifier ATTEST_ID = MinecraftChannelIdentifier.from(HaikyoChannels.ATTEST);
    private static final MinecraftChannelIdentifier LINK_ID = MinecraftChannelIdentifier.from(HaikyoChannels.LINK);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private volatile Localization localization;
    private TrustManager trustManager;

    @Inject
    public HaikyoVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), dataDirectory.resolve("lang"));

        RequiresHaikyoStore store = new RequiresHaikyoStore(dataDirectory.resolve("requires-haikyo.json"));
        try {
            store.load();
        } catch (IOException e) {
            logger.warn("Failed to load requires-haikyo.json: {}", e.getMessage());
        }

        this.trustManager = new TrustManager(
                cfg,
                TrustManager.ServerRole.PROXY,
                "0.1.0-SNAPSHOT",
                store,
                this::sendToPlayer,
                this::kickPlayerLocalized,
                (ch, reg) -> { },
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble()
        );

        // Whenever trust level changes on the proxy (e.g. ATTESTED or SUSPECT), push updated haikyo:link to current backend
        trustManager.addTrustChangeListener(ev -> server.getPlayer(ev.playerId()).ifPresent(this::pushLinkToCurrentBackend));

        HaikyoApiProvider.register(trustManager);

        // Register ONLY proxy-owned channels; leave haikyo:mods, haikyo:mod/*, haikyo:ac forwarded to backend
        server.getChannelRegistrar().register(CONTROL_ID, ATTEST_ID, LINK_ID);

        CommandMeta meta = server.getCommandManager()
                .metaBuilder("haikyoproxy")
                .aliases("hkp")
                .plugin(this)
                .build();
        server.getCommandManager().register(meta, new HaikyoVelocityCommand(this));

        server.getScheduler().buildTask(this, () -> trustManager.checkReportTimeouts())
                .repeat(Duration.ofSeconds(10))
                .schedule();

        if (cfg.linkSharedSecret() == null || cfg.linkSharedSecret().isBlank()) {
            logger.warn("link.shared-secret is empty on Velocity proxy; backend servers will not receive signed haikyo:link sessions.");
        }
        logger.info("Haikyo Velocity proxy enabled (require-haikyo={})", cfg.requireHaikyo());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (trustManager != null) {
            HaikyoApiProvider.unregister(trustManager);
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        trustManager.onPlayerJoin(playerId, player.getUsername());

        // Delay hello briefly so client finishes channel negotiation
        server.getScheduler().buildTask(this, () -> {
            if (player.isActive()) {
                trustManager.sendHello(playerId);
            }
        }).delay(Duration.ofMillis(250)).schedule();

        long timeoutSec = Math.max(3L, trustManager.config().attestationTimeoutSeconds());
        server.getScheduler().buildTask(this, () -> {
            if (!player.isActive()) {
                return;
            }
            Optional<String> kickKey = trustManager.checkAttestationTimeout(playerId);
            kickKey.ifPresent(key -> kickPlayerLocalized(playerId, key, ""));
        }).delay(Duration.ofSeconds(timeoutSec)).schedule();
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        pushLinkToCurrentBackend(player);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        trustManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        var id = event.getIdentifier();

        // Client -> Proxy
        if (event.getSource() instanceof Player player) {
            if (LINK_ID.equals(id)) {
                // CRITICAL SECURITY RULE: Never forward client-sourced haikyo:link to backends!
                event.setResult(PluginMessageEvent.ForwardResult.handled());
                return;
            }
            if (CONTROL_ID.equals(id) || ATTEST_ID.equals(id)) {
                event.setResult(PluginMessageEvent.ForwardResult.handled());
                trustManager.handleClientMessage(player.getUniqueId(), id.getId(), event.getData());
            }
            return;
        }

        // Backend -> Proxy
        if (event.getSource() instanceof ServerConnection backendConn && LINK_ID.equals(id)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            WireFrame.DecodeResult decoded = WireFrame.decode(event.getData(), HaikyoChannels.MAX_S2C_WIRE_BYTES);
            if (decoded instanceof WireFrame.DecodeOk ok
                    && !ok.frame().fragmented()
                    && ok.frame().packetType() == HaikyoChannels.PKT_LINK_REQUEST) {
                try {
                    UUID requestedUuid = HaikyoLinkCodec.decodeLinkRequest(ok.frame().payload());
                    if (requestedUuid.equals(backendConn.getPlayer().getUniqueId())) {
                        trustManager.buildSignedLinkFrame(requestedUuid)
                                .ifPresent(frame -> backendConn.sendPluginMessage(LINK_ID, frame));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public ProxyServer server() {
        return server;
    }

    public TrustManager trustManager() {
        return trustManager;
    }

    public Localization localization() {
        return localization;
    }

    public void reloadPluginConfig() {
        ensureDefaultConfigOnDisk();
        HaikyoConfig cfg = loadConfigFromDisk();
        this.localization = Localization.loadDefault(cfg.language(), dataDirectory.resolve("lang"));
        trustManager.reloadConfig(cfg, TrustManager.ServerRole.PROXY);
    }

    private void pushLinkToCurrentBackend(Player player) {
        player.getCurrentServer().ifPresent(conn ->
                trustManager.buildSignedLinkFrame(player.getUniqueId())
                        .ifPresent(frame -> conn.sendPluginMessage(LINK_ID, frame))
        );
    }

    private boolean sendToPlayer(UUID playerId, String channel, byte[] wireFrame) {
        Optional<Player> opt = server.getPlayer(playerId);
        if (opt.isEmpty()) {
            return false;
        }
        return opt.get().sendPluginMessage(MinecraftChannelIdentifier.from(channel), wireFrame);
    }

    private void kickPlayerLocalized(UUID playerId, String reasonKey, String detail) {
        server.getPlayer(playerId).ifPresent(player -> {
            Locale playerLocale = player.getEffectiveLocale();
            String locale = playerLocale == null
                    ? localization.defaultLocale()
                    : Localization.normalizeLocale(playerLocale.toLanguageTag());
            String downloadUrl = trustManager.config().downloadUrl();
            String msg = "kick.attestation_failed".equals(reasonKey)
                    ? localization.getForLocale(locale, reasonKey, detail, downloadUrl)
                    : localization.getForLocale(locale, reasonKey, downloadUrl);
            player.disconnect(Component.text(msg));
        });
    }

    private void ensureDefaultConfigOnDisk() {
        try {
            Files.createDirectories(dataDirectory);
            Path cfgFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(cfgFile)) {
                try (InputStream is = HaikyoConfig.class.getResourceAsStream("/config.yml")) {
                    if (is != null) {
                        Files.copy(is, cfgFile);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not write default config.yml: {}", e.getMessage());
        }
    }

    private HaikyoConfig loadConfigFromDisk() {
        Path cfgFile = dataDirectory.resolve("config.yml");
        if (Files.isRegularFile(cfgFile)) {
            try {
                return HaikyoConfig.loadFromFile(cfgFile);
            } catch (IOException e) {
                logger.warn("Failed to parse config.yml, using defaults: {}", e.getMessage());
            }
        }
        return HaikyoConfig.loadDefault();
    }
}

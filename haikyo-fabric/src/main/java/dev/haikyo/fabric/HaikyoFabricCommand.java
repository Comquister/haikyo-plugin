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

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.haikyo.api.PlayerSession;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.admin.ScreenshotDeliveryService;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Brigadier {@code /haikyo} command tree for Fabric 26.3 supporting {@code status}, {@code info},
 * {@code hwid}, {@code screenshot}, {@code voice}, {@code pardon}, {@code suspect}, and {@code reload}.
 */
public final class HaikyoFabricCommand {

    private HaikyoFabricCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, HaikyoFabricMod mod) {
        dispatcher.register(Commands.literal("haikyo")
                .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("status")
                        .executes(ctx -> statusAll(ctx.getSource(), mod))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> statusOne(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), mod))))
                .then(Commands.literal("info")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> requestInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), mod))))
                .then(Commands.literal("hwid")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> requestHwid(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), mod))))
                .then(Commands.literal("screenshot")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> requestScreenshot(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), mod))))
                .then(Commands.literal("voice")
                        .then(Commands.literal("mute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), 0L, null, false, mod))
                                        .then(Commands.argument("seconds", LongArgumentType.longArg(0))
                                                .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "seconds"), null, false, mod))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "seconds"), StringArgumentType.getString(ctx, "reason"), false, mod))))))
                        .then(Commands.literal("forcemute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), 0L, null, true, mod))))
                        .then(Commands.literal("unmute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> unmutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), mod)))))
                .then(Commands.literal("pardon")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> pardon(ctx.getSource(), StringArgumentType.getString(ctx, "target"), mod))))
                .then(Commands.literal("suspect")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> suspect(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), "manual_command", mod))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> suspect(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "reason"), mod)))))
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource(), mod)))
        );
    }

    private static int statusAll(CommandSourceStack source, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        TrustManager tm = mod.getTrustManager();
        List<ServerPlayer> online = source.getServer().getPlayerList().getPlayers();
        long haikyo = 0, attested = 0, suspect = 0;
        for (ServerPlayer p : online) {
            Optional<PlayerSession> opt = tm.getPlayerSession(p.getUUID());
            if (opt.isPresent()) {
                if (opt.get().haikyoClient()) haikyo++;
                if (opt.get().trustLevel() == TrustLevel.ATTESTED) attested++;
                if (opt.get().trustLevel() == TrustLevel.SUSPECT) suspect++;
            }
        }
        send(source, mod, locale, "command.status.header",
                online.size(), haikyo, attested, suspect, tm.effectiveRole().wireName());
        if (online.isEmpty()) {
            send(source, mod, locale, "command.status.empty");
            return 1;
        }
        for (ServerPlayer p : online) {
            PlayerSession s = tm.getPlayerSession(p.getUUID()).orElse(null);
            String client = (s != null && s.haikyoClient()) ? "Haikyo" : "Vanilla/Other";
            String ver = (s != null && !s.clientVersion().isEmpty()) ? s.clientVersion() : "-";
            String trust = s != null ? s.trustLevel().name() : TrustLevel.NONE.name();
            boolean sampled = s != null && s.sampledUnrelaxed();
            int sigs = s != null ? s.signals().size() : 0;
            send(source, mod, locale, "command.status.line", p.getGameProfile().name(), client, ver, trust, sampled, sigs);
        }
        return 1;
    }

    private static int statusOne(CommandSourceStack source, ServerPlayer target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        PlayerSession s = mod.getTrustManager().getPlayerSession(target.getUUID()).orElse(null);
        if (s == null) {
            send(source, mod, locale, "command.player_not_found", target.getGameProfile().name());
            return 0;
        }
        send(source, mod, locale, "command.status.player",
                target.getGameProfile().name(),
                s.haikyoClient(),
                s.clientVersion().isEmpty() ? "-" : s.clientVersion(),
                s.clientChannel().isEmpty() ? "-" : s.clientChannel(),
                s.trustLevel().name(),
                s.sampledUnrelaxed(),
                String.join(",", s.consentedScopes()),
                s.signals().isEmpty() ? "0" : String.join(",", s.signals()));
        return 1;
    }

    private static int requestInfo(CommandSourceStack source, ServerPlayer target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        String actor = source.getTextName();
        Optional<String> reqId = mod.getTrustManager().sendAdminInfoRequest(target.getUUID(), targetName, actor, resp ->
                source.getServer().execute(() -> {
                    if (resp.error() != null && !resp.error().isBlank()) {
                        send(source, mod, locale, "command.info.error", targetName, resp.error());
                    } else {
                        send(source, mod, locale, "command.info.result",
                                targetName, resp.version(), resp.channel(), resp.os(), resp.cpu(), resp.gpu(),
                                resp.ramMb(), resp.display(), resp.language(), resp.avgFps(), resp.hwid(),
                                resp.agentControl(), String.join(",", resp.mods()), String.join(",", resp.signals()));
                    }
                }));
        if (reqId.isEmpty()) {
            send(source, mod, locale, "command.not_haikyo", targetName);
            return 0;
        }
        mod.auditLogger().record(actor, target.getUUID(), targetName, "info", "req=" + reqId.get());
        send(source, mod, locale, "command.info.pending", targetName);
        return 1;
    }

    private static int requestHwid(CommandSourceStack source, ServerPlayer target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        String actor = source.getTextName();
        Optional<String> reqId = mod.getTrustManager().sendAdminHwidRequest(target.getUUID(), targetName, actor, resp ->
                source.getServer().execute(() -> {
                    if (resp.error() != null && !resp.error().isBlank()) {
                        send(source, mod, locale, "command.hwid.error", targetName, resp.error());
                    } else {
                        send(source, mod, locale, "command.hwid.result", targetName, resp.hwid());
                    }
                }));
        if (reqId.isEmpty()) {
            send(source, mod, locale, "command.not_haikyo", targetName);
            return 0;
        }
        mod.auditLogger().record(actor, target.getUUID(), targetName, "hwid", "req=" + reqId.get());
        send(source, mod, locale, "command.hwid.pending", targetName);
        return 1;
    }

    private static int requestScreenshot(CommandSourceStack source, ServerPlayer target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        String actor = source.getTextName();
        Optional<String> reqId = mod.getTrustManager().sendAdminScreenshotRequest(target.getUUID(), targetName, actor, resp ->
                CompletableFuture.runAsync(() -> {
                    if (resp.status() != ProtocolMessages.AdminScreenshotResponse.STATUS_OK) {
                        String err = (resp.error() == null || resp.error().isBlank()) ? "status=" + resp.status() : resp.error();
                        source.getServer().execute(() -> send(source, mod, locale, "command.screenshot.error", targetName, err));
                        return;
                    }
                    ScreenshotDeliveryService.DeliveryResult delivery = mod.screenshotService().deliver(
                            resp.imageBytes(), resp.mimeType()
                    );
                    source.getServer().execute(() -> {
                        if (!delivery.success()) {
                            send(source, mod, locale, "command.screenshot.error", targetName, delivery.error());
                        } else {
                            send(source, mod, locale, "command.screenshot.ready",
                                    targetName, resp.width(), resp.height(),
                                    delivery.effectiveMode().name().toLowerCase(Locale.ROOT),
                                    delivery.expiresAt().toString(), delivery.location());
                        }
                    });
                }));
        if (reqId.isEmpty()) {
            send(source, mod, locale, "command.not_haikyo", targetName);
            return 0;
        }
        mod.auditLogger().record(actor, target.getUUID(), targetName, "screenshot", "req=" + reqId.get());
        send(source, mod, locale, "command.screenshot.pending", targetName);
        return 1;
    }

    private static int mutePlayer(CommandSourceStack source, ServerPlayer target, long seconds, String reason, boolean force, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        mod.getTrustManager().voiceModeration().mute(target.getUUID(), seconds, reason, force);
        mod.getTrustManager().sendVoiceConfig(target.getUUID());
        mod.auditLogger().record(source.getTextName(), target.getUUID(), targetName, force ? "voice_forcemute" : "voice_mute", "seconds=" + seconds);
        send(source, mod, locale, "command.voice.muted", targetName, force, seconds, reason == null ? "-" : reason);
        return 1;
    }

    private static int unmutePlayer(CommandSourceStack source, ServerPlayer target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        mod.getTrustManager().voiceModeration().unmute(target.getUUID());
        mod.getTrustManager().sendVoiceConfig(target.getUUID());
        mod.auditLogger().record(source.getTextName(), target.getUUID(), targetName, "voice_unmute", "");
        send(source, mod, locale, "command.voice.unmuted", targetName);
        return 1;
    }

    private static int pardon(CommandSourceStack source, String target, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        Optional<RequiresHaikyoStore.Entry> removed = mod.getTrustManager().requiresStore().pardonByNameOrUuid(target);
        if (removed.isPresent()) {
            send(source, mod, locale, "command.pardon.success", target);
            return 1;
        }
        send(source, mod, locale, "command.pardon.not_found", target);
        return 0;
    }

    private static int suspect(CommandSourceStack source, ServerPlayer target, String reason, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        String targetName = target.getGameProfile().name();
        mod.getTrustManager().markSuspectAndMaybeRequire(target.getUUID(), targetName, reason, "command:" + source.getTextName());
        send(source, mod, locale, "command.suspect.success", targetName, reason, mod.getTrustManager().config().promoteSuspectToRequire());
        return 1;
    }

    private static int reload(CommandSourceStack source, HaikyoFabricMod mod) {
        String locale = resolveLocale(source, mod);
        mod.reloadPluginConfig();
        TrustManager tm = mod.getTrustManager();
        send(source, mod, locale, "command.reload.success",
                tm.effectiveRole().wireName(), tm.config().requireHaikyo(), tm.config().modRules().size());
        return 1;
    }

    private static String resolveLocale(CommandSourceStack source, HaikyoFabricMod mod) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return Localization.normalizeLocale(player.clientInformation().language());
        }
        return mod.localization().defaultLocale();
    }

    private static void send(CommandSourceStack source, HaikyoFabricMod mod, String locale, String key, Object... args) {
        Localization i18n = mod.localization();
        String msg = i18n.getForLocale(locale, "prefix") + i18n.getForLocale(locale, key, args);
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}

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
package dev.haikyo.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.haikyo.api.PlayerSession;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class HaikyoVelocityCommand implements SimpleCommand {

    private final HaikyoVelocityPlugin plugin;

    public HaikyoVelocityCommand(HaikyoVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String locale = resolveLocale(source);

        if (args.length == 0) {
            send(source, locale, "command.usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (!source.hasPermission("haikyo.command.status")) {
                    send(source, locale, "command.no_permission");
                    return;
                }
                TrustManager tm = plugin.trustManager();
                var players = plugin.server().getAllPlayers();
                long haikyo = 0;
                long attested = 0;
                long suspect = 0;
                for (Player p : players) {
                    Optional<PlayerSession> opt = tm.getPlayerSession(p.getUniqueId());
                    if (opt.isPresent()) {
                        if (opt.get().haikyoClient()) haikyo++;
                        if (opt.get().trustLevel() == TrustLevel.ATTESTED) attested++;
                        if (opt.get().trustLevel() == TrustLevel.SUSPECT) suspect++;
                    }
                }
                send(source, locale, "command.status.header",
                        players.size(), haikyo, attested, suspect, tm.effectiveRole().wireName());
                for (Player p : players) {
                    PlayerSession s = tm.getPlayerSession(p.getUniqueId()).orElse(null);
                    String client = (s != null && s.haikyoClient()) ? "Haikyo" : "Vanilla/Other";
                    String ver = (s != null && !s.clientVersion().isEmpty()) ? s.clientVersion() : "-";
                    String trust = s != null ? s.trustLevel().name() : TrustLevel.NONE.name();
                    boolean sampled = s != null && s.sampledUnrelaxed();
                    int sigs = s != null ? s.signals().size() : 0;
                    send(source, locale, "command.status.line", p.getUsername(), client, ver, trust, sampled, sigs);
                }
            }
            case "pardon" -> {
                if (!source.hasPermission("haikyo.command.pardon")) {
                    send(source, locale, "command.no_permission");
                    return;
                }
                if (args.length < 2) {
                    send(source, locale, "command.pardon.usage");
                    return;
                }
                Optional<RequiresHaikyoStore.Entry> removed = plugin.trustManager().requiresStore().pardonByNameOrUuid(args[1]);
                if (removed.isPresent()) {
                    send(source, locale, "command.pardon.success", args[1]);
                } else {
                    send(source, locale, "command.pardon.not_found", args[1]);
                }
            }
            case "reload" -> {
                if (!source.hasPermission("haikyo.command.reload")) {
                    send(source, locale, "command.no_permission");
                    return;
                }
                plugin.reloadPluginConfig();
                TrustManager tm = plugin.trustManager();
                send(source, locale, "command.reload.success",
                        tm.effectiveRole().wireName(),
                        tm.config().requireHaikyo(),
                        tm.config().modRules().size());
            }
            default -> send(source, locale, "command.usage");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : List.of("status", "pardon", "reload")) {
                if (sub.startsWith(prefix) && source.hasPermission("haikyo.command." + sub)) {
                    out.add(sub);
                }
            }
            return out;
        }
        return List.of();
    }

    private String resolveLocale(CommandSource source) {
        if (source instanceof Player p && p.getEffectiveLocale() != null) {
            return Localization.normalizeLocale(p.getEffectiveLocale().toLanguageTag());
        }
        return plugin.localization().defaultLocale();
    }

    private void send(CommandSource source, String locale, String key, Object... args) {
        Localization i18n = plugin.localization();
        source.sendMessage(Component.text(i18n.getForLocale(locale, "prefix") + i18n.getForLocale(locale, key, args)));
    }
}

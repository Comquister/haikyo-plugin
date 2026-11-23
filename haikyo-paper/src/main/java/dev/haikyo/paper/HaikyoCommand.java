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
package dev.haikyo.paper;

import dev.haikyo.api.PlayerSession;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /haikyo} command executor & tab completer supporting {@code status}, {@code pardon},
 * {@code suspect}, and {@code reload} in {@code en_us}, {@code pt_br}, and {@code es_es}.
 */
public final class HaikyoCommand implements CommandExecutor, TabCompleter {

    private final HaikyoPaperPlugin plugin;

    public HaikyoCommand(HaikyoPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String locale = resolveLocale(sender);
        Localization i18n = plugin.localization();
        if (args.length == 0) {
            send(sender, locale, "command.usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> handleStatus(sender, locale, args);
            case "pardon" -> handlePardon(sender, locale, args);
            case "suspect" -> handleSuspect(sender, locale, args);
            case "reload" -> handleReload(sender, locale);
            default -> send(sender, locale, "command.usage");
        }
        return true;
    }

    private void handleStatus(CommandSender sender, String locale, String[] args) {
        if (!sender.hasPermission("haikyo.command.status")) {
            send(sender, locale, "command.no_permission");
            return;
        }
        TrustManager tm = plugin.trustManager();
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                send(sender, locale, "command.player_not_found", args[1]);
                return;
            }
            PlayerSession s = tm.getPlayerSession(target.getUniqueId()).orElse(null);
            if (s == null) {
                send(sender, locale, "command.player_not_found", args[1]);
                return;
            }
            send(sender, locale, "command.status.player",
                    target.getName(),
                    s.haikyoClient(),
                    s.clientVersion().isEmpty() ? "-" : s.clientVersion(),
                    s.clientChannel().isEmpty() ? "-" : s.clientChannel(),
                    s.trustLevel().name(),
                    s.sampledUnrelaxed(),
                    String.join(",", s.consentedScopes()),
                    s.signals().isEmpty() ? "0" : String.join(",", s.signals()));
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        long haikyoCount = 0;
        long attestedCount = 0;
        long suspectCount = 0;
        for (Player p : online) {
            Optional<PlayerSession> opt = tm.getPlayerSession(p.getUniqueId());
            if (opt.isPresent()) {
                PlayerSession s = opt.get();
                if (s.haikyoClient()) {
                    haikyoCount++;
                }
                if (s.trustLevel() == TrustLevel.ATTESTED) {
                    attestedCount++;
                } else if (s.trustLevel() == TrustLevel.SUSPECT) {
                    suspectCount++;
                }
            }
        }
        send(sender, locale, "command.status.header",
                online.size(), haikyoCount, attestedCount, suspectCount, tm.effectiveRole().wireName());
        if (online.isEmpty()) {
            send(sender, locale, "command.status.empty");
            return;
        }
        for (Player p : online) {
            PlayerSession s = tm.getPlayerSession(p.getUniqueId()).orElse(null);
            String client = (s != null && s.haikyoClient()) ? "Haikyo" : "Vanilla/Other";
            String ver = (s != null && !s.clientVersion().isEmpty()) ? s.clientVersion() : "-";
            String trust = s != null ? s.trustLevel().name() : TrustLevel.NONE.name();
            boolean sampled = s != null && s.sampledUnrelaxed();
            int sigs = s != null ? s.signals().size() : 0;
            send(sender, locale, "command.status.line", p.getName(), client, ver, trust, sampled, sigs);
        }
    }

    private void handlePardon(CommandSender sender, String locale, String[] args) {
        if (!sender.hasPermission("haikyo.command.pardon")) {
            send(sender, locale, "command.no_permission");
            return;
        }
        if (args.length < 2) {
            send(sender, locale, "command.pardon.usage");
            return;
        }
        Optional<RequiresHaikyoStore.Entry> removed = plugin.trustManager().requiresStore().pardonByNameOrUuid(args[1]);
        if (removed.isPresent()) {
            String label = removed.get().playerName().isEmpty()
                    ? removed.get().playerId().toString()
                    : removed.get().playerName();
            send(sender, locale, "command.pardon.success", label);
        } else {
            send(sender, locale, "command.pardon.not_found", args[1]);
        }
    }

    private void handleSuspect(CommandSender sender, String locale, String[] args) {
        if (!sender.hasPermission("haikyo.command.suspect")) {
            send(sender, locale, "command.no_permission");
            return;
        }
        if (args.length < 2) {
            send(sender, locale, "command.suspect.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, locale, "command.player_not_found", args[1]);
            return;
        }
        String reason = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "manual_command";
        plugin.trustManager().markSuspectAndMaybeRequire(
                target.getUniqueId(),
                target.getName(),
                reason,
                "command:" + sender.getName()
        );
        send(sender, locale, "command.suspect.success",
                target.getName(), reason, plugin.trustManager().config().promoteSuspectToRequire());
    }

    private void handleReload(CommandSender sender, String locale) {
        if (!sender.hasPermission("haikyo.command.reload")) {
            send(sender, locale, "command.no_permission");
            return;
        }
        plugin.reloadPluginConfig();
        TrustManager tm = plugin.trustManager();
        send(sender, locale, "command.reload.success",
                tm.effectiveRole().wireName(),
                tm.config().requireHaikyo(),
                tm.config().modRules().size());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("haikyo.command.status")) subs.add("status");
            if (sender.hasPermission("haikyo.command.pardon")) subs.add("pardon");
            if (sender.hasPermission("haikyo.command.suspect")) subs.add("suspect");
            if (sender.hasPermission("haikyo.command.reload")) subs.add("reload");
            return filterPrefix(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (("status".equals(sub) && sender.hasPermission("haikyo.command.status"))
                    || ("suspect".equals(sub) && sender.hasPermission("haikyo.command.suspect"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filterPrefix(names, args[1]);
            }
            if ("pardon".equals(sub) && sender.hasPermission("haikyo.command.pardon")) {
                List<String> entries = new ArrayList<>();
                for (RequiresHaikyoStore.Entry e : plugin.trustManager().requiresStore().all()) {
                    entries.add(e.playerName().isEmpty() ? e.playerId().toString() : e.playerName());
                }
                return filterPrefix(entries, args[1]);
            }
        }
        return List.of();
    }

    private String resolveLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            return Localization.normalizeLocale(player.locale().toLanguageTag());
        }
        return plugin.localization().defaultLocale();
    }

    private void send(CommandSender sender, String locale, String key, Object... args) {
        Localization i18n = plugin.localization();
        String msg = i18n.getForLocale(locale, "prefix") + i18n.getForLocale(locale, key, args);
        sender.sendMessage(Component.text(msg));
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c);
            }
        }
        return out;
    }
}

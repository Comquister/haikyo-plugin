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
package dev.haikyo.paper.anticheat;

import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.trust.TrustManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Generic anticheat adapter supporting any third-party anticheat via configurable
 * command filtering (cancels configured alert/punish commands for {@code relaxed} checks
 * when the target player is {@code ATTESTED} and not sampled).
 */
public final class GenericAnticheatAdapter implements Listener {

    private final TrustManager trustManager;

    public GenericAnticheatAdapter(TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        HaikyoConfig cfg = trustManager.config();
        if (!cfg.genericAdapterEnabled()) {
            return;
        }
        String command = event.getCommand();
        if (shouldSuppressCommand(command, cfg.genericCommandPatterns(), this::findOnlineUuid)) {
            event.setCancelled(true);
        }
    }

    /**
     * Pure-logic evaluator exposed for unit testing.
     * Pattern format: {@code "<prefix>"} (e.g. {@code "ac punish"}) or {@code "<prefix>|<checkName>"}.
     */
    public boolean shouldSuppressCommand(
            String commandLine,
            List<String> patterns,
            java.util.function.Function<String, UUID> playerLookup
    ) {
        if (commandLine == null || commandLine.isBlank() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String trimmed = commandLine.startsWith("/") ? commandLine.substring(1).trim() : commandLine.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String rawPattern : patterns) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            String prefix = rawPattern;
            String configuredCheck = null;
            int pipe = rawPattern.indexOf('|');
            if (pipe > 0) {
                prefix = rawPattern.substring(0, pipe).trim();
                configuredCheck = rawPattern.substring(pipe + 1).trim();
            }
            if (!lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String rest = trimmed.substring(prefix.length()).trim();
            String[] tokens = rest.split("\\s+");
            if (tokens.length == 0 || tokens[0].isEmpty()) {
                continue;
            }
            UUID playerId = playerLookup.apply(tokens[0]);
            if (playerId == null) {
                continue;
            }
            String checkName = configuredCheck != null && !configuredCheck.isEmpty()
                    ? configuredCheck
                    : (tokens.length >= 2 ? tokens[1] : "generic");
            TrustManager.FlagDecision decision = trustManager.evaluateFlag(playerId, "generic", checkName);
            if (decision.cancelFlag()) {
                return true;
            }
        }
        return false;
    }

    private UUID findOnlineUuid(String playerName) {
        Player p = Bukkit.getPlayerExact(playerName);
        return p == null ? null : p.getUniqueId();
    }
}

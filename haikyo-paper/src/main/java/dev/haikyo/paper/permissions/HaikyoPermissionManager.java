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
package dev.haikyo.paper.permissions;

import dev.haikyo.core.permissions.HaikyoContextData;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.paper.compat.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in extra permission manager for {@code permissions.on-haikyo} and
 * {@code permissions.on-attested} in {@code config.yml} (works without LuckPerms).
 *
 * <p>100% Folia-safe: all {@link PermissionAttachment} mutations run on
 * {@code player.getScheduler()}, NEVER {@code Bukkit.getScheduler()}.</p>
 */
public final class HaikyoPermissionManager {
    private final Plugin plugin;
    private final TrustManager trustManager;
    private final ConcurrentHashMap<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public HaikyoPermissionManager(Plugin plugin, TrustManager trustManager) {
        this.plugin = plugin;
        this.trustManager = trustManager;
    }

    /**
     * Reconciles the player's granted extra permissions on their Folia entity scheduler.
     */
    public void syncPlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        Set<String> desired = HaikyoContextData.grantedPermissionsFor(
                trustManager.getPlayerSession(playerId),
                trustManager.config().permissions()
        );
        PlatformScheduler.runForPlayer(plugin, player, () -> applyOnEntityThread(player, desired));
    }

    public void syncAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayer(player.getUniqueId());
        }
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        PermissionAttachment existing = attachments.remove(player.getUniqueId());
        if (existing != null) {
            try {
                player.removeAttachment(existing);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void clearAll() {
        for (UUID uuid : new HashSet<>(attachments.keySet())) {
            PermissionAttachment att = attachments.remove(uuid);
            if (att != null) {
                try {
                    att.remove();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void applyOnEntityThread(Player player, Set<String> desired) {
        if (!player.isOnline()) {
            clearPlayer(player);
            return;
        }
        if (desired.isEmpty()) {
            clearPlayer(player);
            return;
        }
        PermissionAttachment att = attachments.computeIfAbsent(player.getUniqueId(), id -> player.addAttachment(plugin));
        // Revoke any previously granted node no longer in desired
        for (String existingNode : new HashSet<>(att.getPermissions().keySet())) {
            if (!desired.contains(existingNode)) {
                att.unsetPermission(existingNode);
            }
        }
        // Grant all desired nodes
        for (String node : desired) {
            att.setPermission(node, true);
        }
        player.recalculatePermissions();
    }
}

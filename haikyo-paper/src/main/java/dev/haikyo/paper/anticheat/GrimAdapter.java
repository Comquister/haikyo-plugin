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

import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.paper.compat.PlatformCompat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Soft-depend GrimAC adapter using GrimAPI 1.6 {@code EventBus#get(FlagEvent.class)#onFlag}:
 * cancels flags for checks marked {@code relaxed} when the player is {@code ATTESTED} and not
 * sampled (with {@code haikyo:ac} input corroboration). Never cancels {@code always} checks.
 *
 * <p>GrimAPI types are isolated inside {@link Registrar} so this class loads cleanly on
 * Java 8 / 1.8 servers without GrimAC installed.</p>
 */
public final class GrimAdapter {
    private GrimAdapter() {
    }

    public static boolean tryRegister(Plugin plugin, TrustManager trustManager, Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            return false;
        }
        try {
            return Registrar.register(plugin, trustManager, logger);
        } catch (Throwable t) {
            logger.warning("GrimAC detected, but GrimAPI hook failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean shouldCancelFlag(TrustManager trustManager, UUID playerId, String checkName) {
        return trustManager.evaluateFlag(playerId, "grim", checkName).cancelFlag();
    }

    private static final class Registrar {
        static boolean register(Plugin plugin, TrustManager trustManager, Logger logger) {
            GrimAbstractAPI grimApi = GrimAPIProvider.get();
            if (grimApi == null) {
                return false;
            }
            GrimPlugin grimPlugin = new BasicGrimPlugin(
                    logger,
                    plugin.getDataFolder(),
                    PlatformCompat.getPluginVersion(plugin),
                    PlatformCompat.getPluginDescription(plugin),
                    PlatformCompat.getPluginAuthors(plugin)
            );
            grimApi.getEventBus().get(FlagEvent.class).onFlagSupplier(grimPlugin, (user, check, verboseSupplier, cancelled) -> {
                if (cancelled) {
                    return true;
                }
                return shouldCancelFlag(trustManager, user.getUniqueId(), check.getCheckName());
            });
            return true;
        }
    }
}

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

import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.Java8Compat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Runtime-reflection adapter for Vulcan ({@code me.frep.vulcan.api.event.VulcanFlagEvent}).
 * Avoids any compile-time dependency on proprietary Vulcan JARs to stay 100% AGPL-3.0-or-later clean.
 * Requires {@code settings.enable-api: true} in Vulcan's config.
 */
public final class VulcanAdapter implements Listener {
    private static final String VULCAN_EVENT_CLASS = "me.frep.vulcan.api.event.VulcanFlagEvent";

    private VulcanAdapter() {
    }

    @SuppressWarnings("unchecked")
    public static boolean tryRegister(Plugin plugin, TrustManager trustManager, Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vulcan")) {
            return false;
        }
        try {
            Class<?> rawClass = Class.forName(VULCAN_EVENT_CLASS);
            if (!Event.class.isAssignableFrom(rawClass)) {
                return false;
            }
            Class<? extends Event> eventClass = (Class<? extends Event>) rawClass;
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getCheck = eventClass.getMethod("getCheck");

            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    Object playerObj = getPlayer.invoke(event);
                    Object checkObj = getCheck.invoke(event);
                    if (!(playerObj instanceof Player) || checkObj == null) {
                        return;
                    }
                    Player player = (Player) playerObj;
                    String checkName = extractCheckName(checkObj);
                    TrustManager.FlagDecision decision = trustManager.evaluateFlag(
                            player.getUniqueId(), "vulcan", checkName
                    );
                    if (decision.cancelFlag() && event instanceof Cancellable) {
                        ((Cancellable) event).setCancelled(true);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            };

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    new VulcanAdapter(),
                    EventPriority.NORMAL,
                    executor,
                    plugin,
                    true
            );
            return true;
        } catch (ReflectiveOperationException e) {
            logger.fine("Vulcan API event not available (ensure enable-api: true): " + e.getMessage());
            return false;
        }
    }

    private static String extractCheckName(Object checkObj) {
        try {
            Method getName = checkObj.getClass().getMethod("getName");
            Object name = getName.invoke(checkObj);
            String base = name == null ? "unknown" : name.toString();
            try {
                Method getType = checkObj.getClass().getMethod("getType");
                Object type = getType.invoke(checkObj);
                if (type != null && !Java8Compat.isBlank(type.toString())) {
                    return base + type;
                }
            } catch (NoSuchMethodException ignored) {
            }
            return base;
        } catch (ReflectiveOperationException e) {
            return checkObj.toString();
        }
    }
}

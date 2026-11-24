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
package dev.haikyo.paper.compat;

import dev.haikyo.core.i18n.Localization;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime compatibility layer supporting CraftBukkit, Spigot, Paper, Purpur, and Folia
 * from Minecraft 1.8 through 26.3 without directly importing Paper/Adventure classes.
 */
public final class PlatformCompat {
    private static final Class<?> COMPONENT_CLASS = findClass("net.kyori.adventure.text.Component");
    private static final Class<?> COMPONENT_LIKE_CLASS = findClass("net.kyori.adventure.text.ComponentLike");
    private static final Method COMPONENT_TEXT = findMethod(COMPONENT_CLASS, "text", String.class);
    private static final Method COMPONENT_EMPTY = findMethod(COMPONENT_CLASS, "empty");
    private static final Method COMPONENT_INSERTION = findMethod(COMPONENT_CLASS, "insertion", String.class);
    private static final Method COMPONENT_APPEND = findMethod(COMPONENT_CLASS, "append", COMPONENT_LIKE_CLASS);
    private static final Method PING_GET_MOTD = findMethod(ServerListPingEvent.class, "motd");
    private static final Method PING_SET_MOTD = findMethod(ServerListPingEvent.class, "motd", COMPONENT_CLASS);
    private static final Method SENDER_SEND_COMPONENT = findMethod(CommandSender.class, "sendMessage", COMPONENT_CLASS);
    private static final Method PLAYER_KICK_COMPONENT = findMethod(Player.class, "kick", COMPONENT_CLASS);

    private static final Method PLAYER_LOCALE_MODERN = findMethod(Player.class, "locale");
    private static final Method PLAYER_GET_LOCALE_SPIGOT_112 = findMethod(Player.class, "getLocale");
    private static final Method PLAYER_SPIGOT = findMethod(Player.class, "spigot");
    private static final Method PLAYER_SPIGOT_GET_LOCALE =
            PLAYER_SPIGOT == null ? null : findMethod(PLAYER_SPIGOT.getReturnType(), "getLocale");

    private static final Method PLUGIN_GET_META = findMethod(Plugin.class, "getPluginMeta");
    private static final Method META_GET_VERSION =
            PLUGIN_GET_META == null ? null : findMethod(PLUGIN_GET_META.getReturnType(), "getVersion");
    private static final Method META_GET_DESCRIPTION =
            PLUGIN_GET_META == null ? null : findMethod(PLUGIN_GET_META.getReturnType(), "getDescription");
    private static final Method META_GET_AUTHORS =
            PLUGIN_GET_META == null ? null : findMethod(PLUGIN_GET_META.getReturnType(), "getAuthors");

    private PlatformCompat() {
    }

    /**
     * Sends a plain-text message using Paper Adventure {@code Component} when available
     * (Paper/Folia 1.16.5 - 26.3), falling back to {@link CommandSender#sendMessage(String)}
     * on Spigot and legacy Paper/CraftBukkit (1.8 - 1.16.4).
     */
    public static void sendMessage(CommandSender sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        if (COMPONENT_TEXT != null && SENDER_SEND_COMPONENT != null) {
            try {
                Object comp = COMPONENT_TEXT.invoke(null, message);
                SENDER_SEND_COMPONENT.invoke(sender, comp);
                return;
            } catch (Throwable ignored) {
            }
        }
        sender.sendMessage(message);
    }

    /**
     * Appends an invisible Adventure {@code Component.empty().insertion(insertion)} child to
     * Paper's {@link ServerListPingEvent#motd()} when running on Paper/Purpur/Folia 1.16.5+.
     * Vanilla clients ignore {@code insertion} on MOTDs and render zero characters; Haikyo
     * clients parse it during the initial Server List Ping.
     */
    public static void appendPingInsertion(ServerListPingEvent event, String insertion) {
        if (event == null || insertion == null || insertion.isEmpty()) {
            return;
        }
        if (PING_GET_MOTD == null || PING_SET_MOTD == null
                || COMPONENT_EMPTY == null || COMPONENT_INSERTION == null || COMPONENT_APPEND == null) {
            return;
        }
        try {
            Object current = PING_GET_MOTD.invoke(event);
            if (current == null) {
                current = COMPONENT_EMPTY.invoke(null);
            }
            Object marker = COMPONENT_INSERTION.invoke(COMPONENT_EMPTY.invoke(null), insertion);
            Object updated = COMPONENT_APPEND.invoke(current, marker);
            PING_SET_MOTD.invoke(event, updated);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Kicks a player using Paper Adventure {@code Player#kick(Component)} when available
     * (Paper/Folia 1.16.5 - 26.3), falling back to {@link Player#kickPlayer(String)}
     * on Spigot and legacy Paper/CraftBukkit (1.8 - 1.16.4).
     */
    @SuppressWarnings("deprecation")
    public static void kickPlayer(Player player, String reason) {
        if (player == null) {
            return;
        }
        String safeReason = reason == null ? "" : reason;
        if (COMPONENT_TEXT != null && PLAYER_KICK_COMPONENT != null) {
            try {
                Object comp = COMPONENT_TEXT.invoke(null, safeReason);
                PLAYER_KICK_COMPONENT.invoke(player, comp);
                return;
            } catch (Throwable ignored) {
            }
        }
        player.kickPlayer(safeReason);
    }

    /**
     * Resolves a player's normalized client locale across all Bukkit/Spigot/Paper versions:
     * Paper 1.16.5+ {@code Player#locale()}, Spigot 1.12+ {@code Player#getLocale()},
     * Spigot 1.8-1.11 {@code player.spigot().getLocale()}, or {@code fallback}.
     */
    public static String getPlayerLocale(Player player, String fallback) {
        if (player == null) {
            return Localization.normalizeLocale(fallback);
        }
        if (PLAYER_LOCALE_MODERN != null) {
            try {
                Object loc = PLAYER_LOCALE_MODERN.invoke(player);
                if (loc instanceof Locale) {
                    return Localization.normalizeLocale(((Locale) loc).toLanguageTag());
                }
            } catch (Throwable ignored) {
            }
        }
        if (PLAYER_GET_LOCALE_SPIGOT_112 != null) {
            try {
                Object loc = PLAYER_GET_LOCALE_SPIGOT_112.invoke(player);
                if (loc instanceof String) {
                    return Localization.normalizeLocale((String) loc);
                }
            } catch (Throwable ignored) {
            }
        }
        if (PLAYER_SPIGOT != null && PLAYER_SPIGOT_GET_LOCALE != null) {
            try {
                Object spigot = PLAYER_SPIGOT.invoke(player);
                if (spigot != null) {
                    Object loc = PLAYER_SPIGOT_GET_LOCALE.invoke(spigot);
                    if (loc instanceof String) {
                        return Localization.normalizeLocale((String) loc);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return Localization.normalizeLocale(fallback);
    }

    @SuppressWarnings("deprecation")
    public static String getPluginVersion(Plugin plugin) {
        Object meta = invokePluginMeta(plugin);
        if (meta != null && META_GET_VERSION != null) {
            try {
                Object v = META_GET_VERSION.invoke(meta);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        PluginDescriptionFile desc = plugin.getDescription();
        return desc == null || desc.getVersion() == null ? "0.0.0" : desc.getVersion();
    }

    @SuppressWarnings("deprecation")
    public static String getPluginDescription(Plugin plugin) {
        Object meta = invokePluginMeta(plugin);
        if (meta != null && META_GET_DESCRIPTION != null) {
            try {
                Object d = META_GET_DESCRIPTION.invoke(meta);
                if (d instanceof String) {
                    return (String) d;
                }
            } catch (Throwable ignored) {
            }
        }
        PluginDescriptionFile desc = plugin.getDescription();
        return desc == null || desc.getDescription() == null ? "" : desc.getDescription();
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    public static List<String> getPluginAuthors(Plugin plugin) {
        Object meta = invokePluginMeta(plugin);
        if (meta != null && META_GET_AUTHORS != null) {
            try {
                Object a = META_GET_AUTHORS.invoke(meta);
                if (a instanceof List<?>) {
                    return (List<String>) a;
                }
            } catch (Throwable ignored) {
            }
        }
        PluginDescriptionFile desc = plugin.getDescription();
        return desc == null || desc.getAuthors() == null ? Collections.<String>emptyList() : desc.getAuthors();
    }

    public static String serverVersionSummary() {
        try {
            return Bukkit.getName() + " " + Bukkit.getBukkitVersion();
        } catch (Throwable t) {
            return "Bukkit";
        }
    }

    private static Object invokePluginMeta(Plugin plugin) {
        if (PLUGIN_GET_META == null || plugin == null) {
            return null;
        }
        try {
            return PLUGIN_GET_META.invoke(plugin);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        for (Class<?> p : params) {
            if (p == null) {
                return null;
            }
        }
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }
}

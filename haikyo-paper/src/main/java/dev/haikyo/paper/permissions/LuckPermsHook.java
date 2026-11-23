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

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.permissions.HaikyoContextData;
import dev.haikyo.core.trust.TrustManager;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional LuckPerms {@code ContextCalculator} hook for Paper & Folia.
 * Outer class never references LuckPerms types so the plugin loads cleanly without LuckPerms.
 */
public final class LuckPermsHook {

    public interface Handle extends AutoCloseable {
        void signalPlayerUpdate(UUID playerId);

        @Override
        void close();
    }

    private LuckPermsHook() {
    }

    public static Handle tryRegister(TrustManager trustManager, Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return null;
        }
        try {
            return Registrar.register(trustManager);
        } catch (Throwable t) {
            logger.fine("LuckPerms API unavailable: " + t.getMessage());
            return null;
        }
    }

    private static final class Registrar {
        static Handle register(TrustManager trustManager) {
            LuckPerms lp = LuckPermsProvider.get();
            CalculatorImpl calc = new CalculatorImpl(trustManager);
            lp.getContextManager().registerCalculator(calc);
            return new Handle() {
                @Override
                public void signalPlayerUpdate(UUID playerId) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        lp.getContextManager().signalContextUpdate(player);
                    }
                }

                @Override
                public void close() {
                    try {
                        lp.getContextManager().unregisterCalculator(calc);
                    } catch (Throwable ignored) {
                    }
                }
            };
        }
    }

    private static final class CalculatorImpl implements ContextCalculator<Player> {
        private final TrustManager trustManager;

        CalculatorImpl(TrustManager trustManager) {
            this.trustManager = trustManager;
        }

        @Override
        public void calculate(Player target, ContextConsumer consumer) {
            Map<String, String> contexts = HaikyoContextData.contextsFor(
                    trustManager.getPlayerSession(target.getUniqueId())
            );
            for (Map.Entry<String, String> e : contexts.entrySet()) {
                consumer.accept(e.getKey(), e.getValue());
            }
        }

        @Override
        public ContextSet estimatePotentialContexts() {
            ImmutableContextSet.Builder b = ImmutableContextSet.builder();
            b.add(HaikyoContextData.KEY_CLIENT, "true");
            b.add(HaikyoContextData.KEY_CLIENT, "false");
            b.add(HaikyoContextData.KEY_ATTESTED, "true");
            b.add(HaikyoContextData.KEY_ATTESTED, "false");
            for (TrustLevel lvl : TrustLevel.values()) {
                b.add(HaikyoContextData.KEY_TRUST, lvl.wireName());
            }
            b.add(HaikyoContextData.KEY_CHANNEL, "stable");
            b.add(HaikyoContextData.KEY_CHANNEL, "release");
            b.add(HaikyoContextData.KEY_CHANNEL, "beta");
            b.add(HaikyoContextData.KEY_CHANNEL, "dev");
            return b.build();
        }
    }
}

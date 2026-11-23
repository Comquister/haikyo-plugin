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

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.permissions.HaikyoContextData;
import dev.haikyo.core.trust.TrustManager;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Optional LuckPerms {@code ContextCalculator} hook for Fabric 26.3.
 * Outer class never references LuckPerms types so the mod runs cleanly without LuckPerms.
 */
public final class LuckPermsFabricHook {

    public interface Handle extends AutoCloseable {
        void signalPlayerUpdate(UUID playerId);

        @Override
        void close();
    }

    private LuckPermsFabricHook() {
    }

    public static Handle tryRegister(Supplier<MinecraftServer> serverSupplier, TrustManager trustManager) {
        if (!FabricLoader.getInstance().isModLoaded("luckperms")) {
            return null;
        }
        try {
            return Registrar.register(serverSupplier, trustManager);
        } catch (Throwable t) {
            HaikyoFabricMod.LOGGER.debug("LuckPerms API unavailable on Fabric: {}", t.getMessage());
            return null;
        }
    }

    private static final class Registrar {
        static Handle register(Supplier<MinecraftServer> serverSupplier, TrustManager trustManager) {
            LuckPerms lp = LuckPermsProvider.get();
            CalculatorImpl calc = new CalculatorImpl(trustManager);
            lp.getContextManager().registerCalculator(calc);
            return new Handle() {
                @Override
                public void signalPlayerUpdate(UUID playerId) {
                    MinecraftServer srv = serverSupplier.get();
                    if (srv != null) {
                        ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
                        if (player != null) {
                            lp.getContextManager().signalContextUpdate(player);
                        }
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

    private static final class CalculatorImpl implements ContextCalculator<ServerPlayer> {
        private final TrustManager trustManager;

        CalculatorImpl(TrustManager trustManager) {
            this.trustManager = trustManager;
        }

        @Override
        public void calculate(ServerPlayer target, ContextConsumer consumer) {
            Map<String, String> contexts = HaikyoContextData.contextsFor(
                    trustManager.getPlayerSession(target.getUUID())
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

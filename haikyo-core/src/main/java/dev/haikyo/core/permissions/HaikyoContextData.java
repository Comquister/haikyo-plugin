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
package dev.haikyo.core.permissions;

import dev.haikyo.api.PlayerSession;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.config.HaikyoConfig;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-Java computation of dynamic LuckPerms contexts and built-in extra permissions
 * from a player's {@link PlayerSession}, shared identically across Paper, Folia, Velocity, and Fabric.
 */
public final class HaikyoContextData {
    public static final String KEY_CLIENT = "haikyo-client";
    public static final String KEY_ATTESTED = "haikyo-attested";
    public static final String KEY_TRUST = "haikyo-trust";
    public static final String KEY_CHANNEL = "haikyo-channel";

    private HaikyoContextData() {
    }

    /**
     * Computes the exact LuckPerms context key/value pairs for an online player's session.
     */
    public static Map<String, String> contextsFor(Optional<PlayerSession> sessionOpt) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (sessionOpt == null || sessionOpt.isEmpty()) {
            ctx.put(KEY_CLIENT, "false");
            ctx.put(KEY_ATTESTED, "false");
            ctx.put(KEY_TRUST, TrustLevel.NONE.wireName());
            return ctx;
        }
        PlayerSession s = sessionOpt.get();
        boolean isClient = s.haikyoClient();
        boolean isAttested = s.trustLevel() == TrustLevel.ATTESTED;
        ctx.put(KEY_CLIENT, isClient ? "true" : "false");
        ctx.put(KEY_ATTESTED, isAttested ? "true" : "false");
        ctx.put(KEY_TRUST, s.trustLevel().wireName());
        if (isClient && s.clientChannel() != null && !s.clientChannel().isBlank()) {
            ctx.put(KEY_CHANNEL, s.clientChannel().trim().toLowerCase(Locale.ROOT));
        }
        return ctx;
    }

    /**
     * Computes the set of built-in extra permission nodes that should be granted right now
     * for a player according to {@code permissions.on-haikyo} and {@code permissions.on-attested}.
     */
    public static Set<String> grantedPermissionsFor(Optional<PlayerSession> sessionOpt, HaikyoConfig.PermissionsConfig permCfg) {
        if (sessionOpt == null || sessionOpt.isEmpty() || permCfg == null) {
            return Set.of();
        }
        PlayerSession s = sessionOpt.get();
        Set<String> granted = new LinkedHashSet<>();
        if (s.haikyoClient()) {
            for (String node : permCfg.onHaikyo()) {
                if (node != null && !node.isBlank()) {
                    granted.add(node.trim());
                }
            }
        }
        if (s.trustLevel() == TrustLevel.ATTESTED) {
            for (String node : permCfg.onAttested()) {
                if (node != null && !node.isBlank()) {
                    granted.add(node.trim());
                }
            }
        }
        return granted;
    }
}

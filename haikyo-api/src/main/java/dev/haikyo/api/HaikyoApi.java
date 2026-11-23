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
package dev.haikyo.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public service API exposed by the Haikyo server plugin for third-party plugins and server mods.
 */
public interface HaikyoApi {

    /**
     * Registers a server-side endpoint for the Haikyo mod channel {@code haikyo:mod/<modId>}.
     *
     * @param modId   lowercase mod identifier (e.g. {@code "waypoints"})
     * @param version protocol capability version advertised as {@code <modId>@<version>} in {@code hello}
     * @param handler callback invoked when a client sends bytes on this mod channel
     */
    void registerModEndpoint(String modId, String version, ModMessageHandler handler);

    /**
     * Unregisters a previously registered mod endpoint.
     */
    void unregisterModEndpoint(String modId);

    /**
     * Sends an opaque payload to a connected Haikyo client on {@code haikyo:mod/<modId>}.
     * Framing and fragmentation are handled automatically by the Haikyo plugin core.
     *
     * @return {@code true} if the payload was accepted for delivery, {@code false} if the player is offline
     *         or does not have a Haikyo session supporting the mod channel
     */
    boolean sendToPlayer(UUID playerId, String modId, byte[] payload);

    /**
     * Returns the current {@link TrustLevel} for the given player (defaults to {@link TrustLevel#NONE}).
     */
    TrustLevel getTrustLevel(UUID playerId);

    /**
     * Returns whether the given player completed the Haikyo protocol handshake.
     */
    boolean isHaikyoClient(UUID playerId);

    /**
     * Returns an immutable snapshot of the player's Haikyo session, if online.
     */
    Optional<PlayerSession> getPlayerSession(UUID playerId);

    /**
     * Registers a listener for {@link TrustChangeEvent} notifications.
     */
    void addTrustChangeListener(TrustChangeListener listener);

    /**
     * Unregisters a {@link TrustChangeListener}.
     */
    void removeTrustChangeListener(TrustChangeListener listener);

    /**
     * Registers a listener for {@link ModSignalEvent} notifications.
     */
    void addModSignalListener(ModSignalListener listener);

    /**
     * Unregisters a {@link ModSignalListener}.
     */
    void removeModSignalListener(ModSignalListener listener);

    /**
     * Marks an online player as {@link TrustLevel#SUSPECT} with an explanatory reason.
     *
     * @return {@code true} if the player session existed and was marked suspect
     */
    boolean markSuspect(UUID playerId, String reason);
}

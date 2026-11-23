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

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Raw-bytes {@link CustomPacketPayload} preserving exact {@code haikyo:*} wire framing
 * without any extra length prefix so Fabric matches Paper, Folia, Velocity, and Horizon byte-for-byte.
 */
public record RawHaikyoPayload(CustomPacketPayload.Type<RawHaikyoPayload> payloadType, byte[] data)
        implements CustomPacketPayload {

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return payloadType;
    }

    public static Type<RawHaikyoPayload> typeFor(String channel) {
        return new Type<>(Identifier.parse(channel));
    }

    public static StreamCodec<FriendlyByteBuf, RawHaikyoPayload> codecFor(Type<RawHaikyoPayload> type) {
        return StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> {
                    int readable = buf.readableBytes();
                    byte[] bytes = new byte[readable];
                    buf.readBytes(bytes);
                    return new RawHaikyoPayload(type, bytes);
                }
        );
    }
}

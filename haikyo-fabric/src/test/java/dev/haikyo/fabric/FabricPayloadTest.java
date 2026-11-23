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

import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FabricPayloadTest {

    @Test
    void rawHaikyoPayloadPreservesExactWireBytesWithoutExtraLengthPrefix() {
        var type = RawHaikyoPayload.typeFor(HaikyoChannels.CONTROL);
        var codec = RawHaikyoPayload.codecFor(type);

        byte[] wire = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_CONTROL_HELLO,
                "{\"protocol_version\":1}".getBytes(StandardCharsets.UTF_8)
        );
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, new RawHaikyoPayload(type, wire));
        assertEquals(wire.length, buf.readableBytes(), "Raw payload must write exact wire bytes with no length prefix");

        RawHaikyoPayload decoded = codec.decode(buf);
        assertEquals(type, decoded.type());
        assertArrayEquals(wire, decoded.data());
    }

    @Test
    void voicePluginIdMatchesHaikyo() {
        assertEquals("haikyo", new HaikyoFabricVoicePlugin().getPluginId());
    }
}

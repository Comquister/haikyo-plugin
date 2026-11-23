/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.core;

import dev.haikyo.core.protocol.FragmentReassembler;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WireFrameAndReassemblerTest {

    @Test
    void unfragmentedRoundtripAndTruncatedAndOversize() {
        byte[] body = "{\"hello\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] wire = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_CONTROL_HELLO, body);

        WireFrame.DecodeOk ok = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(wire, HaikyoChannels.MAX_C2S_WIRE_BYTES));
        assertArrayEquals(body, ok.frame().payload());
        assertFalse(ok.frame().fragmented());

        // Truncated header
        assertInstanceOf(WireFrame.DecodeInvalid.class, WireFrame.decode(new byte[]{'H', 'K', 0, 1}, HaikyoChannels.MAX_C2S_WIRE_BYTES));
        // Bad magic
        assertInstanceOf(WireFrame.DecodeInvalid.class, WireFrame.decode(new byte[]{'X', 'Y', 0, 1, 0, 1}, HaikyoChannels.MAX_C2S_WIRE_BYTES));
        // Oversize rejected
        assertInstanceOf(WireFrame.DecodeInvalid.class, WireFrame.decode(wire, wire.length - 1));
    }

    @Test
    void higherProtocolVersionIgnoredByReassembler() {
        byte[] v99 = WireFrame.encodeUnfragmented(99, 1, new byte[]{1, 2, 3});
        FragmentReassembler r = new FragmentReassembler();
        var res = r.accept(v99, HaikyoChannels.MAX_C2S_WIRE_BYTES, Instant.now());
        var ignored = assertInstanceOf(FragmentReassembler.IgnoredHigherVersion.class, res);
        assertEquals(99, ignored.protocolVersion());
    }

    @Test
    void conflictingDuplicateAndPendingLimitRejected() {
        Instant now = Instant.ofEpochSecond(1_795_300_000L);
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        List<byte[]> frames = WireFrame.encodeForWire(1, payload, 120, 77);
        assertTrue(frames.size() >= 3);

        FragmentReassembler r = new FragmentReassembler();
        assertInstanceOf(FragmentReassembler.Pending.class, r.accept(frames.get(0), 120, now));

        // Mutate chunk byte on duplicate frag_idx 0 -> must reject
        byte[] mutated = frames.get(0).clone();
        mutated[mutated.length - 1] = (byte) (mutated[mutated.length - 1] ^ 0xFF);
        assertInstanceOf(FragmentReassembler.Rejected.class, r.accept(mutated, 120, now));

        // Enforce MAX_PENDING_FRAG_IDS = 8
        FragmentReassembler capped = new FragmentReassembler();
        for (int id = 1; id <= HaikyoChannels.MAX_PENDING_FRAG_IDS; id++) {
            List<byte[]> f = WireFrame.encodeForWire(1, payload, 120, id);
            assertInstanceOf(FragmentReassembler.Pending.class, capped.accept(f.get(0), 120, now));
        }
        List<byte[]> ninth = WireFrame.encodeForWire(1, payload, 120, 999);
        assertInstanceOf(FragmentReassembler.Rejected.class, capped.accept(ninth.get(0), 120, now));
    }
}

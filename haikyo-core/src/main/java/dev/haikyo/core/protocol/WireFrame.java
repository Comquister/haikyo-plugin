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
package dev.haikyo.core.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Big-Endian binary wire frame encoder and decoder for all {@code haikyo:*} channels.
 *
 * <p>Unfragmented (6 B header): {@code "HK" | proto_ver:u16 | flags:u8(0x00) | pkt_type:u8 | body}</p>
 * <p>Fragmented (46 B header): {@code "HK" | proto_ver:u16 | flags:u8(0x01) | pkt_type:u8 | frag_id:u32 | frag_idx:u16 | frag_total:u16 | sha256:[32] | chunk}</p>
 */
public final class WireFrame {

    public record DecodedFrame(
            int protocolVersion,
            boolean fragmented,
            int packetType,
            int fragId,
            int fragIndex,
            int fragTotal,
            byte[] payloadSha256,
            byte[] payload
    ) {
    }

    public sealed interface DecodeResult permits DecodeOk, DecodeHigherVersion, DecodeInvalid {
    }

    public record DecodeOk(DecodedFrame frame) implements DecodeResult {
    }

    public record DecodeHigherVersion(int protocolVersion) implements DecodeResult {
    }

    public record DecodeInvalid(String reason) implements DecodeResult {
    }

    private WireFrame() {
    }

    public static byte[] encodeUnfragmented(int packetType, byte[] body) {
        return encodeUnfragmented(HaikyoChannels.PROTOCOL_VERSION, packetType, body);
    }

    public static byte[] encodeUnfragmented(int protocolVersion, int packetType, byte[] body) {
        byte[] data = body == null ? new byte[0] : body;
        ByteBuffer buf = ByteBuffer.allocate(HaikyoChannels.UNFRAGMENTED_HEADER_BYTES + data.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put(HaikyoChannels.MAGIC_0);
        buf.put(HaikyoChannels.MAGIC_1);
        buf.putShort((short) protocolVersion);
        buf.put((byte) HaikyoChannels.FLAG_UNFRAGMENTED);
        buf.put((byte) (packetType & 0xFF));
        buf.put(data);
        return buf.array();
    }

    public static List<byte[]> encodeForWire(int packetType, byte[] body, int maxWireBytes, int fragId) {
        byte[] data = body == null ? new byte[0] : body;
        if (data.length > HaikyoChannels.MAX_REASSEMBLED_BYTES) {
            throw new IllegalArgumentException("Payload exceeds max reassembled bytes: " + data.length);
        }
        if (HaikyoChannels.UNFRAGMENTED_HEADER_BYTES + data.length <= maxWireBytes) {
            return List.of(encodeUnfragmented(packetType, data));
        }
        int maxChunk = maxWireBytes - HaikyoChannels.FRAGMENTED_HEADER_BYTES;
        if (maxChunk <= 0) {
            throw new IllegalArgumentException("maxWireBytes too small for fragmentation: " + maxWireBytes);
        }
        int total = (data.length + maxChunk - 1) / maxChunk;
        if (total < 1 || total > HaikyoChannels.MAX_FRAGMENTS) {
            throw new IllegalArgumentException("Fragment count out of bounds: " + total);
        }
        byte[] sha256 = sha256(data);
        List<byte[]> frames = new ArrayList<>(total);
        for (int idx = 0; idx < total; idx++) {
            int offset = idx * maxChunk;
            int chunkLen = Math.min(maxChunk, data.length - offset);
            ByteBuffer buf = ByteBuffer.allocate(HaikyoChannels.FRAGMENTED_HEADER_BYTES + chunkLen)
                    .order(ByteOrder.BIG_ENDIAN);
            buf.put(HaikyoChannels.MAGIC_0);
            buf.put(HaikyoChannels.MAGIC_1);
            buf.putShort((short) HaikyoChannels.PROTOCOL_VERSION);
            buf.put((byte) HaikyoChannels.FLAG_FRAGMENTED);
            buf.put((byte) (packetType & 0xFF));
            buf.putInt(fragId);
            buf.putShort((short) idx);
            buf.putShort((short) total);
            buf.put(sha256);
            buf.put(data, offset, chunkLen);
            frames.add(buf.array());
        }
        return frames;
    }

    public static DecodeResult decode(byte[] wireBytes, int maxWireBytes) {
        if (wireBytes == null || wireBytes.length < HaikyoChannels.UNFRAGMENTED_HEADER_BYTES) {
            return new DecodeInvalid("truncated frame header");
        }
        if (wireBytes.length > maxWireBytes) {
            return new DecodeInvalid("frame exceeds wire limit (" + wireBytes.length + " > " + maxWireBytes + ")");
        }
        if (wireBytes[0] != HaikyoChannels.MAGIC_0 || wireBytes[1] != HaikyoChannels.MAGIC_1) {
            return new DecodeInvalid("invalid magic bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(2);
        int protoVer = Short.toUnsignedInt(buf.getShort());
        if (protoVer == 0) {
            return new DecodeInvalid("protocol version 0 is invalid");
        }
        if (protoVer > HaikyoChannels.PROTOCOL_VERSION) {
            return new DecodeHigherVersion(protoVer);
        }
        int flags = Byte.toUnsignedInt(buf.get());
        int pktType = Byte.toUnsignedInt(buf.get());
        if (flags == HaikyoChannels.FLAG_UNFRAGMENTED) {
            byte[] body = Arrays.copyOfRange(wireBytes, HaikyoChannels.UNFRAGMENTED_HEADER_BYTES, wireBytes.length);
            return new DecodeOk(new DecodedFrame(protoVer, false, pktType, 0, 0, 1, null, body));
        }
        if (flags == HaikyoChannels.FLAG_FRAGMENTED) {
            if (wireBytes.length < HaikyoChannels.FRAGMENTED_HEADER_BYTES) {
                return new DecodeInvalid("truncated fragmented header");
            }
            int fragId = buf.getInt();
            int fragIdx = Short.toUnsignedInt(buf.getShort());
            int fragTotal = Short.toUnsignedInt(buf.getShort());
            if (fragTotal < 1 || fragTotal > HaikyoChannels.MAX_FRAGMENTS) {
                return new DecodeInvalid("frag_total out of range: " + fragTotal);
            }
            if (fragIdx >= fragTotal) {
                return new DecodeInvalid("frag_idx >= frag_total: " + fragIdx + "/" + fragTotal);
            }
            byte[] sha256 = new byte[32];
            buf.get(sha256);
            byte[] chunk = Arrays.copyOfRange(wireBytes, HaikyoChannels.FRAGMENTED_HEADER_BYTES, wireBytes.length);
            return new DecodeOk(new DecodedFrame(protoVer, true, pktType, fragId, fragIdx, fragTotal, sha256, chunk));
        }
        return new DecodeInvalid("unknown flags byte: " + flags);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

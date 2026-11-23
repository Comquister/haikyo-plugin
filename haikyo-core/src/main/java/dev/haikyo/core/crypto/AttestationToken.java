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
package dev.haikyo.core.crypto;

import dev.haikyo.core.util.Java8Compat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Canonical binary Ed25519 attestation token ({@code HKAT} v1).
 *
 * <pre>
 * payload = "HKAT" | ver:u8(1) | key_id:lp_str | version:lp_str | channel:lp_str
 *         | exe_sha256:[32] | player_uuid:[16] | nonce:[32]
 *         | server_address:lp_str | issued_at:i64 | expires_at:i64
 * token   = payload || ed25519_sig[64]
 * </pre>
 */
public final class AttestationToken {
    public static final byte[] MAGIC = {'H', 'K', 'A', 'T'};
    public static final int FORMAT_VERSION = 1;
    public static final int SIGNATURE_LENGTH = 64;

    private final int versionByte;
    private final String keyId;
    private final String clientVersion;
    private final String channel;
    private final byte[] exeSha256;
    private final UUID playerUuid;
    private final byte[] nonce;
    private final String serverAddress;
    private final long issuedAt;
    private final long expiresAt;
    private final byte[] signedPayloadBytes;
    private final byte[] signature;

    public AttestationToken(
            int versionByte,
            String keyId,
            String clientVersion,
            String channel,
            byte[] exeSha256,
            UUID playerUuid,
            byte[] nonce,
            String serverAddress,
            long issuedAt,
            long expiresAt,
            byte[] signedPayloadBytes,
            byte[] signature
    ) {
        this.versionByte = versionByte;
        this.keyId = keyId;
        this.clientVersion = clientVersion;
        this.channel = channel;
        this.exeSha256 = exeSha256;
        this.playerUuid = playerUuid;
        this.nonce = nonce;
        this.serverAddress = serverAddress;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.signedPayloadBytes = signedPayloadBytes;
        this.signature = signature;
    }

    public int versionByte() { return versionByte; }
    public String keyId() { return keyId; }
    public String clientVersion() { return clientVersion; }
    public String channel() { return channel; }
    public byte[] exeSha256() { return exeSha256; }
    public UUID playerUuid() { return playerUuid; }
    public byte[] nonce() { return nonce; }
    public String serverAddress() { return serverAddress; }
    public long issuedAt() { return issuedAt; }
    public long expiresAt() { return expiresAt; }
    public byte[] signedPayloadBytes() { return signedPayloadBytes; }
    public byte[] signature() { return signature; }

    public static AttestationToken decodeBase64(String base64) {
        if (Java8Compat.isBlank(base64)) {
            throw new IllegalArgumentException("Empty attestation token");
        }
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        return decode(raw);
    }

    public static AttestationToken decode(byte[] tokenBytes) {
        if (tokenBytes == null || tokenBytes.length < 4 + 1 + 32 + 16 + 32 + 8 + 8 + SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("Attestation token too short");
        }
        int payloadLen = tokenBytes.length - SIGNATURE_LENGTH;
        byte[] payload = Arrays.copyOfRange(tokenBytes, 0, payloadLen);
        byte[] sig = Arrays.copyOfRange(tokenBytes, payloadLen, tokenBytes.length);

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        for (byte m : MAGIC) {
            if (buf.get() != m) {
                throw new IllegalArgumentException("Invalid HKAT magic");
            }
        }
        int ver = Byte.toUnsignedInt(buf.get());
        if (ver != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported HKAT version: " + ver);
        }
        String keyId = readLpStr(buf);
        String clientVersion = readLpStr(buf);
        String channel = readLpStr(buf);
        byte[] exeSha256 = readExact(buf, 32);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID playerUuid = new UUID(msb, lsb);
        byte[] nonce = readExact(buf, 32);
        String serverAddress = readLpStr(buf);
        long issuedAt = buf.getLong();
        long expiresAt = buf.getLong();
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes in HKAT payload: " + buf.remaining());
        }
        return new AttestationToken(
                ver, keyId, clientVersion, channel, exeSha256, playerUuid, nonce,
                serverAddress, issuedAt, expiresAt, payload, sig
        );
    }

    public static byte[] encodePayload(
            String keyId,
            String clientVersion,
            String channel,
            byte[] exeSha256,
            UUID playerUuid,
            byte[] nonce,
            String serverAddress,
            long issuedAt,
            long expiresAt
    ) {
        if (exeSha256 == null || exeSha256.length != 32) {
            throw new IllegalArgumentException("exeSha256 must be 32 bytes");
        }
        if (nonce == null || nonce.length != 32) {
            throw new IllegalArgumentException("nonce must be 32 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(FORMAT_VERSION);
        writeLpStr(out, keyId);
        writeLpStr(out, clientVersion);
        writeLpStr(out, channel);
        out.write(exeSha256, 0, exeSha256.length);
        ByteBuffer uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        uuidBuf.putLong(playerUuid.getMostSignificantBits());
        uuidBuf.putLong(playerUuid.getLeastSignificantBits());
        byte[] uuidArr = uuidBuf.array();
        out.write(uuidArr, 0, uuidArr.length);
        out.write(nonce, 0, nonce.length);
        writeLpStr(out, ServerAddressNormalizer.normalize(serverAddress));
        ByteBuffer times = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        times.putLong(issuedAt);
        times.putLong(expiresAt);
        byte[] timesArr = times.array();
        out.write(timesArr, 0, timesArr.length);
        return out.toByteArray();
    }

    static String readLpStr(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            throw new IllegalArgumentException("Truncated lp_str length");
        }
        int len = Short.toUnsignedInt(buf.getShort());
        if (buf.remaining() < len) {
            throw new IllegalArgumentException("Truncated lp_str bytes");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeLpStr(ByteArrayOutputStream out, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("lp_str exceeds u16 length: " + bytes.length);
        }
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] readExact(ByteBuffer buf, int len) {
        if (buf.remaining() < len) {
            throw new IllegalArgumentException("Truncated binary field");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return bytes;
    }
}

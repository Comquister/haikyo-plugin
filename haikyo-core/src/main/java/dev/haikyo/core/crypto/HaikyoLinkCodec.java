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

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.util.Hex;
import dev.haikyo.core.util.Java8Compat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Binary HMAC-SHA256 codec for {@code haikyo:link} (Velocity proxy <-> backend session transfer).
 */
public final class HaikyoLinkCodec {
    public static final byte[] MAGIC = {'H', 'K', 'L', 'K'};
    public static final byte[] REQUIRE_SYNC_MAGIC = {'H', 'K', 'R', 'Q'};
    public static final int FORMAT_VERSION = 1;
    public static final int HMAC_LENGTH = 32;
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 15L;
    public static final long REQUIRE_SYNC_FRESHNESS_SECONDS = 60L;

    public static final int ACTION_PARDON = 0;
    public static final int ACTION_REQUIRE = 1;

    public static final class RequireSync {
        private final boolean require;
        private final UUID playerUuid;
        private final String playerName;
        private final String reason;
        private final long issuedAt;

        public RequireSync(boolean require, UUID playerUuid, String playerName, String reason, long issuedAt) {
            this.require = require;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.reason = reason;
            this.issuedAt = issuedAt;
        }

        public boolean require() { return require; }
        public UUID playerUuid() { return playerUuid; }
        public String playerName() { return playerName; }
        public String reason() { return reason; }
        public long issuedAt() { return issuedAt; }
    }

    public static final class RequireSyncDecodeResult {
        private final boolean valid;
        private final FailureCode failureCode;
        private final String detail;
        private final RequireSync sync;

        public RequireSyncDecodeResult(boolean valid, FailureCode failureCode, String detail, RequireSync sync) {
            this.valid = valid;
            this.failureCode = failureCode;
            this.detail = detail;
            this.sync = sync;
        }

        public boolean valid() { return valid; }
        public FailureCode failureCode() { return failureCode; }
        public String detail() { return detail; }
        public RequireSync sync() { return sync; }

        public static RequireSyncDecodeResult ok(RequireSync sync) {
            return new RequireSyncDecodeResult(true, null, null, sync);
        }

        public static RequireSyncDecodeResult fail(FailureCode code, String detail) {
            return new RequireSyncDecodeResult(false, code, detail, null);
        }
    }

    public static final class LinkSession {
        private final UUID playerUuid;
        private final boolean attested;
        private final TrustLevel trustLevel;
        private final String clientVersion;
        private final String channel;
        private final String proxyId;
        private final Set<String> consentedScopes;
        private final int signalCount;
        private final long issuedAt;
        private final long expiresAt;
        private final byte[] sessionKey;

        public LinkSession(
                UUID playerUuid,
                boolean attested,
                TrustLevel trustLevel,
                String clientVersion,
                String channel,
                String proxyId,
                Set<String> consentedScopes,
                int signalCount,
                long issuedAt,
                long expiresAt,
                byte[] sessionKey
        ) {
            this.playerUuid = playerUuid;
            this.attested = attested;
            this.trustLevel = trustLevel;
            this.clientVersion = clientVersion;
            this.channel = channel;
            this.proxyId = proxyId;
            this.consentedScopes = consentedScopes;
            this.signalCount = signalCount;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.sessionKey = sessionKey;
        }

        public UUID playerUuid() { return playerUuid; }
        public boolean attested() { return attested; }
        public TrustLevel trustLevel() { return trustLevel; }
        public String clientVersion() { return clientVersion; }
        public String channel() { return channel; }
        public String proxyId() { return proxyId; }
        public Set<String> consentedScopes() { return consentedScopes; }
        public int signalCount() { return signalCount; }
        public long issuedAt() { return issuedAt; }
        public long expiresAt() { return expiresAt; }
        public byte[] sessionKey() { return sessionKey; }
    }

    public enum FailureCode {
        MISSING_SECRET,
        MALFORMED_PAYLOAD,
        INVALID_HMAC,
        SESSION_EXPIRED,
        UUID_MISMATCH
    }

    public static final class DecodeResult {
        private final boolean valid;
        private final FailureCode failureCode;
        private final String detail;
        private final LinkSession session;

        public DecodeResult(boolean valid, FailureCode failureCode, String detail, LinkSession session) {
            this.valid = valid;
            this.failureCode = failureCode;
            this.detail = detail;
            this.session = session;
        }

        public boolean valid() { return valid; }
        public FailureCode failureCode() { return failureCode; }
        public String detail() { return detail; }
        public LinkSession session() { return session; }

        public static DecodeResult ok(LinkSession session) {
            return new DecodeResult(true, null, null, session);
        }

        public static DecodeResult fail(FailureCode code, String detail) {
            return new DecodeResult(false, code, detail, null);
        }
    }

    private HaikyoLinkCodec() {
    }

    public static byte[] encodeSignedSession(LinkSession session, String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            throw new IllegalArgumentException("haikyo:link shared secret must not be empty");
        }
        if (session.sessionKey() == null || session.sessionKey().length != 32) {
            throw new IllegalArgumentException("sessionKey must be 32 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(FORMAT_VERSION);

        ByteBuffer uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        uuidBuf.putLong(session.playerUuid().getMostSignificantBits());
        uuidBuf.putLong(session.playerUuid().getLeastSignificantBits());
        byte[] uuidArr = uuidBuf.array();
        out.write(uuidArr, 0, uuidArr.length);

        out.write(session.attested() ? 1 : 0);
        out.write(session.trustLevel().wireId());
        AttestationToken.writeLpStr(out, session.clientVersion());
        AttestationToken.writeLpStr(out, session.channel());
        AttestationToken.writeLpStr(out, session.proxyId());

        TreeSet<String> sortedScopes = new TreeSet<String>();
        if (session.consentedScopes() != null) {
            sortedScopes.addAll(session.consentedScopes());
        }
        AttestationToken.writeLpStr(out, String.join(",", sortedScopes));

        int sigCount = Math.max(0, Math.min(0xFFFF, session.signalCount()));
        out.write((sigCount >>> 8) & 0xFF);
        out.write(sigCount & 0xFF);

        ByteBuffer times = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        times.putLong(session.issuedAt());
        times.putLong(session.expiresAt());
        byte[] timesArr = times.array();
        out.write(timesArr, 0, timesArr.length);
        byte[] sk = session.sessionKey();
        out.write(sk, 0, sk.length);

        byte[] payload = out.toByteArray();
        byte[] hmac = hmacSha256(sharedSecret.getBytes(StandardCharsets.UTF_8), payload);
        byte[] body = new byte[payload.length + hmac.length];
        System.arraycopy(payload, 0, body, 0, payload.length);
        System.arraycopy(hmac, 0, body, payload.length, hmac.length);
        return body;
    }

    public static DecodeResult verifyAndDecodeSession(
            byte[] body,
            String sharedSecret,
            UUID expectedPlayerUuid,
            Instant now
    ) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            return DecodeResult.fail(FailureCode.MISSING_SECRET, "haikyo:link shared secret is not configured");
        }
        if (body == null || body.length < 4 + 1 + 16 + 2 + 2 + 16 + 32 + HMAC_LENGTH) {
            return DecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "haikyo:link body too short");
        }
        int payloadLen = body.length - HMAC_LENGTH;
        byte[] payload = Arrays.copyOfRange(body, 0, payloadLen);
        byte[] receivedHmac = Arrays.copyOfRange(body, payloadLen, body.length);
        byte[] expectedHmac = hmacSha256(sharedSecret.getBytes(StandardCharsets.UTF_8), payload);
        if (!Hex.constantTimeEquals(receivedHmac, expectedHmac)) {
            return DecodeResult.fail(FailureCode.INVALID_HMAC, "HMAC-SHA256 verification failed");
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            for (byte m : MAGIC) {
                if (buf.get() != m) {
                    return DecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Invalid HKLK magic");
                }
            }
            int ver = Byte.toUnsignedInt(buf.get());
            if (ver != FORMAT_VERSION) {
                return DecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Unsupported HKLK version: " + ver);
            }
            UUID playerUuid = new UUID(buf.getLong(), buf.getLong());
            boolean attested = buf.get() != 0;
            TrustLevel trustLevel = TrustLevel.fromWireId(Byte.toUnsignedInt(buf.get()));
            String clientVersion = AttestationToken.readLpStr(buf);
            String channel = AttestationToken.readLpStr(buf);
            String proxyId = AttestationToken.readLpStr(buf);
            String scopesCsv = AttestationToken.readLpStr(buf);
            int signalCount = Short.toUnsignedInt(buf.getShort());
            long issuedAt = buf.getLong();
            long expiresAt = buf.getLong();
            if (buf.remaining() != 32) {
                return DecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Invalid session_key length: " + buf.remaining());
            }
            byte[] sessionKey = new byte[32];
            buf.get(sessionKey);

            if (expectedPlayerUuid != null && !expectedPlayerUuid.equals(playerUuid)) {
                return DecodeResult.fail(FailureCode.UUID_MISMATCH, "Player UUID mismatch in haikyo:link");
            }
            long nowEpoch = now.getEpochSecond();
            if (nowEpoch > expiresAt + DEFAULT_CLOCK_SKEW_SECONDS) {
                return DecodeResult.fail(FailureCode.SESSION_EXPIRED, "haikyo:link session expired at " + expiresAt);
            }

            Set<String> scopes = new LinkedHashSet<String>();
            if (!scopesCsv.isEmpty()) {
                for (String s : scopesCsv.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        scopes.add(trimmed);
                    }
                }
            }
            return DecodeResult.ok(new LinkSession(
                    playerUuid, attested, trustLevel, clientVersion, channel, proxyId,
                    Java8Compat.copyOfSet(scopes), signalCount, issuedAt, expiresAt, sessionKey
            ));
        } catch (IllegalArgumentException e) {
            return DecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, e.getMessage());
        }
    }

    public static byte[] encodeLinkRequest(UUID playerUuid) {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(playerUuid.getMostSignificantBits());
        buf.putLong(playerUuid.getLeastSignificantBits());
        return buf.array();
    }

    public static UUID decodeLinkRequest(byte[] body) {
        if (body == null || body.length != 16) {
            throw new IllegalArgumentException("haikyo:link request must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        return new UUID(buf.getLong(), buf.getLong());
    }

    public static byte[] encodeSignedRequireSync(RequireSync sync, String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            throw new IllegalArgumentException("haikyo:link shared secret must not be empty");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write(REQUIRE_SYNC_MAGIC, 0, REQUIRE_SYNC_MAGIC.length);
        out.write(FORMAT_VERSION);
        out.write(sync.require() ? ACTION_REQUIRE : ACTION_PARDON);

        ByteBuffer uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        uuidBuf.putLong(sync.playerUuid().getMostSignificantBits());
        uuidBuf.putLong(sync.playerUuid().getLeastSignificantBits());
        byte[] uuidArr = uuidBuf.array();
        out.write(uuidArr, 0, uuidArr.length);

        AttestationToken.writeLpStr(out, sync.playerName() == null ? "" : sync.playerName());
        AttestationToken.writeLpStr(out, sync.reason() == null ? "" : sync.reason());

        ByteBuffer ts = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        ts.putLong(sync.issuedAt());
        byte[] tsArr = ts.array();
        out.write(tsArr, 0, tsArr.length);

        byte[] payload = out.toByteArray();
        byte[] hmac = hmacSha256(sharedSecret.getBytes(StandardCharsets.UTF_8), payload);
        byte[] body = new byte[payload.length + hmac.length];
        System.arraycopy(payload, 0, body, 0, payload.length);
        System.arraycopy(hmac, 0, body, payload.length, hmac.length);
        return body;
    }

    public static RequireSyncDecodeResult verifyAndDecodeRequireSync(
            byte[] body,
            String sharedSecret,
            Instant now
    ) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            return RequireSyncDecodeResult.fail(FailureCode.MISSING_SECRET, "haikyo:link shared secret is not configured");
        }
        if (body == null || body.length < 4 + 1 + 1 + 16 + 2 + 2 + 8 + HMAC_LENGTH) {
            return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "HKRQ payload too short");
        }
        int payloadLen = body.length - HMAC_LENGTH;
        byte[] payload = Arrays.copyOfRange(body, 0, payloadLen);
        byte[] receivedHmac = Arrays.copyOfRange(body, payloadLen, body.length);
        byte[] expectedHmac = hmacSha256(sharedSecret.getBytes(StandardCharsets.UTF_8), payload);
        if (!Hex.constantTimeEquals(receivedHmac, expectedHmac)) {
            return RequireSyncDecodeResult.fail(FailureCode.INVALID_HMAC, "HKRQ HMAC-SHA256 verification failed");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            for (byte m : REQUIRE_SYNC_MAGIC) {
                if (buf.get() != m) {
                    return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Invalid HKRQ magic");
                }
            }
            int ver = Byte.toUnsignedInt(buf.get());
            if (ver != FORMAT_VERSION) {
                return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Unsupported HKRQ version: " + ver);
            }
            int action = Byte.toUnsignedInt(buf.get());
            if (action != ACTION_PARDON && action != ACTION_REQUIRE) {
                return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Invalid HKRQ action: " + action);
            }
            UUID playerUuid = new UUID(buf.getLong(), buf.getLong());
            String playerName = AttestationToken.readLpStr(buf);
            String reason = AttestationToken.readLpStr(buf);
            if (buf.remaining() != 8) {
                return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, "Invalid HKRQ trailing bytes: " + buf.remaining());
            }
            long issuedAt = buf.getLong();
            long skew = Math.abs(now.getEpochSecond() - issuedAt);
            if (skew > REQUIRE_SYNC_FRESHNESS_SECONDS) {
                return RequireSyncDecodeResult.fail(FailureCode.SESSION_EXPIRED, "HKRQ timestamp outside freshness window (" + skew + "s)");
            }
            return RequireSyncDecodeResult.ok(new RequireSync(
                    action == ACTION_REQUIRE,
                    playerUuid,
                    playerName,
                    reason,
                    issuedAt
            ));
        } catch (IllegalArgumentException e) {
            return RequireSyncDecodeResult.fail(FailureCode.MALFORMED_PAYLOAD, e.getMessage());
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

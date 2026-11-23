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

import dev.haikyo.core.util.Hex;
import dev.haikyo.core.util.Java8Compat;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies Ed25519 attestation tokens using built-in JDK Ed25519 on Java 15+ and
 * RFC 8032 pure-Java fallback on Java 8-14, with zero third-party cryptography dependencies.
 */
public final class AttestationVerifier {
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 30L;

    public enum FailureCode {
        MALFORMED_TOKEN,
        UNKNOWN_KEY_ID,
        INVALID_SIGNATURE,
        TOKEN_EXPIRED,
        TOKEN_NOT_YET_VALID,
        NONCE_MISMATCH,
        UUID_MISMATCH,
        SERVER_ADDRESS_MISMATCH,
        VERSION_REVOKED
    }

    public static final class VerificationResult {
        private final boolean valid;
        private final FailureCode failureCode;
        private final String detail;
        private final AttestationToken token;

        public VerificationResult(boolean valid, FailureCode failureCode, String detail, AttestationToken token) {
            this.valid = valid;
            this.failureCode = failureCode;
            this.detail = detail;
            this.token = token;
        }

        public boolean valid() { return valid; }
        public FailureCode failureCode() { return failureCode; }
        public String detail() { return detail; }
        public AttestationToken token() { return token; }

        public static VerificationResult ok(AttestationToken token) {
            return new VerificationResult(true, null, null, token);
        }

        public static VerificationResult fail(FailureCode code, String detail) {
            return new VerificationResult(false, code, detail, null);
        }
    }

    private final Map<String, PublicKey> publicKeys;
    private final Set<String> revokedVersions;
    private final long clockSkewSeconds;

    public AttestationVerifier(Map<String, PublicKey> publicKeys, Set<String> revokedVersions) {
        this(publicKeys, revokedVersions, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public AttestationVerifier(Map<String, PublicKey> publicKeys, Set<String> revokedVersions, long clockSkewSeconds) {
        this.publicKeys = Collections.unmodifiableMap(new HashMap<String, PublicKey>(publicKeys));
        this.revokedVersions = Collections.unmodifiableSet(new HashSet<String>(revokedVersions));
        this.clockSkewSeconds = Math.max(0L, clockSkewSeconds);
    }

    /**
     * Parses a Base64-encoded Ed25519 public key (accepting either raw 32-byte keys or X.509 SPKI DER keys).
     */
    public static PublicKey parsePublicKeyBase64(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        return parsePublicKeyBytes(raw);
    }

    public static PublicKey parsePublicKeyBytes(byte[] keyBytes) {
        return PureEd25519.parsePublicKeyBytes(keyBytes);
    }

    public static AttestationVerifier fromBase64Keys(Map<String, String> keyIdToBase64, Set<String> revokedVersions) {
        Map<String, PublicKey> keys = new HashMap<String, PublicKey>();
        if (keyIdToBase64 != null) {
            for (Map.Entry<String, String> entry : keyIdToBase64.entrySet()) {
                if (entry.getKey() != null && !Java8Compat.isBlank(entry.getValue())) {
                    keys.put(entry.getKey(), parsePublicKeyBase64(entry.getValue()));
                }
            }
        }
        return new AttestationVerifier(keys, revokedVersions == null ? Collections.<String>emptySet() : revokedVersions);
    }

    public VerificationResult verifyBase64(
            String tokenBase64,
            UUID expectedPlayerUuid,
            byte[] expectedNonce,
            String expectedServerAddress,
            Instant now
    ) {
        AttestationToken token;
        try {
            token = AttestationToken.decodeBase64(tokenBase64);
        } catch (IllegalArgumentException e) {
            return VerificationResult.fail(FailureCode.MALFORMED_TOKEN, e.getMessage());
        }
        return verify(token, expectedPlayerUuid, expectedNonce, expectedServerAddress, now);
    }

    public VerificationResult verify(
            AttestationToken token,
            UUID expectedPlayerUuid,
            byte[] expectedNonce,
            String expectedServerAddress,
            Instant now
    ) {
        PublicKey pubKey = publicKeys.get(token.keyId());
        if (pubKey == null) {
            return VerificationResult.fail(FailureCode.UNKNOWN_KEY_ID, "Unknown key_id: " + token.keyId());
        }

        if (!PureEd25519.verify(pubKey, token.signedPayloadBytes(), token.signature())) {
            return VerificationResult.fail(FailureCode.INVALID_SIGNATURE, "Ed25519 signature verification failed");
        }

        long nowEpoch = now.getEpochSecond();
        if (nowEpoch > token.expiresAt() + clockSkewSeconds) {
            return VerificationResult.fail(FailureCode.TOKEN_EXPIRED, "Token expired at " + token.expiresAt());
        }
        if (token.issuedAt() > nowEpoch + clockSkewSeconds) {
            return VerificationResult.fail(FailureCode.TOKEN_NOT_YET_VALID, "Token issued in the future: " + token.issuedAt());
        }
        if (token.expiresAt() < token.issuedAt()) {
            return VerificationResult.fail(FailureCode.MALFORMED_TOKEN, "expires_at < issued_at");
        }

        if (expectedPlayerUuid != null && !expectedPlayerUuid.equals(token.playerUuid())) {
            return VerificationResult.fail(FailureCode.UUID_MISMATCH, "Token UUID mismatch");
        }
        if (expectedNonce == null || !Hex.constantTimeEquals(expectedNonce, token.nonce())) {
            return VerificationResult.fail(FailureCode.NONCE_MISMATCH, "Token nonce mismatch");
        }

        if (!Java8Compat.isBlank(expectedServerAddress)) {
            String expectedNorm = ServerAddressNormalizer.normalize(expectedServerAddress);
            String tokenNorm = ServerAddressNormalizer.normalize(token.serverAddress());
            if (!expectedNorm.equals(tokenNorm)) {
                return VerificationResult.fail(
                        FailureCode.SERVER_ADDRESS_MISMATCH,
                        "Expected server " + expectedNorm + " but got " + tokenNorm
                );
            }
        }

        if (revokedVersions.contains(token.clientVersion())) {
            return VerificationResult.fail(
                    FailureCode.VERSION_REVOKED,
                    "Client version revoked: " + token.clientVersion()
            );
        }

        return VerificationResult.ok(token);
    }
}

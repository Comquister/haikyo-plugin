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

import dev.haikyo.core.util.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies Ed25519-signed rules packages returned by {@code GET /api/rules} ({@code HKRL\x01}).
 *
 * <p>Signing input: {@code "HKRL" | 0x01 | base64_decode(payload_b64)}.
 * Always parses verified bytes from {@code payload_b64}, never trusting any unverified
 * convenience {@code rules} field in the outer JSON envelope.</p>
 */
public final class SignedRulesVerifier {
    public static final byte[] SIGNING_PREFIX = {'H', 'K', 'R', 'L', 0x01};
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 30L;

    public enum FailureCode {
        MALFORMED_ENVELOPE,
        UNKNOWN_KEY_ID,
        INVALID_SIGNATURE,
        KEY_ID_MISMATCH,
        EXPIRED,
        NOT_YET_VALID
    }

    public record SignedRulesPackage(
            int rulesVersion,
            String keyId,
            long issuedAt,
            long expiresAt,
            List<String> allowedModules,
            List<String> allowedPacks,
            Map<String, String> signals,
            List<String> revokedVersions
    ) {
    }

    public record VerificationResult(boolean valid, FailureCode failureCode, String detail, SignedRulesPackage rules) {
        public static VerificationResult ok(SignedRulesPackage pkg) {
            return new VerificationResult(true, null, null, pkg);
        }

        public static VerificationResult fail(FailureCode code, String detail) {
            return new VerificationResult(false, code, detail, null);
        }
    }

    private final Map<String, PublicKey> publicKeys;
    private final long clockSkewSeconds;

    public SignedRulesVerifier(Map<String, PublicKey> publicKeys, long clockSkewSeconds) {
        this.publicKeys = Collections.unmodifiableMap(new HashMap<>(publicKeys));
        this.clockSkewSeconds = Math.max(0L, clockSkewSeconds);
    }

    public static SignedRulesVerifier fromBase64Keys(Map<String, String> keyIdToBase64) {
        Map<String, PublicKey> keys = new HashMap<>();
        if (keyIdToBase64 != null) {
            for (Map.Entry<String, String> e : keyIdToBase64.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                    keys.put(e.getKey(), AttestationVerifier.parsePublicKeyBase64(e.getValue()));
                }
            }
        }
        return new SignedRulesVerifier(keys, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public VerificationResult verifyEnvelopeJson(String envelopeJson, Instant now) {
        Map<String, Object> env;
        try {
            env = JsonCodec.parseObject(envelopeJson);
        } catch (IllegalArgumentException e) {
            return VerificationResult.fail(FailureCode.MALFORMED_ENVELOPE, e.getMessage());
        }
        int fmtVer = JsonCodec.asInt(env, "format_version", 0);
        if (fmtVer != 1) {
            return VerificationResult.fail(FailureCode.MALFORMED_ENVELOPE, "Unsupported format_version: " + fmtVer);
        }
        String keyId = JsonCodec.asString(env, "key_id", "");
        String payloadB64 = JsonCodec.asString(env, "payload_b64", "");
        String sigB64 = JsonCodec.asString(env, "signature_b64", "");
        if (keyId.isEmpty() || payloadB64.isEmpty() || sigB64.isEmpty()) {
            return VerificationResult.fail(FailureCode.MALFORMED_ENVELOPE, "Missing key_id/payload_b64/signature_b64");
        }
        PublicKey pubKey = publicKeys.get(keyId);
        if (pubKey == null) {
            return VerificationResult.fail(FailureCode.UNKNOWN_KEY_ID, "Unknown key_id: " + keyId);
        }
        byte[] rawPayload;
        byte[] sigBytes;
        try {
            rawPayload = Base64.getDecoder().decode(payloadB64.trim());
            sigBytes = Base64.getDecoder().decode(sigB64.trim());
        } catch (IllegalArgumentException e) {
            return VerificationResult.fail(FailureCode.MALFORMED_ENVELOPE, "Invalid base64: " + e.getMessage());
        }
        if (sigBytes.length != 64) {
            return VerificationResult.fail(FailureCode.INVALID_SIGNATURE, "Invalid Ed25519 signature length");
        }
        byte[] signingInput = new byte[SIGNING_PREFIX.length + rawPayload.length];
        System.arraycopy(SIGNING_PREFIX, 0, signingInput, 0, SIGNING_PREFIX.length);
        System.arraycopy(rawPayload, 0, signingInput, SIGNING_PREFIX.length, rawPayload.length);
        if (!verifyEd25519(pubKey, signingInput, sigBytes)) {
            return VerificationResult.fail(FailureCode.INVALID_SIGNATURE, "Ed25519 verification failed");
        }

        Map<String, Object> parsed;
        try {
            parsed = JsonCodec.parseObject(new String(rawPayload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return VerificationResult.fail(FailureCode.MALFORMED_ENVELOPE, "Invalid signed rules JSON: " + e.getMessage());
        }
        String innerKeyId = JsonCodec.asString(parsed, "key_id", "");
        if (!keyId.equals(innerKeyId)) {
            return VerificationResult.fail(FailureCode.KEY_ID_MISMATCH, "Inner key_id does not match envelope key_id");
        }
        long issuedAt = JsonCodec.asLong(parsed, "issued_at", 0L);
        long expiresAt = JsonCodec.asLong(parsed, "expires_at", 0L);
        long nowEpoch = now.getEpochSecond();
        if (nowEpoch > expiresAt + clockSkewSeconds) {
            return VerificationResult.fail(FailureCode.EXPIRED, "Rules package expired at " + expiresAt);
        }
        if (issuedAt > nowEpoch + clockSkewSeconds) {
            return VerificationResult.fail(FailureCode.NOT_YET_VALID, "Rules package issued in future: " + issuedAt);
        }

        Map<String, String> signals = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonCodec.asObject(parsed, "signals").entrySet()) {
            if (e.getValue() != null) {
                signals.put(e.getKey(), e.getValue().toString());
            }
        }

        return VerificationResult.ok(new SignedRulesPackage(
                JsonCodec.asInt(parsed, "rules_version", 1),
                innerKeyId,
                issuedAt,
                expiresAt,
                JsonCodec.asStringList(parsed, "allowed_modules"),
                JsonCodec.asStringList(parsed, "allowed_packs"),
                Collections.unmodifiableMap(signals),
                JsonCodec.asStringList(parsed, "revoked_versions")
        ));
    }

    private static boolean verifyEd25519(PublicKey key, byte[] payload, byte[] sigBytes) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(payload);
            return sig.verify(sigBytes);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}

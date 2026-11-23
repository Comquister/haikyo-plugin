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
package dev.haikyo.core;

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.crypto.AttestationToken;
import dev.haikyo.core.crypto.AttestationVerifier;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.crypto.ServerAddressNormalizer;
import dev.haikyo.core.crypto.SignedRulesVerifier;
import dev.haikyo.core.protocol.FragmentReassembler;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.util.Hex;
import dev.haikyo.core.util.JsonCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-for-byte cross-language verification against {@code tools/protocol_vectors.json}.
 */
class GoldenVectorsTest {

    private static Map<String, Object> vectors;
    private static Instant refNow;

    @BeforeAll
    static void loadVectors() throws Exception {
        try (InputStream is = GoldenVectorsTest.class.getResourceAsStream("/protocol_vectors.json")) {
            assertNotNull(is, "protocol_vectors.json must be present in test resources");
            vectors = JsonCodec.parseObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            refNow = Instant.ofEpochSecond(JsonCodec.asLong(vectors, "reference_now_unix", 0L));
        }
    }

    @Test
    void attestationTokensMatchGoldenVectors() {
        Map<String, Object> keys = JsonCodec.asObject(vectors, "test_keys");
        Map<String, Object> primary = JsonCodec.asObject(keys, "attest_primary");
        Map<String, Object> rotated = JsonCodec.asObject(keys, "attest_rotated");

        AttestationVerifier verifier = AttestationVerifier.fromBase64Keys(
                Map.of(
                        JsonCodec.asString(primary, "key_id", ""), JsonCodec.asString(primary, "public_raw_b64", ""),
                        JsonCodec.asString(rotated, "key_id", ""), JsonCodec.asString(rotated, "public_spki_der_b64", "")
                ),
                Set.of("v2026.01.01-revoked")
        );

        Map<String, Object> tokens = JsonCodec.asObject(vectors, "attestation_tokens");
        Map<String, Object> fields = JsonCodec.asObject(tokens, "fields");
        UUID playerUuid = UUID.fromString(JsonCodec.asString(fields, "player_uuid", ""));
        byte[] nonce = Hex.decode(JsonCodec.asString(fields, "nonce_hex", ""));
        String rawAddr = JsonCodec.asString(fields, "server_address_raw", "");
        String normAddr = JsonCodec.asString(fields, "server_address_normalized", "");
        assertEquals(normAddr, ServerAddressNormalizer.normalize(rawAddr));

        // 1. Valid token
        Map<String, Object> validVec = JsonCodec.asObject(tokens, "valid");
        var validRes = verifier.verifyBase64(JsonCodec.asString(validVec, "token_b64", ""), playerUuid, nonce, rawAddr, refNow);
        assertTrue(validRes.valid(), "Valid golden token must pass: " + validRes.detail());
        assertEquals("v2026.11.22", validRes.token().clientVersion());
        assertEquals("release", validRes.token().channel());
        assertEquals(JsonCodec.asString(validVec, "payload_hex", ""), Hex.encode(validRes.token().signedPayloadBytes()));

        // Re-encoding payload must match byte-for-byte
        byte[] reencoded = AttestationToken.encodePayload(
                validRes.token().keyId(),
                validRes.token().clientVersion(),
                validRes.token().channel(),
                validRes.token().exeSha256(),
                validRes.token().playerUuid(),
                validRes.token().nonce(),
                rawAddr,
                validRes.token().issuedAt(),
                validRes.token().expiresAt()
        );
        assertEquals(JsonCodec.asString(validVec, "payload_hex", ""), Hex.encode(reencoded));

        // 2. Rotated key token (SPKI DER pubkey)
        Map<String, Object> rotVec = JsonCodec.asObject(tokens, "rotated_key");
        var rotRes = verifier.verifyBase64(JsonCodec.asString(rotVec, "token_b64", ""), playerUuid, nonce, normAddr, refNow);
        assertTrue(rotRes.valid(), "Rotated-key golden token must pass: " + rotRes.detail());

        // 3. Expired token
        Map<String, Object> expVec = JsonCodec.asObject(tokens, "expired");
        var expRes = verifier.verifyBase64(JsonCodec.asString(expVec, "token_b64", ""), playerUuid, nonce, normAddr, refNow);
        assertFalse(expRes.valid());
        assertEquals(AttestationVerifier.FailureCode.TOKEN_EXPIRED, expRes.failureCode());

        // 4. Tampered payload
        Map<String, Object> tpVec = JsonCodec.asObject(tokens, "tampered_payload");
        var tpRes = verifier.verifyBase64(JsonCodec.asString(tpVec, "token_b64", ""), playerUuid, nonce, normAddr, refNow);
        assertFalse(tpRes.valid());
        assertEquals(AttestationVerifier.FailureCode.INVALID_SIGNATURE, tpRes.failureCode());

        // 5. Tampered signature
        Map<String, Object> tsVec = JsonCodec.asObject(tokens, "tampered_signature");
        var tsRes = verifier.verifyBase64(JsonCodec.asString(tsVec, "token_b64", ""), playerUuid, nonce, normAddr, refNow);
        assertFalse(tsRes.valid());
        assertEquals(AttestationVerifier.FailureCode.INVALID_SIGNATURE, tsRes.failureCode());

        // 6. Wrong nonce / wrong UUID / wrong server / revoked version
        byte[] wrongNonce = nonce.clone();
        wrongNonce[0] ^= 0x01;
        assertEquals(
                AttestationVerifier.FailureCode.NONCE_MISMATCH,
                verifier.verifyBase64(JsonCodec.asString(validVec, "token_b64", ""), playerUuid, wrongNonce, normAddr, refNow).failureCode()
        );
        assertEquals(
                AttestationVerifier.FailureCode.UUID_MISMATCH,
                verifier.verifyBase64(JsonCodec.asString(validVec, "token_b64", ""), UUID.randomUUID(), nonce, normAddr, refNow).failureCode()
        );
        assertEquals(
                AttestationVerifier.FailureCode.SERVER_ADDRESS_MISMATCH,
                verifier.verifyBase64(JsonCodec.asString(validVec, "token_b64", ""), playerUuid, nonce, "other.server:25565", refNow).failureCode()
        );

        AttestationVerifier revokingVerifier = AttestationVerifier.fromBase64Keys(
                Map.of(JsonCodec.asString(primary, "key_id", ""), JsonCodec.asString(primary, "public_raw_b64", "")),
                Set.of("v2026.11.22")
        );
        assertEquals(
                AttestationVerifier.FailureCode.VERSION_REVOKED,
                revokingVerifier.verifyBase64(JsonCodec.asString(validVec, "token_b64", ""), playerUuid, nonce, normAddr, refNow).failureCode()
        );
    }

    @Test
    void linkSessionsMatchGoldenVectors() {
        Map<String, Object> links = JsonCodec.asObject(vectors, "link_sessions");
        String secret = JsonCodec.asString(links, "shared_secret", "");
        Map<String, Object> fields = JsonCodec.asObject(links, "fields");
        UUID playerUuid = UUID.fromString(JsonCodec.asString(fields, "player_uuid", ""));

        // Valid haikyo:link 0x01
        Map<String, Object> valid = JsonCodec.asObject(links, "valid");
        byte[] validFrame = Hex.decode(JsonCodec.asString(valid, "frame_hex", ""));
        WireFrame.DecodeOk ok = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(validFrame, HaikyoChannels.MAX_S2C_WIRE_BYTES));
        assertEquals(HaikyoChannels.PKT_LINK_SESSION, ok.frame().packetType());

        var decoded = HaikyoLinkCodec.verifyAndDecodeSession(ok.frame().payload(), secret, playerUuid, refNow);
        assertTrue(decoded.valid(), "Valid haikyo:link must pass: " + decoded.detail());
        assertEquals(TrustLevel.ATTESTED, decoded.session().trustLevel());
        assertEquals("proxy.haikyo.test:25565", decoded.session().proxyId());
        assertEquals(Set.of("inputs", "integrity", "mods", "version"), decoded.session().consentedScopes());

        // Re-encode must match golden frame_hex byte-for-byte
        byte[] reencodedBody = HaikyoLinkCodec.encodeSignedSession(decoded.session(), secret);
        byte[] reencodedFrame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_LINK_SESSION, reencodedBody);
        assertEquals(JsonCodec.asString(valid, "frame_hex", ""), Hex.encode(reencodedFrame));

        // Tampered HMAC
        byte[] tamperedBody = Hex.decode(JsonCodec.asString(JsonCodec.asObject(links, "tampered"), "body_hex", ""));
        var tamperedRes = HaikyoLinkCodec.verifyAndDecodeSession(tamperedBody, secret, playerUuid, refNow);
        assertFalse(tamperedRes.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.INVALID_HMAC, tamperedRes.failureCode());

        // Missing secret
        var missingRes = HaikyoLinkCodec.verifyAndDecodeSession(ok.frame().payload(), "", playerUuid, refNow);
        assertFalse(missingRes.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.MISSING_SECRET, missingRes.failureCode());

        // Expired session
        byte[] expiredBody = Hex.decode(JsonCodec.asString(JsonCodec.asObject(links, "expired"), "body_hex", ""));
        var expiredRes = HaikyoLinkCodec.verifyAndDecodeSession(expiredBody, secret, playerUuid, refNow);
        assertFalse(expiredRes.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.SESSION_EXPIRED, expiredRes.failureCode());

        // Link request 0x02
        Map<String, Object> reqVec = JsonCodec.asObject(links, "link_request");
        byte[] reqFrame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_LINK_REQUEST, HaikyoLinkCodec.encodeLinkRequest(playerUuid));
        assertEquals(JsonCodec.asString(reqVec, "frame_hex", ""), Hex.encode(reqFrame));
        assertEquals(playerUuid, HaikyoLinkCodec.decodeLinkRequest(Hex.decode(JsonCodec.asString(reqVec, "body_hex", ""))));
    }

    @Test
    void packetsIntegrityReportAndAcInputsMatchGoldenVectors() {
        Map<String, Object> packets = JsonCodec.asObject(vectors, "packets");

        // Integrity report canonical string & HMAC
        Map<String, Object> irVec = JsonCodec.asObject(packets, "integrity_report");
        byte[] irFrame = Hex.decode(JsonCodec.asString(irVec, "frame_hex", ""));
        WireFrame.DecodeOk irOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(irFrame, HaikyoChannels.MAX_C2S_WIRE_BYTES));
        ProtocolMessages.IntegrityReport report = ProtocolMessages.IntegrityReport.decodeJson(irOk.frame().payload());
        assertEquals(JsonCodec.asString(irVec, "canonical_signing_input", ""), report.canonicalSigningInput());
        byte[] sessionKey = Hex.decode(JsonCodec.asString(irVec, "session_key_hex", ""));
        assertEquals(JsonCodec.asString(irVec, "hmac_hex", ""), report.computeHmacHex(sessionKey));
        assertTrue(report.verifyHmac(sessionKey));

        // haikyo:ac inputs binary & HMAC
        Map<String, Object> acVec = JsonCodec.asObject(packets, "ac_inputs");
        byte[] acFrame = Hex.decode(JsonCodec.asString(acVec, "frame_hex", ""));
        WireFrame.DecodeOk acOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(acFrame, HaikyoChannels.MAX_C2S_WIRE_BYTES));
        ProtocolMessages.AcInputsPacket acPkt = ProtocolMessages.AcInputsPacket.decodeAndVerify(acOk.frame().payload(), sessionKey);
        assertEquals(7L, acPkt.seq());
        assertEquals(123456L, acPkt.tick());
        assertEquals(3, acPkt.samples().size());
        assertEquals(577, acPkt.samples().get(0).flags());
        assertEquals(1.5f, acPkt.samples().get(0).yawDelta());
        assertEquals(-0.25f, acPkt.samples().get(0).pitchDelta());

        // Re-encode ac_inputs must match golden body_hex byte-for-byte
        byte[] reencodedAc = ProtocolMessages.AcInputsPacket.encode(acPkt.seq(), acPkt.tick(), acPkt.samples(), sessionKey);
        assertEquals(JsonCodec.asString(acVec, "body_hex", ""), Hex.encode(reencodedAc));

        // Tampered ac_inputs must fail HMAC
        byte[] tamperedAcBody = Hex.decode(JsonCodec.asString(acVec, "tampered_body_hex", ""));
        assertThrows(SecurityException.class, () -> ProtocolMessages.AcInputsPacket.decodeAndVerify(tamperedAcBody, sessionKey));

        // Higher protocol version must be ignored gracefully
        Map<String, Object> higherVec = JsonCodec.asObject(packets, "higher_protocol_version");
        byte[] higherFrame = Hex.decode(JsonCodec.asString(higherVec, "frame_hex", ""));
        var higherRes = WireFrame.decode(higherFrame, HaikyoChannels.MAX_C2S_WIRE_BYTES);
        WireFrame.DecodeHigherVersion hv = assertInstanceOf(WireFrame.DecodeHigherVersion.class, higherRes);
        assertEquals(2, hv.protocolVersion());
    }

    @Test
    void fragmentationMatchesGoldenVectors() {
        Map<String, Object> frags = JsonCodec.asObject(vectors, "fragmentation");

        // 1. small_3_fragments: encode, out-of-order, duplicate, corrupted hash
        Map<String, Object> small = JsonCodec.asObject(frags, "small_3_fragments");
        byte[] payload = Hex.decode(JsonCodec.asString(small, "payload_hex", ""));
        int fragId = JsonCodec.asInt(small, "frag_id", 0);
        int maxWire = JsonCodec.asInt(small, "max_wire_bytes", 100);
        List<String> expectedFramesHex = JsonCodec.asStringList(small, "frames_hex");

        List<byte[]> encoded = WireFrame.encodeForWire(HaikyoChannels.PKT_MOD_OPAQUE, payload, maxWire, fragId);
        assertEquals(expectedFramesHex.size(), encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
            assertEquals(expectedFramesHex.get(i), Hex.encode(encoded.get(i)));
        }

        // Out-of-order reassembly
        FragmentReassembler ooo = new FragmentReassembler();
        FragmentReassembler.Result last = null;
        for (Object idxObj : JsonCodec.asList(small, "out_of_order_indices")) {
            int idx = ((Number) idxObj).intValue();
            last = ooo.accept(Hex.decode(expectedFramesHex.get(idx)), maxWire, refNow);
        }
        FragmentReassembler.Complete completeOoo = assertInstanceOf(FragmentReassembler.Complete.class, last);
        assertArrayEquals(payload, completeOoo.packet().body());

        // Duplicate identical fragment ignored cleanly
        FragmentReassembler dup = new FragmentReassembler();
        for (Object idxObj : JsonCodec.asList(small, "duplicate_indices")) {
            int idx = ((Number) idxObj).intValue();
            last = dup.accept(Hex.decode(expectedFramesHex.get(idx)), maxWire, refNow);
        }
        FragmentReassembler.Complete completeDup = assertInstanceOf(FragmentReassembler.Complete.class, last);
        assertArrayEquals(payload, completeDup.packet().body());

        // Corrupted hash rejected
        FragmentReassembler bad = new FragmentReassembler();
        List<String> corruptFrames = JsonCodec.asStringList(small, "corrupted_hash_frames_hex");
        for (int i = 0; i < corruptFrames.size(); i++) {
            last = bad.accept(Hex.decode(corruptFrames.get(i)), maxWire, refNow);
        }
        assertInstanceOf(FragmentReassembler.Rejected.class, last);

        // 2. c2s_boundary_2_fragments (33,000 B @ 32,767 B limit)
        Map<String, Object> boundary = JsonCodec.asObject(frags, "c2s_boundary_2_fragments");
        List<String> b64Frames = JsonCodec.asStringList(boundary, "frames_b64");
        FragmentReassembler boundaryReasm = new FragmentReassembler();
        for (String f : b64Frames) {
            byte[] wire = Base64.getDecoder().decode(f);
            assertTrue(wire.length <= HaikyoChannels.MAX_C2S_WIRE_BYTES);
            last = boundaryReasm.accept(wire, HaikyoChannels.MAX_C2S_WIRE_BYTES, refNow);
        }
        FragmentReassembler.Complete boundaryComplete = assertInstanceOf(FragmentReassembler.Complete.class, last);
        assertEquals(33000, boundaryComplete.packet().body().length);
        assertEquals(JsonCodec.asString(boundary, "payload_sha256_hex", ""), Hex.encode(WireFrame.sha256(boundaryComplete.packet().body())));
    }

    @Test
    void adminVoiceAndSignedRulesMatchGoldenVectors() {
        // 1. haikyo:admin requests & responses
        Map<String, Object> admin = JsonCodec.asObject(vectors, "admin");
        for (String reqKey : List.of("admin_request_info", "admin_request_hwid", "admin_request_screenshot")) {
            Map<String, Object> rv = JsonCodec.asObject(admin, reqKey);
            byte[] frame = Hex.decode(JsonCodec.asString(rv, "frame_hex", ""));
            var ok = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(frame, HaikyoChannels.MAX_S2C_WIRE_BYTES));
            ProtocolMessages.AdminRequest decoded = ProtocolMessages.AdminRequest.decodeJson(ok.frame().payload());
            byte[] reencoded = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ADMIN_REQUEST, decoded.encodeJson());
            assertEquals(JsonCodec.asString(rv, "frame_hex", ""), Hex.encode(reencoded));
        }

        // admin_info_response
        Map<String, Object> infoVec = JsonCodec.asObject(admin, "admin_info_response");
        var infoOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(Hex.decode(JsonCodec.asString(infoVec, "frame_hex", "")), HaikyoChannels.MAX_C2S_WIRE_BYTES));
        var infoResp = ProtocolMessages.AdminInfoResponse.decodeJson(infoOk.frame().payload());
        assertEquals("req-info-01", infoResp.requestId());
        assertEquals("Windows 11", infoResp.os());
        assertEquals(32768L, infoResp.ramMb());
        assertEquals(List.of("zoom", "waypoints"), infoResp.mods());
        assertEquals(JsonCodec.asString(infoVec, "frame_hex", ""), Hex.encode(WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ADMIN_INFO_RESPONSE, infoResp.encodeJson())));

        // admin_hwid_response
        Map<String, Object> hwidVec = JsonCodec.asObject(admin, "admin_hwid_response");
        var hwidOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(Hex.decode(JsonCodec.asString(hwidVec, "frame_hex", "")), HaikyoChannels.MAX_C2S_WIRE_BYTES));
        var hwidResp = ProtocolMessages.AdminHwidResponse.decodeJson(hwidOk.frame().payload());
        assertEquals("req-hwid-01", hwidResp.requestId());
        assertEquals(64, hwidResp.hwid().length());

        // admin_screenshot_response_ok (single & 4-fragment reassembly!)
        Map<String, Object> shotOkVec = JsonCodec.asObject(admin, "admin_screenshot_response_ok");
        byte[] shotBody = Hex.decode(JsonCodec.asString(shotOkVec, "body_hex", ""));
        var shot = ProtocolMessages.AdminScreenshotResponse.decodeBinary(shotBody);
        assertEquals("req-shot-01", shot.requestId());
        assertEquals(ProtocolMessages.AdminScreenshotResponse.STATUS_OK, shot.status());
        assertEquals(640, shot.width());
        assertEquals(360, shot.height());
        assertEquals("image/jpeg", shot.mimeType());
        assertEquals(192, shot.imageBytes().length);
        assertEquals(JsonCodec.asString(shotOkVec, "body_hex", ""), Hex.encode(shot.encodeBinary()));

        Map<String, Object> fragShot = JsonCodec.asObject(shotOkVec, "fragmented");
        int fragMaxWire = JsonCodec.asInt(fragShot, "max_wire_bytes", 120);
        FragmentReassembler shotReasm = new FragmentReassembler();
        FragmentReassembler.Result last = null;
        for (String fHex : JsonCodec.asStringList(fragShot, "frames_hex")) {
            last = shotReasm.accept(Hex.decode(fHex), fragMaxWire, refNow);
        }
        var reasmShot = assertInstanceOf(FragmentReassembler.Complete.class, last);
        assertArrayEquals(shotBody, reasmShot.packet().body());

        // admin_screenshot_response_declined
        Map<String, Object> shotDecVec = JsonCodec.asObject(admin, "admin_screenshot_response_declined");
        var shotDec = ProtocolMessages.AdminScreenshotResponse.decodeBinary(Hex.decode(JsonCodec.asString(shotDecVec, "body_hex", "")));
        assertEquals(ProtocolMessages.AdminScreenshotResponse.STATUS_CONSENT_DECLINED, shotDec.status());
        assertEquals("consent_declined", shotDec.error());
        assertEquals(0, shotDec.imageBytes().length);

        // 2. haikyo:voice configs
        Map<String, Object> voice = JsonCodec.asObject(vectors, "voice");
        for (String vKey : List.of("voice_config_override", "voice_config_muted")) {
            Map<String, Object> vv = JsonCodec.asObject(voice, vKey);
            var vOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(Hex.decode(JsonCodec.asString(vv, "frame_hex", "")), HaikyoChannels.MAX_S2C_WIRE_BYTES));
            var vMsg = ProtocolMessages.VoiceConfigMessage.decodeJson(vOk.frame().payload());
            byte[] reencoded = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_VOICE_CONFIG, vMsg.encodeJson());
            assertEquals(JsonCodec.asString(vv, "frame_hex", ""), Hex.encode(reencoded));
        }

        // 3. HKRL\x01 signed rules package
        Map<String, Object> keys = JsonCodec.asObject(vectors, "test_keys");
        Map<String, Object> primary = JsonCodec.asObject(keys, "attest_primary");
        Map<String, Object> rotated = JsonCodec.asObject(keys, "attest_rotated");
        SignedRulesVerifier rulesVerifier = SignedRulesVerifier.fromBase64Keys(Map.of(
                JsonCodec.asString(primary, "key_id", ""), JsonCodec.asString(primary, "public_raw_b64", ""),
                JsonCodec.asString(rotated, "key_id", ""), JsonCodec.asString(rotated, "public_spki_der_b64", "")
        ));

        Map<String, Object> sr = JsonCodec.asObject(vectors, "signed_rules");
        var validSr = rulesVerifier.verifyEnvelopeJson(JsonCodec.stringify(JsonCodec.asObject(JsonCodec.asObject(sr, "valid"), "envelope")), refNow);
        assertTrue(validSr.valid(), "Valid signed rules must pass: " + validSr.detail());
        assertTrue(validSr.rules().allowedModules().contains("discordhook64.dll"));
        assertTrue(validSr.rules().revokedVersions().contains("v2026.10.01"));

        var rotSr = rulesVerifier.verifyEnvelopeJson(JsonCodec.stringify(JsonCodec.asObject(JsonCodec.asObject(sr, "rotated_key"), "envelope")), refNow);
        assertTrue(rotSr.valid(), "Rotated-key signed rules must pass: " + rotSr.detail());

        var expSr = rulesVerifier.verifyEnvelopeJson(JsonCodec.stringify(JsonCodec.asObject(JsonCodec.asObject(sr, "expired"), "envelope")), refNow);
        assertFalse(expSr.valid());
        assertEquals(SignedRulesVerifier.FailureCode.EXPIRED, expSr.failureCode());

        var tpSr = rulesVerifier.verifyEnvelopeJson(JsonCodec.stringify(JsonCodec.asObject(JsonCodec.asObject(sr, "tampered_payload"), "envelope")), refNow);
        assertFalse(tpSr.valid());
        assertEquals(SignedRulesVerifier.FailureCode.INVALID_SIGNATURE, tpSr.failureCode());

        var tsSr = rulesVerifier.verifyEnvelopeJson(JsonCodec.stringify(JsonCodec.asObject(JsonCodec.asObject(sr, "tampered_signature"), "envelope")), refNow);
        assertFalse(tsSr.valid());
        assertEquals(SignedRulesVerifier.FailureCode.INVALID_SIGNATURE, tsSr.failureCode());
    }
}

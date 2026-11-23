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

import dev.haikyo.api.ModSignalEvent;
import dev.haikyo.api.TrustChangeEvent;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.AttestationToken;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.mods.ModRule;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.CheckPolicy;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.Hex;
import dev.haikyo.core.util.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TrustAndModsEngineTest {

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private record SentPacket(UUID playerId, String channel, byte[] wireFrame) {
    }

    private static KeyPair generateEd25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String signToken(
            KeyPair kp,
            String keyId,
            String version,
            UUID playerId,
            byte[] nonce,
            String serverAddr,
            long issuedAt,
            long expiresAt
    ) throws Exception {
        byte[] payload = AttestationToken.encodePayload(
                keyId, version, "release", new byte[32], playerId, nonce, serverAddr, issuedAt, expiresAt
        );
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(payload);
        byte[] sigBytes = sig.sign();
        byte[] token = new byte[payload.length + sigBytes.length];
        System.arraycopy(payload, 0, token, 0, payload.length);
        System.arraycopy(sigBytes, 0, token, payload.length, sigBytes.length);
        return Base64.getEncoder().encodeToString(token);
    }

    private static HaikyoConfig testConfig(String keyId, String pubKeySpkiB64, double samplingFraction, String linkSecret) {
        Map<String, ModRule> rules = Map.of(
                "zoom", ModRule.limit(Map.of("max_factor", 2.0), "PvP zoom cap"),
                "minimap", ModRule.block("No minimap")
        );
        return new HaikyoConfig(
                "en_us",
                "standalone",
                "play.test.local:25565",
                "test-server",
                "https://haikyo.conquister.net",
                false,
                15,
                List.of("version", "integrity", "mods", "inputs"),
                Map.of(keyId, pubKeySpkiB64),
                Set.of("v0.0.1-revoked"),
                true,
                15,
                45,
                linkSecret,
                60,
                rules,
                true,
                List.of(),
                samplingFraction,
                true,
                true,
                AnticheatCheckTable.defaults(),
                false,
                List.of()
        );
    }

    @Test
    void fullAttestationModViolationAndTrustSamplingLifecycle(@TempDir Path tmp) throws Exception {
        KeyPair kp = generateEd25519KeyPair();
        String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        List<SentPacket> sent = new ArrayList<>();
        List<TrustChangeEvent> trustEvents = new ArrayList<>();
        List<ModSignalEvent> signalEvents = new ArrayList<>();
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));

        // samplingFraction = 0.05, rng returns 0.5 -> NOT sampled (eligible for relaxed checks)
        TrustManager tm = new TrustManager(
                testConfig("k1", pubB64, 0.05, "shared-secret"),
                TrustManager.ServerRole.STANDALONE,
                "0.1.0",
                store,
                (p, c, data) -> {
                    sent.add(new SentPacket(p, c, data));
                    return true;
                },
                (p, k, d) -> { },
                (c, r) -> { },
                clock,
                () -> 0.50
        );
        tm.addTrustChangeListener(trustEvents::add);
        tm.addModSignalListener(signalEvents::add);

        UUID player = UUID.randomUUID();
        tm.onPlayerJoin(player, "Alice");
        assertEquals(TrustLevel.NONE, tm.getTrustLevel(player));
        assertFalse(tm.isHaikyoClient(player));

        // Before attestation: RELAXED check must NOT be cancelled
        assertFalse(tm.evaluateFlag(player, "grim", "Simulation").cancelFlag());

        // Client sends hello_ack -> server sends mods_rules + attest_challenge
        ProtocolMessages.HelloAck ack = new ProtocolMessages.HelloAck(
                1, "v2026.11.22", "release",
                Map.of("control", 1, "attest", 1, "mods", 1, "ac", 1),
                List.of("waypoints@1"),
                List.of("version", "integrity", "mods", "inputs")
        );
        tm.handleClientMessage(player, HaikyoChannels.CONTROL, WireFrame.encodeUnfragmented(HaikyoChannels.PKT_CONTROL_HELLO_ACK, ack.encodeJson()));
        assertTrue(tm.isHaikyoClient(player));

        // Extract issued challenge
        SentPacket challengePkt = sent.stream()
                .filter(p -> HaikyoChannels.ATTEST.equals(p.channel()))
                .findFirst()
                .orElseThrow();
        WireFrame.DecodeOk chOk = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(challengePkt.wireFrame(), HaikyoChannels.MAX_S2C_WIRE_BYTES));
        ProtocolMessages.AttestChallenge ch = ProtocolMessages.AttestChallenge.decodeJson(chOk.frame().payload());
        byte[] nonce = Hex.decode(ch.nonceHex());
        byte[] sessionKey = Hex.decode(ch.sessionKeyHex());

        // Client responds with valid Ed25519 token
        String tokenB64 = signToken(kp, "k1", "v2026.11.22", player, nonce, "play.test.local:25565",
                clock.instant().getEpochSecond() - 5, clock.instant().getEpochSecond() + 120);
        tm.handleClientMessage(player, HaikyoChannels.ATTEST,
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_RESPONSE, new ProtocolMessages.AttestResponse(tokenB64, null).encodeJson()));

        assertEquals(TrustLevel.ATTESTED, tm.getTrustLevel(player));
        assertEquals(1, trustEvents.size());
        assertEquals(TrustLevel.ATTESTED, trustEvents.get(0).current());

        // Send valid haikyo:ac input ticks to corroborate flags
        byte[] acBody = ProtocolMessages.AcInputsPacket.encode(
                1, 100L,
                List.of(new ProtocolMessages.AcInputSample(ProtocolMessages.FLAG_FWD | ProtocolMessages.FLAG_ON_GROUND, 1.2f, -0.3f)),
                sessionKey
        );
        tm.handleClientMessage(player, HaikyoChannels.AC, WireFrame.encodeUnfragmented(HaikyoChannels.PKT_AC_INPUTS, acBody));

        // Now ATTESTED & not sampled -> RELAXED check cancelled & input-corroborated!
        TrustManager.FlagDecision relaxedDec = tm.evaluateFlag(player, "grim", "Simulation");
        assertTrue(relaxedDec.cancelFlag());
        assertEquals(CheckPolicy.RELAXED, relaxedDec.policy());
        assertTrue(relaxedDec.inputCorroborated());

        // Clean integrity report keeps player ATTESTED
        ProtocolMessages.IntegrityReport cleanReport = buildSignedReport(
                1, clock.instant().getEpochSecond(),
                Map.of("zoom", new ProtocolMessages.ReportedModState(true, Map.of("max_factor", 2.0))),
                false, List.of(), sessionKey
        );
        tm.handleClientMessage(player, HaikyoChannels.ATTEST, encodeReportFrame(cleanReport));
        assertEquals(TrustLevel.ATTESTED, tm.getTrustLevel(player));

        // Violating mods.rules (zoom max_factor=5.0 > 2.0) -> emits ModSignalEvent + transitions to SUSPECT!
        ProtocolMessages.IntegrityReport violatingReport = buildSignedReport(
                2, clock.instant().getEpochSecond() + 20,
                Map.of("zoom", new ProtocolMessages.ReportedModState(true, Map.of("max_factor", 5.0))),
                false, List.of(), sessionKey
        );
        tm.handleClientMessage(player, HaikyoChannels.ATTEST, encodeReportFrame(violatingReport));
        assertEquals(TrustLevel.SUSPECT, tm.getTrustLevel(player));
        assertFalse(signalEvents.isEmpty());
        assertEquals("zoom", signalEvents.get(0).modId());

        // Once SUSPECT -> RELAXED check is NO LONGER cancelled!
        assertFalse(tm.evaluateFlag(player, "grim", "Simulation").cancelFlag());
    }

    @Test
    void samplingUnrelaxedAndAlwaysCheckDemotionAndReportTimeout(@TempDir Path tmp) throws Exception {
        KeyPair kp = generateEd25519KeyPair();
        String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));

        // Case 1: rng returns 0.01 < 0.05 -> sampledUnrelaxed = true
        TrustManager sampledTm = new TrustManager(
                testConfig("k1", pubB64, 0.05, "sec"),
                TrustManager.ServerRole.STANDALONE, "0.1.0", store,
                (p, c, d) -> true, null, null, clock, () -> 0.01
        );
        UUID p1 = UUID.randomUUID();
        sampledTm.onPlayerJoin(p1, "SampledBob");
        var ch1 = sampledTm.issueAttestChallenge(p1);
        String tok1 = signToken(kp, "k1", "v2026.11.22", p1, Hex.decode(ch1.nonceHex()),
                "play.test.local:25565", clock.instant().getEpochSecond() - 1, clock.instant().getEpochSecond() + 120);
        sampledTm.handleClientMessage(p1, HaikyoChannels.ATTEST,
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_RESPONSE, new ProtocolMessages.AttestResponse(tok1, null).encodeJson()));

        assertEquals(TrustLevel.ATTESTED, sampledTm.getTrustLevel(p1));
        assertTrue(sampledTm.getPlayerSession(p1).orElseThrow().sampledUnrelaxed());
        // Sampled player must run unrelaxed even on RELAXED checks!
        var sampledDec = sampledTm.evaluateFlag(p1, "grim", "Simulation");
        assertFalse(sampledDec.cancelFlag());
        assertEquals("sampled_unrelaxed", sampledDec.reason());

        // Missing integrity report timeout -> SUSPECT
        clock.now = clock.now.plusSeconds(120);
        List<UUID> timedOut = sampledTm.checkReportTimeouts();
        assertEquals(List.of(p1), timedOut);
        assertEquals(TrustLevel.SUSPECT, sampledTm.getTrustLevel(p1));

        // Case 2: ALWAYS check NEVER cancelled and demotes ATTESTED -> SUSPECT + promotes to requires-haikyo.json
        TrustManager normalTm = new TrustManager(
                testConfig("k1", pubB64, 0.05, "sec"),
                TrustManager.ServerRole.STANDALONE, "0.1.0", store,
                (p, c, d) -> true, null, null, clock, () -> 0.99
        );
        UUID p2 = UUID.randomUUID();
        normalTm.onPlayerJoin(p2, "CheaterEve");
        var ch2 = normalTm.issueAttestChallenge(p2);
        String tok2 = signToken(kp, "k1", "v2026.11.22", p2, Hex.decode(ch2.nonceHex()),
                "play.test.local:25565", clock.instant().getEpochSecond() - 1, clock.instant().getEpochSecond() + 120);
        normalTm.handleClientMessage(p2, HaikyoChannels.ATTEST,
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_RESPONSE, new ProtocolMessages.AttestResponse(tok2, null).encodeJson()));
        assertEquals(TrustLevel.ATTESTED, normalTm.getTrustLevel(p2));

        var alwaysDec = normalTm.evaluateFlag(p2, "grim", "Reach");
        assertFalse(alwaysDec.cancelFlag());
        assertEquals(CheckPolicy.ALWAYS, alwaysDec.policy());
        assertEquals(TrustLevel.SUSPECT, normalTm.getTrustLevel(p2));
        assertTrue(store.isRequired(p2), "Always-check flag should promote player to requires-haikyo.json");
    }

    @Test
    void proxyLinkTransferAndModEndpointApi(@TempDir Path tmp) {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));
        List<SentPacket> backendSent = new ArrayList<>();

        TrustManager backend = new TrustManager(
                testConfig("k1", Base64.getEncoder().encodeToString(new byte[32]), 0.0, "my-secret-123"),
                TrustManager.ServerRole.BACKEND, "0.1.0", store,
                (p, c, d) -> {
                    backendSent.add(new SentPacket(p, c, d));
                    return true;
                },
                null, null, clock, () -> 0.5
        );

        UUID player = UUID.randomUUID();
        backend.onPlayerJoin(player, "ProxyPlayer");

        // Valid signed link session from proxy
        HaikyoLinkCodec.LinkSession ls = new HaikyoLinkCodec.LinkSession(
                player, true, TrustLevel.ATTESTED, "v2026.11.22", "release", "proxy-1",
                Set.of("version", "integrity", "mods", "inputs"), 0,
                clock.instant().getEpochSecond() - 2, clock.instant().getEpochSecond() + 60,
                new byte[32]
        );
        byte[] linkFrame = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_LINK_SESSION,
                HaikyoLinkCodec.encodeSignedSession(ls, "my-secret-123")
        );
        var linkRes = backend.handleIncomingLink(player, linkFrame);
        assertTrue(linkRes.valid());
        assertEquals(TrustLevel.ATTESTED, backend.getTrustLevel(player));
        assertTrue(backend.isHaikyoClient(player));

        // Mod endpoint API roundtrip (waypoints)
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        backend.registerModEndpoint("waypoints", "1", (pid, modId, data) ->
                receivedPayload.set(new String(data, StandardCharsets.UTF_8)));
        backend.handleClientMessage(
                player,
                "haikyo:mod/waypoints",
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_MOD_OPAQUE, "{\"op\":\"request_sync\"}".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals("{\"op\":\"request_sync\"}", receivedPayload.get());
        assertTrue(backend.sendToPlayer(player, "waypoints", "{\"op\":\"clear\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(backendSent.stream().anyMatch(p -> "haikyo:mod/waypoints".equals(p.channel())));
    }

    @Test
    void proxyRoleDoesNotFlagBackendModRulesOnIntegrityReport(@TempDir Path tmp) throws Exception {
        KeyPair kp = generateEd25519KeyPair();
        String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));

        TrustManager proxy = new TrustManager(
                testConfig("k1", pubB64, 0.0, "proxy-secret"),
                TrustManager.ServerRole.PROXY, "0.1.0", store,
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );
        UUID player = UUID.randomUUID();
        proxy.onPlayerJoin(player, "VelocityPlayer");
        var ch = proxy.issueAttestChallenge(player);
        String tok = signToken(kp, "k1", "v2026.11.22", player, Hex.decode(ch.nonceHex()),
                "play.test.local:25565", clock.instant().getEpochSecond() - 1, clock.instant().getEpochSecond() + 120);
        proxy.handleClientMessage(player, HaikyoChannels.ATTEST,
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_RESPONSE, new ProtocolMessages.AttestResponse(tok, null).encodeJson()));
        assertEquals(TrustLevel.ATTESTED, proxy.getTrustLevel(player));

        // Even if proxy config happens to contain mods.rules (e.g. minimap=block, zoom=limit),
        // proxy never sends mods.rules so it must NOT flag the player SUSPECT on integrity_report!
        byte[] sessionKey = Hex.decode(ch.sessionKeyHex());
        ProtocolMessages.IntegrityReport report = buildSignedReport(
                1, clock.instant().getEpochSecond(),
                Map.of(
                        "zoom", new ProtocolMessages.ReportedModState(true, Map.of("max_factor", 5.0)),
                        "minimap", new ProtocolMessages.ReportedModState(true, Map.of())
                ),
                false, List.of(), sessionKey
        );
        proxy.handleClientMessage(player, HaikyoChannels.ATTEST, encodeReportFrame(report));
        assertEquals(TrustLevel.ATTESTED, proxy.getTrustLevel(player));
        assertTrue(proxy.getPlayerSession(player).orElseThrow().signals().isEmpty());
    }

    private static ProtocolMessages.IntegrityReport buildSignedReport(
            long seq,
            long ts,
            Map<String, ProtocolMessages.ReportedModState> mods,
            boolean agentControl,
            List<String> signals,
            byte[] sessionKey
    ) {
        ProtocolMessages.IntegrityReport draft = new ProtocolMessages.IntegrityReport(seq, ts, mods, agentControl, signals, "");
        String hmac = draft.computeHmacHex(sessionKey);
        return new ProtocolMessages.IntegrityReport(seq, ts, mods, agentControl, signals, hmac);
    }

    private static byte[] encodeReportFrame(ProtocolMessages.IntegrityReport report) {
        Map<String, Object> modsObj = new LinkedHashMap<>();
        for (Map.Entry<String, ProtocolMessages.ReportedModState> e : report.mods().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", e.getValue().enabled());
            m.put("params", e.getValue().params());
            modsObj.put(e.getKey(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seq", report.seq());
        root.put("timestamp", report.timestamp());
        root.put("mods", modsObj);
        root.put("agent_control", report.agentControl());
        root.put("signals", report.signals());
        root.put("hmac", report.hmacHex());
        return WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_ATTEST_INTEGRITY_REPORT,
                JsonCodec.stringify(root).getBytes(StandardCharsets.UTF_8)
        );
    }
}

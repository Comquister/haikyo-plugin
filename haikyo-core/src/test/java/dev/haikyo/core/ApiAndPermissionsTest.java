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

import dev.haikyo.api.IntegritySnapshot;
import dev.haikyo.api.ModSignalEvent;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.permissions.HaikyoContextData;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApiAndPermissionsTest {

    @Test
    void thirdPartyApiMethodsIntegrityListenerAndProxySync(@TempDir Path tmp) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));
        HaikyoConfig cfg = new HaikyoConfig(
                "en_us", "backend", "", "srv", "https://haikyo.conquister.net",
                false, 15, List.of("version", "integrity"), Map.of(), Set.of(), true, 15, 45,
                "secret-abc", 60, true, true, Map.of(), false, List.of(),
                0.0, true, true, AnticheatCheckTable.defaults(), false, List.of(),
                HaikyoConfig.AdminConfig.defaults(), HaikyoConfig.VoiceConfig.defaults(),
                new HaikyoConfig.PermissionsConfig(List.of("my.haikyo"), List.of("my.attested"))
        );

        TrustManager tm = new TrustManager(
                cfg, TrustManager.ServerRole.BACKEND, "0.1.0", store,
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );
        List<byte[]> syncedToProxy = new ArrayList<>();
        tm.setBackendLinkSyncSender((carrier, frame) -> syncedToProxy.add(frame));

        List<ModSignalEvent> signals = new ArrayList<>();
        tm.addModSignalListener(signals::add);
        AtomicReference<IntegritySnapshot> lastCbSnapshot = new AtomicReference<>();
        tm.registerIntegrityListener(lastCbSnapshot::set);

        UUID player = UUID.randomUUID();
        tm.onPlayerJoin(player, "Alice");
        assertFalse(tm.isAttested(player));
        assertTrue(tm.getLastIntegritySnapshot(player).isEmpty());

        // Promote via valid haikyo:link -> ATTESTED
        byte[] sessionKey = new byte[32];
        sessionKey[0] = 0x42;
        var ls = new HaikyoLinkCodec.LinkSession(
                player, true, TrustLevel.ATTESTED, "v2026.11.23", "stable", "proxy-1",
                Set.of("version", "integrity"), 0,
                clock.instant().getEpochSecond(), clock.instant().getEpochSecond() + 60, sessionKey
        );
        byte[] linkFrame = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_LINK_SESSION,
                HaikyoLinkCodec.encodeSignedSession(ls, "secret-abc")
        );
        assertTrue(tm.handleIncomingLink(player, linkFrame).valid());
        assertTrue(tm.isAttested(player));

        // shouldRelaxCheck: RELAXED -> true, ALWAYS -> false
        assertTrue(tm.shouldRelaxCheck(player, "grim", "Simulation"));
        assertFalse(tm.shouldRelaxCheck(player, "grim", "UnknownAlwaysCheck"));

        // Re-attest another player to test IntegrityReportListener & getLastIntegritySnapshot
        UUID player2 = UUID.randomUUID();
        tm.onPlayerJoin(player2, "Bob");
        var ls2 = new HaikyoLinkCodec.LinkSession(
                player2, true, TrustLevel.ATTESTED, "v2026.11.23", "dev", "proxy-1",
                Set.of("version", "integrity"), 0,
                clock.instant().getEpochSecond(), clock.instant().getEpochSecond() + 60, sessionKey
        );
        assertTrue(tm.handleIncomingLink(player2, WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_LINK_SESSION, HaikyoLinkCodec.encodeSignedSession(ls2, "secret-abc")
        )).valid());

        // Send verified integrity report -> IntegrityReportListener fires & snapshot stored
        ProtocolMessages.IntegrityReport draft = new ProtocolMessages.IntegrityReport(
                1L, clock.instant().getEpochSecond(),
                Map.of("zoom", new ProtocolMessages.ReportedModState(true, Map.of("max_factor", 2.0))),
                false, List.of(), ""
        );
        String hmac = draft.computeHmacHex(sessionKey);
        Map<String, Object> reportObj = new LinkedHashMap<>();
        reportObj.put("seq", 1L);
        reportObj.put("timestamp", clock.instant().getEpochSecond());
        reportObj.put("mods", Map.of("zoom", Map.of("enabled", true, "params", Map.of("max_factor", 2.0))));
        reportObj.put("agent_control", false);
        reportObj.put("signals", List.of());
        reportObj.put("hmac", hmac);
        tm.handleClientMessage(
                player2, HaikyoChannels.ATTEST,
                WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_INTEGRITY_REPORT, JsonCodec.stringify(reportObj).getBytes(StandardCharsets.UTF_8))
        );

        assertNotNull(lastCbSnapshot.get());
        assertEquals(player2, lastCbSnapshot.get().playerId());
        assertEquals(1L, lastCbSnapshot.get().sequence());
        assertTrue(lastCbSnapshot.get().mods().get("zoom").enabled());
        assertEquals(lastCbSnapshot.get(), tm.getLastIntegritySnapshot(player2).orElseThrow());

        // addExternalSignal -> marks SUSPECT & emits ModSignalEvent
        tm.addExternalSignal(player2, "CustomAC", "bad_timer");
        assertFalse(tm.isAttested(player2));
        assertEquals(TrustLevel.SUSPECT, tm.getTrustLevel(player2));
        assertFalse(tm.shouldRelaxCheck(player2, "grim", "Simulation"));
        assertTrue(signals.stream().anyMatch(ev -> "external:CustomAC:bad_timer".equals(ev.signalCode())));

        // flagSuspect -> marks SUSPECT, adds to requires-haikyo.json, AND triggers 0x03 proxy sync!
        int beforeSyncs = syncedToProxy.size();
        tm.flagSuspect(player2, "CustomAC", "Confirmed reach");
        assertTrue(store.isRequired(player2));
        assertEquals(beforeSyncs + 1, syncedToProxy.size());
    }

    @Test
    void luckPermsContextsAndBuiltInPermissionsConfig() {
        String yaml = """
                language: en_us
                permissions:
                  on-haikyo:
                    - "server.haikyo.badge"
                  on-attested:
                    - "server.ranked.join"
                    - "server.cosmetic.cape"
                """;
        HaikyoConfig cfg = HaikyoConfig.fromYaml(yaml);
        assertEquals(List.of("server.haikyo.badge"), cfg.permissions().onHaikyo());
        assertEquals(List.of("server.ranked.join", "server.cosmetic.cape"), cfg.permissions().onAttested());

        // 1. Offline / vanilla player
        Map<String, String> vanillaCtx = HaikyoContextData.contextsFor(Optional.empty());
        assertEquals("false", vanillaCtx.get(HaikyoContextData.KEY_CLIENT));
        assertEquals("false", vanillaCtx.get(HaikyoContextData.KEY_ATTESTED));
        assertEquals("none", vanillaCtx.get(HaikyoContextData.KEY_TRUST));
        assertFalse(vanillaCtx.containsKey(HaikyoContextData.KEY_CHANNEL));
        assertTrue(HaikyoContextData.grantedPermissionsFor(Optional.empty(), cfg.permissions()).isEmpty());

        // 2. Haikyo client, ATTESTED on channel "stable"
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        var attestedSession = new dev.haikyo.api.PlayerSession(
                UUID.randomUUID(), true, 1, "v2026.11.23", "stable",
                TrustLevel.ATTESTED, false, Set.of(), Map.of(), Set.of(), List.of(), now, now, now, null
        );
        Map<String, String> attCtx = HaikyoContextData.contextsFor(Optional.of(attestedSession));
        assertEquals("true", attCtx.get(HaikyoContextData.KEY_CLIENT));
        assertEquals("true", attCtx.get(HaikyoContextData.KEY_ATTESTED));
        assertEquals("attested", attCtx.get(HaikyoContextData.KEY_TRUST));
        assertEquals("stable", attCtx.get(HaikyoContextData.KEY_CHANNEL));
        assertEquals(
                Set.of("server.haikyo.badge", "server.ranked.join", "server.cosmetic.cape"),
                HaikyoContextData.grantedPermissionsFor(Optional.of(attestedSession), cfg.permissions())
        );

        // 3. Trust drops to SUSPECT -> haikyo-attested=false, on-attested permissions immediately revoked!
        var suspectSession = new dev.haikyo.api.PlayerSession(
                attestedSession.playerId(), true, 1, "v2026.11.23", "dev",
                TrustLevel.SUSPECT, false, Set.of(), Map.of(), Set.of(), List.of("sig"), now, now, now, null
        );
        Map<String, String> susCtx = HaikyoContextData.contextsFor(Optional.of(suspectSession));
        assertEquals("true", susCtx.get(HaikyoContextData.KEY_CLIENT));
        assertEquals("false", susCtx.get(HaikyoContextData.KEY_ATTESTED));
        assertEquals("suspect", susCtx.get(HaikyoContextData.KEY_TRUST));
        assertEquals("dev", susCtx.get(HaikyoContextData.KEY_CHANNEL));
        assertEquals(
                Set.of("server.haikyo.badge"),
                HaikyoContextData.grantedPermissionsFor(Optional.of(suspectSession), cfg.permissions())
        );
    }
}

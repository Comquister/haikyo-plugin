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
package dev.haikyo.paper;

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.util.JsonCodec;
import dev.haikyo.paper.anticheat.GenericAnticheatAdapter;
import dev.haikyo.paper.anticheat.GrimAdapter;
import dev.haikyo.paper.mods.WaypointsMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GenericAdapterAndWaypointsTest {

    @Test
    void genericAdapterSuppressesRelaxedChecksOnlyForAttestedNonSampled(@TempDir Path tmp) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));
        HaikyoConfig cfg = new HaikyoConfig(
                "en_us", "backend", "", "srv", "https://haikyo.conquister.net",
                false, 15, List.of("version"), Map.of(), Set.of(), true, 15, 45,
                "secret-xyz", 60, Map.of(), true, List.of(), 0.0, false, true,
                AnticheatCheckTable.defaults(), true, List.of("ac alert", "ac kick|Reach")
        );

        TrustManager tm = new TrustManager(
                cfg, TrustManager.ServerRole.BACKEND, "0.1.0", store,
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );
        UUID attestedId = UUID.randomUUID();
        UUID vanillaId = UUID.randomUUID();
        tm.onPlayerJoin(attestedId, "AttestedAlice");
        tm.onPlayerJoin(vanillaId, "VanillaBob");

        // Promote AttestedAlice via signed haikyo:link
        var ls = new HaikyoLinkCodec.LinkSession(
                attestedId, true, TrustLevel.ATTESTED, "v2026.11.22", "release", "proxy",
                Set.of("version"), 0, clock.instant().getEpochSecond(), clock.instant().getEpochSecond() + 60, new byte[32]
        );
        byte[] linkFrame = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_LINK_SESSION,
                HaikyoLinkCodec.encodeSignedSession(ls, "secret-xyz")
        );
        assertTrue(tm.handleIncomingLink(attestedId, linkFrame).valid());

        GenericAnticheatAdapter adapter = new GenericAnticheatAdapter(tm);
        java.util.function.Function<String, UUID> lookup = name ->
                "AttestedAlice".equals(name) ? attestedId : ("VanillaBob".equals(name) ? vanillaId : null);

        // Relaxed check ("Simulation") suppressed for ATTESTED, NOT for Vanilla
        assertTrue(adapter.shouldSuppressCommand("ac alert AttestedAlice Simulation", cfg.genericCommandPatterns(), lookup));
        assertFalse(adapter.shouldSuppressCommand("ac alert VanillaBob Simulation", cfg.genericCommandPatterns(), lookup));

        // GrimAdapter decision logic: relaxed Simulation cancelled for ATTESTED, NOT for Vanilla
        assertTrue(GrimAdapter.shouldCancelFlag(tm, attestedId, "Simulation"));
        assertFalse(GrimAdapter.shouldCancelFlag(tm, vanillaId, "Simulation"));

        // ALWAYS check ("Reach") NEVER suppressed even for ATTESTED — and demotes player to SUSPECT!
        assertFalse(adapter.shouldSuppressCommand("ac kick AttestedAlice", cfg.genericCommandPatterns(), lookup));
        assertEquals(TrustLevel.SUSPECT, tm.getTrustLevel(attestedId));
        assertFalse(GrimAdapter.shouldCancelFlag(tm, attestedId, "Reach"));
        assertFalse(GrimAdapter.shouldCancelFlag(tm, attestedId, "Simulation"));
    }

    @Test
    void foliaStrictSafetyNoBukkitSchedulerCalls() throws Exception {
        Path paperSrc = Path.of("src/main/java");
        try (var stream = Files.walk(paperSrc)) {
            List<Path> javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());
            for (Path f : javaFiles) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                // Strip comments before checking for Bukkit.getScheduler()
                String noBlockComments = src.replaceAll("(?s)/\\*.*?\\*/", "");
                String noLineComments = noBlockComments.replaceAll("(?m)//.*$", "");
                assertFalse(
                        noLineComments.contains("Bukkit.getScheduler()") || noLineComments.contains("getServer().getScheduler()"),
                        "Forbidden Bukkit.getScheduler() call found in Folia-compatible source: " + f
                );
            }
        }
    }

    @Test
    void waypointsModRespondsToRequestSyncViaHaikyoApi(@TempDir Path tmp) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        RequiresHaikyoStore store = new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json"));
        List<byte[]> outgoingWaypoints = new ArrayList<>();

        HaikyoConfig cfg = HaikyoConfig.loadDefault();
        TrustManager tm = new TrustManager(
                cfg, TrustManager.ServerRole.STANDALONE, "0.1.0", store,
                (p, ch, data) -> {
                    if ("haikyo:mod/waypoints".equals(ch)) {
                        outgoingWaypoints.add(data);
                    }
                    return true;
                },
                null, null, clock, () -> 0.5
        );

        var marker = new HaikyoConfig.WaypointMarker("spawn", "Spawn", "minecraft:overworld", 0.0, 64.0, 0.0, "#FF6B35");
        WaypointsMod mod = new WaypointsMod(tm, List.of(marker));
        mod.enable(List.of(marker));
        tm.addHandshakeListener(pid -> {
            if (mod.isRegistered()) {
                mod.sendSync(pid);
            }
        });
        assertTrue(tm.advertisedModCapabilities().contains("waypoints@1"));

        UUID player = UUID.randomUUID();
        tm.onPlayerJoin(player, "Explorer");
        // Mark as Haikyo client via hello_ack -> handshake listener proactively pushes sync!
        byte[] ackFrame = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_CONTROL_HELLO_ACK,
                "{\"protocol_version\":1,\"client_version\":\"v2026.11.22\",\"channel\":\"release\",\"channels\":{\"control\":1},\"mod_channels\":[\"waypoints@1\"],\"consented_scopes\":[\"version\"]}"
                        .getBytes(StandardCharsets.UTF_8)
        );
        tm.handleClientMessage(player, HaikyoChannels.CONTROL, ackFrame);
        assertEquals(1, outgoingWaypoints.size(), "hello_ack should proactively push waypoints sync on standalone");

        // Explicit client request_sync also sends sync
        byte[] reqFrame = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_MOD_OPAQUE,
                "{\"op\":\"request_sync\"}".getBytes(StandardCharsets.UTF_8)
        );
        tm.handleClientMessage(player, "haikyo:mod/waypoints", reqFrame);

        assertEquals(2, outgoingWaypoints.size());
        var decoded = assertInstanceOf(WireFrame.DecodeOk.class, WireFrame.decode(outgoingWaypoints.get(1), HaikyoChannels.MAX_S2C_WIRE_BYTES));
        Map<String, Object> msg = JsonCodec.parseObject(new String(decoded.frame().payload(), StandardCharsets.UTF_8));
        assertEquals("sync", JsonCodec.asString(msg, "op", ""));
        assertEquals(1, JsonCodec.asList(msg, "markers").size());
    }
}

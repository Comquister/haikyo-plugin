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

import dev.haikyo.core.admin.AuditLogger;
import dev.haikyo.core.admin.ScreenshotDeliveryService;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.TrustManager;
import dev.haikyo.core.voice.VoiceModerationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdminAndVoiceCoreTest {

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

    @Test
    void auditLoggerRecordsAndPrunesByRetention(@TempDir Path tmp) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        AuditLogger audit = new AuditLogger(tmp.resolve("audit.log"), 30, clock);
        UUID target = UUID.randomUUID();
        audit.record("Admin1", target, "PlayerOld", "screenshot", "mode=file");

        clock.now = Instant.parse("2026-11-20T00:00:00Z"); // 50 days later
        audit.record("Admin2", target, "PlayerNew", "hwid", "");

        int pruned = audit.pruneExpired();
        assertEquals(1, pruned);
        List<String> lines = audit.readAllLines();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Admin2"));
        assertFalse(lines.get(0).contains("PlayerOld"));
    }

    @Test
    void screenshotBuiltinServesValidToken404sAfterExpiryAndFallsBackWithoutPublicUrl(@TempDir Path tmp) throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        Path screens = tmp.resolve("screens");
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};

        // 1. Builtin without public-url falls back cleanly to FILE mode
        try (ScreenshotDeliveryService fallbackSvc = new ScreenshotDeliveryService(
                screens,
                new ScreenshotDeliveryService.DeliveryConfig(ScreenshotDeliveryService.Mode.BUILTIN, 60, "127.0.0.1", 0, "", "", ""),
                clock
        )) {
            fallbackSvc.reconfigure(new ScreenshotDeliveryService.DeliveryConfig(
                    ScreenshotDeliveryService.Mode.BUILTIN, 60, "127.0.0.1", 0, "", "", ""
            ));
            assertFalse(fallbackSvc.isHttpServerRunning());
            var res = fallbackSvc.deliver(fakeJpeg, "image/jpeg");
            assertTrue(res.success());
            assertEquals(ScreenshotDeliveryService.Mode.FILE, res.effectiveMode());
            assertEquals(32, res.token().length());
            assertTrue(Files.isRegularFile(Path.of(res.location())));

            // Purge after TTL deletes file
            clock.now = clock.now.plusSeconds(65);
            assertEquals(1, fallbackSvc.purgeExpired());
            assertFalse(Files.exists(Path.of(res.location())));
        }

        // 2. Builtin WITH public-url binds embedded HTTP server, serves 200, then 404 after expiry
        try (ScreenshotDeliveryService httpSvc = new ScreenshotDeliveryService(
                screens,
                new ScreenshotDeliveryService.DeliveryConfig(ScreenshotDeliveryService.Mode.BUILTIN, 60, "127.0.0.1", 0, "http://127.0.0.1", "", ""),
                clock
        )) {
            httpSvc.reconfigure(new ScreenshotDeliveryService.DeliveryConfig(
                    ScreenshotDeliveryService.Mode.BUILTIN, 60, "127.0.0.1", 0, "http://127.0.0.1", "", ""
            ));
            assertTrue(httpSvc.isHttpServerRunning());
            int port = httpSvc.boundPort();
            assertTrue(port > 0);

            var res = httpSvc.deliver(fakeJpeg, "image/jpeg");
            assertTrue(res.success());
            assertEquals(ScreenshotDeliveryService.Mode.BUILTIN, res.effectiveMode());

            HttpClient client = HttpClient.newHttpClient();
            URI url = URI.create("http://127.0.0.1:" + port + "/haikyo/s/" + res.token());
            HttpResponse<byte[]> okResp = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, okResp.statusCode());
            assertArrayEquals(fakeJpeg, okResp.body());
            assertEquals("image/jpeg", okResp.headers().firstValue("Content-Type").orElse(""));

            // Advance past TTL -> 404 and file deleted!
            clock.now = clock.now.plusSeconds(61);
            HttpResponse<byte[]> expiredResp = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, expiredResp.statusCode());
            assertFalse(Files.exists(screens.resolve(res.token() + ".jpg")));
        }
    }

    @Test
    void fragmentedScreenshotAndSpoofRejectionThroughTrustManager(@TempDir Path tmp) {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        HaikyoConfig cfg = HaikyoConfig.loadDefault();
        TrustManager tm = new TrustManager(
                cfg, TrustManager.ServerRole.STANDALONE, "0.1.0",
                new RequiresHaikyoStore(tmp.resolve("requires-haikyo.json")),
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );

        UUID victim = UUID.randomUUID();
        UUID spoofer = UUID.randomUUID();
        tm.onPlayerJoin(victim, "Victim");
        tm.onPlayerJoin(spoofer, "Spoofer");
        byte[] ack = WireFrame.encodeUnfragmented(
                HaikyoChannels.PKT_CONTROL_HELLO_ACK,
                "{\"protocol_version\":1,\"client_version\":\"v1\",\"channel\":\"release\",\"channels\":{\"control\":1,\"admin\":1},\"mod_channels\":[],\"consented_scopes\":[\"version\",\"screenshot\"]}"
                        .getBytes(StandardCharsets.UTF_8)
        );
        tm.handleClientMessage(victim, HaikyoChannels.CONTROL, ack);
        tm.handleClientMessage(spoofer, HaikyoChannels.CONTROL, ack);

        AtomicReference<ProtocolMessages.AdminScreenshotResponse> received = new AtomicReference<>();
        String reqId = tm.sendAdminScreenshotRequest(victim, "Victim", "ModAlice", received::set).orElseThrow();

        // Spoofer tries to answer victim's request_id -> MUST be ignored!
        byte[] spoofBody = new ProtocolMessages.AdminScreenshotResponse(reqId, 0, "", 10, 10, "image/jpeg", new byte[]{1, 2}).encodeBinary();
        tm.handleClientMessage(spoofer, HaikyoChannels.ADMIN, WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ADMIN_SCREENSHOT_RESPONSE, spoofBody));
        assertNull(received.get(), "Spoofed response from different player must be ignored");

        // Victim responds with a 70 KB screenshot fragmented across 3 C2S chunks (<= 32,767 B each)
        byte[] bigJpeg = new byte[70_000];
        for (int i = 0; i < bigJpeg.length; i++) {
            bigJpeg[i] = (byte) (i * 31 + 7);
        }
        byte[] realBody = new ProtocolMessages.AdminScreenshotResponse(reqId, 0, "", 1280, 720, "image/jpeg", bigJpeg).encodeBinary();
        List<byte[]> chunks = WireFrame.encodeForWire(HaikyoChannels.PKT_ADMIN_SCREENSHOT_RESPONSE, realBody, HaikyoChannels.MAX_C2S_WIRE_BYTES, 55);
        assertEquals(3, chunks.size());
        for (byte[] chunk : chunks) {
            tm.handleClientMessage(victim, HaikyoChannels.ADMIN, chunk);
        }
        assertNotNull(received.get());
        assertArrayEquals(bigJpeg, received.get().imageBytes());
        assertEquals(1280, received.get().width());
    }

    @Test
    void voiceModerationModesTimedMuteAndForceMute() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_800_000_000L));
        VoiceModerationManager vm = new VoiceModerationManager(clock);
        HaikyoConfig.VoiceConfig cfg = HaikyoConfig.loadDefault().voice();

        assertFalse(cfg.recordingAllowed(), "Recording must be off by default");
        assertEquals("off", vm.effectiveMode(cfg), "Without SVC detected, auto mode resolves to off");
        vm.setSvcDetected(true);
        assertEquals("svc", vm.effectiveMode(cfg), "With SVC detected, auto mode resolves to svc");

        UUID player = UUID.randomUUID();
        assertFalse(vm.isPlayerMuted(player));
        vm.mute(player, 60, "Mic spam", false);
        assertTrue(vm.isPlayerMuted(player));
        var msg = vm.buildMessageFor(player, cfg);
        assertTrue(msg.muted());
        assertFalse(msg.forceMuted());
        assertEquals("natural", msg.falloffMode());

        // After 61s timed mute expires automatically
        clock.now = clock.now.plusSeconds(61);
        assertFalse(vm.isPlayerMuted(player));

        // Force mute stays until explicitly unmuted
        vm.mute(player, 0, "Admin force mute", true);
        assertTrue(vm.isPlayerMuted(player));
        assertTrue(vm.buildMessageFor(player, cfg).forceMuted());
        vm.unmute(player);
        assertFalse(vm.isPlayerMuted(player));
    }
}

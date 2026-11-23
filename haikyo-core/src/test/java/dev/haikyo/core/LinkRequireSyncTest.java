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
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class LinkRequireSyncTest {

    private static HaikyoConfig configWithLink(String secret, boolean syncToProxy, boolean acceptBackendSync) {
        return new HaikyoConfig(
                "en_us", "backend", "", "srv", "https://haikyo.conquister.net",
                false, 15, List.of("version"), Map.of(), Set.of(), true, 15, 45,
                secret, 60, syncToProxy, acceptBackendSync,
                Map.of(), false, List.of(), 0.05, true, true,
                AnticheatCheckTable.defaults(), false, List.of(),
                HaikyoConfig.AdminConfig.defaults(), HaikyoConfig.VoiceConfig.defaults()
        );
    }

    @Test
    void hkrqCodecRoundtripTamperMissingSecretAndFreshness() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        UUID player = UUID.randomUUID();
        String secret = "super-secret-link-key";

        // 1. REQUIRE roundtrip
        HaikyoLinkCodec.RequireSync req = new HaikyoLinkCodec.RequireSync(
                true, player, "SuspectSteve", "always_check_flag:grim:Reach", now.getEpochSecond()
        );
        byte[] body = HaikyoLinkCodec.encodeSignedRequireSync(req, secret);
        var ok = HaikyoLinkCodec.verifyAndDecodeRequireSync(body, secret, now);
        assertTrue(ok.valid(), "Valid HKRQ REQUIRE must decode: " + ok.detail());
        assertTrue(ok.sync().require());
        assertEquals(player, ok.sync().playerUuid());
        assertEquals("SuspectSteve", ok.sync().playerName());
        assertEquals("always_check_flag:grim:Reach", ok.sync().reason());

        // 2. PARDON roundtrip
        HaikyoLinkCodec.RequireSync pardon = new HaikyoLinkCodec.RequireSync(
                false, player, "SuspectSteve", "pardon", now.getEpochSecond()
        );
        byte[] pardonBody = HaikyoLinkCodec.encodeSignedRequireSync(pardon, secret);
        var pardonOk = HaikyoLinkCodec.verifyAndDecodeRequireSync(pardonBody, secret, now);
        assertTrue(pardonOk.valid());
        assertFalse(pardonOk.sync().require());

        // 3. Tampered HMAC rejected
        byte[] tampered = body.clone();
        tampered[5] = (byte) (tampered[5] ^ 0x01);
        var badHmac = HaikyoLinkCodec.verifyAndDecodeRequireSync(tampered, secret, now);
        assertFalse(badHmac.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.INVALID_HMAC, badHmac.failureCode());

        // 4. Wrong secret rejected
        var wrongSecret = HaikyoLinkCodec.verifyAndDecodeRequireSync(body, "wrong-secret", now);
        assertFalse(wrongSecret.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.INVALID_HMAC, wrongSecret.failureCode());

        // 5. Missing secret rejected
        var missing = HaikyoLinkCodec.verifyAndDecodeRequireSync(body, "", now);
        assertFalse(missing.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.MISSING_SECRET, missing.failureCode());

        // 6. Expired (>60s old) and future (>60s ahead) timestamps rejected
        var expired = HaikyoLinkCodec.verifyAndDecodeRequireSync(body, secret, now.plusSeconds(61));
        assertFalse(expired.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.SESSION_EXPIRED, expired.failureCode());

        var future = HaikyoLinkCodec.verifyAndDecodeRequireSync(body, secret, now.minusSeconds(61));
        assertFalse(future.valid());
        assertEquals(HaikyoLinkCodec.FailureCode.SESSION_EXPIRED, future.failureCode());
    }

    @Test
    void backendSuspectAlwaysFlagAndPardonSyncToProxyAndRespectConfigFlags(@TempDir Path tmp) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        String secret = "shared-proxy-secret";

        // Default config.yml enables both flags
        HaikyoConfig defaultCfg = HaikyoConfig.loadDefault();
        assertTrue(defaultCfg.linkSyncRequiresToProxy());
        assertTrue(defaultCfg.linkAcceptBackendRequiresSync());

        RequiresHaikyoStore backendStore = new RequiresHaikyoStore(tmp.resolve("backend-requires.json"));
        RequiresHaikyoStore proxyStore = new RequiresHaikyoStore(tmp.resolve("proxy-requires.json"));

        TrustManager backend = new TrustManager(
                configWithLink(secret, true, true),
                TrustManager.ServerRole.BACKEND, "0.1.0", backendStore,
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );
        TrustManager proxy = new TrustManager(
                configWithLink(secret, true, true),
                TrustManager.ServerRole.PROXY, "0.1.0", proxyStore,
                (p, c, d) -> true, null, null, clock, () -> 0.5
        );

        List<byte[]> forwardedFrames = new ArrayList<>();
        backend.setBackendLinkSyncSender((carrier, frame) -> {
            forwardedFrames.add(frame);
            assertTrue(proxy.handleIncomingRequireSync(frame).valid());
        });

        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        backend.onPlayerJoin(player1, "Bob");
        proxy.onPlayerJoin(player1, "Bob");
        backend.onPlayerJoin(player2, "Eve");
        proxy.onPlayerJoin(player2, "Eve");

        // 1. /haikyo suspect on backend propagates REQUIRE to proxy & marks online player SUSPECT
        backend.markSuspectAndMaybeRequire(player1, "Bob", "manual_suspect", "command:Admin");
        assertTrue(backendStore.isRequired(player1));
        assertTrue(proxyStore.isRequired(player1));
        assertEquals(TrustLevel.SUSPECT, proxy.getTrustLevel(player1));
        assertEquals(1, forwardedFrames.size());

        // 2. Anticheat ALWAYS-check promotion on backend propagates REQUIRE to proxy
        backend.evaluateFlag(player2, "grim", "Reach");
        assertTrue(backendStore.isRequired(player2));
        assertTrue(proxyStore.isRequired(player2));
        assertEquals(TrustLevel.SUSPECT, proxy.getTrustLevel(player2));
        assertEquals(2, forwardedFrames.size());

        // 3. /haikyo pardon on backend propagates PARDON to proxy
        assertTrue(backend.pardonPlayer("Bob").isPresent());
        assertFalse(backendStore.isRequired(player1));
        assertFalse(proxyStore.isRequired(player1));
        assertEquals(3, forwardedFrames.size());

        // 4. sync-requires-to-proxy: false suppresses 0x03 emission (local only)
        backend.reloadConfig(configWithLink(secret, false, true), TrustManager.ServerRole.BACKEND);
        UUID localOnly = UUID.randomUUID();
        backend.onPlayerJoin(localOnly, "LocalOnly");
        backend.markSuspectAndMaybeRequire(localOnly, "LocalOnly", "local_reason", "command:Admin");
        assertTrue(backendStore.isRequired(localOnly));
        assertFalse(proxyStore.isRequired(localOnly));
        assertEquals(3, forwardedFrames.size(), "No 0x03 frame should be emitted when sync-requires-to-proxy is false");

        // 5. accept-backend-requires-sync: false on proxy ignores incoming 0x03
        backend.reloadConfig(configWithLink(secret, true, true), TrustManager.ServerRole.BACKEND);
        proxy.reloadConfig(configWithLink(secret, true, false), TrustManager.ServerRole.PROXY);
        byte[] rawFrame = backend.buildSignedRequireSyncFrame(true, localOnly, "LocalOnly", "blocked_by_proxy_cfg").orElseThrow();
        assertFalse(proxy.handleIncomingRequireSync(rawFrame).valid());
        assertFalse(proxyStore.isRequired(localOnly));
    }
}

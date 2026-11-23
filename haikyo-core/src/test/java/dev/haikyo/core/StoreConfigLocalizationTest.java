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

import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.i18n.Localization;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.trust.CheckPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoreConfigLocalizationTest {

    @Test
    void requiresHaikyoStorePersistenceAndPardon(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("requires-haikyo.json");
        RequiresHaikyoStore store = new RequiresHaikyoStore(file);
        UUID id = UUID.randomUUID();
        store.requirePlayer(id, "SuspiciousPlayer", "always_check_flag:grim:Reach", "anticheat:grim", Instant.ofEpochSecond(1_800_000_000L));
        assertTrue(store.isRequired(id));

        // Reload from disk into a fresh instance
        RequiresHaikyoStore reloaded = new RequiresHaikyoStore(file);
        reloaded.load();
        assertTrue(reloaded.isRequired(id));
        assertEquals("SuspiciousPlayer", reloaded.get(id).orElseThrow().playerName());

        // Pardon by player name
        assertTrue(reloaded.pardonByNameOrUuid("suspiciousplayer").isPresent());
        assertFalse(reloaded.isRequired(id));

        RequiresHaikyoStore afterPardon = new RequiresHaikyoStore(file);
        afterPardon.load();
        assertFalse(afterPardon.isRequired(id));
    }

    @Test
    void defaultConfigParsesCleanly() {
        HaikyoConfig cfg = HaikyoConfig.loadDefault();
        assertEquals("en_us", cfg.language());
        assertEquals("auto", cfg.configuredRole());
        assertFalse(cfg.requireHaikyo());
        assertEquals(0.05, cfg.samplingFraction(), 1e-6);
        assertEquals(CheckPolicy.RELAXED, cfg.checkTable().policyFor("grim", "Simulation"));
        assertEquals(CheckPolicy.RELAXED, cfg.checkTable().policyFor("grim", "AimDuplicateLook"));
        assertEquals(CheckPolicy.ALWAYS, cfg.checkTable().policyFor("grim", "Reach"));
        assertEquals(CheckPolicy.ALWAYS, cfg.checkTable().policyFor("grim", "UnknownCustomCheck"));
        assertEquals(CheckPolicy.RELAXED, cfg.checkTable().policyFor("vulcan", "AimA"));
        assertEquals(CheckPolicy.ALWAYS, cfg.checkTable().policyFor("vulcan", "FlightA"));
    }

    @Test
    void localizationSupportsThreeLanguagesWithParity() {
        Localization loc = Localization.loadDefault("pt_br");
        List<String> keys = List.of(
                "prefix", "command.no_permission", "command.usage", "command.status.header",
                "command.pardon.success", "command.suspect.success", "command.reload.success",
                "kick.require_haikyo", "kick.require_haikyo_flagged", "kick.attestation_failed",
                "log.proxy_fallback_no_secret", "log.proxy_fallback_bad_hmac"
        );
        for (String lang : Localization.SUPPORTED_LOCALES) {
            for (String key : keys) {
                String msg = loc.getForLocale(lang, key, "A", "B", "C", "D", "E");
                assertNotEquals(key, msg, "Missing translation for " + lang + " key=" + key);
                assertFalse(msg.isBlank());
            }
        }
        assertTrue(loc.getForLocale("pt_br", "kick.require_haikyo", "https://haikyo.conquister.net").contains("exige o Haikyo Client"));
        assertTrue(loc.getForLocale("es_es", "kick.require_haikyo", "https://haikyo.conquister.net").contains("requiere Haikyo Client"));
        assertTrue(loc.getForLocale("en_us", "kick.require_haikyo", "https://haikyo.conquister.net").contains("requires the Haikyo Client"));
    }

    @Test
    void haikyoCoreHasZeroPlatformImports() throws Exception {
        Path srcDir = Path.of("src/main/java");
        try (var stream = java.nio.file.Files.walk(srcDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(files.isEmpty());
            for (Path f : files) {
                String content = java.nio.file.Files.readString(f);
                assertFalse(content.contains("import org.bukkit."), "Bukkit import found in haikyo-core: " + f);
                assertFalse(content.contains("import io.papermc."), "Paper import found in haikyo-core: " + f);
                assertFalse(content.contains("import com.velocitypowered."), "Velocity import found in haikyo-core: " + f);
            }
        }
    }
}

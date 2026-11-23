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
package dev.haikyo.core.config;

import dev.haikyo.core.crypto.ServerAddressNormalizer;
import dev.haikyo.core.mods.ModRule;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.CheckPolicy;
import dev.haikyo.core.util.JsonCodec;
import dev.haikyo.core.util.SimpleYamlParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Platform-independent immutable configuration model parsed from {@code config.yml}.
 */
public record HaikyoConfig(
        String language,
        String configuredRole,
        String serverAddress,
        String serverId,
        String downloadUrl,
        boolean requireHaikyo,
        int attestationTimeoutSeconds,
        List<String> consentScopes,
        Map<String, String> attestationPublicKeys,
        Set<String> revokedVersions,
        boolean requireNoAgent,
        int reportIntervalMinSeconds,
        int reportIntervalMaxSeconds,
        String linkSharedSecret,
        int linkSessionTtlSeconds,
        Map<String, ModRule> modRules,
        boolean waypointsEnabled,
        List<WaypointMarker> waypointMarkers,
        double samplingFraction,
        boolean promoteSuspectToRequire,
        boolean useInputTelemetry,
        AnticheatCheckTable checkTable,
        boolean genericAdapterEnabled,
        List<String> genericCommandPatterns
) {
    public record WaypointMarker(
            String id,
            String name,
            String world,
            double x,
            double y,
            double z,
            String color
    ) {
        public Map<String, Object> toJsonMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("world", world);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("color", color);
            return m;
        }
    }

    public static HaikyoConfig loadDefault() {
        try (InputStream is = HaikyoConfig.class.getResourceAsStream("/config.yml")) {
            if (is == null) {
                return fromMap(Map.of());
            }
            return fromYaml(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return fromMap(Map.of());
        }
    }

    public static HaikyoConfig loadFromFile(Path path) throws IOException {
        return fromYaml(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static HaikyoConfig fromYaml(String yaml) {
        return fromMap(SimpleYamlParser.parse(yaml));
    }

    public static HaikyoConfig fromMap(Map<String, Object> root) {
        String language = JsonCodec.asString(root, "language", "en_us");
        String role = JsonCodec.asString(root, "role", "auto").toLowerCase(Locale.ROOT);
        String rawAddr = JsonCodec.asString(root, "server-address", "");
        String normAddr = rawAddr.isBlank() ? "" : ServerAddressNormalizer.normalize(rawAddr);
        String serverId = JsonCodec.asString(root, "server-id", normAddr.isEmpty() ? "haikyo-server" : normAddr);
        String downloadUrl = JsonCodec.asString(root, "download-url", "https://haikyo.conquister.net");
        boolean requireHaikyo = JsonCodec.asBoolean(root, "require-haikyo", false);
        int attestTimeout = Math.max(3, JsonCodec.asInt(root, "attestation-timeout-seconds", 15));

        List<String> consentScopes = JsonCodec.asStringList(root, "consent-scopes");
        if (consentScopes.isEmpty()) {
            consentScopes = List.of("version", "integrity", "mods", "inputs");
        }

        Map<String, Object> attestMap = JsonCodec.asObject(root, "attestation");
        Map<String, String> pubKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonCodec.asObject(attestMap, "public-keys").entrySet()) {
            if (e.getValue() != null) {
                String val = e.getValue().toString().trim();
                if (!val.isEmpty()) {
                    pubKeys.put(e.getKey(), val);
                }
            }
        }
        Set<String> revoked = new LinkedHashSet<>(JsonCodec.asStringList(attestMap, "revoked-versions"));
        boolean requireNoAgent = JsonCodec.asBoolean(attestMap, "require-no-agent", true);
        int reportMin = Math.max(5, JsonCodec.asInt(attestMap, "report-interval-min-seconds", 15));
        int reportMax = Math.max(reportMin, JsonCodec.asInt(attestMap, "report-interval-max-seconds", 45));

        Map<String, Object> linkMap = JsonCodec.asObject(root, "link");
        String sharedSecret = JsonCodec.asString(linkMap, "shared-secret", "");
        int linkTtl = Math.max(10, JsonCodec.asInt(linkMap, "session-ttl-seconds", 60));

        Map<String, Object> modsMap = JsonCodec.asObject(root, "mods");
        Map<String, ModRule> modRules = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonCodec.asObject(modsMap, "rules").entrySet()) {
            if (e.getValue() instanceof Map<?, ?> rawRule) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) rawRule;
                ModRule.Action action = ModRule.Action.fromWireName(JsonCodec.asString(r, "action", "allow"));
                Map<String, Object> params = JsonCodec.asObject(r, "params");
                String reason = JsonCodec.asString(r, "reason", "");
                modRules.put(e.getKey(), new ModRule(action, params, reason.isBlank() ? null : reason));
            }
        }

        Map<String, Object> wpMap = JsonCodec.asObject(modsMap, "waypoints");
        boolean wpEnabled = JsonCodec.asBoolean(wpMap, "enabled", false);
        List<WaypointMarker> wpMarkers = new ArrayList<>();
        for (Object item : JsonCodec.asList(wpMap, "markers")) {
            if (item instanceof Map<?, ?> rawM) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) rawM;
                String id = JsonCodec.asString(mm, "id", "");
                if (!id.isEmpty()) {
                    wpMarkers.add(new WaypointMarker(
                            id,
                            JsonCodec.asString(mm, "name", id),
                            JsonCodec.asString(mm, "world", "minecraft:overworld"),
                            JsonCodec.asDouble(mm, "x", 0.0),
                            JsonCodec.asDouble(mm, "y", 64.0),
                            JsonCodec.asDouble(mm, "z", 0.0),
                            JsonCodec.asString(mm, "color", "#FF6B35")
                    ));
                }
            }
        }

        Map<String, Object> acMap = JsonCodec.asObject(root, "anticheat");
        double sampling = Math.max(0.0, Math.min(1.0, JsonCodec.asDouble(acMap, "sampling-fraction", 0.05)));
        boolean promoteSuspect = JsonCodec.asBoolean(acMap, "promote-suspect-to-require", true);
        boolean useInputTelemetry = JsonCodec.asBoolean(acMap, "use-input-telemetry", true);

        Map<String, Object> checksObj = JsonCodec.asObject(acMap, "checks");
        AnticheatCheckTable checkTable;
        if (checksObj.isEmpty()) {
            checkTable = AnticheatCheckTable.defaults();
        } else {
            Map<String, Map<String, CheckPolicy>> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> acEntry : checksObj.entrySet()) {
                if (acEntry.getValue() instanceof Map<?, ?> rawChecks) {
                    Map<String, CheckPolicy> checkMap = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> ce : rawChecks.entrySet()) {
                        checkMap.put(
                                String.valueOf(ce.getKey()),
                                CheckPolicy.fromConfig(ce.getValue() == null ? "always" : ce.getValue().toString())
                        );
                    }
                    parsed.put(acEntry.getKey(), checkMap);
                }
            }
            checkTable = new AnticheatCheckTable(parsed);
        }

        Map<String, Object> genericObj = JsonCodec.asObject(acMap, "generic-adapter");
        boolean genericEnabled = JsonCodec.asBoolean(genericObj, "enabled", false);
        List<String> genericPatterns = JsonCodec.asStringList(genericObj, "command-patterns");

        return new HaikyoConfig(
                language,
                role,
                normAddr,
                serverId,
                downloadUrl,
                requireHaikyo,
                attestTimeout,
                List.copyOf(consentScopes),
                Collections.unmodifiableMap(pubKeys),
                Collections.unmodifiableSet(revoked),
                requireNoAgent,
                reportMin,
                reportMax,
                sharedSecret,
                linkTtl,
                Collections.unmodifiableMap(modRules),
                wpEnabled,
                List.copyOf(wpMarkers),
                sampling,
                promoteSuspect,
                useInputTelemetry,
                checkTable,
                genericEnabled,
                List.copyOf(genericPatterns)
        );
    }
}

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
package dev.haikyo.core.config;

import dev.haikyo.core.admin.ScreenshotDeliveryService;
import dev.haikyo.core.crypto.ServerAddressNormalizer;
import dev.haikyo.core.mods.ModRule;
import dev.haikyo.core.trust.AnticheatCheckTable;
import dev.haikyo.core.trust.CheckPolicy;
import dev.haikyo.core.util.Java8Compat;
import dev.haikyo.core.util.JsonCodec;
import dev.haikyo.core.util.SimpleYamlParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
public final class HaikyoConfig {
    private final String language;
    private final String configuredRole;
    private final String serverAddress;
    private final String serverId;
    private final String downloadUrl;
    private final boolean requireHaikyo;
    private final int attestationTimeoutSeconds;
    private final List<String> consentScopes;
    private final Map<String, String> attestationPublicKeys;
    private final Set<String> revokedVersions;
    private final boolean requireNoAgent;
    private final int reportIntervalMinSeconds;
    private final int reportIntervalMaxSeconds;
    private final String linkSharedSecret;
    private final int linkSessionTtlSeconds;
    private final boolean linkSyncRequiresToProxy;
    private final boolean linkAcceptBackendRequiresSync;
    private final Map<String, ModRule> modRules;
    private final boolean waypointsEnabled;
    private final List<WaypointMarker> waypointMarkers;
    private final double samplingFraction;
    private final boolean promoteSuspectToRequire;
    private final boolean useInputTelemetry;
    private final AnticheatCheckTable checkTable;
    private final boolean genericAdapterEnabled;
    private final List<String> genericCommandPatterns;
    private final AdminConfig admin;
    private final VoiceConfig voice;
    private final PermissionsConfig permissions;

    public HaikyoConfig(
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
            boolean linkSyncRequiresToProxy,
            boolean linkAcceptBackendRequiresSync,
            Map<String, ModRule> modRules,
            boolean waypointsEnabled,
            List<WaypointMarker> waypointMarkers,
            double samplingFraction,
            boolean promoteSuspectToRequire,
            boolean useInputTelemetry,
            AnticheatCheckTable checkTable,
            boolean genericAdapterEnabled,
            List<String> genericCommandPatterns,
            AdminConfig admin,
            VoiceConfig voice,
            PermissionsConfig permissions
    ) {
        this.language = language;
        this.configuredRole = configuredRole;
        this.serverAddress = serverAddress;
        this.serverId = serverId;
        this.downloadUrl = downloadUrl;
        this.requireHaikyo = requireHaikyo;
        this.attestationTimeoutSeconds = attestationTimeoutSeconds;
        this.consentScopes = consentScopes;
        this.attestationPublicKeys = attestationPublicKeys;
        this.revokedVersions = revokedVersions;
        this.requireNoAgent = requireNoAgent;
        this.reportIntervalMinSeconds = reportIntervalMinSeconds;
        this.reportIntervalMaxSeconds = reportIntervalMaxSeconds;
        this.linkSharedSecret = linkSharedSecret;
        this.linkSessionTtlSeconds = linkSessionTtlSeconds;
        this.linkSyncRequiresToProxy = linkSyncRequiresToProxy;
        this.linkAcceptBackendRequiresSync = linkAcceptBackendRequiresSync;
        this.modRules = modRules;
        this.waypointsEnabled = waypointsEnabled;
        this.waypointMarkers = waypointMarkers;
        this.samplingFraction = samplingFraction;
        this.promoteSuspectToRequire = promoteSuspectToRequire;
        this.useInputTelemetry = useInputTelemetry;
        this.checkTable = checkTable;
        this.genericAdapterEnabled = genericAdapterEnabled;
        this.genericCommandPatterns = genericCommandPatterns;
        this.admin = admin;
        this.voice = voice;
        this.permissions = permissions;
    }

    public HaikyoConfig(
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
        this(
                language, configuredRole, serverAddress, serverId, downloadUrl,
                requireHaikyo, attestationTimeoutSeconds, consentScopes,
                attestationPublicKeys, revokedVersions, requireNoAgent,
                reportIntervalMinSeconds, reportIntervalMaxSeconds,
                linkSharedSecret, linkSessionTtlSeconds, true, true, modRules,
                waypointsEnabled, waypointMarkers, samplingFraction,
                promoteSuspectToRequire, useInputTelemetry, checkTable,
                genericAdapterEnabled, genericCommandPatterns,
                AdminConfig.defaults(), VoiceConfig.defaults(), PermissionsConfig.empty()
        );
    }

    public HaikyoConfig(
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
            boolean linkSyncRequiresToProxy,
            boolean linkAcceptBackendRequiresSync,
            Map<String, ModRule> modRules,
            boolean waypointsEnabled,
            List<WaypointMarker> waypointMarkers,
            double samplingFraction,
            boolean promoteSuspectToRequire,
            boolean useInputTelemetry,
            AnticheatCheckTable checkTable,
            boolean genericAdapterEnabled,
            List<String> genericCommandPatterns,
            AdminConfig admin,
            VoiceConfig voice
    ) {
        this(
                language, configuredRole, serverAddress, serverId, downloadUrl,
                requireHaikyo, attestationTimeoutSeconds, consentScopes,
                attestationPublicKeys, revokedVersions, requireNoAgent,
                reportIntervalMinSeconds, reportIntervalMaxSeconds,
                linkSharedSecret, linkSessionTtlSeconds, linkSyncRequiresToProxy,
                linkAcceptBackendRequiresSync, modRules, waypointsEnabled,
                waypointMarkers, samplingFraction, promoteSuspectToRequire,
                useInputTelemetry, checkTable, genericAdapterEnabled,
                genericCommandPatterns, admin, voice, PermissionsConfig.empty()
        );
    }

    public String language() { return language; }
    public String configuredRole() { return configuredRole; }
    public String serverAddress() { return serverAddress; }
    public String serverId() { return serverId; }
    public String downloadUrl() { return downloadUrl; }
    public boolean requireHaikyo() { return requireHaikyo; }
    public int attestationTimeoutSeconds() { return attestationTimeoutSeconds; }
    public List<String> consentScopes() { return consentScopes; }
    public Map<String, String> attestationPublicKeys() { return attestationPublicKeys; }
    public Set<String> revokedVersions() { return revokedVersions; }
    public boolean requireNoAgent() { return requireNoAgent; }
    public int reportIntervalMinSeconds() { return reportIntervalMinSeconds; }
    public int reportIntervalMaxSeconds() { return reportIntervalMaxSeconds; }
    public String linkSharedSecret() { return linkSharedSecret; }
    public int linkSessionTtlSeconds() { return linkSessionTtlSeconds; }
    public boolean linkSyncRequiresToProxy() { return linkSyncRequiresToProxy; }
    public boolean linkAcceptBackendRequiresSync() { return linkAcceptBackendRequiresSync; }
    public Map<String, ModRule> modRules() { return modRules; }
    public boolean waypointsEnabled() { return waypointsEnabled; }
    public List<WaypointMarker> waypointMarkers() { return waypointMarkers; }
    public double samplingFraction() { return samplingFraction; }
    public boolean promoteSuspectToRequire() { return promoteSuspectToRequire; }
    public boolean useInputTelemetry() { return useInputTelemetry; }
    public AnticheatCheckTable checkTable() { return checkTable; }
    public boolean genericAdapterEnabled() { return genericAdapterEnabled; }
    public List<String> genericCommandPatterns() { return genericCommandPatterns; }
    public AdminConfig admin() { return admin; }
    public VoiceConfig voice() { return voice; }
    public PermissionsConfig permissions() { return permissions; }

    public static final class WaypointMarker {
        private final String id;
        private final String name;
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final String color;

        public WaypointMarker(String id, String name, String world, double x, double y, double z, String color) {
            this.id = id;
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String world() { return world; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public String color() { return color; }

        public Map<String, Object> toJsonMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
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

    public static final class AdminConfig {
        private final String hwidSalt;
        private final int auditRetentionDays;
        private final ScreenshotDeliveryService.DeliveryConfig screenshotDelivery;
        private final int screenshotMaxWidth;
        private final int screenshotMaxHeight;
        private final int screenshotJpegQuality;
        private final int screenshotMaxBytes;

        public AdminConfig(
                String hwidSalt,
                int auditRetentionDays,
                ScreenshotDeliveryService.DeliveryConfig screenshotDelivery,
                int screenshotMaxWidth,
                int screenshotMaxHeight,
                int screenshotJpegQuality,
                int screenshotMaxBytes
        ) {
            this.hwidSalt = hwidSalt;
            this.auditRetentionDays = auditRetentionDays;
            this.screenshotDelivery = screenshotDelivery;
            this.screenshotMaxWidth = screenshotMaxWidth;
            this.screenshotMaxHeight = screenshotMaxHeight;
            this.screenshotJpegQuality = screenshotJpegQuality;
            this.screenshotMaxBytes = screenshotMaxBytes;
        }

        public String hwidSalt() { return hwidSalt; }
        public int auditRetentionDays() { return auditRetentionDays; }
        public ScreenshotDeliveryService.DeliveryConfig screenshotDelivery() { return screenshotDelivery; }
        public int screenshotMaxWidth() { return screenshotMaxWidth; }
        public int screenshotMaxHeight() { return screenshotMaxHeight; }
        public int screenshotJpegQuality() { return screenshotJpegQuality; }
        public int screenshotMaxBytes() { return screenshotMaxBytes; }

        public static AdminConfig defaults() {
            return new AdminConfig(
                    "",
                    30,
                    ScreenshotDeliveryService.DeliveryConfig.defaults(),
                    1280,
                    720,
                    75,
                    1_048_576
            );
        }
    }

    public static final class VoiceConfig {
        private final String mode;
        private final Double maxDistance;
        private final Double whisperDistance;
        private final String falloffMode;
        private final Double falloffFactor;
        private final Boolean allowGroups;
        private final boolean recordingAllowed;

        public VoiceConfig(
                String mode,
                Double maxDistance,
                Double whisperDistance,
                String falloffMode,
                Double falloffFactor,
                Boolean allowGroups,
                boolean recordingAllowed
        ) {
            this.mode = mode;
            this.maxDistance = maxDistance;
            this.whisperDistance = whisperDistance;
            this.falloffMode = falloffMode;
            this.falloffFactor = falloffFactor;
            this.allowGroups = allowGroups;
            this.recordingAllowed = recordingAllowed;
        }

        public String mode() { return mode; }
        public Double maxDistance() { return maxDistance; }
        public Double whisperDistance() { return whisperDistance; }
        public String falloffMode() { return falloffMode; }
        public Double falloffFactor() { return falloffFactor; }
        public Boolean allowGroups() { return allowGroups; }
        public boolean recordingAllowed() { return recordingAllowed; }

        public static VoiceConfig defaults() {
            return new VoiceConfig("auto", 48.0, 16.0, "natural", 1.0, Boolean.TRUE, false);
        }
    }

    public static final class PermissionsConfig {
        private final List<String> onHaikyo;
        private final List<String> onAttested;

        public PermissionsConfig(List<String> onHaikyo, List<String> onAttested) {
            this.onHaikyo = Java8Compat.copyOfList(onHaikyo);
            this.onAttested = Java8Compat.copyOfList(onAttested);
        }

        public List<String> onHaikyo() { return onHaikyo; }
        public List<String> onAttested() { return onAttested; }

        public static PermissionsConfig empty() {
            return new PermissionsConfig(Collections.<String>emptyList(), Collections.<String>emptyList());
        }
    }

    public static HaikyoConfig loadDefault() {
        try (InputStream is = HaikyoConfig.class.getResourceAsStream("/config.yml")) {
            if (is == null) {
                return fromMap(Collections.<String, Object>emptyMap());
            }
            return fromYaml(new String(Java8Compat.readAllBytes(is), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return fromMap(Collections.<String, Object>emptyMap());
        }
    }

    public static HaikyoConfig loadFromFile(Path path) throws IOException {
        return fromYaml(Java8Compat.readString(path, StandardCharsets.UTF_8));
    }

    public static HaikyoConfig fromYaml(String yaml) {
        return fromMap(SimpleYamlParser.parse(yaml));
    }

    public static HaikyoConfig fromMap(Map<String, Object> root) {
        String language = JsonCodec.asString(root, "language", "en_us");
        String role = JsonCodec.asString(root, "role", "auto").toLowerCase(Locale.ROOT);
        String rawAddr = JsonCodec.asString(root, "server-address", "");
        String normAddr = Java8Compat.isBlank(rawAddr) ? "" : ServerAddressNormalizer.normalize(rawAddr);
        String serverId = JsonCodec.asString(root, "server-id", normAddr.isEmpty() ? "haikyo-server" : normAddr);
        String downloadUrl = JsonCodec.asString(root, "download-url", "https://haikyo.conquister.net");
        boolean requireHaikyo = JsonCodec.asBoolean(root, "require-haikyo", false);
        int attestTimeout = Math.max(3, JsonCodec.asInt(root, "attestation-timeout-seconds", 15));

        List<String> consentScopes = JsonCodec.asStringList(root, "consent-scopes");
        if (consentScopes.isEmpty()) {
            consentScopes = Java8Compat.listOf("version", "integrity", "mods", "inputs");
        }

        Map<String, Object> attestMap = JsonCodec.asObject(root, "attestation");
        Map<String, String> pubKeys = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> e : JsonCodec.asObject(attestMap, "public-keys").entrySet()) {
            if (e.getValue() != null) {
                String val = e.getValue().toString().trim();
                if (!val.isEmpty()) {
                    pubKeys.put(e.getKey(), val);
                }
            }
        }
        Set<String> revoked = new LinkedHashSet<String>(JsonCodec.asStringList(attestMap, "revoked-versions"));
        boolean requireNoAgent = JsonCodec.asBoolean(attestMap, "require-no-agent", true);
        int reportMin = Math.max(5, JsonCodec.asInt(attestMap, "report-interval-min-seconds", 15));
        int reportMax = Math.max(reportMin, JsonCodec.asInt(attestMap, "report-interval-max-seconds", 45));

        Map<String, Object> linkMap = JsonCodec.asObject(root, "link");
        String sharedSecret = JsonCodec.asString(linkMap, "shared-secret", "");
        int linkTtl = Math.max(10, JsonCodec.asInt(linkMap, "session-ttl-seconds", 60));
        boolean linkSyncToProxy = JsonCodec.asBoolean(linkMap, "sync-requires-to-proxy", true);
        boolean linkAcceptBackendSync = JsonCodec.asBoolean(linkMap, "accept-backend-requires-sync", true);

        Map<String, Object> modsMap = JsonCodec.asObject(root, "mods");
        Map<String, ModRule> modRules = new LinkedHashMap<String, ModRule>();
        for (Map.Entry<String, Object> e : JsonCodec.asObject(modsMap, "rules").entrySet()) {
            if (e.getValue() instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) e.getValue();
                ModRule.Action action = ModRule.Action.fromWireName(JsonCodec.asString(r, "action", "allow"));
                Map<String, Object> params = JsonCodec.asObject(r, "params");
                String reason = JsonCodec.asString(r, "reason", "");
                modRules.put(e.getKey(), new ModRule(action, params, Java8Compat.isBlank(reason) ? null : reason));
            }
        }

        Map<String, Object> wpMap = JsonCodec.asObject(modsMap, "waypoints");
        boolean wpEnabled = JsonCodec.asBoolean(wpMap, "enabled", false);
        List<WaypointMarker> wpMarkers = new ArrayList<WaypointMarker>();
        for (Object item : JsonCodec.asList(wpMap, "markers")) {
            if (item instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) item;
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
            Map<String, Map<String, CheckPolicy>> parsed = new LinkedHashMap<String, Map<String, CheckPolicy>>();
            for (Map.Entry<String, Object> acEntry : checksObj.entrySet()) {
                if (acEntry.getValue() instanceof Map<?, ?>) {
                    Map<?, ?> rawChecks = (Map<?, ?>) acEntry.getValue();
                    Map<String, CheckPolicy> checkMap = new LinkedHashMap<String, CheckPolicy>();
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

        Map<String, Object> adminMap = JsonCodec.asObject(root, "admin");
        String hwidSalt = JsonCodec.asString(adminMap, "hwid-salt", "");
        int auditDays = Math.max(1, JsonCodec.asInt(adminMap, "audit-retention-days", 30));
        Map<String, Object> ssMap = JsonCodec.asObject(adminMap, "screenshot");
        ScreenshotDeliveryService.Mode ssMode = ScreenshotDeliveryService.Mode.fromConfig(
                JsonCodec.asString(ssMap, "delivery", "builtin")
        );
        int ssTtl = Math.max(10, JsonCodec.asInt(ssMap, "ttl-seconds", 3600));
        int ssMaxW = Math.max(160, JsonCodec.asInt(ssMap, "max-width", 1280));
        int ssMaxH = Math.max(120, JsonCodec.asInt(ssMap, "max-height", 720));
        int ssQual = Math.max(20, Math.min(95, JsonCodec.asInt(ssMap, "jpeg-quality", 75)));
        int ssMaxBytes = Math.max(32_768, Math.min(1_048_576, JsonCodec.asInt(ssMap, "max-bytes", 1_048_576)));
        Map<String, Object> builtinMap = JsonCodec.asObject(ssMap, "builtin");
        Map<String, Object> uploadMap = JsonCodec.asObject(ssMap, "upload");
        ScreenshotDeliveryService.DeliveryConfig deliveryCfg = new ScreenshotDeliveryService.DeliveryConfig(
                ssMode,
                ssTtl,
                JsonCodec.asString(builtinMap, "bind-host", "0.0.0.0"),
                JsonCodec.asInt(builtinMap, "port", 8765),
                JsonCodec.asString(builtinMap, "public-url", ""),
                JsonCodec.asString(uploadMap, "url", ""),
                JsonCodec.asString(uploadMap, "auth-header", "")
        );
        AdminConfig adminCfg = new AdminConfig(hwidSalt, auditDays, deliveryCfg, ssMaxW, ssMaxH, ssQual, ssMaxBytes);

        Map<String, Object> voiceMap = JsonCodec.asObject(root, "voice");
        Object maxDistObj = voiceMap.get("max-distance");
        Object whisperDistObj = voiceMap.get("whisper-distance");
        Object falloffFactorObj = voiceMap.get("falloff-factor");
        Object allowGroupsObj = voiceMap.get("allow-groups");
        VoiceConfig voiceCfg = new VoiceConfig(
                JsonCodec.asString(voiceMap, "mode", "auto").toLowerCase(Locale.ROOT),
                voiceMap.containsKey("max-distance")
                        ? (maxDistObj instanceof Number ? Double.valueOf(((Number) maxDistObj).doubleValue()) : null)
                        : Double.valueOf(48.0),
                voiceMap.containsKey("whisper-distance")
                        ? (whisperDistObj instanceof Number ? Double.valueOf(((Number) whisperDistObj).doubleValue()) : null)
                        : Double.valueOf(16.0),
                voiceMap.containsKey("falloff-mode")
                        ? JsonCodec.asNullableString(voiceMap, "falloff-mode")
                        : "natural",
                voiceMap.containsKey("falloff-factor")
                        ? (falloffFactorObj instanceof Number ? Double.valueOf(((Number) falloffFactorObj).doubleValue()) : null)
                        : Double.valueOf(1.0),
                voiceMap.containsKey("allow-groups")
                        ? (allowGroupsObj instanceof Boolean ? (Boolean) allowGroupsObj : null)
                        : Boolean.TRUE,
                JsonCodec.asBoolean(voiceMap, "recording-allowed", false)
        );

        Map<String, Object> permMap = JsonCodec.asObject(root, "permissions");
        PermissionsConfig permCfg = new PermissionsConfig(
                JsonCodec.asStringList(permMap, "on-haikyo"),
                JsonCodec.asStringList(permMap, "on-attested")
        );

        return new HaikyoConfig(
                language,
                role,
                normAddr,
                serverId,
                downloadUrl,
                requireHaikyo,
                attestTimeout,
                Java8Compat.copyOfList(consentScopes),
                Collections.unmodifiableMap(pubKeys),
                Collections.unmodifiableSet(revoked),
                requireNoAgent,
                reportMin,
                reportMax,
                sharedSecret,
                linkTtl,
                linkSyncToProxy,
                linkAcceptBackendSync,
                Collections.unmodifiableMap(modRules),
                wpEnabled,
                Java8Compat.copyOfList(wpMarkers),
                sampling,
                promoteSuspect,
                useInputTelemetry,
                checkTable,
                genericEnabled,
                Java8Compat.copyOfList(genericPatterns),
                adminCfg,
                voiceCfg,
                permCfg
        );
    }
}

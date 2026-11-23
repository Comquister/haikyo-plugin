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
package dev.haikyo.core.protocol;

import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.mods.ModRule;
import dev.haikyo.core.util.Hex;
import dev.haikyo.core.util.Java8Compat;
import dev.haikyo.core.util.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed encoders and decoders for all locked v1 {@code haikyo:*} message bodies.
 */
public final class ProtocolMessages {
    private static final byte[] AC_HMAC_PREFIX = "haikyo-ac-v1\n".getBytes(StandardCharsets.UTF_8);

    // haikyo:ac input flags (gameplay inputs only — never typed chat/text keys)
    public static final int FLAG_FWD = 1 << 0;
    public static final int FLAG_BACK = 1 << 1;
    public static final int FLAG_LEFT = 1 << 2;
    public static final int FLAG_RIGHT = 1 << 3;
    public static final int FLAG_JUMP = 1 << 4;
    public static final int FLAG_SNEAK = 1 << 5;
    public static final int FLAG_SPRINT = 1 << 6;
    public static final int FLAG_ATTACK = 1 << 7;
    public static final int FLAG_USE = 1 << 8;
    public static final int FLAG_ON_GROUND = 1 << 9;

    public static final int AC_MAX_SAMPLES = 20;

    private ProtocolMessages() {
    }

    public static final class Hello {
        private final int protocolVersion;
        private final String serverRole;
        private final String serverId;
        private final String pluginVersion;
        private final boolean requireHaikyo;
        private final Map<String, Integer> channels;
        private final List<String> modChannels;
        private final List<String> consentScopes;
        private final String downloadUrl;

        public Hello(
                int protocolVersion,
                String serverRole,
                String serverId,
                String pluginVersion,
                boolean requireHaikyo,
                Map<String, Integer> channels,
                List<String> modChannels,
                List<String> consentScopes,
                String downloadUrl
        ) {
            this.protocolVersion = protocolVersion;
            this.serverRole = serverRole;
            this.serverId = serverId;
            this.pluginVersion = pluginVersion;
            this.requireHaikyo = requireHaikyo;
            this.channels = channels;
            this.modChannels = modChannels;
            this.consentScopes = consentScopes;
            this.downloadUrl = downloadUrl;
        }

        public int protocolVersion() { return protocolVersion; }
        public String serverRole() { return serverRole; }
        public String serverId() { return serverId; }
        public String pluginVersion() { return pluginVersion; }
        public boolean requireHaikyo() { return requireHaikyo; }
        public Map<String, Integer> channels() { return channels; }
        public List<String> modChannels() { return modChannels; }
        public List<String> consentScopes() { return consentScopes; }
        public String downloadUrl() { return downloadUrl; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("protocol_version", protocolVersion);
            m.put("server_role", serverRole);
            m.put("server_id", serverId);
            m.put("plugin_version", pluginVersion);
            m.put("require_haikyo", requireHaikyo);
            m.put("channels", channels);
            m.put("mod_channels", modChannels);
            m.put("consent_scopes", consentScopes);
            m.put("download_url", downloadUrl);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static Hello decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Map<String, Integer> ch = new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "channels").entrySet()) {
                if (e.getValue() instanceof Number) {
                    ch.put(e.getKey(), ((Number) e.getValue()).intValue());
                }
            }
            return new Hello(
                    JsonCodec.asInt(m, "protocol_version", HaikyoChannels.PROTOCOL_VERSION),
                    JsonCodec.asString(m, "server_role", "standalone"),
                    JsonCodec.asString(m, "server_id", ""),
                    JsonCodec.asString(m, "plugin_version", ""),
                    JsonCodec.asBoolean(m, "require_haikyo", false),
                    ch,
                    JsonCodec.asStringList(m, "mod_channels"),
                    JsonCodec.asStringList(m, "consent_scopes"),
                    JsonCodec.asString(m, "download_url", "")
            );
        }
    }

    public static final class HelloAck {
        private final int protocolVersion;
        private final String clientVersion;
        private final String channel;
        private final Map<String, Integer> channels;
        private final List<String> modChannels;
        private final List<String> consentedScopes;

        public HelloAck(
                int protocolVersion,
                String clientVersion,
                String channel,
                Map<String, Integer> channels,
                List<String> modChannels,
                List<String> consentedScopes
        ) {
            this.protocolVersion = protocolVersion;
            this.clientVersion = clientVersion;
            this.channel = channel;
            this.channels = channels;
            this.modChannels = modChannels;
            this.consentedScopes = consentedScopes;
        }

        public int protocolVersion() { return protocolVersion; }
        public String clientVersion() { return clientVersion; }
        public String channel() { return channel; }
        public Map<String, Integer> channels() { return channels; }
        public List<String> modChannels() { return modChannels; }
        public List<String> consentedScopes() { return consentedScopes; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("protocol_version", protocolVersion);
            m.put("client_version", clientVersion);
            m.put("channel", channel);
            m.put("channels", channels);
            m.put("mod_channels", modChannels);
            m.put("consented_scopes", consentedScopes);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static HelloAck decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Map<String, Integer> ch = new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "channels").entrySet()) {
                if (e.getValue() instanceof Number) {
                    ch.put(e.getKey(), ((Number) e.getValue()).intValue());
                }
            }
            return new HelloAck(
                    JsonCodec.asInt(m, "protocol_version", 0),
                    JsonCodec.asString(m, "client_version", ""),
                    JsonCodec.asString(m, "channel", ""),
                    ch,
                    JsonCodec.asStringList(m, "mod_channels"),
                    JsonCodec.asStringList(m, "consented_scopes")
            );
        }
    }

    public static final class AttestChallenge {
        private final String nonceHex;
        private final String serverAddress;
        private final String sessionKeyHex;
        private final int reportIntervalMinSeconds;
        private final int reportIntervalMaxSeconds;

        public AttestChallenge(
                String nonceHex,
                String serverAddress,
                String sessionKeyHex,
                int reportIntervalMinSeconds,
                int reportIntervalMaxSeconds
        ) {
            this.nonceHex = nonceHex;
            this.serverAddress = serverAddress;
            this.sessionKeyHex = sessionKeyHex;
            this.reportIntervalMinSeconds = reportIntervalMinSeconds;
            this.reportIntervalMaxSeconds = reportIntervalMaxSeconds;
        }

        public String nonceHex() { return nonceHex; }
        public String serverAddress() { return serverAddress; }
        public String sessionKeyHex() { return sessionKeyHex; }
        public int reportIntervalMinSeconds() { return reportIntervalMinSeconds; }
        public int reportIntervalMaxSeconds() { return reportIntervalMaxSeconds; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("nonce", nonceHex);
            m.put("server_address", serverAddress);
            m.put("session_key", sessionKeyHex);
            m.put("report_interval_min_s", reportIntervalMinSeconds);
            m.put("report_interval_max_s", reportIntervalMaxSeconds);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AttestChallenge decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            return new AttestChallenge(
                    JsonCodec.asString(m, "nonce", ""),
                    JsonCodec.asString(m, "server_address", ""),
                    JsonCodec.asString(m, "session_key", ""),
                    JsonCodec.asInt(m, "report_interval_min_s", 15),
                    JsonCodec.asInt(m, "report_interval_max_s", 45)
            );
        }
    }

    public static final class AttestResponse {
        private final String tokenBase64;
        private final String error;

        public AttestResponse(String tokenBase64, String error) {
            this.tokenBase64 = tokenBase64;
            this.error = error;
        }

        public String tokenBase64() { return tokenBase64; }
        public String error() { return error; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("token", tokenBase64);
            m.put("error", error);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AttestResponse decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            return new AttestResponse(
                    JsonCodec.asNullableString(m, "token"),
                    JsonCodec.asNullableString(m, "error")
            );
        }
    }

    public static final class AttestResult {
        private final boolean accepted;
        private final TrustLevel trustLevel;
        private final String reason;

        public AttestResult(boolean accepted, TrustLevel trustLevel, String reason) {
            this.accepted = accepted;
            this.trustLevel = trustLevel;
            this.reason = reason;
        }

        public boolean accepted() { return accepted; }
        public TrustLevel trustLevel() { return trustLevel; }
        public String reason() { return reason; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("accepted", accepted);
            m.put("trust_level", trustLevel.wireName());
            m.put("reason", reason);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AttestResult decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            return new AttestResult(
                    JsonCodec.asBoolean(m, "accepted", false),
                    TrustLevel.fromWireName(JsonCodec.asString(m, "trust_level", "none")),
                    JsonCodec.asNullableString(m, "reason")
            );
        }
    }

    public static final class ReportedModState {
        private final boolean enabled;
        private final Map<String, Object> params;

        public ReportedModState(boolean enabled, Map<String, Object> params) {
            this.enabled = enabled;
            this.params = params == null
                    ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(params));
        }

        public boolean enabled() { return enabled; }
        public Map<String, Object> params() { return params; }
    }

    public static final class IntegrityReport {
        private final long seq;
        private final long timestamp;
        private final Map<String, ReportedModState> mods;
        private final boolean agentControl;
        private final List<String> signals;
        private final String hmacHex;

        public IntegrityReport(
                long seq,
                long timestamp,
                Map<String, ReportedModState> mods,
                boolean agentControl,
                List<String> signals,
                String hmacHex
        ) {
            this.seq = seq;
            this.timestamp = timestamp;
            this.mods = mods;
            this.agentControl = agentControl;
            this.signals = signals;
            this.hmacHex = hmacHex;
        }

        public long seq() { return seq; }
        public long timestamp() { return timestamp; }
        public Map<String, ReportedModState> mods() { return mods; }
        public boolean agentControl() { return agentControl; }
        public List<String> signals() { return signals; }
        public String hmacHex() { return hmacHex; }

        public String canonicalSigningInput() {
            TreeMap<String, ReportedModState> sortedMods = new TreeMap<String, ReportedModState>(mods);
            StringBuilder modsCanon = new StringBuilder();
            boolean firstMod = true;
            for (Map.Entry<String, ReportedModState> e : sortedMods.entrySet()) {
                if (!firstMod) {
                    modsCanon.append(',');
                }
                firstMod = false;
                modsCanon.append(e.getKey()).append('=').append(e.getValue().enabled() ? '1' : '0');
                Map<String, Object> p = e.getValue().params();
                if (!p.isEmpty()) {
                    modsCanon.append(':');
                    TreeMap<String, Object> sortedParams = new TreeMap<String, Object>(p);
                    boolean firstParam = true;
                    for (Map.Entry<String, Object> pe : sortedParams.entrySet()) {
                        if (!firstParam) {
                            modsCanon.append(';');
                        }
                        firstParam = false;
                        modsCanon.append(pe.getKey()).append('=').append(JsonCodec.formatCanonicalScalar(pe.getValue()));
                    }
                }
            }

            List<String> sortedSignals = new ArrayList<String>(signals);
            Collections.sort(sortedSignals);
            String signalsCanon = String.join(",", sortedSignals);

            return "haikyo-report-v1\n"
                    + seq + "\n"
                    + timestamp + "\n"
                    + (agentControl ? "1" : "0") + "\n"
                    + modsCanon + "\n"
                    + signalsCanon;
        }

        public String computeHmacHex(byte[] sessionKey) {
            byte[] input = canonicalSigningInput().getBytes(StandardCharsets.UTF_8);
            return Hex.encode(HaikyoLinkCodec.hmacSha256(sessionKey, input));
        }

        public boolean verifyHmac(byte[] sessionKey) {
            if (sessionKey == null || sessionKey.length != 32 || hmacHex == null || hmacHex.length() != 64) {
                return false;
            }
            try {
                byte[] provided = Hex.decode(hmacHex);
                byte[] expected = HaikyoLinkCodec.hmacSha256(
                        sessionKey,
                        canonicalSigningInput().getBytes(StandardCharsets.UTF_8)
                );
                return Hex.constantTimeEquals(provided, expected);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        public static IntegrityReport decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Map<String, ReportedModState> mods = new LinkedHashMap<String, ReportedModState>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "mods").entrySet()) {
                if (e.getValue() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modObj = (Map<String, Object>) e.getValue();
                    boolean enabled = JsonCodec.asBoolean(modObj, "enabled", false);
                    Map<String, Object> params = JsonCodec.asObject(modObj, "params");
                    mods.put(e.getKey(), new ReportedModState(enabled, params));
                }
            }
            return new IntegrityReport(
                    JsonCodec.asLong(m, "seq", 0L),
                    JsonCodec.asLong(m, "timestamp", 0L),
                    mods,
                    JsonCodec.asBoolean(m, "agent_control", false),
                    JsonCodec.asStringList(m, "signals"),
                    JsonCodec.asString(m, "hmac", "")
            );
        }
    }

    public static final class ModsRulesMessage {
        private final Map<String, ModRule> rules;

        public ModsRulesMessage(Map<String, ModRule> rules) {
            this.rules = rules;
        }

        public Map<String, ModRule> rules() { return rules; }

        public byte[] encodeJson() {
            Map<String, Object> rulesMap = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, ModRule> entry : rules.entrySet()) {
                ModRule r = entry.getValue();
                Map<String, Object> ruleObj = new LinkedHashMap<String, Object>();
                ruleObj.put("action", r.action().wireName());
                if (!r.params().isEmpty()) {
                    ruleObj.put("params", r.params());
                }
                if (!Java8Compat.isBlank(r.reason())) {
                    ruleObj.put("reason", r.reason());
                }
                rulesMap.put(entry.getKey(), ruleObj);
            }
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("rules", rulesMap);
            return JsonCodec.stringify(root).getBytes(StandardCharsets.UTF_8);
        }

        public static ModsRulesMessage decodeJson(byte[] body) {
            Map<String, Object> root = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Map<String, ModRule> rules = new LinkedHashMap<String, ModRule>();
            for (Map.Entry<String, Object> entry : JsonCodec.asObject(root, "rules").entrySet()) {
                if (entry.getValue() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ruleObj = (Map<String, Object>) entry.getValue();
                    ModRule.Action action = ModRule.Action.fromWireName(JsonCodec.asString(ruleObj, "action", "allow"));
                    Map<String, Object> params = JsonCodec.asObject(ruleObj, "params");
                    String reason = JsonCodec.asNullableString(ruleObj, "reason");
                    rules.put(entry.getKey(), new ModRule(action, params, reason));
                }
            }
            return new ModsRulesMessage(rules);
        }
    }

    public static final class AcInputSample {
        private final int flags;
        private final float yawDelta;
        private final float pitchDelta;

        public AcInputSample(int flags, float yawDelta, float pitchDelta) {
            this.flags = flags;
            this.yawDelta = yawDelta;
            this.pitchDelta = pitchDelta;
        }

        public int flags() { return flags; }
        public float yawDelta() { return yawDelta; }
        public float pitchDelta() { return pitchDelta; }

        public boolean hasFlag(int bit) {
            return (flags & bit) != 0;
        }
    }

    public static final class AcInputsPacket {
        private final long seq;
        private final long tick;
        private final List<AcInputSample> samples;
        private final byte[] hmac;

        public AcInputsPacket(long seq, long tick, List<AcInputSample> samples, byte[] hmac) {
            this.seq = seq;
            this.tick = tick;
            this.samples = samples;
            this.hmac = hmac;
        }

        public long seq() { return seq; }
        public long tick() { return tick; }
        public List<AcInputSample> samples() { return samples; }
        public byte[] hmac() { return hmac; }

        public static byte[] encode(long seq, long tick, List<AcInputSample> samples, byte[] sessionKey) {
            if (samples == null || samples.isEmpty() || samples.size() > AC_MAX_SAMPLES) {
                throw new IllegalArgumentException("ac_inputs sample count must be in 1..20");
            }
            int bodyNoHmacLen = 4 + 8 + 1 + samples.size() * 12;
            ByteBuffer buf = ByteBuffer.allocate(bodyNoHmacLen + 32).order(ByteOrder.BIG_ENDIAN);
            buf.putInt((int) seq);
            buf.putLong(tick);
            buf.put((byte) samples.size());
            for (AcInputSample s : samples) {
                buf.putShort((short) (s.flags() & 0xFFFF));
                buf.putShort((short) 0);
                buf.putFloat(s.yawDelta());
                buf.putFloat(s.pitchDelta());
            }
            byte[] noHmac = Arrays.copyOf(buf.array(), bodyNoHmacLen);
            byte[] hmac = computeAcHmac(sessionKey, noHmac);
            buf.put(hmac);
            return buf.array();
        }

        public static AcInputsPacket decodeAndVerify(byte[] body, byte[] sessionKey) {
            if (sessionKey == null || sessionKey.length != 32) {
                throw new IllegalArgumentException("Missing session key for haikyo:ac verification");
            }
            if (body == null || body.length < 4 + 8 + 1 + 12 + 32) {
                throw new IllegalArgumentException("ac_inputs packet too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            long seq = Integer.toUnsignedLong(buf.getInt());
            long tick = buf.getLong();
            int count = Byte.toUnsignedInt(buf.get());
            if (count < 1 || count > AC_MAX_SAMPLES) {
                throw new IllegalArgumentException("Invalid ac_inputs sample count: " + count);
            }
            int expectedLen = 4 + 8 + 1 + count * 12 + 32;
            if (body.length != expectedLen) {
                throw new IllegalArgumentException("ac_inputs length mismatch: " + body.length + " != " + expectedLen);
            }
            int noHmacLen = body.length - 32;
            byte[] noHmac = Arrays.copyOfRange(body, 0, noHmacLen);
            byte[] receivedHmac = Arrays.copyOfRange(body, noHmacLen, body.length);
            byte[] expectedHmac = computeAcHmac(sessionKey, noHmac);
            if (!Hex.constantTimeEquals(receivedHmac, expectedHmac)) {
                throw new SecurityException("ac_inputs HMAC-SHA256 mismatch");
            }
            List<AcInputSample> samples = new ArrayList<AcInputSample>(count);
            for (int i = 0; i < count; i++) {
                int flags = Short.toUnsignedInt(buf.getShort());
                buf.getShort();
                float yawDelta = buf.getFloat();
                float pitchDelta = buf.getFloat();
                samples.add(new AcInputSample(flags, yawDelta, pitchDelta));
            }
            return new AcInputsPacket(seq, tick, Java8Compat.copyOfList(samples), receivedHmac);
        }

        private static byte[] computeAcHmac(byte[] sessionKey, byte[] bodyWithoutHmac) {
            byte[] macInput = new byte[AC_HMAC_PREFIX.length + bodyWithoutHmac.length];
            System.arraycopy(AC_HMAC_PREFIX, 0, macInput, 0, AC_HMAC_PREFIX.length);
            System.arraycopy(bodyWithoutHmac, 0, macInput, AC_HMAC_PREFIX.length, bodyWithoutHmac.length);
            return HaikyoLinkCodec.hmacSha256(sessionKey, macInput);
        }
    }

    public static final class AdminRequest {
        private final String requestId;
        private final String kind;
        private final String hwidSalt;
        private final Integer maxWidth;
        private final Integer maxHeight;
        private final Integer jpegQuality;
        private final Integer maxBytes;

        public AdminRequest(
                String requestId,
                String kind,
                String hwidSalt,
                Integer maxWidth,
                Integer maxHeight,
                Integer jpegQuality,
                Integer maxBytes
        ) {
            this.requestId = requestId;
            this.kind = kind;
            this.hwidSalt = hwidSalt;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.jpegQuality = jpegQuality;
            this.maxBytes = maxBytes;
        }

        public String requestId() { return requestId; }
        public String kind() { return kind; }
        public String hwidSalt() { return hwidSalt; }
        public Integer maxWidth() { return maxWidth; }
        public Integer maxHeight() { return maxHeight; }
        public Integer jpegQuality() { return jpegQuality; }
        public Integer maxBytes() { return maxBytes; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("request_id", requestId);
            m.put("kind", kind);
            if (hwidSalt != null && !hwidSalt.isEmpty()) {
                m.put("hwid_salt", hwidSalt);
            }
            if (maxWidth != null) {
                m.put("max_width", maxWidth);
            }
            if (maxHeight != null) {
                m.put("max_height", maxHeight);
            }
            if (jpegQuality != null) {
                m.put("jpeg_quality", jpegQuality);
            }
            if (maxBytes != null) {
                m.put("max_bytes", maxBytes);
            }
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AdminRequest decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Object mw = m.get("max_width");
            Object mh = m.get("max_height");
            Object jq = m.get("jpeg_quality");
            Object mb = m.get("max_bytes");
            return new AdminRequest(
                    JsonCodec.asString(m, "request_id", ""),
                    JsonCodec.asString(m, "kind", ""),
                    JsonCodec.asNullableString(m, "hwid_salt"),
                    mw instanceof Number ? Integer.valueOf(((Number) mw).intValue()) : null,
                    mh instanceof Number ? Integer.valueOf(((Number) mh).intValue()) : null,
                    jq instanceof Number ? Integer.valueOf(((Number) jq).intValue()) : null,
                    mb instanceof Number ? Integer.valueOf(((Number) mb).intValue()) : null
            );
        }
    }

    public static final class AdminInfoResponse {
        private final String requestId;
        private final String error;
        private final String version;
        private final String channel;
        private final String exeSha256;
        private final String os;
        private final String cpu;
        private final String gpu;
        private final long ramMb;
        private final String display;
        private final String language;
        private final int avgFps;
        private final String hwid;
        private final boolean agentControl;
        private final List<String> mods;
        private final List<String> signals;

        public AdminInfoResponse(
                String requestId,
                String error,
                String version,
                String channel,
                String exeSha256,
                String os,
                String cpu,
                String gpu,
                long ramMb,
                String display,
                String language,
                int avgFps,
                String hwid,
                boolean agentControl,
                List<String> mods,
                List<String> signals
        ) {
            this.requestId = requestId;
            this.error = error;
            this.version = version;
            this.channel = channel;
            this.exeSha256 = exeSha256;
            this.os = os;
            this.cpu = cpu;
            this.gpu = gpu;
            this.ramMb = ramMb;
            this.display = display;
            this.language = language;
            this.avgFps = avgFps;
            this.hwid = hwid;
            this.agentControl = agentControl;
            this.mods = mods;
            this.signals = signals;
        }

        public String requestId() { return requestId; }
        public String error() { return error; }
        public String version() { return version; }
        public String channel() { return channel; }
        public String exeSha256() { return exeSha256; }
        public String os() { return os; }
        public String cpu() { return cpu; }
        public String gpu() { return gpu; }
        public long ramMb() { return ramMb; }
        public String display() { return display; }
        public String language() { return language; }
        public int avgFps() { return avgFps; }
        public String hwid() { return hwid; }
        public boolean agentControl() { return agentControl; }
        public List<String> mods() { return mods; }
        public List<String> signals() { return signals; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("request_id", requestId);
            m.put("error", error);
            m.put("version", version);
            m.put("channel", channel);
            m.put("exe_sha256", exeSha256);
            m.put("os", os);
            m.put("cpu", cpu);
            m.put("gpu", gpu);
            m.put("ram_mb", ramMb);
            m.put("display", display);
            m.put("language", language);
            m.put("avg_fps", avgFps);
            m.put("hwid", hwid);
            m.put("agent_control", agentControl);
            m.put("mods", mods);
            m.put("signals", signals);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AdminInfoResponse decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            return new AdminInfoResponse(
                    JsonCodec.asString(m, "request_id", ""),
                    JsonCodec.asNullableString(m, "error"),
                    JsonCodec.asString(m, "version", ""),
                    JsonCodec.asString(m, "channel", ""),
                    JsonCodec.asString(m, "exe_sha256", ""),
                    JsonCodec.asString(m, "os", ""),
                    JsonCodec.asString(m, "cpu", ""),
                    JsonCodec.asString(m, "gpu", ""),
                    JsonCodec.asLong(m, "ram_mb", 0L),
                    JsonCodec.asString(m, "display", ""),
                    JsonCodec.asString(m, "language", ""),
                    JsonCodec.asInt(m, "avg_fps", 0),
                    JsonCodec.asString(m, "hwid", ""),
                    JsonCodec.asBoolean(m, "agent_control", false),
                    JsonCodec.asStringList(m, "mods"),
                    JsonCodec.asStringList(m, "signals")
            );
        }
    }

    public static final class AdminHwidResponse {
        private final String requestId;
        private final String error;
        private final String hwid;

        public AdminHwidResponse(String requestId, String error, String hwid) {
            this.requestId = requestId;
            this.error = error;
            this.hwid = hwid;
        }

        public String requestId() { return requestId; }
        public String error() { return error; }
        public String hwid() { return hwid; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("request_id", requestId);
            m.put("error", error);
            m.put("hwid", hwid);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static AdminHwidResponse decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            return new AdminHwidResponse(
                    JsonCodec.asString(m, "request_id", ""),
                    JsonCodec.asNullableString(m, "error"),
                    JsonCodec.asString(m, "hwid", "")
            );
        }
    }

    public static final class AdminScreenshotResponse {
        public static final int STATUS_OK = 0;
        public static final int STATUS_CONSENT_DECLINED = 1;
        public static final int STATUS_RATE_LIMITED = 2;
        public static final int STATUS_CAPTURE_FAILED = 3;
        public static final int MAX_IMAGE_BYTES = 1_048_576;

        private final String requestId;
        private final int status;
        private final String error;
        private final int width;
        private final int height;
        private final String mimeType;
        private final byte[] imageBytes;

        public AdminScreenshotResponse(
                String requestId,
                int status,
                String error,
                int width,
                int height,
                String mimeType,
                byte[] imageBytes
        ) {
            this.requestId = requestId;
            this.status = status;
            this.error = error;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.imageBytes = imageBytes;
        }

        public String requestId() { return requestId; }
        public int status() { return status; }
        public String error() { return error; }
        public int width() { return width; }
        public int height() { return height; }
        public String mimeType() { return mimeType; }
        public byte[] imageBytes() { return imageBytes; }

        public byte[] encodeBinary() {
            byte[] img = imageBytes == null ? new byte[0] : imageBytes;
            if (img.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Screenshot image exceeds 1 MiB limit: " + img.length);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 + img.length);
            writeLpStr(out, requestId);
            out.write(status & 0xFF);
            writeLpStr(out, error == null ? "" : error);
            out.write((width >>> 8) & 0xFF);
            out.write(width & 0xFF);
            out.write((height >>> 8) & 0xFF);
            out.write(height & 0xFF);
            writeLpStr(out, mimeType == null ? "image/jpeg" : mimeType);
            ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            lenBuf.putInt(img.length);
            byte[] lenArr = lenBuf.array();
            out.write(lenArr, 0, lenArr.length);
            out.write(img, 0, img.length);
            return out.toByteArray();
        }

        public static AdminScreenshotResponse decodeBinary(byte[] body) {
            if (body == null || body.length < 15) {
                throw new IllegalArgumentException("admin_screenshot_response too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            String requestId = readLpStr(buf);
            if (!buf.hasRemaining()) {
                throw new IllegalArgumentException("Truncated screenshot status");
            }
            int status = Byte.toUnsignedInt(buf.get());
            String error = readLpStr(buf);
            if (buf.remaining() < 4) {
                throw new IllegalArgumentException("Truncated screenshot dimensions");
            }
            int width = Short.toUnsignedInt(buf.getShort());
            int height = Short.toUnsignedInt(buf.getShort());
            String mimeType = readLpStr(buf);
            if (buf.remaining() < 4) {
                throw new IllegalArgumentException("Truncated screenshot image_len");
            }
            long imgLenLong = Integer.toUnsignedLong(buf.getInt());
            if (imgLenLong > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Screenshot image_len exceeds 1 MiB: " + imgLenLong);
            }
            int imgLen = (int) imgLenLong;
            if (buf.remaining() != imgLen) {
                throw new IllegalArgumentException("Screenshot image_len mismatch: remaining=" + buf.remaining() + " expected=" + imgLen);
            }
            byte[] img = new byte[imgLen];
            buf.get(img);
            return new AdminScreenshotResponse(requestId, status, error, width, height, mimeType, img);
        }

        private static void writeLpStr(ByteArrayOutputStream out, String s) {
            byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("lp_str too long: " + bytes.length);
            }
            out.write((bytes.length >>> 8) & 0xFF);
            out.write(bytes.length & 0xFF);
            out.write(bytes, 0, bytes.length);
        }

        private static String readLpStr(ByteBuffer buf) {
            if (buf.remaining() < 2) {
                throw new IllegalArgumentException("Truncated lp_str length");
            }
            int len = Short.toUnsignedInt(buf.getShort());
            if (buf.remaining() < len) {
                throw new IllegalArgumentException("Truncated lp_str payload");
            }
            byte[] bytes = new byte[len];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static final class VoiceConfigMessage {
        private final String mode;
        private final Double maxDistance;
        private final Double whisperDistance;
        private final String falloffMode;
        private final Double falloffFactor;
        private final Boolean allowGroups;
        private final boolean recordingAllowed;
        private final boolean muted;
        private final boolean forceMuted;
        private final long muteUntil;
        private final String muteReason;
        private final boolean adminVoiceActive;

        public VoiceConfigMessage(
                String mode,
                Double maxDistance,
                Double whisperDistance,
                String falloffMode,
                Double falloffFactor,
                Boolean allowGroups,
                boolean recordingAllowed,
                boolean muted,
                boolean forceMuted,
                long muteUntil,
                String muteReason,
                boolean adminVoiceActive
        ) {
            this.mode = mode;
            this.maxDistance = maxDistance;
            this.whisperDistance = whisperDistance;
            this.falloffMode = falloffMode;
            this.falloffFactor = falloffFactor;
            this.allowGroups = allowGroups;
            this.recordingAllowed = recordingAllowed;
            this.muted = muted;
            this.forceMuted = forceMuted;
            this.muteUntil = muteUntil;
            this.muteReason = muteReason;
            this.adminVoiceActive = adminVoiceActive;
        }

        public String mode() { return mode; }
        public Double maxDistance() { return maxDistance; }
        public Double whisperDistance() { return whisperDistance; }
        public String falloffMode() { return falloffMode; }
        public Double falloffFactor() { return falloffFactor; }
        public Boolean allowGroups() { return allowGroups; }
        public boolean recordingAllowed() { return recordingAllowed; }
        public boolean muted() { return muted; }
        public boolean forceMuted() { return forceMuted; }
        public long muteUntil() { return muteUntil; }
        public String muteReason() { return muteReason; }
        public boolean adminVoiceActive() { return adminVoiceActive; }

        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("mode", mode);
            m.put("max_distance", maxDistance);
            m.put("whisper_distance", whisperDistance);
            m.put("falloff_mode", falloffMode);
            m.put("falloff_factor", falloffFactor);
            m.put("allow_groups", allowGroups);
            m.put("recording_allowed", recordingAllowed);
            m.put("muted", muted);
            m.put("force_muted", forceMuted);
            m.put("mute_until", muteUntil);
            m.put("mute_reason", muteReason);
            m.put("admin_voice_active", adminVoiceActive);
            return JsonCodec.stringify(m).getBytes(StandardCharsets.UTF_8);
        }

        public static VoiceConfigMessage decodeJson(byte[] body) {
            Map<String, Object> m = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Object md = m.get("max_distance");
            Object wd = m.get("whisper_distance");
            Object ff = m.get("falloff_factor");
            Object ag = m.get("allow_groups");
            return new VoiceConfigMessage(
                    JsonCodec.asString(m, "mode", "off"),
                    md instanceof Number ? Double.valueOf(((Number) md).doubleValue()) : null,
                    wd instanceof Number ? Double.valueOf(((Number) wd).doubleValue()) : null,
                    JsonCodec.asNullableString(m, "falloff_mode"),
                    ff instanceof Number ? Double.valueOf(((Number) ff).doubleValue()) : null,
                    ag instanceof Boolean ? (Boolean) ag : null,
                    JsonCodec.asBoolean(m, "recording_allowed", false),
                    JsonCodec.asBoolean(m, "muted", false),
                    JsonCodec.asBoolean(m, "force_muted", false),
                    JsonCodec.asLong(m, "mute_until", 0L),
                    JsonCodec.asNullableString(m, "mute_reason"),
                    JsonCodec.asBoolean(m, "admin_voice_active", false)
            );
        }
    }
}

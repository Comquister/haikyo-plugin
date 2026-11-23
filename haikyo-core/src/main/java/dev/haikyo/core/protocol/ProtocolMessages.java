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
import dev.haikyo.core.util.JsonCodec;

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

    public record Hello(
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
        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<>();
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
            Map<String, Integer> ch = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "channels").entrySet()) {
                if (e.getValue() instanceof Number n) {
                    ch.put(e.getKey(), n.intValue());
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

    public record HelloAck(
            int protocolVersion,
            String clientVersion,
            String channel,
            Map<String, Integer> channels,
            List<String> modChannels,
            List<String> consentedScopes
    ) {
        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<>();
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
            Map<String, Integer> ch = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "channels").entrySet()) {
                if (e.getValue() instanceof Number n) {
                    ch.put(e.getKey(), n.intValue());
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

    public record AttestChallenge(
            String nonceHex,
            String serverAddress,
            String sessionKeyHex,
            int reportIntervalMinSeconds,
            int reportIntervalMaxSeconds
    ) {
        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<>();
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

    public record AttestResponse(String tokenBase64, String error) {
        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<>();
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

    public record AttestResult(boolean accepted, TrustLevel trustLevel, String reason) {
        public byte[] encodeJson() {
            Map<String, Object> m = new LinkedHashMap<>();
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

    public record ReportedModState(boolean enabled, Map<String, Object> params) {
        public ReportedModState {
            params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    public record IntegrityReport(
            long seq,
            long timestamp,
            Map<String, ReportedModState> mods,
            boolean agentControl,
            List<String> signals,
            String hmacHex
    ) {
        public String canonicalSigningInput() {
            TreeMap<String, ReportedModState> sortedMods = new TreeMap<>(mods);
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
                    TreeMap<String, Object> sortedParams = new TreeMap<>(p);
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

            List<String> sortedSignals = new ArrayList<>(signals);
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
            Map<String, ReportedModState> mods = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : JsonCodec.asObject(m, "mods").entrySet()) {
                if (e.getValue() instanceof Map<?, ?> rawMod) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modObj = (Map<String, Object>) rawMod;
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

    public record ModsRulesMessage(Map<String, ModRule> rules) {
        public byte[] encodeJson() {
            Map<String, Object> rulesMap = new LinkedHashMap<>();
            for (Map.Entry<String, ModRule> entry : rules.entrySet()) {
                ModRule r = entry.getValue();
                Map<String, Object> ruleObj = new LinkedHashMap<>();
                ruleObj.put("action", r.action().wireName());
                if (!r.params().isEmpty()) {
                    ruleObj.put("params", r.params());
                }
                if (r.reason() != null && !r.reason().isBlank()) {
                    ruleObj.put("reason", r.reason());
                }
                rulesMap.put(entry.getKey(), ruleObj);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("rules", rulesMap);
            return JsonCodec.stringify(root).getBytes(StandardCharsets.UTF_8);
        }

        public static ModsRulesMessage decodeJson(byte[] body) {
            Map<String, Object> root = JsonCodec.parseObject(new String(body, StandardCharsets.UTF_8));
            Map<String, ModRule> rules = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : JsonCodec.asObject(root, "rules").entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> rawRule) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ruleObj = (Map<String, Object>) rawRule;
                    ModRule.Action action = ModRule.Action.fromWireName(JsonCodec.asString(ruleObj, "action", "allow"));
                    Map<String, Object> params = JsonCodec.asObject(ruleObj, "params");
                    String reason = JsonCodec.asNullableString(ruleObj, "reason");
                    rules.put(entry.getKey(), new ModRule(action, params, reason));
                }
            }
            return new ModsRulesMessage(rules);
        }
    }

    public record AcInputSample(int flags, float yawDelta, float pitchDelta) {
        public boolean hasFlag(int bit) {
            return (flags & bit) != 0;
        }
    }

    public record AcInputsPacket(long seq, long tick, List<AcInputSample> samples, byte[] hmac) {
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
            List<AcInputSample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int flags = Short.toUnsignedInt(buf.getShort());
                buf.getShort(); // reserved
                float yawDelta = buf.getFloat();
                float pitchDelta = buf.getFloat();
                samples.add(new AcInputSample(flags, yawDelta, pitchDelta));
            }
            return new AcInputsPacket(seq, tick, List.copyOf(samples), receivedHmac);
        }

        private static byte[] computeAcHmac(byte[] sessionKey, byte[] bodyWithoutHmac) {
            byte[] macInput = new byte[AC_HMAC_PREFIX.length + bodyWithoutHmac.length];
            System.arraycopy(AC_HMAC_PREFIX, 0, macInput, 0, AC_HMAC_PREFIX.length);
            System.arraycopy(bodyWithoutHmac, 0, macInput, AC_HMAC_PREFIX.length, bodyWithoutHmac.length);
            return HaikyoLinkCodec.hmacSha256(sessionKey, macInput);
        }
    }
}

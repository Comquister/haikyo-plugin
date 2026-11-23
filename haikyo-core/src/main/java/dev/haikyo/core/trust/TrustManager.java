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
package dev.haikyo.core.trust;

import dev.haikyo.api.HaikyoApi;
import dev.haikyo.api.ModMessageHandler;
import dev.haikyo.api.ModSignalEvent;
import dev.haikyo.api.ModSignalListener;
import dev.haikyo.api.PlayerSession;
import dev.haikyo.api.TrustChangeEvent;
import dev.haikyo.api.TrustChangeListener;
import dev.haikyo.api.TrustLevel;
import dev.haikyo.core.admin.AdminRequestManager;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.crypto.AttestationVerifier;
import dev.haikyo.core.crypto.HaikyoLinkCodec;
import dev.haikyo.core.mods.ModRuleEngine;
import dev.haikyo.core.protocol.FragmentReassembler;
import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;
import dev.haikyo.core.store.RequiresHaikyoStore;
import dev.haikyo.core.util.Hex;
import dev.haikyo.core.voice.VoiceModerationManager;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Pure-Java, thread-safe core engine for Haikyo sessions, attestation, mod compliance,
 * trust levels ({@code NONE}/{@code ATTESTED}/{@code SUSPECT}), sampling, and anticheat flag decisions.
 *
 * <p>Safe for Folia region threads, Paper main/async threads, and Velocity event loops.</p>
 */
public final class TrustManager implements HaikyoApi {

    public enum ServerRole {
        STANDALONE,
        BACKEND,
        PROXY;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    @FunctionalInterface
    public interface PacketSender {
        boolean send(UUID playerId, String channel, byte[] wireFrame);
    }

    @FunctionalInterface
    public interface PlayerKicker {
        void kick(UUID playerId, String reasonKey, String detail);
    }

    @FunctionalInterface
    public interface ModChannelHook {
        void onModChannelChanged(String channel, boolean registered);
    }

    public record FlagDecision(boolean cancelFlag, CheckPolicy policy, String reason, boolean inputCorroborated) {
    }

    public record RegisteredModEndpoint(String modId, String version, ModMessageHandler handler) {
        public String capabilityTag() {
            return modId + "@" + version;
        }
    }

    private final Clock clock;
    private final DoubleSupplier samplerRng;
    private final SecureRandom secureRandom;
    private final RequiresHaikyoStore requiresStore;
    private final PacketSender packetSender;
    private final PlayerKicker playerKicker;
    private final ModChannelHook modChannelHook;
    private final String pluginVersion;
    private final AdminRequestManager adminRequestManager;
    private final VoiceModerationManager voiceModerationManager;

    private volatile HaikyoConfig config;
    private volatile ServerRole effectiveRole;
    private volatile AttestationVerifier attestationVerifier;
    private volatile ModRuleEngine modRuleEngine;

    private final ConcurrentHashMap<UUID, MutableSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegisteredModEndpoint> modEndpoints = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TrustChangeListener> trustListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ModSignalListener> signalListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<UUID>> handshakeListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextFragId = new AtomicInteger(1);

    public TrustManager(
            HaikyoConfig config,
            ServerRole effectiveRole,
            String pluginVersion,
            RequiresHaikyoStore requiresStore,
            PacketSender packetSender,
            PlayerKicker playerKicker,
            ModChannelHook modChannelHook,
            Clock clock,
            DoubleSupplier samplerRng
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.samplerRng = Objects.requireNonNull(samplerRng, "samplerRng");
        this.secureRandom = new SecureRandom();
        this.requiresStore = Objects.requireNonNull(requiresStore, "requiresStore");
        this.packetSender = packetSender == null ? (p, c, d) -> false : packetSender;
        this.playerKicker = playerKicker == null ? (p, k, d) -> { } : playerKicker;
        this.modChannelHook = modChannelHook == null ? (c, r) -> { } : modChannelHook;
        this.pluginVersion = pluginVersion == null ? "0.1.0" : pluginVersion;
        this.adminRequestManager = new AdminRequestManager(this.clock);
        this.voiceModerationManager = new VoiceModerationManager(this.clock);
        reloadConfig(config, effectiveRole);
    }

    public AdminRequestManager adminRequests() {
        return adminRequestManager;
    }

    public VoiceModerationManager voiceModeration() {
        return voiceModerationManager;
    }

    public void reloadConfig(HaikyoConfig newConfig, ServerRole newEffectiveRole) {
        this.config = Objects.requireNonNull(newConfig, "config");
        this.effectiveRole = Objects.requireNonNull(newEffectiveRole, "effectiveRole");
        this.attestationVerifier = AttestationVerifier.fromBase64Keys(
                newConfig.attestationPublicKeys(),
                newConfig.revokedVersions()
        );
        this.modRuleEngine = new ModRuleEngine(newConfig.modRules());
    }

    public HaikyoConfig config() {
        return config;
    }

    public ServerRole effectiveRole() {
        return effectiveRole;
    }

    public RequiresHaikyoStore requiresStore() {
        return requiresStore;
    }

    public ModRuleEngine modRuleEngine() {
        return modRuleEngine;
    }

    public Collection<RegisteredModEndpoint> registeredModEndpoints() {
        return Collections.unmodifiableCollection(modEndpoints.values());
    }

    public List<String> advertisedModCapabilities() {
        List<String> caps = new ArrayList<>();
        for (RegisteredModEndpoint ep : modEndpoints.values()) {
            caps.add(ep.capabilityTag());
        }
        Collections.sort(caps);
        return caps;
    }

    // -------------------------------------------------------------------------
    // HaikyoApi implementation
    // -------------------------------------------------------------------------

    @Override
    public void registerModEndpoint(String modId, String version, ModMessageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        String channel = HaikyoChannels.modChannel(modId);
        String ver = (version == null || version.isBlank()) ? "1" : version.trim();
        modEndpoints.put(modId, new RegisteredModEndpoint(modId, ver, handler));
        modChannelHook.onModChannelChanged(channel, true);
    }

    @Override
    public void unregisterModEndpoint(String modId) {
        if (modId == null) {
            return;
        }
        RegisteredModEndpoint removed = modEndpoints.remove(modId);
        if (removed != null) {
            modChannelHook.onModChannelChanged(HaikyoChannels.modChannel(modId), false);
        }
    }

    @Override
    public boolean sendToPlayer(UUID playerId, String modId, byte[] payload) {
        MutableSession session = sessions.get(playerId);
        if (session == null || !session.haikyoClient) {
            return false;
        }
        String channel = HaikyoChannels.modChannel(modId);
        List<byte[]> frames = WireFrame.encodeForWire(
                HaikyoChannels.PKT_MOD_OPAQUE,
                payload == null ? new byte[0] : payload,
                HaikyoChannels.MAX_S2C_WIRE_BYTES,
                nextFragId.getAndIncrement()
        );
        boolean allSent = true;
        for (byte[] frame : frames) {
            if (!packetSender.send(playerId, channel, frame)) {
                allSent = false;
            }
        }
        return allSent;
    }

    @Override
    public TrustLevel getTrustLevel(UUID playerId) {
        MutableSession s = sessions.get(playerId);
        return s == null ? TrustLevel.NONE : s.trustLevel;
    }

    @Override
    public boolean isHaikyoClient(UUID playerId) {
        MutableSession s = sessions.get(playerId);
        return s != null && s.haikyoClient;
    }

    @Override
    public Optional<PlayerSession> getPlayerSession(UUID playerId) {
        MutableSession s = sessions.get(playerId);
        return s == null ? Optional.empty() : Optional.of(s.snapshot());
    }

    public List<PlayerSession> allSessions() {
        List<PlayerSession> out = new ArrayList<>();
        for (MutableSession s : sessions.values()) {
            out.add(s.snapshot());
        }
        return out;
    }

    @Override
    public void addTrustChangeListener(TrustChangeListener listener) {
        if (listener != null) {
            trustListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeTrustChangeListener(TrustChangeListener listener) {
        trustListeners.remove(listener);
    }

    @Override
    public void addModSignalListener(ModSignalListener listener) {
        if (listener != null) {
            signalListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeModSignalListener(ModSignalListener listener) {
        signalListeners.remove(listener);
    }

    public void addHandshakeListener(Consumer<UUID> listener) {
        if (listener != null) {
            handshakeListeners.addIfAbsent(listener);
        }
    }

    public void removeHandshakeListener(Consumer<UUID> listener) {
        handshakeListeners.remove(listener);
    }

    @Override
    public boolean markSuspect(UUID playerId, String reason) {
        MutableSession s = sessions.get(playerId);
        if (s == null) {
            return false;
        }
        String r = (reason == null || reason.isBlank()) ? "manual_suspect" : reason;
        transitionToSuspect(s, "", "suspect:" + r, r);
        return true;
    }

    // -------------------------------------------------------------------------
    // Player lifecycle & protocol entry points
    // -------------------------------------------------------------------------

    public PlayerSession onPlayerJoin(UUID playerId, String playerName) {
        Instant now = clock.instant();
        MutableSession session = new MutableSession(playerId, playerName == null ? "" : playerName, now);
        sessions.put(playerId, session);
        return session.snapshot();
    }

    public void onPlayerQuit(UUID playerId) {
        sessions.remove(playerId);
        voiceModerationManager.clearPlayer(playerId);
    }

    /**
     * Builds and sends the {@code haikyo:control} {@code hello} frame to the player.
     */
    public boolean sendHello(UUID playerId) {
        HaikyoConfig cfg = this.config;
        Map<String, Integer> channels = new LinkedHashMap<>();
        channels.put("control", HaikyoChannels.PROTOCOL_VERSION);
        channels.put("attest", HaikyoChannels.PROTOCOL_VERSION);
        if (effectiveRole != ServerRole.PROXY) {
            channels.put("mods", HaikyoChannels.PROTOCOL_VERSION);
            channels.put("ac", HaikyoChannels.PROTOCOL_VERSION);
            channels.put("admin", HaikyoChannels.PROTOCOL_VERSION);
            channels.put("voice", HaikyoChannels.PROTOCOL_VERSION);
        }
        ProtocolMessages.Hello hello = new ProtocolMessages.Hello(
                HaikyoChannels.PROTOCOL_VERSION,
                effectiveRole.wireName(),
                cfg.serverId(),
                pluginVersion,
                isHaikyoRequiredFor(playerId),
                channels,
                advertisedModCapabilities(),
                cfg.consentScopes(),
                cfg.downloadUrl()
        );
        byte[] frame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_CONTROL_HELLO, hello.encodeJson());
        return packetSender.send(playerId, HaikyoChannels.CONTROL, frame);
    }

    public boolean sendModRules(UUID playerId) {
        if (effectiveRole == ServerRole.PROXY) {
            return false;
        }
        return packetSender.send(playerId, HaikyoChannels.MODS, modRuleEngine.encodeRulesFrame());
    }

    public boolean sendVoiceConfig(UUID playerId) {
        if (effectiveRole == ServerRole.PROXY) {
            return false;
        }
        MutableSession s = sessions.get(playerId);
        if (s == null || !s.haikyoClient) {
            return false;
        }
        byte[] frame = voiceModerationManager.encodeConfigFrame(playerId, config.voice());
        return packetSender.send(playerId, HaikyoChannels.VOICE, frame);
    }

    public Optional<String> sendAdminInfoRequest(
            UUID playerId,
            String targetName,
            String actorName,
            Consumer<ProtocolMessages.AdminInfoResponse> onReply
    ) {
        return sendAdminRequestInternal(playerId, targetName, actorName, "info", obj -> {
            if (obj instanceof ProtocolMessages.AdminInfoResponse resp && onReply != null) {
                onReply.accept(resp);
            }
        });
    }

    public Optional<String> sendAdminHwidRequest(
            UUID playerId,
            String targetName,
            String actorName,
            Consumer<ProtocolMessages.AdminHwidResponse> onReply
    ) {
        return sendAdminRequestInternal(playerId, targetName, actorName, "hwid", obj -> {
            if (obj instanceof ProtocolMessages.AdminHwidResponse resp && onReply != null) {
                onReply.accept(resp);
            }
        });
    }

    public Optional<String> sendAdminScreenshotRequest(
            UUID playerId,
            String targetName,
            String actorName,
            Consumer<ProtocolMessages.AdminScreenshotResponse> onReply
    ) {
        return sendAdminRequestInternal(playerId, targetName, actorName, "screenshot", obj -> {
            if (obj instanceof ProtocolMessages.AdminScreenshotResponse resp && onReply != null) {
                onReply.accept(resp);
            }
        });
    }

    private Optional<String> sendAdminRequestInternal(
            UUID playerId,
            String targetName,
            String actorName,
            String kind,
            Consumer<Object> callback
    ) {
        MutableSession s = sessions.get(playerId);
        if (s == null || !s.haikyoClient) {
            return Optional.empty();
        }
        HaikyoConfig cfg = this.config;
        HaikyoConfig.AdminConfig ac = cfg.admin();
        String salt = (ac.hwidSalt() == null || ac.hwidSalt().isBlank()) ? cfg.serverId() : ac.hwidSalt();
        AdminRequestManager.PendingRequest pending = adminRequestManager.createRequest(
                kind, playerId, targetName, actorName, callback
        );
        ProtocolMessages.AdminRequest req = "screenshot".equals(kind)
                ? new ProtocolMessages.AdminRequest(
                        pending.requestId(), kind, null,
                        ac.screenshotMaxWidth(), ac.screenshotMaxHeight(),
                        ac.screenshotJpegQuality(), ac.screenshotMaxBytes())
                : new ProtocolMessages.AdminRequest(
                        pending.requestId(), kind, salt,
                        null, null, null, null);
        byte[] frame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ADMIN_REQUEST, req.encodeJson());
        if (!packetSender.send(playerId, HaikyoChannels.ADMIN, frame)) {
            adminRequestManager.complete(pending.requestId(), playerId, kind);
            return Optional.empty();
        }
        return Optional.of(pending.requestId());
    }

    /**
     * Sends a fresh (or existing pending) {@code haikyo:attest} challenge to the player.
     */
    public ProtocolMessages.AttestChallenge issueAttestChallenge(UUID playerId) {
        MutableSession s = sessions.get(playerId);
        if (s == null) {
            return null;
        }
        HaikyoConfig cfg = this.config;
        byte[] nonce;
        byte[] sessionKey;
        synchronized (s) {
            if (s.pendingNonce == null) {
                s.pendingNonce = randomBytes(32);
            }
            if (s.sessionKey == null) {
                s.sessionKey = randomBytes(32);
            }
            nonce = s.pendingNonce;
            sessionKey = s.sessionKey;
        }
        ProtocolMessages.AttestChallenge challenge = new ProtocolMessages.AttestChallenge(
                Hex.encode(nonce),
                cfg.serverAddress(),
                Hex.encode(sessionKey),
                cfg.reportIntervalMinSeconds(),
                cfg.reportIntervalMaxSeconds()
        );
        byte[] frame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_CHALLENGE, challenge.encodeJson());
        packetSender.send(playerId, HaikyoChannels.ATTEST, frame);
        return challenge;
    }

    /**
     * Builds a signed {@code haikyo:link} {@code 0x01} frame for proxy -> backend session transfer.
     */
    public Optional<byte[]> buildSignedLinkFrame(UUID playerId) {
        HaikyoConfig cfg = this.config;
        if (cfg.linkSharedSecret() == null || cfg.linkSharedSecret().isEmpty()) {
            return Optional.empty();
        }
        MutableSession s = sessions.get(playerId);
        if (s == null || !s.haikyoClient) {
            return Optional.empty();
        }
        long nowEpoch = clock.instant().getEpochSecond();
        HaikyoLinkCodec.LinkSession link;
        synchronized (s) {
            byte[] key = s.sessionKey != null ? s.sessionKey : randomBytes(32);
            s.sessionKey = key;
            link = new HaikyoLinkCodec.LinkSession(
                    s.playerId,
                    s.trustLevel == TrustLevel.ATTESTED || s.attestedAt != null,
                    s.trustLevel,
                    s.clientVersion,
                    s.clientChannel,
                    cfg.serverId(),
                    new LinkedHashSet<>(s.consentedScopes),
                    s.signals.size(),
                    nowEpoch,
                    nowEpoch + cfg.linkSessionTtlSeconds(),
                    key
            );
        }
        byte[] body = HaikyoLinkCodec.encodeSignedSession(link, cfg.linkSharedSecret());
        return Optional.of(WireFrame.encodeUnfragmented(HaikyoChannels.PKT_LINK_SESSION, body));
    }

    /**
     * Handles an incoming {@code haikyo:link} frame on a backend server.
     *
     * @return decode result; when invalid (e.g. missing secret or bad HMAC), caller should log a warning
     *         and fall back to standalone handshake/attestation.
     */
    public HaikyoLinkCodec.DecodeResult handleIncomingLink(UUID playerId, byte[] wireBytes) {
        MutableSession s = sessions.get(playerId);
        if (s == null) {
            return HaikyoLinkCodec.DecodeResult.fail(HaikyoLinkCodec.FailureCode.MALFORMED_PAYLOAD, "Unknown player session");
        }
        WireFrame.DecodeResult decoded = WireFrame.decode(wireBytes, HaikyoChannels.MAX_S2C_WIRE_BYTES);
        if (!(decoded instanceof WireFrame.DecodeOk ok) || ok.frame().fragmented()
                || ok.frame().packetType() != HaikyoChannels.PKT_LINK_SESSION) {
            return HaikyoLinkCodec.DecodeResult.fail(HaikyoLinkCodec.FailureCode.MALFORMED_PAYLOAD, "Invalid haikyo:link frame");
        }
        HaikyoLinkCodec.DecodeResult res = HaikyoLinkCodec.verifyAndDecodeSession(
                ok.frame().payload(),
                config.linkSharedSecret(),
                playerId,
                clock.instant()
        );
        if (!res.valid()) {
            return res;
        }
        HaikyoLinkCodec.LinkSession ls = res.session();
        TrustLevel prev;
        TrustLevel target = ls.trustLevel();
        Instant now = clock.instant();
        synchronized (s) {
            prev = s.trustLevel;
            s.haikyoClient = true;
            s.protocolVersion = HaikyoChannels.PROTOCOL_VERSION;
            s.clientVersion = ls.clientVersion();
            s.clientChannel = ls.channel();
            s.proxyId = ls.proxyId();
            s.consentedScopes.clear();
            s.consentedScopes.addAll(ls.consentedScopes());
            s.sessionKey = ls.sessionKey();
            s.proxySessionTrusted = true;
            if (ls.attested()) {
                s.attestedAt = now;
            }
            if (target == TrustLevel.ATTESTED && prev != TrustLevel.SUSPECT) {
                s.trustLevel = TrustLevel.ATTESTED;
                s.sampledUnrelaxed = samplerRng.getAsDouble() < config.samplingFraction();
            } else if (target == TrustLevel.SUSPECT) {
                s.trustLevel = TrustLevel.SUSPECT;
                s.sampledUnrelaxed = false;
            }
            target = s.trustLevel;
        }
        if (prev != target) {
            emitTrustChange(playerId, prev, target, "proxy_link_session");
        }
        sendModRules(playerId);
        return res;
    }

    /**
     * Dispatches any client-to-server plugin message arriving on a {@code haikyo:*} channel.
     */
    public void handleClientMessage(UUID playerId, String channel, byte[] wireBytes) {
        MutableSession s = sessions.get(playerId);
        if (s == null || channel == null || wireBytes == null) {
            return;
        }
        // Clients must NEVER inject haikyo:link frames
        if (HaikyoChannels.LINK.equals(channel)) {
            return;
        }
        FragmentReassembler reassembler = s.reassemblers.computeIfAbsent(channel, k -> new FragmentReassembler());
        FragmentReassembler.Result result = reassembler.accept(wireBytes, HaikyoChannels.MAX_C2S_WIRE_BYTES, clock.instant());
        if (!(result instanceof FragmentReassembler.Complete complete)) {
            // Higher protocol versions, pending fragments, and malformed frames never disconnect the client
            return;
        }
        FragmentReassembler.CompletePacket pkt = complete.packet();
        try {
            if (HaikyoChannels.CONTROL.equals(channel) && pkt.packetType() == HaikyoChannels.PKT_CONTROL_HELLO_ACK) {
                handleHelloAck(s, ProtocolMessages.HelloAck.decodeJson(pkt.body()));
            } else if (HaikyoChannels.ATTEST.equals(channel)) {
                if (pkt.packetType() == HaikyoChannels.PKT_ATTEST_RESPONSE) {
                    handleAttestResponse(s, ProtocolMessages.AttestResponse.decodeJson(pkt.body()));
                } else if (pkt.packetType() == HaikyoChannels.PKT_ATTEST_INTEGRITY_REPORT) {
                    handleIntegrityReport(s, ProtocolMessages.IntegrityReport.decodeJson(pkt.body()));
                }
            } else if (HaikyoChannels.AC.equals(channel) && pkt.packetType() == HaikyoChannels.PKT_AC_INPUTS) {
                handleAcInputs(s, pkt.body());
            } else if (HaikyoChannels.ADMIN.equals(channel)) {
                handleAdminResponse(playerId, pkt.packetType(), pkt.body());
            } else if (channel.startsWith(HaikyoChannels.MOD_PREFIX) && pkt.packetType() == HaikyoChannels.PKT_MOD_OPAQUE) {
                String modId = HaikyoChannels.extractModId(channel);
                if (modId != null) {
                    RegisteredModEndpoint ep = modEndpoints.get(modId);
                    if (ep != null) {
                        ep.handler().onMessage(playerId, modId, pkt.body());
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed untrusted client JSON/binary payload: ignore safely without crashing server thread
        }
    }

    private void handleHelloAck(MutableSession s, ProtocolMessages.HelloAck ack) {
        if (ack.protocolVersion() > HaikyoChannels.PROTOCOL_VERSION || ack.protocolVersion() <= 0) {
            return;
        }
        boolean shouldChallenge;
        synchronized (s) {
            s.haikyoClient = true;
            s.protocolVersion = ack.protocolVersion();
            s.clientVersion = ack.clientVersion();
            s.clientChannel = ack.channel();
            s.negotiatedChannels.clear();
            s.negotiatedChannels.addAll(ack.channels().keySet());
            s.modCapabilities.clear();
            for (String cap : ack.modChannels()) {
                int at = cap.indexOf('@');
                if (at > 0 && at + 1 < cap.length()) {
                    s.modCapabilities.put(cap.substring(0, at), cap.substring(at + 1));
                } else if (!cap.isBlank()) {
                    s.modCapabilities.put(cap, "1");
                }
            }
            s.consentedScopes.clear();
            s.consentedScopes.addAll(ack.consentedScopes());
            shouldChallenge = (effectiveRole != ServerRole.BACKEND || !s.proxySessionTrusted)
                    && s.attestedAt == null;
        }
        if (effectiveRole != ServerRole.PROXY) {
            sendModRules(s.playerId);
            if (!"off".equals(voiceModerationManager.effectiveMode(config.voice()))) {
                sendVoiceConfig(s.playerId);
            }
        }
        if (shouldChallenge) {
            issueAttestChallenge(s.playerId);
        }
        for (Consumer<UUID> l : handshakeListeners) {
            try {
                l.accept(s.playerId);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void handleAttestResponse(MutableSession s, ProtocolMessages.AttestResponse resp) {
        if (resp.error() != null && !resp.error().isBlank()) {
            sendAttestResult(s.playerId, false, s.trustLevel, resp.error());
            if (isHaikyoRequiredFor(s.playerId)) {
                playerKicker.kick(s.playerId, "kick.attestation_failed", resp.error());
            }
            return;
        }
        byte[] expectedNonce;
        synchronized (s) {
            expectedNonce = s.pendingNonce;
        }
        AttestationVerifier.VerificationResult ver = attestationVerifier.verifyBase64(
                resp.tokenBase64(),
                s.playerId,
                expectedNonce,
                config.serverAddress(),
                clock.instant()
        );
        if (!ver.valid()) {
            String reason = ver.failureCode().name().toLowerCase(java.util.Locale.ROOT);
            sendAttestResult(s.playerId, false, s.trustLevel, reason);
            if (isHaikyoRequiredFor(s.playerId)) {
                playerKicker.kick(s.playerId, "kick.attestation_failed", reason);
            }
            return;
        }

        TrustLevel prev;
        TrustLevel current;
        Instant now = clock.instant();
        synchronized (s) {
            prev = s.trustLevel;
            s.attestedAt = now;
            s.clientVersion = ver.token().clientVersion();
            s.clientChannel = ver.token().channel();
            // Do not clear SUSPECT if player already triggered signals
            if (s.trustLevel != TrustLevel.SUSPECT) {
                s.trustLevel = TrustLevel.ATTESTED;
                s.sampledUnrelaxed = samplerRng.getAsDouble() < config.samplingFraction();
            }
            current = s.trustLevel;
        }
        if (prev != current) {
            emitTrustChange(s.playerId, prev, current, "attestation_verified");
        }
        sendAttestResult(s.playerId, true, current, null);
    }

    private void handleIntegrityReport(MutableSession s, ProtocolMessages.IntegrityReport report) {
        byte[] sessionKey;
        long prevSeq;
        synchronized (s) {
            sessionKey = s.sessionKey;
            prevSeq = s.lastReportSeq;
        }
        if (sessionKey == null || !report.verifyHmac(sessionKey)) {
            transitionToSuspect(s, "", "invalid_report_hmac", "Integrity report HMAC verification failed");
            return;
        }
        if (report.seq() <= prevSeq) {
            transitionToSuspect(s, "", "replayed_report_seq", "Integrity report seq not monotonic: " + report.seq());
            return;
        }
        synchronized (s) {
            s.lastReportSeq = report.seq();
            s.lastReportAt = clock.instant();
        }

        if (report.agentControl() && config.requireNoAgent()) {
            transitionToSuspect(s, "", "agent_control_enabled", "Client reported Agent Control (--control) enabled");
        }
        for (String sig : report.signals()) {
            if (sig != null && !sig.isBlank()) {
                transitionToSuspect(s, "", "client_signal:" + sig, "Client integrity signal: " + sig);
            }
        }
        // Proxy role does not send or own backend mods.rules; only standalone/backend enforce mod rules
        if (effectiveRole != ServerRole.PROXY) {
            List<ModRuleEngine.ModViolation> violations = modRuleEngine.verifyCompliance(report.mods());
            for (ModRuleEngine.ModViolation v : violations) {
                transitionToSuspect(s, v.modId(), v.code(), v.detail());
            }
        }
    }

    private void handleAcInputs(MutableSession s, byte[] body) {
        byte[] sessionKey;
        synchronized (s) {
            sessionKey = s.sessionKey;
        }
        if (sessionKey == null) {
            return;
        }
        try {
            ProtocolMessages.AcInputsPacket packet = ProtocolMessages.AcInputsPacket.decodeAndVerify(body, sessionKey);
            AcInputVerifier.AcceptResult res = s.acInputVerifier.accept(packet, clock.instant());
            if (!res.accepted() && res.rejectReason() != null && res.rejectReason().contains("non-monotonic")) {
                transitionToSuspect(s, "", "ac_inputs_replay", res.rejectReason());
            }
        } catch (SecurityException e) {
            transitionToSuspect(s, "", "invalid_ac_hmac", "haikyo:ac HMAC verification failed");
        }
    }

    private void handleAdminResponse(UUID playerId, int packetType, byte[] body) {
        if (packetType == HaikyoChannels.PKT_ADMIN_INFO_RESPONSE) {
            ProtocolMessages.AdminInfoResponse resp = ProtocolMessages.AdminInfoResponse.decodeJson(body);
            adminRequestManager.complete(resp.requestId(), playerId, "info")
                    .ifPresent(req -> {
                        if (req.callback() != null) {
                            req.callback().accept(resp);
                        }
                    });
        } else if (packetType == HaikyoChannels.PKT_ADMIN_HWID_RESPONSE) {
            ProtocolMessages.AdminHwidResponse resp = ProtocolMessages.AdminHwidResponse.decodeJson(body);
            adminRequestManager.complete(resp.requestId(), playerId, "hwid")
                    .ifPresent(req -> {
                        if (req.callback() != null) {
                            req.callback().accept(resp);
                        }
                    });
        } else if (packetType == HaikyoChannels.PKT_ADMIN_SCREENSHOT_RESPONSE) {
            ProtocolMessages.AdminScreenshotResponse resp = ProtocolMessages.AdminScreenshotResponse.decodeBinary(body);
            adminRequestManager.complete(resp.requestId(), playerId, "screenshot")
                    .ifPresent(req -> {
                        if (req.callback() != null) {
                            req.callback().accept(resp);
                        }
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Timeouts & Anticheat Trust Decisions
    // -------------------------------------------------------------------------

    /**
     * Scans all online {@code ATTESTED} sessions and transitions any player whose integrity report
     * is overdue (beyond {@code 2 * reportIntervalMax + 10s}) to {@code SUSPECT}.
     *
     * @return list of player UUIDs newly marked suspect due to missing reports
     */
    public List<UUID> checkReportTimeouts() {
        Instant now = clock.instant();
        HaikyoConfig cfg = this.config;
        Duration maxGap = Duration.ofSeconds(cfg.reportIntervalMaxSeconds() * 2L + 10L);
        List<UUID> timedOut = new ArrayList<>();
        for (MutableSession s : sessions.values()) {
            boolean overdue = false;
            synchronized (s) {
                if (s.trustLevel == TrustLevel.ATTESTED && s.attestedAt != null) {
                    Instant reference = s.lastReportAt != null ? s.lastReportAt : s.attestedAt;
                    if (Duration.between(reference, now).compareTo(maxGap) > 0) {
                        overdue = true;
                    }
                }
            }
            if (overdue) {
                transitionToSuspect(s, "", "missing_integrity_report", "Integrity report missing beyond timeout");
                timedOut.add(s.playerId);
            }
        }
        return timedOut;
    }

    /**
     * Checks whether a player who must use Haikyo has failed to attest in time.
     *
     * @return localization kick key if the player should be kicked, or empty if allowed
     */
    public Optional<String> checkAttestationTimeout(UUID playerId) {
        MutableSession s = sessions.get(playerId);
        if (s == null) {
            return Optional.empty();
        }
        boolean listedInStore = requiresStore.isRequired(playerId);
        if (!config.requireHaikyo() && !listedInStore) {
            return Optional.empty();
        }
        synchronized (s) {
            if (s.attestedAt != null) {
                return Optional.empty();
            }
            Duration elapsed = Duration.between(s.connectedAt, clock.instant());
            if (elapsed.getSeconds() < config.attestationTimeoutSeconds()) {
                return Optional.empty();
            }
            if (!s.haikyoClient) {
                return Optional.of(listedInStore ? "kick.require_haikyo_flagged" : "kick.require_haikyo");
            }
            return Optional.of("kick.attestation_timeout");
        }
    }

    public boolean isHaikyoRequiredFor(UUID playerId) {
        return config.requireHaikyo() || requiresStore.isRequired(playerId);
    }

    /**
     * Core Phase 4 anticheat decision hook used by GrimAC, Vulcan, and Generic adapters.
     */
    public FlagDecision evaluateFlag(UUID playerId, String anticheatId, String checkName) {
        HaikyoConfig cfg = this.config;
        CheckPolicy policy = cfg.checkTable().policyFor(anticheatId, checkName);
        MutableSession s = sessions.get(playerId);
        if (s == null) {
            return new FlagDecision(false, policy, "no_session", false);
        }

        if (policy == CheckPolicy.ALWAYS) {
            // Core/cheap checks are NEVER relaxed; flagging an ALWAYS check demotes ATTESTED -> SUSPECT
            if (s.trustLevel == TrustLevel.ATTESTED) {
                transitionToSuspect(s, "", "always_check_flag:" + checkName, "Flagged always check: " + checkName);
            }
            if (cfg.promoteSuspectToRequire()) {
                requiresStore.requirePlayer(
                        playerId,
                        s.playerName,
                        "always_check_flag:" + anticheatId + ":" + checkName,
                        "anticheat:" + anticheatId,
                        clock.instant()
                );
            }
            return new FlagDecision(false, CheckPolicy.ALWAYS, "always_check", false);
        }

        // RELAXED check: cancel ONLY if player is ATTESTED and NOT sampled
        boolean eligible;
        synchronized (s) {
            eligible = s.trustLevel == TrustLevel.ATTESTED && !s.sampledUnrelaxed;
        }
        if (!eligible) {
            String reason = s.trustLevel != TrustLevel.ATTESTED
                    ? "trust_" + s.trustLevel.wireName()
                    : "sampled_unrelaxed";
            return new FlagDecision(false, CheckPolicy.RELAXED, reason, false);
        }

        boolean inputCorroborated = cfg.useInputTelemetry()
                && s.acInputVerifier.explainsFlag(checkName, clock.instant());
        return new FlagDecision(
                true,
                CheckPolicy.RELAXED,
                inputCorroborated ? "attested_relaxed_input_explained" : "attested_relaxed",
                inputCorroborated
        );
    }

    /**
     * Promotes a player to {@code SUSPECT} and optionally adds them to {@code requires-haikyo.json}.
     */
    public boolean markSuspectAndMaybeRequire(UUID playerId, String playerName, String reason, String addedBy) {
        boolean existed = markSuspect(playerId, reason);
        if (config.promoteSuspectToRequire()) {
            requiresStore.requirePlayer(playerId, playerName, reason, addedBy, clock.instant());
        }
        return existed;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void transitionToSuspect(MutableSession s, String modId, String signalCode, String detail) {
        Instant now = clock.instant();
        TrustLevel prev;
        synchronized (s) {
            prev = s.trustLevel;
            if (!s.signals.contains(signalCode)) {
                s.signals.add(signalCode);
            }
            s.trustLevel = TrustLevel.SUSPECT;
            s.sampledUnrelaxed = false;
        }
        ModSignalEvent sigEvent = new ModSignalEvent(s.playerId, modId, signalCode, detail, now);
        for (ModSignalListener l : signalListeners) {
            try {
                l.onModSignal(sigEvent);
            } catch (RuntimeException ignored) {
            }
        }
        if (prev != TrustLevel.SUSPECT) {
            emitTrustChange(s.playerId, prev, TrustLevel.SUSPECT, signalCode);
            sendAttestResult(s.playerId, s.attestedAt != null, TrustLevel.SUSPECT, signalCode);
        }
    }

    private void emitTrustChange(UUID playerId, TrustLevel prev, TrustLevel current, String reason) {
        TrustChangeEvent event = new TrustChangeEvent(playerId, prev, current, reason, clock.instant());
        for (TrustChangeListener l : trustListeners) {
            try {
                l.onTrustChange(event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void sendAttestResult(UUID playerId, boolean accepted, TrustLevel trustLevel, String reason) {
        ProtocolMessages.AttestResult res = new ProtocolMessages.AttestResult(accepted, trustLevel, reason);
        byte[] frame = WireFrame.encodeUnfragmented(HaikyoChannels.PKT_ATTEST_RESULT, res.encodeJson());
        packetSender.send(playerId, HaikyoChannels.ATTEST, frame);
    }

    private byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        secureRandom.nextBytes(out);
        return out;
    }

    private static final class MutableSession {
        final UUID playerId;
        final String playerName;
        final Instant connectedAt;
        final ConcurrentHashMap<String, FragmentReassembler> reassemblers = new ConcurrentHashMap<>();
        final AcInputVerifier acInputVerifier = new AcInputVerifier();

        boolean haikyoClient;
        int protocolVersion;
        String clientVersion = "";
        String clientChannel = "";
        TrustLevel trustLevel = TrustLevel.NONE;
        boolean sampledUnrelaxed;
        final Set<String> negotiatedChannels = new LinkedHashSet<>();
        final Map<String, String> modCapabilities = new LinkedHashMap<>();
        final Set<String> consentedScopes = new LinkedHashSet<>();
        final List<String> signals = new ArrayList<>();
        Instant attestedAt;
        Instant lastReportAt;
        String proxyId;
        boolean proxySessionTrusted;
        byte[] pendingNonce;
        byte[] sessionKey;
        long lastReportSeq;

        MutableSession(UUID playerId, String playerName, Instant connectedAt) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.connectedAt = connectedAt;
        }

        synchronized PlayerSession snapshot() {
            return new PlayerSession(
                    playerId,
                    haikyoClient,
                    protocolVersion,
                    clientVersion,
                    clientChannel,
                    trustLevel,
                    sampledUnrelaxed,
                    negotiatedChannels,
                    modCapabilities,
                    consentedScopes,
                    signals,
                    connectedAt,
                    attestedAt,
                    lastReportAt,
                    proxyId
            );
        }
    }
}

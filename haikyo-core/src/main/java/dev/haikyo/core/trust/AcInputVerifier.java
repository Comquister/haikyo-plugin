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

import dev.haikyo.core.protocol.ProtocolMessages;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Per-player verified ring buffer of {@code haikyo:ac} gameplay input ticks.
 */
public final class AcInputVerifier {
    private static final int MAX_BUFFERED_SAMPLES = 200; // ~10 seconds at 20 TPS
    private static final int MAX_PACKETS_PER_SECOND = 25;
    private static final Duration EXPLANATION_WINDOW = Duration.ofMillis(1500);

    public static final class RecordedSample {
        private final long tick;
        private final Instant receivedAt;
        private final ProtocolMessages.AcInputSample sample;

        public RecordedSample(long tick, Instant receivedAt, ProtocolMessages.AcInputSample sample) {
            this.tick = tick;
            this.receivedAt = receivedAt;
            this.sample = sample;
        }

        public long tick() { return tick; }
        public Instant receivedAt() { return receivedAt; }
        public ProtocolMessages.AcInputSample sample() { return sample; }
    }

    public static final class AcceptResult {
        private final boolean accepted;
        private final String rejectReason;

        public AcceptResult(boolean accepted, String rejectReason) {
            this.accepted = accepted;
            this.rejectReason = rejectReason;
        }

        public boolean accepted() { return accepted; }
        public String rejectReason() { return rejectReason; }

        public static AcceptResult ok() {
            return new AcceptResult(true, null);
        }

        public static AcceptResult reject(String reason) {
            return new AcceptResult(false, reason);
        }
    }

    private final Deque<RecordedSample> buffer = new ArrayDeque<RecordedSample>(MAX_BUFFERED_SAMPLES);
    private final Deque<Instant> packetTimestamps = new ArrayDeque<Instant>(MAX_PACKETS_PER_SECOND + 2);
    private long lastSeq = -1;
    private Instant lastAcceptedAt;

    public synchronized AcceptResult accept(ProtocolMessages.AcInputsPacket packet, Instant now) {
        if (lastSeq >= 0 && packet.seq() <= lastSeq) {
            return AcceptResult.reject("non-monotonic ac_inputs seq: " + packet.seq() + " <= " + lastSeq);
        }
        Instant cutoff = now.minusSeconds(1);
        while (!packetTimestamps.isEmpty() && packetTimestamps.peekFirst().isBefore(cutoff)) {
            packetTimestamps.removeFirst();
        }
        if (packetTimestamps.size() >= MAX_PACKETS_PER_SECOND) {
            return AcceptResult.reject("ac_inputs rate limit exceeded");
        }
        packetTimestamps.addLast(now);
        lastSeq = packet.seq();
        lastAcceptedAt = now;

        long baseTick = packet.tick();
        int count = packet.samples().size();
        for (int i = 0; i < count; i++) {
            if (buffer.size() >= MAX_BUFFERED_SAMPLES) {
                buffer.removeFirst();
            }
            long sampleTick = baseTick - (count - 1 - i);
            buffer.addLast(new RecordedSample(sampleTick, now, packet.samples().get(i)));
        }
        return AcceptResult.ok();
    }

    public synchronized boolean explainsFlag(String checkName, Instant now) {
        if (lastAcceptedAt == null || Duration.between(lastAcceptedAt, now).compareTo(EXPLANATION_WINDOW) > 0) {
            return false;
        }
        Instant windowStart = now.minus(EXPLANATION_WINDOW);
        boolean sawMovement = false;
        boolean sawRotation = false;
        boolean sawAttackOrUse = false;
        boolean sawPlausibleSample = false;

        for (RecordedSample rs : buffer) {
            if (rs.receivedAt().isBefore(windowStart)) {
                continue;
            }
            ProtocolMessages.AcInputSample s = rs.sample();
            if (!Float.isFinite(s.yawDelta()) || !Float.isFinite(s.pitchDelta())) {
                return false;
            }
            if (Math.abs(s.pitchDelta()) > 180.0f) {
                return false;
            }
            sawPlausibleSample = true;
            if ((s.flags() & (ProtocolMessages.FLAG_FWD | ProtocolMessages.FLAG_BACK
                    | ProtocolMessages.FLAG_LEFT | ProtocolMessages.FLAG_RIGHT
                    | ProtocolMessages.FLAG_JUMP | ProtocolMessages.FLAG_SPRINT
                    | ProtocolMessages.FLAG_SNEAK)) != 0) {
                sawMovement = true;
            }
            if (Math.abs(s.yawDelta()) > 0.001f || Math.abs(s.pitchDelta()) > 0.001f) {
                sawRotation = true;
            }
            if ((s.flags() & (ProtocolMessages.FLAG_ATTACK | ProtocolMessages.FLAG_USE)) != 0) {
                sawAttackOrUse = true;
            }
        }

        if (!sawPlausibleSample) {
            return false;
        }
        String lower = checkName == null ? "" : checkName.toLowerCase(Locale.ROOT);
        if (lower.contains("aim") || lower.contains("rotation")) {
            return sawRotation;
        }
        if (lower.contains("click") || lower.contains("killaura") || lower.contains("combat")) {
            return sawAttackOrUse;
        }
        if (lower.contains("simulation") || lower.contains("strafe") || lower.contains("move") || lower.contains("ground")) {
            return sawMovement || sawPlausibleSample;
        }
        return sawPlausibleSample;
    }

    public synchronized int sampleCount() {
        return buffer.size();
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }
}

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

import dev.haikyo.core.util.Hex;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stateful per-connection fragment reassembler enforcing all v1 protocol safety bounds:
 * out-of-order acceptance, identical duplicate suppression, conflicting duplicate rejection,
 * SHA-256 verification, max 64 fragments, max 8 concurrent pending {@code frag_id}s,
 * and max 2 MiB reassembled payload size.
 */
public final class FragmentReassembler {
    private static final Duration PENDING_TTL = Duration.ofSeconds(30);

    public static final class CompletePacket {
        private final int protocolVersion;
        private final int packetType;
        private final byte[] body;

        public CompletePacket(int protocolVersion, int packetType, byte[] body) {
            this.protocolVersion = protocolVersion;
            this.packetType = packetType;
            this.body = body;
        }

        public int protocolVersion() { return protocolVersion; }
        public int packetType() { return packetType; }
        public byte[] body() { return body; }
    }

    public interface Result {
    }

    public static final class Complete implements Result {
        private final CompletePacket packet;

        public Complete(CompletePacket packet) {
            this.packet = packet;
        }

        public CompletePacket packet() { return packet; }
    }

    public static final class Pending implements Result {
        private final int fragId;
        private final int receivedCount;
        private final int totalCount;

        public Pending(int fragId, int receivedCount, int totalCount) {
            this.fragId = fragId;
            this.receivedCount = receivedCount;
            this.totalCount = totalCount;
        }

        public int fragId() { return fragId; }
        public int receivedCount() { return receivedCount; }
        public int totalCount() { return totalCount; }
    }

    public static final class IgnoredHigherVersion implements Result {
        private final int protocolVersion;

        public IgnoredHigherVersion(int protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public int protocolVersion() { return protocolVersion; }
    }

    public static final class Rejected implements Result {
        private final String reason;

        public Rejected(String reason) {
            this.reason = reason;
        }

        public String reason() { return reason; }
    }

    private final Map<Integer, PendingAssembly> pending = new HashMap<Integer, PendingAssembly>();

    public synchronized Result accept(byte[] wireBytes, int maxWireBytes, Instant now) {
        pruneExpired(now);
        WireFrame.DecodeResult decoded = WireFrame.decode(wireBytes, maxWireBytes);
        if (decoded instanceof WireFrame.DecodeHigherVersion) {
            return new IgnoredHigherVersion(((WireFrame.DecodeHigherVersion) decoded).protocolVersion());
        }
        if (decoded instanceof WireFrame.DecodeInvalid) {
            return new Rejected(((WireFrame.DecodeInvalid) decoded).reason());
        }
        WireFrame.DecodedFrame frame = ((WireFrame.DecodeOk) decoded).frame();
        if (!frame.fragmented()) {
            if (frame.payload().length > HaikyoChannels.MAX_REASSEMBLED_BYTES) {
                return new Rejected("payload exceeds max reassembled bytes");
            }
            return new Complete(new CompletePacket(frame.protocolVersion(), frame.packetType(), frame.payload()));
        }

        int fragId = frame.fragId();
        PendingAssembly asm = pending.get(fragId);
        if (asm == null) {
            if (pending.size() >= HaikyoChannels.MAX_PENDING_FRAG_IDS) {
                return new Rejected("too many concurrent pending fragment assemblies");
            }
            asm = new PendingAssembly(
                    frame.protocolVersion(),
                    frame.packetType(),
                    frame.fragTotal(),
                    frame.payloadSha256(),
                    now
            );
            pending.put(fragId, asm);
        } else {
            if (asm.packetType != frame.packetType()
                    || asm.total != frame.fragTotal()
                    || !Arrays.equals(asm.expectedSha256, frame.payloadSha256())) {
                pending.remove(fragId);
                return new Rejected("fragment metadata mismatch for frag_id=" + fragId);
            }
        }

        byte[] existing = asm.chunks[frame.fragIndex()];
        if (existing != null) {
            if (Arrays.equals(existing, frame.payload())) {
                return new Pending(fragId, asm.receivedCount, asm.total);
            }
            pending.remove(fragId);
            return new Rejected("conflicting duplicate fragment for frag_id=" + fragId + " idx=" + frame.fragIndex());
        }

        if (asm.totalBytes + frame.payload().length > HaikyoChannels.MAX_REASSEMBLED_BYTES) {
            pending.remove(fragId);
            return new Rejected("reassembled payload exceeds limit");
        }

        asm.chunks[frame.fragIndex()] = frame.payload();
        asm.receivedCount++;
        asm.totalBytes += frame.payload().length;

        if (asm.receivedCount < asm.total) {
            return new Pending(fragId, asm.receivedCount, asm.total);
        }

        pending.remove(fragId);
        byte[] assembled = new byte[asm.totalBytes];
        int offset = 0;
        for (int i = 0; i < asm.total; i++) {
            byte[] c = asm.chunks[i];
            System.arraycopy(c, 0, assembled, offset, c.length);
            offset += c.length;
        }

        byte[] actualSha256 = WireFrame.sha256(assembled);
        if (!Hex.constantTimeEquals(actualSha256, asm.expectedSha256)) {
            return new Rejected("reassembled SHA-256 mismatch");
        }
        return new Complete(new CompletePacket(asm.protocolVersion, asm.packetType, assembled));
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }

    private void pruneExpired(Instant now) {
        if (now == null || pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, PendingAssembly>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            PendingAssembly asm = it.next().getValue();
            if (Duration.between(asm.createdAt, now).compareTo(PENDING_TTL) > 0) {
                it.remove();
            }
        }
    }

    private static final class PendingAssembly {
        final int protocolVersion;
        final int packetType;
        final int total;
        final byte[] expectedSha256;
        final Instant createdAt;
        final byte[][] chunks;
        int receivedCount;
        int totalBytes;

        PendingAssembly(int protocolVersion, int packetType, int total, byte[] expectedSha256, Instant createdAt) {
            this.protocolVersion = protocolVersion;
            this.packetType = packetType;
            this.total = total;
            this.expectedSha256 = expectedSha256;
            this.createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            this.chunks = new byte[total][];
        }
    }
}

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
package dev.haikyo.core.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tracks in-flight {@code haikyo:admin} requests ({@code info}, {@code hwid}, {@code screenshot})
 * and validates that incoming responses match both {@code request_id} and {@code targetUuid}.
 */
public final class AdminRequestManager {
    private static final Duration REQUEST_TTL = Duration.ofSeconds(30);

    public record PendingRequest(
            String requestId,
            String kind,
            UUID targetUuid,
            String targetName,
            String actorName,
            Instant createdAt,
            Consumer<Object> callback
    ) {
    }

    private final Clock clock;
    private final AtomicLong seq = new AtomicLong(1);
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    public AdminRequestManager(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public PendingRequest createRequest(
            String kind,
            UUID targetUuid,
            String targetName,
            String actorName,
            Consumer<Object> callback
    ) {
        pruneExpired();
        String id = "req-" + seq.getAndIncrement() + "-" + Long.toHexString(clock.instant().toEpochMilli());
        PendingRequest req = new PendingRequest(id, kind, targetUuid, targetName, actorName, clock.instant(), callback);
        pending.put(id, req);
        return req;
    }

    /**
     * Completes and removes a pending request only if {@code requestId} matches {@code senderUuid}.
     */
    public Optional<PendingRequest> complete(String requestId, UUID senderUuid, String expectedKind) {
        if (requestId == null || requestId.isBlank() || senderUuid == null) {
            return Optional.empty();
        }
        PendingRequest req = pending.get(requestId);
        if (req == null) {
            return Optional.empty();
        }
        if (!senderUuid.equals(req.targetUuid())) {
            return Optional.empty();
        }
        if (expectedKind != null && !expectedKind.equals(req.kind())) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.remove(requestId));
    }

    public void pruneExpired() {
        Instant cutoff = clock.instant().minus(REQUEST_TTL);
        Iterator<Map.Entry<String, PendingRequest>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().createdAt().isBefore(cutoff)) {
                it.remove();
            }
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}

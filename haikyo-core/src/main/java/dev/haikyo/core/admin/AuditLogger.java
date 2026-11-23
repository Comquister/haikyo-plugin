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

import dev.haikyo.core.util.Java8Compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Append-only audit log ({@code audit.log}) for administrative commands ({@code info}, {@code hwid},
 * {@code screenshot}, {@code voice}). Records ONLY who, whom, when, and action metadata —
 * NEVER stores screenshot image bytes — and prunes entries older than the configured retention period
 * (default 30 days) for LGPD/GDPR data minimization.
 */
public final class AuditLogger {
    private final Path logFile;
    private final Clock clock;
    private volatile int retentionDays;

    public AuditLogger(Path logFile, int retentionDays, Clock clock) {
        this.logFile = logFile;
        this.retentionDays = Math.max(1, retentionDays);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(1, retentionDays);
    }

    public int retentionDays() {
        return retentionDays;
    }

    public synchronized void record(String actor, UUID targetUuid, String targetName, String action, String detail) {
        if (logFile == null) {
            return;
        }
        String ts = clock.instant().toString();
        String line = ts
                + " | actor=" + sanitize(actor)
                + " | target=" + sanitize(targetName) + "(" + (targetUuid == null ? "-" : targetUuid) + ")"
                + " | action=" + sanitize(action)
                + (Java8Compat.isBlank(detail) ? "" : " | detail=" + sanitize(detail))
                + "\n";
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Java8Compat.writeString(
                    logFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    /**
     * Removes lines older than {@code retentionDays} from {@code audit.log}.
     *
     * @return number of pruned lines
     */
    public synchronized int pruneExpired() {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<String>(lines.size());
            int pruned = 0;
            for (String line : lines) {
                int pipe = line.indexOf(" | ");
                if (pipe > 0) {
                    try {
                        Instant ts = Instant.parse(line.substring(0, pipe).trim());
                        if (ts.isBefore(cutoff)) {
                            pruned++;
                            continue;
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
                kept.add(line);
            }
            if (pruned > 0) {
                Path tmp = logFile.resolveSibling(logFile.getFileName().toString() + ".tmp");
                String content = kept.isEmpty() ? "" : String.join("\n", kept) + "\n";
                Java8Compat.writeString(tmp, content, StandardCharsets.UTF_8);
                Files.move(tmp, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return pruned;
        } catch (IOException e) {
            return 0;
        }
    }

    public synchronized List<String> readAllLines() throws IOException {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return Collections.emptyList();
        }
        return Files.readAllLines(logFile, StandardCharsets.UTF_8);
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "-";
        }
        return s.replace('\r', ' ').replace('\n', ' ').replace('|', '/').trim();
    }
}

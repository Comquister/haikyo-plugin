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
package dev.haikyo.core.store;

import dev.haikyo.core.util.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe persistent store for {@code requires-haikyo.json}: players promoted from
 * suspect/anticheat flags who must join with an attested Haikyo client until pardoned.
 */
public final class RequiresHaikyoStore {

    public record Entry(
            UUID playerId,
            String playerName,
            String reason,
            Instant addedAt,
            String addedBy
    ) {
    }

    private final Path filePath;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public RequiresHaikyoStore(Path filePath) {
        this.filePath = filePath;
    }

    public synchronized void load() throws IOException {
        entries.clear();
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        String raw = Files.readString(filePath, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return;
        }
        Map<String, Object> root = JsonCodec.parseObject(raw);
        Map<String, Object> players = JsonCodec.asObject(root, "players");
        for (Map.Entry<String, Object> e : players.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> rawEntry)) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(e.getKey());
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rawEntry;
                String name = JsonCodec.asString(m, "name", "");
                String reason = JsonCodec.asString(m, "reason", "");
                long addedAtEpoch = JsonCodec.asLong(m, "added_at", 0L);
                String addedBy = JsonCodec.asString(m, "added_by", "system");
                entries.put(uuid, new Entry(uuid, name, reason, Instant.ofEpochSecond(addedAtEpoch), addedBy));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public synchronized void save() throws IOException {
        if (filePath == null) {
            return;
        }
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Object> players = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("name", e.playerName());
            obj.put("reason", e.reason());
            obj.put("added_at", e.addedAt().getEpochSecond());
            obj.put("added_by", e.addedBy());
            players.put(e.playerId().toString(), obj);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1);
        root.put("players", players);
        String json = JsonCodec.stringifyPretty(root);

        Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean isRequired(UUID playerId) {
        return playerId != null && entries.containsKey(playerId);
    }

    public Optional<Entry> get(UUID playerId) {
        return Optional.ofNullable(entries.get(playerId));
    }

    public Entry requirePlayer(UUID playerId, String playerName, String reason, String addedBy, Instant now) {
        Entry entry = new Entry(
                playerId,
                playerName == null ? "" : playerName,
                reason == null ? "" : reason,
                now == null ? Instant.now() : now,
                addedBy == null ? "system" : addedBy
        );
        entries.put(playerId, entry);
        saveQuietly();
        return entry;
    }

    public boolean pardon(UUID playerId) {
        Entry removed = entries.remove(playerId);
        if (removed != null) {
            saveQuietly();
            return true;
        }
        return false;
    }

    public Optional<Entry> pardonByNameOrUuid(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String trimmed = target.trim();
        try {
            UUID uuid = UUID.fromString(trimmed);
            Entry removed = entries.remove(uuid);
            if (removed != null) {
                saveQuietly();
                return Optional.of(removed);
            }
        } catch (IllegalArgumentException ignored) {
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Entry e : entries.values()) {
            if (e.playerName().toLowerCase(Locale.ROOT).equals(lower)) {
                entries.remove(e.playerId());
                saveQuietly();
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) {
        }
    }
}

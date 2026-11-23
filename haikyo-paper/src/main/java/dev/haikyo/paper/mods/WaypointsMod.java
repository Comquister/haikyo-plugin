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
package dev.haikyo.paper.mods;

import dev.haikyo.api.HaikyoApi;
import dev.haikyo.core.config.HaikyoConfig;
import dev.haikyo.core.util.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Example optional server-enhanced mod registered via {@link HaikyoApi#registerModEndpoint}:
 * serves configured server waypoints over {@code haikyo:mod/waypoints} ({@code waypoints@1}).
 */
public final class WaypointsMod {
    public static final String MOD_ID = "waypoints";
    public static final String VERSION = "1";

    private final HaikyoApi api;
    private volatile List<HaikyoConfig.WaypointMarker> markers;
    private volatile boolean registered;

    public WaypointsMod(HaikyoApi api, List<HaikyoConfig.WaypointMarker> initialMarkers) {
        this.api = api;
        this.markers = initialMarkers == null ? List.of() : List.copyOf(initialMarkers);
    }

    public void enable(List<HaikyoConfig.WaypointMarker> newMarkers) {
        this.markers = newMarkers == null ? List.of() : List.copyOf(newMarkers);
        if (!registered) {
            api.registerModEndpoint(MOD_ID, VERSION, this::onClientMessage);
            registered = true;
        }
    }

    public void disable() {
        if (registered) {
            api.unregisterModEndpoint(MOD_ID);
            registered = false;
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean sendSync(UUID playerId) {
        List<Map<String, Object>> markerMaps = new ArrayList<>();
        for (HaikyoConfig.WaypointMarker m : markers) {
            markerMaps.add(m.toJsonMap());
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "sync");
        msg.put("markers", markerMaps);
        return api.sendToPlayer(playerId, MOD_ID, JsonCodec.stringify(msg).getBytes(StandardCharsets.UTF_8));
    }

    public boolean sendUpsert(UUID playerId, HaikyoConfig.WaypointMarker marker) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "upsert");
        msg.put("marker", marker.toJsonMap());
        return api.sendToPlayer(playerId, MOD_ID, JsonCodec.stringify(msg).getBytes(StandardCharsets.UTF_8));
    }

    public boolean sendRemove(UUID playerId, String markerId) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "remove");
        msg.put("id", markerId);
        return api.sendToPlayer(playerId, MOD_ID, JsonCodec.stringify(msg).getBytes(StandardCharsets.UTF_8));
    }

    public boolean sendClear(UUID playerId) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("op", "clear");
        return api.sendToPlayer(playerId, MOD_ID, JsonCodec.stringify(msg).getBytes(StandardCharsets.UTF_8));
    }

    private void onClientMessage(UUID playerId, String modId, byte[] payload) {
        try {
            Map<String, Object> obj = JsonCodec.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = JsonCodec.asString(obj, "op", "");
            if ("request_sync".equals(op)) {
                sendSync(playerId);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }
}

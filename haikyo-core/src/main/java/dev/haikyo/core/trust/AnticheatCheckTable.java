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
package dev.haikyo.core.trust;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-anticheat check classification table ({@code always} vs {@code relaxed}).
 *
 * <p>Fail-safe default: any check not explicitly configured as {@code relaxed} evaluates
 * to {@link CheckPolicy#ALWAYS} so cheap/core checks (speed, reach, impossible actions)
 * can never be accidentally bypassed.</p>
 */
public final class AnticheatCheckTable {
    private final Map<String, Map<String, CheckPolicy>> tables;

    public AnticheatCheckTable(Map<String, Map<String, CheckPolicy>> tables) {
        Map<String, Map<String, CheckPolicy>> normalized = new LinkedHashMap<>();
        if (tables != null) {
            for (Map.Entry<String, Map<String, CheckPolicy>> acEntry : tables.entrySet()) {
                String acKey = acEntry.getKey().toLowerCase(Locale.ROOT);
                Map<String, CheckPolicy> checks = new LinkedHashMap<>();
                if (acEntry.getValue() != null) {
                    for (Map.Entry<String, CheckPolicy> checkEntry : acEntry.getValue().entrySet()) {
                        checks.put(checkEntry.getKey().toLowerCase(Locale.ROOT), checkEntry.getValue());
                    }
                }
                normalized.put(acKey, Collections.unmodifiableMap(checks));
            }
        }
        this.tables = Collections.unmodifiableMap(normalized);
    }

    /**
     * Resolves policy for a check name under the given anticheat ({@code "grim"}, {@code "vulcan"}, {@code "generic"}).
     * Supports exact match (case-insensitive) and wildcard suffix ({@code "aim*"}).
     */
    public CheckPolicy policyFor(String anticheatId, String checkName) {
        if (anticheatId == null || checkName == null || checkName.isBlank()) {
            return CheckPolicy.ALWAYS;
        }
        Map<String, CheckPolicy> acMap = tables.get(anticheatId.toLowerCase(Locale.ROOT));
        if (acMap == null || acMap.isEmpty()) {
            return CheckPolicy.ALWAYS;
        }
        String normCheck = checkName.trim().toLowerCase(Locale.ROOT);
        CheckPolicy exact = acMap.get(normCheck);
        if (exact != null) {
            return exact;
        }
        // Check prefix wildcards such as "aim*" or "badpackets*"
        for (Map.Entry<String, CheckPolicy> entry : acMap.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (normCheck.startsWith(prefix)) {
                    return entry.getValue();
                }
            }
        }
        return CheckPolicy.ALWAYS;
    }

    public static AnticheatCheckTable defaults() {
        Map<String, Map<String, CheckPolicy>> map = new LinkedHashMap<>();

        Map<String, CheckPolicy> grim = new LinkedHashMap<>();
        grim.put("simulation", CheckPolicy.RELAXED);
        grim.put("aim*", CheckPolicy.RELAXED);
        grim.put("post*", CheckPolicy.RELAXED);
        grim.put("groundspoof", CheckPolicy.RELAXED);
        grim.put("reach", CheckPolicy.ALWAYS);
        grim.put("timer", CheckPolicy.ALWAYS);
        grim.put("badpackets*", CheckPolicy.ALWAYS);
        grim.put("fly*", CheckPolicy.ALWAYS);
        grim.put("speed*", CheckPolicy.ALWAYS);
        map.put("grim", grim);

        Map<String, CheckPolicy> vulcan = new LinkedHashMap<>();
        vulcan.put("aim*", CheckPolicy.RELAXED);
        vulcan.put("strafe*", CheckPolicy.RELAXED);
        vulcan.put("groundspoof*", CheckPolicy.RELAXED);
        vulcan.put("reach*", CheckPolicy.ALWAYS);
        vulcan.put("flight*", CheckPolicy.ALWAYS);
        vulcan.put("speed*", CheckPolicy.ALWAYS);
        vulcan.put("timer*", CheckPolicy.ALWAYS);
        vulcan.put("badpackets*", CheckPolicy.ALWAYS);
        map.put("vulcan", vulcan);

        Map<String, CheckPolicy> generic = new LinkedHashMap<>();
        generic.put("aim*", CheckPolicy.RELAXED);
        generic.put("simulation*", CheckPolicy.RELAXED);
        generic.put("reach*", CheckPolicy.ALWAYS);
        generic.put("fly*", CheckPolicy.ALWAYS);
        generic.put("speed*", CheckPolicy.ALWAYS);
        map.put("generic", generic);

        return new AnticheatCheckTable(map);
    }
}

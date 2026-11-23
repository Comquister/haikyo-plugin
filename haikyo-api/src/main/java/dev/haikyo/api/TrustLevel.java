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
package dev.haikyo.api;

/**
 * Trust level assigned by Haikyo to an online player session.
 */
public enum TrustLevel {
    /** Not using Haikyo, or attestation not completed / invalid / expired. Full anticheat checks apply. */
    NONE(0),
    /** Valid attestation and clean integrity reports. Relaxed anticheat checks may apply unless sampled. */
    ATTESTED(1),
    /** Client signal, mod-rule violation, missing report, or always-check flag. Full checks + heightened watch. */
    SUSPECT(2);

    private final int wireId;

    TrustLevel(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static TrustLevel fromWireId(int id) {
        return switch (id) {
            case 0 -> NONE;
            case 1 -> ATTESTED;
            case 2 -> SUSPECT;
            default -> throw new IllegalArgumentException("Unknown TrustLevel wire id: " + id);
        };
    }

    public static TrustLevel fromWireName(String name) {
        if (name == null) {
            return NONE;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "attested" -> ATTESTED;
            case "suspect" -> SUSPECT;
            default -> NONE;
        };
    }

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

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

import java.util.List;

/**
 * Locked v1 channel names, wire limits, and packet type IDs for the {@code haikyo:*} protocol.
 */
public final class HaikyoChannels {
    public static final int PROTOCOL_VERSION = 1;

    public static final String CONTROL = "haikyo:control";
    public static final String ATTEST = "haikyo:attest";
    public static final String MODS = "haikyo:mods";
    public static final String MOD_PREFIX = "haikyo:mod/";
    public static final String AC = "haikyo:ac";
    public static final String ADMIN = "haikyo:admin";
    public static final String VOICE = "haikyo:voice";
    public static final String LINK = "haikyo:link";

    public static final List<String> CORE_CHANNELS = List.of(CONTROL, ATTEST, MODS, AC, ADMIN, VOICE);

    // Wire framing constants
    public static final byte MAGIC_0 = 'H';
    public static final byte MAGIC_1 = 'K';
    public static final int UNFRAGMENTED_HEADER_BYTES = 6;
    public static final int FRAGMENTED_HEADER_BYTES = 46;
    public static final int FLAG_UNFRAGMENTED = 0x00;
    public static final int FLAG_FRAGMENTED = 0x01;

    // Wire & reassembly limits
    public static final int MAX_C2S_WIRE_BYTES = 32_767;
    public static final int MAX_S2C_WIRE_BYTES = 1_048_576;
    public static final int MAX_REASSEMBLED_BYTES = 2_097_152;
    public static final int MAX_FRAGMENTS = 64;
    public static final int MAX_PENDING_FRAG_IDS = 8;

    // haikyo:control packet types
    public static final int PKT_CONTROL_HELLO = 0x01;
    public static final int PKT_CONTROL_HELLO_ACK = 0x02;

    // haikyo:attest packet types
    public static final int PKT_ATTEST_CHALLENGE = 0x01;
    public static final int PKT_ATTEST_RESPONSE = 0x02;
    public static final int PKT_ATTEST_RESULT = 0x03;
    public static final int PKT_ATTEST_INTEGRITY_REPORT = 0x04;

    // haikyo:mods packet types
    public static final int PKT_MODS_RULES = 0x01;

    // haikyo:mod/<id> packet types
    public static final int PKT_MOD_OPAQUE = 0x01;

    // haikyo:ac packet types
    public static final int PKT_AC_INPUTS = 0x01;

    // haikyo:admin packet types
    public static final int PKT_ADMIN_REQUEST = 0x01;
    public static final int PKT_ADMIN_INFO_RESPONSE = 0x02;
    public static final int PKT_ADMIN_HWID_RESPONSE = 0x03;
    public static final int PKT_ADMIN_SCREENSHOT_RESPONSE = 0x04;

    // haikyo:voice packet types
    public static final int PKT_VOICE_CONFIG = 0x01;

    // haikyo:link packet types (proxy <-> backend)
    public static final int PKT_LINK_SESSION = 0x01;
    public static final int PKT_LINK_REQUEST = 0x02;

    private HaikyoChannels() {
    }

    public static String modChannel(String modId) {
        if (modId == null || !modId.matches("[a-z0-9_.-]{1,48}")) {
            throw new IllegalArgumentException("Invalid mod id: " + modId);
        }
        return MOD_PREFIX + modId;
    }

    public static String extractModId(String channel) {
        if (channel == null || !channel.startsWith(MOD_PREFIX)) {
            return null;
        }
        String id = channel.substring(MOD_PREFIX.length());
        return id.matches("[a-z0-9_.-]{1,48}") ? id : null;
    }
}

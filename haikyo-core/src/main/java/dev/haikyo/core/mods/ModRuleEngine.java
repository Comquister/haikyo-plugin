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
package dev.haikyo.core.mods;

import dev.haikyo.core.protocol.HaikyoChannels;
import dev.haikyo.core.protocol.ProtocolMessages;
import dev.haikyo.core.protocol.WireFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates server-configured {@code mods.rules} against client integrity reports.
 * Violations emit structured signals so {@link dev.haikyo.core.trust.TrustManager}
 * transitions the player to {@link dev.haikyo.api.TrustLevel#SUSPECT}.
 */
public final class ModRuleEngine {
    private static final double EPSILON = 1e-6;

    public record ModViolation(String modId, String code, String detail) {
    }

    private final Map<String, ModRule> rules;

    public ModRuleEngine(Map<String, ModRule> rules) {
        this.rules = rules == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    public Map<String, ModRule> rules() {
        return rules;
    }

    public byte[] encodeRulesFrame() {
        byte[] body = new ProtocolMessages.ModsRulesMessage(rules).encodeJson();
        return WireFrame.encodeUnfragmented(HaikyoChannels.PKT_MODS_RULES, body);
    }

    /**
     * Checks a client's reported mod states against all configured {@code mods.rules}.
     */
    public List<ModViolation> verifyCompliance(Map<String, ProtocolMessages.ReportedModState> reportedMods) {
        if (rules.isEmpty() || reportedMods == null || reportedMods.isEmpty()) {
            return List.of();
        }
        List<ModViolation> violations = new ArrayList<>();
        for (Map.Entry<String, ModRule> entry : rules.entrySet()) {
            String modId = entry.getKey();
            ModRule rule = entry.getValue();
            ProtocolMessages.ReportedModState state = reportedMods.get(modId);
            if (state == null || !state.enabled()) {
                continue;
            }
            if (rule.action() == ModRule.Action.BLOCK) {
                violations.add(new ModViolation(
                        modId,
                        "mod_rule_violation:" + modId,
                        "Blocked mod '" + modId + "' reported enabled"
                ));
                continue;
            }
            if (rule.action() == ModRule.Action.LIMIT) {
                for (Map.Entry<String, Object> limitEntry : rule.params().entrySet()) {
                    String paramKey = limitEntry.getKey();
                    Object limitVal = limitEntry.getValue();
                    Object reportedVal = state.params().get(paramKey);
                    if (reportedVal == null) {
                        continue;
                    }
                    String err = checkParamLimit(paramKey, limitVal, reportedVal);
                    if (err != null) {
                        violations.add(new ModViolation(
                                modId,
                                "mod_rule_violation:" + modId + ":" + paramKey,
                                err
                        ));
                    }
                }
            }
        }
        return violations;
    }

    private static String checkParamLimit(String key, Object limitVal, Object reportedVal) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (limitVal instanceof Number limNum && reportedVal instanceof Number repNum) {
            double lim = limNum.doubleValue();
            double rep = repNum.doubleValue();
            if (lower.startsWith("min_")) {
                if (rep + EPSILON < lim) {
                    return "Param '" + key + "' value " + rep + " is below server minimum " + lim;
                }
                return null;
            }
            // Default numeric limit (including max_* such as max_factor, max_zoom): upper bound
            if (rep - EPSILON > lim) {
                return "Param '" + key + "' value " + rep + " exceeds server limit " + lim;
            }
            return null;
        }
        if (limitVal instanceof Boolean limBool && reportedVal instanceof Boolean repBool) {
            if (!limBool && repBool) {
                return "Boolean param '" + key + "' enabled despite server limit false";
            }
            return null;
        }
        if (limitVal != null && !limitVal.toString().equals(reportedVal.toString())) {
            return "Param '" + key + "' value '" + reportedVal + "' does not match required '" + limitVal + "'";
        }
        return null;
    }
}

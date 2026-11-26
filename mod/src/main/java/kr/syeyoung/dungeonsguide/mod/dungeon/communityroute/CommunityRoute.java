/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026 Modern Dungeons Guide contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import java.util.List;

/**
 * An ordered route through one room. roomKey is the source file's room name
 * (e.g. SRM "Grand-Library-4", Odin "Grand Library"); variant distinguishes
 * alternate routes for the same room (SRM "Room-N:1" -> variant 1).
 */
public final class CommunityRoute {
    public final String roomKey;
    public final int variant;
    public final List<RouteStep> steps;

    public CommunityRoute(String roomKey, int variant, List<RouteStep> steps) {
        this.roomKey = roomKey;
        this.variant = variant;
        this.steps = steps;
    }

    /** all authored secret locations of this route; used for room fingerprinting */
    public void collectSecrets(List<RouteVec3> out) {
        for (RouteStep step : steps) {
            if (step.secret != null && step.secret.pos != null) {
                out.add(step.secret.pos);
            }
        }
    }

    /**
     * Secrets whose type corresponds to a DG mechanic (item->itemdrop,
     * interact->lever/chest, bat->bat). "exit"/"exitroute"/"prince"/"dummy"
     * have no DG counterpart and would cap the achievable match rate, so
     * they're excluded from fingerprint fitting.
     */
    public void collectMatchableSecrets(List<RouteStep.Secret> out) {
        for (RouteStep step : steps) {
            if (step.secret != null && step.secret.pos != null && isMatchableType(step.secret.type)) {
                out.add(step.secret);
            }
        }
    }

    static boolean isMatchableType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("item") || t.contains("interact") || t.contains("bat")
                || t.contains("chest") || t.contains("essence") || t.contains("lever");
    }
}

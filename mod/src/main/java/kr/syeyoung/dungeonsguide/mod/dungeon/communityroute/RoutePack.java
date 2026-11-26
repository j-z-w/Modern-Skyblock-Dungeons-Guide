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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One parsed route file: provenance + room-keyed routes.
 * No community data is bundled in the jar; packs are parsed at runtime from
 * the user's own installed mod config files (AD10).
 */
public final class RoutePack {
    public enum Format { SRM, ODIN, DG }

    /** stable id: "srm:fowroutes", "odin:Imported", "dg:custom/<file>" */
    public final String id;
    public final Format format;
    /** file name relative to its routes dir, for diagnostics */
    public final String fileName;
    /** provenance fields preserved from the file (SRM #origin / #copyright) */
    public final String origin;
    public final String copyright;

    private final Map<String, List<CommunityRoute>> byRoom = new LinkedHashMap<>();

    public RoutePack(String id, Format format, String fileName, String origin, String copyright) {
        this.id = id;
        this.format = format;
        this.fileName = fileName;
        this.origin = origin;
        this.copyright = copyright;
    }

    public void addRoute(CommunityRoute route) {
        byRoom.computeIfAbsent(route.roomKey, k -> new ArrayList<>()).add(route);
    }

    /** variants sorted by variant index; base (no ':n') is variant 0 */
    public List<CommunityRoute> routesFor(String roomKey) {
        List<CommunityRoute> routes = byRoom.get(roomKey);
        if (routes == null || routes.size() < 2) return routes;
        List<CommunityRoute> sorted = new ArrayList<>(routes);
        sorted.sort((a, b) -> Integer.compare(a.variant, b.variant));
        return sorted;
    }

    public Map<String, List<CommunityRoute>> allRooms() {
        return byRoom;
    }

    public int roomCount() {
        return byRoom.size();
    }
}

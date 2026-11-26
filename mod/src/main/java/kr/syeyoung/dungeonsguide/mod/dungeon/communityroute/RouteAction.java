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

/**
 * A single authored action inside a route step. Positions are in the source
 * route file's room-relative frame until transformed by CanonicalTransform.
 */
public final class RouteAction {
    public enum Kind {
        /** walk breadcrumb (SRM 'locations') */
        WALK,
        /** etherwarp target block (SRM 'etherwarps') */
        ETHERWARP,
        /** Dungeon Breaker block to mine (SRM 'mines'), any order within the step */
        BREAKER,
        /** superboom / TNT spot (SRM 'tnts') */
        SUPERBOOM,
        /** ender pearl spot; carries yaw/pitch when authored (SRM enderpearls+angles) */
        PEARL,
        /** right-click interact (lever / door / device) */
        INTERACT,
        /** bonzo staff jump spot */
        BONZO,
        /** untyped marker (Odin waypoints that do not classify) */
        MARKER
    }

    public final Kind kind;
    public final RouteVec3 pos;
    public final String label;
    /** look direction for PEARL, degrees; Float.NaN when not authored */
    public final float yaw;
    public final float pitch;

    public RouteAction(Kind kind, RouteVec3 pos, String label, float yaw, float pitch) {
        this.kind = kind;
        this.pos = pos;
        this.label = label;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public RouteAction(Kind kind, RouteVec3 pos) {
        this(kind, pos, null, Float.NaN, Float.NaN);
    }
}

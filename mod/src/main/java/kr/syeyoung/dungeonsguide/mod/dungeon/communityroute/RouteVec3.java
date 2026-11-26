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
 * Minecraft-free room-relative position used by community route data.
 * SRM and Odin both store coordinates relative to the room's clay-corner
 * anchor in the room's canonical orientation.
 */
public final class RouteVec3 {
    public final double x;
    public final double y;
    public final double z;

    public RouteVec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public double distSq(RouteVec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistSq(RouteVec3 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    public boolean isWithin(RouteVec3 other, double radius) {
        return distSq(other) <= radius * radius;
    }

    /** Packs floor(x),floor(y),floor(z) into a long; only valid inside a room (all coords small positive or small negative). */
    public long blockKey() {
        return blockKey(blockX(), blockY(), blockZ());
    }

    public static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 21) | (y & 0x1FFFFF);
    }

    public static int keyX(long key) {
        int v = (int) (key >> 42) & 0x3FFFFF;
        return (v & 0x200000) != 0 ? v | ~0x3FFFFF : v;
    }

    public static int keyY(long key) {
        int v = (int) key & 0x1FFFFF;
        return (v & 0x100000) != 0 ? v | ~0x1FFFFF : v;
    }

    public static int keyZ(long key) {
        int v = (int) (key >> 21) & 0x3FFFFF;
        return (v & 0x200000) != 0 ? v | ~0x3FFFFF : v;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}

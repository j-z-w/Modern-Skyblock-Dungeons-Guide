/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2024  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.pathfinding.live;

import kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap.CollisionState;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.List;

/**
 * AD8: bounded forward A* over DG's own collision-state world - the doubled
 * (0.5 block) node grid {@link IPathfindWorld#getBlock} exposes, which is the
 * same walkability model every shipped precalculation was generated from.
 *
 * Walk / jump / fall only: a cell is enterable iff its CollisionState is
 * canGo and not blocked (ONAIR, ONGROUND, STAIR, ENDERCHEST, SUPERBOOMABLE_*).
 * No stonk, etherwarp, or pearl moves. The move set (6 axis directions in
 * half-block cells) and per-step costs (1 for ground/up moves, 2 for air
 * moves, 10 for leaving a superboom volume) mirror the non-blocked branch of
 * FineGridStonkingBFS so live costs stay comparable to precalc gScores.
 *
 * Search shape follows Waypointer's GroundPathfinder (GPL-3, babbur/waypointer):
 * a box bounded around start+goal and a hard expansion cap. Everything here
 * runs on a background worker; LivePathfinder handles scheduling and publish.
 */
public final class LiveAStar {
    public static final int DEFAULT_MAX_EXPANSIONS = 8000;
    // doubled cells; a path may legitimately detour a little past the start/goal rect
    private static final int HORIZONTAL_PAD = 16; // 8 blocks
    private static final int VERTICAL_PAD = 64;   // 32 blocks

    private static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {1, -1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private LiveAStar() {}

    public enum Status {
        FOUND, NO_PASSABLE_START, NO_PASSABLE_GOAL, EXPANSION_LIMIT, NO_ROUTE
    }

    public static final class Result {
        public Status status;
        public int expansions;
        public double cost;
        /** packed cells, start -> goal; empty unless status == FOUND */
        public long[] cells = new long[0];
        /** parallel to cells; PathfindResult.NodeType ordinals */
        public byte[] types = new byte[0];
        public long nanos;
    }

    // x,z get 21 bits (+/- 1M doubled cells = +/- 500k blocks), y 21 bits
    private static final long XZ_OFF = 1 << 20;
    public static long pack(int x, int y, int z) {
        return ((x + XZ_OFF) << 42) | ((z + XZ_OFF) << 21) | (y + XZ_OFF);
    }
    public static int unpackX(long cell) { return (int) (cell >>> 42) - (int) XZ_OFF; }
    public static int unpackY(long cell) { return (int) (cell & ((1L << 21) - 1)) - (int) XZ_OFF; }
    public static int unpackZ(long cell) { return (int) ((cell >>> 21) & ((1L << 21) - 1)) - (int) XZ_OFF; }

    public static boolean walkable(IPathfindWorld world, int x, int y, int z) {
        CollisionState s = world.getBlock(x, y, z);
        // canGo && !blocked: STONKING*/BLOCKED* are blocked and stay off
        // limits - the live path is walk/jump/fall only, no stonk
        return s.isCanGo() && !s.isBlocked();
    }
    public static boolean walkable(IPathfindWorld world, long cell) {
        return walkable(world, unpackX(cell), unpackY(cell), unpackZ(cell));
    }

    /** every published path cell still enterable - catches nearby block changes */
    public static boolean pathStillValid(IPathfindWorld world, long[] cells) {
        for (long cell : cells) {
            if (!walkable(world, cell)) return false;
        }
        return true;
    }

    private static boolean isSuperboom(CollisionState s) {
        return s == CollisionState.SUPERBOOMABLE_GROUND || s == CollisionState.SUPERBOOMABLE_AIR;
    }

    /** goal cells = every integer cell inside a destination AABB plus its centre cell (boxes can contain zero cells) */
    public static long[] goalCells(List<AABB> doubledBoxes) {
        long[] buf = new long[doubledBoxes.size() * 8];
        int n = 0;
        for (AABB bb : doubledBoxes) {
            for (int x = (int) Math.ceil(bb.minX); x < bb.maxX; x++)
                for (int y = (int) Math.ceil(bb.minY); y < bb.maxY; y++)
                    for (int z = (int) Math.ceil(bb.minZ); z < bb.maxZ; z++) {
                        if (n == buf.length) buf = Arrays.copyOf(buf, buf.length * 2 + 8);
                        buf[n++] = pack(x, y, z);
                    }
            if (n == buf.length) buf = Arrays.copyOf(buf, buf.length + 1);
            buf[n++] = pack((int) Math.floor((bb.minX + bb.maxX) / 2),
                    (int) Math.floor((bb.minY + bb.maxY) / 2),
                    (int) Math.floor((bb.minZ + bb.maxZ) / 2));
        }
        long[] cells = Arrays.copyOf(buf, n);
        Arrays.sort(cells);
        // dedupe
        int m = 0;
        for (int i = 0; i < cells.length; i++) {
            if (m == 0 || cells[i] != cells[m - 1]) cells[m++] = cells[i];
        }
        return Arrays.copyOf(cells, m);
    }

    private static boolean isGoal(long[] goalCellsSorted, long cell) {
        return Arrays.binarySearch(goalCellsSorted, cell) >= 0;
    }

    /** nearest enterable cell to (x,y,z), preferring horizontal proximity then vertical */
    private static long nearestPassable(IPathfindWorld world, int x, int y, int z,
                                        int horizontalRadius, int verticalRadius) {
        if (walkable(world, x, y, z)) return pack(x, y, z);
        long best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int r = 1; r <= horizontalRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                        if (!walkable(world, x + dx, y + dy, z + dz)) continue;
                        int score = Math.abs(dx) + Math.abs(dz) + Math.abs(dy);
                        if (score < bestScore) {
                            bestScore = score;
                            best = pack(x + dx, y + dy, z + dz);
                        }
                    }
                }
            }
            if (best != -1) return best;
        }
        return best;
    }

    private static Result fail(Status status, int expansions, long startedNanos) {
        Result r = new Result();
        r.status = status;
        r.expansions = expansions;
        r.cost = Double.NaN;
        r.nanos = System.nanoTime() - startedNanos;
        return r;
    }

    public static Result search(IPathfindWorld world, long startCell, List<AABB> doubledGoalBoxes,
                                int maxExpansions) {
        return search(world, startCell, doubledGoalBoxes, goalCells(doubledGoalBoxes), maxExpansions);
    }

    /**
     * @param startCell   packed player cell (round(worldPos * 2))
     * @param goalCells   sorted packed goal cells (from {@link #goalCells})
     */
    public static Result search(IPathfindWorld world, long startCell, List<AABB> doubledGoalBoxes,
                                long[] goalCells, int maxExpansions) {
        long started = System.nanoTime();
        int sx = unpackX(startCell), sy = unpackY(startCell), sz = unpackZ(startCell);

        long start = nearestPassable(world, sx, sy, sz, 6, 10);
        if (start == -1) return fail(Status.NO_PASSABLE_START, 0, started);

        boolean anyGoal = false;
        for (long g : goalCells) {
            if (walkable(world, g)) { anyGoal = true; break; }
        }
        long[] resolvedGoals = goalCells;
        if (!anyGoal) {
            // targets are often wall-adjacent: accept anything standable within 2 cells
            long[] buf = new long[goalCells.length * 32];
            int n = 0;
            for (long g : goalCells) {
                int gx = unpackX(g), gy = unpackY(g), gz = unpackZ(g);
                for (int dx = -2; dx <= 2; dx++)
                    for (int dy = -3; dy <= 3; dy++)
                        for (int dz = -2; dz <= 2; dz++) {
                            if (walkable(world, gx + dx, gy + dy, gz + dz)) {
                                if (n == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                                buf[n++] = pack(gx + dx, gy + dy, gz + dz);
                            }
                        }
            }
            if (n == 0) return fail(Status.NO_PASSABLE_GOAL, 0, started);
            Arrays.sort(buf, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) if (m == 0 || buf[i] != buf[m - 1]) buf[m++] = buf[i];
            resolvedGoals = Arrays.copyOf(buf, m);
        }

        // search bounds: start/goal rect + padding, clamped to the pathfind world
        int bx0 = unpackX(start), bx1 = bx0, by0 = unpackY(start), by1 = by0, bz0 = unpackZ(start), bz1 = bz0;
        for (long g : resolvedGoals) {
            int gx = unpackX(g), gy = unpackY(g), gz = unpackZ(g);
            bx0 = Math.min(bx0, gx); bx1 = Math.max(bx1, gx);
            by0 = Math.min(by0, gy); by1 = Math.max(by1, gy);
            bz0 = Math.min(bz0, gz); bz1 = Math.max(bz1, gz);
        }
        bx0 = Math.max(bx0 - HORIZONTAL_PAD, world.getMinX());
        bx1 = Math.min(bx1 + HORIZONTAL_PAD, world.getMinX() + world.getXwidth());
        by0 = Math.max(by0 - VERTICAL_PAD, world.getMinY());
        by1 = Math.min(by1 + VERTICAL_PAD, world.getMinY() + world.getYwidth());
        bz0 = Math.max(bz0 - HORIZONTAL_PAD, world.getMinZ());
        bz1 = Math.min(bz1 + HORIZONTAL_PAD, world.getMinZ() + world.getZwidth());

        // heuristic: min manhattan distance over a guide set of goal cells.
        // A goal-centre heuristic degenerates to ~0 (floods as Dijkstra) when a
        // precalc's destination set is spread across the room, so instead we keep
        // the <=64 goals nearest to the start; the full set still decides isGoal.
        long[] guides = resolvedGoals;
        if (guides.length > 64) {
            long[] picked = new long[64];
            for (int i = 0; i < 64; i++) picked[i] = resolvedGoals[(int) ((long) i * resolvedGoals.length / 64)];
            // make sure the start-nearest goal is always represented
            long nearest = resolvedGoals[0];
            int best = Integer.MAX_VALUE;
            for (long g : resolvedGoals) {
                int d = Math.abs(unpackX(g) - unpackX(start)) + Math.abs(unpackY(g) - unpackY(start)) + Math.abs(unpackZ(g) - unpackZ(start));
                if (d < best) { best = d; nearest = g; }
            }
            picked[0] = nearest;
            guides = picked;
        }
        final int gn = guides.length;
        final int[] gx = new int[gn], gy = new int[gn], gz = new int[gn];
        for (int i = 0; i < gn; i++) {
            gx[i] = unpackX(guides[i]); gy[i] = unpackY(guides[i]); gz[i] = unpackZ(guides[i]);
        }

        // open-addressed cell map: key+1 -> [g, parentSlot, type]
        final int cap = 1 << 17, mask = cap - 1;
        long[] keys = new long[cap];
        float[] gs = new float[cap];
        int[] parent = new int[cap];
        byte[] types = new byte[cap];
        Arrays.fill(parent, -1);
        Arrays.fill(gs, Float.MAX_VALUE);

        // binary min-heap of (fBits << 32) | slot; f >= 0 so float int bits sort correctly
        long[] heap = new long[1024];
        int heapSize = 0;

        int startSlot;
        {
            long key = start + 1;
            int slot = (int) (mix(key) & mask);
            while (keys[slot] != 0) slot = (slot + 1) & mask;
            keys[slot] = key;
            gs[slot] = 0;
            startSlot = slot;
        }
        heap = heapPush(heap, heapSize++, entry(heuristic(unpackX(start), unpackY(start), unpackZ(start), gx, gy, gz), startSlot));

        int expansions = 0;
        int goalSlot = -1;
        while (heapSize > 0 && expansions < maxExpansions) {
            long e = heap[0];
            heapSize = heapPop(heap, heapSize);
            int slot = (int) (e & 0xFFFFFFFFL);
            float entryF = Float.intBitsToFloat((int) (e >>> 32));
            long cell = keys[slot] - 1;
            int cx = unpackX(cell), cy = unpackY(cell), cz = unpackZ(cell);
            float curF = gs[slot] + heuristic(cx, cy, cz, gx, gy, gz);
            if (entryF > curF + 0.001f) continue; // stale push
            expansions++;
            if (isGoal(resolvedGoals, cell)) { goalSlot = slot; break; }

            CollisionState curState = world.getBlock(cx, cy, cz);
            for (int d = 0; d < 6; d++) {
                int nx = cx + DX[d], ny = cy + DY[d], nz = cz + DZ[d];
                if (nx < bx0 || nx >= bx1 || ny < by0 || ny >= by1 || nz < bz0 || nz >= bz1) continue;
                CollisionState ns = world.getBlock(nx, ny, nz);
                if (!ns.isCanGo() || ns.isBlocked()) continue;

                float step;
                byte type = (byte) PathfindResult.PathfindNode.NodeType.WALK.ordinal();
                if (isSuperboom(curState) && !isSuperboom(ns)) {
                    step = 10;
                    type = (byte) PathfindResult.PathfindNode.NodeType.SUPERBOOM.ordinal();
                } else {
                    step = (ns.isOnGround() || d == UP) ? 1 : 2;
                }
                float ng = gs[slot] + step;

                long key = pack(nx, ny, nz) + 1;
                int nslot = (int) (mix(key) & mask);
                while (keys[nslot] != 0 && keys[nslot] != key) nslot = (nslot + 1) & mask;
                if (keys[nslot] != 0 && gs[nslot] <= ng) continue;
                keys[nslot] = key;
                gs[nslot] = ng;
                parent[nslot] = slot;
                types[nslot] = type;
                heap = heapPush(heap, heapSize++, entry(ng + heuristic(nx, ny, nz, gx, gy, gz), nslot));
            }
        }

        if (goalSlot == -1) {
            return fail(heapSize == 0 ? Status.NO_ROUTE : Status.EXPANSION_LIMIT, expansions, started);
        }

        // reconstruct goal -> start, reverse
        int len = 0;
        for (int s = goalSlot; s != -1; s = parent[s]) len++;
        long[] cells = new long[len];
        byte[] resultTypes = new byte[len];
        int i = len;
        for (int s = goalSlot; s != -1; s = parent[s]) {
            cells[--i] = keys[s] - 1;
            resultTypes[i] = types[s];
        }
        Result r = new Result();
        r.status = Status.FOUND;
        r.expansions = expansions;
        r.cost = gs[goalSlot];
        r.cells = cells;
        r.types = resultTypes;
        r.nanos = System.nanoTime() - started;
        return r;
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return k;
    }

    private static long entry(float f, int slot) {
        return ((long) Float.floatToIntBits(f) << 32) | (slot & 0xFFFFFFFFL);
    }

    private static long[] heapPush(long[] heap, int size, long e) {
        if (size == heap.length) heap = Arrays.copyOf(heap, heap.length * 2);
        int i = size;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= e) break;
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = e;
        return heap;
    }

    private static int heapPop(long[] heap, int size) {
        long last = heap[--size];
        if (size == 0) return 0;
        int i = 0;
        while (true) {
            int l = i * 2 + 1, r = l + 1;
            if (l >= size) break;
            int c = (r < size && heap[r] < heap[l]) ? r : l;
            if (heap[c] >= last) break;
            heap[i] = heap[c];
            i = c;
        }
        heap[i] = last;
        return size;
    }

    private static float heuristic(int x, int y, int z, int[] gx, int[] gy, int[] gz) {
        int h = Integer.MAX_VALUE;
        for (int i = 0; i < gx.length; i++) {
            int d = Math.abs(x - gx[i]) + Math.abs(y - gy[i]) + Math.abs(z - gz[i]);
            if (d < h) h = d;
        }
        return h;
    }
}

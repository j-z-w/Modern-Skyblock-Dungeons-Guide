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

package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

@AllArgsConstructor
public class PrecalculatedPathfinder implements IPathfinder {
    private int rotation;
    private PathfindPrecalculation.ReferenceCountedByteBufferWrapper buffer;
    private ByteBuffer array;

    private int xStart, yStart, zStart;
    private int xLen, yLen, zLen;


    private int roomXMin, roomYMin, roomZMin;
    private int roomXLen, roomZLen;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    public PrecalculatedPathfinder(int rotation, int xStart, int yStart, int zStart, int xLen, int yLen, int zLen, PathfindPrecalculation.ReferenceCountedByteBufferWrapper data) {
        this.rotation = rotation;
        this.xStart = xStart;
        this.yStart = yStart;
        this.zStart = zStart;
        this.xLen = xLen;
        this.yLen = yLen;
        this.zLen = zLen;
        this.array = data.getByteBuffer();
        this.buffer = data;
    }


    @Override
    public void init(IPathfindWorld dungeonRoom, BoundingBox destination) {
        this.roomXMin = dungeonRoom.getMinX() - 2;
        this.roomYMin = dungeonRoom.getMinY();
        this.roomZMin = dungeonRoom.getMinZ() - 2;
        this.roomXLen = dungeonRoom.getXwidth() / 2;
        this.roomZLen = dungeonRoom.getZwidth() / 2;
        routeCacheValid = false;
    }


    public void init2() {
        this.roomXMin = -2;
        this.roomYMin = 0;
        this.roomZMin = - 2;
        routeCacheValid = false;
    }

    @Override
    public boolean doOneStep() {
        return true;
    }

    private Vec3 target;
    @Override
    public void setTarget(Vec3 from) {
        this.target = from;
    }

    @Override
    public Vec3 getTarget() {
        return target;
    }

    private static final PathfindResult.PathfindNode.NodeType[] NODE_TYPES = PathfindResult.PathfindNode.NodeType.values();
    private static final int DESTINATION = PathfindResult.PathfindNode.NodeType.DESTINATION.ordinal();

    // -Ddg.nomemo disables route memoization so the benchmark can A/B it in the same binary
    private static final boolean NO_MEMO = Boolean.getBoolean("dg.nomemo");

    // the route depends only on the caller's half-block cell: memoize so a frame/tick that doesn't
    // cross a cell boundary doesn't re-walk the parent chain. Buffer data is immutable, so a hit is exact.
    private int cachedQX, cachedQY, cachedQZ;
    private PathfindResult cachedRoute;
    private boolean routeCacheValid;

    @Override
    public PathfindResult getRoute(Vec3 from) {
        if (NO_MEMO) return buildRoute(from);
        int qx = (int) Math.round(from.x * 2), qy = (int) Math.round(from.y * 2), qz = (int) Math.round(from.z * 2);
        if (routeCacheValid && qx == cachedQX && qy == cachedQY && qz == cachedQZ) return cachedRoute;
        PathfindResult route = buildRoute(from);
        cachedQX = qx; cachedQY = qy; cachedQZ = qz;
        cachedRoute = route;
        routeCacheValid = true;
        return route;
    }

    // decodes a node into scratch = {parentX, parentY, parentZ, typeOrdinal or -1}; returns its gScore.
    // Same byte layout as getNode: relX/relY/relZ/type packed into a long, gScore little-endian at +4.
    private final int[] scratch = new int[4];
    private float decodeNode(int x, int y, int z) {
        if (x < xStart || y < yStart || z < zStart || x >= xStart + xLen || y >= yStart + yLen || z >= zStart + zLen) {
            scratch[0] = scratch[1] = scratch[2] = 0; scratch[3] = -1;
            return Float.POSITIVE_INFINITY;
        }
        int idx = ((y - yStart) * xLen * zLen + (z - zStart) * xLen + (x - xStart)) * 8;
        int type = array.get(idx + 3) & 0xFF;
        if (type == 12) {
            scratch[0] = scratch[1] = scratch[2] = 0; scratch[3] = -1;
            return Float.POSITIVE_INFINITY;
        }
        scratch[0] = (array.get(idx) & 0xFF) + xStart;
        scratch[1] = (array.get(idx + 1) & 0xFF) + yStart;
        scratch[2] = (array.get(idx + 2) & 0xFF) + zStart;
        scratch[3] = type;
        return Float.intBitsToFloat(Integer.reverseBytes(array.getInt(idx + 4)));
    }

    private PathfindResult buildRoute(Vec3 from) {
        double dx = from.x - roomXMin / 2.0, dz = from.z - roomZMin / 2.0, dy = from.y - roomYMin/2.0;
        for (int i = rotation - 1; i >= 0; i--) {
            double tempX = dx;
            dx = -dz + ((i & 1) == 0 ? roomZLen + 2 : roomXLen + 2);
            dz = tempX;
        }

        int nx = (int) Math.round(dx * 2);
        int ny = (int) Math.round(dy * 2);
        int nz = (int) Math.round(dz * 2);

        LinkedList<PathfindResult.PathfindNode> route = new LinkedList<>();
        float gScore = decodeNode(nx, ny, nz);
        int type = scratch[3];
        if (type < 0) return null;
        double nextX = Math.round(from.x * 2) / 2.0, nextY = Math.round(from.y * 2) / 2.0 + 0.05, nextZ = Math.round(from.z * 2) / 2.0;
        int cnt = 0;
        while (type >= 0 && type != DESTINATION) {
            route.addLast(new PathfindResult.PathfindNode(nextX, nextY, nextZ, NODE_TYPES[type]));

            // the parent's node coords rotated back into world space, inlined from
            // OffsetVec3.toRotatedRelBlockPos(rotation, roomZLen, roomXLen) so the hot path doesn't allocate
            double rx = scratch[0] / 2.0, rz = scratch[2] / 2.0;
            for (int i = 0; i < rotation; i++) {
                double tempX = rx;
                rx = rz;
                rz = -tempX;
                rz += (i % 2 == 0) ? roomZLen + 2 : roomXLen + 2;
            }
            nextX = rx + roomXMin / 2.0;
            nextY = scratch[1] / 2.0 + roomYMin / 2.0 + 0.05;
            nextZ = rz + roomZMin / 2.0;

            decodeNode(scratch[0], scratch[1], scratch[2]);
            type = scratch[3];
            cnt ++;
            if (cnt > 1000) break;
        };
        route.addLast(new PathfindResult.PathfindNode(nextX, nextY, nextZ, type < 0 ? null : NODE_TYPES[type]));

        return new PathfindResult(route, gScore);
    }

    // also called from the Cleaner thread, releasing twice would free the buffer under another pathfinder
    public void close() {
        if (closed.compareAndSet(false, true)) {
            buffer.release();
            array = null;
        }
    }
    @AllArgsConstructor @Getter
    public static class CachedPathfindNode {
        private int x, y, z;
        private float gScore;
        private PathfindResult.PathfindNode.NodeType nodeType;

        private void fill(int x, int y, int z, float gScore, PathfindResult.PathfindNode.NodeType nodeType) {
            this.x = x; this.y = y; this.z = z;
            this.gScore = gScore;
            this.nodeType = nodeType;
        }
    }

    public CachedPathfindNode getNode(int x, int y, int z) {
        return getNode(x, y, z, new CachedPathfindNode(0,0,0,0,null));
    }

    // decodes the 8-byte node straight from the packed long - no byte[]/object garbage per hop.
    // `into` is caller-owned scratch, refilled every call
    private CachedPathfindNode getNode(int x, int y, int z, CachedPathfindNode into) {
        if (x < xStart || y < yStart || z < zStart || x >= xStart + xLen || y >= yStart + yLen || z >= zStart + zLen) {
            into.fill(0, 0, 0, Float.POSITIVE_INFINITY, null);
            return into;
        }
        int relX = x - xStart;
        int relY = y - yStart;
        int relZ = z - zStart;
        int idx = (relY * xLen * zLen + relZ * xLen + relX) * 8;
        long lng = array.getLong(idx);
        int nodeType = (int) (lng >> 32) & 0xFF; // data[3]
        if (nodeType == 12) {
            into.fill(0, 0, 0, Float.POSITIVE_INFINITY, null);
            return into;
        }

        int parentX = ((int) (lng >> 56) & 0xFF) + xStart; // data[0]
        int parentY = ((int) (lng >> 48) & 0xFF) + yStart; // data[1]
        int parentZ = ((int) (lng >> 40) & 0xFF) + zStart; // data[2]
        PathfindResult.PathfindNode.NodeType type = NODE_TYPES[nodeType];
        // data[4..7] are the low 4 bytes of lng, stored little-endian in the float
        float gScore = Float.intBitsToFloat(Integer.reverseBytes((int) lng));

        into.fill(parentX, parentY, parentZ, gScore, type);
        return into;
    }

    @Override
    public double getCost(Vec3 from) {
        double dx = from.x - roomXMin / 2.0, dz = from.z - roomZMin / 2.0, dy = from.y - roomYMin/2.0;
        for (int i = rotation - 1; i >= 0; i--) {
            double tempX = dx;
            dx = -dz + ((i & 1) == 0 ? roomZLen + 2 : roomXLen + 2);
            dz = tempX;
        }

        int x, y, z;
        x = (int) Math.round(dx * 2);
        y = (int) Math.round(dy * 2);
        z = (int) Math.round(dz * 2);

        if (x < xStart || y < yStart || z < zStart || x >= xStart + xLen || y >= yStart + yLen || z >= zStart + zLen) {
            return Float.POSITIVE_INFINITY;
        }
        int relX = x - xStart;
        int relY = y - yStart;
        int relZ = z - zStart;
        int idx = (relY * xLen * zLen + relZ * xLen + relX) * 8;

        int val = array.getInt(idx + 4);
        return Float.intBitsToFloat(Integer.reverseBytes(val));
    }
}

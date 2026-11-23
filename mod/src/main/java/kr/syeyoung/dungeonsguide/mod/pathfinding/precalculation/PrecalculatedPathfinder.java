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

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
    }


    public void init2() {
        this.roomXMin = -2;
        this.roomYMin = 0;
        this.roomZMin = - 2;
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

    // the route only depends on the quantized start cell; while the player stays in it
    // (most ticks) the previous result is returned instead of re-walking the parent chain
    private int memoCellX = Integer.MIN_VALUE, memoCellY, memoCellZ;
    private PathfindResult memoRoute;
    private boolean memoRouteValid;
    private final CachedPathfindNode scratch = new CachedPathfindNode(0, 0, 0, 0, null);

    @Override
    public PathfindResult getRoute(Vec3 from) {
        double dx = from.x - roomXMin / 2.0, dz = from.z - roomZMin / 2.0, dy = from.y - roomYMin/2.0;
        for (int i = 0; i < rotation; i++) {
            double tempX = dx;
            dx = -dz;
            dz = tempX;
            if (i % 2 == 0) {
                dx += xLen/2 + 1; // + Z
            } else {
                dx += zLen/2 + 1; // + X
            }
        }

        int nodeX, nodeY, nodeZ;
        nodeX = (int) Math.round(dx * 2);
        nodeY = (int) Math.round(dy * 2);
        nodeZ = (int) Math.round(dz * 2);

        if (memoRouteValid && nodeX == memoCellX && nodeY == memoCellY && nodeZ == memoCellZ) {
            return memoRoute;
        }

        LinkedList<PathfindResult.PathfindNode> route = new LinkedList<>();
        CachedPathfindNode curr = getNode(nodeX, nodeY, nodeZ, scratch);
        float gScore = curr.gScore;
        if (curr.nodeType == null) {
            memoCellX = nodeX; memoCellY = nodeY; memoCellZ = nodeZ;
            memoRoute = null;
            memoRouteValid = true;
            return null;
        }
        Vec3 nextPos = new Vec3(((int)Math.round(from.x * 2)) / 2.0,((int)Math.round(from.y * 2)) / 2.0 + 0.05,((int)Math.round(from.z * 2)) / 2.0);
        int cnt = 0;
        while(curr.nodeType != null && curr.nodeType != PathfindResult.PathfindNode.NodeType.DESTINATION) {
            route.addLast(new PathfindResult.PathfindNode(nextPos.x, nextPos.y, nextPos.z, curr.nodeType));

            OffsetVec3 offsetVec31 = new OffsetVec3(curr.x / 2.0, curr.y / 2.0, curr.z / 2.0);
            nextPos = offsetVec31.toRotatedRelBlockPos(rotation, roomZLen, roomXLen).add(roomXMin / 2.0, roomYMin / 2.0 + 0.05, roomZMin / 2.0);

            curr = getNode(curr.x, curr.y, curr.z, scratch);
            cnt ++;
            if (cnt > 1000) break;
        };
        route.addLast(new PathfindResult.PathfindNode(nextPos.x, nextPos.y, nextPos.z, curr.nodeType));

        PathfindResult result = new PathfindResult(route, gScore);
        memoCellX = nodeX; memoCellY = nodeY; memoCellZ = nodeZ;
        memoRoute = result;
        memoRouteValid = true;
        return result;
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
        PathfindResult.PathfindNode.NodeType type = PathfindResult.PathfindNode.NodeType.values()[nodeType];
        // data[4..7] are the low 4 bytes of lng, stored little-endian in the float
        float gScore = Float.intBitsToFloat(Integer.reverseBytes((int) lng));

        into.fill(parentX, parentY, parentZ, gScore, type);
        return into;
    }

    @Override
    public double getCost(Vec3 from) {
        double dx = from.x - roomXMin / 2.0, dz = from.z - roomZMin / 2.0, dy = from.y - roomYMin/2.0;
        for (int i = 0; i < rotation; i++) {
            double tempX = dx;
            dx = -dz;
            dz = tempX;
            if (i % 2 == 0) {
                dx += xLen/2 + 1; // + Z
            } else {
                dx += zLen/2 + 1; // + X
            }
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

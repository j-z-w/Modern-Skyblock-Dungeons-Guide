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

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AD8: live IPathfinder for the "precalculation MISSING" case. A bounded
 * forward A* (see {@link LiveAStar}) runs on a shared background worker -
 * never on the render thread - and the finished route is published atomically.
 *
 * Re-pathing: a worker tick (every {@value #TICK_MS} ms) re-runs the search
 * when the player's grid cell changed or when cells of the published path are
 * no longer enterable (nearby block updates), throttled to at most one search
 * per {@value #MIN_SEARCH_INTERVAL_MS} ms per pathfinder. Between publishes,
 * {@link #getRoute} only reads a volatile - no allocation, no computation.
 * If the search finds nothing, an empty result is published so the engines
 * draw just the destination marker, exactly like an unreachable precalc.
 */
public class LivePathfinder implements IPathfinder {
    private static final long TICK_MS = 250;
    private static final long MIN_SEARCH_INTERVAL_MS = 250;
    private static final PathfindResult EMPTY = new PathfindResult(Collections.emptyList(), 0);

    private static class Holder {
        // single daemon thread: searches are short and serialising them keeps
        // collision-map reads single-threaded
        private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(DungeonsGuide.THREAD_GROUP, r, "DG-LivePathfinder");
                t.setDaemon(true);
                return t;
            }
        });
    }

    private IPathfindWorld world;
    private List<AABB> doubledGoalBoxes;
    private long[] goalCells;
    private ScheduledFuture<?> future;
    private volatile boolean closed;

    private final AtomicLong playerCell = new AtomicLong(-1);
    private final AtomicBoolean repath = new AtomicBoolean(true);

    private volatile PathfindResult published;
    private volatile long[] publishedCells; // packed cells of the published path, for staleness checks
    private volatile double publishedCost;
    private volatile long lastSearchStartNanos;
    private volatile long searchedFromCell = -1;
    private final AtomicReference<LiveAStar.Status> lastStatus = new AtomicReference<>();

    @Override
    public void init(IPathfindWorld dungeonRoom, BoundingBox destination) {
        this.world = dungeonRoom;
        this.doubledGoalBoxes = destination.multiply(2).getBoundingBoxes();
        this.goalCells = LiveAStar.goalCells(doubledGoalBoxes);
        this.future = Holder.WORKER.scheduleWithFixedDelay(this::tickSafely, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private Vec3 target;

    @Override
    public boolean doOneStep() {
        // the search runs on the worker; complete immediately so consumers can query routes
        return true;
    }

    @Override
    public void setTarget(Vec3 from) {
        this.target = from;
        updatePlayerCell(from);
    }

    @Override
    public Vec3 getTarget() {
        return target;
    }

    private void updatePlayerCell(Vec3 from) {
        long cell = LiveAStar.pack(
                (int) Math.round(from.x * 2),
                (int) Math.round(from.y * 2),
                (int) Math.round(from.z * 2));
        if (playerCell.getAndSet(cell) != cell) repath.set(true);
    }

    @Override
    public PathfindResult getRoute(Vec3 from) {
        updatePlayerCell(from);
        PathfindResult r = published;
        return r == null ? EMPTY : r;
    }

    @Override
    public double getCost(Vec3 from) {
        return published != null ? publishedCost : Double.NaN;
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            // a drawing aid must never take the worker down with it
            t.printStackTrace();
        }
    }

    private void tick() {
        if (closed) return;
        long cell = playerCell.get();
        if (cell < 0) return;

        long[] cells = publishedCells;
        boolean broken = cells != null && !LiveAStar.pathStillValid(world, cells);
        boolean stale = repath.getAndSet(false)
                || cell != searchedFromCell
                || broken
                || (published == null && lastStatus.get() == null);
        if (!stale) return;

        long now = System.nanoTime();
        if (now - lastSearchStartNanos < TimeUnit.MILLISECONDS.toNanos(MIN_SEARCH_INTERVAL_MS)) {
            repath.set(true); // still wanted, just throttled
            return;
        }
        lastSearchStartNanos = now;
        searchedFromCell = cell;

        LiveAStar.Result res = LiveAStar.search(world, cell, doubledGoalBoxes, goalCells,
                LiveAStar.DEFAULT_MAX_EXPANSIONS);
        lastStatus.set(res.status);
        if (res.status == LiveAStar.Status.FOUND) {
            PathfindResult result = toRoute(res);
            publishedCells = res.cells;
            publishedCost = res.cost;
            published = result;
        } else {
            publishedCells = null;
            published = null;
        }
    }

    private static PathfindResult toRoute(LiveAStar.Result res) {
        LinkedList<PathfindResult.PathfindNode> route = new LinkedList<>();
        PathfindResult.PathfindNode.NodeType[] nodeTypes = PathfindResult.PathfindNode.NodeType.values();
        for (int i = 0; i < res.cells.length; i++) {
            long cell = res.cells[i];
            // types[i] is the move *into* cell i; the start cell has none (stale
            // slot default would read STONK_EXIT), so it is pinned to WALK
            PathfindResult.PathfindNode.NodeType type = i == res.cells.length - 1
                    ? PathfindResult.PathfindNode.NodeType.DESTINATION
                    : i == 0 ? PathfindResult.PathfindNode.NodeType.WALK
                    : nodeTypes[res.types[i]];
            route.addLast(new PathfindResult.PathfindNode(
                    LiveAStar.unpackX(cell) / 2.0,
                    LiveAStar.unpackY(cell) / 2.0 + 0.05,
                    LiveAStar.unpackZ(cell) / 2.0,
                    type));
        }
        return new PathfindResult(route, res.cost);
    }

    @Override
    public void close() {
        closed = true;
        if (future != null) future.cancel(false);
    }
}

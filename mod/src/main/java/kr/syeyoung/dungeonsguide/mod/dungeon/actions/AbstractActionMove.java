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

package kr.syeyoung.dungeonsguide.mod.dungeon.actions;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.RoomState;
import kr.syeyoung.dungeonsguide.mod.pathfinding.BoundingBox;
import kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Data
@EqualsAndHashCode(callSuper=false)
public abstract class AbstractActionMove extends AbstractAction {
//    private List<PossibleClickingSpot> targets;
    private String id;

    private OffsetVec3 targetVec3;
    private BlockPos beaconTargetPos;
    private List<OffsetVec3> targetOffsetPointSet;

    public AbstractActionMove(OffsetVec3 targetVec3, BlockPos beaconPos, List<OffsetVec3> targetOffsetPtSet) {
        this.targetVec3 = targetVec3;
        this.beaconTargetPos = beaconPos;
        this.targetOffsetPointSet = targetOffsetPtSet;
    }
    public AbstractActionMove(OffsetVec3 targetVec3, List<OffsetVec3> targetOffsetPtSet) {
        this.targetVec3 = targetVec3;
        this.targetOffsetPointSet = targetOffsetPtSet;
    }
    public AbstractActionMove(OffsetVec3 targetVec3) {
        this.targetVec3 = targetVec3;
        this.targetOffsetPointSet = Arrays.asList(
                targetVec3
        );
    }


    private BoundingBox boundingBox;
    public BoundingBox getPathfindBoundingBox(DungeonRoom dungeonRoom) {
        if (this.boundingBox != null) return boundingBox;
        BoundingBox boundingBox = new BoundingBox();
        for (OffsetVec3 offsetPoint : getTargetOffsetPointSet()) {
            Vec3 pos = offsetPoint.getPos(dungeonRoom);
            boundingBox.addBoundingBox(new AABB(
                    pos.x - 0.1, pos.y - 0.1, pos.z - 0.1,
                    pos.x + 0.1, pos.y + 0.1, pos.z + 0.1
            ));
        }
        return this.boundingBox = boundingBox;
    }

    public BlockPos getBeaconTargetPos(DungeonRoom dungeonRoom) {
        if (beaconTargetPos != null) return beaconTargetPos;
        return beaconTargetPos = BlockPos.containing(targetVec3.getPos(dungeonRoom));
    }

    private Vec3 transformedTargetVec3;
    private String vec3Str;
    private Vec3 getTransformedTargetVec3(DungeonRoom dungeonRoom) {
        if (transformedTargetVec3 != null) return transformedTargetVec3;
        this.transformedTargetVec3 = getTargetVec3().getPos(dungeonRoom);
        vec3Str = transformedTargetVec3.toString();
        return transformedTargetVec3;
    }

    //    @Override
//    public boolean isComplete(DungeonRoom dungeonRoom) {
//        return targets.stream().flatMap(a -> a.getOffsetPointSet().stream()).anyMatch(
//                a-> a.getPos(dungeonRoom).squareDistanceTo(Minecraft.getInstance().player.getPositionVector()) < 0.625
//        );
//    }



    public PathfindRequest getPathfindRequest(DungeonRoom dungeonRoom) {
        GeneralRoomProcessor generalRoomProcessor = (GeneralRoomProcessor) dungeonRoom.getRoomProcessor();
        return new PathfindRequest(
                generalRoomProcessor.getAlgorithmSetting(),
                dungeonRoom.getDungeonRoomInfo(),
                dungeonRoom.getMechanics().entrySet().stream().filter(b -> {
                    return  (b.getValue() instanceof DungeonDoorState || b.getValue() instanceof DungeonOnewayDoorState);
                }).filter(b -> !((WorldMutatingMechanicState)b.getValue()).isBlocking(dungeonRoom)).map(Map.Entry::getKey).collect(Collectors.toSet()),
                getTargetOffsetPointSet()
        );
    }

    public void forceRefresh(DungeonRoom dungeonRoom) {
        throw new UnsupportedOperationException("BRUH");
    }


    @Override
    public String toString() {
        return "Move\n- target: "+ getTargetVec3().toString();
    }

    private volatile String[] hashCache;

    // every eval-miss counter exists twice: the static EVAL_* (the regression harness's
    // global view) and this thread's EvalStats (what DPTSP.logSolveOutcome reports for
    // the solve actually running on this thread - concurrent solvers race the statics)
    private static void bumpEval(java.util.concurrent.atomic.AtomicLong global,
                                 java.util.function.Consumer<kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EvalStats> local) {
        if (!kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_DIAG) return;
        global.incrementAndGet();
        local.accept(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.stats());
    }

    @Override
    public double evalulateCost(RoomState state, DungeonRoom room, TSPCache tspCache, RoomPresetPathPlanner pathPlanner) {
        Vec3 bpos = getTransformedTargetVec3(room);

        if (hashCache == null) {
            hashCache = new String[1 << state.getOpenMechanicsIndex().size()];
        }

        String hash = hashCache[state.openMechanicsBitset];
        if (hash == null) {
            int bitset = state.openMechanicsBitset;
            Set<String> setConstruction = new HashSet<>();
            for (int i = 0; i < state.getOpenMechanicsIndex().size(); i++) {
                if ((bitset & (1 << i)) != 0) {
                    setConstruction.add(state.getOpenMechanicsIndex().get(i));
                }
            }

            hashCache[state.openMechanicsBitset] = hash = new PathfindRequest(
                    pathPlanner.getAlgorithmSetting(),
                    room.getDungeonRoomInfo(),
                    setConstruction,
                    getTargetOffsetPointSet()
            ).getHash();
        }


        kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache tspCache1 = pathPlanner.getTSPCache();
        if (tspCache1 == null) {
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_MATRIX_NULL,
                    s -> s.EVAL_MATRIX_NULL++);
        }
        double cost = tspCache1 == null ? -1 : tspCache1.getCost(hash, state.getPlayerPosOff());
        if (cost >= 0) {
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_MATRIX_HIT,
                    s -> s.EVAL_MATRIX_HIT++);
            state.setPlayerPos(bpos);
            return cost;
        } else if (cost == -2) {
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_MATRIX_HASH_MISS,
                    s -> s.EVAL_MATRIX_HASH_MISS++);
        } else if (tspCache1 != null) {
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_MATRIX_POS_MISS,
                    s -> s.EVAL_MATRIX_POS_MISS++);
        }

        cost = tspCache.getCost(hash, state.getPlayerPos());
        if (cost >= 0) {
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_SOLVECACHE_HIT,
                    s -> s.EVAL_SOLVECACHE_HIT++);
            state.setPlayerPos(bpos);
            return cost;
        }

        if (cost == -2) {
            PathfindPrecalculation precalculation = pathPlanner.getPrecalcByHash(hash);
            if (precalculation == null) {
                bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_PRECALC_MISSING,
                        s -> s.EVAL_PRECALC_MISSING++);
                return Double.POSITIVE_INFINITY;
            }
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_ADDTOCACHE,
                    s -> s.EVAL_ADDTOCACHE++);
            try {
                tspCache.addToCache(precalculation);
            } catch (IOException e) { throw new RuntimeException(e); }

            cost = tspCache.getCost(hash, state.getPlayerPos());
            state.setPlayerPos(bpos);
            return cost;
        }

//        System.out.println(state.getPlayerPos());

        bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_PFRES_LOAD,
                s -> s.EVAL_PFRES_LOAD++);
        // cached per solve: one executor per hash serves all positions (getCost is a pure grid
        // lookup); building one per callback made matrix misses ~ms each - the ordering hang.
        // callers give each solve worker its own planner, so this cache is single-threaded
        PathfinderExecutor executor = pathPlanner.loadPrecalculatedByHashCached(hash, room);
        if (executor == null) { // load failed (IO error / missing buffer) - treat as unreachable rather than NPE
            bumpEval(kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_PRECALC_MISSING,
                    s -> s.EVAL_PRECALC_MISSING++);
            state.setPlayerPos(bpos);
            return Double.POSITIVE_INFINITY;
        }

        double result = executor.getPathfinder().getCost(state.getPlayerPos());

        state.setPlayerPos(bpos);
        if (Double.isNaN(result)) return 999999999;
        return result;
    }
}

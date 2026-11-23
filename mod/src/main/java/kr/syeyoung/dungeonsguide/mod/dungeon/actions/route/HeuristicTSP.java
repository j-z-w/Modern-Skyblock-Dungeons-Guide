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

package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner;
import lombok.Getter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Dependency-aware nearest-neighbour + bounded relocation-improvement ordering.
 * Unlike DPTSP (native DP over 2^requireBits x or-product x 2^mechanics states)
 * this runs in ~O(n^2) evals and is guaranteed to emit a usable order quickly,
 * which is what keeps a line on screen for rooms like `spider` where the exact
 * DP needs ~2^27 states (several minutes of JNI callbacks).
 */
public class HeuristicTSP {
    private final DungeonRoom dungeonRoom;
    private final TSPCache tspCache;
    private final RoomPresetPathPlanner pathPlanner;

    private final List<ActionDAGNode> all;
    private final boolean[] enabled;
    private final boolean[] done;
    private final boolean[] skipped;
    private final RoomState roomState;
    private final Vec3 startPos;
    private final int startBitset;
    private final List<ActionDAGNode> order = new ArrayList<>();
    private long evals;
    private boolean timedOut;

    public HeuristicTSP(ActionDAG dag, Vec3 start, DungeonRoom dungeonRoom,
                        TSPCache tspCache, RoomPresetPathPlanner pathPlanner) {
        this.dungeonRoom = dungeonRoom;
        this.tspCache = tspCache;
        this.pathPlanner = pathPlanner;
        this.all = dag.getAllNodes();

        int[] status;
        try {
            status = dag.getNodeStatusAll();
        } catch (Throwable t) {
            status = null; // isComplete can be unusable headless; treat every node as enabled
        }
        this.enabled = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            enabled[i] = status == null || status[i] == 3;
        }
        this.done = new boolean[all.size()];
        this.skipped = new boolean[all.size()];

        List<String> mechanicNames = doorMechanics(dungeonRoom);
        this.roomState = new RoomState(mechanicNames);
        this.roomState.setDungeonRoom(dungeonRoom);
        this.startPos = start;
        this.startBitset = initialBitset(dungeonRoom, mechanicNames);
        this.roomState.setPlayerPos(start);
        this.roomState.openMechanicsBitset = startBitset;
    }

    public static List<String> doorMechanics(DungeonRoom room) {
        if (room == null) return Collections.emptyList();
        return room.getMechanics().entrySet().stream()
                .filter(a -> a.getValue() instanceof DungeonDoorState || a.getValue() instanceof DungeonOnewayDoorState)
                .map(Map.Entry::getKey).collect(Collectors.toList());
    }

    public static int initialBitset(DungeonRoom room, List<String> mechanicNames) {
        int bitset = 0;
        for (int i = 0; i < mechanicNames.size(); i++) {
            if (!((WorldMutatingMechanicState) room.getMechanics().get(mechanicNames.get(i))).isBlocking(room)) {
                bitset |= 1 << i;
            }
        }
        return bitset;
    }

    @Getter
    public static class Result {
        private final List<ActionDAGNode> order;
        private final double cost;
        private final long evals;
        private final boolean complete; // every enabled node is either placed or legitimately skipped
        private final boolean timedOut;

        private Result(List<ActionDAGNode> order, double cost, long evals, boolean complete, boolean timedOut) {
            this.order = order;
            this.cost = cost;
            this.evals = evals;
            this.complete = complete;
            this.timedOut = timedOut;
        }
    }

    public Result solve(BooleanSupplier cancelled, long deadlineNanos) {
        int scan = 0;
        while (true) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true;
                break;
            }
            ActionDAGNode best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (ActionDAGNode node : all) {
                int id = node.getId();
                if (!enabled[id] || done[id] || skipped[id] || !depsMet(node)) continue;
                double cost = probe(node);
                if (best == null || cost < bestCost) {
                    best = node;
                    bestCost = cost;
                }
                if ((++scan & 0x3F) == 0 && System.nanoTime() > deadlineNanos) break;
            }
            if (best == null) break;
            evalCost(best); // commit: let the action update position / open-mechanics bitset
            done[best.getId()] = true;
            order.add(best);
            sweepDead();
        }

        // anything still unplaced that can be placed gets appended dependency-first,
        // so a timed-out or INF-plagued greedy pass still yields a complete route
        boolean progress = true;
        while (progress) {
            progress = false;
            for (ActionDAGNode node : all) {
                int id = node.getId();
                if (!enabled[id] || done[id] || skipped[id] || !depsMet(node)) continue;
                done[id] = true;
                order.add(node);
                sweepDead();
                progress = true;
            }
        }

        if (!timedOut) improve(cancelled, deadlineNanos);
        return new Result(new ArrayList<>(order), simulate(order), evals, isComplete(), timedOut);
    }

    private boolean isComplete() {
        for (int i = 0; i < all.size(); i++) {
            if (enabled[i] && !done[i] && !skipped[i]) return false;
        }
        return true;
    }

    private double evalCost(ActionDAGNode node) {
        evals++;
        try {
            double cost = node.getAction().evalulateCost(roomState, dungeonRoom, tspCache, pathPlanner);
            if (Double.isNaN(cost)) return Double.POSITIVE_INFINITY;
            return cost;
        } catch (Throwable t) {
            return Double.POSITIVE_INFINITY;
        }
    }

    // score a candidate without committing the state changes the action simulates
    private double probe(ActionDAGNode node) {
        Vec3 pos = roomState.getPlayerPos();
        int bits = roomState.openMechanicsBitset;
        double cost = evalCost(node);
        roomState.setPlayerPos(pos);
        roomState.openMechanicsBitset = bits;
        return cost;
    }

    private boolean depsMet(ActionDAGNode node) {
        for (ActionDAGNode req : node.getRequire()) {
            int id = req.getId();
            if (!enabled[id]) continue; // already complete in the world
            if (!done[id]) return false;
        }
        if (!node.getOr().isEmpty() && !orSatisfied(node.getOr())) return false;
        return true;
    }

    // an or-group is satisfied once one child is done - and also when one child is
    // already complete in the world (!enabled): that alternative needs no work, so it
    // can't keep the parent gated or force a pointless placement
    private boolean orSatisfied(List<ActionDAGNode> ors) {
        for (ActionDAGNode or : ors) {
            if (done[or.getId()] || !enabled[or.getId()]) return true;
        }
        return false;
    }

    private boolean anyDone(List<ActionDAGNode> nodes) {
        for (ActionDAGNode node : nodes) {
            if (done[node.getId()]) return true;
        }
        return false;
    }

    // a node is dead once no living parent still needs it:
    // or-siblings of a satisfied group, children of skipped parents, late optionals
    private boolean stillNeeded(ActionDAGNode node) {
        if (node.getRequiredBy().isEmpty()) return true; // terminal root
        for (ActionDAGNode parent : node.getRequiredBy()) {
            if (done[parent.getId()] || skipped[parent.getId()]) continue;
            if (parent.getRequire().contains(node)) return true;
            if (parent.getOptional().contains(node)) return true;
            if (parent.getOr().contains(node) && !orSatisfied(parent.getOr())) return true;
        }
        return false;
    }

    private void sweepDead() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ActionDAGNode node : all) {
                int id = node.getId();
                if (!enabled[id] || done[id] || skipped[id]) continue;
                if (!stillNeeded(node)) {
                    skipped[id] = true;
                    changed = true;
                }
            }
        }
    }

    // total simulated cost of any order through the same RoomState model the solve
    // used - lets callers price the exact DP's order on identical terms (the
    // heuristic/exact ratio reported by ActionRoute and the regression test)
    public double routeCost(List<ActionDAGNode> ord) {
        return simulate(ord);
    }

    private double simulate(List<ActionDAGNode> ord) {
        Vec3 savedPos = roomState.getPlayerPos();
        int savedBits = roomState.openMechanicsBitset;
        roomState.setPlayerPos(startPos);
        roomState.openMechanicsBitset = startBitset;
        double total = 0;
        for (ActionDAGNode node : ord) {
            total += evalCost(node);
        }
        roomState.setPlayerPos(savedPos);
        roomState.openMechanicsBitset = savedBits;
        return total;
    }

    // order is executable iff it matches what a topological sort of some dagId would
    // produce: requires first, exactly one or-child before its parent, included
    // optional children before their parents
    public boolean isValidOrder(List<ActionDAGNode> ord) {
        int[] pos = new int[all.size()];
        Arrays.fill(pos, -1);
        for (int i = 0; i < ord.size(); i++) {
            if (pos[ord.get(i).getId()] != -1) return false;
            pos[ord.get(i).getId()] = i;
        }
        for (int i = 0; i < ord.size(); i++) {
            ActionDAGNode node = ord.get(i);
            for (ActionDAGNode req : node.getRequire()) {
                if (enabled[req.getId()] && pos[req.getId()] >= i) return false;
                if (enabled[req.getId()] && pos[req.getId()] == -1) return false;
            }
            if (!node.getOr().isEmpty()) {
                int chosen = -1;
                boolean satisfiedElsewhere = false;
                for (ActionDAGNode or : node.getOr()) {
                    if (pos[or.getId()] >= 0) {
                        if (chosen != -1) return false; // two alternatives in one route
                        chosen = pos[or.getId()];
                    } else if (!enabled[or.getId()]) {
                        satisfiedElsewhere = true; // already complete in the world
                    }
                }
                if (chosen == -1) {
                    if (!satisfiedElsewhere) return false;
                } else if (chosen >= i) return false;
            }
            for (ActionDAGNode opt : node.getOptional()) {
                if (pos[opt.getId()] >= i) return false;
            }
        }
        return true;
    }

    // a placed node is deadwood when no placed parent needs it as a require/or child;
    // dropping it corresponds to a dagId where that optional/alternative is off
    private boolean removable(ActionDAGNode node, int[] pos) {
        if (node.getRequiredBy().isEmpty()) return false; // never drop the terminal root
        for (ActionDAGNode parent : node.getRequiredBy()) {
            if (pos[parent.getId()] < 0) continue;
            if (parent.getRequire().contains(node)) return false;
            if (parent.getOr().contains(node)) return false;
        }
        return true;
    }

    // bounded relocate + deadwood-removal improvement
    private void improve(BooleanSupplier cancelled, long deadlineNanos) {
        int n = order.size();
        if (n <= 2) return;
        double best = simulate(order);

        // removal pass: speculative greedy picks whose parent group resolved another
        // way (or that are only optional children) get dropped when legal and not worse
        while (true) {
            if (cancelled != null && cancelled.getAsBoolean()) return;
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true;
                return;
            }
            int[] pos = new int[all.size()];
            Arrays.fill(pos, -1);
            for (int i = 0; i < order.size(); i++) pos[order.get(i).getId()] = i;
            int removedIdx = -1;
            for (int idx = 0; idx < order.size(); idx++) {
                ActionDAGNode node = order.get(idx);
                if (!removable(node, pos)) continue;
                List<ActionDAGNode> candidate = new ArrayList<>(order);
                candidate.remove(idx);
                if (!isValidOrder(candidate)) continue;
                double cost = simulate(candidate);
                if (cost < best + 1e-6) {
                    removedIdx = idx;
                    best = cost;
                    break;
                }
            }
            if (removedIdx < 0) break;
            ActionDAGNode removed = order.remove(removedIdx);
            done[removed.getId()] = false;
            skipped[removed.getId()] = true;
            n = order.size();
        }

        int passes = 0;
        while (passes++ < 4) {
            boolean improved = false;
            for (int j = 1; j < n; j++) {
                if (cancelled != null && cancelled.getAsBoolean()) return;
                if (System.nanoTime() > deadlineNanos) {
                    timedOut = true;
                    return;
                }
                for (int i = 0; i < j; i++) {
                    List<ActionDAGNode> candidate = new ArrayList<>(order);
                    candidate.add(i, candidate.remove(j));
                    if (!isValidOrder(candidate)) continue;
                    double cost = simulate(candidate);
                    if (cost < best - 1e-6) {
                        order.clear();
                        order.addAll(candidate);
                        best = cost;
                        improved = true;
                        break;
                    }
                }
                if (improved) break;
            }
            if (!improved) return;
        }
    }
}

/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
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

import kr.syeyoung.dungeonsguide.mod.events.impl.LivingDeathEvent;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEntityEvent;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEvent;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ActionRoute {

    private String name;
    @Override
    public String toString() {
        return name;
    }

    // written by pathCalculator workers, read every tick/frame on the render
    // thread: volatile so the render thread can't see a stale/mid-write state.
    // `calculating = false` stays the last (release) write of each publication
    @Getter
    private transient volatile int current;
    @Getter
    private transient volatile List<AbstractAction> actions;

    @Getter
    private final ActionDAG dag;
    @Getter
    private transient volatile int dagId;
    @Getter
    private transient volatile List<ActionDAGNode> order;

    @Getter
    private final DungeonRoom dungeonRoom;

    public ActionRoute(DungeonRoom dungeonRoom, String mechanic, String state, AlgorithmSetting algorithmSetting)throws PathfindImpossibleException  {
        this(mechanic +" -> "+state, dungeonRoom, new ActionDAGBuilder(dungeonRoom)
                .requires(new ActionChangeState(mechanic, state), algorithmSetting).build());
    }

    public ActionRoute(String name, DungeonRoom dungeonRoom, ActionDAG dag) throws PathfindImpossibleException  {
        this.name = name;
        this.checkCanCancel = (dg) -> false;
        this.dag = dag;
        this.dungeonRoom = dungeonRoom;

        recalculatePath();
    }

    @Getter
    private volatile boolean calculating = false;

    private static final ExecutorService pathCalculator = DungeonsGuide.getDungeonsGuide().registerExecutorService(Executors.newWorkStealingPool(10));

    // how long a solve may wait for the preset's TSP cost matrix before degrading to
    // the per-precalc path. a cold build loads every pfres in the room, so this has
    // to be generous; the common case (matrix already resident) never waits at all
    private static final long TSP_CACHE_WAIT_MS = Long.getLong("dg.smartRoute.tspCacheWaitMs", 30_000L);

    // exact DP remains the default solver: the heuristic order is only published
    // once the exact solve has run longer than this, and is then replaced by the
    // exact order when it arrives
    private static final long HEURISTIC_DELAY_MS = Long.getLong("dg.smartRoute.heuristicDelayMs", 1_500L);
    private static final long HEURISTIC_BUDGET_MS = Long.getLong("dg.smartRoute.heuristicBudgetMs", 300L);
    // the native coroutine can only be cancelled at its Java-callback boundaries,
    // so a solve whose estimated state space exceeds this is never started - the
    // heuristic order is the route for those rooms (spider ~2^27 states was the
    // 240s+ hang from the live log)
    private static final double MAX_EXACT_STATES = Double.parseDouble(System.getProperty("dg.smartRoute.maxExactStates", "2097152"));
    // secondary bound for under-cap solves; polled once per native callback
    private static final long EXACT_DEADLINE_MS = Long.getLong("dg.smartRoute.exactDeadlineMs", 10_000L);

    // bumped on every recalculatePath: workers carrying an older generation see
    // stale=true and stop instead of publishing an obsolete route
    private final java.util.concurrent.atomic.AtomicInteger pathGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean failureLogged = false;

    private void recalculatePath() {
        final int gen = pathGeneration.incrementAndGet();
        calculating = true;
        current = 0;
        List<AbstractAction> placeholder = new ArrayList<>();
        placeholder.add(new ActionRoot());
        actions = placeholder;
        final Vec3 start = Minecraft.getInstance().player.position();
        pathCalculator.submit(() -> {
            ChatTransmitter.sendDebugChat("ActionDAG has "+dag.getCount()+" Possible action set");

            long startNanos = System.nanoTime();
            boolean published = false;
            java.util.function.BooleanSupplier stale = () -> pathGeneration.get() != gen;
            try {
                // the preset's TSP matrix may still be loading (DG-Init cache scan) or
                // rebuilding (scheduled with a 5s delay): wait for it here, off the
                // render thread, so the solve doesn't fall into a pathfinder build per
                // cost callback just because loading is in progress
                RoomPresetPathPlanner pathPlanner = new RoomPresetPathPlanner(dungeonRoom.getContext().getPreset().getRoomPreset(dungeonRoom.getDungeonRoomInfo().getUuid()));
                pathPlanner.awaitTSPCache(TSP_CACHE_WAIT_MS);
                if (stale.getAsBoolean()) return;

                DPTSP dptsp = new DPTSP(dag, start, dungeonRoom, pathPlanner);
                double states = dptsp.getStateEstimate();
                boolean exactBounded = states <= MAX_EXACT_STATES;

                // degenerate DAG (nothing left to schedule): publish the empty route
                // immediately, no solver threads needed
                if (dptsp.enabledNodeCount == 0) {
                    dptsp.solve(null);
                    publish(dptsp.reconstructPath());
                    published = true;
                    return;
                }

                // the exact DP runs on its own worker so this thread can publish the
                // heuristic order if the exact solve outlives the delay below
                java.util.concurrent.CompletableFuture<List<ActionDAGNode>> exactFuture =
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            if (!exactBounded) return null;
                            long exactDeadline = System.nanoTime() + EXACT_DEADLINE_MS * 1_000_000L;
                            java.util.function.BooleanSupplier cancelled =
                                    () -> stale.getAsBoolean() || System.nanoTime() > exactDeadline;
                            try {
                                return dptsp.solve(cancelled) ? dptsp.reconstructPath() : null;
                            } catch (Throwable t) {
                                return null;
                            }
                        }, pathCalculator);

                // heuristic backstop, computed concurrently here (bounded to ~300ms):
                // dependency-respecting order that becomes the route when the exact
                // DP is too slow or too big
                HeuristicTSP heuristic = null;
                HeuristicTSP.Result heur = null;
                try {
                    TSPCache tspCache = new TSPCache((GeneralRoomProcessor) dungeonRoom.getRoomProcessor(), dungeonRoom, Collections.EMPTY_LIST, Collections.singletonList(start));
                    heuristic = new HeuristicTSP(dag, start, dungeonRoom, tspCache, pathPlanner);
                    heur = heuristic.solve(stale, System.nanoTime() + HEURISTIC_BUDGET_MS * 1_000_000L);
                } catch (Throwable t) {
                    // heuristic failure leaves the exact / topological paths to cover it
                }
                if (stale.getAsBoolean()) return;

                boolean heuristicPublished = false;
                while (!exactFuture.isDone()) {
                    if (stale.getAsBoolean()) return;
                    if (heur != null && System.nanoTime() - startNanos > HEURISTIC_DELAY_MS * 1_000_000L) {
                        publish(heur.getOrder());
                        heuristicPublished = true;
                        published = true;
                        calculating = false;
                        ChatTransmitter.sendDebugChat("Smart Route :: heuristic order shown ("
                                + heur.getOrder().size() + " actions, cost " + (long) heur.getCost()
                                + "); exact solve still running");
                        break;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                List<ActionDAGNode> exact;
                try {
                    exact = exactFuture.get();
                } catch (Throwable t) {
                    exact = null;
                }
                if (stale.getAsBoolean()) return;
                if (exact != null) {
                    publish(exact);
                    published = true;
                    if (heur != null && heuristicPublished) {
                        double exactCost = heuristic.routeCost(exact);
                        ChatTransmitter.sendDebugChat("Smart Route :: exact order installed (cost "
                                + (long) exactCost + " vs heuristic " + (long) heur.getCost()
                                + ", ratio " + String.format(java.util.Locale.ROOT, "%.3f", heur.getCost() / exactCost)
                                + ") in " + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
                    } else {
                        ChatTransmitter.sendDebugChat("Pathfinding took " + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
                    }
                } else if (heur != null && !heuristicPublished) {
                    // exact was over the state cap and never ran, or failed/cancelled
                    // within the delay: the heuristic order is the route
                    publish(heur.getOrder());
                    published = true;
                    ChatTransmitter.sendDebugChat("Smart Route :: heuristic order shown ("
                            + heur.getOrder().size() + " actions, cost " + (long) heur.getCost() + ")"
                            + (!exactBounded ? "; exact ~2^" + (int) (Math.log(states) / Math.log(2)) + " states over cap" : "; exact solve failed"));
                }
            } catch (Throwable t) {
                if (!failureLogged) {
                    failureLogged = true;
                    t.printStackTrace();
                    ChatTransmitter.addToQueue("§eDungeons Guide :: §fSmart Route :: §croute planner failed, publishing fallback order");
                }
            } finally {
                // `calculating` must always be released by the newest generation;
                // stale workers leave it to their replacement
                if (!stale.getAsBoolean()) {
                    if (!published) {
                        publish(firstTopologicalOrder());
                    }
                    calculating = false;
                }
            }
        });
    }

    private void publish(List<ActionDAGNode> nodes) {
        this.dagId = 0;
        this.order = nodes;
        List<AbstractAction> actionList = nodes.stream().map(ActionDAGNode::getAction).collect(Collectors.toList());
        actionList.add(new ActionComplete());
        this.actions = actionList;
        this.current = 0;
    }

    // last-resort order: the first topological sort of any dag variant, so the route
    // still lists every pending action even if both solvers fail
    private List<ActionDAGNode> firstTopologicalOrder() {
        int limit = Math.min(dag.getCount(), 64);
        for (int i = 0; i < limit; i++) {
            try {
                Iterator<List<ActionDAGNode>> it = dag.topologicalSortIterator(i);
                if (it.hasNext()) {
                    return it.next();
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>();
    }
    public AbstractAction next() {
        current ++;
        if (current >= actions.size()) {
            current = actions.size() - 1;
        }
        return getCurrentAction();
    }

    public AbstractAction prev() {
        current --;
        if (current < 0) {
            current = 0;
        }
        return getCurrentAction();
    }

    public AbstractAction getCurrentAction() {
        return actions.get(current);
    }



    public void onPlayerInteract(PlayerInteractEvent event) {
        if (calculating) return;
        getCurrentAction().onPlayerInteract(dungeonRoom, event );
    }
    public void onLivingDeath(LivingDeathEvent event) {
        if (calculating) return;
        getCurrentAction().onLivingDeath(dungeonRoom, event );
    }

    private final Function<DungeonRoom, Boolean> checkCanCancel;

    public void onTick() {
        if (calculating) return;
        AbstractAction currentAction = getCurrentAction();

        currentAction.onTick(dungeonRoom);

        if (checkCanCancel != null && checkCanCancel.apply(dungeonRoom)) { // action change state
            this.current = actions.size() - 1;
        }


        while (currentAction.isComplete(dungeonRoom)) {
            next();
            currentAction = getCurrentAction();
        }

        boolean recalc = false;
        for (int i = current; i < actions.size(); i++) {
            if (actions.get(i).shouldRecalculatePath(dungeonRoom)) {
                recalc = true;
            }
        }
        if (recalc) {
            recalculatePath();
            while (currentAction.isComplete(dungeonRoom)) {
                next();
                currentAction = getCurrentAction();
            }
        }
    }

    public void onLivingInteract(PlayerInteractEntityEvent event) {
        if (calculating) return;
        getCurrentAction().onLivingInteract(dungeonRoom, event );
    }

}

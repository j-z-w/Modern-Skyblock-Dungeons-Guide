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
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
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
        this(name, dungeonRoom, dag, false);
    }

    // defer=true skips the initial solve (recalculatePath reads the client player
    // position, unavailable to headless tests)
    ActionRoute(String name, DungeonRoom dungeonRoom, ActionDAG dag, boolean defer) throws PathfindImpossibleException  {
        this.name = name;
        this.checkCanCancel = (dg) -> false;
        this.dag = dag;
        this.dungeonRoom = dungeonRoom;

        if (!defer) recalculatePath();
    }

    @Getter
    private volatile boolean calculating = false;

    private static final ExecutorService pathCalculator = DungeonsGuide.getDungeonsGuide().registerExecutorService(Executors.newWorkStealingPool(10));

    // exact DP solves run on their own threads: submitting them to pathCalculator let
    // >=10 concurrent recalculates park every worker on the outer wait loop and starve
    // every exactFuture for the full heuristic delay. bounded and daemon: an
    // over-subscribed solver rejects onto the heuristic path instead of spawning
    // unbounded native-DP threads, and a stuck solve can't keep the JVM alive
    private static final java.util.concurrent.ThreadPoolExecutor exactSolverPool =
            new java.util.concurrent.ThreadPoolExecutor(4, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    // a real queue, not a SynchronousQueue: "display all" submits one
                    // exact solve per room at once - with zero queueing every solve
                    // past the 4 workers was rejected onto the heuristic path
                    new java.util.concurrent.LinkedBlockingQueue<>(32),
                    new com.google.common.util.concurrent.ThreadFactoryBuilder()
                            .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                            .setDaemon(true)
                            .setNameFormat("DG-ExactTSP-%d").build());
    static {
        exactSolverPool.allowCoreThreadTimeOut(true);
    }
    private static final ExecutorService exactSolver = DungeonsGuide.getDungeonsGuide().registerExecutorService(exactSolverPool);

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
    // so a solve whose estimated state space exceeds this is never started. The
    // estimate is a loose upper bound (spider ~2^27 solves in <1s once the TSP
    // matrix is present; the live 240s+ hang was per-callback matrix misses), so
    // the cap only excludes pathological DAGs beyond every bundled room (~2^34)
    private static final double MAX_EXACT_STATES = Double.parseDouble(System.getProperty("dg.smartRoute.maxExactStates", "6.8719476736E10"));
    // step costs at or above this are the TSP matrix's unreachable sentinels
    private static final double UNREACHABLE_COST = Float.MAX_VALUE / 2;
    // secondary bound for under-cap solves; polled once per native callback
    private static final long EXACT_DEADLINE_MS = Long.getLong("dg.smartRoute.exactDeadlineMs", 10_000L);

    // bumped on every recalculatePath: workers carrying an older generation see
    // stale=true and stop instead of publishing an obsolete route
    final java.util.concurrent.atomic.AtomicInteger pathGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean failureLogged = false;

    // abandons any in-flight solve: the generation bump makes every worker's `stale`
    // check trip, so the exact DP's cancellation supplier, the cache wait, and the
    // heuristic all unwind instead of finishing a solve for a route being torn down
    // (dungeon left, world unload, route registry cleared)
    public void cancelSolves() {
        pathGeneration.incrementAndGet();
    }

    private void recalculatePath() {
        final int gen = pathGeneration.incrementAndGet();
        calculating = true;
        current = 0;
        List<AbstractAction> placeholder = new ArrayList<>();
        placeholder.add(new ActionRoot());
        actions = placeholder;
        // headless/test contexts have no client player; the solve only needs a start pos
        Minecraft mc = Minecraft.getInstance();
        final Vec3 start = mc != null && mc.player != null ? mc.player.position() : Vec3.ZERO;
        pathCalculator.submit(() -> {
            long startNanos = System.nanoTime();
            boolean published = false;
            java.util.function.BooleanSupplier stale = () -> pathGeneration.get() != gen;
            RoomPresetPathPlanner heuristicPlanner = null;
            RoomPresetPathPlanner exactPlanner = null;
            // the exact worker owns its eval cache once accepted; the inner finally
            // only closes it when the task was never submitted
            java.util.concurrent.atomic.AtomicBoolean exactSubmitted =
                    new java.util.concurrent.atomic.AtomicBoolean();
            try {
                // everything the worker touches lives inside this try: a throwable
                // anywhere before it (class-init errors included) would be lost in
                // the discarded Future and `calculating` would never release
                ChatTransmitter.sendDebugChat("ActionDAG has "+dag.getCount()+" Possible action set");
                // one planner per solve worker: the exact and heuristic evals call the
                // planner's executor LRU concurrently, so each side gets its own cache and
                // closes it when its worker exits - a shared LRU's evict-close could free a
                // buffer the other worker is inside getCost() on
                RoomPreset roomPreset = dungeonRoom.getContext().getPreset().getRoomPreset(dungeonRoom.getDungeonRoomInfo().getUuid());
                // an unmatched room (or a route triggered before the room matcher
                // finished) has no preset to plan against - fall to the topological
                // order instead of dying inside a discarded Future with `calculating`
                // stuck true forever
                if (roomPreset == null) throw new PathfindImpossibleException("no room preset for " + name);
                exactPlanner = new RoomPresetPathPlanner(roomPreset);
                heuristicPlanner = new RoomPresetPathPlanner(roomPreset);
                try {
                    // the preset's TSP matrix may still be loading (DG-Init cache scan) or
                    // rebuilding (scheduled with a 5s delay): wait for it here, off the
                    // render thread, so the solve doesn't fall into a pathfinder build per
                    // cost callback just because loading is in progress. the clock starts
                    // after the wait so a long load doesn't burn the heuristic delay
                    exactPlanner.awaitTSPCache(TSP_CACHE_WAIT_MS, stale);
                    startNanos = System.nanoTime();
                    if (stale.getAsBoolean()) return;

                    DPTSP dptsp = new DPTSP(dag, start, dungeonRoom, exactPlanner);
                    double states = dptsp.getStateEstimate();
                    boolean exactBounded = states <= MAX_EXACT_STATES;

                    // degenerate DAG (nothing left to schedule): publish the empty route
                    // immediately, no solver threads needed
                    if (dptsp.enabledNodeCount == 0) {
                        dptsp.solve(null);
                        publish(dptsp.reconstructPath(), gen);
                        published = true;
                        return;
                    }

                    // the exact DP runs on its own executor so this thread can publish the
                    // heuristic order if the exact solve outlives the delay below. an
                    // over-cap solve - or a rejection from the bounded solver - degrades
                    // to a completed future and the heuristic route carries on
                    java.util.concurrent.CompletableFuture<List<ActionDAGNode>> exactFuture;
                    boolean exactRejected = false;
                    if (!exactBounded) {
                        exactFuture = java.util.concurrent.CompletableFuture.completedFuture(null);
                    } else {
                        java.util.concurrent.CompletableFuture<List<ActionDAGNode>> submitted;
                        final RoomPresetPathPlanner plannerForTask = exactPlanner;
                        try {
                            submitted = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                long exactDeadline = System.nanoTime() + EXACT_DEADLINE_MS * 1_000_000L;
                                java.util.function.BooleanSupplier cancelled =
                                        () -> stale.getAsBoolean() || System.nanoTime() > exactDeadline;
                                try {
                                    return dptsp.solve(cancelled) ? dptsp.reconstructPath() : null;
                                } catch (Throwable t) {
                                    return null;
                                } finally {
                                    plannerForTask.closeEvalCache();
                                }
                            }, exactSolver);
                            exactSubmitted.set(true);
                        } catch (java.util.concurrent.RejectedExecutionException saturated) {
                            // pool AND queue both full: the heuristic route carries on
                            submitted = java.util.concurrent.CompletableFuture.completedFuture(null);
                            exactRejected = true;
                        }
                        exactFuture = submitted;
                    }

                    // heuristic backstop, computed concurrently here (bounded to ~300ms):
                    // dependency-respecting order that becomes the route when the exact
                    // DP is too slow or too big
                    HeuristicTSP heuristic = null;
                    HeuristicTSP.Result heur = null;
                    try {
                        TSPCache tspCache = new TSPCache((GeneralRoomProcessor) dungeonRoom.getRoomProcessor(), dungeonRoom, Collections.EMPTY_LIST, Collections.singletonList(start));
                        heuristic = new HeuristicTSP(dag, start, dungeonRoom, tspCache, heuristicPlanner);
                        heur = heuristic.solve(stale, System.nanoTime() + HEURISTIC_BUDGET_MS * 1_000_000L);
                    } catch (Throwable t) {
                        // heuristic failure leaves the exact / topological paths to cover it
                    }
                    if (stale.getAsBoolean()) return;
                    // a heuristic that couldn't place every node, or whose order contains a step
                    // the cost matrix marks unreachable (Float.MAX_VALUE sentinels), is never shown
                    if (heur != null && (!heur.isComplete() || !(heur.getCost() < UNREACHABLE_COST))) heur = null;

                    boolean heuristicPublished = false;
                    List<ActionDAGNode> exact = null;
                    // timed gets park via ManagedBlocker (unlike Thread.sleep), and each
                    // slice re-checks `stale` so a recalculation wakes this wait instead of
                    // blocking behind a slow exact solve
                    try {
                        while (true) {
                            try {
                                exact = exactFuture.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                                break;
                            } catch (java.util.concurrent.TimeoutException slice) {
                                if (stale.getAsBoolean()) return;
                                if (heur != null && !heuristicPublished
                                        && System.nanoTime() - startNanos > HEURISTIC_DELAY_MS * 1_000_000L) {
                                    publish(heur.getOrder(), gen);
                                    heuristicPublished = true;
                                    published = true;
                                    ChatTransmitter.sendDebugChat("Smart Route :: heuristic order shown ("
                                            + heur.getOrder().size() + " actions, cost " + (long) heur.getCost()
                                            + "); exact solve still running");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        exact = null;
                    }
                    if (stale.getAsBoolean()) return;
                    if (exact != null) {
                        publish(exact, gen);
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
                        publish(heur.getOrder(), gen);
                        published = true;
                        ChatTransmitter.sendDebugChat("Smart Route :: heuristic order shown ("
                                + heur.getOrder().size() + " actions, cost " + (long) heur.getCost() + ")"
                                + (!exactBounded ? "; exact ~2^" + (int) (Math.log(states) / Math.log(2)) + " states over cap"
                                    : exactRejected ? "; exact solve rejected (solver saturated)"
                                    : "; exact solve failed"));
                    }
                } finally {
                    // a submitted exact worker closes its own cache; only a solve that
                    // never made it onto the solver pool is closed here
                    if (!exactSubmitted.get() && exactPlanner != null) exactPlanner.closeEvalCache();
                }
            } catch (Throwable t) {
                // everything above runs inside a discarded Future: any throwable left
                // unlogged is invisible, and `calculating` used to stay true forever
                t.printStackTrace();
                if (!failureLogged) {
                    failureLogged = true;
                    ChatTransmitter.addToQueue("§eDungeons Guide :: §fSmart Route :: §croute planner failed, publishing fallback order");
                }
            } finally {
                if (heuristicPlanner != null) heuristicPlanner.closeEvalCache();
                // publication (and with it `calculating`) is released on the render
                // thread; stale workers leave it to their replacement
                if (!stale.getAsBoolean() && !published) {
                    publish(firstTopologicalOrder(), gen);
                }
            }
        });
    }

    // route publication must be atomic for the render thread: it reads actions/current
    // every tick, so solver workers never write them directly. the swap happens here on
    // the render thread (queued publications apply in submission order), gated on the
    // generation so a superseded worker can't clobber a newer route
    void publish(List<ActionDAGNode> nodes, int gen) {
        kr.syeyoung.dungeonsguide.mod.utils.RenderThreadDispatcher.execute("ActionRoute.publish", () -> {
            if (pathGeneration.get() != gen) return;
            this.dagId = 0;
            this.order = nodes;
            List<AbstractAction> actionList = nodes.stream().map(ActionDAGNode::getAction).collect(Collectors.toList());
            actionList.add(new ActionComplete());
            this.actions = actionList;
            this.current = 0;
            this.calculating = false;
        });
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

    // manual stall recovery: advance past the displayed step without touching any mechanic
    // state - a skipped secret stays 'not sure' and can still complete via its own signals.
    // returns the skipped action, or null when there is nothing to skip
    public AbstractAction skipCurrentStep() {
        if (calculating || actions == null || actions.isEmpty()) return null;
        AbstractAction currentAction = getCurrentAction();
        if (currentAction instanceof ActionComplete || currentAction instanceof ActionRoot) return null;
        next();
        return currentAction;
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

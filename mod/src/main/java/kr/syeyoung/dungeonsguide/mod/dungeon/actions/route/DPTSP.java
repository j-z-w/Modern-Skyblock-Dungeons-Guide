package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class DPTSP {
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("DG-DPTSP");
    private final RoomPresetPathPlanner pathPlanner;
    private final ActionDAG dag;
    private final Vec3 start;
    private final double startX;
    private final double startY;
    private final double startZ;
    private final DungeonRoom dungeonRoom;
    private TSPCache cache;


    private ActionDAGNode[] bitNodes;
    private int[] requireIdBitMapping;

    private ActionDAGNode[][] orNodes;
    private int[] orIdIdxMapping;
    private boolean[] sanity;

    private int[] nodeType;
    private ActionDAGNode[] everyNode;

    private List<String> mechanicNames;

    private long[] require;
    private int[][] or;

    int requireBitSize;
    int stBitset;
    int enabledNodeCount; // nodes the DP can still schedule (nodeType != 0)

    int[] solution;
    private double stateEstimate;

    public DPTSP(ActionDAG dag, Vec3 start, DungeonRoom dungeonRoom) {
        this(dag, start, dungeonRoom,
                new RoomPresetPathPlanner(dungeonRoom.getContext().getPreset().getRoomPreset(dungeonRoom.getDungeonRoomInfo().getUuid())));
    }

    public DPTSP(ActionDAG dag, Vec3 start, DungeonRoom dungeonRoom, RoomPresetPathPlanner pathPlanner) {
        this.pathPlanner = pathPlanner;
        this.dag = dag;
        this.start = start;
        this.startX = start.x;
        this.startY = start.y;
        this.startZ = start.z;
        this.dungeonRoom = dungeonRoom;

        setup();
    }

    // rough upper bound of the DP table size the native coroutine will walk:
    // 2^requireBits x or-product x 2^door/oneway-mechanics. The native solver cannot
    // be killed mid-enumeration, so callers must cap this before calling solve().
    public double getStateEstimate() {
        return stateEstimate;
    }



    private RoomState roomState;

    private static volatile boolean nativeLoaded = true;

    // solve diagnostics (see SmartRouteRegressionTest): counts native->java cost
    // callbacks and the class of each returned cost. the probe can also abort a
    // runaway solve by returning false from onEval.
    public interface EvalProbe {
        boolean onEval(int node, int mech, double x, double y, double z, double cost);
    }
    public static class SolveAbortedException extends RuntimeException {}
    public static volatile EvalProbe EVAL_PROBE = null;
    public static final java.util.concurrent.atomic.AtomicLong EVAL_TOTAL = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong EVAL_FINITE = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong EVAL_IMPOSSIBLE = new java.util.concurrent.atomic.AtomicLong(); // +inf or NaN: never a viable step
    public static final java.util.concurrent.atomic.AtomicLong EVAL_HUGE = new java.util.concurrent.atomic.AtomicLong();      // finite but >= 1e9 (the 999999999 unreachable sentinel)
    public static final java.util.concurrent.atomic.AtomicLong EVAL_NANOS = new java.util.concurrent.atomic.AtomicLong();   // wall time inside evalulateCost
    // miss-mode counters: which lookup stage each eval fell through to. a healthy
    // solve is ~all matrix hits; a hang is pfres loads (each ~ms without caching).
    public static final java.util.concurrent.atomic.AtomicLong EVAL_MATRIX_HIT = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong EVAL_MATRIX_POS_MISS = new java.util.concurrent.atomic.AtomicLong();  // posOff not in locationsInCache (-1)
    public static final java.util.concurrent.atomic.AtomicLong EVAL_MATRIX_HASH_MISS = new java.util.concurrent.atomic.AtomicLong(); // hash not in matrix (-2)
    public static final java.util.concurrent.atomic.AtomicLong EVAL_MATRIX_NULL = new java.util.concurrent.atomic.AtomicLong();      // no tsp matrix for this preset
    public static final java.util.concurrent.atomic.AtomicLong EVAL_SOLVECACHE_HIT = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong EVAL_ADDTOCACHE = new java.util.concurrent.atomic.AtomicLong();       // pfres merged into per-solve cache
    public static final java.util.concurrent.atomic.AtomicLong EVAL_PFRES_LOAD = new java.util.concurrent.atomic.AtomicLong();       // loadPrecalculatedByHash calls
    public static final java.util.concurrent.atomic.AtomicLong EVAL_PRECALC_MISSING = new java.util.concurrent.atomic.AtomicLong();  // no pfres for hash at all

    // the static counters above are the regression harness's global view. concurrent
    // solvers (DPTSP on one worker, HeuristicTSP on another) race them, so per-solve
    // diagnostics read a thread-local copy - each solver thread accumulates its own.
    // thread-local means plain longs: atomics on a single-threaded structure only
    // added fence cost to every eval
    public static class EvalStats {
        public long EVAL_TOTAL;
        public long EVAL_FINITE;
        public long EVAL_IMPOSSIBLE;
        public long EVAL_HUGE;
        public long EVAL_NANOS;
        public long EVAL_MATRIX_HIT;
        public long EVAL_MATRIX_POS_MISS;
        public long EVAL_MATRIX_HASH_MISS;
        public long EVAL_MATRIX_NULL;
        public long EVAL_SOLVECACHE_HIT;
        public long EVAL_ADDTOCACHE;
        public long EVAL_PFRES_LOAD;
        public long EVAL_PRECALC_MISSING;
        public void reset() {
            EVAL_TOTAL = 0; EVAL_FINITE = 0; EVAL_IMPOSSIBLE = 0; EVAL_HUGE = 0; EVAL_NANOS = 0;
            EVAL_MATRIX_HIT = 0; EVAL_MATRIX_POS_MISS = 0; EVAL_MATRIX_HASH_MISS = 0; EVAL_MATRIX_NULL = 0;
            EVAL_SOLVECACHE_HIT = 0; EVAL_ADDTOCACHE = 0; EVAL_PFRES_LOAD = 0; EVAL_PRECALC_MISSING = 0;
        }
    }
    private static final ThreadLocal<EvalStats> STATS = ThreadLocal.withInitial(EvalStats::new);
    public static EvalStats stats() { return STATS.get(); }

    public static void resetEvalCounters() {
        stats().reset();
        EVAL_TOTAL.set(0); EVAL_FINITE.set(0); EVAL_IMPOSSIBLE.set(0); EVAL_HUGE.set(0); EVAL_NANOS.set(0);
        EVAL_MATRIX_HIT.set(0); EVAL_MATRIX_POS_MISS.set(0); EVAL_MATRIX_HASH_MISS.set(0); EVAL_MATRIX_NULL.set(0);
        EVAL_SOLVECACHE_HIT.set(0); EVAL_ADDTOCACHE.set(0); EVAL_PFRES_LOAD.set(0); EVAL_PRECALC_MISSING.set(0);
    }
    public static String evalMissSummary() {
        return "matrixHit=" + EVAL_MATRIX_HIT.get()
                + " posMiss=" + EVAL_MATRIX_POS_MISS.get()
                + " hashMiss=" + EVAL_MATRIX_HASH_MISS.get()
                + " matrixNull=" + EVAL_MATRIX_NULL.get()
                + " solveHit=" + EVAL_SOLVECACHE_HIT.get()
                + " addToCache=" + EVAL_ADDTOCACHE.get()
                + " pfresLoad=" + EVAL_PFRES_LOAD.get()
                + " precalcMissing=" + EVAL_PRECALC_MISSING.get();
    }
    public static long evalMisses() {
        return EVAL_MATRIX_POS_MISS.get() + EVAL_MATRIX_HASH_MISS.get() + EVAL_MATRIX_NULL.get();
    }

    private long solveStartNanos;
    private long lastProgressLog;
    // this solve's own counters: concurrent solvers race the statics, so outcomes are
    // read from the per-thread EvalStats captured when solve() starts
    private EvalStats evalStats;

    private void diagProgress() {
        long now = System.nanoTime();
        if (now - lastProgressLog < 10_000_000_000L) return;
        lastProgressLog = now;
        LOGGER.warn("DPTSP progress: callbacks={} elapsed={}s finite={} impossible={} huge={} {}",
                evalStats.EVAL_TOTAL, (now - solveStartNanos) / 1_000_000_000.0,
                evalStats.EVAL_FINITE, evalStats.EVAL_IMPOSSIBLE, evalStats.EVAL_HUGE, evalMissSummary(evalStats));
    }

    private static String evalMissSummary(EvalStats s) {
        return "matrixHit=" + s.EVAL_MATRIX_HIT
                + " posMiss=" + s.EVAL_MATRIX_POS_MISS
                + " hashMiss=" + s.EVAL_MATRIX_HASH_MISS
                + " matrixNull=" + s.EVAL_MATRIX_NULL
                + " solveHit=" + s.EVAL_SOLVECACHE_HIT
                + " addToCache=" + s.EVAL_ADDTOCACHE
                + " pfresLoad=" + s.EVAL_PFRES_LOAD
                + " precalcMissing=" + s.EVAL_PRECALC_MISSING;
    }

    // concise per-solve line: WARN when the solve was slow (>2s), aborted, or hit a real
    // lookup failure (no matrix, hash miss, pfres fallback). position misses covered by
    // the per-solve cache are normal - every solve's start pos is absent from the matrix
    // (mechanic state isn't dumped here: isFound() reads render-thread structures and
    // this runs on a solver worker)
    private void logSolveOutcome(boolean aborted) {
        long elapsedMs = (System.nanoTime() - solveStartNanos) / 1_000_000;
        boolean bad = aborted || elapsedMs > 2000
                || evalStats.EVAL_PFRES_LOAD > 0
                || evalStats.EVAL_MATRIX_NULL > 0
                || evalStats.EVAL_MATRIX_HASH_MISS > 0;
        String msg = "DPTSP " + (aborted ? "aborted" : "done") + ": callbacks=" + evalStats.EVAL_TOTAL
                + " ms=" + elapsedMs + " javaEvalMs=" + evalStats.EVAL_NANOS / 1_000_000
                + " " + evalMissSummary(evalStats)
                + " nodes=" + everyNode.length + " requireBits=" + requireBitSize
                + " orGroups=" + orNodes.length + " doors=" + mechanicNames.size()
                + " stBitset=" + stBitset
                + " order=" + (solution == null ? "null" : java.util.Arrays.toString(solution));
        if (bad) {
            LOGGER.warn("{}", msg);
        } else {
            LOGGER.debug("{}", msg);
        }
    }

//
//    // jni requires.
//    public EvalRes evaluate(double x, double y, double z, int mechanic, int node) {
//        roomState.setPlayerPos(new Vec3(x, y, z));
//        roomState.setOpenMechanicsBitset(mechanic);
//        double cost = everyNode[node].getAction().evalulateCost(roomState, dungeonRoom, cache, pathPlanner);
//        return new EvalRes(roomState.getPlayerPos().xCoord, roomState.getPlayerPos().yCoord, roomState.getPlayerPos().zCoord, roomState.openMechanicsBitset, cost);
//    }
//
//    @AllArgsConstructor
//    public static class EvalRes {
//        public double x;
//        public double y;
//        public double z;
//        public int mechanic;
//        public double cost;
//    }

    public boolean solve() {
        return solve(null);
    }

    /**
     * Runs the native coroutine DP. The solver only returns control to Java through
     * these callbacks, so `cancelled` is polled once per request - good enough to
     * stop stale solves, but it cannot preempt a long internal enumeration. Callers
     * must therefore also check {@link #getStateEstimate()} before calling this.
     *
     * @return true if an exact solution was computed and stored in {@link #solution}
     */
    public boolean solve(java.util.function.BooleanSupplier cancelled) {

        // degenerate DAGs: nothing left to schedule (empty DAG - root only - or
        // everything already complete). the native coroutine yields garbage node
        // ids for these (live log: getNode() = 66178/620 on a 1-node DAG ->
        // ArrayIndexOutOfBounds in this loop), and the answer is trivial anyway.
        if (enabledNodeCount == 0) {
            solution = new int[0];
            return true;
        }

        if (nativeLoaded) {
            boolean aborted = false;
            try {
                resetEvalCounters();
                // clock and counters start with the solve, not the constructor: ActionRoute
                // builds the DPTSP then hands it to a worker, so ctor-time stats inflated
                // the reported wall time and raced the still-running heuristic evals
                solveStartNanos = System.nanoTime();
                lastProgressLog = solveStartNanos;
                evalStats = stats();
                long handle = startCoroutine();
                if (handle == 0) {
                    // never pass a failed handle to the other native calls
                    return false;
                }
                try {
                    while (true) {
                        roomState.setPlayerPos(getX(handle), getY(handle), getZ(handle)); // no Vec3 alloc per callback
                        roomState.setOpenMechanicsBitset(getMech(handle));
                        int node = getNode(handle);
                        if (node < 0 || node >= everyNode.length) {
                            // the native coroutine produced an out-of-range node id -
                            // its state is corrupt for this DAG (seen on 1-node DAGs);
                            // fail loudly so the caller can fall back, never index with it
                            throw new IllegalStateException("DPTSP native returned node " + node
                                    + " for " + everyNode.length + "-node DAG");
                        }
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            aborted = true;
                            solution = null;
                            return false;
                        }
                        long t0 = System.nanoTime();
                        double cost = everyNode[node].getAction().evalulateCost(roomState, dungeonRoom, cache, pathPlanner);
                        long evalNanos = System.nanoTime() - t0;
                        EVAL_NANOS.addAndGet(evalNanos);
                        EVAL_TOTAL.incrementAndGet();
                        // the thread-local copy is what logSolveOutcome/diagProgress
                        // report - it used to stay zero because only the statics moved
                        evalStats.EVAL_NANOS += evalNanos;
                        evalStats.EVAL_TOTAL++;
                        if (Double.isNaN(cost) || cost == Double.POSITIVE_INFINITY) {
                            EVAL_IMPOSSIBLE.incrementAndGet();
                            evalStats.EVAL_IMPOSSIBLE++;
                        } else if (cost >= 1e9) {
                            EVAL_HUGE.incrementAndGet();
                            evalStats.EVAL_HUGE++;
                        } else {
                            EVAL_FINITE.incrementAndGet();
                            evalStats.EVAL_FINITE++;
                        }
                        EvalProbe probe = EVAL_PROBE;
                        if (probe != null && !probe.onEval(node, roomState.openMechanicsBitset,
                                roomState.getPx(), roomState.getPy(), roomState.getPz(), cost)) {
                            aborted = true;
                            throw new SolveAbortedException();
                        }
                        diagProgress();
                        boolean res = resumeCoroutine(handle, roomState.getPx(), roomState.getPy(), roomState.getPz(), roomState.openMechanicsBitset, cost);
                        if (!res) break;
                    }
                    solution = getResult(handle, dag.getActionDAGNode().getId());
                    return solution != null;
                } finally {
                    destoryCoroutine(handle);
                    logSolveOutcome(aborted);
                }
            } catch (UnsatisfiedLinkError e) {
                ChatTransmitter.addToQueue("§eDungeons Guide :: §fTSP Path Planner :: §cNative Library is not loaded. Falling back to old planners");
                nativeLoaded = false;
                throw e;
            }
        } else {
            ChatTransmitter.addToQueue("§eDungeons Guide :: §fTSP Path Planner :: §cNative Library is not loaded. Falling back to old planners");
            return false;
        }
    }
/*
* 6dcddc04-c094-4b9b-8c05-b101e8f3dd27::18.0,20.0,24.5;18.0,20.0,25.0;18.5,20.0,23.5;18.5,20.0,24.0;18.5,20.0,24.5;18.5,20.0,25.0;19.0,20.0,23.5;19.0,20.0,24.0;19.0,20.0,24.5;19.0,21.0,25.0;19.5,20.0,23.5;19.5,20.0,24.0;19.5,20.0,24.5;19.5,21.0,25.0;19.5,21.0,25.5
* 6dcddc04-c094-4b9b-8c05-b101e8f3dd27::18.0,20.0,26.0;18.0,20.0,26.5;18.5,20.0,25.5;18.5,20.0,26.0;18.5,20.0,26.5;18.5,20.0,27.0;18.5,20.0,27.5;18.5,20.0,28.0;19.0,21.0,25.5;19.0,21.0,26.0;19.0,20.0,26.5;19.0,20.0,27.0;19.0,20.0,27.5;19.5,21.0,26.0;19.5,20.0,26.5;19.5,20.0,27.0;19.5,20.0,27.5
*
* 6dcddc04-c094-4b9b-8c05-b101e8f3dd27::18.0,20.0,24.5;18.0,20.0,25.0;18.5,20.0,23.5;18.5,20.0,24.0;18.5,20.0,24.5;18.5,20.0,25.0;18.5,20.0,25.5;19.0,20.0,23.5;19.0,20.0,24.0;19.0,20.0,24.5;19.0,21.0,25.0;19.0,21.0,25.5;19.5,20.0,23.5;19.5,20.0,24.0;19.5,20.0,24.5;19.5,21.0,25.0
* 6dcddc04-c094-4b9b-8c05-b101e8f3dd27::18.0,20.0,26.0;18.0,20.0,26.5;18.5,20.0,26.0;18.5,20.0,26.5;18.5,20.0,27.0;18.5,20.0,27.5;18.5,20.0,28.0;19.0,21.0,26.0;19.0,20.0,26.5;19.0,20.0,27.0;19.0,20.0,27.5;19.5,21.0,25.5;19.5,21.0,26.0;19.5,20.0,26.5;19.5,20.0,27.0;19.5,20.0,27.5
*
* */
    private void setup() {
        List<ActionDAGNode> dagNodeList = new ArrayList<>();
        int[] nodeStatus = dag.getNodeStatusAll();


        requireIdBitMapping = new int[dag.getAllNodes().size()];
        orIdIdxMapping = new int[dag.getAllNodes().size()];
        nodeType = new int[dag.getAllNodes().size()];
        require = new long[dag.getAllNodes().size()];
        or = new int[dag.getAllNodes().size()][];
        sanity = new boolean[dag.getAllNodes().size()];


        label: for (int i = 0; i < dag.getAllNodes().size(); i++) {
            ActionDAGNode node = dag.getAllNodes().get(i);
            for (ActionDAGNode actionDAGNode : node.getRequiredBy()) {
                if (actionDAGNode.getOr().isEmpty()) continue;
                continue label;
            } // ignore optional.

            nodeType[node.getId()] = 1;
            requireIdBitMapping[node.getId()] = dagNodeList.size();
            dagNodeList.add(node);
        }
        bitNodes = dagNodeList.toArray(new ActionDAGNode[0]);
        requireBitSize = bitNodes.length;

        long mult = 1;
        List<ActionDAGNode[]> orNodes = new ArrayList<>();
        for (ActionDAGNode allNode : dag.getAllNodes()) {
            if (allNode.getOr().isEmpty()) continue;
            ActionDAGNode[] ornode = new ActionDAGNode[allNode.getOr().size()+1];
            for (int i = 0; i < allNode.getOr().size(); i++) {
                orIdIdxMapping[allNode.getOr().get(i).getId()] = i+1;
                ornode[i+1] = allNode.getOr().get(i);
                nodeType[allNode.getOr().get(i).getId()] = orNodes.size() + 2;
            }
            orNodes.add(ornode);
            mult *= ornode.length;
        }
        this.orNodes = orNodes.toArray(new ActionDAGNode[0][]);



        mechanicNames = dungeonRoom.getMechanics().entrySet().stream().filter(a -> a.getValue() instanceof DungeonDoorState || a.getValue() instanceof DungeonOnewayDoorState)
                .map(a -> a.getKey()).collect(Collectors.toList());

        int bitset = 0;
        for (int i = 0; i < mechanicNames.size(); i++) {
            String mechanicName = mechanicNames.get(i);
            if (!((WorldMutatingMechanicState)dungeonRoom.getMechanics().get(mechanicName)).isBlocking(dungeonRoom)) {
                bitset |= 1 << i;
            }
        }
        stBitset = bitset;

        for (int i = 0; i < nodeStatus.length; i++) {
            if (nodeStatus[i] == 1 || nodeStatus[i] == 2)
                nodeType[i] = 0;
        }

        int enabled = 0;
        for (int t : nodeType) {
            if (t != 0) enabled++;
        }
        enabledNodeCount = enabled;

        everyNode = dag.getAllNodes().toArray(new ActionDAGNode[0]);
        for (int i = 0; i < everyNode.length; i++) {
            require[i] = 0;
            for (int j = 0; j < everyNode[i].getRequire().size(); j++) {
                if (nodeType[everyNode[i].getRequire().get(j).getId()] != 1) continue;
                require[i] |= (1L << requireIdBitMapping[everyNode[i].getRequire().get(j).getId()]);
            }
            or[i] = new int[everyNode[i].getOr().size()];
            for (int j = 0; j < everyNode[i].getOr().size(); j++) {
                or[i][j] = everyNode[i].getOr().get(j).getId();
            }
            sanity[i] = everyNode[i].getAction().isSanityCheck();
        }

        roomState = new RoomState(mechanicNames);
        roomState.setDungeonRoom(dungeonRoom);
        cache = new TSPCache((GeneralRoomProcessor) dungeonRoom.getRoomProcessor(), dungeonRoom, Collections.EMPTY_LIST, Collections.singletonList(start));

        stateEstimate = Math.scalb(1.0, requireBitSize) * mult * Math.scalb(1.0, mechanicNames.size());
    }

    private native long startCoroutine();
    private native boolean resumeCoroutine(long handle, double x, double y, double z, int mech, double cost);
    private native double getX(long handle);
    private native double getY(long handle);
    private native double getZ(long handle);
    private native int getNode(long handle);
    private native int getMech(long handle);
    private native int[] getResult(long handle, int target);
    private native void destoryCoroutine(long handle);



    public List<ActionDAGNode> reconstructPath() {
        List<ActionDAGNode> nodes = new ArrayList<>();
        if (solution == null) return nodes;
        for (int i : solution) {
            if (i < 0 || i >= everyNode.length) continue; // garbage ids like the corrupt-DAG case
            nodes.add(everyNode[i]);
        }
        return nodes;
    }




}

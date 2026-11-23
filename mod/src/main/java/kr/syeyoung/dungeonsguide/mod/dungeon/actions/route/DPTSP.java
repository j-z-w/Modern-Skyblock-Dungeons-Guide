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

    int[] solution;

    public DPTSP(ActionDAG dag, Vec3 start, DungeonRoom dungeonRoom) {
        this.pathPlanner = new RoomPresetPathPlanner(dungeonRoom.getContext().getPreset().getRoomPreset(dungeonRoom.getDungeonRoomInfo().getUuid()));
        this.dag = dag;
        this.start = start;
        this.startX = start.x;
        this.startY = start.y;
        this.startZ = start.z;
        this.dungeonRoom = dungeonRoom;

        setup();
        solve();
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
    public static void resetEvalCounters() {
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

    // one line of door + secret-mechanic states, appended to the slow/missy solve
    // log so an in-game hang leaves its repro in the log
    private String mechanicSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mechanicNames.size(); i++) {
            String name = mechanicNames.get(i);
            kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState mech =
                    dungeonRoom.getMechanics().get(name);
            sb.append(' ').append(name).append('=')
                    .append(((WorldMutatingMechanicState) mech).isBlocking(dungeonRoom) ? "blocking" : "open");
        }
        int secrets = 0, found = 0;
        for (Map.Entry<String, kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState> e
                : dungeonRoom.getMechanics().entrySet()) {
            if (e.getValue() instanceof kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret) {
                secrets++;
                if (((kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret) e.getValue()).isFound(dungeonRoom)) found++;
            }
        }
        sb.append(" secrets=").append(found).append('/').append(secrets);
        return sb.toString();
    }

    private final long solveStartNanos = System.nanoTime();
    private long lastProgressLog = solveStartNanos;

    private void diagProgress() {
        long now = System.nanoTime();
        if (now - lastProgressLog < 10_000_000_000L) return;
        lastProgressLog = now;
        LOGGER.warn("DPTSP progress: callbacks={} elapsed={}s finite={} impossible={} huge={} {}",
                EVAL_TOTAL.get(), (now - solveStartNanos) / 1_000_000_000.0,
                EVAL_FINITE.get(), EVAL_IMPOSSIBLE.get(), EVAL_HUGE.get(), evalMissSummary());
    }

    // concise per-solve line: WARN when the solve was slow (>2s) or hit any matrix
    // miss (the in-game hang signatures), DEBUG otherwise
    private void logSolveOutcome(boolean aborted) {
        long elapsedMs = (System.nanoTime() - solveStartNanos) / 1_000_000;
        boolean bad = aborted || elapsedMs > 2000 || evalMisses() > 0;
        String msg = "DPTSP " + (aborted ? "aborted" : "done") + ": callbacks=" + EVAL_TOTAL.get()
                + " ms=" + elapsedMs + " javaEvalMs=" + EVAL_NANOS.get() / 1_000_000
                + " " + evalMissSummary()
                + " nodes=" + everyNode.length + " requireBits=" + requireBitSize
                + " orGroups=" + orNodes.length + " doors=" + mechanicNames.size()
                + " stBitset=" + stBitset
                + " order=" + (solution == null ? "null" : java.util.Arrays.toString(solution));
        if (bad) {
            LOGGER.warn("{} mech:{}", msg, mechanicSummary());
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

    public void solve() {

        if (nativeLoaded) {
            boolean aborted = false;
            try {
                resetEvalCounters();
                long handle = startCoroutine();
                try {
                    while (true) {
                        roomState.setPlayerPos(new Vec3(getX(handle), getY(handle), getZ(handle)));
                        roomState.setOpenMechanicsBitset(getMech(handle));
                        int node = getNode(handle);
                        long t0 = System.nanoTime();
                        double cost = everyNode[node].getAction().evalulateCost(roomState, dungeonRoom, cache, pathPlanner);
                        EVAL_NANOS.addAndGet(System.nanoTime() - t0);
                        EVAL_TOTAL.incrementAndGet();
                        if (Double.isNaN(cost) || cost == Double.POSITIVE_INFINITY) EVAL_IMPOSSIBLE.incrementAndGet();
                        else if (cost >= 1e9) EVAL_HUGE.incrementAndGet();
                        else EVAL_FINITE.incrementAndGet();
                        EvalProbe probe = EVAL_PROBE;
                        if (probe != null && !probe.onEval(node, roomState.openMechanicsBitset,
                                roomState.getPlayerPos().x, roomState.getPlayerPos().y, roomState.getPlayerPos().z, cost)) {
                            aborted = true;
                            throw new SolveAbortedException();
                        }
                        diagProgress();
                        boolean res = resumeCoroutine(handle, roomState.getPlayerPos().x, roomState.getPlayerPos().y, roomState.getPlayerPos().z, roomState.openMechanicsBitset, cost);
                        if (!res) break;
                    }
                    solution = getResult(handle, dag.getActionDAGNode().getId());
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
            throw new RuntimeException("");
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
        for (int i : solution) {
            nodes.add(everyNode[i]);
        }
        return nodes;
    }




}

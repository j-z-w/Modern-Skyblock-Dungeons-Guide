package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.HeuristicTSP;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

// stuck-route sweep (opt-in, -Pdg.routeSweep): for every bundled room, build the
// Smart Route DAG FeatureSmartRoute would build - in the initial world and in
// plausible variant states (each door open, each secret found, each redstone-key
// variant spawned, all doors open, all secrets found) - and assert, per variant:
//   1. every requires() a real run would attempt is buildable (no swallowed
//      PathfindImpossibleException dropping a secret into a dangling edge)
//   2. a bounded heuristic solve places every enabled node (the route is solvable)
//   3. every ActionChangeState node asks for a state the mechanic can actually
//      reach in that world (target == current, or in getAvailableActions(), or a
//      pseudo-state); an unreachable target means the route soft-locks there
//   4. every move action's target set has a precalculation in the room preset
//      (no precalc -> Infinity step cost -> the ordering silently reroutes or stalls)
//   5. every action type in the solved order has a renderer in both display engines
//
// knobs: -Ddg.routeSweep.rooms=<name fragments,comma separated>
//        -Ddg.routeSweep.deadlineMs=<heuristic budget per variant, default 4000>
//        -Ddg.routeSweep.maxVariants=<cap per room, default 0 = all>
public class RouteSweepTest {
    private static final double UNREACHABLE_COST = Float.MAX_VALUE / 2;
    // same bound ActionRoute applies before handing the DAG to the native DP
    private static final double MAX_EXACT_STATES =
            Double.parseDouble(System.getProperty("dg.smartRoute.maxExactStates", "6.8719476736E10"));

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(System.getProperty("dg.routeSweep") != null,
                "opt-in sweep; pass -Pdg.routeSweep to run");
        TestRooms.boot();
        kr.syeyoung.dungeonsguide.mod.NativeLoader.extractLibraryAndLoad("dptsp");
    }

    // ---------------- world forcing (same semantics as SmartRouteRegressionTest) ----------------

    private static class ForcedWorld implements DGWorld {
        private final DGWorld delegate;
        private final Set<BlockPos> airOverride = new HashSet<>();
        private final Map<BlockPos, BlockState> blockOverride = new HashMap<>();

        ForcedWorld(DGWorld delegate) { this.delegate = delegate; }

        public BlockState getBlock(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            if (airOverride.contains(p)) return Blocks.AIR.defaultBlockState();
            BlockState s = blockOverride.get(p);
            return s != null ? s : delegate.getBlock(x, y, z);
        }
        public boolean isInScope(int x, int y, int z) { return delegate.isInScope(x, y, z); }
        public int getMinX() { return delegate.getMinX(); }
        public int getMinY() { return delegate.getMinY(); }
        public int getMinZ() { return delegate.getMinZ(); }
        public int getMaxX() { return delegate.getMaxX(); }
        public int getMaxY() { return delegate.getMaxY(); }
        public int getMaxZ() { return delegate.getMaxZ(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return delegate.getBlockEntity(pos); }
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private interface Variant { String label(); void apply(DungeonRoom room, ForcedWorld forced) throws Exception; }

    // door forced open = its blocked points read air; forced closed = iron
    private static Variant doorVariant(String name, boolean open) {
        return new Variant() {
            public String label() { return "door:" + name + "=" + (open ? "open" : "closed"); }
            public void apply(DungeonRoom room, ForcedWorld forced) {
                DungeonMechanicState m = room.getMechanics().get(name);
                if (!(m instanceof WorldMutatingMechanicState)) return;
                for (OffsetPoint pt : ((WorldMutatingMechanicState) m).blockedPoints()) {
                    BlockPos bp = pt.getBlockPos(room);
                    if (open) forced.airOverride.add(bp);
                    else forced.blockOverride.put(bp, Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
        };
    }

    private static void forceFound(DungeonMechanicState m) {
        if (m instanceof DungeonSecretItemDropState) {
            ((DungeonSecretItemDropState) m).setStatus(DungeonSecretItemDropState.SecretStatus.FOUND);
            ((DungeonSecretItemDropState) m).setAbsolutelyFound(true);
        } else if (m instanceof DungeonSecretBatState) {
            ((DungeonSecretBatState) m).setDidKillBat(true);
        } else if (m instanceof DungeonSecretEssenceState) {
            ((DungeonSecretEssenceState) m).setEssenceWasThere(true);
            ((DungeonSecretEssenceState) m).setFound(true);
        } else if (m instanceof DungeonSecretChestState) {
            ((DungeonSecretChestState) m).markFound();
        } else if (m instanceof DungeonSecretDoubleChestState) {
            ((DungeonSecretDoubleChestState) m).markFound();
        }
    }

    private static Variant foundVariant(String name, Set<String> names) {
        return new Variant() {
            public String label() { return name; }
            public void apply(DungeonRoom room, ForcedWorld forced) {
                for (String n : names) forceFound(room.getMechanics().get(n));
            }
        };
    }

    // a key-variant choice: the skull stands at this spawn point and nowhere else
    // in the group (every sibling sharing the slot reads 'not-spawned')
    private static Variant keyVariant(String chosenName, List<String> groupNames) {
        return new Variant() {
            public String label() { return "key:" + chosenName; }
            public void apply(DungeonRoom room, ForcedWorld forced) {
                for (String n : groupNames) {
                    DungeonMechanicState m = room.getMechanics().get(n);
                    if (!(m instanceof DungeonRedstoneKeyState)) continue;
                    BlockPos bp = ((DungeonRedstoneKeyState) m).getData().getSecretPoint().getBlockPos(room);
                    if (n.equals(chosenName))
                        forced.blockOverride.put(bp, Blocks.SKELETON_SKULL.defaultBlockState());
                    else forced.airOverride.add(bp);
                }
            }
        };
    }

    // ---------------- the Smart Route DAG, exactly as FeatureSmartRoute builds it ----------------

    private static class DagBuild {
        ActionDAG dag;
        List<String> requiresFailures = new ArrayList<>();
    }

    private static DagBuild buildSmartRouteDAG(DungeonRoom room, AlgorithmSetting setting) {
        DagBuild out = new DagBuild();
        ActionDAGBuilder builder = new ActionDAGBuilder(room);
        for (Map.Entry<String, DungeonMechanicState> value : room.getMechanics().entrySet()) {
            if (value.getValue() instanceof ISecret && !((ISecret) value.getValue()).isFound(room)) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "found"), setting);
                } catch (PathfindImpossibleException e) {
                    out.requiresFailures.add(value.getKey() + ":found -> " + e.getMessage());
                }
            } else if (value.getValue() instanceof DungeonRedstoneKeyState
                    && value.getValue().getCurrentState().equalsIgnoreCase("unobtained")) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "obtained-self"), setting);
                } catch (PathfindImpossibleException e) {
                    out.requiresFailures.add(value.getKey() + ":obtained-self -> " + e.getMessage());
                }
            }
        }
        out.dag = builder.build();
        return out;
    }

    // ---------------- per-action checks ----------------

    // action types with a render branch in both NeoRouteDisplayEngine and
    // ClassicPathDisplayEngine; a solved step of any other class guides nothing
    private static boolean hasRenderer(AbstractAction a) {
        return a instanceof AbstractActionMove || a instanceof ActionClick
                || a instanceof ActionClickSet || a instanceof ActionStonkClick
                || a instanceof ActionKill || a instanceof ActionInteract
                || a instanceof ActionDropItem || a instanceof ActionBreakWithSuperBoom
                || a instanceof ActionChangeState || a instanceof AtomicAction
                || a instanceof ActionRoot || a instanceof ActionTeleport;
    }

    private static final Set<String> PSEUDO_STATES = new HashSet<>(Arrays.asList("navigate", "click", "dummy"));

    private static void checkChangeState(String label, ActionDAGNode node, ActionChangeState cs,
                                         DungeonRoom room, List<String> failures, boolean inOrder) {
        String mechName = cs.getMechanicName();
        String target = cs.getState();
        if (PSEUDO_STATES.contains(target.toLowerCase(Locale.ROOT))) return;
        DungeonMechanicState mech = room.getMechanics().get(mechName);
        if (mech == null) {
            failures.add(tag(label, node, inOrder) + "ChangeState to missing mechanic '" + mechName + "' (state " + target + "): node can never complete");
            return;
        }
        String current;
        try {
            current = mech.getCurrentState();
        } catch (Throwable t) {
            failures.add(tag(label, node, inOrder) + mechName + " getCurrentState threw " + t);
            return;
        }
        if (target.equalsIgnoreCase(current)) return;
        Set<String> available;
        try {
            available = mech.getAvailableActions();
        } catch (Throwable t) {
            failures.add(tag(label, node, inOrder) + mechName + " getAvailableActions threw " + t);
            return;
        }
        boolean reachable = available.stream().anyMatch(s -> s.equalsIgnoreCase(target));
        if (!reachable) {
            boolean bare = node.getRequire().isEmpty() && node.getOr().isEmpty() && node.getOptional().isEmpty();
            failures.add(tag(label, node, inOrder)
                    + "ChangeState(" + mechName + " -> " + target + ") unreachable: "
                    + "current=" + current + " available=" + available + (bare ? " [bare node: no guiding children]" : ""));
        }
    }

    private static String tag(String label, ActionDAGNode node, boolean inOrder) {
        return "[" + label + (inOrder ? " SOLVED" : "") + "] #" + node.getId() + " ";
    }

    private static void checkAction(String label, ActionDAGNode node, AbstractAction a,
                                    DungeonRoom room, RoomPresetPathPlanner planner,
                                    List<String> failures, boolean inOrder) {
        if (!hasRenderer(a)) {
            failures.add(tag(label, node, inOrder) + "no renderer for " + a.getClass().getSimpleName());
        }
        if (a instanceof ActionChangeState) {
            checkChangeState(label, node, (ActionChangeState) a, room, failures, inOrder);
        }
        if (a instanceof AtomicAction) {
            for (AbstractAction inner : ((AtomicAction) a).getActions())
                checkAction(label, node, inner, room, planner, failures, inOrder);
            return;
        }
        if (a instanceof AbstractActionMove) {
            try {
                String hash = ((AbstractActionMove) a).getPathfindRequest(room).getHash();
                if (planner.getPrecalcByHash(hash) == null)
                    failures.add(tag(label, node, inOrder) + "move target has no precalc: " + a.toString().replace('\n', ' '));
            } catch (Throwable t) {
                failures.add(tag(label, node, inOrder) + "precalc check threw " + t);
            }
        }
    }

    // replay a solved order one node at a time to find which step the TSP model
    // prices as unreachable. Double.POSITIVE_INFINITY = no precalculation exists
    // for the move's target set under that door state (a coverage hole the route
    // cannot recover from in-sim) -> hard failure. the matrix's Float.MAX_VALUE
    // sentinel = "unreachable from here under these doors" - usually a secret
    // behind a wall whose door action is only an OPTIONAL prerequisite, so the
    // solver legally places it before the door opens; degraded, not stuck -> warning.
    private static void diagnoseInfSteps(String label, DungeonRoom room, RoomPresetPathPlanner planner,
                                         TSPCache tspCache, List<ActionDAGNode> order, Vec3 start,
                                         List<String> failures, List<String> warnings) {
        List<String> mechNames = HeuristicTSP.doorMechanics(room);
        kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.RoomState rs =
                new kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.RoomState(mechNames);
        rs.setDungeonRoom(room);
        rs.setPlayerPos(start);
        rs.openMechanicsBitset = HeuristicTSP.initialBitset(room, mechNames);
        for (ActionDAGNode node : order) {
            AbstractAction a = node.getAction();
            List<AbstractAction> children = a instanceof AtomicAction
                    ? ((AtomicAction) a).getActions() : Collections.singletonList(a);
            for (AbstractAction child : children) {
                double c;
                try {
                    c = child.evalulateCost(rs, room, tspCache, planner);
                } catch (Throwable t) {
                    failures.add("[" + label + " DIAG] #" + node.getId() + " "
                            + child.toString().split("\n")[0] + " eval threw " + t
                            + " openDoors=" + rs.openMechanicsBitset);
                    continue;
                }
                if (c == Double.POSITIVE_INFINITY) {
                    String hashInfo = "";
                    if (child instanceof AbstractActionMove) {
                        try {
                            hashInfo = " hash=" + ((AbstractActionMove) child).getPathfindRequest(room).getHash();
                        } catch (Throwable ignored) {}
                    }
                    failures.add("[" + label + " DIAG] step #" + node.getId() + " "
                            + child.toString().split("\n")[0] + " has no precalc for this door state"
                            + " openDoors=" + rs.openMechanicsBitset + hashInfo);
                } else if (!(c < 1e8)) {
                    warnings.add("[" + label + " DIAG] step #" + node.getId() + " "
                            + child.toString().split("\n")[0] + " matrix-unreachable cost=" + (long) c
                            + " openDoors=" + rs.openMechanicsBitset);
                }
            }
        }
    }

    private static Vec3 pickStart(DungeonRoom room, ActionDAG dag) {
        for (ActionDAGNode node : dag.getAllNodes()) {
            AbstractAction a = node.getAction();
            if (a instanceof AbstractActionMove)
                return ((AbstractActionMove) a).getTargetVec3().getPos(room);
            if (a instanceof AtomicAction)
                for (AbstractAction inner : ((AtomicAction) a).getActions())
                    if (inner instanceof AbstractActionMove)
                        return ((AbstractActionMove) inner).getTargetVec3().getPos(room);
        }
        return Vec3.ZERO;
    }

    // ---------------- variants ----------------

    private static List<Variant> variantsFor(DungeonRoom probe) {
        List<Variant> out = new ArrayList<>();
        out.add(new Variant() {
            public String label() { return "base"; }
            public void apply(DungeonRoom room, ForcedWorld forced) {}
        });
        List<String> doors = new ArrayList<>();
        List<String> secrets = new ArrayList<>();
        Map<String, List<String>> keyGroups = new LinkedHashMap<>();
        for (Map.Entry<String, DungeonMechanicState> e : probe.getMechanics().entrySet()) {
            if (e.getValue() instanceof WorldMutatingMechanicState) doors.add(e.getKey());
            if (e.getValue() instanceof ISecret) secrets.add(e.getKey());
            if (e.getValue() instanceof DungeonRedstoneKeyState) {
                String trig = ((DungeonRedstoneKeyState) e.getValue()).getData().getTriggering();
                keyGroups.computeIfAbsent(trig, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        out.add(new Variant() {
            public String label() { return "doors:all-open"; }
            public void apply(DungeonRoom room, ForcedWorld forced) throws Exception {
                for (String d : doors) doorVariant(d, true).apply(room, forced);
            }
        });
        for (String d : doors) out.add(doorVariant(d, true));
        for (String s : secrets) out.add(foundVariant("found:" + s, Collections.singleton(s)));
        out.add(foundVariant("found:all", new LinkedHashSet<>(secrets)));
        for (Map.Entry<String, List<String>> g : keyGroups.entrySet()) {
            if (g.getValue().size() < 2) continue;
            for (String key : g.getValue()) out.add(keyVariant(key, g.getValue()));
        }
        return out;
    }

    // ---------------- the sweep ----------------

    @Test
    void sweepAllRooms() throws Exception {
        String roomsProp = System.getProperty("dg.routeSweep.rooms");
        Set<String> roomFilter = roomsProp == null ? null
                : new HashSet<>(Arrays.asList(roomsProp.toLowerCase(Locale.ROOT).split(",")));
        long deadlineMs = Long.parseLong(System.getProperty("dg.routeSweep.deadlineMs", "4000"));
        int maxVariants = Integer.parseInt(System.getProperty("dg.routeSweep.maxVariants", "0"));

        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int roomsTested = 0, variantCount = 0;

        for (RoomPreset roomPreset : TestRooms.defaultPreset().getPresets().values()) {
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null || info.getTotalSecrets() == 0) continue;
            if (roomFilter != null && roomFilter.stream().noneMatch(f -> info.getName().toLowerCase(Locale.ROOT).contains(f)))
                continue;
            roomsTested++;

            // a probe room just to enumerate mechanics for the variant list
            DungeonRoom probe = TestRooms.buildRoom(roomPreset, info);
            List<Variant> variants = variantsFor(probe);
            if (maxVariants > 0 && variants.size() > maxVariants)
                variants = variants.subList(0, maxVariants);

            AlgorithmSetting setting = roomPreset.getEffectiveAlgorithmSetting(info);
            for (Variant v : variants) {
                variantCount++;
                String label = info.getName() + "/" + v.label();
                DungeonRoom room = TestRooms.buildRoom(roomPreset, info);
                try {
                    ForcedWorld forced = new ForcedWorld(room.getCachedWorld());
                    v.apply(room, forced);
                    if (!forced.airOverride.isEmpty() || !forced.blockOverride.isEmpty())
                        setField(room, "cachedWorld", forced);

                    DagBuild build = buildSmartRouteDAG(room, setting);
                    for (String f : build.requiresFailures)
                        failures.add("[" + label + "] requires() swallowed: " + f);

                    // every mechanic's reported state must be one it declares possible;
                    // "undeterminable" means a lever/plate's 'triggering' name does not
                    // resolve, so the mechanic (and anything requiring it) is dead weight.
                    // base variant only: these properties don't vary with forced state
                    if ("base".equals(v.label())) {
                        for (Map.Entry<String, DungeonMechanicState> e : room.getMechanics().entrySet()) {
                            DungeonMechanicState m = e.getValue();
                            String cur;
                            try {
                                cur = m.getCurrentState();
                            } catch (Throwable t) {
                                if (net.minecraft.client.Minecraft.getInstance() == null
                                        || net.minecraft.client.Minecraft.getInstance().player == null)
                                    warnings.add("[" + label + "] " + e.getKey() + " getCurrentState is client-dependent, skipped (" + m.getClass().getSimpleName() + ")");
                                else
                                    failures.add("[" + label + "] " + e.getKey() + " getCurrentState threw " + t);
                                continue;
                            }
                            if (cur == null) continue;
                            if ("undeterminable".equalsIgnoreCase(cur))
                                warnings.add("[" + label + "] " + e.getKey() + " is undeterminable - its 'triggering' name does not resolve (" + m.getClass().getSimpleName() + ")");
                            Set<String> declared = m.getTotalPossibleStates();
                            if (declared != null && !declared.contains(cur))
                                warnings.add("[" + label + "] " + e.getKey() + " reports '" + cur + "' outside declared states " + declared
                                        + " (" + m.getClass().getSimpleName() + ")");
                        }
                    }

                    RoomPresetPathPlanner planner = new RoomPresetPathPlanner(roomPreset);
                    try {
                        // node-level checks over every DAG node; solved-order nodes get
                        // the same checks again tagged SOLVED for the report
                        for (ActionDAGNode node : build.dag.getAllNodes())
                            checkAction(label, node, node.getAction(), room, planner, failures, false);

                        if (room.getRoomProcessor() instanceof GeneralRoomProcessor && build.dag.getAllNodes().size() > 1) {
                            Vec3 start = pickStart(room, build.dag);
                            TSPCache tspCache = new TSPCache((GeneralRoomProcessor) room.getRoomProcessor(),
                                    room, Collections.emptyList(), Collections.singletonList(start));
                            planner.awaitTSPCache(60_000);
                            kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP dptsp =
                                    new kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP(build.dag, start, room, planner);
                            double states = dptsp.getStateEstimate();
                            long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
                            List<ActionDAGNode> order = null;
                            String solverTag;
                            if (states <= MAX_EXACT_STATES) {
                                // production's primary solver: an exact order exists or the
                                // route is structurally unsolvable in this world state
                                solverTag = "exact";
                                try {
                                    if (dptsp.solve(() -> System.nanoTime() > deadline))
                                        order = dptsp.reconstructPath();
                                } catch (Throwable t) {
                                    failures.add("[" + label + "] exact solve threw " + t);
                                }
                                if (order == null)
                                    warnings.add("[" + label + "] exact solve failed/aborted under cap (states~2^"
                                            + (int) (Math.log(states) / Math.log(2)) + "): production falls back to heuristic/topological order");
                            } else {
                                // over cap production runs the heuristic backstop, then the
                                // topological order if the heuristic can't place everything
                                solverTag = "heuristic";
                                HeuristicTSP.Result res = new HeuristicTSP(build.dag, start, room, tspCache, planner)
                                        .solve(null, deadline);
                                if (!res.isComplete()) {
                                    failures.add("[" + label + "] heuristic left enabled nodes unplaced (order=" + res.getOrder().size() + ")");
                                } else {
                                    order = res.getOrder();
                                    if (!(res.getCost() < UNREACHABLE_COST))
                                        warnings.add("[" + label + "] over-cap heuristic route cost hit the unreachable sentinel"
                                                + " (states~2^" + (int) (Math.log(states) / Math.log(2)) + ", topological fallback in production)");
                                }
                            }
                            if (order != null) {
                                for (ActionDAGNode node : order)
                                    checkAction(label + ":" + solverTag, node, node.getAction(), room, planner, failures, true);
                                diagnoseInfSteps(label + ":" + solverTag, room, planner, tspCache, order, start, failures, warnings);
                            }
                        }
                    } finally {
                        planner.closeEvalCache();
                    }
                    rows.add(label + " nodes=" + build.dag.getAllNodes().size());
                } catch (Throwable t) {
                    failures.add("[" + label + "] variant threw " + t);
                }
            }
            System.out.println("swept " + info.getName() + " variants=" + variants.size()
                    + " failures so far=" + failures.size());
        }

        System.out.println("=== route sweep: " + roomsTested + " rooms, " + variantCount + " variants ===");
        for (String w : warnings) System.out.println("  WARN " + w);
        for (String f : failures) System.out.println("  FAIL " + f);

        Assertions.assertTrue(failures.isEmpty(),
                failures.size() + " stuck-route findings across " + roomsTested + " rooms / " + variantCount
                        + " variants:\n  " + String.join("\n  ", failures.subList(0, Math.min(failures.size(), 60))));
    }
}

package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.NativeLoader;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonMapLayout;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
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

import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

// root-cause harness for the smart-route ordering regression: builds the same
// DAG FeatureSmartRoute builds for a bundled room, then runs the real native
// DPTSP against it while DPTSP's eval instrumentation counts callbacks and
// the class of each returned cost (finite / impossible / huge-sentinel).
//
// run with -Pdg.configDir=<config dir> (read only: precalcs + tspCache are
// loaded from it, nothing is written back). knobs:
//   -Ddg.diag.rooms=spider,mines,doors,mushroom   name fragments, default spider
//   -Ddg.diag.tspDeadlineMs=300000               abort the solve past this
//   -Ddg.diag.variant=<spec>                    input state to solve under, see below
//   -Ddg.diag.sweep=1                           run the built-in variant matrix
//
// variant spec is '+'-separated terms:
//   base                       pristine room (schematic world, all secrets unfound)
//   start:first                start at the first move target (default)
//   start:all                  solve once per unique move target position
//   start:center               start at room-bounds center
//   start:min                  start at room-bounds min corner
//   start:x,y,z                start at explicit world coords
//   found:<name[,name...]>     force those secret mechanics to FOUND
//   found:all                  force every ISecret to FOUND
//   status:<name>=<found|definitely_not|not_sure>
//   doorforce:<name>=<open|closed>   force a WorldMutating mechanic's blocking
//   doorforce:all=open|closed
//   mechadd:<name[,name...]>   inject DungeonDummyState under these names - mimics
//                              the in-game DOOR-N room-door mechanics that perturb
//                              getMechanics() iteration order (and thus node ids)
//   order:reverse|shuffle<N>   reorder the mechanics map
//
// The metric that matters is `callbacks` (deterministic): wall time varies with
// machine load, the native DP's (state,action) evaluation count does not.
public class SmartRouteRegressionTest {
    private static PathfindPreset defaultPreset;
    private static File configDir;

    @BeforeAll
    static void setup() throws Exception {
        // the eval counters this harness reads are off by default in normal play
        kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.DPTSP.EVAL_DIAG = true;
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        configDir = new File(configDirProperty);

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();

        if (PathfindPrecalculationRegistry.getINSTANCE() == null)
            new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        if (defaultPreset == null) {
            for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
                if (preset != PathfindPresetRegistry.DEFAULT_PRESET) defaultPreset = preset;
            }
        }
        if (defaultPreset == null) defaultPreset = PathfindPresetRegistry.DEFAULT_PRESET;

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());

        TSPCacheRegistry tspRegistry = new TSPCacheRegistry(Files.createTempDirectory("dg-tspcache").toFile(), false);
        CBORMapper cborMapper = new CBORMapper();
        File cacheDir = new File(configDir, "tspCache");
        int loaded = 0;
        if (cacheDir.isDirectory()) {
            for (File f : cacheDir.listFiles()) {
                try {
                    tspRegistry.registerTSPCache(cborMapper.readValue(f, TSPCache.class));
                    loaded++;
                } catch (Exception e) {
                    System.out.println("tspCache load failed: " + f.getName() + " " + e);
                }
            }
        }
        System.out.println("loaded " + loaded + " tsp cache files");

        NativeLoader.extractLibraryAndLoad("dptsp");
    }

    private static DungeonRoom buildRoom(RoomPreset roomPreset, DungeonRoomInfo info) {
        DRIWorld driWorld = new DRIWorld(info, roomPreset.getParent());
        DungeonContext fakeContext = new DungeonContext("TEST DG", driWorld, roomPreset.getParent(), false);
        DungeonMapLayout dungeonMapLayout = new DungeonMapLayout(
                new Dimension(16, 16), 5, new Point(0, 0), new BlockPos(0, 70, 0));
        fakeContext.setScaffoldParser(new DungeonRoomScaffoldParser(dungeonMapLayout, fakeContext));
        DungeonRoom room = new DungeonRoom(fakeContext);
        // in game this runs on the room matcher thread after matching; without it the
        // roomProcessor is null and the route-local TSPCache can't populate from a .pfres.
        // staging tails the method with a Minecraft.getInstance() prefetch - absent under
        // McBootstrap - but roomProcessor is already assigned by then, so the NPE is fine
        try {
            room.updateRoomProcessor();
        } catch (NullPointerException | NoClassDefFoundError ignored) {
        }
        return room;
    }

    // exactly what FeatureSmartRoute.createSmartRoute builds
    private static ActionDAG buildSmartRouteDAG(DungeonRoom dungeonRoom, AlgorithmSetting algorithmSetting) {
        ActionDAGBuilder builder = new ActionDAGBuilder(dungeonRoom);
        for (Map.Entry<String, DungeonMechanicState> value : dungeonRoom.getMechanics().entrySet()) {
            if (value.getValue() instanceof ISecret && !((ISecret) value.getValue()).isFound(dungeonRoom)) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "found"), algorithmSetting);
                } catch (PathfindImpossibleException e) {
                    System.out.println("  requires failed: " + value.getKey() + ": " + e.getMessage());
                }
            } else if (value.getValue() instanceof DungeonRedstoneKeyState
                    && value.getValue().getCurrentState().equalsIgnoreCase("unobtained")) {
                try {
                    builder.requires(new ActionChangeState(value.getKey(), "obtained-self"), algorithmSetting);
                } catch (PathfindImpossibleException e) {
                    System.out.println("  requires failed: " + value.getKey() + ": " + e.getMessage());
                }
            }
        }
        return builder.build();
    }

    private static List<AbstractActionMove> movesOf(ActionDAGNode node) {
        List<AbstractActionMove> out = new ArrayList<>();
        AbstractAction a = node.getAction();
        if (a instanceof AtomicAction) {
            for (AbstractAction inner : ((AtomicAction) a).getActions())
                if (inner instanceof AbstractActionMove) out.add((AbstractActionMove) inner);
        } else if (a instanceof AbstractActionMove) {
            out.add((AbstractActionMove) a);
        }
        return out;
    }

    private static Vec3 pickStart(DungeonRoom room, ActionDAG dag) {
        for (ActionDAGNode node : dag.getAllNodes()) {
            for (AbstractActionMove m : movesOf(node)) {
                return m.getTargetVec3().getPos(room);
            }
        }
        return new Vec3(0, 70, 0);
    }

    // ---------------- variant support ----------------

    private static class Variant {
        String label;
        int startMode = 0; // 0 first, 1 all, 2 center, 3 min, 4 explicit
        Vec3 startPos;
        Map<String, Boolean> doorForce = new LinkedHashMap<>(); // mechanic -> open?
        boolean doorForceAll;
        Boolean doorForceAllOpen;
        Set<String> forceFound = new LinkedHashSet<>();
        boolean forceFoundAll;
        Map<String, String> statusSet = new LinkedHashMap<>();
        List<String> mechAdd = new ArrayList<>();
        int order = 0; // 0 identity, 1 reverse, 2 shuffle
        long shuffleSeed = 1;
        boolean noMatrix; // remove the room's tsp matrix: every eval falls to the pfres path
        int rotation = -1; // -1 keep, else force roomMatcher rotation 0..3
    }

    private static Variant parseVariant(String spec) {
        Variant v = new Variant();
        v.label = spec;
        for (String term : spec.split("\\+")) {
            term = term.trim();
            if (term.isEmpty() || term.equals("base")) continue;
            int colon = term.indexOf(':');
            String key = colon < 0 ? term : term.substring(0, colon);
            String val = colon < 0 ? "" : term.substring(colon + 1);
            switch (key) {
                case "start":
                    if (val.equals("first")) v.startMode = 0;
                    else if (val.equals("all")) v.startMode = 1;
                    else if (val.equals("center")) v.startMode = 2;
                    else if (val.equals("min")) v.startMode = 3;
                    else {
                        String[] c = val.split(",");
                        v.startPos = new Vec3(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
                        v.startMode = 4;
                    }
                    break;
                case "found":
                    if (val.equals("all")) v.forceFoundAll = true;
                    else v.forceFound.addAll(Arrays.asList(val.split("[;,]")));
                    break;
                case "status": {
                    int eq = val.indexOf('=');
                    v.statusSet.put(val.substring(0, eq), val.substring(eq + 1));
                    break;
                }
                case "doorforce": {
                    int eq = val.indexOf('=');
                    String name = val.substring(0, eq);
                    boolean open = val.substring(eq + 1).equalsIgnoreCase("open");
                    if (name.equals("all")) {
                        v.doorForceAll = true;
                        v.doorForceAllOpen = open;
                    } else {
                        v.doorForce.put(name, open);
                    }
                    break;
                }
                case "mechadd":
                    v.mechAdd.addAll(Arrays.asList(val.split("[;,]")));
                    break;
                case "nomatrix":
                    v.noMatrix = true;
                    break;
                case "rot":
                    v.rotation = Integer.parseInt(val) & 3;
                    break;
                case "order":
                    if (val.equals("reverse")) v.order = 1;
                    else if (val.startsWith("shuffle")) {
                        v.order = 2;
                        if (val.length() > 7) v.shuffleSeed = Long.parseLong(val.substring(7));
                    }
                    break;
                default:
                    System.out.println("  unknown variant term: " + term);
            }
        }
        return v;
    }

    // wraps the room's cached world so WorldMutatingMechanicState.isBlocking reads a
    // forced open/closed state at the door's blocks - the stand-in for "the live
    // world already has this door open" (in game the cached world is the live level)
    private static class ForcedWorld implements DGWorld {
        private final DGWorld delegate;
        private final Set<BlockPos> airOverride = new HashSet<>();
        private final Map<BlockPos, BlockState> blockOverride = new HashMap<>();

        ForcedWorld(DGWorld delegate) {
            this.delegate = delegate;
        }

        public BlockState getBlock(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            if (airOverride.contains(p)) return Blocks.AIR.defaultBlockState();
            BlockState s = blockOverride.get(p);
            if (s != null) return s;
            return delegate.getBlock(x, y, z);
        }

        public boolean isInScope(int x, int y, int z) {
            return delegate.isInScope(x, y, z);
        }

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

    // the room's matrix removed by the nomatrix variant; restored after each solve so the
    // shared TSPCacheRegistry isn't poisoned for the next variant/test in the same JVM
    private static String removedCacheId;
    private static kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache removedCache;

    private static void restoreNoMatrix() throws Exception {
        if (removedCache == null) return;
        Field mappingField = TSPCacheRegistry.class.getDeclaredField("mapping");
        mappingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache> mapping =
                (Map<String, kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache>) mappingField.get(null);
        mapping.put(removedCacheId, removedCache);
        removedCache = null;
        removedCacheId = null;
    }

    @SuppressWarnings("unchecked")
    private static void applyVariant(DungeonRoom room, Variant v) throws Exception {
        Map<String, DungeonMechanicState> mechanics = room.getMechanics();

        // in game, _mechanics also holds DungeonRoomDoorState entries for every door
        // on the room's boundary ("entrance-1", "withergate-2", ...) - non-secret
        // mechanics whose extra hash keys reshuffle the whole map's iteration order
        for (String name : v.mechAdd) {
            mechanics.put(name, new DungeonDummyState(new DungeonDummyState.DungeonDummyData(), room));
        }

        if (v.order != 0) {
            List<Map.Entry<String, DungeonMechanicState>> entries = new ArrayList<>(mechanics.entrySet());
            if (v.order == 1) Collections.reverse(entries);
            else Collections.shuffle(entries, new Random(v.shuffleSeed));
            Map<String, DungeonMechanicState> reordered = new LinkedHashMap<>();
            for (Map.Entry<String, DungeonMechanicState> e : entries) reordered.put(e.getKey(), e.getValue());
            Field f = DungeonRoom.class.getDeclaredField("_mechanics");
            f.setAccessible(true);
            f.set(room, new HashMap<>(reordered));
            mechanics = room.getMechanics();
        }

        if (v.doorForceAll || !v.doorForce.isEmpty()) {
            ForcedWorld forced = new ForcedWorld(room.getCachedWorld());
            for (Map.Entry<String, DungeonMechanicState> e : mechanics.entrySet()) {
                if (!(e.getValue() instanceof WorldMutatingMechanicState)) continue;
                Boolean open = v.doorForce.get(e.getKey());
                if (open == null) {
                    if (!v.doorForceAll) continue;
                    open = v.doorForceAllOpen;
                }
                for (kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint pt :
                        ((WorldMutatingMechanicState) e.getValue()).blockedPoints()) {
                    BlockPos bp = pt.getBlockPos(room);
                    if (open) forced.airOverride.add(bp);
                    else forced.blockOverride.put(bp, Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
            setField(room, "cachedWorld", forced);
        }

        if (v.noMatrix) {
            Field mappingField = TSPCacheRegistry.class.getDeclaredField("mapping");
            mappingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache> mapping =
                    (Map<String, kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCache>) mappingField.get(null);
            for (RoomPreset rp : room.getContext().getPreset().getPresets().values()) {
                if (rp.getRoomId().equals(room.getDungeonRoomInfo().getUuid())) {
                    removedCacheId = rp.getTSPCache();
                    removedCache = mapping.remove(removedCacheId);
                }
            }
        }

        if (v.rotation >= 0) room.getRoomMatcher().setRotation(v.rotation);

        for (String name : v.forceFound) forceFound(mechanics.get(name));
        if (v.forceFoundAll) {
            for (DungeonMechanicState m : mechanics.values())
                if (m instanceof ISecret) forceFound(m);
        }

        for (Map.Entry<String, String> e : v.statusSet.entrySet()) {
            DungeonMechanicState m = mechanics.get(e.getKey());
            if (m == null) continue;
            switch (e.getValue()) {
                case "found": forceFound(m); break;
                case "definitely_not": setSecretStatus(m, "DEFINITELY_NOT"); break;
                case "not_sure": setSecretStatus(m, "NOT_SURE"); break;
            }
        }
    }

    private static void setSecretStatus(DungeonMechanicState m, String status) throws Exception {
        if (m instanceof DungeonSecretItemDropState) {
            ((DungeonSecretItemDropState) m).setStatus(DungeonSecretItemDropState.SecretStatus.valueOf(status));
        } else if (m instanceof DungeonSecretEssenceState) {
            DungeonSecretEssenceState e = (DungeonSecretEssenceState) m;
            e.setEssenceWasThere(status.equals("DEFINITELY_NOT"));
        } else {
            System.out.println("  status not settable on " + m.getClass().getSimpleName());
        }
    }

    private static void forceFound(DungeonMechanicState m) throws Exception {
        if (m == null) return;
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
        } else {
            System.out.println("  no forceFound path for " + m.getClass().getSimpleName());
        }
    }

    private static void dumpMechanics(DungeonRoom room) {
        StringBuilder sb = new StringBuilder("mechanics (" + room.getMechanics().size() + "):");
        for (Map.Entry<String, DungeonMechanicState> e : room.getMechanics().entrySet()) {
            DungeonMechanicState m = e.getValue();
            sb.append("\n  ").append(e.getKey())
                    .append(" ").append(m.getClass().getSimpleName().replace("Dungeon", "").replace("State", ""))
                    .append(" state=").append(m.getCurrentState())
                    .append(m instanceof ISecret ? " isFound=" + ((ISecret) m).isFound(room) : "")
                    .append(m instanceof WorldMutatingMechanicState ? " blocking=" + ((WorldMutatingMechanicState) m).isBlocking(room) : "");
        }
        System.out.println(sb);
    }

    // ---------------- the run ----------------

    private static class SolveResult {
        final String line;
        final boolean solved;
        final long wallMs;
        final long callbacks;
        final long pfresLoads;
        SolveResult(String line, boolean solved, long wallMs, long callbacks, long pfresLoads) {
            this.line = line; this.solved = solved; this.wallMs = wallMs;
            this.callbacks = callbacks; this.pfresLoads = pfresLoads;
        }
    }

    private static SolveResult runSolve(String tag, DungeonRoom room, ActionDAG dag, Vec3 start, long deadlineNanos) {
        DPTSP.resetEvalCounters();
        long deadline = System.nanoTime() + deadlineNanos;

        DPTSP dptsp = null;
        String outcome;
        long wallStart = System.currentTimeMillis();
        try {
            dptsp = new DPTSP(dag, start, room);
            outcome = dptsp.solve(() -> System.nanoTime() >= deadline) ? "SOLVED" : "ABORTED";
        } catch (DPTSP.SolveAbortedException e) {
            outcome = "ABORTED";
        } catch (Throwable t) {
            outcome = "THREW " + t;
        }
        long wallMs = System.currentTimeMillis() - wallStart;
        int orderLen = dptsp != null && outcome.equals("SOLVED") ? dptsp.reconstructPath().size() : -1;
        String line = String.format(Locale.ROOT,
                "== %-28s %s wall=%dms callbacks=%d finite=%d impossible=%d huge=%d javaEvalMs=%d order=%d %s",
                tag, outcome, wallMs, DPTSP.EVAL_TOTAL.get(), DPTSP.EVAL_FINITE.get(),
                DPTSP.EVAL_IMPOSSIBLE.get(), DPTSP.EVAL_HUGE.get(), DPTSP.EVAL_NANOS.get() / 1_000_000, orderLen,
                DPTSP.evalMissSummary());
        System.out.println(line);
        return new SolveResult(line, outcome.equals("SOLVED"), wallMs,
                DPTSP.EVAL_TOTAL.get(), DPTSP.EVAL_PFRES_LOAD.get());
    }

    private static RoomPreset findRoomPreset(String fragment, DungeonRoomInfo[] infoOut) {
        for (RoomPreset candidate : defaultPreset.getPresets().values()) {
            DungeonRoomInfo i = DungeonRoomInfoRegistry.getByUUID(candidate.getRoomId());
            if (i != null && i.getName() != null && i.getName().toLowerCase().contains(fragment)) {
                infoOut[0] = i;
                return candidate;
            }
        }
        return null;
    }

    // minimal room+dag+solve used by the bounded-solve regression test
    private static SolveResult solveVariant(String fragment, Variant v, long deadlineNanos) throws Exception {
        DungeonRoomInfo[] infoBox = new DungeonRoomInfo[1];
        RoomPreset roomPreset = findRoomPreset(fragment, infoBox);
        Assertions.assertNotNull(roomPreset, "no room preset matches " + fragment);
        DungeonRoom room = buildRoom(roomPreset, infoBox[0]);
        try {
            applyVariant(room, v);
            ActionDAG dag = buildSmartRouteDAG(room, roomPreset.getEffectiveAlgorithmSetting(infoBox[0]));
            return runSolve(v.label, room, dag, pickStart(room, dag), deadlineNanos);
        } finally {
            restoreNoMatrix();
        }
    }

    private static void runDiag(String fragment, long deadlineNanos) throws Exception {
        DungeonRoomInfo[] infoBox = new DungeonRoomInfo[1];
        RoomPreset roomPreset = findRoomPreset(fragment, infoBox);
        DungeonRoomInfo info = infoBox[0];
        if (roomPreset == null) {
            System.out.println("== " + fragment + ": no room preset matches, skipping");
            return;
        }
        System.out.println("== " + info.getName() + " (" + info.getUuid() + ") secrets=" + info.getTotalSecrets()
                + " mechanics=" + info.getMechanics().size() + " preset precalcs=" + roomPreset.getPrecalculations().size()
                + " tspCacheId=" + roomPreset.getTSPCache() + " processor=" + info.getProcessorId()
                + " blocks=" + info.getBlocks().length + "x" + info.getBlocks()[0].length);

        List<Variant> variants = new ArrayList<>();
        String sweep = prop("dg.diag.sweep", null);
        if (sweep != null) {
            variants.addAll(defaultSweep());
        } else {
            variants.add(parseVariant(prop("dg.diag.variant", "base")));
        }

        int vi = 0;
        for (Variant v : variants) {
            vi++;
            System.out.println("== variant " + vi + "/" + variants.size() + ": " + v.label);
            DungeonRoom room = buildRoom(roomPreset, info);
            applyVariant(room, v);
            try {
            dumpMechanics(room);
            AlgorithmSetting setting = roomPreset.getEffectiveAlgorithmSetting(info);

            long t0 = System.currentTimeMillis();
            ActionDAG dag = buildSmartRouteDAG(room, setting);
            System.out.println("DAG build: " + (System.currentTimeMillis() - t0) + "ms"
                    + " allNodes=" + dag.getAllNodes().size() + " dagIds=" + dag.getCount());

            List<ActionDAGNode> all = dag.getAllNodes();
            int bitCount = 0;
            label:
            for (ActionDAGNode node : all) {
                for (ActionDAGNode req : node.getRequiredBy()) {
                    if (req.getOr().isEmpty()) continue;
                    continue label;
                }
                bitCount++;
            }
            double orProduct = 1;
            int orGroups = 0;
            int orBits = 0;
            for (ActionDAGNode node : all) {
                if (node.getOr().isEmpty()) continue;
                orGroups++;
                orProduct *= node.getOr().size() + 1;
            }
            while (orProduct >= 2) { orProduct /= 2; orBits++; }
            List<String> mechNames = room.getMechanics().entrySet().stream()
                    .filter(a -> a.getValue() instanceof DungeonDoorState || a.getValue() instanceof DungeonOnewayDoorState)
                    .map(Map.Entry::getKey).collect(Collectors.toList());
            int openAtStart = 0;
            for (String name : mechNames) {
                if (!((WorldMutatingMechanicState) room.getMechanics().get(name)).isBlocking(room)) openAtStart++;
            }
            System.out.println("DP dimensions: requireBits=" + bitCount + " orGroups=" + orGroups
                    + " (~2^" + orBits + ") doors=" + mechNames.size() + " openAtStart=" + openAtStart
                    + " -> ~2^" + (bitCount + orBits) + " states x 2^" + mechNames.size() + " door sets");

            StringBuilder nodeDump = new StringBuilder("DAG nodes:");
            for (ActionDAGNode node : all) {
                String firstLine = node.getAction().toString().split("\n")[0];
                nodeDump.append("\n  #").append(node.getId()).append(' ').append(firstLine)
                        .append(" req=").append(node.getRequire().size())
                        .append(" or=").append(node.getOr().size())
                        .append(" opt=").append(node.getOptional().size());
            }
            System.out.println(nodeDump);

            if (v.startMode == 1) {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                List<Vec3> starts = new ArrayList<>();
                for (ActionDAGNode node : all) {
                    for (AbstractActionMove m : movesOf(node)) {
                        Vec3 p = m.getTargetVec3().getPos(room);
                        if (seen.add(p.toString())) starts.add(p);
                    }
                }
                int si = 0;
                for (Vec3 start : starts) {
                    runSolve(v.label + " start#" + (++si), room, dag, start, deadlineNanos);
                }
            } else {
                Vec3 start;
                switch (v.startMode) {
                    case 2:
                        BlockPos c = room.getRoomBounds().getMin().offset(room.getRoomBounds().getMax()).offset(0, 0, 0);
                        start = new Vec3((room.getRoomBounds().getMin().getX() + room.getRoomBounds().getMax().getX()) / 2.0,
                                room.getRoomBounds().getMin().getY() + 10,
                                (room.getRoomBounds().getMin().getZ() + room.getRoomBounds().getMax().getZ()) / 2.0);
                        break;
                    case 3:
                        start = Vec3.atLowerCornerOf(room.getRoomBounds().getMin()).add(1, 1, 1);
                        break;
                    case 4:
                        start = v.startPos;
                        break;
                    default:
                        start = pickStart(room, dag);
                }
                runSolve(v.label, room, dag, start, deadlineNanos);
            }
            } finally {
                restoreNoMatrix();
            }
        }
    }

    // the default matrix: baseline, in-game door mechanics perturbing iteration
    // order, several start positions, door forced states, secret-status variants
    private static List<Variant> defaultSweep() {
        List<Variant> out = new ArrayList<>();
        for (String spec : new String[]{
                "base",
                "mechadd:entrance-1",
                "mechadd:entrance-1;entrance-2",
                "mechadd:entrance-1;withergate-2",
                "mechadd:entrance-1;gate-2;bloodgate-3",
                "start:all",
                "start:center",
                "start:min",
                "doorforce:all=open",
                "doorforce:door-1=open",
                "doorforce:crypt-1=open",
                "doorforce:crypt-1=open;crypt-2=open;crypt-3=open",
                "doorforce:superboom-1=open",
                "doorforce:superboom-1=open;crypt-1=open;crypt-2=open;crypt-3=open",
                "found:all",
                "mechadd:entrance-1+start:center",
                "mechadd:entrance-1+start:min",
                "order:reverse",
                "order:shuffle1",
                "rot:1",
                "rot:2",
                "rot:3",
        }) out.add(parseVariant(spec));
        return out;
    }

    private static String prop(String name, String def) {
        String v = System.getProperty(name);
        return v != null ? v : System.getenv().getOrDefault(name.replace('.', '_').toUpperCase(), def);
    }

    @Test
    void dptspDiag() throws Exception {
        String rooms = prop("dg.diag.rooms", "spider");
        long deadlineMs = Long.parseLong(prop("dg.diag.tspDeadlineMs", "300000"));
        for (String fragment : rooms.split(",")) {
            runDiag(fragment.trim().toLowerCase(), deadlineMs * 1_000_000L);
        }
    }

    // the in-game hang mode (see P3f): every matrix miss routes the eval to
    // loadPrecalculatedByHash - formerly a full pathfinder build per callback (~ms each,
    // not cached) - which turned a ~600k-callback solve into minutes. two guards must hold:
    // rotated rooms must round-trip OffsetVec3 offsets exactly (keep evals on the matrix),
    // and even a total matrix outage must degrade to ~seconds via the per-solve executor
    // cache (nomatrix), not minutes.
    @Test
    void smartRouteBoundedSolve() throws Exception {
        for (String spec : new String[]{"base", "rot:1", "rot:2", "rot:3"}) {
            SolveResult r = solveVariant("spider", parseVariant(spec), 300_000_000_000L);
            Assertions.assertTrue(r.solved, spec + " did not solve: " + r.line);
            Assertions.assertTrue(r.wallMs < 30_000, spec + " too slow: " + r.line);
            Assertions.assertTrue(r.pfresLoads < r.callbacks / 20,
                    spec + " solve fell back to per-eval pathfinder loads: " + r.line);
        }

        // worst case: the whole matrix missing. with per-solve executor reuse this is a
        // ~2s solve, not the former minutes-long pathfinder-build-per-callback hang
        SolveResult r = solveVariant("spider", parseVariant("nomatrix"), 300_000_000_000L);
        Assertions.assertTrue(r.solved, "nomatrix did not solve: " + r.line);
        Assertions.assertTrue(r.wallMs < 60_000, "nomatrix too slow: " + r.line);
    }

    // a solve that starts while the room's TSP matrix is absent (registry still
    // scanning its .cache files, an invalidated rebuild, or a preset with no shipped
    // cache) must not fall into per-callback pathfinder builds: ActionRoute waits on
    // RoomPresetPathPlanner.awaitTSPCache, which triggers the build on demand rather
    // than waiting for the 5s-coalesced scheduled task. after the await the matrix
    // is resident and the solve runs on matrix hits again.
    @Test
    void tspCacheAwaitRebuilds() throws Exception {
        DungeonRoomInfo[] infoBox = new DungeonRoomInfo[1];
        RoomPreset roomPreset = findRoomPreset("spider", infoBox);
        Assertions.assertNotNull(roomPreset, "no room preset matches spider");

        Field mappingField = TSPCacheRegistry.class.getDeclaredField("mapping");
        mappingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TSPCache> mapping = (Map<String, TSPCache>) mappingField.get(null);
        String id = roomPreset.getTSPCache();
        TSPCache removed = mapping.remove(id);
        Assertions.assertNotNull(removed, "spider matrix was not loaded");
        try {
            RoomPresetPathPlanner planner = new RoomPresetPathPlanner(roomPreset);
            Assertions.assertNull(planner.getTSPCache(), "matrix still resident after removal");
            TSPCache rebuilt = planner.awaitTSPCache(60_000);
            Assertions.assertNotNull(rebuilt, "awaitTSPCache did not rebuild the matrix");
            Assertions.assertSame(rebuilt, planner.getTSPCache());
        } finally {
            if (mapping.get(id) == null) mapping.put(id, removed);
        }

        // and a solve after the await runs entirely on matrix hits
        SolveResult r = solveVariant("spider", parseVariant("base"), 300_000_000_000L);
        Assertions.assertTrue(r.solved, "solve failed: " + r.line);
        Assertions.assertTrue(DPTSP.EVAL_MATRIX_HIT.get() > 0, "no matrix hits: " + r.line);
        Assertions.assertEquals(0, DPTSP.EVAL_MATRIX_NULL.get(), "matrix still absent: " + r.line);
        Assertions.assertEquals(0, DPTSP.EVAL_PFRES_LOAD.get(), "solve fell to pfres loads: " + r.line);
    }

    // live-log regression: the native coroutine yielded garbage node ids
    // (getNode() = 66178 / 620) for degenerate DAGs -> AIOOBE inside DPTSP.solve.
    // a DAG with nothing to schedule (root only, or every node complete) is now
    // answered in Java and the native lib must not even start a coroutine.
    @Test
    void trivialDagSkipsNative() throws Exception {
        DungeonRoomInfo[] infoBox = new DungeonRoomInfo[1];
        RoomPreset roomPreset = findRoomPreset("spider", infoBox);
        Assertions.assertNotNull(roomPreset, "no room preset matches spider");
        DungeonRoom room = buildRoom(roomPreset, infoBox[0]);

        // the DAG Smart Route builds when no secret is pending: root alone
        ActionDAG dag = new ActionDAGBuilder(room).build();
        Assertions.assertEquals(1, dag.getAllNodes().size());

        DPTSP.resetEvalCounters();
        DPTSP dptsp = new DPTSP(dag, new Vec3(0, 70, 0), room);
        Assertions.assertTrue(dptsp.solve(null));
        Assertions.assertTrue(dptsp.reconstructPath().isEmpty(),
                "trivial DAG must produce an empty route");
        Assertions.assertEquals(0, DPTSP.EVAL_TOTAL.get(),
                "native coroutine ran for a trivial DAG");
    }

    // item-4 reporting: for every bundled room, the heuristic backstop must emit a
    // valid, non-empty order inside its ~300ms publication budget; where the exact
    // DP is also practical (same cap ActionRoute applies) both run and the
    // heuristic/exact route-cost ratio is printed for the report. over-cap rooms
    // are heuristic-only in production, mirroring ActionRoute.
    @Test
    void heuristicVsExactRatio() throws Exception {
        double maxExactStates = Double.parseDouble(System.getProperty("dg.smartRoute.maxExactStates", "6.8719476736E10"));
        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int tested = 0, compared = 0;
        for (RoomPreset roomPreset : defaultPreset.getPresets().values()) {
            DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(roomPreset.getRoomId());
            if (info == null || info.getTotalSecrets() == 0) continue;
            DungeonRoom room = buildRoom(roomPreset, info);
            AlgorithmSetting setting = roomPreset.getEffectiveAlgorithmSetting(info);
            ActionDAG dag;
            try {
                dag = buildSmartRouteDAG(room, setting);
            } catch (Throwable t) {
                continue; // DAG build failure is unrelated to ordering
            }
            if (dag.getAllNodes().size() <= 1) continue;
            if (!(room.getRoomProcessor() instanceof GeneralRoomProcessor)) {
                rows.add(info.getName() + " SKIPPED: processor=" + room.getRoomProcessor());
                continue; // no pathfinder world: neither solver can evaluate costs
            }
            tested++;

            Vec3 start = pickStart(room, dag);
            RoomPresetPathPlanner planner = new RoomPresetPathPlanner(roomPreset);
            kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache solveCache =
                    new kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache(
                            (GeneralRoomProcessor) room.getRoomProcessor(), room,
                            Collections.emptyList(), Collections.singletonList(start));

            kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.HeuristicTSP tsp =
                    new kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.HeuristicTSP(
                            dag, start, room, solveCache, planner);
            long h0 = System.nanoTime();
            kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.HeuristicTSP.Result heur =
                    tsp.solve(() -> false, h0 + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300));
            long heurMs = (System.nanoTime() - h0) / 1_000_000;

            if (heur.getOrder().isEmpty()) failures.add(info.getName() + ": empty heuristic order");
            if (!tsp.isValidOrder(heur.getOrder())) failures.add(info.getName() + ": invalid heuristic order");

            DPTSP dptsp = new DPTSP(dag, start, room, planner);
            if (dptsp.getStateEstimate() > maxExactStates) {
                rows.add(String.format(Locale.ROOT, "%-28s nodes=%3d heuristic=%12.0f %5dms%s exact=OVER-CAP(log2=%.1f)",
                        info.getName(), dag.getAllNodes().size(), heur.getCost(), heurMs,
                        heur.isTimedOut() ? " TIMED" : "", Math.log(dptsp.getStateEstimate()) / Math.log(2)));
                continue;
            }

            long e0 = System.nanoTime();
            // same 10 s deadline as ActionRoute's exact solve
            boolean solved = dptsp.solve(() -> System.nanoTime() - e0 > java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
            long exactMs = (System.nanoTime() - e0) / 1_000_000;
            List<ActionDAGNode> exact = dptsp.reconstructPath();
            if (!solved || exact.isEmpty()) {
                rows.add(String.format(Locale.ROOT, "%-28s nodes=%3d heuristic=%12.0f %5dms exact=UNSOLVED(%dms)",
                        info.getName(), dag.getAllNodes().size(), heur.getCost(), heurMs, exactMs));
                continue;
            }
            compared++;
            double exactCost = tsp.routeCost(exact);
            rows.add(String.format(Locale.ROOT, "%-28s nodes=%3d heuristic=%12.0f %5dms exact=%12.0f %6dms ratio=%.3f",
                    info.getName(), dag.getAllNodes().size(), heur.getCost(), heurMs,
                    exactCost, exactMs, heur.getCost() / exactCost));
        }
        System.out.println("heuristic backstop over " + tested + " rooms, exact compared on " + compared + ":");
        for (String r : rows) System.out.println("  " + r);
        Assertions.assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}

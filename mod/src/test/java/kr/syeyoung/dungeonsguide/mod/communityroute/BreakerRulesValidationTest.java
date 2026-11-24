package kr.syeyoung.dungeonsguide.mod.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CanonicalTransform;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CommunityRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.OdinWaypointParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RoutePack;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteRoomMapper;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStep;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.SrmRouteParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonPressurePlateState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonRedstoneKeyState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.InstaBreakFactorCalculatingCoordinateMap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PrecalculatedPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4h: validates the 'Dungeon Breaker + Etherwarp' preset's digging rules against
 * real community route data (SRM fowroutes/3ppopkaroutes + Odin Imported).
 *
 * Opt-in: -Pdg.breakerAnalysis=true -Pdg.configDir=<DG config dir>
 *   -Pdg.breakerPresetDir=<dir of generated .pfres>   (default: the installed
 *   eb84bd79 breaker-etherwarp packaging dir)
 *   -Pdg.srmFow/-Pdg.srmPop/-Pdg.odinWaypoints        (route file overrides,
 *   default: the dungeons Prism instance paths, same as RouteAlignmentTest)
 *
 * Writes build/breaker-analysis/*.txt and prints the same report to stdout.
 * Read-only against every input.
 */
public class BreakerRulesValidationTest {

    private static final String DEFAULT_PRISM =
            "C:/Users/justi/AppData/Roaming/PrismLauncher/instances/dungeons/minecraft/";
    private static final String DEFAULT_PRESET_DIR =
            "C:/Users/justi/dg-presets/precalculations/eb84bd79-567c-4acc-b653-c60b129ba1fa";

    private static File outDir;
    private static List<RoutePack> packs;
    private static List<DungeonRoomInfo> rooms;
    private static Set<Integer> breakerProtected;

    // ---------- report plumbing ----------

    private static PrintWriter openReport(String name) throws Exception {
        outDir.mkdirs();
        return new Tee(new PrintWriter(Files.newBufferedWriter(new File(outDir, name).toPath())));
    }

    /** writes to the report file and stdout simultaneously */
    private static final class Tee extends PrintWriter {
        Tee(PrintWriter inner) { super(inner, true); }
        @Override public void println(String x) {
            super.println(x);
            System.out.println(x);
        }
    }

    // ---------- boot ----------

    private static File find(String prop, String... candidates) {
        String sys = System.getProperty(prop);
        if (sys != null && new File(sys).isFile()) return new File(sys);
        for (String c : candidates) {
            File f = new File(c.startsWith("config/") ? DEFAULT_PRISM + c : c);
            if (f.isFile()) return f;
        }
        return null;
    }

    @BeforeAll
    static void boot() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dg.breakerAnalysis"),
                "breaker analysis disabled, add -Pdg.breakerAnalysis=true");
        TestRooms.boot();
        outDir = new File("build/breaker-analysis");

        Field f = InstaBreakFactorCalculatingCoordinateMap.class.getDeclaredField("BREAKER_PROTECTED");
        f.setAccessible(true);
        breakerProtected = (Set<Integer>) f.get(null);

        rooms = DungeonRoomInfoRegistry.getRegistered();

        packs = new ArrayList<>();
        File fow = find("dg.srmFow", "config/SecretRoutes/routes/fowroutes.json");
        if (fow != null) try (FileReader r = new FileReader(fow)) {
            packs.add(new SrmRouteParser().parse("srm:fowroutes", fow.getName(), r));
        }
        File pop = find("dg.srmPop", "config/SecretRoutes/routes/3ppopkaroutes.json");
        if (pop != null) try (FileReader r = new FileReader(pop)) {
            packs.add(new SrmRouteParser().parse("srm:3ppopkaroutes", pop.getName(), r));
        }
        File odin = find("dg.odinWaypoints", "config/odin/dungeon-waypoints/Imported.json");
        if (odin != null) try (FileReader r = new FileReader(odin)) {
            packs.add(new OdinWaypointParser().parse("odin:" + odin.getName(), odin.getName(), r));
        }
        Assumptions.assumeTrue(!packs.isEmpty(), "no community route files found");
    }

    // ---------- shared helpers ----------

    // non-overlapping fields: x 22 bits [42..63], z 22 bits [20..41], y 20 bits [0..19]
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }

    private static int kx(long k) { int v = (int) (k >> 42) & 0x3FFFFF; return (v & 0x200000) != 0 ? v | ~0x3FFFFF : v; }
    private static int ky(long k) { int v = (int) k & 0xFFFFF; return (v & 0x80000) != 0 ? v | ~0xFFFFF : v; }
    private static int kz(long k) { int v = (int) (k >> 20) & 0x3FFFFF; return (v & 0x200000) != 0 ? v | ~0x3FFFFF : v; }

    private static int cheb(long a, long b) {
        return Math.max(Math.abs(kx(a) - kx(b)),
                Math.max(Math.abs(ky(a) - ky(b)), Math.abs(kz(a) - kz(b))));
    }

    private static String posStr(long k) {
        return kx(k) + "," + ky(k) + "," + kz(k);
    }

    /** canonical (OffsetPoint frame) positions of every world-mutating mechanic's blocks */
    private static Set<Long> mechanicBlocked(DungeonRoomInfo info) {
        return mechanicOwners(info).keySet();
    }

    /** canonical positions -> "mechKey:Type" of every world-mutating mechanic covering them */
    private static Map<Long, String> mechanicOwners(DungeonRoomInfo info) {
        Map<Long, String> out = new HashMap<>();
        for (Map.Entry<String, DungeonMechanicData> e : info.getMechanics().entrySet()) {
            DungeonMechanicData d = e.getValue();
            if (d instanceof WorldMutatingMechanicData) {
                String tag = e.getKey() + ":" + d.getClass().getSimpleName();
                for (OffsetPoint p : ((WorldMutatingMechanicData) d).blockedPoints()) {
                    out.putIfAbsent(key(p.getX(), p.getY(), p.getZ()), tag);
                }
            }
        }
        return out;
    }

    private static String name(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /**
     * For an air/bedrock lookup (mapping suspect), find the closest non-air
     * non-bedrock block within chebyshev 1 — the block the waypoint probably
     * meant. Returns "<none>" when everything nearby is also air/bedrock.
     */
    private static String nearestDiggable(DungeonRoomInfo info, int bx, int by, int bz) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (d >= bestD) continue;
                    try {
                        BlockState s = info.getBlock(bx + dx, by + dy, bz + dz, 0);
                        if (s == null || s.isAir()) continue;
                        String n = name(s);
                        if ("minecraft:bedrock".equals(n)) continue;
                        best = n;
                        bestD = d;
                    } catch (Throwable ignored) {
                    }
                }
        return best != null ? best : "<none>";
    }

    /** "lever-gated" mechanics: levers/oneway levers/redstone-key slots/plates -> the mechanic they open */
    private static final class Gate {
        String mechKey, kind, targetKey;
        long triggerPos;
        Set<Long> targetBlocked = new HashSet<>();
        Set<Long> targetNear2 = new HashSet<>(); // targetBlocked dilated by chebyshev 2
        boolean targetMutating;
    }

    private static List<Gate> gates(DungeonRoomInfo info) {
        List<Gate> out = new ArrayList<>();
        for (Map.Entry<String, DungeonMechanicData> e : info.getMechanics().entrySet()) {
            DungeonMechanicData d = e.getValue();
            Gate g = null;
            if (d instanceof DungeonLeverState.DungeonLeverData) {
                DungeonLeverState.DungeonLeverData v = (DungeonLeverState.DungeonLeverData) d;
                g = new Gate(); g.kind = "lever";
                g.triggerPos = key(v.getLeverPoint().getX(), v.getLeverPoint().getY(), v.getLeverPoint().getZ());
                g.targetKey = v.getTriggering();
            } else if (d instanceof DungeonOnewayLeverState.DungeonOnewayLeverData) {
                DungeonOnewayLeverState.DungeonOnewayLeverData v = (DungeonOnewayLeverState.DungeonOnewayLeverData) d;
                g = new Gate(); g.kind = "oneway-lever";
                g.triggerPos = key(v.getLeverPoint().getX(), v.getLeverPoint().getY(), v.getLeverPoint().getZ());
                g.targetKey = v.getTriggering();
            } else if (d instanceof DungeonRedstoneKeyState.DungeonRedstoneKeyData) {
                DungeonRedstoneKeyState.DungeonRedstoneKeyData v = (DungeonRedstoneKeyState.DungeonRedstoneKeyData) d;
                g = new Gate(); g.kind = "redstone-key";
                g.triggerPos = key(v.getSecretPoint().getX(), v.getSecretPoint().getY(), v.getSecretPoint().getZ());
                g.targetKey = v.getTriggering();
            } else if (d instanceof DungeonPressurePlateState.DungeonPressurePlateData) {
                DungeonPressurePlateState.DungeonPressurePlateData v = (DungeonPressurePlateState.DungeonPressurePlateData) d;
                g = new Gate(); g.kind = "pressure-plate";
                g.triggerPos = key(v.getPlatePoint().getX(), v.getPlatePoint().getY(), v.getPlatePoint().getZ());
                g.targetKey = v.getTriggering();
            }
            if (g == null) continue;
            g.mechKey = e.getKey();
            DungeonMechanicData target = info.getMechanics().get(g.targetKey);
            g.targetMutating = target instanceof WorldMutatingMechanicData;
            if (g.targetMutating) {
                for (OffsetPoint p : ((WorldMutatingMechanicData) target).blockedPoints()) {
                    g.targetBlocked.add(key(p.getX(), p.getY(), p.getZ()));
                }
                for (long bp : g.targetBlocked) {
                    int bx = kx(bp), by = ky(bp), bz = kz(bp);
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dy = -2; dy <= 2; dy++)
                            for (int dz = -2; dz <= 2; dz++)
                                g.targetNear2.add(key(bx + dx, by + dy, bz + dz));
                }
            }
            out.add(g);
        }
        return out;
    }

    // one community 'mine' entry resolved to a canonical block
    private static final class Mine {
        String pack, routeKey, room, how;
        double matchRate;
        int stepIndex, routeVariant;
        long pos;
        BlockState state;
        String block = "<lookup-fail>";
        int legacyId = -2;
        boolean inMechBlocked, inProtected, inGate;
        String mechName, gateName;
        // nearest non-air non-bedrock block within chebyshev 1 (mapping-noise probe)
        String nearBlock;
    }

    // lazily computed shared analysis state
    private static Map<DungeonRoomInfo, List<Gate>> roomGates;
    private static Map<DungeonRoomInfo, Map<Long, String>> roomMech;
    private static List<Mine> allMines;
    private static Map<String, Integer> minesPerRoom;
    private static Set<String> minedTypes;

    private static synchronized void ensureMines() {
        if (allMines != null) return;
        roomGates = new HashMap<>();
        roomMech = new HashMap<>();
        minesPerRoom = new TreeMap<>();
        for (DungeonRoomInfo info : rooms) {
            List<Gate> g = gates(info);
            if (!g.isEmpty()) roomGates.put(info, g);
            roomMech.put(info, mechanicOwners(info));
        }

        List<Mine> mines = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (DungeonRoomInfo info : rooms) {
            List<Gate> gates = roomGates.get(info);
            Map<Long, String> mech = roomMech.get(info);
            for (RoutePack pack : packs) {
                // boundsMinY 70 = DRIWorld's canonical base so the fallback transform's
                // ty maps SRM/Odin absolute y onto canonical (OffsetPoint) y correctly
                RouteRoomMapper.Resolution res = RouteRoomMapper.resolve(info, pack, 70);
                if (!res.matched()) continue;
                List<CommunityRoute> routes = pack.routesFor(res.routeKey);
                if (routes == null) continue;
                for (CommunityRoute route : routes) {
                    int idx = -1;
                    for (RouteStep step : route.steps) {
                        idx++;
                        for (RouteAction a : step.actions) {
                            if (a.kind != RouteAction.Kind.BREAKER) continue;
                            RouteVec3 c = res.transform.apply(a.pos);
                            int bx = (int) Math.round(c.x), by = (int) Math.round(c.y), bz = (int) Math.round(c.z);
                            Mine m = new Mine();
                            m.pack = pack.id; m.routeKey = res.routeKey; m.room = info.getName();
                            m.how = res.how; m.matchRate = res.matchRate;
                            m.stepIndex = idx; m.routeVariant = route.variant;
                            m.pos = key(bx, by, bz);
                            try {
                                m.state = info.getBlock(bx, by, bz, 0);
                                m.block = name(m.state);
                                m.legacyId = LegacyBlock.legacyId(m.state);
                                if (m.state.isAir() || "minecraft:bedrock".equals(m.block)) {
                                    m.nearBlock = nearestDiggable(info, bx, by, bz);
                                }
                            } catch (Throwable ignored) {
                            }
                            m.inProtected = breakerProtected.contains(m.legacyId);
                            m.mechName = mech.get(m.pos);
                            m.inMechBlocked = m.mechName != null;
                            if (gates != null) {
                                for (Gate g : gates) {
                                    if (g.targetBlocked.contains(m.pos)) {
                                        m.inGate = true;
                                        m.gateName = g.kind + "->" + g.targetKey;
                                        break;
                                    }
                                }
                            }
                            mines.add(m);
                            types.add(m.block);
                            minesPerRoom.merge(info.getName(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        allMines = mines;
        minedTypes = types;
    }

    private static String topRooms(Map<String, Integer> rooms, int n) {
        if (rooms == null) return "";
        StringBuilder sb = new StringBuilder();
        rooms.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n).forEach(e -> sb.append(e.getKey()).append('(').append(e.getValue()).append(") "));
        return sb.toString().trim();
    }

    private static String abbrev(Set<String> items, int n) {
        List<String> l = new ArrayList<>(items);
        if (l.size() <= n) return String.join(", ", l);
        return String.join(", ", l.subList(0, n)) + ", +(" + (l.size() - n) + " more)";
    }

    // ---------- Task 1 + 2a: what do real players mine? ----------

    @Test
    void communityMineTally() throws Exception {
        ensureMines();

        Map<String, Integer> byType = new TreeMap<>();
        Map<String, Integer> byTypeLegacy = new TreeMap<>();
        Map<String, Map<String, Integer>> typeRooms = new HashMap<>();
        int air = 0, lookupFail = 0, protectedCnt = 0, mechCnt = 0, gateCnt = 0;
        List<Mine> forbiddenHits = new ArrayList<>();
        for (Mine m : allMines) {
            byType.merge(m.block, 1, Integer::sum);
            byTypeLegacy.merge(m.block + "|" + m.legacyId, 1, Integer::sum);
            typeRooms.computeIfAbsent(m.block, k -> new TreeMap<>()).merge(m.room, 1, Integer::sum);
            if (m.state != null && m.state.isAir()) air++;
            if (m.state == null) lookupFail++;
            if (m.inProtected) protectedCnt++;
            if (m.inMechBlocked) mechCnt++;
            if (m.inGate) gateCnt++;
            if (m.inProtected || m.inMechBlocked) forbiddenHits.add(m);
        }

        try (PrintWriter w = openReport("1-community-mines.txt")) {
            w.println("=== community 'mine' entries resolved to canonical blocks ===");
            w.println("total mines=" + allMines.size() + " air=" + air + " lookupFail=" + lookupFail
                    + " inBREAKER_PROTECTED=" + protectedCnt + " inMechanicBlocked=" + mechCnt
                    + " inLeverGatedBlocked=" + gateCnt);
            w.println("rooms with mines: " + minesPerRoom.size());
            w.println();
            w.println("--- mined block types (name|legacyId -> count) ---");
            byTypeLegacy.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> {
                        String block = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
                        w.println(String.format("%6d %s   rooms: %s", e.getValue(), e.getKey(),
                                topRooms(typeRooms.get(block), 8)));
                    });
            w.println();
            w.println("--- mined blocks our rules FORBID (rules-too-strict candidates) ---");
            for (Mine m : forbiddenHits) {
                w.println(String.format("  %s pack=%s room=%s route=%s:%d step=%d pos=%s why=%s mech=%s gate=%s how=%s fit=%.2f near=%s",
                        m.block, m.pack, m.room, m.routeKey, m.routeVariant, m.stepIndex,
                        posStr(m.pos), m.inProtected ? "BREAKER_PROTECTED" : "mechanic",
                        m.mechName == null ? "-" : m.mechName,
                        m.gateName == null ? "-" : m.gateName,
                        m.how, m.matchRate, m.nearBlock == null ? "-" : m.nearBlock));
            }
            w.println();
            w.println("--- per-room mine counts ---");
            minesPerRoom.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.println(String.format("%6d %s", e.getValue(), e.getKey())));
            w.println();
            w.println("--- air hits (mapping suspects) ---");
            for (Mine m : allMines) {
                if (m.state != null && m.state.isAir())
                    w.println(String.format("  pack=%s room=%s route=%s:%d step=%d pos=%s how=%s fit=%.2f near=%s",
                            m.pack, m.room, m.routeKey, m.routeVariant, m.stepIndex, posStr(m.pos),
                            m.how, m.matchRate, m.nearBlock == null ? "-" : m.nearBlock));
            }
        }
    }

    // ---------- Task 2b + sanity + gates: single scan of all generated .pfres ----------

    // NodeType ordinals (PathfindResult.PathfindNode.NodeType order)
    private static final int NT_STONK_EXIT = 0, NT_STONK_WALK = 1, NT_WALK = 2, NT_ETHERWARP = 3,
            NT_DIG_DOWN = 4, NT_ECHEST = 5, NT_DIG_UP = 6, NT_TELEPORT_INTO = 7,
            NT_SUPERBOOM = 8, NT_TNTPEARL = 9, NT_ENDERPEARL = 10, NT_DESTINATION = 11,
            NT_UNPOPULATED = 12;
    private static final String[] NT_NAMES = {
            "STONK_EXIT", "STONK_WALK", "WALK", "ETHERWARP", "DIG_DOWN", "ECHEST",
            "DIG_UP", "TELEPORT_INTO", "SUPERBOOM", "TNTPEARL", "ENDERPEARL", "DESTINATION"};

    private static Field pfArrayField() throws Exception {
        Field f = PrecalculatedPathfinder.class.getDeclaredField("array");
        f.setAccessible(true);
        return f;
    }

    private static boolean isDig(int t) {
        return t == NT_STONK_WALK || t == NT_STONK_EXIT || t == NT_DIG_DOWN || t == NT_DIG_UP;
    }

    private static boolean isTeleport(int t) {
        return t == NT_ETHERWARP || t == NT_ECHEST || t == NT_TELEPORT_INTO || t == NT_ENDERPEARL;
    }

    // ---------- spot check: do the CURRENT collision rules mark flagged dig cells STONKING? ----------

    /**
     * The packaged .pfres contain dig-type nodes (STONK_WALK, STONK_EXIT, DIG_DOWN,
     * DIG_UP) whose canon block resolves to bedrock or BREAKER_PROTECTED types.
     * This test rebuilds the request-time world
     * (DRIWorld + the file's own stored AlgorithmSetting) and queries the live
     * CollisionState at those exact node coords:
     *  - STONKING* means the current rules would regenerate the same diggable cell
     *    (the flagged dig is faithful generation, i.e. a real rules gap)
     *  - BLOCKED* means the stored file was produced by different semantics than the
     *    current code (generation-time divergence worth reporting)
     */
    @Test
    void spotCheck() throws Exception {
        File dir = new File(System.getProperty("dg.breakerPresetDir", DEFAULT_PRESET_DIR));
        File[] files = dir.listFiles((d, n) -> n.endsWith(".pfres"));
        Assumptions.assumeTrue(files != null && files.length > 0);
        Field arrayF = pfArrayField();

        try (PrintWriter w = openReport("5-spot-check.txt")) {
            int filesChecked = 0, samples = 0;
            Map<String, AtomicInteger> verdict = new TreeMap<>();
            for (File f : files) {
                if (samples > 120 || filesChecked > 12) break;
                PathfindPrecalculation p = new PathfindPrecalculation(f);
                DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(p.getRoomUID());
                if (info == null || !info.hasSchematic()) continue;

                // scan the stored tree for dig cells landing on bedrock/protected blocks
                PrecalculatedPathfinder pf = (PrecalculatedPathfinder) p.createPathfinder(0);
                List<String> flagged = new ArrayList<>();
                try {
                    pf.init2();
                    java.nio.ByteBuffer buf = (java.nio.ByteBuffer) arrayF.get(pf);
                    int x0 = p.getXStart(), y0 = p.getYStart(), z0 = p.getZStart();
                    int xLen = p.getXLen(), yLen = p.getYLen(), zLen = p.getZLen();
                    for (int y = 0; y < yLen && flagged.size() < 6; y++)
                        for (int z = 0; z < zLen && flagged.size() < 6; z++)
                            for (int x = 0; x < xLen && flagged.size() < 6; x++) {
                                int type = buf.get((y * xLen * zLen + z * xLen + x) * 8 + 3) & 0xFF;
                                if (!isDig(type)) continue;
                                int nx = x0 + x, ny = y0 + y, nz = z0 + z;
                                int pbx = Math.floorDiv(nx, 2), pby = Math.floorDiv(ny, 2) - 70,
                                        pbz = Math.floorDiv(nz, 2);
                                int[] xs = (nx % 2 == 0) ? new int[]{pbx - 1, pbx} : new int[]{pbx};
                                int[] zs = (nz % 2 == 0) ? new int[]{pbz - 1, pbz} : new int[]{pbz};
                                StringBuilder blocks = new StringBuilder();
                                boolean suspicious = false;
                                for (int bxi : xs) for (int bzi : zs) for (int byi = pby; byi <= pby + 1; byi++) {
                                    BlockState s;
                                    try { s = info.getBlock(bxi, byi, bzi, 0); } catch (Throwable th) { continue; }
                                    String bn = name(s);
                                    if (blocks.length() > 0) blocks.append('+');
                                    blocks.append(bn.replace("minecraft:", ""));
                                    if ("minecraft:bedrock".equals(bn)
                                            || breakerProtected.contains(LegacyBlock.legacyId(s)))
                                        suspicious = true;
                                }
                                if (suspicious)
                                    flagged.add(nx + "," + ny + "," + nz + " " + NT_NAMES[type]
                                            + " feet=" + pbx + "," + pby + "," + pbz + " " + blocks);
                            }
                } finally {
                    pf.close();
                }
                p.evictBuffer();
                if (flagged.isEmpty()) continue;
                filesChecked++;

                // rebuild the request-time world with the file's own stored setting
                String[] parts = p.getTargetId().split(":", 3);
                Set<String> openMech = new HashSet<>();
                if (parts.length > 1 && !parts[1].isEmpty()) openMech.addAll(java.util.Arrays.asList(parts[1].split(",")));
                kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting setting = p.getAlgorithmSetting();
                kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset preset =
                        new kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset();
                preset.setAlgorithmSetting(setting);
                kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld world =
                        new kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld(info, new ArrayList<>(openMech), preset);

                w.println("=== " + info.getName() + " " + f.getName()
                        + " openMech=" + openMech + " storedSetting.breaker=" + setting.isDungeonBreaker()
                        + " digBudget=" + setting.getDigBudget() + " ===");
                for (String line : flagged) {
                    String[] nxyz = line.split(" ")[0].split(",");
                    int nx = Integer.parseInt(nxyz[0]), ny = Integer.parseInt(nxyz[1]), nz = Integer.parseInt(nxyz[2]);
                    kr.syeyoung.dungeonsguide.mod.dungeon.world.CollisionStateCalculatingCoordinateMap.CollisionState st =
                            world.getPathfindWorld().getBlock(nx, ny, nz);
                    verdict.computeIfAbsent(st.name(), kk -> new AtomicInteger()).incrementAndGet();
                    w.println("  " + line + "  -> live state=" + st);
                    samples++;
                }
            }
            w.println("verdict histogram: " + verdict);
        }
    }

    @Test
    void generatedDugBlocks() throws Exception {
        ensureMines();
        String dir = System.getProperty("dg.breakerPresetDir", DEFAULT_PRESET_DIR);
        File presetDir = new File(dir);
        Assumptions.assumeTrue(presetDir.isDirectory(), "preset dir missing: " + dir);
        File[] files = presetDir.listFiles((d, n) -> n.endsWith(".pfres"));
        Assumptions.assumeTrue(files != null && files.length > 0);
        final Field arrayF = pfArrayField();

        Map<UUID, DungeonRoomInfo> infoByUuid = new HashMap<>();
        for (DungeonRoomInfo info : rooms) infoByUuid.put(info.getUuid(), info);

        Map<String, AtomicLong> dugFeet = new ConcurrentHashMap<>();
        Map<String, AtomicLong> teleSolid = new ConcurrentHashMap<>();
        Map<String, Set<String>> dugRooms = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> typeHist = new ConcurrentHashMap<>();
        Map<UUID, AtomicInteger> filesPerRoom = new ConcurrentHashMap<>();
        Map<UUID, AtomicLong> dugPerRoom = new ConcurrentHashMap<>();
        Map<UUID, Set<String>> dugTypesPerRoom = new ConcurrentHashMap<>();
        Map<UUID, AtomicInteger> filesWithDigsPerRoom = new ConcurrentHashMap<>();
        // task-3/sanity aggregates
        Map<UUID, AtomicLong> digInsideGate = new ConcurrentHashMap<>();
        Map<UUID, AtomicLong> digAdjGate = new ConcurrentHashMap<>();
        Map<UUID, AtomicLong> digInsideMech = new ConcurrentHashMap<>();
        Map<UUID, Map<String, AtomicLong>> protectedDug = new ConcurrentHashMap<>();
        // bedrock dug-cell classification: border/void vs real interior bedrock
        Map<String, AtomicLong> bedrockCats = new ConcurrentHashMap<>();
        List<String> interiorBedrock = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger badFiles = new AtomicInteger();
        List<String> badList = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (File f : files) {
            pool.submit(() -> {
                try {
                    PathfindPrecalculation p = new PathfindPrecalculation(f);
                    DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(p.getRoomUID());
                    if (info == null || !info.hasSchematic()) return;
                    filesPerRoom.computeIfAbsent(info.getUuid(), k -> new AtomicInteger()).incrementAndGet();
                    Set<Long> mech = roomMech.get(info).keySet();
                    List<Gate> gates = roomGates.get(info);
                    PrecalculatedPathfinder pf = (PrecalculatedPathfinder) p.createPathfinder(0);
                    try {
                        pf.init2();
                        java.nio.ByteBuffer buf = (java.nio.ByteBuffer) arrayF.get(pf);
                        int x0 = p.getXStart(), y0 = p.getYStart(), z0 = p.getZStart();
                        int xLen = p.getXLen(), yLen = p.getYLen(), zLen = p.getZLen();
                        Set<Long> seen = new HashSet<>();
                        boolean anyDig = false;
                        for (int y = 0; y < yLen; y++)
                            for (int z = 0; z < zLen; z++)
                                for (int x = 0; x < xLen; x++) {
                                    int type = buf.get((y * xLen * zLen + z * xLen + x) * 8 + 3) & 0xFF;
                                    if (type == NT_UNPOPULATED) continue;
                                    typeHist.computeIfAbsent(
                                            type < NT_NAMES.length ? NT_NAMES[type] : "?" + type,
                                            k -> new AtomicInteger()).incrementAndGet();
                                    if (!isDig(type) && !isTeleport(type)) continue;
                                    // node coords are oct-world indices = canon pos *2: verified
                                    // against this file's TRGT entries (DESTINATION node coords
                                    // exactly equal the stored target ints, written as
                                    // 2*canonX / 2*canonY+140 / 2*canonZ in PathfindRequest:163).
                                    // So canon block = floorDiv(n,2), canon y = floorDiv(ny,2)-70.
                                    // A cell on an even boundary (integer canon pos) straddles two
                                    // blocks - the AABB is [pos-0.25, pos+0.25] - so BOTH columns
                                    // must be clear to stand. The dug volume per cell is the
                                    // overlapped column(s) at feet and head; the block below stays.
                                    int nx = x0 + x, ny = y0 + y, nz = z0 + z;
                                    int pbx = Math.floorDiv(nx, 2), pby = Math.floorDiv(ny, 2) - 70,
                                            pbz = Math.floorDiv(nz, 2);
                                    int[] xs = (nx % 2 == 0) ? new int[]{pbx - 1, pbx} : new int[]{pbx};
                                    int[] zs = (nz % 2 == 0) ? new int[]{pbz - 1, pbz} : new int[]{pbz};
                                    int[] ys = isTeleport(type) ? new int[]{pby} : new int[]{pby, pby + 1};
                                    for (int bxi : xs) for (int bzi : zs) for (int byi : ys) {
                                        long k = key(bxi, byi, bzi);
                                        if (!seen.add(k)) continue;
                                        BlockState s;
                                        try { s = info.getBlock(bxi, byi, bzi, 0); } catch (Throwable th) { continue; }
                                        if (s.isAir()) continue;
                                        String bn = name(s);
                                        if (isDig(type)) {
                                            anyDig = true;
                                            dugFeet.computeIfAbsent(bn, kk -> new AtomicLong()).incrementAndGet();
                                            dugRooms.computeIfAbsent(bn, kk -> ConcurrentHashMap.newKeySet()).add(info.getName());
                                            dugPerRoom.computeIfAbsent(info.getUuid(), kk -> new AtomicLong()).incrementAndGet();
                                            dugTypesPerRoom.computeIfAbsent(info.getUuid(), kk -> ConcurrentHashMap.newKeySet()).add(bn);
                                            if ("minecraft:bedrock".equals(bn)) {
                                                String cat = byi < -70 ? "below-room"
                                                        : bxi <= 0 ? "x-edge-min" : bxi >= info.getWidth() ? "x-edge-max"
                                                        : bzi <= 0 ? "z-edge-min" : bzi >= info.getLength() ? "z-edge-max"
                                                        : byi >= 186 ? "above-room" : "INTERIOR-BEDROCK";
                                                bedrockCats.computeIfAbsent(cat, kk -> new AtomicLong()).incrementAndGet();
                                                if ("INTERIOR-BEDROCK".equals(cat) && interiorBedrock.size() < 40)
                                                    interiorBedrock.add(info.getName() + " " + f.getName()
                                                            + " node=" + nx + "," + ny + "," + nz + " canon=" + posStr(k)
                                                            + " nt=" + NT_NAMES[type]);
                                            }
                                            if (breakerProtected.contains(LegacyBlock.legacyId(s))) {
                                                protectedDug.computeIfAbsent(info.getUuid(), kk -> new ConcurrentHashMap<>())
                                                        .computeIfAbsent(bn, kk -> new AtomicLong()).incrementAndGet();
                                            }
                                            if (mech.contains(k))
                                                digInsideMech.computeIfAbsent(info.getUuid(), kk -> new AtomicLong()).incrementAndGet();
                                            if (gates != null) {
                                                for (Gate g : gates) {
                                                    if (g.targetBlocked.contains(k)) {
                                                        digInsideGate.computeIfAbsent(info.getUuid(), kk -> new AtomicLong()).incrementAndGet();
                                                        break;
                                                    }
                                                    if (g.targetNear2.contains(k)) {
                                                        digAdjGate.computeIfAbsent(info.getUuid(), kk -> new AtomicLong()).incrementAndGet();
                                                        break;
                                                    }
                                                }
                                            }
                                        } else {
                                            teleSolid.computeIfAbsent(bn, kk -> new AtomicLong()).incrementAndGet();
                                        }
                                    }
                                }
                        if (anyDig)
                            filesWithDigsPerRoom.computeIfAbsent(info.getUuid(), k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        pf.close();
                    }
                    p.evictBuffer();
                } catch (Throwable t) {
                    badFiles.incrementAndGet();
                    if (badList.size() < 20) badList.add(f.getName() + ": " + t);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);

        try (PrintWriter w = openReport("2-generated-dug.txt")) {
            w.println("=== generated breaker-etherwarp .pfres dig scan (" + files.length + " files) ===");
            w.println("node type histogram: " + new TreeMap<>(typeHist));
            w.println("bad files: " + badFiles.get() + " " + badList);
            w.println();
            w.println("--- dug block types (STONK_WALK/STONK_EXIT/DIG_DOWN/DIG_UP cells, unique per pfres, non-air) ---");
            dugFeet.entrySet().stream()
                    .sorted(Map.Entry.<String, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed())
                    .forEach(e -> w.println(String.format("%8d %s   rooms(%d): %s",
                            e.getValue().get(), e.getKey(),
                            dugRooms.get(e.getKey()).size(),
                            abbrev(new TreeSet<>(dugRooms.get(e.getKey())), 10))));
            w.println();
            w.println("--- teleport-family nodes landing inside solid blocks (informational) ---");
            teleSolid.entrySet().stream()
                    .sorted(Map.Entry.<String, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed())
                    .forEach(e -> w.println(String.format("%8d %s", e.getValue().get(), e.getKey())));
            w.println();
            w.println("--- 'bedrock' dug cells classified by coordinate bounds (border/void vs real interior bedrock) ---");
            new TreeMap<>(bedrockCats).forEach((cat, n) -> w.println("  " + n.get() + " " + cat));
            for (String s : interiorBedrock) w.println("    " + s);
            w.println();
            w.println("--- dug types NEVER mined by community routes (candidate un-mineable) ---");
            dugFeet.entrySet().stream()
                    .filter(e -> !minedTypes.contains(e.getKey()))
                    .sorted(Map.Entry.<String, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed())
                    .forEach(e -> w.println(String.format("%8d %s   rooms: %s", e.getValue().get(), e.getKey(),
                            abbrev(new TreeSet<>(dugRooms.get(e.getKey())), 15))));
            w.println();
            w.println("--- community-mined types we never dig (informational) ---");
            for (String mt : new TreeSet<>(minedTypes))
                if (!dugFeet.containsKey(mt)) w.println("  " + mt);
            w.println();
            w.println("--- per-room dug-block totals + pfres counts ---");
            dugPerRoom.entrySet().stream()
                    .sorted(Map.Entry.<UUID, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed())
                    .forEach(e -> {
                        DungeonRoomInfo info = DungeonRoomInfoRegistry.getByUUID(e.getKey());
                        w.println(String.format("%8d %-30s pfres=%d withDigs=%d", e.getValue().get(),
                                info == null ? e.getKey().toString() : info.getName(),
                                filesPerRoom.getOrDefault(e.getKey(), new AtomicInteger()).get(),
                                filesWithDigsPerRoom.getOrDefault(e.getKey(), new AtomicInteger()).get()));
                    });
        }

        try (PrintWriter w = openReport("4-generated-vs-gates.txt")) {
            w.println("=== generated digs vs lever gates + protected/mech sanity ===");
            w.println("sanity: dug cells inside any mechanic blockedPoints (should be ~0):");
            List<Map.Entry<UUID, AtomicLong>> mechList = new ArrayList<>(digInsideMech.entrySet());
            mechList.sort(Map.Entry.<UUID, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed());
            for (Map.Entry<UUID, AtomicLong> e : mechList)
                w.println("  " + e.getValue().get() + " " + infoByUuid.get(e.getKey()).getName());
            w.println("sanity: dug BREAKER_PROTECTED block types per room (should be 0):");
            for (Map.Entry<UUID, Map<String, AtomicLong>> e : protectedDug.entrySet())
                w.println("  " + infoByUuid.get(e.getKey()).getName() + " " + e.getValue());
            w.println();
            w.println("--- digs inside / adjacent (cheb<=2) to lever-gated blockedPoints ---");
            for (Map.Entry<DungeonRoomInfo, List<Gate>> e : roomGates.entrySet()) {
                DungeonRoomInfo info = e.getKey();
                w.println(String.format("  %-25s inside=%d adj2=%d totalDug=%d communityMines=%d",
                        info.getName(),
                        digInsideGate.getOrDefault(info.getUuid(), new AtomicLong()).get(),
                        digAdjGate.getOrDefault(info.getUuid(), new AtomicLong()).get(),
                        dugPerRoom.getOrDefault(info.getUuid(), new AtomicLong()).get(),
                        minesPerRoom.getOrDefault(info.getName(), 0)));
            }
            w.println();
            w.println("--- rooms our preset digs in but community routes NEVER mine (whole-room protection candidates) ---");
            List<Map.Entry<UUID, AtomicLong>> noMine = new ArrayList<>(dugPerRoom.entrySet());
            noMine.sort(Map.Entry.<UUID, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed());
            for (Map.Entry<UUID, AtomicLong> e : noMine) {
                DungeonRoomInfo info = infoByUuid.get(e.getKey());
                if (info == null) continue;
                if (!minesPerRoom.containsKey(info.getName()) && e.getValue().get() > 0) {
                    w.println(String.format("  %-25s dugBlocks=%d pfres=%d", info.getName(), e.getValue().get(),
                            filesPerRoom.getOrDefault(e.getKey(), new AtomicInteger()).get()));
                }
            }
            w.println();
            w.println("--- rooms with community mines but zero generated digs (informational) ---");
            for (Map.Entry<String, Integer> e : minesPerRoom.entrySet()) {
                boolean dug = false;
                for (Map.Entry<UUID, AtomicLong> de : dugPerRoom.entrySet()) {
                    DungeonRoomInfo info = infoByUuid.get(de.getKey());
                    if (info != null && info.getName().equals(e.getKey()) && de.getValue().get() > 0) dug = true;
                }
                if (!dug) w.println("  " + e.getKey() + " mines=" + e.getValue());
            }
        }
    }

    // ---------- Task 3: lever / switch gating ----------

    @Test
    void leverGating() throws Exception {
        ensureMines();

        try (PrintWriter w = openReport("3-lever-gating.txt")) {
            w.println("=== lever/switch gating analysis ===");
            w.println("rooms with lever-type mechanics: " + roomGates.size());
            for (Map.Entry<DungeonRoomInfo, List<Gate>> e : roomGates.entrySet()) {
                for (Gate g : e.getValue())
                    w.println(String.format("  %s: %s '%s' -> '%s' (mutating=%s, blocked=%d, at %s)",
                            e.getKey().getName(), g.kind, g.mechKey, g.targetKey,
                            g.targetMutating, g.targetBlocked.size(), posStr(g.triggerPos)));
            }
            w.println();

            // rooms containing each WorldMutating mechanic class (precalc-impact scope)
            w.println("--- rooms containing each WorldMutating mechanic type ---");
            Map<String, Set<String>> typeRooms = new TreeMap<>();
            for (DungeonRoomInfo info : rooms) {
                for (String tag : new HashSet<>(mechanicOwners(info).values())) {
                    String type = tag.substring(tag.lastIndexOf(':') + 1);
                    typeRooms.computeIfAbsent(type, k -> new TreeSet<>()).add(info.getName());
                }
            }
            for (Map.Entry<String, Set<String>> e : typeRooms.entrySet())
                w.println(String.format("  %-35s rooms=%d  %s", e.getKey(), e.getValue().size(),
                        abbrev(new TreeSet<>(e.getValue()), 12)));
            w.println();

            // per gate per route: earliest interact step near the trigger vs earliest mine inside gated blocks
            w.println("--- per-gate: earliest lever-interact step vs earliest mine-inside-gate step ---");
            for (Map.Entry<DungeonRoomInfo, List<Gate>> e : roomGates.entrySet()) {
                DungeonRoomInfo info = e.getKey();
                for (RoutePack pack : packs) {
                    RouteRoomMapper.Resolution res = RouteRoomMapper.resolve(info, pack, 70);
                    if (!res.matched()) continue;
                    List<CommunityRoute> routes = pack.routesFor(res.routeKey);
                    if (routes == null) continue;
                    for (CommunityRoute route : routes) {
                        int idx = -1;
                        for (Gate g : e.getValue()) {
                            if (g.targetBlocked.isEmpty()) continue;
                            int firstLever = -1, firstGateMine = -1;
                            idx = -1;
                            for (RouteStep step : route.steps) {
                                idx++;
                                for (RouteAction a : step.actions) {
                                    RouteVec3 c = res.transform.apply(a.pos);
                                    int bx = (int) Math.round(c.x), by = (int) Math.round(c.y),
                                            bz = (int) Math.round(c.z);
                                    if (a.kind == RouteAction.Kind.INTERACT && firstLever < 0
                                            && cheb(key(bx, by, bz), g.triggerPos) <= 2) {
                                        firstLever = idx;
                                    }
                                    if (a.kind == RouteAction.Kind.BREAKER && firstGateMine < 0
                                            && g.targetBlocked.contains(key(bx, by, bz))) {
                                        firstGateMine = idx;
                                    }
                                }
                            }
                            if (firstLever >= 0 || firstGateMine >= 0) {
                                w.println(String.format("  %s %s:%d gate=%s->%s leverStep=%d gateMineStep=%d",
                                        info.getName(), res.routeKey, route.variant,
                                        g.mechKey, g.targetKey, firstLever, firstGateMine));
                            }
                        }
                    }
                }
            }
            w.println();

            // per lever room: community mines and their distance to gate blocks
            for (Map.Entry<DungeonRoomInfo, List<Gate>> e : roomGates.entrySet()) {
                DungeonRoomInfo info = e.getKey();
                List<Gate> gates = e.getValue();
                List<Mine> roomMines = new ArrayList<>();
                for (Mine m : allMines) if (m.room.equals(info.getName())) roomMines.add(m);
                w.println("### " + info.getName() + " gates=" + gates.size() + " communityMines=" + roomMines.size());
                for (Mine m : roomMines) {
                    int best = Integer.MAX_VALUE;
                    Gate bestGate = null;
                    for (Gate g : gates) {
                        for (long bp : g.targetBlocked) {
                            int d = cheb(m.pos, bp);
                            if (d < best) { best = d; bestGate = g; }
                        }
                    }
                    if (best <= 4) {
                        w.println(String.format("  mine %s step=%d dist=%d to gate %s->%s (%s) pos=%s",
                                m.block, m.stepIndex, best,
                                bestGate == null ? "?" : bestGate.mechKey,
                                bestGate == null ? "?" : bestGate.targetKey, m.pack, posStr(m.pos)));
                    }
                }
            }
            w.println();

            // route step order: INTERACT (lever clicks) vs BREAKER steps in lever rooms
            w.println("--- route step order in lever rooms (SRM packs; Odin has no explicit ordering) ---");
            for (Map.Entry<DungeonRoomInfo, List<Gate>> e : roomGates.entrySet()) {
                DungeonRoomInfo info = e.getKey();
                for (RoutePack pack : packs) {
                    if (pack.format != RoutePack.Format.SRM) continue;
                    RouteRoomMapper.Resolution res = RouteRoomMapper.resolve(info, pack, 70);
                    if (!res.matched()) continue;
                    List<CommunityRoute> routes = pack.routesFor(res.routeKey);
                    if (routes == null) continue;
                    for (CommunityRoute route : routes) {
                        StringBuilder seq = new StringBuilder();
                        int idx = -1;
                        boolean hasMine = false, hasInteract = false;
                        for (RouteStep step : route.steps) {
                            idx++;
                            boolean mine = false, inter = false;
                            for (RouteAction a : step.actions) {
                                if (a.kind == RouteAction.Kind.BREAKER) mine = true;
                                if (a.kind == RouteAction.Kind.INTERACT) inter = true;
                            }
                            if (mine) hasMine = true;
                            if (inter) hasInteract = true;
                            if (mine && inter) seq.append(" [M+I]").append(idx);
                            else if (mine) seq.append(" M").append(idx);
                            else if (inter) seq.append(" I").append(idx);
                        }
                        if (hasMine || hasInteract) {
                            w.println(String.format("  %s %s:%d%s", info.getName(), res.routeKey, route.variant, seq));
                        }
                    }
                }
            }
            w.println();
            // do community mines sit inside gated blocks at all?
            w.println("--- community mines inside lever-gated blockedPoints ---");
            int n = 0;
            for (Mine m : allMines) if (m.inGate) {
                n++;
                w.println(String.format("  %s room=%s pack=%s step=%d pos=%s", m.block, m.room, m.pack, m.stepIndex, posStr(m.pos)));
            }
            w.println("total: " + n);
        }
    }

}

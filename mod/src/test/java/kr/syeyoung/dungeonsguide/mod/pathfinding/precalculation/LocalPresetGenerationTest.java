package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractActionMove;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AtomicAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.HeuristicTSP;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// P4g: emit .pfreq request files for the DG-native "Walk + Etherwarp" and
// "Dungeon Breaker + Etherwarp" presets, package the generated .pfres into preset JSON,
// and build per-preset TSP caches. Same request set as the shipped default preset:
// every shipped .pfres is mirrored 1:1 (same room, open-mechanic state, targets).
//
//   -Pdg.configDir=<real DG config dir>
//   -Pdg.genOut=<output root>           (default build/local-presets)
//   -Pdg.precalcIds=<id1,id2,...>       (default: every shipped .pfres)
//   -Pdg.genPresets=etherwarp,breaker   (default: both)
//   -Pdg.genThreads=<n>                 (default: 8, request writing is CPU-bound)
//
// generateRequests writes <out>/requests-<mode>/<shippedId>.pfreq for pathfinder-cli.
// packagePresets turns <out>/results-<mode>/*.pfres into
//   <out>/presets/<mode>.json + <out>/precalculations-<mode>/<presetId>/*.pfres.
// buildTspCaches writes <out>/tspcache-<mode>/*.cache.
public class LocalPresetGenerationTest {

    // Dungeon Breaker charges per run path; matches Hypixel's 20-charge item
    private static final int DIG_BUDGET = 20;

    private static class Mode {
        final String key;        // dir/file suffix
        final String presetName; // shown in the precalc browser
        final AlgorithmSetting setting;
        Mode(String key, String presetName, AlgorithmSetting setting) {
            this.key = key; this.presetName = presetName; this.setting = setting;
        }
    }

    private static List<Mode> MODES;
    private static File configDir;
    private static File outDir;
    private static AlgorithmSetting shippedSetting;
    private static File[] shippedFiles;
    private static PathfindPreset shippedPreset;

    private static Mode etherwarp(AlgorithmSetting base) {
        return new Mode("walk-etherwarp", "Walk + Etherwarp",
                base.withRouteEtherwarp(true));
    }

    private static Mode breaker(AlgorithmSetting base) {
        return new Mode("breaker-etherwarp", "Dungeon Breaker + Etherwarp",
                base.withRouteEtherwarp(true)
                        .withDungeonBreaker(true)
                        .withDigBudget(DIG_BUDGET)
                        .withStonkDown(true)
                        .withMaxStonk(DIG_BUDGET)
                        .withStonkTeleport(false)
                        .withStonkEChest(false));
    }

    @BeforeAll
    static void setup() throws Exception {
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        configDir = new File(configDirProperty);
        outDir = new File(System.getProperty("dg.genOut", "build/local-presets"));
        outDir.mkdirs();

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();

        if (PathfindPrecalculationRegistry.getINSTANCE() == null)
            new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        PathfindPreset defaultPreset = null;
        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        assertNotNull(defaultPreset, "default.json preset not found");
        shippedSetting = defaultPreset.getAlgorithmSetting();
        shippedPreset = defaultPreset;

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());

        MODES = new ArrayList<>();
        Set<String> wanted = new HashSet<>(Arrays.asList(
                System.getProperty("dg.genPresets", "etherwarp,breaker").split(",")));
        if (wanted.contains("etherwarp")) MODES.add(etherwarp(shippedSetting));
        if (wanted.contains("breaker")) MODES.add(breaker(shippedSetting));
        // regen of the shipped default preset - for binary-compat checks against the
        // locally built pathfinder, not for shipping (the remote service owns default)
        if (wanted.contains("default")) MODES.add(new Mode("default", "Default (local regen)", shippedSetting));
        assertFalse(MODES.isEmpty(), "no presets selected");

        File precalcDir = new File(configDir, "precalculations/" + defaultPreset.getPresetId());
        String idProp = System.getProperty("dg.precalcIds");
        if (idProp != null && !idProp.isEmpty() && !idProp.equals("all")) {
            List<File> files = new ArrayList<>();
            for (String id : idProp.split(",")) {
                File f = new File(precalcDir, id + ".pfres");
                assertTrue(f.exists(), f + " missing");
                files.add(f);
            }
            shippedFiles = files.toArray(new File[0]);
        } else {
            shippedFiles = precalcDir.listFiles((d, n) -> n.endsWith(".pfres"));
            assertNotNull(shippedFiles);
            Arrays.sort(shippedFiles);
        }
        System.out.println("local preset generation: " + shippedFiles.length + " shipped requests, modes="
                + MODES.stream().map(m -> m.key).reduce((a, b) -> a + "," + b));
    }

    private static class Reconstructed {
        PathfindPrecalculation precalc;
        DungeonRoomInfo info;
        Set<String> openMech;
        List<OffsetVec3> targets;
    }

    private static Reconstructed reconstruct(File pfresFile) throws IOException {
        Reconstructed r = new Reconstructed();
        r.precalc = new PathfindPrecalculation(pfresFile);

        String[] parts = r.precalc.getTargetId().split(":", 3);
        assertEquals(3, parts.length, "unexpected targetId format " + r.precalc.getTargetId());
        UUID uuid = UUID.fromString(parts[0]);
        r.openMech = new HashSet<>();
        if (!parts[1].isEmpty()) r.openMech.addAll(Arrays.asList(parts[1].split(",")));
        r.targets = new ArrayList<>();
        for (String t : parts[2].split(";")) {
            String[] c = t.split(",");
            r.targets.add(new OffsetVec3(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2])));
        }
        r.info = DungeonRoomInfoRegistry.getByUUID(uuid);
        assertNotNull(r.info, "room " + uuid + " not in bundled roomdata");
        return r;
    }

    // preset object carrying one mode's algorithm setting, so DRIWorld's effective
    // per-room settings (color-62 etherwarp strip etc.) match what the request embeds
    private static PathfindPreset modePreset(Mode mode) {
        PathfindPreset preset = new PathfindPreset();
        preset.setPresetName(mode.presetName);
        preset.setAlgorithmSetting(mode.setting);
        preset.setOrigin("DG Local Generation");
        return preset;
    }

    private static File requestsDir(Mode mode) {
        File dir = new File(outDir, "requests-" + mode.key);
        dir.mkdirs();
        return dir;
    }

    private static File resultsDir(Mode mode) {
        File dir = new File(outDir, "results-" + mode.key);
        dir.mkdirs();
        return dir;
    }

    // .pfreq files sitting in the requests dir; the consumer deletes each after success,
    // so the count directly tracks the unconsumed backlog
    private static int countPending(File requestsDir, File resultsDir) {
        String[] names = requestsDir.list((d, n) -> n.endsWith(".pfreq") || n.endsWith(".tmp"));
        return names == null ? 0 : names.length;
    }

    @Test
    void generateRequests() throws Exception {
        // offline bulk-generation harness, not a unit test: only run when the
        // output root is explicitly passed so a plain `:mod:test` skips it
        Assumptions.assumeTrue(System.getProperty("dg.genOut") != null,
                "bulk generation requires -Pdg.genOut=<output root>");
        int threads = Integer.parseInt(System.getProperty("dg.genThreads", "8"));
        File[] files = shippedFiles;
        if (Boolean.getBoolean("dg.genSampleOnly"))
            files = Arrays.copyOf(files, Math.min(6, files.length));

        // preset objects are effectively read-only for DRIWorld; share one per mode
        Map<Mode, PathfindPreset> presets = new HashMap<>();
        for (Mode mode : MODES) presets.put(mode, modePreset(mode));

        AtomicInteger done = new AtomicInteger(), skipped = new AtomicInteger(), failed = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.currentTimeMillis();
        for (File shipped : files) {
            String shippedId = shipped.getName().replace(".pfres", "");
            pool.submit(() -> {
                try {
                    Reconstructed r = reconstruct(shipped);
                    for (Mode mode : MODES) {
                        File result = new File(resultsDir(mode), shippedId + ".pfres");
                        File req = new File(requestsDir(mode), shippedId + ".pfreq");
                        if (result.exists()) { skipped.incrementAndGet(); continue; }
                        if (req.exists()) continue; // already queued for the generator
                        // .pfreq files are ~6MB each; the WSL generator consumes + deletes
                        // them, so bound the backlog to keep total disk under ~2GB
                        int pending = 0;
                        while ((pending = countPending(requestsDir(mode), resultsDir(mode))) > 300)
                            Thread.sleep(5000);
                        PathfindRequest request = new PathfindRequest(
                                presets.get(mode).getRoomPreset(r.info.getUuid())
                                        .getEffectiveAlgorithmSetting(r.info),
                                r.info, r.openMech, r.targets);
                        DRIWorld world = new DRIWorld(r.info, new ArrayList<>(r.openMech), presets.get(mode));
                        File tmp = new File(requestsDir(mode), shippedId + ".tmp");
                        try (DataOutputStream dos = new DataOutputStream(
                                new BufferedOutputStream(new FileOutputStream(tmp)))) {
                            request.write(world, dos);
                        }
                        // atomic rename so the consumer never picks up a half-written file
                        if (!tmp.renameTo(req) && !result.exists())
                            throw new IOException("rename " + tmp + " -> " + req + " failed");
                    }
                    int n = done.incrementAndGet();
                    if (n % 100 == 0)
                        System.out.println("requests: " + n + "/" + shippedFiles.length
                                + " (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    failures.add(shippedId + ": " + t);
                    t.printStackTrace();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(24, TimeUnit.HOURS));
        System.out.println("request generation: " + done.get() + " done, " + skipped.get()
                + " already generated, " + failed.get() + " failed, "
                + (System.currentTimeMillis() - t0) / 1000 + "s");
        for (String f : failures) System.out.println("  FAIL " + f);
        assertEquals(0, failed.get());
        for (Mode mode : MODES)
            new File(outDir, "requests-" + mode.key + ".done").createNewFile();
    }

    // package <out>/results-<mode>/*.pfres into a loadable preset
    @Test
    void packagePresets() throws Exception {
        for (Mode mode : MODES) {
            File[] results = resultsDir(mode).listFiles((d, n) -> n.endsWith(".pfres"));
            Assumptions.assumeTrue(results != null && results.length > 0,
                    mode.key + ": no generated results yet, skipping");
            Arrays.sort(results);

            PathfindPreset preset = modePreset(mode);
            preset.setEditable(false);

            // reuse the presetId of an already-packaged file so reruns stay stable
            File presetFile = new File(outDir, "presets/" + mode.key + ".json");
            if (presetFile.exists()) {
                preset.setPresetId(PathfindPreset.loadFromFile(presetFile).getPresetId());
            }
            File precalcOut = new File(outDir, "precalculations/" + preset.getPresetId());
            precalcOut.mkdirs();

            Map<UUID, Set<String>> byRoom = new TreeMap<>();
            int copied = 0;
            for (File f : results) {
                PathfindPrecalculation precalc = new PathfindPrecalculation(f);
                File dest = new File(precalcOut, precalc.getId() + ".pfres");
                if (!dest.exists()) {
                    Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
                byRoom.computeIfAbsent(precalc.getRoomUID(), k -> new TreeSet<>())
                        .add(precalc.getId());
            }

            // RoomPreset.loadFromJson fills the precalc id set without touching registries
            for (Map.Entry<UUID, Set<String>> entry : byRoom.entrySet()) {
                JsonObject roomJson = new JsonObject();
                roomJson.addProperty("id", entry.getKey().toString());
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                entry.getValue().forEach(arr::add);
                roomJson.add("precalculations", arr);
                preset.getPresets().put(entry.getKey(), RoomPreset.loadFromJson(preset, roomJson));
            }

            presetFile.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(presetFile)) {
                com.google.gson.stream.JsonWriter w = new com.google.gson.stream.JsonWriter(
                        new OutputStreamWriter(new BufferedOutputStream(os)));
                new Gson().toJson(preset.saveToJson(), w);
                w.flush();
            }
            System.out.println(mode.key + ": packaged " + results.length + " precalcs across "
                    + byRoom.size() + " rooms -> " + presetFile + " (id " + preset.getPresetId()
                    + ", " + copied + " new copies)");
        }
    }

    @Test
    void buildTspCaches() throws Exception {
        for (Mode mode : MODES) {
            File presetFile = new File(outDir, "presets/" + mode.key + ".json");
            Assumptions.assumeTrue(presetFile.exists(), mode.key + " not packaged yet, skipping");
            PathfindPreset preset = PathfindPreset.loadFromFile(presetFile);

            File tspDir = new File(outDir, "tspcache-" + mode.key);
            tspDir.mkdirs();
            TSPCacheRegistry registry = new TSPCacheRegistry(tspDir, false);

            // generated precalcs must be resolvable by id for the cost table
            File precalcOut = new File(outDir, "precalculations/" + preset.getPresetId());
            for (File f : Objects.requireNonNull(precalcOut.listFiles((d, n) -> n.endsWith(".pfres")))) {
                PathfindPrecalculationRegistry.getINSTANCE().register(new PathfindPrecalculation(f));
            }

            AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            long t0 = System.currentTimeMillis();
            for (RoomPreset roomPreset : preset.getPresets().values()) {
                if (roomPreset.getPrecalculations().isEmpty()) continue;
                pool.submit(() -> {
                    try {
                        registry.new TSPCacheCalculationTask(new AtomicInteger(0), roomPreset).run();
                        done.incrementAndGet();
                    } catch (Throwable t) {
                        failed.incrementAndGet();
                        System.out.println("  TSP FAIL " + roomPreset.getRoomId() + ": " + t);
                        t.printStackTrace();
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(2, TimeUnit.HOURS));
            System.out.println(mode.key + " tsp caches: " + done.get() + " rooms, "
                    + failed.get() + " failed, " + (System.currentTimeMillis() - t0) / 1000 + "s");
            assertEquals(0, failed.get());
        }
    }

    // P4k: opt-in (-Pdg.genExtraDoorCombos). The Smart Route TSP hashes a move
    // request for every (target, openMechanics) pair it evaluates, and the open
    // bitset ranges over ALL supersets of the already-open doors
    // (AbstractActionMove.evalulateCost: 1 bit per door mechanic, bits only OR'd
    // on by ActionChangeState). The shipped presets only generated the
    // door-closed variant of most targets - RouteSweepTest found 134 misses -
    // so every other combination falls back to live A*. This enumerates, per
    // room, every move target appearing in the Smart Route DAG (union over
    // RouteSweepTest's world-state variants) x every reachable door subset,
    // and writes a .pfreq for each request hash not covered by the mode's
    // existing precalc dir.
    //
    //   -Pdg.genExtraDoorCombos          (required; off = skip)
    //   -Pdg.genPresets=default,etherwarp,breaker   (which modes to emit for)
    //   -Pdg.genOut=<output root>        (requests-<mode>/<hash>.pfreq for the CLI)
    //   -Pdg.genSampleOnly               (count combos, write nothing)
    //   -Pdg.extraCov.<mode-key>=<dir>   (existing .pfres dir for that mode;
    //                                   "default" uses configDir's shipped dir)
    //   -Pdg.genRooms=<substr,substr>    (optional room-name filter)
    //
    // packageExtras then folds <genOut>/results-<mode>/*.pfres into a copy of the
    // mode's base preset json (-Pdg.extraBasePreset.<mode-key>, shipped
    // default.json for "default") as <genOut>/presets/<mode>-extended.json.
    @Test
    void generateExtraDoorCombos() throws Exception {
        Assumptions.assumeTrue(System.getProperty("dg.genExtraDoorCombos") != null,
                "opt-in; pass -Pdg.genExtraDoorCombos to run");
        boolean sampleOnly = Boolean.getBoolean("dg.genSampleOnly");
        int maxRooms = Integer.parseInt(System.getProperty("dg.genMaxRooms", "0"));
        String roomsProp = System.getProperty("dg.genRooms");
        Set<String> roomFilter = roomsProp == null ? null
                : new HashSet<>(Arrays.asList(roomsProp.toLowerCase(Locale.ROOT).split(",")));
        if (TSPCacheRegistry.getINSTANCE() == null)
            new TSPCacheRegistry(Files.createTempDirectory("dg-tspcache").toFile(), false);

        // coverage = every request hash the mode's existing .pfres files already answer
        Map<Mode, Set<String>> coverage = new HashMap<>();
        for (Mode mode : MODES) {
            File covDir;
            if (mode.key.equals("default")) {
                covDir = new File(configDir, "precalculations/" + shippedPreset.getPresetId());
            } else {
                String prop = System.getProperty("dg.extraCov." + mode.key);
                assertNotNull(prop, "-Pdg.extraCov." + mode.key + "=<existing .pfres dir> required for " + mode.key);
                covDir = new File(prop);
            }
            File[] existing = covDir.isDirectory()
                    ? covDir.listFiles((d, n) -> n.endsWith(".pfres")) : null;
            assertNotNull(existing, covDir + ": coverage dir has no .pfres files");
            Set<String> hashes = new HashSet<>();
            for (File f : existing)
                hashes.add(new PathfindPrecalculation(f).getTargetHash());
            coverage.put(mode, hashes);
            System.out.println("extra-door-combos: " + mode.key + " coverage=" + hashes.size()
                    + " from " + covDir);
        }

        Map<Mode, PathfindPreset> presets = new HashMap<>();
        for (Mode mode : MODES) presets.put(mode, modePreset(mode));

        Map<Mode, Integer> missing = new HashMap<>();
        Map<Mode, Set<String>> queued = new HashMap<>();
        for (Mode mode : MODES) { missing.put(mode, 0); queued.put(mode, new HashSet<>()); }
        int rooms = 0, totalCombos = 0;

        for (DungeonRoomInfo info : DungeonRoomInfoRegistry.getRegistered()) {
            if (roomFilter != null && roomFilter.stream().noneMatch(
                    f -> info.getName().toLowerCase(Locale.ROOT).contains(f))) continue;
            RoomPreset roomPreset = shippedPreset.getRoomPreset(info.getUuid());
            DungeonRoom probe = TestRooms.buildRoom(roomPreset, info);
            List<String> doors = HeuristicTSP.doorMechanics(probe);
            if (doors.isEmpty()) continue;
            rooms++;

            // the TSP bitset universe: bits only get OR'd on, so every reachable
            // openMechanics set is a superset of the doors already open
            int baseBits = HeuristicTSP.initialBitset(probe, doors);
            Set<String> baseOpen = new HashSet<>();
            List<String> closed = new ArrayList<>();
            for (int i = 0; i < doors.size(); i++) {
                if ((baseBits & (1 << i)) != 0) baseOpen.add(doors.get(i));
                else closed.add(doors.get(i));
            }

            // union of move-target sets over every world state the sweep models
            Map<String, List<OffsetVec3>> targets = new LinkedHashMap<>();
            for (RouteSweepTest.Variant v : RouteSweepTest.variantsFor(probe)) {
                DungeonRoom room = TestRooms.buildRoom(roomPreset, info);
                RouteSweepTest.ForcedWorld forced = new RouteSweepTest.ForcedWorld(room.getCachedWorld());
                v.apply(room, forced);
                if (!forced.airOverride.isEmpty() || !forced.blockOverride.isEmpty())
                    RouteSweepTest.setField(room, "cachedWorld", forced);
                for (ActionDAGNode node : RouteSweepTest.buildSmartRouteDAG(room, shippedSetting).dag.getAllNodes()) {
                    AbstractAction a = node.getAction();
                    List<AbstractAction> children = a instanceof AtomicAction
                            ? ((AtomicAction) a).getActions() : Collections.singletonList(a);
                    for (AbstractAction child : children) {
                        if (!(child instanceof AbstractActionMove)) continue;
                        List<OffsetVec3> t = ((AbstractActionMove) child).getTargetOffsetPointSet();
                        if (t != null && !t.isEmpty()) targets.putIfAbsent(targetKey(t), t);
                    }
                }
            }

            // hash is setting-independent (uuid + openMech + targets); compute once,
            // check each mode's coverage, emit one .pfreq per missing mode
            int subsets = 1 << closed.size();
            int roomMissing = 0;
            Map<Mode, DRIWorld> worlds = new HashMap<>();
            for (int mask = 0; mask < subsets; mask++) {
                Set<String> open = new HashSet<>(baseOpen);
                for (int i = 0; i < closed.size(); i++)
                    if ((mask & (1 << i)) != 0) open.add(closed.get(i));
                for (List<OffsetVec3> t : targets.values()) {
                    String hash = new PathfindRequest(shippedSetting, info, open, t).getHash();
                    totalCombos++;
                    for (Mode mode : MODES) {
                        if (coverage.get(mode).contains(hash) || !queued.get(mode).add(hash)) continue;
                        File result = new File(resultsDir(mode), hash + ".pfres");
                        // resumable: a .pfreq the consumer hasn't picked up yet is
                        // as good as written - don't rewrite it
                        if (result.exists() || new File(requestsDir(mode), hash + ".pfreq").exists()) continue;
                        missing.merge(mode, 1, Integer::sum);
                        roomMissing++;
                        if (sampleOnly) continue;
                        DRIWorld world = worlds.computeIfAbsent(mode,
                                m -> new DRIWorld(info, new ArrayList<>(open), presets.get(m)));
                        PathfindRequest request = new PathfindRequest(
                                presets.get(mode).getRoomPreset(info.getUuid())
                                        .getEffectiveAlgorithmSetting(info),
                                info, open, t);
                        File tmp = new File(requestsDir(mode), hash + ".tmp");
                        try (DataOutputStream dos = new DataOutputStream(
                                new BufferedOutputStream(new FileOutputStream(tmp)))) {
                            request.write(world, dos);
                        }
                        if (!tmp.renameTo(new File(requestsDir(mode), hash + ".pfreq")) && !result.exists())
                            throw new IOException("rename " + tmp + " failed");
                    }
                }
            }
            System.out.println(info.getName() + ": doors=" + doors.size() + " (open="
                    + baseOpen.size() + ") targets=" + targets.size() + " subsets=" + subsets
                    + " newRequests=" + roomMissing);
            if (maxRooms > 0 && rooms >= maxRooms) break;
        }
        for (Mode mode : MODES)
            System.out.println("extra-door-combos: " + mode.key + " missing=" + missing.get(mode));
        System.out.println("extra-door-combos: " + rooms + " door rooms, " + totalCombos
                + " (target,openMech) combos enumerated" + (sampleOnly ? " [sample only]" : ""));
        if (!sampleOnly)
            for (Mode mode : MODES)
                new File(outDir, "requests-" + mode.key + ".done").createNewFile();
    }

    // PathfindRequest.getId's target portion: the dedupe key for move-target sets
    private static String targetKey(List<OffsetVec3> t) {
        return t.stream().sorted(Comparator
                        .<OffsetVec3>comparingDouble(a -> a.xCoord)
                        .thenComparingDouble(a -> a.zCoord)
                        .thenComparingDouble(a -> a.yCoord))
                .map(a -> a.xCoord + "," + a.yCoord + "," + a.zCoord)
                .collect(Collectors.joining(";"));
    }

    // folds <genOut>/results-<mode>/*.pfres into a copy of the mode's base preset
    // json so the extended preset lists both the original and the new door-combo
    // precalcs. The base json is only read - the output is a new presetId under
    // <genOut>/presets/<mode>-extended.json + precalculations/<presetId>/.
    @Test
    void packageExtras() throws Exception {
        Assumptions.assumeTrue(System.getProperty("dg.genExtraDoorCombos") != null,
                "opt-in; pass -Pdg.genExtraDoorCombos to run");
        // RoomPreset.addPrecalculation invalidates via the global registry
        if (TSPCacheRegistry.getINSTANCE() == null)
            new TSPCacheRegistry(Files.createTempDirectory("dg-tspcache").toFile(), false);
        for (Mode mode : MODES) {
            File[] results = resultsDir(mode).listFiles((d, n) -> n.endsWith(".pfres"));
            Assumptions.assumeTrue(results != null && results.length > 0,
                    mode.key + ": no extra results yet, skipping");

            String baseProp = System.getProperty("dg.extraBasePreset." + mode.key);
            File baseFile = baseProp != null ? new File(baseProp)
                    : mode.key.equals("default") ? new File(configDir, "presets/default.json") : null;
            assertNotNull(baseFile, "-Pdg.extraBasePreset." + mode.key
                    + "=<base preset json> required for " + mode.key);
            assertTrue(baseFile.exists(), baseFile + " missing");
            PathfindPreset preset = PathfindPreset.loadFromFile(baseFile);

            // fresh id unless re-packaging, so reruns stay stable; never reuse the
            // shipped id - this is an add-on copy, not a replacement
            File presetFile = new File(outDir, "presets/" + mode.key + "-extended.json");
            if (presetFile.exists())
                preset.setPresetId(PathfindPreset.loadFromFile(presetFile).getPresetId());
            else
                preset.setPresetId(UUID.randomUUID().toString());
            preset.setPresetName(preset.getPresetName() + " + door combos");

            File precalcOut = new File(outDir, "precalculations/" + preset.getPresetId());
            precalcOut.mkdirs();

            int copied = 0;
            for (File f : results) {
                PathfindPrecalculation precalc = new PathfindPrecalculation(f);
                File dest = new File(precalcOut, precalc.getId() + ".pfres");
                if (!dest.exists()) {
                    Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
                RoomPreset roomPreset = preset.getRoomPreset(precalc.getRoomUID());
                if (!preset.getPresets().containsKey(precalc.getRoomUID()))
                    preset.getPresets().put(precalc.getRoomUID(), roomPreset);
                roomPreset.addPrecalculation(precalc.getId());
            }

            presetFile.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(presetFile)) {
                com.google.gson.stream.JsonWriter w = new com.google.gson.stream.JsonWriter(
                        new OutputStreamWriter(new BufferedOutputStream(os)));
                new Gson().toJson(preset.saveToJson(), w);
                w.flush();
            }
            System.out.println(mode.key + ": extended preset " + presetFile + " (id "
                    + preset.getPresetId() + ", " + results.length + " extra precalcs, "
                    + copied + " copied to " + precalcOut + ")");
        }
    }
}

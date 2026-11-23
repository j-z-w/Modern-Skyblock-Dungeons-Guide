package kr.syeyoung.dungeonsguide.gametest;

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.impl.ClientTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngineLineProperties;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.classic.ClassicPathDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PrecalculatedPathfinder;
import kr.syeyoung.dungeonsguide.mod.utils.DGPerf;
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stress workload for the route/world rendering benchmark. Drives the production render
 * entry points (NeoRouteDisplayEngine.drawLinesPathfindNode, ClassicPathDisplayEngine.
 * drawLinesPathfindNode, RenderUtils beacon/text helpers) with N real precalculated routes,
 * so extraction/submit cost, allocation and draw batch counts are measurable in a
 * singleplayer world. Runs entirely in the gametest source set: install() hooks the DG
 * event bus, then DungeonsGuideClientGameTest drives it.
 *
 * BEFORE/AFTER in one binary: -Ddg.nobatch (submit batching), -Ddg.nomemo (route
 * memoization), -Ddg.nocache (segment/strip caches + re-transform each frame).
 */
public final class DGPerfBenchmark {
    private DGPerfBenchmark() {}

    private static final boolean NO_CACHE = Boolean.getBoolean("dg.nocache");

    // production defaults from ArrowPathDisplayEngineSetting, animation frozen so screenshots are deterministic
    private static final double WIDTH = 0.3, SMOOTH = 1.0;
    private static final AColor BG = new AColor(0x6400FFFF, true);
    private static final AColor ARROW = new AColor(0xFFFFFFFF, true);
    private static final NeoRouteDisplayEngineLineProperties NEO_SETTINGS = NeoRouteDisplayEngineLineProperties.builder()
            .background(BG).arrow(ARROW).width(WIDTH).smooth(SMOOTH).animationSpeed(0).destinationSize(1.0)
            .enableBeacon(true).beamColor(new AColor(0x77FF0000, true)).beaconColor(new AColor(0x33FF0000, true))
            .build();
    private static final AColor CLASSIC_LINE = new AColor(0xFF00FFFF, true);
    private static final AColor BEACON = new AColor(0x33FF0000, true);
    private static final AColor BEAM = new AColor(0x77FF0000, true);

    private static class Route {
        PrecalculatedPathfinder pathfinder;
        PathfindResult route;
        PathfindResult segmentsRoute; // the route the current segments were built from
        List<NeoRouteDisplayEngine.PathSegment> segments;
        Vec3[] anchors;
        int anchorIdx;
        BlockPos dest;
        long lastDistSqr = -1;
        String distText;
    }

    private static final List<Route> routes = new ArrayList<>();
    private static String mode = "neo"; // neo | classic | both
    // written by the gametest thread, read on the render thread every frame
    private static volatile boolean enabled;
    private static boolean installed;
    private static int tickCounter;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        // same hook the display engines use: states recorded during extraction
        DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(RenderWorldEvent.class, ListenerPriority.THIRD, e -> {
            if (enabled) record(e);
            return EventProcessResult.COMPLETE;
        });
        // same hook LiveRouteRegistry uses for per-tick route upkeep
        DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(ClientTickEvent.class, ListenerPriority.THIRD, e -> {
            if (enabled && e.phase == ClientTickEvent.Phase.END) tick();
            return EventProcessResult.COMPLETE;
        });
    }

    /** loads up to wanted routes, longest-first, from the shipped precalcs. returns a summary string. */
    public static synchronized String load(int wanted) {
        routes.clear();
        PathfindPrecalculationRegistry registry = PathfindPrecalculationRegistry.getINSTANCE();
        if (registry == null) return "no precalculation registry";
        int tried = 0;
        for (PathfindPrecalculation precalc : registry.getLoaded()) {
            if (routes.size() >= wanted) break;
            if (precalc.getTargetLocations().isEmpty() || tried++ > 400) break;
            try {
                PrecalculatedPathfinder pathfinder = (PrecalculatedPathfinder) precalc.createPathfinder(0);
                pathfinder.init2();
                OffsetVec3 t = precalc.getTargetLocations().get(0);
                // probe a spread around the target; keep the longest route (worst-case route length)
                PathfindResult best = null;
                for (int dx = -24; dx <= 24; dx += 8) {
                    for (int dz = -24; dz <= 24; dz += 8) {
                        for (int dy = 0; dy <= 6; dy += 3) {
                            PathfindResult r = pathfinder.getRoute(new Vec3(t.xCoord + dx, t.yCoord + 70 + dy, t.zCoord + dz));
                            if (r != null && (best == null || r.getNodeList().size() > best.getNodeList().size()))
                                best = r;
                        }
                    }
                }
                if (best == null || best.getNodeList().size() < 10) {
                    pathfinder.close();
                    continue;
                }
                Route route = new Route();
                route.pathfinder = pathfinder;
                route.route = best;
                route.segmentsRoute = best;
                route.segments = NeoRouteDisplayEngine.transformPathfindResult(best.getNodeList(), WIDTH, SMOOTH);
                route.anchors = new Vec3[best.getNodeList().size()];
                for (int i = 0; i < route.anchors.length; i++) {
                    PathfindResult.PathfindNode n = best.getNodeList().get(i);
                    route.anchors[i] = new Vec3(n.getX(), n.getY(), n.getZ());
                }
                PathfindResult.PathfindNode end = best.getNodeList().get(best.getNodeList().size() - 1);
                route.dest = BlockPos.containing(end.getX(), end.getY(), end.getZ());
                routes.add(route);
            } catch (IOException e) {
                // skip unreadable precalcs
            }
        }
        StringBuilder sb = new StringBuilder("loaded " + routes.size() + " routes:");
        for (Route r : routes) sb.append(" ").append(r.route.getNodeList().size());
        return sb.toString();
    }

    public static synchronized void enable(String m) {
        mode = m;
        enabled = true;
    }

    public static synchronized void disable() {
        enabled = false;
    }

    public static int routeCount() {
        return routes.size();
    }

    // center of the loaded routes so the gametest can point the camera at them
    public static Vec3 center() {
        double[] b = bounds();
        return new Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    // a player-eye view along the first route: {x, y, z, yaw, pitch}. The ribbons are
    // ~0.3 wide and horizontal, so they only show in screenshots from up close on the path
    public static double[] viewPose() {
        if (routes.isEmpty()) return new double[] {0, 80, 0, 0, 30};
        Vec3[] a = routes.get(0).anchors;
        int i0 = a.length / 3;
        int i1 = Math.min(i0 + 5, a.length - 1);
        Vec3 dir = a[i1].subtract(a[i0]).normalize();
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        return new double[] {
                a[i0].x - dir.x * 6, a[i0].y + 3, a[i0].z - dir.z * 6, yaw, 20
        };
    }

    // {minX, minY, minZ, maxX, maxY, maxZ} over every route anchor
    public static double[] bounds() {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Route r : routes) {
            for (Vec3 a : r.anchors) {
                b[0] = Math.min(b[0], a.x); b[1] = Math.min(b[1], a.y); b[2] = Math.min(b[2], a.z);
                b[3] = Math.max(b[3], a.x); b[4] = Math.max(b[4], a.y); b[5] = Math.max(b[5], a.z);
            }
        }
        return b;
    }

    // mirrors tickAction: one getRoute per route per tick, transform only when the route object
    // (or, with -Ddg.nocache, nothing) changes. Costs land in DGPerf.tickNanos like LiveRouteRegistry's.
    // The anchor advances every 3rd tick (~sprint speed across half-block cells): getRoute still runs
    // every tick, so memoized hits are exercised the way a real player walking the route hits them.
    private static void tick() {
        long t0 = System.nanoTime(), a0 = DGPerf.alloc();
        boolean move = ++tickCounter % 3 == 0;
        for (Route route : routes) {
            if (move && ++route.anchorIdx >= route.anchors.length) route.anchorIdx = 0;
            PathfindResult r = route.pathfinder.getRoute(route.anchors[route.anchorIdx]);
            if (r != null) route.route = r;
            // old code re-transformed every tick; the memoized path only does it when the route object changes
            if (route.route != null && (NO_CACHE || r != null && r != route.segmentsRoute))
                route.segments = NeoRouteDisplayEngine.transformPathfindResult(route.route.getNodeList(), WIDTH, SMOOTH);
            if (r != null) route.segmentsRoute = r;
        }
        DGPerf.tickNanos += System.nanoTime() - t0;
        DGPerf.tickBytes += DGPerf.alloc() - a0;
        DGPerf.ticks++;
    }

    private static void record(RenderWorldEvent event) {
        float partialTicks = event.partialTicks;
        BlockPos playerBp = net.minecraft.client.Minecraft.getInstance().player.blockPosition();
        for (Route route : routes) {
            if (!"classic".equals(mode))
                NeoRouteDisplayEngine.drawLinesPathfindNode(route.segments, BG, ARROW, event, NEO_SETTINGS);
            if (!"neo".equals(mode))
                ClassicPathDisplayEngine.drawLinesPathfindNode(route.route.getNodeList(), CLASSIC_LINE, 3.0f, event);

            // destination: beacon beam, target highlight, "Destination" and distance text (production calls)
            RenderUtils.renderBeaconBeam(route.dest.getX(), route.dest.getY(), route.dest.getZ(), BEAM, partialTicks);
            RenderUtils.highlightBlock(route.dest, BEACON, partialTicks);
            double distSqr = route.dest.distSqr(playerBp);
            float scale = 0.45f * ((float) Math.sqrt(distSqr) / 120f) * 25f / 6f;
            RenderUtils.drawTextAtWorld("Destination", route.dest.getX() + 0.5f, route.dest.getY() + 0.5f + scale, route.dest.getZ() + 0.5f, 0xFF00FF00, 1f, true, false, partialTicks);
            String distText;
            long key = (long) distSqr;
            if (!NO_CACHE && key == route.lastDistSqr && route.distText != null) {
                distText = route.distText;
            } else {
                distText = String.format("%.2f", Math.sqrt(distSqr)) + "m";
                route.lastDistSqr = key;
                route.distText = distText;
            }
            RenderUtils.drawTextAtWorld(distText, route.dest.getX() + 0.5f, route.dest.getY() + 0.5f - scale, route.dest.getZ() + 0.5f, 0xFFFFFF00, 1f, true, false, partialTicks);
        }
    }

    public static void dumpReport(Path out, String label, long windowNanos) throws IOException {
        double secs = windowNanos / 1e9;
        String report = "# DG render benchmark " + label + "\n"
                + "mode=" + mode + " routes=" + routes.size()
                + " nobatch=" + Boolean.getBoolean("dg.nobatch")
                + " nomemo=" + Boolean.getBoolean("dg.nomemo")
                + " nocache=" + Boolean.getBoolean("dg.nocache") + "\n"
                + "window " + String.format("%.1f", secs) + "s"
                + " fps " + String.format("%.1f", DGPerf.frames / secs) + "\n"
                + DGPerf.report() + "\n";
        Files.writeString(out, report);
        System.out.println("[DGBENCH] " + report.replace('\n', ' '));
    }
}

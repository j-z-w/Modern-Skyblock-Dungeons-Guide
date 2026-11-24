package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.render.world.DGWorldRenderer;
import kr.syeyoung.dungeonsguide.mod.render.world.state.DGWorldRenderState;
import kr.syeyoung.dungeonsguide.mod.render.world.state.FilledBoxState;
import kr.syeyoung.dungeonsguide.mod.render.world.state.LineStripState;
import kr.syeyoung.dungeonsguide.mod.render.world.state.WorldTextState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-room resolved community route: the matched route is canonicalized once
 * and every render state is prebuilt here, so the render path only re-records
 * existing objects (zero per-frame allocation; the single exception is the
 * player endpoint Vec3 of the dynamic guidance line, which necessarily moves).
 *
 * Coordinate chain (verified empirically by RouteAlignmentTest):
 *   source pos --CanonicalTransform--> DG canonical --rotate CCW x roomRotation-->
 *   rotated-rel --(+boundsMin)--> world.
 * The inverse (world -> canonical) replicates OffsetPoint.setPosInWorld.
 */
public final class RouteRoomOverlay {

    // ARGB, alpha ~0x80 upcoming / 0xCC current / 0x30 done
    private static final int C_ETHERWARP = 0x8000FFFF;
    private static final int C_BREAKER = 0x80FF8800;
    private static final int C_SUPERBOOM = 0x80FF3333;
    private static final int C_PEARL = 0x8055FF55;
    private static final int C_INTERACT = 0x80FFFF55;
    private static final int C_BONZO = 0x80FF55FF;
    private static final int C_MARKER = 0x80AAAAAA;
    private static final int C_WALK = 0x4066AAFF;
    private static final int C_SECRET_TEXT = 0xFFFFD700;
    private static final int C_CURRENT_BOX = 0x60FFFFFF;
    private static final int C_LINE = 0xFF00FFAA;

    private static int doneColor(int color) {
        return (color & 0x00FFFFFF) | 0x30000000;
    }

    public final DungeonRoom room;
    public final RoutePack pack;
    public final CommunityRoute route;
    public final CanonicalTransform transform;
    public final RouteStepTracker tracker;

    /** canonicalized steps in authored order (ambient included, flagged) */
    public final List<RouteStep> steps;
    /** DG secret mechanics bound to each step's secret (null when none) */
    private final ISecret[] stepSecrets;
    private final boolean[] secretFound;

    // prebuilt render states; indexed by step index in `steps`
    private final List<DGWorldRenderState>[] currentStates;
    private final List<DGWorldRenderState>[] upcomingStates;
    private final List<DGWorldRenderState>[] doneStates;
    private final List<DGWorldRenderState> ambientStates;

    // dynamic guidance line (single reusable list; player endpoint updated per frame)
    private final List<Vec3> linePoints;
    private final LineStripState dynamicLine;
    private final List<DGWorldRenderState> targetMark = new ArrayList<>(1);
    private Vec3 targetMarkPos;

    private final double xLen;
    private final double zLen;
    private final int roomRotation;
    private final int minX, minY, minZ;

    private RouteVec3 playerCanon;
    private Vec3 playerWorld;

    public RouteRoomOverlay(DungeonRoom room, RoutePack pack, CommunityRoute route,
                            CanonicalTransform transform, Vec3 playerStart) {
        this.room = room;
        this.pack = pack;
        this.route = route;
        this.transform = transform;

        roomRotation = room.getRoomMatcher() == null ? 0 : room.getRoomMatcher().getRotation();
        BlockPos bmin = room.getRoomBounds().getMin();
        BlockPos bmax = room.getRoomBounds().getMax();
        minX = bmin.getX();
        minY = bmin.getY();
        minZ = bmin.getZ();
        xLen = bmax.getX() - bmin.getX() + 1;
        zLen = bmax.getZ() - bmin.getZ() + 1;

        steps = canonicalize(route.steps, transform);
        stepSecrets = bindSecrets(steps, room);
        secretFound = new boolean[steps.size()];

        int n = steps.size();
        currentStates = new List[n];
        upcomingStates = new List[n];
        doneStates = new List[n];
        for (int i = 0; i < n; i++) {
            currentStates[i] = new ArrayList<>();
            upcomingStates[i] = new ArrayList<>();
            doneStates[i] = new ArrayList<>();
            if (!steps.get(i).ambient) {
                buildStepStates(steps.get(i), currentStates[i], upcomingStates[i], doneStates[i]);
            }
        }
        ambientStates = new ArrayList<>();
        buildAmbientStates();

        linePoints = new ArrayList<>(2);
        linePoints.add(Vec3.ZERO);
        linePoints.add(Vec3.ZERO);
        dynamicLine = new LineStripState(linePoints, new int[]{C_LINE, C_LINE}, 3f, false, false);

        tracker = new RouteStepTracker(steps);
        playerWorld = playerStart;
    }

    /* ---------------- coordinate transforms ---------------- */

    /** DG canonical -> world (OffsetPoint.toRotatedRelBlockPos + boundsMin, doubles) */
    public Vec3 toWorld(RouteVec3 canon) {
        return toWorld(canon, roomRotation, xLen, zLen, minX, minY, minZ);
    }

    // static form so the transform pair is unit-testable without a DungeonRoom:
    // T_i(a,b) = (b, -a + B_i) applied for i = 0..rotation-1, B_i = bound span
    static Vec3 toWorld(RouteVec3 canon, int roomRotation, double xLen, double zLen,
                        int minX, int minY, int minZ) {
        double x = canon.x, z = canon.z;
        for (int i = 0; i < roomRotation; i++) {
            double t = x;
            x = z;
            z = -t + (i % 2 == 0 ? zLen : xLen);
        }
        return new Vec3(x + minX, canon.y + minY, z + minZ);
    }

    /** world -> DG canonical: the exact inverse of {@link #toWorld}, mirroring
     * OffsetPoint.setPosInWorld - undo T_i for i = rotation-1..0 with
     * T_i^{-1}(a,b) = (-b + B_i, a), where B_i is the bound span (xLen/zLen).
     * The previous version iterated the rotation steps forward (identity only
     * for rotation <= 1) and used the roominfo blocks-array dimensions, which
     * disagree with the bounds span for createNew()-shaped rooms. */
    public RouteVec3 canonicalize(Vec3 world) {
        return canonicalize(world, roomRotation, xLen, zLen, minX, minY, minZ);
    }

    static RouteVec3 canonicalize(Vec3 world, int roomRotation, double xLen, double zLen,
                                  int minX, int minY, int minZ) {
        double x = world.x - minX, z = world.z - minZ;
        for (int i = roomRotation - 1; i >= 0; i--) {
            double t = x;
            x = -z + (i % 2 == 0 ? zLen : xLen);
            z = t;
        }
        return new RouteVec3(x, world.y - minY, z);
    }

    /** canonical -> rotated-rel block pos (for block lookups) */
    private BlockPos canonicalBlock(RouteVec3 canon) {
        double x = canon.x, z = canon.z;
        for (int i = 0; i < roomRotation; i++) {
            double t = x;
            x = z;
            z = -t + (i % 2 == 0 ? zLen : xLen);
        }
        return new BlockPos((int) Math.floor(x), (int) Math.floor(canon.y), (int) Math.floor(z));
    }

    private boolean isAirCanonical(RouteVec3 canon) {
        BlockPos rel = canonicalBlock(canon);
        BlockState bs = room.getRelativeBlockAt(rel.getX(), rel.getY(), rel.getZ());
        return bs != null && bs.isAir();
    }

    /* ---------------- setup ---------------- */

    private static List<RouteStep> canonicalize(List<RouteStep> source, CanonicalTransform t) {
        List<RouteStep> out = new ArrayList<>(source.size());
        for (RouteStep s : source) {
            List<RouteAction> acts = new ArrayList<>(s.actions.size());
            for (RouteAction a : s.actions) {
                float yaw = a.yaw;
                if (!Float.isNaN(yaw)) {
                    // authored yaw lives in source canonical; direction vectors
                    // rotate +90deg per CanonicalTransform rotation and -90deg
                    // per room rotation (matches the position transforms)
                    yaw = (float) ((yaw + 90.0 * t.rotation) % 360.0);
                }
                acts.add(new RouteAction(a.kind, a.pos == null ? null : t.apply(a.pos), a.label, yaw, a.pitch));
            }
            RouteStep.Secret secret = s.secret == null || s.secret.pos == null
                    ? null : new RouteStep.Secret(s.secret.type, t.apply(s.secret.pos));
            out.add(new RouteStep(acts, secret, s.ambient));
        }
        return out;
    }

    private static ISecret[] bindSecrets(List<RouteStep> steps, DungeonRoom room) {
        List<ISecret> secrets = new ArrayList<>();
        for (DungeonMechanicState state : room.getMechanics().values()) {
            if (state instanceof ISecret) secrets.add((ISecret) state);
        }
        ISecret[] bound = new ISecret[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            RouteStep.Secret secret = steps.get(i).secret;
            if (secret == null || secret.pos == null) continue;
            ISecret best = null;
            double bestD = 2.5 * 2.5;
            for (ISecret s : secrets) {
                OffsetPoint p = s.getSecretPoint();
                if (p == null) continue;
                double dx = p.getX() - secret.pos.x;
                double dy = p.getY() - secret.pos.y;
                double dz = p.getZ() - secret.pos.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            bound[i] = best;
        }
        return bound;
    }

    private AABB boxAround(Vec3 center, double half) {
        return new AABB(center.x - half, center.y - half, center.z - half,
                center.x + half, center.y + half, center.z + half);
    }

    private AABB blockBox(RouteVec3 canon) {
        Vec3 w = toWorld(canon);
        // actions are stored as the block corner per SRM convention
        return new AABB(Math.floor(w.x), Math.floor(w.y), Math.floor(w.z),
                Math.floor(w.x) + 1, Math.floor(w.y) + 1, Math.floor(w.z) + 1);
    }

    private void buildStepStates(RouteStep step,
                                 List<DGWorldRenderState> current,
                                 List<DGWorldRenderState> upcoming,
                                 List<DGWorldRenderState> done) {
        for (RouteAction a : step.actions) {
            if (a.pos == null) continue;
            switch (a.kind) {
                case ETHERWARP:
                    addBoxWithLabel(current, upcoming, done, blockBox(a.pos),
                            C_ETHERWARP, a.label == null ? "Etherwarp" : a.label, true);
                    break;
                case BREAKER:
                    addBox(current, upcoming, done, blockBox(a.pos), C_BREAKER);
                    break;
                case SUPERBOOM:
                    addBoxWithLabel(current, upcoming, done, blockBox(a.pos),
                            C_SUPERBOOM, a.label == null ? "Superboom" : a.label, true);
                    break;
                case PEARL:
                    addBoxWithLabel(current, upcoming, done, blockBox(a.pos),
                            C_PEARL, a.label == null ? "Pearl" : a.label, true);
                    if (!Float.isNaN(a.yaw)) {
                        addPearlAim(a, current, upcoming, done);
                    }
                    break;
                case INTERACT:
                    addBoxWithLabel(current, upcoming, done, blockBox(a.pos),
                            C_INTERACT, a.label == null ? "Interact" : a.label, false);
                    break;
                case BONZO:
                    addBoxWithLabel(current, upcoming, done, blockBox(a.pos),
                            C_BONZO, a.label == null ? "Bonzo" : a.label, true);
                    break;
                case MARKER:
                    Vec3 c = toWorld(a.pos);
                    AABB b = boxAround(c, 0.3);
                    addBoxWithLabel(current, upcoming, done, b, C_MARKER, a.label, false);
                    break;
                case WALK:
                default:
                    // breadcrumbs are drawn via the ambient path line, not per point
                    break;
            }
        }
        if (step.secret != null && step.secret.pos != null) {
            Vec3 w = toWorld(step.secret.pos);
            String text = "§6" + (step.secret.type == null ? "secret" : step.secret.type);
            current.add(new WorldTextState(text, w.add(0, 1.2, 0), C_SECRET_TEXT, 0.025f, true, false, false));
            upcoming.add(new WorldTextState(text, w.add(0, 1.2, 0), C_SECRET_TEXT, 0.025f, true, false, false));
            done.add(new WorldTextState(text, w.add(0, 1.2, 0), 0x30FFFFFF, 0.025f, true, false, false));
        }
    }

    private void addPearlAim(RouteAction a,
                             List<DGWorldRenderState> current,
                             List<DGWorldRenderState> upcoming,
                             List<DGWorldRenderState> done) {
        Vec3 w = toWorld(a.pos).add(0.5, 1.62, 0.5);
        double yaw = Math.toRadians(a.yaw - 90.0 * roomRotation);
        double pitch = Math.toRadians(a.pitch);
        Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        List<Vec3> pts = Arrays.asList(w, w.add(dir.scale(5)));
        int[] col = new int[]{C_PEARL, C_PEARL};
        int[] doneCol = new int[]{doneColor(C_PEARL), doneColor(C_PEARL)};
        current.add(new LineStripState(pts, col, 2f, false, false));
        upcoming.add(new LineStripState(pts, col, 2f, false, false));
        done.add(new LineStripState(pts, doneCol, 2f, false, false));
    }

    private void addBox(List<DGWorldRenderState> current, List<DGWorldRenderState> upcoming,
                        List<DGWorldRenderState> done, AABB box, int color) {
        current.add(new FilledBoxState(box.inflate(0.03), (color & 0xFFFFFF) | 0xA0000000, false));
        upcoming.add(new FilledBoxState(box, color, false));
        done.add(new FilledBoxState(box, doneColor(color), false));
    }

    private void addBoxWithLabel(List<DGWorldRenderState> current, List<DGWorldRenderState> upcoming,
                                 List<DGWorldRenderState> done, AABB box, int color,
                                 String label, boolean text) {
        addBox(current, upcoming, done, box, color);
        if (text && label != null) {
            Vec3 pos = new Vec3(box.getCenter().x, box.maxY + 0.4, box.getCenter().z);
            current.add(new WorldTextState("§l" + label, pos, (color & 0xFFFFFF) | 0xFF000000, 0.03f, true, false, false));
            upcoming.add(new WorldTextState(label, pos, (color & 0xFFFFFF) | 0xFF000000, 0.025f, true, false, false));
            done.add(new WorldTextState(label, pos, 0x30FFFFFF, 0.025f, true, false, false));
        }
    }

    private void buildAmbientStates() {
        // authored walk path: one line strip through every WALK/MARKER breadcrumb
        List<Vec3> path = new ArrayList<>();
        for (RouteStep s : steps) {
            for (RouteAction a : s.actions) {
                if (a.kind == RouteAction.Kind.WALK && a.pos != null) {
                    path.add(toWorld(a.pos).add(0.5, 0.6, 0.5));
                }
            }
        }
        if (path.size() >= 2) {
            int[] colors = new int[path.size()];
            Arrays.fill(colors, C_WALK);
            ambientStates.add(new LineStripState(path, colors, 2f, false, false));
        }
        // ambient markers (Odin unnumbered waypoints etc.)
        for (RouteStep s : steps) {
            if (!s.ambient) continue;
            for (RouteAction a : s.actions) {
                if (a.pos == null) continue;
                Vec3 c = toWorld(a.pos);
                ambientStates.add(new FilledBoxState(boxAround(c, 0.3), C_MARKER, false));
                if (a.label != null) {
                    ambientStates.add(new WorldTextState(a.label, c.add(0, 0.8, 0),
                            C_MARKER | 0xFF000000, 0.025f, true, false, false));
                }
            }
            if (s.secret != null && s.secret.pos != null) {
                Vec3 w = toWorld(s.secret.pos);
                ambientStates.add(new WorldTextState("§6" + s.secret.type, w.add(0, 1.2, 0),
                        C_SECRET_TEXT, 0.025f, true, false, false));
            }
        }
    }

    /* ---------------- per-tick / events ---------------- */

    public void tick(Vec3 playerPos) {
        playerWorld = playerPos;
        playerCanon = canonicalize(playerPos);

        for (int i = 0; i < stepSecrets.length; i++) {
            ISecret s = stepSecrets[i];
            if (s != null && !secretFound[i]) {
                try {
                    secretFound[i] = s.isFound(room);
                } catch (Exception ignored) {
                }
            }
        }
        tracker.tick(playerCanon, secretFound, this::isAirCanonical);
        updateTargetMark();
    }

    /** server teleport packet landed the player at `pos` (world) */
    public void onTeleport(Vec3 pos) {
        tracker.onTeleport(canonicalize(pos));
    }

    private void updateTargetMark() {
        targetMark.clear();
        Vec3 target = currentTargetWorld();
        targetMarkPos = target;
        if (target != null) {
            targetMark.add(new FilledBoxState(boxAround(target, 0.55), C_CURRENT_BOX, false));
        }
    }

    /** world position the dynamic line should guide to (nearest unfinished gate action, else step's last point) */
    public Vec3 currentTargetWorld() {
        int idx = tracker.guideStep();
        if (idx < 0 || idx >= steps.size()) return null;
        RouteStep step = steps.get(idx);
        for (int a = 0; a < step.actions.size(); a++) {
            RouteAction action = step.actions.get(a);
            if (RouteStepTracker.isGate(action.kind) && !tracker.isActionDone(idx, a) && action.pos != null) {
                return toWorld(action.pos).add(0.5, 0.5, 0.5);
            }
        }
        RouteVec3 last = step.lastPoint();
        return last == null ? null : toWorld(last).add(0.5, 0.5, 0.5);
    }

    /* ---------------- render ---------------- */

    public void render(boolean drawCompleted) {
        for (DGWorldRenderState s : ambientStates) DGWorldRenderer.record(s);
        int current = tracker.guideStep();
        for (int i = 0; i < steps.size(); i++) {
            RouteStep step = steps.get(i);
            if (step.ambient) continue;
            List<DGWorldRenderState> states;
            if (tracker.isStepDone(i)) {
                if (!drawCompleted) continue;
                states = doneStates[i];
            } else if (i == current) {
                states = currentStates[i];
            } else {
                states = upcomingStates[i];
            }
            for (DGWorldRenderState s : states) DGWorldRenderer.record(s);
        }
        // dynamic guidance: straight segment to the current target + marker.
        // (DG precalc path targets don't exist for arbitrary route points; a
        //  live A* fallback is a planned follow-up, so this is a straight line.)
        if (targetMarkPos != null && playerWorld != null) {
            linePoints.set(0, playerWorld);
            linePoints.set(1, targetMarkPos);
            DGWorldRenderer.record(dynamicLine);
            for (DGWorldRenderState s : targetMark) DGWorldRenderer.record(s);
        }
    }
}

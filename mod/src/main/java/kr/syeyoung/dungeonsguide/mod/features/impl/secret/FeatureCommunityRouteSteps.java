package kr.syeyoung.dungeonsguide.mod.features.impl.secret;

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.config.types.TCBoolean;
import kr.syeyoung.dungeonsguide.mod.config.types.TCEnum;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CanonicalTransform;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CommunityRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CommunityRouteLoader;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RoutePack;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteRoomMapper;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteRoomOverlay;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStep;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStepTracker;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.events.impl.DGTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.RawPacketReceivedEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.WorldUnloadEvent;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders community-authored room routes (Secret Routes Mod / Odin waypoint
 * packs) as an overlay: authored etherwarp targets, Dungeon Breaker blocks,
 * superboom spots, pearls and secrets, plus a straight guidance line to the
 * current step's target.
 *
 * Render-only (AD9): nothing here aims, rotates the player, sends packets or
 * mines — advancement is driven purely by observable client state.
 *
 * Data is loaded once, asynchronously, from the user's own installed mod
 * configs (AD10); no community route data is bundled with DG. Credit is given
 * to SRM (github.com/your-name-here/Secret-Routes, AGPL pack authors) and
 * Odin (BSD-3-Clause) in the feature description.
 */
public class FeatureCommunityRouteSteps extends SimpleFeature {

    public enum Source {
        AUTO,
        SRM_FOW,
        SRM_3PPOPKA,
        ODIN,
        CUSTOM
    }

    public enum VariantPick {
        CLOSEST,
        INDEX_0,
        INDEX_1,
        INDEX_2,
        INDEX_3
    }

    private final Map<DungeonRoom, RouteRoomOverlay> overlays = new ConcurrentHashMap<>();
    private final Set<DungeonRoom> unmatched = ConcurrentHashMap.newKeySet();
    private volatile RouteRoomOverlay current;
    private RoutePack lastPack;

    public FeatureCommunityRouteSteps() {
        super("Pathfinding & Secrets", "Community route steps (etherwarp / Dungeon Breaker / superboom)",
                "Shows community-authored route steps (etherwarp targets, Dungeon Breaker blocks, superboom spots, pearls, secrets) from Secret Routes Mod / Odin route files installed on this machine.\n\n"
                        + "Data is read from your own config folders - SecretRoutes/routes/*.json, odin/dungeon-waypoints/*.json, and dungeonsguide/routes/ for custom files. No route data is bundled; credit to the SRM route authors and the Odin project (BSD-3-Clause).\n\n"
                        + "Render-only: no auto-aim, no rotation changes, no packets sent, no auto-mining.",
                "secrets.communityroute", true);
        addParameter("source", new FeatureParameter<>("source", "Route source",
                "Which installed route pack to use", Source.AUTO, new TCEnum<>(Source.values())));
        addParameter("variant", new FeatureParameter<>("variant", "Route variant",
                "How to pick between route variants for a room. CLOSEST picks the variant whose first step is nearest you, like SRM.",
                VariantPick.CLOSEST, new TCEnum<>(VariantPick.values())));
        addParameter("drawCompleted", new FeatureParameter<>("drawCompleted", "Show completed steps",
                "Draw already-completed steps dimmed instead of hiding them", false, TCBoolean.INSTANCE));
    }

    private <T> T param(String key) {
        return (T) getParameter(key).getValue();
    }

    /* ---------------- room plumbing (mirrors LiveRouteRegistry) ---------------- */

    private DungeonRoom getRoomIn() {
        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        if (!SkyblockStatus.isOnDungeon() || context == null || context.getScaffoldParser() == null) {
            return null;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        Point roomPt = context.getScaffoldParser().getDungeonMapLayout()
                .worldPointToRoomPoint(player.position());
        return context.getScaffoldParser().getRoomMap().get(roomPt);
    }

    private RoutePack pickPack() {
        List<RoutePack> packs = CommunityRouteLoader.get().packs();
        if (packs.isEmpty()) return null;
        Source source = param("source");
        String prefix;
        switch (source) {
            case SRM_FOW: return packById(packs, "srm:fowroutes");
            case SRM_3PPOPKA: return packById(packs, "srm:3ppopkaroutes");
            case ODIN: prefix = "odin:"; break;
            case CUSTOM: prefix = "dg:"; break;
            default: return packs.get(0);
        }
        for (RoutePack p : packs) {
            if (p.id.startsWith(prefix)) return p;
        }
        return null;
    }

    private RoutePack packById(List<RoutePack> packs, String id) {
        for (RoutePack p : packs) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private RouteRoomOverlay buildOverlay(DungeonRoom room, Vec3 playerPos) {
        if (room.getDungeonRoomInfo() == null) return null;
        RoutePack pack = pickPack();
        if (pack == null) return null;
        RouteRoomMapper.Resolution res = RouteRoomMapper.resolve(
                room.getDungeonRoomInfo(), pack, room.getRoomBounds().getMin().getY());
        if (!res.matched()) return null;
        CommunityRoute route = pickVariant(pack, res.routeKey, room, res.transform, playerPos);
        if (route == null) return null;
        return new RouteRoomOverlay(room, pack, route, res.transform, playerPos);
    }

    private CommunityRoute pickVariant(RoutePack pack, String roomKey, DungeonRoom room,
                                       CanonicalTransform transform, Vec3 playerPos) {
        List<CommunityRoute> routes = pack.routesFor(roomKey);
        if (routes == null || routes.isEmpty()) return null;
        VariantPick pick = param("variant");
        switch (pick) {
            case INDEX_0:
            case INDEX_1:
            case INDEX_2:
            case INDEX_3:
                int idx = pick.ordinal() - 1;
                return routes.get(Math.min(idx, routes.size() - 1));
            case CLOSEST:
            default:
                // like SRM: the variant whose first step starts nearest the player.
                // source points go through the fitted transform into DG canonical,
                // then to world space, so they can be compared to the player pos.
                CommunityRoute best = routes.get(0);
                double bestD = Double.MAX_VALUE;
                int rotation = room.getRoomMatcher() == null ? 0 : room.getRoomMatcher().getRotation();
                BlockPos bmin = room.getRoomBounds().getMin();
                BlockPos bmax = room.getRoomBounds().getMax();
                for (CommunityRoute r : routes) {
                    RouteVec3 first = null;
                    for (RouteStep s : r.steps) {
                        if (!s.ambient && s.firstPoint() != null) {
                            first = s.firstPoint();
                            break;
                        }
                    }
                    if (first == null) continue;
                    RouteVec3 canon = transform.apply(first);
                    double x = canon.x, z = canon.z;
                    for (int i = 0; i < rotation; i++) {
                        double t = x;
                        x = z;
                        z = -t + (i % 2 == 0
                                ? bmax.getZ() - bmin.getZ() + 1
                                : bmax.getX() - bmin.getX() + 1);
                    }
                    double dx = x + bmin.getX() - playerPos.x;
                    double dy = canon.y + bmin.getY() - playerPos.y;
                    double dz = z + bmin.getZ() - playerPos.z;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = r;
                    }
                }
                return best;
        }
    }

    /* ---------------- events ---------------- */

    @DGEventHandler(triggerOutOfSkyblock = true)
    public void onTick(DGTickEvent event) {
        CommunityRouteLoader.get().ensureLoaded();
        if (!CommunityRouteLoader.get().isLoaded()) return;

        RoutePack pack = pickPack();
        if (pack != lastPack) { // source changed (or load finished): rebuild overlays
            lastPack = pack;
            overlays.clear();
            unmatched.clear();
            current = null;
        }

        DungeonRoom room = getRoomIn();
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            current = null;
            return;
        }
        if (room.getDungeonRoomInfo() == null) {
            current = null;
            return; // room not matched yet; retry next tick
        }

        RouteRoomOverlay overlay = overlays.get(room);
        if (overlay == null && !unmatched.contains(room)) {
            overlay = buildOverlay(room, player.position());
            if (overlay == null) unmatched.add(room); // don't re-resolve every tick
            else overlays.put(room, overlay);
        }
        current = overlay;
        if (current != null) {
            current.tick(player.position());
        }
    }

    @DGEventHandler
    public void onWorldRender(RenderWorldEvent event) {
        RouteRoomOverlay overlay = current;
        if (overlay != null) {
            overlay.render(this.<Boolean>param("drawCompleted"));
        }
    }

    @DGEventHandler
    public void onPacket(RawPacketReceivedEvent event) {
        if (!(event.packet instanceof ClientboundPlayerPositionPacket pos)) return;
        RouteRoomOverlay overlay = current;
        if (overlay == null) return;
        Vec3 landing = pos.change().position();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || landing == null) return;
        if (landing.distanceToSqr(player.position())
                > RouteStepTracker.TELEPORT_MIN_DIST * RouteStepTracker.TELEPORT_MIN_DIST) {
            overlay.onTeleport(landing);
        }
    }

    @DGEventHandler(triggerOutOfSkyblock = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        overlays.clear();
        unmatched.clear();
        current = null;
    }
}

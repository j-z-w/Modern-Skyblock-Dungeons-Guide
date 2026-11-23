package kr.syeyoung.dungeonsguide.mod.commands;

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractActionMove;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AtomicAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.ActionRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayLeverState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonPressurePlateState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.IPathDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.MoveDiagnostics;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.PrecalcFallback;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay.RoomRouteHandler;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculation;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PrecalculatedPathfinder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.IPathfindWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// dgdebug routestatus: a read-only dump of every live route's pathfinder state plus the room's
// mechanic states. Never creates handlers, never loads precalcs, never calls getPrecalcByHash
// (it prints a MISSING warning), never mutates the route.
public class RouteStatusDump {

    private static void print(String s) {
        ChatTransmitter.addToQueue(Component.literal("§eDungeons Guide §7:: " + s));
    }

    public static void dump() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            print("§cno player");
            return;
        }
        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        if (context == null || context.getScaffoldParser() == null) {
            print("§cno dungeon context / scaffold parser");
            return;
        }

        Map<DungeonRoom, RoomRouteHandler> handlers = FeatureRegistry.SECRET_ROUTE_REGISTRY.getHandlers();

        DungeonRoom playerRoom = context.getScaffoldParser().getRoomMap().get(
                context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(mc.player.position()));
        print("§fplayer at " + String.format("%.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ())
                + " in room " + (playerRoom == null ? "none" : playerRoom.getDungeonRoomInfo() == null ? "unmatched" : playerRoom.getDungeonRoomInfo().getName()));

        Set<DungeonRoom> dumpedRooms = new HashSet<>();
        for (Map.Entry<DungeonRoom, RoomRouteHandler> entry : handlers.entrySet()) {
            DungeonRoom room = entry.getKey();
            dumpedRooms.add(room);
            print("§f== room " + (room.getDungeonRoomInfo() == null ? "unmatched" : room.getDungeonRoomInfo().getName())
                    + " rot=" + room.getRoomMatcher().getRotation()
                    + " preset=" + context.getPreset().getPresetName() + " ==");
            for (Map.Entry<String, IPathDisplayEngine<?>> path : entry.getValue().getPath().entrySet()) {
                dumpRoute(path.getKey(), path.getValue(), room, context);
            }
            dumpMechanics(room);
        }
        if (playerRoom != null && !dumpedRooms.contains(playerRoom)) {
            print("§f== room " + playerRoom.getDungeonRoomInfo().getName() + " (no live routes) ==");
            dumpMechanics(playerRoom);
        }
        if (handlers.isEmpty() && playerRoom == null) {
            print("§7no live routes and no room");
        }
    }

    private static void dumpRoute(String id, IPathDisplayEngine<?> engine, DungeonRoom room, DungeonContext context) {
        ActionRoute route = engine.getActionRoute();
        List<AbstractAction> actions = route.getActions();
        print("§froute §b" + id + "§f \"" + route + "\" engine=" + engine.getClass().getSimpleName()
                + " cur=" + route.getCurrent() + "/" + (actions == null ? -1 : actions.size())
                + (route.isCalculating() ? " §e(calculating)" : ""));
        if (route.isCalculating()) {
            print("  §7still computing the plan; nothing renders until it finishes");
            return;
        }
        AbstractAction current = route.getCurrentAction();
        print("  action[" + route.getCurrent() + "]=" + describe(current));

        Map<AbstractActionMove, MoveDiagnostics.MoveDiag> diags =
                engine instanceof MoveDiagnostics ? ((MoveDiagnostics) engine).describeMoves() : null;

        RoomPreset roomPreset = room.getDungeonRoomInfo() == null ? null
                : context.getPreset().getRoomPreset(room.getDungeonRoomInfo().getUuid());
        // same hash->precalc map RoomPresetPathPlanner builds, without the missing-precalc warning
        Map<String, PathfindPrecalculation> known = new HashMap<>();
        if (roomPreset != null) {
            for (String precalcId : roomPreset.getPrecalculations()) {
                PathfindPrecalculation p = PathfindPrecalculationRegistry.getINSTANCE().getById(precalcId);
                if (p != null) known.putIfAbsent(p.getTargetHash(), p);
            }
        }

        Vec3 playerPos = Minecraft.getInstance().player.position();
        for (AbstractActionMove move : relevantMoves(route)) {
            dumpMove(move, diags == null ? null : diags.get(move), room, playerPos, known);
        }
    }

    // the moves whose lines are (or would be) drawn this tick: current + previous, unwrapping AtomicActions
    private static List<AbstractActionMove> relevantMoves(ActionRoute route) {
        List<AbstractActionMove> moves = new ArrayList<>();
        List<AbstractAction> actions = route.getActions();
        if (actions == null) return moves;
        for (int idx = Math.min(route.getCurrent(), actions.size() - 1);
             idx >= Math.max(0, route.getCurrent() - 1); idx--) {
            addMoves(actions.get(idx), moves);
        }
        return moves;
    }

    private static void addMoves(AbstractAction action, List<AbstractActionMove> out) {
        if (action instanceof AtomicAction) {
            AtomicAction atomic = (AtomicAction) action;
            List<AbstractAction> subs = atomic.getActions();
            if (subs == null) return;
            for (int i = Math.min(atomic.getCurrent(), subs.size() - 1);
                 i >= Math.max(0, atomic.getCurrent() - 1); i--) {
                AbstractAction sub = subs.get(i);
                if (sub instanceof AbstractActionMove && !out.contains(sub)) out.add((AbstractActionMove) sub);
            }
        } else if (action instanceof AbstractActionMove && !out.contains(action)) {
            out.add((AbstractActionMove) action);
        }
    }

    private static String describe(AbstractAction action) {
        if (action == null) return "null";
        String desc = action.getClass().getSimpleName() + " " + action.toString().replace("\n", " | ");
        if (action instanceof AtomicAction) {
            AtomicAction atomic = (AtomicAction) action;
            desc += " sub=" + atomic.getCurrent() + "/" + atomic.getActions().size()
                    + " -> " + describe(atomic.getCurrentAction());
        }
        return desc;
    }

    private static void dumpMove(AbstractActionMove move, MoveDiagnostics.MoveDiag diag, DungeonRoom room,
                                 Vec3 playerPos, Map<String, PathfindPrecalculation> known) {
        List<OffsetVec3> targets = move.getTargetOffsetPointSet();
        print("  move targets=" + (targets == null || targets.isEmpty() ? "§cNONE"
                : String.join(" ", targets.stream().map(t -> "(" + t.xCoord + "," + t.yCoord + "," + t.zCoord + ")").toArray(String[]::new))));
        if (targets == null || targets.isEmpty()) {
            print("    §cno targets: no pathfind request can be built");
            return;
        }

        PathfindRequest request;
        try {
            request = move.getPathfindRequest(room);
        } catch (Exception e) {
            print("    §cgetPathfindRequest failed: " + e);
            return;
        }
        print("    req id=" + request.getId());
        print("    req hash=" + request.getHash() + " openMech=" + request.getOpenMech());

        PathfindPrecalculation precalc = known.get(request.getHash());
        print("    precalc in preset: " + (precalc == null ? "§cMISSING" : "§apresent (id=" + precalc.getId() + ")"));

        // whether the alternative-door-state fallback (PrecalcFallback) has anything to work with
        List<String> altHits = new ArrayList<>();
        for (String hash : PrecalcFallback.alternativeRequestHashes(request, room)) {
            if (known.containsKey(hash)) altHits.add(known.get(hash).getId());
        }
        print("    alt-door-state precalcs: " + (altHits.isEmpty() ? "§7none" : "§a" + String.join(",", altHits)));

        if (diag == null) {
            print("    §7no move context yet (engine has not ticked this action)");
            return;
        }
        PathfinderExecutor executor = diag.executor;
        if (executor == null) {
            print("    §cexecutor: null - " + (precalc == null ? "precalc missing, nothing to load"
                    : "loadPrecalculatedByHash returned null"));
            return;
        }
        IPathfinder pathfinder = executor.getPathfinder();
        print("    executor: loaded pathfinder=" + (pathfinder == null ? "null" : pathfinder.getClass().getSimpleName())
                + " complete=" + executor.isComplete());
        if (!executor.isComplete()) {
            print("    §estill loading: getRoute returns the last (initially empty) route until the first step completes");
            return;
        }
        PathfindResult route = pathfinder == null ? null : pathfinder.getRoute(playerPos);
        if (route != null) {
            print("    route: §a" + route.getNodeList().size() + " nodes");
        } else {
            print("    route: §cnull - " + diagnose(pathfinder, precalc, room, playerPos));
        }
        print("    last drawn: " + (diag.result == null ? "none" : diag.result.getNodeList().size() + " nodes"));
    }

    private static String diagnose(IPathfinder pathfinder, PathfindPrecalculation precalc, DungeonRoom room, Vec3 playerPos) {
        if (pathfinder == null) return "null pathfinder";
        if (!(pathfinder instanceof PrecalculatedPathfinder)) return "not a precalculated pathfinder";
        if (precalc == null) return "no precalc to compare against";
        if (!(room.getRoomProcessor() instanceof GeneralRoomProcessor)) return "non-general room processor";
        PrecalculatedPathfinder pf = (PrecalculatedPathfinder) pathfinder;

        IPathfindWorld world = ((GeneralRoomProcessor) room.getRoomProcessor()).getPathfinderWorld();
        double roomXMin = world.getMinX() - 2, roomYMin = world.getMinY(), roomZMin = world.getMinZ() - 2;
        int rotation = room.getRoomMatcher().getRotation();

        // PrecalculatedPathfinder.getRoute's coordinate transform
        double dx = playerPos.x - roomXMin / 2.0, dz = playerPos.z - roomZMin / 2.0, dy = playerPos.y - roomYMin / 2.0;
        for (int i = 0; i < rotation; i++) {
            double tempX = dx;
            dx = -dz;
            dz = tempX;
            dx += (i % 2 == 0 ? precalc.getXLen() : precalc.getZLen()) / 2 + 1;
        }
        int nodeX = (int) Math.round(dx * 2);
        int nodeY = (int) Math.round(dy * 2);
        int nodeZ = (int) Math.round(dz * 2);

        if (nodeX < precalc.getXStart() || nodeY < precalc.getYStart() || nodeZ < precalc.getZStart()
                || nodeX >= precalc.getXStart() + precalc.getXLen() || nodeY >= precalc.getYStart() + precalc.getYLen()
                || nodeZ >= precalc.getZStart() + precalc.getZLen()) {
            return "player cell outside precalc bounds node=(" + nodeX + "," + nodeY + "," + nodeZ + ") grid=("
                    + precalc.getXStart() + ".." + (precalc.getXStart() + precalc.getXLen() - 1) + ","
                    + precalc.getYStart() + ".." + (precalc.getYStart() + precalc.getYLen() - 1) + ","
                    + precalc.getZStart() + ".." + (precalc.getZStart() + precalc.getZLen() - 1) + ")";
        }
        PrecalculatedPathfinder.CachedPathfindNode node = pf.getNode(nodeX, nodeY, nodeZ);
        if (node.getNodeType() == null) {
            return "player cell unpopulated in the precalc cost field node=(" + nodeX + "," + nodeY + "," + nodeZ + ")"
                    + " - unreachable from the targets in the current open-mechanic state";
        }
        return "cell populated type=" + node.getNodeType() + " g=" + node.getGScore() + " but route was still null";
    }

    private static void dumpMechanics(DungeonRoom room) {
        Map<String, DungeonMechanicState> mechanics = room.getMechanics();
        print("§fmechanics (" + mechanics.size() + "):");
        for (Map.Entry<String, DungeonMechanicState> entry : mechanics.entrySet()) {
            DungeonMechanicState state = entry.getValue();
            String line;
            try {
                line = "  " + entry.getKey() + " " + state.getClass().getSimpleName() + " state=" + state.getCurrentState();
            } catch (Exception e) {
                line = "  " + entry.getKey() + " " + state.getClass().getSimpleName() + " state=§c<" + e.getClass().getSimpleName() + ">";
            }
            print(line);
            dumpMechanicBlocks(state, room);
        }
    }

    private static void dumpMechanicBlocks(DungeonMechanicState state, DungeonRoom room) {
        List<OffsetPoint> points = new ArrayList<>();
        if (state instanceof WorldMutatingMechanicState) {
            points.addAll(((WorldMutatingMechanicState) state).blockedPoints());
        } else if (state instanceof DungeonLeverState) {
            points.add(((DungeonLeverState) state).getData().getLeverPoint());
        } else if (state instanceof DungeonOnewayLeverState) {
            points.add(((DungeonOnewayLeverState) state).getData().getLeverPoint());
        } else if (state instanceof DungeonPressurePlateState) {
            points.add(((DungeonPressurePlateState) state).getData().getPlatePoint());
        } else if (state.getRepresentingPoint() != null) {
            points.add(state.getRepresentingPoint());
        }
        if (points.isEmpty()) return;

        StringBuilder sb = new StringBuilder("    blocks: ");
        int shown = 0;
        for (OffsetPoint point : points) {
            if (shown >= 4) {
                sb.append(" +").append(points.size() - shown).append(" more");
                break;
            }
            try {
                BlockPos pos = point.getBlockPos(room);
                BlockState block = point.getBlock(room);
                sb.append("(").append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ()).append(")=")
                        .append(block == null ? "null" : block.toString()).append(" ");
                shown++;
            } catch (Exception e) {
                sb.append(point).append("=<").append(e.getClass().getSimpleName()).append("> ");
            }
        }
        print(sb.toString());
    }
}

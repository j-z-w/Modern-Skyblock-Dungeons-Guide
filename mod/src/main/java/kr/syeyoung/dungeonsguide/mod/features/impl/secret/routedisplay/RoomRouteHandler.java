package kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay;

import kr.syeyoung.dungeonsguide.mod.events.impl.LivingDeathEvent;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.route.ActionRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEntityEvent;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.IPathDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import lombok.Getter;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEvent;

import java.util.*;
import java.util.function.Function;

public class RoomRouteHandler {
    @Getter
    private Map<String, IPathDisplayEngine<?>> path = new HashMap<>();
    @Getter
    private final DungeonRoom dungeonRoom;
    @Getter
    private AlgorithmSetting algorithmSetting;


    public RoomRouteHandler(DungeonRoom targetRoom) {
        this.dungeonRoom = targetRoom;
        algorithmSetting = targetRoom.getContext().getPreset().getAlgorithmSetting(); // TODO: fix
    }

    public void tick() {
        path.values().removeIf(engine -> {
            engine.tick();
            engine.getActionRoute().onTick();
            return engine.getActionRoute().getCurrentAction() instanceof ActionComplete;
        });
    }

    public void onWorldRenderLast(RenderWorldEvent event) {
        for (IPathDisplayEngine<?> a : path.values()) {
            a.renderActionRoute(event);
        }
    }

    public void onInteract(PlayerInteractEntityEvent event) {
        for (IPathDisplayEngine<?> a : path.values()) {
            a.getActionRoute().onLivingInteract(event);
        }
    }

    public void onInteract(PlayerInteractEvent event) {
        for (IPathDisplayEngine<?> a : path.values()) {
            a.getActionRoute().onPlayerInteract(event);
        }
    }

    public void onEntityDeath(LivingDeathEvent event) {
        for (IPathDisplayEngine<?> a : path.values()) {
            a.getActionRoute().onLivingDeath(event);
        }
    }

    public IPathDisplayEngine<?> getPath(String id){
        return path.get(id);
    }

    public String pathfind(String mechanic, String state, Function<ActionRoute, IPathDisplayEngine<?>> converter) throws PathfindImpossibleException {
        String str = UUID.randomUUID().toString();
        pathfind(str, mechanic, state, converter);
        return str;
    }

    public void pathfind(String id, String mechanic, String state, Function<ActionRoute, IPathDisplayEngine<?>> converter) throws PathfindImpossibleException {
        pathfind(id, converter.apply(new ActionRoute(dungeonRoom, mechanic, state, algorithmSetting)));
    }
    public void pathfind(String id, IPathDisplayEngine<?> pathDisplayEngine) {
        path.put(id, pathDisplayEngine);
    }


    public void cancelAll() {
        path.clear();
    }
    public void cancel(String id) {
        path.remove(id);
    }
}

package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.IPathDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.routedisplay.RoomRouteHandler;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// replacing a route in the handler must cancel the replaced route's in-flight
// solve, not just swap the display: a recalculated smart route used to leave the
// previous native TSP solve running to completion
public class RouteReplacementTest {

    private static class StubEngine implements IPathDisplayEngine<Object> {
        final ActionRoute route;
        StubEngine(ActionRoute route) { this.route = route; }
        public void renderActionRoute(RenderWorldEvent event) {}
        public ActionRoute getActionRoute() { return route; }
        public void tick() {}
        public Object getSettings() { return null; }
        public void setSettings(Object settings) {}
    }

    @Test
    void replacingRouteCancelsOldSolve() throws Exception {
        TestRooms.boot();
        DungeonRoomInfo info = TestRooms.anyRoom();
        DungeonRoom room = TestRooms.buildRoom(TestRooms.presetFor(info), info);
        RoomRouteHandler handler = new RoomRouteHandler(room);

        ActionDAG dag = new ActionDAGBuilder(room).build();
        ActionRoute old = new ActionRoute("old", room, dag, true);
        ActionRoute replacement = new ActionRoute("new", room, dag, true);

        int oldGen = old.pathGeneration.get();
        int newGen = replacement.pathGeneration.get();

        handler.pathfind("smart", new StubEngine(old));
        assertEquals(oldGen, old.pathGeneration.get(), "inserting a fresh route doesn't cancel it");

        handler.pathfind("smart", new StubEngine(replacement));
        assertTrue(old.pathGeneration.get() > oldGen,
                "the replaced route's generation must be invalidated so its workers bail out");
        assertEquals(newGen, replacement.pathGeneration.get(),
                "the replacement route itself must not be cancelled");
    }

    @Test
    void replacingWithSameEngineDoesNotCancel() throws Exception {
        TestRooms.boot();
        DungeonRoomInfo info = TestRooms.anyRoom();
        DungeonRoom room = TestRooms.buildRoom(TestRooms.presetFor(info), info);
        RoomRouteHandler handler = new RoomRouteHandler(room);
        ActionDAG dag = new ActionDAGBuilder(room).build();
        ActionRoute route = new ActionRoute("same", room, dag, true);
        StubEngine engine = new StubEngine(route);

        handler.pathfind("smart", engine);
        int gen = route.pathGeneration.get();
        handler.pathfind("smart", engine);
        assertEquals(gen, route.pathGeneration.get(),
                "re-inserting the same engine is a no-op, not a self-cancellation");
    }
}

package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TestRooms;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSettingRegistry;
import kr.syeyoung.dungeonsguide.mod.utils.RenderThreadDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// B3: route publication. the render thread must see order/actions/current swap
// as one unit on the render thread, in submission order, and a publication from
// a superseded generation must be dropped
public class ActionRoutePublicationTest {

    private static final AlgorithmSetting SETTING = AlgorithmSettingRegistry.STANDARD_DEFAULT_ALGORITHM_SETTING;

    // ActionRoute's static solver executors are registered on the DungeonsGuide
    // instance at class-load - the class won't even init without a full boot
    @BeforeAll
    static void boot() throws Exception {
        TestRooms.boot();
    }

    private static class CostAction extends AbstractAction {
        private final String name;

        CostAction(String name) {
            this.name = name;
        }

        @Override
        public double evalulateCost(RoomState state, kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom room,
                                    kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache tspCache,
                                    kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner pathPlanner) {
            return 1;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static ActionDAG smallDAG() throws Exception {
        ActionDAGBuilder b = new ActionDAGBuilder(null);
        b.requires(new CostAction("goal"), SETTING)
                .requires(new CostAction("a"), SETTING).end();
        return b.build();
    }

    @Test
    void publishAppendsTerminalAndAppliesOnDrain() throws Exception {
        ActionDAG dag = smallDAG();
        ActionRoute route = new ActionRoute("t", null, dag, true);
        List<ActionDAGNode> nodes = dag.getAllNodes();

        route.publish(nodes, route.pathGeneration.get());
        // nothing applied before the render-thread queue drains: workers must not
        // publish directly or a worker-thread write could tear under the renderer
        assertNull(route.getActions());
        RenderThreadDispatcher.drain();

        assertEquals(nodes.size() + 1, route.getActions().size(), "terminal ActionComplete appended");
        assertSame(nodes, route.getOrder());
        assertEquals(0, route.getCurrent());
    }

    @Test
    void staleGenerationIsDropped() throws Exception {
        ActionDAG dag = smallDAG();
        ActionRoute route = new ActionRoute("t", null, dag, true);
        List<ActionDAGNode> nodes = dag.getAllNodes();

        route.publish(nodes, route.pathGeneration.get());
        RenderThreadDispatcher.drain();
        int applied = route.getActions().size();

        // a worker that started under the previous generation must not overwrite
        route.pathGeneration.incrementAndGet();
        route.publish(java.util.Collections.emptyList(), route.pathGeneration.get() - 1);
        RenderThreadDispatcher.drain();
        assertEquals(applied, route.getActions().size(), "stale publication must be dropped");
    }

    @Test
    void currentIsClampedAtEnd() throws Exception {
        ActionDAG dag = smallDAG();
        ActionRoute route = new ActionRoute("t", null, dag, true);
        route.publish(dag.getAllNodes(), route.pathGeneration.get());
        RenderThreadDispatcher.drain();

        for (int i = 0; i < route.getActions().size() + 5; i++) route.next();
        assertEquals(route.getActions().size() - 1, route.getCurrent(),
                "current must stay clamped, not walk past the published route");
    }

    @Test
    void solverFailureReleasesCalculating() throws Exception {
        // SF3: a solve that dies before producing a route (null preset / planner
        // construction failure - modelled here by a null room) must still publish the
        // topological fallback; `calculating` used to stay true forever because the
        // exception was lost inside the discarded Future
        ActionDAG dag = smallDAG();
        ActionRoute route = new ActionRoute("t", null, dag, false);

        // the worker publishes asynchronously through the render-thread dispatcher;
        // actions is a placeholder from the start, so wait on the calculating flag
        long deadline = System.currentTimeMillis() + 10_000;
        while (route.isCalculating() && System.currentTimeMillis() < deadline) {
            RenderThreadDispatcher.drain();
            Thread.sleep(10);
        }
        RenderThreadDispatcher.drain();
        assertFalse(route.isCalculating(), "calculating must release even when the solve fails");
        assertNotNull(route.getOrder(), "the fallback order must be published on failure");
    }
}

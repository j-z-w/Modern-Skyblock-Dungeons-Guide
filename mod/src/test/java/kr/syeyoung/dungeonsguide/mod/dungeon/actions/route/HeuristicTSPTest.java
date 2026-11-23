package kr.syeyoung.dungeonsguide.mod.dungeon.actions.route;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.PathfindImpossibleException;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAG;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGNode;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSettingRegistry;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class HeuristicTSPTest {
    private static final AlgorithmSetting SETTING = AlgorithmSettingRegistry.STANDARD_DEFAULT_ALGORITHM_SETTING;

    private static class CostAction extends AbstractAction {
        private final String name;
        private final double cost;

        CostAction(String name, double cost) {
            this.name = name;
            this.cost = cost;
        }

        @Override
        public double evalulateCost(RoomState state, kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom room,
                                    kr.syeyoung.dungeonsguide.mod.pathfinding.TSPCache tspCache,
                                    kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPresetPathPlanner pathPlanner) {
            return cost;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<ActionDAGNode> solve(ActionDAG dag) {
        HeuristicTSP tsp = new HeuristicTSP(dag, new Vec3(0, 0, 0), null, null, null);
        HeuristicTSP.Result result = tsp.solve(() -> false, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertTrue(tsp.isValidOrder(result.getOrder()), "order must satisfy DAG dependencies");
        return result.getOrder();
    }

    private static int pos(List<ActionDAGNode> order, ActionDAG dag, String name) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getAction().toString().equals(name)) return i;
        }
        return -1;
    }

    // goal requires a,b,c; one of x/y as or-alternative; opt optional; x itself requires inner
    private static ActionDAG buildDag() throws PathfindImpossibleException {
        ActionDAGBuilder builder = new ActionDAGBuilder(null);
        builder.requires(new CostAction("goal", 0), SETTING)
                .requires(new CostAction("a", 5), SETTING).end()
                .requires(new CostAction("b", 1), SETTING).end()
                .requires(new CostAction("c", 3), SETTING).end()
                .or(new CostAction("x", 100), SETTING)
                    .requires(new CostAction("inner", 50), SETTING).end()
                .end()
                .or(new CostAction("y", 2), SETTING).end()
                .optional(new CostAction("opt", 0.5), SETTING).end();
        return builder.build();
    }

    @Test
    void respectsRequiresAndOrDependencies() throws Exception {
        ActionDAG dag = buildDag();
        List<ActionDAGNode> order = solve(dag);

        int goal = pos(order, dag, "goal");
        assertTrue(goal >= 0, "goal must be in the order");
        for (String req : new String[]{"a", "b", "c"}) {
            int p = pos(order, dag, req);
            assertTrue(p >= 0 && p < goal, req + " must precede goal");
        }

        // exactly one or-alternative: cheaper y wins, x's whole subtree stays out
        int y = pos(order, dag, "y");
        assertTrue(y >= 0 && y < goal, "cheaper or-alternative must be chosen before goal");
        assertEquals(-1, pos(order, dag, "x"), "losing or-alternative must be skipped");
        assertEquals(-1, pos(order, dag, "inner"), "child of skipped or-alternative must be pruned");
    }

    @Test
    void respectsNestedOrDependency() throws Exception {
        // force x to win: make y absurdly expensive
        ActionDAGBuilder builder = new ActionDAGBuilder(null);
        builder.requires(new CostAction("goal", 0), SETTING)
                .or(new CostAction("x", 1), SETTING)
                    .requires(new CostAction("inner", 0.5), SETTING).end()
                .end()
                .or(new CostAction("y", 1000), SETTING).end();
        ActionDAG dag = builder.build();
        List<ActionDAGNode> order = solve(dag);

        int x = pos(order, dag, "x");
        int inner = pos(order, dag, "inner");
        int goal = pos(order, dag, "goal");
        assertTrue(inner >= 0 && inner < x, "or-child's require must precede it");
        assertTrue(x >= 0 && x < goal, "or-child must precede its parent");
        assertEquals(-1, pos(order, dag, "y"), "losing or-alternative must be skipped");
    }

    @Test
    void cancellationReturnsImmediately() throws Exception {
        ActionDAG dag = buildDag();
        HeuristicTSP tsp = new HeuristicTSP(dag, new Vec3(0, 0, 0), null, null, null);
        BooleanSupplier alwaysCancelled = () -> true;
        HeuristicTSP.Result result = tsp.solve(alwaysCancelled, System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
        assertNotNull(result.getOrder());
        // whatever it managed to emit must still be a legal order
        assertTrue(tsp.isValidOrder(result.getOrder()));
    }

    @Test
    void deadlineProducesCompleteOrder() throws Exception {
        ActionDAG dag = buildDag();
        HeuristicTSP tsp = new HeuristicTSP(dag, new Vec3(0, 0, 0), null, null, null);
        long t0 = System.nanoTime();
        // essentially no time: greedy must still emit a complete dep-respecting route
        HeuristicTSP.Result result = tsp.solve(() -> false, t0 + 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsedMs < 5000, "must terminate promptly, took " + elapsedMs + "ms");
        assertTrue(tsp.isValidOrder(result.getOrder()));
        int goal = pos(result.getOrder(), dag, "goal");
        assertTrue(goal >= 0, "even a deadline-truncated solve publishes a usable route");
    }
}

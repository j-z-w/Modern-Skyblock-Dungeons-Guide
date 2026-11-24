package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle;

import kr.syeyoung.dungeonsguide.mod.dungeon.actions.AbstractActionMove;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.PathfinderExecutor;

import java.util.Map;

// read-only snapshot of a display engine's per-move pathfinder state, for dgdebug routestatus
public interface MoveDiagnostics {
    Map<AbstractActionMove, MoveDiag> describeMoves();

    class MoveDiag {
        public final PathfinderExecutor executor;
        public final PathfindResult result;

        public MoveDiag(PathfinderExecutor executor, PathfindResult result) {
            this.executor = executor;
            this.result = result;
        }
    }
}

package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath;

import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NeoRouteDisplayEngineBeaconTest {
    @Test
    void multiTargetMoveBeaconFollowsReconstructedDestination() {
        BlockPos fallback = new BlockPos(10, 86, 26);
        PathfindResult route = new PathfindResult(Arrays.asList(
                new PathfindResult.PathfindNode(12.5, 89.05, 22.5, PathfindResult.PathfindNode.NodeType.WALK),
                new PathfindResult.PathfindNode(10.5, 89.05, 25.0, PathfindResult.PathfindNode.NodeType.DESTINATION)
        ), 1.0);

        assertEquals(new BlockPos(10, 89, 25), NeoRouteDisplayEngine.beaconTarget(fallback, route));
        assertEquals(fallback, NeoRouteDisplayEngine.beaconTarget(fallback, null));
        assertEquals(fallback, NeoRouteDisplayEngine.beaconTarget(fallback,
                new PathfindResult(Arrays.asList(
                        new PathfindResult.PathfindNode(12.5, 89.05, 22.5, PathfindResult.PathfindNode.NodeType.WALK)
                ), 1.0)));
    }
}

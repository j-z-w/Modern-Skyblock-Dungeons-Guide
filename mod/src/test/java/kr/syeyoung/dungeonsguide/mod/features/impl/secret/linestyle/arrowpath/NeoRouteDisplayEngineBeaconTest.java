package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath;

import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    @Test
    void terminalDigStepVolumeIncludesDestinationCell() {
        List<BlockPos> volume = new ArrayList<>();
        NeoRouteDisplayEngine.addPlayerVolume(volume,
                new PathfindResult.PathfindNode(10.5, 89.05, 24.5, PathfindResult.PathfindNode.NodeType.STONK_WALK));
        NeoRouteDisplayEngine.addPlayerVolume(volume,
                new PathfindResult.PathfindNode(10.5, 89.05, 25.0, PathfindResult.PathfindNode.NodeType.DESTINATION));

        org.junit.jupiter.api.Assertions.assertTrue(volume.contains(new BlockPos(10, 89, 25)));
        org.junit.jupiter.api.Assertions.assertTrue(volume.contains(new BlockPos(10, 90, 25)));
    }

}

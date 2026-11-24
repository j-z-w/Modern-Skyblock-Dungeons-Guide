package kr.syeyoung.dungeonsguide.mod.pathfinding.world;

import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.RoomBounds;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import java.util.Set;

public class CoordinateMapBackedPathfindWorld implements IPathfindWorld {
    private ICoordinateMap<BlockState> backingWorld;

    private AlgorithmSetting algorithmSetting;

    private BitCachingCoordinateMap<PearlCalculatingCoordinateMap.PearlLandType> enderpearl;
    private BitCachingCoordinateMap<CollisionStateCalculatingCoordinateMap.CollisionState> whole;
    private InstaBreakFactorCalculatingCoordinateMap instaBreak;

    private RoomBounds roomBounds;

    public CoordinateMapBackedPathfindWorld(ICoordinateMap<BlockState> backingWorld, AlgorithmSetting algorithmSetting, RoomBounds roomBounds, Set<BlockPos> superboom) { // plan to remove roombounds.
        this(backingWorld, algorithmSetting, roomBounds, superboom, java.util.Collections.emptySet());
    }

    // breakerForbidden: absolute BlockPos the Dungeon Breaker may not mine (mechanic-controlled
    // blocks); only consulted when algorithmSetting.isDungeonBreaker()
    public CoordinateMapBackedPathfindWorld(ICoordinateMap<BlockState> backingWorld, AlgorithmSetting algorithmSetting, RoomBounds roomBounds, Set<BlockPos> superboom, Set<BlockPos> breakerForbidden) {
        this.backingWorld = backingWorld;

        minx = roomBounds.getMinX() * 2 + 2; miny = 0; minz = roomBounds.getMinZ() * 2 + 2;
        maxx = roomBounds.getMaxX() * 2 + 2; maxy = 255 * 2 + 2; maxz = roomBounds.getMaxZ() * 2 + 2;
        lenx = maxx - minx; leny = maxy - miny; lenz = maxz - minz;
        this.roomBounds = roomBounds;

        instaBreak = new InstaBreakFactorCalculatingCoordinateMap(backingWorld, algorithmSetting, breakerForbidden);
        enderpearl = new BitCachingCoordinateMap<>(new PearlCalculatingCoordinateMap(backingWorld, roomBounds), PearlCalculatingCoordinateMap.PearlLandType.VALUES, PearlCalculatingCoordinateMap.PearlLandType.BLOCKED);
        whole = new BitCachingCoordinateMap<>(new CollisionStateCalculatingCoordinateMap(backingWorld, superboom, instaBreak, roomBounds), CollisionStateCalculatingCoordinateMap.CollisionState.VALUES, CollisionStateCalculatingCoordinateMap.CollisionState.BLOCKED);
    }

    @Override
    public BlockState getActualBlock(int x, int y, int z) {
        return backingWorld.getBlock(x, y, z);
    }

    @Override
    public CollisionStateCalculatingCoordinateMap.CollisionState getBlock(int x, int y, int z) {
        return whole.getBlock(x, y, z);
    }

    @Override
    public PearlCalculatingCoordinateMap.PearlLandType getPearl(int x, int y, int z) {
        return enderpearl.getBlock(x, y, z);
    }


    private final int minx;
    private final int miny;
    private final int minz;
    private final int maxx;
    private final int maxy;
    private final int maxz;
    private final int lenx, leny, lenz;

    @Override
    public boolean isInstabreak(int x, int y, int z) {
        if (!roomBounds.canAccessRelative(x/2, z/2)) return false;
        if (x%2 != 0 && z%2 != 0) return false;

        return instaBreak.getBlock(x/2, y/2, z/2).getFactor()  == 0;
    }

    @Override
    public int getXwidth() {
        return lenx;
    }

    @Override
    public int getYwidth() {
        return leny;
    }

    @Override
    public int getZwidth() {
        return lenz;
    }

    @Override
    public int getMinX() {
        return minx;
    }

    @Override
    public int getMinY() {
        return miny;
    }

    @Override
    public int getMinZ() {
        return minz;
    }



    public void resetBlock(BlockPos pos) {
        // invalidating instead of recomputing: the backing value is derived lazily on next read,
        // so a block-update burst only costs bit clears, not 550 world lookups per packet
        whole.invalidateRange(pos.getX() * 2 - 2, pos.getY() * 2 - 5, pos.getZ() * 2 - 2,
                pos.getX() * 2 + 3, pos.getY() * 2 + 6, pos.getZ() * 2 + 3);
        enderpearl.invalidateRange(pos.getX() * 2 - 2, pos.getY() * 2 - 5, pos.getZ() * 2 - 2,
                pos.getX() * 2 + 3, pos.getY() * 2 + 6, pos.getZ() * 2 + 3);
    }

    public void resetChunk(int cx, int cz) {
        // both maps live in 2x half-block space (see minx/maxy above) - the block-space chunk
        // range misses every odd half-block, especially with negative coordinates
        whole.invalidateRange(cx * 32 - 2, 0, cz * 32 - 2, cx * 32 + 34, 512, cz * 32 + 34);
        enderpearl.invalidateRange(cx * 32 - 2, 0, cz * 32 - 2, cx * 32 + 34, 512, cz * 32 + 34);
    }
}

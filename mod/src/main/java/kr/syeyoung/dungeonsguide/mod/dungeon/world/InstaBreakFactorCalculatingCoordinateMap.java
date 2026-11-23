package kr.syeyoung.dungeonsguide.mod.dungeon.world;

import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;

public class InstaBreakFactorCalculatingCoordinateMap implements ICoordinateMap<InstaBreakFactorCalculatingCoordinateMap.BreakFactor> {
    private ICoordinateMap<BlockState> map;
    private CoordinateMapWorld world;
    private AlgorithmSetting algorithmSetting;

    @Getter
    private int minX, minY, minZ, maxX, maxY, maxZ, lenX, lenY, lenZ;

    public InstaBreakFactorCalculatingCoordinateMap(ICoordinateMap<BlockState> map, AlgorithmSetting algorithmSetting) {
        this.map = map;
        this.world = new CoordinateMapWorld(map);
        this.minX = map.getMinX(); this.minY = map.getMinY(); this.minZ = map.getMinZ();
        this.maxX = map.getMaxX(); this.maxY = map.getMaxY(); this.maxZ = map.getMaxZ();
        this.lenX = maxX - minX;
        this.lenY = maxY - minY;
        this.lenZ = maxZ - minZ;
        this.algorithmSetting = algorithmSetting;
    }

    // the pickaxe speed the remote calculation service precalculates with (fitted against the
    // shipped precalculations: 1.5-hardness pickaxe blocks and soft blocks insta, wood does not).
    // dg.remotePickaxeSpeed overrides it for reproducing older service generations; <=0 disables
    // the canonical tool entirely, matching the oldest shipped files' all-MAYBE_HARD semantics.
    private static final double REMOTE_PICKAXE_SPEED =
            Double.parseDouble(System.getProperty("dg.remotePickaxeSpeed", "150.0"));

    private ThreadLocal<BlockPos.MutableBlockPos> blockPosThreadLocal = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Override
    public BreakFactor getBlock(int x, int y, int z) {
        BlockState iBlockState = map.getBlock(x, y, z);
        BlockPos.MutableBlockPos pos = blockPosThreadLocal.get();
        pos.set(x, y, z);
        // 1.8.9 only had one air
        if (iBlockState.isAir()) return BreakFactor.INSTABREAK;
        BlockAndTintGetter getter = world.asVanilla();
        float hardness = iBlockState.getDestroySpeed(getter, pos);
        if (hardness < 0) {
            return BreakFactor.NO;
        }
        // Precalc requests ship no tool config; the remote calculation service ignores that and
        // runs its own canonical pickaxe. Speed 150 reproduces the shipped 1.8.9-era results:
        // rock/iron/anvil blocks up to hardness 5 insta-break, soft no-tool blocks up to 1.5 do,
        // and wood/harder blocks count toward notstonkable.
        if (algorithmSetting.getPickaxe() == null && algorithmSetting.getShovel() == null
                && algorithmSetting.getAxe() == null) {
            if ((LegacyCollision.isPickaxeEffective(iBlockState) && hardness <= REMOTE_PICKAXE_SPEED / 30.0)
                    || hardness <= REMOTE_PICKAXE_SPEED / 100.0) return BreakFactor.INSTABREAK;
            if (!algorithmSetting.isAllowSlowStonkPath())
                return BreakFactor.NO;
            return BreakFactor.MAYBE_HARD;
        }
        if (algorithmSetting.getPickaxeSpeed() > 0 &&
                // the stub pickaxe is the highest level, so it's the correct tool for anything mineable with a pickaxe (D125)
                ((LegacyCollision.isPickaxeEffective(iBlockState) &&
                        hardness <= algorithmSetting.getPickaxeSpeed() / 30.0) ||
                        (hardness <= algorithmSetting.getPickaxeSpeed() / 100.0))
        ) {
        } else if (algorithmSetting.getShovelSpeed() > 0
                // forge's isToolEffective("shovel") / ("axe"), keyed off the 1.8.9 ids (D125)
                && LegacyCollision.isShovelEffective(iBlockState)
                && hardness <= algorithmSetting.getShovelSpeed()) {
        } else if (algorithmSetting.getAxeSpeed() > 0 && LegacyCollision.isAxeEffective(iBlockState) && hardness <= algorithmSetting.getAxeSpeed()) {
        } else {
            if (!algorithmSetting.isAllowSlowStonkPath())
                return BreakFactor.NO;
            return BreakFactor.MAYBE_HARD;
        }
        return BreakFactor.INSTABREAK;
    }

    @Override
    public boolean isInScope(int x, int y, int z) {
        return map.isInScope(x, y, z);
    }

    @AllArgsConstructor
    public enum BreakFactor {
        INSTABREAK(0), MAYBE_HARD(1), NO(99);

        @Getter
        private int factor;
    }
}

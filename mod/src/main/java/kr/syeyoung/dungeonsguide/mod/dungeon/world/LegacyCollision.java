package kr.syeyoung.dungeonsguide.mod.dungeon.world;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.TintedGlassBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 1.8.9's Block.addCollisionBoxesToList rebuilt on legacy ids (D110/D112): the blocks' collision
 * boxes, as AABBs, added when they strictly intersect the mask. Blocks 1.8.9 didn't have fall back
 * to their modern collision shape AABBs.
 *
 * <p>This is the single per-legacy-id 1.8.9 table: collision type, opacity/full-cube/material flags,
 * fence/pane/gate classification, harvest-tool effectiveness and static boxes all live here.
 * {@link kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlockShapes} builds its raytrace layer on
 * top of the same table and delegates entity-collision queries to {@link #addCollisionBoxesToList},
 * so pathfinding and clicking-spot generation can't drift apart on a per-id fix.
 */
public final class LegacyCollision {
    private LegacyCollision() {
    }

    private static final int MODERN = -1, NONE = 0, FULL = 1, BOX = 2, STAIRS = 3, SLAB = 4,
            SNOW = 5, FENCE = 6, PANE = 7, WALL = 8, GATE = 9, DOOR = 10, TRAPDOOR = 11,
            SKULL = 12, CHEST = 13, COCOA = 14, LADDER = 15, CAKE = 16, CAULDRON = 17,
            BREWING = 18, HOPPER = 19, EPF = 20, PHEAD = 21;

    private static final int OPAQUE = 1, FULLC = 2, MOPAQUE = 4, GOURD = 8,
            FEN = 16, FENCE_WOOD = 32, GAT = 64, PAN = 128, PICK = 256, SHOVEL = 512, AXE = 1024;

    private static final int[] T = new int[256];
    private static final int[] F = new int[256];
    private static final float[][] B = new float[256][];

    private static void t(int id, int type, int flags) {
        T[id] = type;
        F[id] = flags;
    }

    private static void t(int id, int type, float[] box, int flags) {
        t(id, type, flags);
        B[id] = box;
    }

    private static float[] b(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new float[]{(float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1};
    }

    static {
        java.util.Arrays.fill(T, MODERN);
        java.util.Arrays.fill(F, 0);
        int o = OPAQUE, f = FULLC, m = MOPAQUE;
        t(0, NONE, 0); // air
        t(1, FULL, o | f | m); // stone
        t(2, FULL, o | f | m); // grass
        t(3, FULL, o | f | m); // dirt
        t(4, FULL, o | f | m); // cobblestone
        t(5, FULL, o | f | m); // planks
        t(6, NONE, 0); // sapling
        t(7, FULL, o | f | m); // bedrock
        t(8, NONE, 0); // flowing_water
        t(9, NONE, 0); // water
        t(10, NONE, 0); // flowing_lava
        t(11, NONE, 0); // lava
        t(12, FULL, o | f | m); // sand
        t(13, FULL, o | f | m); // gravel
        t(14, FULL, o | f | m); // gold_ore
        t(15, FULL, o | f | m); // iron_ore
        t(16, FULL, o | f | m); // coal_ore
        t(17, FULL, o | f | m); // log
        t(18, FULL, o | f); // leaves (opaqueCube = !fancyGraphics -> true server-side)
        t(19, FULL, o | f | m); // sponge
        t(20, FULL, 0); // glass (Breakable: not opaque cube, not full cube, glass material)
        t(21, FULL, o | f | m); // lapis_ore
        t(22, FULL, o | f | m); // lapis_block
        t(23, FULL, o | f | m); // dispenser
        t(24, FULL, o | f | m); // sandstone
        t(25, FULL, o | f | m); // noteblock
        t(26, BOX, b(0, 0, 0, 1, 0.5625, 1), m); // bed
        t(27, NONE, 0); // powered_rail
        t(28, NONE, 0); // detector_rail
        t(29, FULL, m); // sticky_piston
        t(30, NONE, 0); // web
        t(31, NONE, 0); // tallgrass
        t(32, NONE, 0); // deadbush
        t(33, FULL, m); // piston
        t(34, PHEAD, 0); // piston_head
        t(35, FULL, o | f | m); // wool
        t(36, NONE, 0); // piston_extension (moving): null box without a tile entity
        t(37, NONE, 0); // yellow_flower
        t(38, NONE, 0); // red_flower
        t(39, NONE, 0); // brown_mushroom
        t(40, NONE, 0); // red_mushroom
        t(41, FULL, o | f | m); // gold_block
        t(42, FULL, o | f | m); // iron_block
        t(43, FULL, o | f | m); // double_stone_slab
        t(44, SLAB, m); // stone_slab
        t(45, FULL, o | f | m); // brick_block
        t(46, FULL, o | f); // tnt (tnt material is not opaque)
        t(47, FULL, o | f | m); // bookshelf
        t(48, FULL, o | f | m); // mossy_cobblestone
        t(49, FULL, o | f | m); // obsidian
        t(50, NONE, 0); // torch
        t(51, NONE, 0); // fire
        t(52, FULL, f | m); // mob_spawner (not an opaque cube: panes don't connect)
        t(53, STAIRS, m); // oak_stairs
        t(54, CHEST, m); // chest
        t(55, NONE, 0); // redstone_wire
        t(56, FULL, o | f | m); // diamond_ore
        t(57, FULL, o | f | m); // diamond_block
        t(58, FULL, o | f | m); // crafting_table
        t(59, NONE, 0); // wheat
        t(60, FULL, m); // farmland (not opaque cube / full cube)
        t(61, FULL, o | f | m); // furnace
        t(62, FULL, o | f | m); // lit_furnace
        t(63, NONE, m); // standing_sign
        t(64, DOOR, m); // wooden_door
        t(65, LADDER, 0); // ladder
        t(66, NONE, 0); // rail
        t(67, STAIRS, m); // stone_stairs
        t(68, NONE, m); // wall_sign
        t(69, NONE, 0); // lever
        t(70, NONE, m); // stone_pressure_plate
        t(71, DOOR, m); // iron_door
        t(72, NONE, m); // wooden_pressure_plate
        t(73, FULL, o | f | m); // redstone_ore
        t(74, FULL, o | f | m); // lit_redstone_ore
        t(75, NONE, 0); // unlit_redstone_torch
        t(76, NONE, 0); // redstone_torch
        t(77, NONE, 0); // stone_button
        t(78, SNOW, 0); // snow_layer
        t(79, FULL, f); // ice (Breakable: not opaque cube, ice material)
        t(80, FULL, o | f | m); // snow
        t(81, BOX, b(0.0625, 0, 0.0625, 0.9375, 0.9375, 0.9375), 0); // cactus
        t(82, FULL, o | f | m); // clay
        t(83, NONE, 0); // reeds
        t(84, FULL, o | f | m); // jukebox
        t(85, FENCE, m | FEN | FENCE_WOOD); // fence
        t(86, FULL, o | f | m | GOURD); // pumpkin
        t(87, FULL, o | f | m); // netherrack
        t(88, BOX, b(0, 0, 0, 1, 0.875, 1), o | f | m); // soul_sand (opaque cube!)
        t(89, FULL, o | f); // glowstone (opaque cube, glass material)
        t(90, NONE, 0); // portal (BlockBreakable: not an opaque cube)
        t(91, FULL, o | f | m | GOURD); // lit_pumpkin
        t(92, CAKE, m); // cake
        t(93, BOX, b(0, 0, 0, 1, 0.125, 1), 0); // unpowered_repeater
        t(94, BOX, b(0, 0, 0, 1, 0.125, 1), 0); // powered_repeater
        t(95, FULL, 0); // stained_glass
        t(96, TRAPDOOR, m); // trapdoor
        t(97, FULL, o | f | m); // monster_egg
        t(98, FULL, o | f | m); // stonebrick
        t(99, FULL, o | f | m); // brown_mushroom_block
        t(100, FULL, o | f | m); // red_mushroom_block
        t(101, PANE, m | PAN); // iron_bars (iron material!)
        t(102, PANE, PAN); // glass_pane
        t(103, FULL, o | f | m | GOURD); // melon_block
        t(104, NONE, 0); // pumpkin_stem
        t(105, NONE, 0); // melon_stem
        t(106, NONE, 0); // vine
        t(107, GATE, m | GAT); // fence_gate
        t(108, STAIRS, m); // brick_stairs
        t(109, STAIRS, m); // stone_brick_stairs
        t(110, FULL, o | f | m); // mycelium
        t(111, BOX, b(0, 0, 0, 1, 0.015625, 1), 0); // waterlily
        t(112, FULL, o | f | m); // nether_brick
        t(113, FENCE, m | FEN); // nether_brick_fence (rock material)
        t(114, STAIRS, m); // nether_brick_stairs
        t(115, NONE, 0); // nether_wart
        t(116, BOX, b(0, 0, 0, 1, 0.75, 1), m); // enchanting_table
        t(117, BREWING, m); // brewing_stand
        t(118, CAULDRON, m); // cauldron
        t(119, NONE, 0); // end_portal
        t(120, EPF, f | m); // end_portal_frame (not opaque cube, but IS a full cube)
        t(121, FULL, o | f | m); // end_stone
        t(122, BOX, b(0.0625, 0, 0.0625, 0.9375, 1, 0.9375), m); // dragon_egg
        t(123, FULL, o | f | m); // redstone_lamp
        t(124, FULL, o | f | m); // lit_redstone_lamp
        t(125, FULL, o | f | m); // double_wooden_slab
        t(126, SLAB, m); // wooden_slab
        t(127, COCOA, 0); // cocoa
        t(128, STAIRS, m); // sandstone_stairs
        t(129, FULL, o | f | m); // emerald_ore
        t(130, BOX, b(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375), m); // ender_chest
        t(131, NONE, 0); // tripwire_hook
        t(132, NONE, 0); // tripwire
        t(133, FULL, o | f | m); // emerald_block
        t(134, STAIRS, m); // spruce_stairs
        t(135, STAIRS, m); // birch_stairs
        t(136, STAIRS, m); // jungle_stairs
        t(137, FULL, o | f | m); // command_block
        t(138, FULL, 0); // beacon (not opaque cube / full cube, glass material)
        t(139, WALL, m); // cobblestone_wall
        t(140, BOX, b(0.3125, 0, 0.3125, 0.6875, 0.375, 0.6875), 0); // flower_pot
        t(141, NONE, 0); // carrots
        t(142, NONE, 0); // potatoes
        t(143, NONE, 0); // wooden_button
        t(144, SKULL, 0); // skull
        t(145, FULL, m); // anvil (collision uses the un-mutated 0..1 bounds)
        t(146, CHEST, m); // trapped_chest
        t(147, NONE, m); // light_weighted_pressure_plate
        t(148, NONE, m); // heavy_weighted_pressure_plate
        t(149, BOX, b(0, 0, 0, 1, 0.125, 1), 0); // unpowered_comparator
        t(150, BOX, b(0, 0, 0, 1, 0.125, 1), 0); // powered_comparator
        t(151, BOX, b(0, 0, 0, 1, 0.375, 1), m); // daylight_detector
        t(152, FULL, o | f | m); // redstone_block
        t(153, FULL, o | f | m); // quartz_ore
        t(154, HOPPER, m); // hopper
        t(155, FULL, o | f | m); // quartz_block
        t(156, STAIRS, m); // quartz_stairs
        t(157, NONE, 0); // activator_rail
        t(158, FULL, o | f | m); // dropper
        t(159, FULL, o | f | m); // stained_hardened_clay
        t(160, PANE, PAN); // stained_glass_pane
        t(161, FULL, o | f); // leaves2 (same as leaves)
        t(162, FULL, o | f | m); // log2
        t(163, STAIRS, m); // acacia_stairs
        t(164, STAIRS, m); // dark_oak_stairs
        t(165, FULL, f | m); // slime (Breakable: not opaque cube; clay material, full cube)
        t(166, FULL, m | f); // barrier (not opaque cube; full cube; fences/walls opt out explicitly)
        t(167, TRAPDOOR, m); // iron_trapdoor
        t(168, FULL, o | f | m); // prismarine
        t(169, FULL, o | f); // sea_lantern (opaque cube, glass material)
        t(170, FULL, o | f | m); // hay_block
        t(171, BOX, b(0, 0, 0, 1, 0.0625, 1), 0); // carpet
        t(172, FULL, o | f | m); // hardened_clay
        t(173, FULL, o | f | m); // coal_block
        t(174, FULL, o | f | m); // packed_ice
        t(175, NONE, 0); // double_plant
        t(176, NONE, m); // standing_banner
        t(177, NONE, m); // wall_banner
        t(178, BOX, b(0, 0, 0, 1, 0.375, 1), m); // daylight_detector_inverted
        t(179, FULL, o | f | m); // red_sandstone
        t(180, STAIRS, m); // red_sandstone_stairs
        t(181, FULL, o | f | m); // double_stone_slab2
        t(182, SLAB, m); // stone_slab2
        t(183, GATE, m | GAT); // spruce_fence_gate
        t(184, GATE, m | GAT); // birch_fence_gate
        t(185, GATE, m | GAT); // jungle_fence_gate
        t(186, GATE, m | GAT); // dark_oak_fence_gate
        t(187, GATE, m | GAT); // acacia_fence_gate
        t(188, FENCE, m | FEN | FENCE_WOOD); // spruce_fence
        t(189, FENCE, m | FEN | FENCE_WOOD); // birch_fence
        t(190, FENCE, m | FEN | FENCE_WOOD); // jungle_fence
        t(191, FENCE, m | FEN | FENCE_WOOD); // dark_oak_fence
        t(192, FENCE, m | FEN | FENCE_WOOD); // acacia_fence
        t(193, DOOR, m); // spruce_door
        t(194, DOOR, m); // birch_door
        t(195, DOOR, m); // jungle_door
        t(196, DOOR, m); // acacia_door
        t(197, DOOR, m); // dark_oak_door

        // 1.8.9's ItemPickaxe.canHarvestBlock: Material rock/iron/anvil + snow/snow_layer,
        // plus ice/packed_ice which Forge marks "pickaxe" via getHarvestTool.
        for (int id : new int[]{1, 4, 7, 14, 15, 16, 21, 22, 23, 24, 41, 42, 43, 44, 45, 48, 49,
                52, 56, 57, 61, 62, 67, 70, 71, 73, 74, 78, 79, 80, 87, 98, 101, 108, 109, 112,
                113, 114, 116, 117, 118, 121, 128, 129, 130, 133, 139, 145, 147, 148, 152, 153,
                154, 155, 156, 158, 159, 167, 168, 172, 173, 174, 179, 180, 181, 182}) F[id] |= PICK;

        // Forge 1.8.9's isToolEffective("shovel") / ("axe") sets (getHarvestTool == that tool):
        // shovel gets the ground-type blocks, axe the wood/gourd-material blocks.
        for (int id : new int[]{2, 3, 12, 13, 60, 82, 88, 110}) F[id] |= SHOVEL;
        for (int id : new int[]{5, 17, 25, 26, 47, 53, 54, 58, 63, 64, 68, 84, 85, 86, 91, 96,
                99, 100, 103, 107, 125, 126, 134, 135, 136, 143, 146, 151, 162, 163, 164, 170,
                176, 177, 178, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195,
                196, 197}) F[id] |= AXE;
    }

    private static void add(BlockPos pos, AABB mask, List<AABB> list,
                            double x0, double y0, double z0, double x1, double y1, double z1) {
        AABB box = new AABB(pos.getX() + x0, pos.getY() + y0, pos.getZ() + z0,
                pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1);
        // AxisAlignedBB.intersectsWith: strictly greater / less on every axis
        if (mask.intersects(box)) list.add(box);
    }

    /** 1.8.9's Block.addCollisionBoxesToList, with collidingEntity = null. */
    public static void addCollisionBoxesToList(BlockGetter world, BlockPos pos, BlockState state,
                                               AABB mask, List<AABB> list) {
        int id = LegacyBlock.legacyId(state);
        int type = id < 0 ? MODERN : T[id];
        switch (type) {
            case NONE:
                return;
            case FULL:
                add(pos, mask, list, 0, 0, 0, 1, 1, 1);
                return;
            case BOX: {
                float[] b = B[id];
                add(pos, mask, list, b[0], b[1], b[2], b[3], b[4], b[5]);
                return;
            }
            case STAIRS:
                stairs(world, pos, state, mask, list);
                return;
            case SLAB: {
                SlabType slab = state.getValue(SlabBlock.TYPE);
                if (slab == SlabType.DOUBLE) add(pos, mask, list, 0, 0, 0, 1, 1, 1);
                else if (slab == SlabType.TOP) add(pos, mask, list, 0, 0.5, 0, 1, 1, 1);
                else add(pos, mask, list, 0, 0, 0, 1, 0.5, 1);
                return;
            }
            case SNOW:
                add(pos, mask, list, 0, 0, 0, 1, (state.getValue(SnowLayerBlock.LAYERS) - 1) * 0.125, 1);
                return;
            case FENCE:
                fence(world, pos, state, id, mask, list);
                return;
            case PANE:
                pane(world, pos, mask, list);
                return;
            case WALL:
                wall(world, pos, mask, list);
                return;
            case GATE:
                if (!state.getValue(FenceGateBlock.OPEN)) {
                    if (state.getValue(FenceGateBlock.FACING).getAxis() == Direction.Axis.Z)
                        add(pos, mask, list, 0, 0, 0.375, 1, 1.5, 0.625);
                    else add(pos, mask, list, 0.375, 0, 0, 0.625, 1.5, 1);
                }
                return;
            case DOOR:
                door(world, pos, mask, list);
                return;
            case TRAPDOOR:
                trapdoor(pos, state, mask, list);
                return;
            case SKULL:
                skull(pos, state, mask, list);
                return;
            case CHEST:
                chest(world, pos, id, mask, list);
                return;
            case COCOA:
                cocoa(pos, state, mask, list);
                return;
            case LADDER:
                ladder(pos, state, mask, list);
                return;
            case CAKE: {
                double f = 0.0625, f1 = (1 + state.getValue(CakeBlock.BITES) * 2) / 16.0;
                add(pos, mask, list, f1, 0, f, 1 - f, 0.5, 1 - f);
                return;
            }
            case CAULDRON:
                add(pos, mask, list, 0, 0, 0, 1, 0.3125, 1);
                add(pos, mask, list, 0, 0, 0, 0.125, 1, 1);
                add(pos, mask, list, 0, 0, 0, 1, 1, 0.125);
                add(pos, mask, list, 0.875, 0, 0, 1, 1, 1);
                add(pos, mask, list, 0, 0, 0.875, 1, 1, 1);
                return;
            case BREWING:
                add(pos, mask, list, 0.4375, 0, 0.4375, 0.5625, 0.875, 0.5625);
                add(pos, mask, list, 0, 0, 0, 1, 0.125, 1);
                return;
            case HOPPER:
                add(pos, mask, list, 0, 0, 0, 1, 0.625, 1);
                add(pos, mask, list, 0, 0, 0, 0.125, 1, 1);
                add(pos, mask, list, 0, 0, 0, 1, 1, 0.125);
                add(pos, mask, list, 0.875, 0, 0, 1, 1, 1);
                add(pos, mask, list, 0, 0, 0.875, 1, 1, 1);
                return;
            case EPF:
                add(pos, mask, list, 0, 0, 0, 1, 0.8125, 1);
                if (state.getValue(EndPortalFrameBlock.HAS_EYE))
                    add(pos, mask, list, 0.3125, 0.8125, 0.3125, 0.6875, 1.0, 0.6875);
                return;
            case PHEAD:
                pistonHead(pos, state, mask, list);
                return;
            default:
                modern(world, pos, state, mask, list);
        }
    }

    private static void modern(BlockGetter world, BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return;
        for (AABB a : shape.toAabbs()) {
            AABB box = a.move(pos.getX(), pos.getY(), pos.getZ());
            if (mask.intersects(box)) list.add(box);
        }
    }

    private static int idAt(BlockGetter world, BlockPos pos) {
        return LegacyBlock.legacyId(world.getBlockState(pos));
    }

    private static boolean connectSolid(BlockGetter world, BlockPos pos, BlockState ns) {
        int nid = LegacyBlock.legacyId(ns);
        if (nid < 0) return ns.canOcclude() && ns.isCollisionShapeFullBlock(world, pos);
        int fl = F[nid];
        return (fl & MOPAQUE) != 0 && (fl & FULLC) != 0 && (fl & GOURD) == 0;
    }

    /** 1.8.9 `block instanceof BlockFence` (any material) for a legacy id. */
    public static boolean isFence(int id) {
        return id >= 0 && (F[id] & FEN) != 0;
    }

    /** Whether the fence's material is wood (85 + the 1.8.9 wood fences; 113 nether brick isn't). */
    public static boolean isWoodFence(int id) {
        return id >= 0 && (F[id] & FENCE_WOOD) != 0;
    }

    /** 1.8.9 `block instanceof BlockFenceGate` for a legacy id. */
    public static boolean isGate(int id) {
        return id >= 0 && (F[id] & GAT) != 0;
    }

    /** 1.8.9 `block instanceof BlockPane` for a legacy id (iron bars + glass panes). */
    public static boolean isPane(int id) {
        return id >= 0 && (F[id] & PAN) != 0;
    }

    /** Material.gourd: pumpkin / lit_pumpkin / melon — fences and walls don't connect to them. */
    public static boolean isGourd(int id) {
        return id >= 0 && (F[id] & GOURD) != 0;
    }

    /** 1.8.9 Block.isFullCube for a legacy id. */
    public static boolean isFullCube(int id) {
        return id >= 0 && (F[id] & FULLC) != 0;
    }

    // BlockFence.canConnectTo
    public static boolean fenceConnect(BlockGetter world, BlockPos pos, boolean selfWood) {
        BlockState ns = world.getBlockState(pos);
        int nid = LegacyBlock.legacyId(ns);
        if (nid == 166) return false; // barrier
        if (nid >= 0) {
            int fl = F[nid];
            if ((fl & FEN) != 0) return ((fl & FENCE_WOOD) != 0) == selfWood;
            if ((fl & GAT) != 0) return true;
            return connectSolid(world, pos, ns);
        }
        Block b = ns.getBlock();
        return b instanceof FenceBlock || b instanceof FenceGateBlock || connectSolid(world, pos, ns);
    }

    private static void fence(BlockGetter world, BlockPos pos, BlockState state, int id, AABB mask, List<AABB> list) {
        boolean selfWood = (F[id] & FENCE_WOOD) != 0;
        boolean n = fenceConnect(world, pos.north(), selfWood);
        boolean s = fenceConnect(world, pos.south(), selfWood);
        boolean w = fenceConnect(world, pos.west(), selfWood);
        boolean e = fenceConnect(world, pos.east(), selfWood);
        float f = 0.375f, f1 = 0.625f, f2 = 0.375f, f3 = 0.625f;
        if (n) f2 = 0;
        if (s) f3 = 1;
        if (n || s) add(pos, mask, list, f, 0, f2, f1, 1.5, f3);
        f2 = 0.375f;
        f3 = 0.625f;
        if (w) f = 0;
        if (e) f1 = 1;
        if (w || e || (!n && !s)) add(pos, mask, list, f, 0, f2, f1, 1.5, f3);
    }

    // BlockPane.canPaneConnectToBlock: isFullBlock || same pane family || glass || stained glass
    public static boolean paneConnect(BlockGetter world, BlockPos pos) {
        BlockState ns = world.getBlockState(pos);
        int nid = LegacyBlock.legacyId(ns);
        if (nid >= 0) return (F[nid] & (OPAQUE | PAN)) != 0 || nid == 20 || nid == 95;
        Block b = ns.getBlock();
        return ns.canOcclude() || b instanceof IronBarsBlock || b instanceof StainedGlassBlock
                || b instanceof TintedGlassBlock;
    }

    private static void pane(BlockGetter world, BlockPos pos, AABB mask, List<AABB> list) {
        boolean n = paneConnect(world, pos.north());
        boolean s = paneConnect(world, pos.south());
        boolean w = paneConnect(world, pos.west());
        boolean e = paneConnect(world, pos.east());
        if ((!w || !e) && (w || e || n || s)) {
            if (w) add(pos, mask, list, 0, 0, 0.4375, 0.5, 1, 0.5625);
            else if (e) add(pos, mask, list, 0.5, 0, 0.4375, 1, 1, 0.5625);
        } else {
            add(pos, mask, list, 0, 0, 0.4375, 1, 1, 0.5625);
        }
        if ((!n || !s) && (w || e || n || s)) {
            if (n) add(pos, mask, list, 0.4375, 0, 0, 0.5625, 1, 0.5);
            else if (s) add(pos, mask, list, 0.4375, 0, 0.5, 0.5625, 1, 1);
        } else {
            add(pos, mask, list, 0.4375, 0, 0, 0.5625, 1, 1);
        }
    }

    // BlockWall.canConnectTo
    public static boolean wallConnect(BlockGetter world, BlockPos pos) {
        BlockState ns = world.getBlockState(pos);
        int nid = LegacyBlock.legacyId(ns);
        if (nid == 166) return false; // barrier
        if (nid >= 0) return nid == 139 || (F[nid] & GAT) != 0 || connectSolid(world, pos, ns);
        return ns.getBlock() instanceof FenceGateBlock || connectSolid(world, pos, ns);
    }

    private static void wall(BlockGetter world, BlockPos pos, AABB mask, List<AABB> list) {
        boolean n = wallConnect(world, pos.north());
        boolean s = wallConnect(world, pos.south());
        boolean w = wallConnect(world, pos.west());
        boolean e = wallConnect(world, pos.east());
        float f = 0.25f, f1 = 0.75f, f2 = 0.25f, f3 = 0.75f, f4 = 1f;
        if (n) f2 = 0;
        if (s) f3 = 1;
        if (w) f = 0;
        if (e) f1 = 1;
        if (n && s && !w && !e) {
            f4 = 0.8125f;
            f = 0.3125f;
            f1 = 0.6875f;
        } else if (!n && !s && w && e) {
            f4 = 0.8125f;
            f2 = 0.3125f;
            f3 = 0.6875f;
        }
        // getCollisionBoundingBox forces maxY = 1.5
        add(pos, mask, list, f, 0, f2, f1, 1.5, f3);
    }

    private static void chest(BlockGetter world, BlockPos pos, int id, AABB mask, List<AABB> list) {
        if (idAt(world, pos.north()) == id) add(pos, mask, list, 0.0625, 0, 0, 0.9375, 0.875, 0.9375);
        else if (idAt(world, pos.south()) == id) add(pos, mask, list, 0.0625, 0, 0.0625, 0.9375, 0.875, 1);
        else if (idAt(world, pos.west()) == id) add(pos, mask, list, 0, 0, 0.0625, 0.9375, 0.875, 0.9375);
        else if (idAt(world, pos.east()) == id) add(pos, mask, list, 0.0625, 0, 0.0625, 1, 0.875, 0.9375);
        else add(pos, mask, list, 0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375);
    }

    private static void ladder(BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        switch (state.getValue(LadderBlock.FACING)) {
            case NORTH:
                add(pos, mask, list, 0, 0, 0.875, 1, 1, 1);
                break;
            case SOUTH:
                add(pos, mask, list, 0, 0, 0, 1, 1, 0.125);
                break;
            case WEST:
                add(pos, mask, list, 0.875, 0, 0, 1, 1, 1);
                break;
            default:
                add(pos, mask, list, 0, 0, 0, 0.125, 1, 1);
        }
    }

    private static void trapdoor(BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        if (!state.getValue(TrapDoorBlock.OPEN)) {
            if (state.getValue(TrapDoorBlock.HALF) == Half.TOP) add(pos, mask, list, 0, 0.8125, 0, 1, 1, 1);
            else add(pos, mask, list, 0, 0, 0, 1, 0.1875, 1);
            return;
        }
        switch (state.getValue(TrapDoorBlock.FACING)) {
            case NORTH:
                add(pos, mask, list, 0, 0, 0.8125, 1, 1, 1);
                break;
            case SOUTH:
                add(pos, mask, list, 0, 0, 0, 1, 1, 0.1875);
                break;
            case WEST:
                add(pos, mask, list, 0.8125, 0, 0, 1, 1, 1);
                break;
            default:
                add(pos, mask, list, 0, 0, 0, 0.1875, 1, 1);
        }
    }

    private static void skull(BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        if (state.getBlock() instanceof WallSkullBlock) {
            switch (state.getValue(WallSkullBlock.FACING)) {
                case NORTH:
                    add(pos, mask, list, 0.25, 0.25, 0.5, 0.75, 0.75, 1);
                    break;
                case SOUTH:
                    add(pos, mask, list, 0.25, 0.25, 0, 0.75, 0.75, 0.5);
                    break;
                case WEST:
                    add(pos, mask, list, 0.5, 0.25, 0.25, 1, 0.75, 0.75);
                    break;
                default:
                    add(pos, mask, list, 0, 0.25, 0.25, 0.5, 0.75, 0.75);
            }
            return;
        }
        add(pos, mask, list, 0.25, 0, 0.25, 0.75, 0.5, 0.75);
    }

    private static void cocoa(BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        int age = state.getValue(CocoaBlock.AGE);
        float j = 4 + age * 2, k = 5 + age * 2;
        float f = j / 2f;
        switch (state.getValue(CocoaBlock.FACING)) {
            case SOUTH:
                add(pos, mask, list, (8 - f) / 16, (12 - k) / 16, (15 - j) / 16, (8 + f) / 16, 0.75, 0.9375);
                break;
            case NORTH:
                add(pos, mask, list, (8 - f) / 16, (12 - k) / 16, 0.0625, (8 + f) / 16, 0.75, (1 + j) / 16);
                break;
            case WEST:
                add(pos, mask, list, 0.0625, (12 - k) / 16, (8 - f) / 16, (1 + j) / 16, 0.75, (8 + f) / 16);
                break;
            default:
                add(pos, mask, list, (15 - j) / 16, (12 - k) / 16, (8 - f) / 16, 0.9375, 0.75, (8 + f) / 16);
        }
    }

    private static void pistonHead(BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        Direction d = state.getValue(PistonHeadBlock.FACING);
        switch (d) {
            case DOWN:
                add(pos, mask, list, 0, 0, 0, 1, 0.25, 1);
                break;
            case UP:
                add(pos, mask, list, 0, 0.75, 0, 1, 1, 1);
                break;
            case NORTH:
                add(pos, mask, list, 0, 0, 0, 1, 1, 0.25);
                break;
            case SOUTH:
                add(pos, mask, list, 0, 0, 0.75, 1, 1, 1);
                break;
            case WEST:
                add(pos, mask, list, 0, 0, 0, 0.25, 1, 1);
                break;
            default:
                add(pos, mask, list, 0.75, 0, 0, 1, 1, 1);
        }
        switch (d) {
            case DOWN:
                add(pos, mask, list, 0.375, 0.25, 0.375, 0.625, 1, 0.625);
                break;
            case UP:
                add(pos, mask, list, 0.375, 0, 0.375, 0.625, 0.75, 0.625);
                break;
            case NORTH:
                add(pos, mask, list, 0.25, 0.375, 0.25, 0.75, 0.625, 1);
                break;
            case SOUTH:
                add(pos, mask, list, 0.25, 0.375, 0, 0.75, 0.625, 0.75);
                break;
            case WEST:
                add(pos, mask, list, 0.375, 0.25, 0.25, 0.625, 0.75, 1);
                break;
            default:
                add(pos, mask, list, 0, 0.375, 0.25, 0.75, 0.625, 0.75);
        }
    }

    // BlockDoor.combineMetadata + setBoundBasedOnMeta
    private static void door(BlockGetter world, BlockPos pos, AABB mask, List<AABB> list) {
        int i = LegacyBlock.meta(world.getBlockState(pos));
        boolean top = (i & 8) != 0;
        int j = LegacyBlock.meta(world.getBlockState(pos.below()));
        int l = LegacyBlock.meta(world.getBlockState(pos.above()));
        int k = top ? j : i;
        int i1 = top ? i : l;
        int combined = (k & 7) | (top ? 8 : 0) | ((i1 & 1) != 0 ? 16 : 0) | ((i1 & 2) != 0 ? 32 : 0);

        float f = 0.1875f;
        Direction facing = Direction.from2DDataValue(combined & 3).getCounterClockWise();
        boolean open = (combined & 4) != 0;
        boolean hingeLeft = (combined & 16) != 0;
        if (open) {
            switch (facing) {
                case EAST:
                    if (!hingeLeft) add(pos, mask, list, 0, 0, 0, 1, 1, f);
                    else add(pos, mask, list, 0, 0, 1 - f, 1, 1, 1);
                    break;
                case SOUTH:
                    if (!hingeLeft) add(pos, mask, list, 1 - f, 0, 0, 1, 1, 1);
                    else add(pos, mask, list, 0, 0, 0, f, 1, 1);
                    break;
                case WEST:
                    if (!hingeLeft) add(pos, mask, list, 0, 0, 1 - f, 1, 1, 1);
                    else add(pos, mask, list, 0, 0, 0, 1, 1, f);
                    break;
                default:
                    if (!hingeLeft) add(pos, mask, list, 0, 0, 0, f, 1, 1);
                    else add(pos, mask, list, 1 - f, 0, 0, 1, 1, 1);
            }
            return;
        }
        switch (facing) {
            case EAST:
                add(pos, mask, list, 0, 0, 0, f, 1, 1);
                break;
            case SOUTH:
                add(pos, mask, list, 0, 0, 0, 1, 1, f);
                break;
            case WEST:
                add(pos, mask, list, 1 - f, 0, 0, 1, 1, 1);
                break;
            default:
                add(pos, mask, list, 0, 0, 1 - f, 1, 1, 1);
        }
    }

    // BlockStairs: half slab + facing side slab + inner corner
    private static boolean isStairs(BlockState s) {
        return s.getBlock() instanceof StairBlock;
    }

    private static boolean sameStair(BlockGetter world, BlockPos pos, BlockState state) {
        BlockState s = world.getBlockState(pos);
        return isStairs(s) && s.getValue(StairBlock.HALF) == state.getValue(StairBlock.HALF)
                && s.getValue(StairBlock.FACING) == state.getValue(StairBlock.FACING);
    }

    private static void stairs(BlockGetter world, BlockPos pos, BlockState state, AABB mask, List<AABB> list) {
        Direction facing = state.getValue(StairBlock.FACING);
        boolean top = state.getValue(StairBlock.HALF) == Half.TOP;
        // setBaseCollisionBounds
        if (top) add(pos, mask, list, 0, 0.5, 0, 1, 1, 1);
        else add(pos, mask, list, 0, 0, 0, 1, 0.5, 1);
        // func_176306_h
        float f = top ? 0 : 0.5f, f1 = top ? 0.5f : 1f;
        float f2 = 0, f3 = 1, f4 = 0, f5 = 0.5f;
        boolean flag = true;
        if (facing == Direction.EAST) {
            f2 = 0.5f;
            f5 = 1;
            BlockState ns = world.getBlockState(pos.east());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.NORTH && !sameStair(world, pos.south(), state)) {
                    f5 = 0.5f;
                    flag = false;
                } else if (nf == Direction.SOUTH && !sameStair(world, pos.north(), state)) {
                    f4 = 0.5f;
                    flag = false;
                }
            }
        } else if (facing == Direction.WEST) {
            f3 = 0.5f;
            f5 = 1;
            BlockState ns = world.getBlockState(pos.west());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.NORTH && !sameStair(world, pos.south(), state)) {
                    f5 = 0.5f;
                    flag = false;
                } else if (nf == Direction.SOUTH && !sameStair(world, pos.north(), state)) {
                    f4 = 0.5f;
                    flag = false;
                }
            }
        } else if (facing == Direction.SOUTH) {
            f4 = 0.5f;
            f5 = 1;
            BlockState ns = world.getBlockState(pos.south());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.WEST && !sameStair(world, pos.east(), state)) {
                    f3 = 0.5f;
                    flag = false;
                } else if (nf == Direction.EAST && !sameStair(world, pos.west(), state)) {
                    f2 = 0.5f;
                    flag = false;
                }
            }
        } else if (facing == Direction.NORTH) {
            BlockState ns = world.getBlockState(pos.north());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.WEST && !sameStair(world, pos.east(), state)) {
                    f3 = 0.5f;
                    flag = false;
                } else if (nf == Direction.EAST && !sameStair(world, pos.west(), state)) {
                    f2 = 0.5f;
                    flag = false;
                }
            }
        }
        add(pos, mask, list, f2, f, f4, f3, f1, f5);
        if (!flag) return;

        // func_176304_i: inner corner
        f2 = 0;
        f3 = 0.5f;
        f4 = 0.5f;
        f5 = 1;
        boolean corner = false;
        if (facing == Direction.EAST) {
            BlockState ns = world.getBlockState(pos.west());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.NORTH && !sameStair(world, pos.north(), state)) {
                    f4 = 0;
                    f5 = 0.5f;
                    corner = true;
                } else if (nf == Direction.SOUTH && !sameStair(world, pos.south(), state)) {
                    f4 = 0.5f;
                    f5 = 1;
                    corner = true;
                }
            }
        } else if (facing == Direction.WEST) {
            BlockState ns = world.getBlockState(pos.east());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                f2 = 0.5f;
                f3 = 1;
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.NORTH && !sameStair(world, pos.north(), state)) {
                    f4 = 0;
                    f5 = 0.5f;
                    corner = true;
                } else if (nf == Direction.SOUTH && !sameStair(world, pos.south(), state)) {
                    f4 = 0.5f;
                    f5 = 1;
                    corner = true;
                }
            }
        } else if (facing == Direction.SOUTH) {
            BlockState ns = world.getBlockState(pos.north());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                f4 = 0;
                f5 = 0.5f;
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.WEST && !sameStair(world, pos.west(), state)) {
                    corner = true;
                } else if (nf == Direction.EAST && !sameStair(world, pos.east(), state)) {
                    f2 = 0.5f;
                    f3 = 1;
                    corner = true;
                }
            }
        } else if (facing == Direction.NORTH) {
            BlockState ns = world.getBlockState(pos.south());
            if (isStairs(ns) && top == (ns.getValue(StairBlock.HALF) == Half.TOP)) {
                Direction nf = ns.getValue(StairBlock.FACING);
                if (nf == Direction.WEST && !sameStair(world, pos.west(), state)) {
                    corner = true;
                } else if (nf == Direction.EAST && !sameStair(world, pos.east(), state)) {
                    f2 = 0.5f;
                    f3 = 1;
                    corner = true;
                }
            }
        }
        if (corner) add(pos, mask, list, f2, f, f4, f3, f1, f5);
    }

    /**
     * 1.8.9's ItemPickaxe.canHarvestBlock (the pickaxe-effectiveness check the old
     * insta-break map ran): true for rock/iron/anvil-material blocks, snow, and ice.
     * Falls back to the modern mineable tag for blocks 1.8.9 didn't have.
     */
    public static boolean isPickaxeEffective(BlockState state) {
        int id = LegacyBlock.legacyId(state);
        if (id >= 0) return (F[id] & PICK) != 0;
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
    }

    /** 1.8.9's Block.isToolEffective("shovel"): the ground-material blocks. */
    public static boolean isShovelEffective(BlockState state) {
        int id = LegacyBlock.legacyId(state);
        if (id >= 0) return (F[id] & SHOVEL) != 0;
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL);
    }

    /** 1.8.9's Block.isToolEffective("axe"): the wood/gourd-material blocks. */
    public static boolean isAxeEffective(BlockState state) {
        int id = LegacyBlock.legacyId(state);
        if (id >= 0) return (F[id] & AXE) != 0;
        return state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
    }

    /** 1.8.9's block.canCollideCheck(state, true): everything but air and fire, plus liquid sources. */
    public static boolean canCollideCheck(BlockState state) {
        int id = LegacyBlock.legacyId(state);
        if (id == 0) return false;
        if (id == 51) return false; // fire isCollidable() = false
        if (id >= 8 && id <= 11) return state.hasProperty(LiquidBlock.LEVEL) && state.getValue(LiquidBlock.LEVEL) == 0;
        if (id > 0) return true;
        if (!state.getFluidState().isEmpty()) return state.getFluidState().isSource();
        return !state.isAir();
    }

    /** 1.8.9's `block instanceof BlockStairs` for the stair-floor logic. */
    public static boolean isStairBlock(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    /** 1.8.9's head-level stonk exemption: not iron_bars, not a fence, not a skull. */
    public static boolean isIronBarsFenceOrSkull(BlockState state) {
        int id = LegacyBlock.legacyId(state);
        if (id >= 0) return id == 101 || id == 144 || (F[id] & FEN) != 0;
        Block b = state.getBlock();
        return b instanceof FenceBlock || b instanceof AbstractSkullBlock;
    }
}

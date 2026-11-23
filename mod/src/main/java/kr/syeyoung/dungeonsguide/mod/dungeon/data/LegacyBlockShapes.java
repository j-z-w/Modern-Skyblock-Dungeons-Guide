/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026  cyoung06 (syeyoung)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.dungeon.data;

import kr.syeyoung.dungeonsguide.mod.dungeon.world.DGWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1.8.9 block geometry, keyed by the legacy numeric block id, for {@link kr.syeyoung.dungeonsguide.mod.dungeon.actions.RaytraceHelper}.
 *
 * <p>On 1.8.9 {@code World.rayTraceBlocks} skipped blocks whose {@code canCollideCheck(state, false)}
 * was false (air / liquids / fire) and clipped the ray against the AABB left by
 * {@code setBlockBoundsBasedOnState} (single box for almost every block; stairs used 6 octant passes
 * whose union equals the modern stair shape). {@code addCollisionBoxesToList} appended the
 * collision AABBs used to test whether a player can stand inside a volume.
 *
 * <p>Those boxes differ from modern {@code getShape} / {@code getCollisionShape} VoxelShapes for
 * fences, panes, walls, fence gates, doors, trapdoors, double chests, cauldrons, hoppers and others:
 * 1.8.9 merged whole runs into one fat AABB or ignored open states, so rays that slip through a
 * modern outline gap were blocked on 1.8.9. Hypixel rooms follow the 1.8.9 behaviour, so clicking
 * spots must too.
 */
public final class LegacyBlockShapes {
    private LegacyBlockShapes() {}

    private static final AABB FULL = new AABB(0, 0, 0, 1, 1, 1);
    private static final List<AABB> FULL_LIST = Collections.singletonList(FULL);
    private static final List<AABB> EMPTY = Collections.emptyList();

    // 1.8.9 canCollideCheck(state, hitIfLiquid=false): air, liquids and fire are skipped
    public static boolean canCollideCheck(int legacyId) {
        switch (legacyId) {
            case 0: case 8: case 9: case 10: case 11: case 51:
                return false;
            default:
                return true;
        }
    }

    // 1.8.9 getCollisionBoundingBox() == null blocks: no entity collision (canStand ignores them)
    private static boolean noCollision(int id) {
        switch (id) {
            case 0: // air
            case 6: // sapling
            case 8: case 9: case 10: case 11: // liquids
            case 27: case 28: case 66: case 157: // rails
            case 30: // web
            case 31: case 32: // tallgrass, deadbush
            case 36: // piston moving
            case 37: case 38: case 39: case 40: // flowers, mushrooms
            case 50: case 75: case 76: // torches
            case 51: // fire
            case 55: // redstone wire
            case 59: case 104: case 105: // crops, stems
            case 63: case 68: // signs
            case 69: // lever
            case 70: case 72: case 147: case 148: // pressure plates
            case 77: case 143: // buttons
            case 83: // sugar cane
            case 90: // nether portal
            case 106: // vine
            case 115: // nether wart
            case 119: // end portal
            case 131: case 132: // tripwire hook + wire
            case 175: // double plant
            case 176: case 177: // banners
                return true;
            default:
                return false;
        }
    }

    // 1.8.9 isOpaqueCube() && isFullCube() — used by fence/pane/wall connection predicates
    private static boolean opaqueFullCube(int id) {
        switch (id) {
            case 0: case 6: case 8: case 9: case 10: case 11:
            case 18: case 161: // leaves
            case 20: // glass (95 stained glass is in the list below)
            case 26: // bed
            case 27: case 28: case 66: case 157: // rails
            case 29: case 33: case 34: case 36: // pistons
            case 30: case 31: case 32: // web, grasses
            case 37: case 38: case 39: case 40:
            case 44: case 126: case 182: // single slabs
            case 50: case 75: case 76: case 51: case 52:
            case 53: case 67: case 108: case 109: case 114: case 128:
            case 134: case 135: case 136: case 156: case 163: case 164: case 180: // stairs
            case 54: case 55:
            case 59: case 60: // crops, farmland
            case 63: case 64: case 65: case 68: case 69: case 70: case 71: case 72:
            case 77: case 78:
            case 79: // ice
            case 81: case 83:
            case 85: case 113: case 188: case 189: case 190: case 191: case 192: // fences
            case 89: // glowstone
            case 90: case 92: case 93: case 94: case 95: case 96:
            case 101: case 102: // bars/pane
            case 104: case 105: case 106: case 107:
            case 111: case 115: case 116: case 117: case 118: case 119: case 120: case 122:
            case 127: case 130: case 131: case 132:
            case 138: case 139: case 140: case 143: case 144: case 145: case 146:
            case 147: case 148: case 149: case 150: case 151: case 154:
            case 160: case 165: case 166: case 167: case 169: case 171:
            case 175: case 176: case 177: case 178:
            case 183: case 184: case 185: case 186: case 187: // fence gates
            case 193: case 194: case 195: case 196: case 197: // wood doors
                return false;
            default:
                return id > 0;
        }
    }

    private static boolean isFence(int id) { // wooden fences share blockMaterial.wood; 113 is rock
        return id == 85 || id == 113 || (id >= 188 && id <= 192);
    }
    private static boolean isFenceGate(int id) {
        return id == 107 || (id >= 183 && id <= 187);
    }
    private static boolean isPane(int id) {
        return id == 101 || id == 102 || id == 160;
    }
    private static boolean isGourd(int id) { // Material.gourd: pumpkin/lit_pumpkin/melon
        return id == 86 || id == 91 || id == 103;
    }

    // BlockFence.canConnectTo
    private static boolean fenceConnects(int selfId, int otherId) {
        if (otherId == 166) return false; // barrier
        boolean otherFence = isFence(otherId);
        if (otherFence && (otherId == 113) == (selfId == 113)) return true; // same material
        if (!otherFence && !isFenceGate(otherId)) {
            return opaqueFullCube(otherId) && !isGourd(otherId);
        }
        return otherFence || isFenceGate(otherId);
    }

    // BlockPane.canPaneConnectToBlock
    private static boolean paneConnects(int selfId, int otherId) {
        return opaqueFullCube(otherId) || otherId == selfId || otherId == 20 || otherId == 95
                || isPane(otherId);
    }

    // BlockWall.canConnectTo
    private static boolean wallConnects(int selfId, int otherId) {
        if (otherId == 166) return false; // barrier
        if (otherId == selfId || isFenceGate(otherId)) return true;
        return opaqueFullCube(otherId) && !isGourd(otherId);
    }

    private static int idAt(DGWorld w, BlockPos pos) {
        return LegacyBlock.legacyId(w.getBlockState(pos));
    }

    /**
     * The meta 1.8.9's getMetaFromState would return for this state. {@link LegacyBlock#meta} is
     * ambiguous: invalid metas flatten to the same modern state as valid ones (65@0 and 65@2 both
     * become ladder[facing=north]) and the table keeps the lowest. Directional blocks therefore
     * recompute meta from the state's properties here.
     */
    private static int meta(BlockState state, int id) {
        switch (id) {
            case 50: case 75: case 76: { // torch: wall plate by facing, 5 = floor
                Direction f = dirProp(state, "facing");
                return f == null ? 5 : h2(f); // e=1,w=2,s=3,n=4
            }
            case 65: case 68: case 177: // ladder / wall sign / wall banner: n=2,s=3,w=4,e=5
                return dirIndex(dirProp(state, "facing"));
            case 69: { // lever: wall e/w/s/n=1..4, floor z=5 x=6, ceiling x=0 z=7, +8 powered
                int m = orientationMeta(state);
                return m | (boolProp(state, "powered") ? 8 : 0);
            }
            case 77: case 143: { // buttons: wall e/w/s/n=1..4, floor=5, ceiling=0, +8 powered
                String face = strProp(state, "face");
                int m;
                if ("floor".equals(face)) m = 5;
                else if ("ceiling".equals(face)) m = 0;
                else m = h2(dirProp(state, "facing"));
                return m | (boolProp(state, "powered") ? 8 : 0);
            }
            case 96: case 167: { // trapdoor: n=0,s=1,w=2,e=3 +open 4 +top 8
                int m = trapdoorFacing(dirProp(state, "facing"));
                if (boolProp(state, "open")) m |= 4;
                if ("top".equals(strProp(state, "half"))) m |= 8;
                return m;
            }
            case 107: case 183: case 184: case 185: case 186: case 187: { // gate: s=0,w=1,n=2,e=3 +4 open +8 powered
                int m = hIndex(dirProp(state, "facing"));
                if (boolProp(state, "open")) m |= 4;
                if (boolProp(state, "powered")) m |= 8;
                return m;
            }
            case 131: { // tripwire hook: hIndex facing +4 attached +8 powered
                int m = hIndex(dirProp(state, "facing"));
                if (boolProp(state, "attached")) m |= 4;
                if (boolProp(state, "powered")) m |= 8;
                return m;
            }
            case 127: { // cocoa: hIndex facing + age<<2
                Object age = propValue(state, "age");
                int a = age instanceof Integer ? (Integer) age : 0;
                return hIndex(dirProp(state, "facing")) | (a << 2);
            }
            case 144: { // skull: facing.getIndex; floor skull has facing=up -> 1
                Direction f = dirProp(state, "facing");
                return f == null ? 1 : f.ordinal(); // Direction ordinal == 1.8.9 getIndex
            }
            case 145: { // anvil: hIndex facing + damage<<2
                String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                int dmg = "chipped_anvil".equals(name) ? 1 : "damaged_anvil".equals(name) ? 2 : 0;
                return hIndex(dirProp(state, "facing")) | (dmg << 2);
            }
            case 29: case 33: { // piston: facing.getIndex + extended 8
                Direction f = dirProp(state, "facing");
                int m = f == null ? 0 : f.ordinal();
                return m | (boolProp(state, "extended") ? 8 : 0);
            }
            case 120: { // end portal frame: hIndex facing + eye 4
                return hIndex(dirProp(state, "facing")) | (boolProp(state, "eye") ? 4 : 0);
            }
            case 26: { // bed: hIndex facing + occupied 4 + head 8
                int m = hIndex(dirProp(state, "facing"));
                boolean head = "head".equals(strProp(state, "part"));
                if (head) { m |= 8; if (boolProp(state, "occupied")) m |= 4; }
                return m;
            }
            case 90: // nether portal: axis x=1, z=2
                return "x".equals(strProp(state, "axis")) ? 1 : "z".equals(strProp(state, "axis")) ? 2 : 0;
            case 78: { // snow: layers-1
                Object layers = propValue(state, "layers");
                return layers instanceof Integer ? (Integer) layers - 1 : LegacyBlock.meta(state);
            }
            case 70: case 72: case 147: case 148: // pressure plates: powered bit
                return boolProp(state, "powered") ? 1 : 0;
            case 54: case 130: case 146: case 62: case 61: case 23: case 158: // chest-ish: facing.getIndex
                return dirIndex(dirProp(state, "facing"));
            case 154: // hopper: facing.getIndex (down or horizontal)
                return dirIndex(dirProp(state, "facing"));
            case 86: case 91: // pumpkin: hIndex
                return hIndex(dirProp(state, "facing"));
            default:
                return LegacyBlock.meta(state);
        }
    }

    private static Object propValue(BlockState state, String name) {
        net.minecraft.world.level.block.state.properties.Property<?> p =
                state.getBlock().getStateDefinition().getProperty(name);
        return p == null ? null : state.getValue(p);
    }

    private static Direction dirProp(BlockState state, String name) {
        Object v = propValue(state, name);
        return v instanceof Direction ? (Direction) v : null;
    }

    private static String strProp(BlockState state, String name) {
        Object v = propValue(state, name);
        if (v instanceof net.minecraft.util.StringRepresentable)
            return ((net.minecraft.util.StringRepresentable) v).getSerializedName();
        return v == null ? null : v.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean boolProp(BlockState state, String name) {
        Object v = propValue(state, name);
        return Boolean.TRUE.equals(v);
    }

    private static int dirIndex(Direction f) { // 1.8.9 facing.getIndex(): d,u,n,s,w,e
        return f == null ? 2 : f.ordinal();
    }

    private static int hIndex(Direction f) { // 1.8.9 getHorizontalIndex: s=0,w=1,n=2,e=3
        if (f == null) return 0;
        switch (f) {
            case SOUTH: return 0;
            case WEST: return 1;
            case NORTH: return 2;
            default: return 3;
        }
    }

    private static int h2(Direction f) { // wall-facing meta: e=1,w=2,s=3,n=4
        if (f == null) return 5;
        switch (f) {
            case EAST: return 1;
            case WEST: return 2;
            case SOUTH: return 3;
            case NORTH: return 4;
            default: return 5;
        }
    }

    private static int trapdoorFacing(Direction f) { // n=0,s=1,w=2,e=3
        if (f == null) return 0;
        switch (f) {
            case NORTH: return 0;
            case SOUTH: return 1;
            case WEST: return 2;
            default: return 3;
        }
    }

    private static int doorFacing(Direction f) { // lower-half meta: e=0,s=1,w=2,n=3 (rotateY + hIndex)
        if (f == null) return 0;
        switch (f) {
            case EAST: return 0;
            case SOUTH: return 1;
            case WEST: return 2;
            default: return 3;
        }
    }

    private static int orientationMeta(BlockState state) { // BlockLever.EnumOrientation meta 0-7
        String face = strProp(state, "face");
        Direction f = dirProp(state, "facing");
        if ("wall".equals(face)) return h2(f);
        boolean xAxis = f != null && f.getAxis() == Direction.Axis.X;
        if ("floor".equals(face)) return xAxis ? 6 : 5;  // up_x=6, up_z=5
        return xAxis ? 0 : 7;                            // down_x=0, down_z=7
    }

    private static Direction horizontal(int index) { // EnumFacing.getHorizontal: 0=S,1=W,2=N,3=E
        switch (index & 3) {
            case 0: return Direction.SOUTH;
            case 1: return Direction.WEST;
            case 2: return Direction.NORTH;
            default: return Direction.EAST;
        }
    }

    private static AABB box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    /**
     * Block-local AABBs the 1.8.9 ray is clipped against (post-setBlockBoundsBasedOnState bounds).
     * Empty list = canCollideCheck false = the ray passes through.
     */
    public static List<AABB> raytraceBoxes(DGWorld w, BlockPos pos) {
        BlockState state = w.getBlockState(pos);
        int legacy = LegacyBlock.legacy(state);
        if (!canCollideCheck(legacy >> 4)) return EMPTY;
        if (legacy == -1) { // block 1.8.9 didn't have: no legacy semantics, keep the modern outline
            return state.getShape(w.asVanilla(), pos).toAabbs();
        }
        int id = legacy >> 4;
        int meta = meta(state, id);

        switch (id) {
            // stairs raytrace 6 of 8 octants picked by facing+half only (BlockStairs.collisionRayTrace)
            case 53: case 67: case 108: case 109: case 114: case 128: case 134: case 135:
            case 136: case 156: case 163: case 164: case 180:
                return stairRaytraceBoxes(meta);

            case 6: return single(box(0.1, 0, 0.1, 0.9, 0.8, 0.9)); // sapling
            case 26: return single(box(0, 0, 0, 1, 0.5625, 1)); // bed
            case 27: case 28: case 66: case 157: // rails
                return single(box(0, 0, 0, 1, (meta & 7) >= 2 && (meta & 7) <= 5 ? 0.625 : 0.125, 1));
            case 30: return FULL_LIST; // web
            case 31: case 32: return single(box(0.1, 0, 0.1, 0.9, 0.8, 0.9)); // tallgrass, deadbush
            case 37: case 38: return single(box(0.3, 0, 0.3, 0.7, 0.6, 0.7)); // flowers
            case 39: case 40: return single(box(0.3, 0, 0.3, 0.7, 0.4, 0.7)); // mushrooms
            case 44: case 126: case 182: // single slabs
                return single((meta & 8) != 0 ? box(0, 0.5, 0, 1, 1, 1) : box(0, 0, 0, 1, 0.5, 1));
            case 50: case 75: case 76: // torches (facing from meta: 1=E,2=W,3=S,4=N,5=up)
                return single(torchBox(meta));
            case 54: case 146: // chest / trapped chest: extends to the connected edge
                return single(chestBox(w, pos, id));
            case 55: return single(box(0, 0, 0, 1, 0.0625, 1)); // redstone wire
            case 59: return single(box(0, 0, 0, 1, 0.25, 1)); // crops
            case 63: return single(box(0.25, 0, 0.25, 0.75, 1, 0.75)); // standing sign
            case 64: case 71: case 193: case 194: case 195: case 196: case 197: // doors
                return single(doorBox(w, pos));
            case 65: return single(ladderBox(meta)); // ladder
            case 68: return single(wallSignBox(meta)); // wall sign
            case 69: return single(leverBox(meta)); // lever
            case 70: case 72: case 147: case 148: // pressure plates
                return single(box(0.0625, 0, 0.0625, 0.9375, (meta & 1) != 0 ? 0.03125 : 0.0625, 0.9375));
            case 77: case 143: // buttons
                return single(buttonBox(meta));
            case 78: return single(box(0, 0, 0, 1, (meta + 1) / 8.0, 1)); // snow layer
            case 81: return single(box(0.0625, 0, 0.0625, 0.9375, 1, 0.9375)); // cactus selection
            case 83: return single(box(0.125, 0, 0.125, 0.875, 1, 0.875)); // sugar cane
            case 85: case 113: case 188: case 189: case 190: case 191: case 192: // fences
                return single(fenceLikeBox(w, pos, id, 0.375, 0.625, 1.0));
            case 90: return single(portalBox(meta)); // nether portal
            case 92: return single(box((1 + meta * 2) / 16.0, 0, 0.0625, 0.9375, 0.5, 0.9375)); // cake
            case 93: case 94: case 149: case 150: // repeaters / comparators
                return single(box(0, 0, 0, 1, 0.125, 1));
            case 96: case 167: return single(trapdoorBox(meta)); // trapdoors
            case 101: case 102: case 160: // iron bars + panes
                return single(fenceLikeBox(w, pos, id, 0.4375, 0.5625, 1.0));
            case 104: case 105: return single(box(0.375, 0, 0.375, 0.625, 0.25, 0.625)); // stems
            case 106: return single(vineBox(w, pos, meta));
            case 107: case 183: case 184: case 185: case 186: case 187: // fence gates: plate regardless of OPEN
                return single((horizontal(meta).getAxis() == Direction.Axis.Z)
                        ? box(0, 0, 0.375, 1, 1, 0.625) : box(0.375, 0, 0, 0.625, 1, 1));
            case 111: return single(box(0, 0, 0, 1, 0.015625, 1)); // lily pad
            case 115: return single(box(0, 0, 0, 1, 0.25, 1)); // nether wart
            case 116: return single(box(0, 0, 0, 1, 0.75, 1)); // enchantment table
            case 117: return single(box(0, 0, 0, 1, 0.125, 1)); // brewing stand (bounds = base plate)
            case 118: return FULL_LIST; // cauldron selection = full block
            case 119: return single(box(0, 0, 0, 1, 0.0625, 1)); // end portal
            case 120: return single(box(0, 0, 0, 1, 0.8125, 1)); // end portal frame
            case 122: return single(box(0.0625, 0, 0.0625, 0.9375, 1, 0.9375)); // dragon egg
            case 127: return single(cocoaBox(meta));
            case 130: return single(box(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375)); // ender chest
            case 131: return single(tripwireHookBox(meta)); // tripwire hook
            case 132: return single(box(0, 0, 0, 1, 0.15625, 1)); // tripwire
            case 139: return single(wallBox(w, pos)); // cobblestone wall
            case 140: return single(box(0.3125, 0, 0.3125, 0.6875, 0.375, 0.6875)); // flower pot
            case 144: return single(skullBox(meta)); // skull
            case 145: return single(horizontal(meta & 3).getAxis() == Direction.Axis.X
                    ? box(0, 0, 0.125, 1, 1, 0.875) : box(0.125, 0, 0, 0.875, 1, 1)); // anvil
            case 151: case 178: return single(box(0, 0, 0, 1, 0.375, 1)); // daylight detectors
            case 154: return FULL_LIST; // hopper: setBlockBoundsBasedOnState = full block
            case 171: return single(box(0, 0, 0, 1, 0.0625, 1)); // carpet
            case 175: return single(box(0.3, 0, 0.3, 0.7, 0.6, 0.7)); // double plant
            case 176: return single(box(0.25, 0, 0.25, 0.75, 1, 0.75)); // standing banner
            case 177: return single(wallBannerBox(meta)); // wall banner
            case 29: case 33: // pistons: 0.75 box when extended
                return single(pistonBox(meta));
            case 34: // piston head: head plate + rod; rare in rooms, modern shape is equivalent enough
                return state.getShape(w.asVanilla(), pos).toAabbs();
            default:
                return FULL_LIST;
        }
    }

    /**
     * 1.8.9 addCollisionBoxesToList equivalent: appends world-space AABBs intersecting {@code mask}.
     */
    public static void addCollisionBoxes(DGWorld w, BlockPos pos, AABB mask, List<AABB> out) {
        int id = LegacyBlock.legacyId(w.getBlockState(pos));
        if (id <= 0 || noCollision(id)) return;

        List<AABB> boxes = collisionBoxes(w, pos, id);
        for (AABB box : boxes) {
            AABB world = box.move(pos);
            if (mask.intersects(world)) out.add(world);
        }
    }

    /** Block-local collision AABBs (addCollisionBoxesToList / getCollisionBoundingBox). */
    private static List<AABB> collisionBoxes(DGWorld w, BlockPos pos, int id) {
        BlockState state = w.getBlockState(pos);
        int meta = meta(state, id);
        switch (id) {
            case 53: case 67: case 108: case 109: case 114: case 128: case 134: case 135:
            case 136: case 156: case 163: case 164: case 180: // stairs: neighbor-dependent quarters
                return stairCollisionBoxes(w, pos, meta);

            case 78: // snow layer: (layers-1)/8 high; 1 layer = nothing
                return meta == 0 ? EMPTY : single(box(0, 0, 0, 1, meta * 0.125, 1));
            case 81: return single(box(0.0625, 0, 0.0625, 0.9375, 0.9375, 0.9375)); // cactus
            case 85: case 113: case 188: case 189: case 190: case 191: case 192:
                return fenceCollision(w, pos, id);
            case 88: return single(box(0, 0, 0, 1, 0.875, 1)); // soul sand
            case 92: return single(box((1 + meta * 2) / 16.0, 0, 0.0625, 0.9375, 0.5, 0.9375));
            case 101: case 102: case 160: return paneCollision(w, pos, id);
            case 107: case 183: case 184: case 185: case 186: case 187: // fence gate: null when open
                if ((meta & 4) != 0) return EMPTY;
                return single((horizontal(meta).getAxis() == Direction.Axis.Z)
                        ? box(0, 0, 0.375, 1, 1.5, 0.625) : box(0.375, 0, 0, 0.625, 1.5, 1));
            case 117: // brewing stand: rod + base
                return java.util.Arrays.asList(
                        box(0.4375, 0, 0.4375, 0.5625, 0.875, 0.5625), box(0, 0, 0, 1, 0.125, 1));
            case 118: // cauldron: bottom + 4 walls
                return java.util.Arrays.asList(
                        box(0, 0, 0, 1, 0.3125, 1),
                        box(0, 0, 0, 0.125, 1, 1), box(0, 0, 0, 1, 1, 0.125),
                        box(0.875, 0, 0, 1, 1, 1), box(0, 0, 0.875, 1, 1, 1));
            case 120: // end portal frame: base + eye
                if ((meta & 4) != 0)
                    return java.util.Arrays.asList(box(0, 0, 0, 1, 0.8125, 1),
                            box(0.3125, 0.8125, 0.3125, 0.6875, 1, 0.6875));
                return single(box(0, 0, 0, 1, 0.8125, 1));
            case 139: // wall: selection bounds with maxY forced to 1.5
                AABB sel = wallBox(w, pos);
                return single(new AABB(sel.minX, sel.minY, sel.minZ, sel.maxX, 1.5, sel.maxZ));
            case 154: // hopper: base + 4 walls
                return java.util.Arrays.asList(
                        box(0, 0, 0, 1, 0.625, 1),
                        box(0, 0, 0, 0.125, 1, 1), box(0, 0, 0, 1, 1, 0.125),
                        box(0.875, 0, 0, 1, 1, 1), box(0, 0, 0.875, 1, 1, 1));
            default:
                return raytraceBoxes(w, pos);
        }
    }

    /** 1.8.9 getSelectedBoundingBox equivalent: enclosing box of the raytrace bounds. */
    public static AABB selectionBounds(DGWorld w, BlockPos pos) {
        List<AABB> boxes = raytraceBoxes(w, pos);
        if (boxes.isEmpty()) return FULL;
        AABB b = boxes.get(0);
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        for (int i = 1; i < boxes.size(); i++) {
            AABB a = boxes.get(i);
            x0 = Math.min(x0, a.minX); y0 = Math.min(y0, a.minY); z0 = Math.min(z0, a.minZ);
            x1 = Math.max(x1, a.maxX); y1 = Math.max(y1, a.maxY); z1 = Math.max(z1, a.maxZ);
        }
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    /**
     * 1.8.9 collisionRayTrace: whether the ray hits any of this block's bounds boxes. Unlike modern
     * {@code AABB.clip}, the 1.8.9 face-intersection check accepts a hit at t=0 — a ray that starts
     * exactly on a face and goes inward registers, which is how the stepping raytracer detects the
     * block it just entered. That detail changes clicking spots whenever the ray lands exactly on a
     * block boundary, so it is reproduced faithfully here.
     */
    public static boolean rayHits(DGWorld w, BlockPos pos, Vec3 from, Vec3 to) {
        List<AABB> boxes = raytraceBoxes(w, pos);
        if (boxes.isEmpty()) return false;
        Vec3 f = from.subtract(pos.getX(), pos.getY(), pos.getZ());
        Vec3 t = to.subtract(pos.getX(), pos.getY(), pos.getZ());
        for (AABB box : boxes) {
            if (collisionRayTrace(box, f, t) != null) return true;
        }
        return false;
    }

    /** Block.collisionRayTrace: nearest valid face intersection, or null. */
    private static Vec3 collisionRayTrace(AABB box, Vec3 from, Vec3 to) {
        Vec3 minX = intermediateWithX(from, to, box.minX);
        Vec3 maxX = intermediateWithX(from, to, box.maxX);
        Vec3 minY = intermediateWithY(from, to, box.minY);
        Vec3 maxY = intermediateWithY(from, to, box.maxY);
        Vec3 minZ = intermediateWithZ(from, to, box.minZ);
        Vec3 maxZ = intermediateWithZ(from, to, box.maxZ);

        if (!insideYZ(box, minX)) minX = null;
        if (!insideYZ(box, maxX)) maxX = null;
        if (!insideXZ(box, minY)) minY = null;
        if (!insideXZ(box, maxY)) maxY = null;
        if (!insideXY(box, minZ)) minZ = null;
        if (!insideXY(box, maxZ)) maxZ = null;

        Vec3 best = null;
        if (minX != null && (best == null || from.distanceToSqr(minX) < from.distanceToSqr(best))) best = minX;
        if (maxX != null && (best == null || from.distanceToSqr(maxX) < from.distanceToSqr(best))) best = maxX;
        if (minY != null && (best == null || from.distanceToSqr(minY) < from.distanceToSqr(best))) best = minY;
        if (maxY != null && (best == null || from.distanceToSqr(maxY) < from.distanceToSqr(best))) best = maxY;
        if (minZ != null && (best == null || from.distanceToSqr(minZ) < from.distanceToSqr(best))) best = minZ;
        if (maxZ != null && (best == null || from.distanceToSqr(maxZ) < from.distanceToSqr(best))) best = maxZ;
        return best;
    }

    private static Vec3 intermediateWithX(Vec3 a, Vec3 b, double x) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        if (dx * dx < 1.0E-7) return null;
        double t = (x - a.x) / dx;
        return t >= 0 && t <= 1 ? a.add(dx * t, dy * t, dz * t) : null;
    }

    private static Vec3 intermediateWithY(Vec3 a, Vec3 b, double y) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        if (dy * dy < 1.0E-7) return null;
        double t = (y - a.y) / dy;
        return t >= 0 && t <= 1 ? a.add(dx * t, dy * t, dz * t) : null;
    }

    private static Vec3 intermediateWithZ(Vec3 a, Vec3 b, double z) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        if (dz * dz < 1.0E-7) return null;
        double t = (z - a.z) / dz;
        return t >= 0 && t <= 1 ? a.add(dx * t, dy * t, dz * t) : null;
    }

    private static boolean insideYZ(AABB box, Vec3 v) {
        return v != null && v.y >= box.minY && v.y <= box.maxY && v.z >= box.minZ && v.z <= box.maxZ;
    }

    private static boolean insideXZ(AABB box, Vec3 v) {
        return v != null && v.x >= box.minX && v.x <= box.maxX && v.z >= box.minZ && v.z <= box.maxZ;
    }

    private static boolean insideXY(AABB box, Vec3 v) {
        return v != null && v.x >= box.minX && v.x <= box.maxX && v.y >= box.minY && v.y <= box.maxY;
    }

    private static List<AABB> single(AABB box) {
        return Collections.singletonList(box);
    }

    /* --- stairs ------------------------------------------------------------
     * 1.8.9 BlockStairs meta: bits 0-1 facing (0=EAST,1=WEST,2=SOUTH,3=NORTH), bit 2 = top half.
     * Both the raytrace octants and the collision boxes are computed from neighbors at runtime,
     * never from the palette `shape` property. */

    // octants NOT raytraced, indexed by [EnumFacing.getHorizontalIndex + (top ? 4 : 0)]
    private static final int[][] STAIR_RAY_SKIP = {{4, 5}, {5, 7}, {6, 7}, {4, 6}, {0, 1}, {1, 3}, {2, 3}, {0, 2}};
    // meta&3 facing -> 1.8.9 horizontalIndex (SOUTH=0, WEST=1, NORTH=2, EAST=3)
    private static int stairHIndex(int facing) {
        switch (facing & 3) {
            case 0: return 3; // EAST
            case 1: return 1; // WEST
            case 2: return 0; // SOUTH
            default: return 2; // NORTH
        }
    }

    private static boolean isStairs(int id) {
        switch (id) {
            case 53: case 67: case 108: case 109: case 114: case 128: case 134: case 135:
            case 136: case 156: case 163: case 164: case 180:
                return true;
            default:
                return false;
        }
    }

    /** meta&7 (facing|half) of the neighbor if it is a stair, else -1. */
    private static int stairMetaAt(DGWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        return isStairs(LegacyBlock.legacyId(s)) ? LegacyBlock.meta(s) & 7 : -1;
    }

    /** BlockStairs.isSameStair: stair with the same facing and half. */
    private static boolean sameStair(DGWorld w, BlockPos pos, int meta) {
        return stairMetaAt(w, pos) == (meta & 7);
    }

    private static boolean sameHalf(int neighborMeta, int meta) {
        return neighborMeta != -1 && (neighborMeta & 4) == (meta & 4);
    }

    /** The 6 octants BlockStairs.collisionRayTrace clips against. */
    private static List<AABB> stairRaytraceBoxes(int meta) {
        int[] skip = STAIR_RAY_SKIP[stairHIndex(meta) + ((meta & 4) != 0 ? 4 : 0)];
        List<AABB> out = new ArrayList<>(6);
        for (int j = 0; j < 8; j++) {
            if (j == skip[0] || j == skip[1]) continue;
            double x = 0.5 * (j & 1), y = 0.5 * ((j >> 2) & 1), z = 0.5 * ((j >> 1) & 1);
            out.add(box(x, y, z, x + 0.5, y + 0.5, z + 0.5));
        }
        return out;
    }

    /** BlockStairs.addCollisionBoxesToList: base half + facing quarter + optional inner-corner quarter. */
    private static List<AABB> stairCollisionBoxes(DGWorld w, BlockPos pos, int meta) {
        boolean top = (meta & 4) != 0;
        int facing = meta & 3;
        List<AABB> out = new ArrayList<>(3);
        out.add(top ? box(0, 0.5, 0, 1, 1, 1) : box(0, 0, 0, 1, 0.5, 1));

        // func_176306_h: facing quarter on the opposite half, possibly shrunk by a perpendicular
        // same-half stair on the facing side. Returns "straight" flag.
        double f = top ? 0.0 : 0.5, f1 = top ? 0.5 : 1.0;
        double f2 = 0, f3 = 1, f4 = 0, f5 = 0.5;
        boolean straight = true;
        int nb;
        switch (facing) {
            case 0: // EAST
                f2 = 0.5; f5 = 1;
                nb = stairMetaAt(w, pos.east());
                if (sameHalf(nb, meta)) {
                    if ((nb & 3) == 3 && !sameStair(w, pos.south(), meta)) { f5 = 0.5; straight = false; }
                    else if ((nb & 3) == 2 && !sameStair(w, pos.north(), meta)) { f4 = 0.5; straight = false; }
                }
                break;
            case 1: // WEST
                f3 = 0.5; f5 = 1;
                nb = stairMetaAt(w, pos.west());
                if (sameHalf(nb, meta)) {
                    if ((nb & 3) == 3 && !sameStair(w, pos.south(), meta)) { f5 = 0.5; straight = false; }
                    else if ((nb & 3) == 2 && !sameStair(w, pos.north(), meta)) { f4 = 0.5; straight = false; }
                }
                break;
            case 2: // SOUTH
                f4 = 0.5; f5 = 1;
                nb = stairMetaAt(w, pos.south());
                if (sameHalf(nb, meta)) {
                    if ((nb & 3) == 1 && !sameStair(w, pos.east(), meta)) { f3 = 0.5; straight = false; }
                    else if ((nb & 3) == 0 && !sameStair(w, pos.west(), meta)) { f2 = 0.5; straight = false; }
                }
                break;
            default: // NORTH
                nb = stairMetaAt(w, pos.north());
                if (sameHalf(nb, meta)) {
                    if ((nb & 3) == 1 && !sameStair(w, pos.east(), meta)) { f3 = 0.5; straight = false; }
                    else if ((nb & 3) == 0 && !sameStair(w, pos.west(), meta)) { f2 = 0.5; straight = false; }
                }
        }
        out.add(box(f2, f, f4, f3, f1, f5));

        if (straight) {
            // func_176304_i: inner-corner quarter on the side opposite the facing direction
            double g2 = 0, g3 = 0.5, g4 = 0.5, g5 = 1;
            boolean has = false;
            switch (facing) {
                case 0: // EAST: check WEST neighbor
                    nb = stairMetaAt(w, pos.west());
                    if (sameHalf(nb, meta)) {
                        if ((nb & 3) == 3 && !sameStair(w, pos.north(), meta)) { g4 = 0; g5 = 0.5; has = true; }
                        else if ((nb & 3) == 2 && !sameStair(w, pos.south(), meta)) { g4 = 0.5; g5 = 1; has = true; }
                    }
                    break;
                case 1: // WEST: check EAST neighbor
                    g2 = 0.5; g3 = 1;
                    nb = stairMetaAt(w, pos.east());
                    if (sameHalf(nb, meta)) {
                        if ((nb & 3) == 3 && !sameStair(w, pos.north(), meta)) { g4 = 0; g5 = 0.5; has = true; }
                        else if ((nb & 3) == 2 && !sameStair(w, pos.south(), meta)) { g4 = 0.5; g5 = 1; has = true; }
                    }
                    break;
                case 2: // SOUTH: check NORTH neighbor
                    g4 = 0; g5 = 0.5;
                    nb = stairMetaAt(w, pos.north());
                    if (sameHalf(nb, meta)) {
                        if ((nb & 3) == 1 && !sameStair(w, pos.west(), meta)) { has = true; }
                        else if ((nb & 3) == 0 && !sameStair(w, pos.east(), meta)) { g2 = 0.5; g3 = 1; has = true; }
                    }
                    break;
                default: // NORTH: check SOUTH neighbor
                    nb = stairMetaAt(w, pos.south());
                    if (sameHalf(nb, meta)) {
                        if ((nb & 3) == 1 && !sameStair(w, pos.west(), meta)) { has = true; }
                        else if ((nb & 3) == 0 && !sameStair(w, pos.east(), meta)) { g2 = 0.5; g3 = 1; has = true; }
                    }
            }
            if (has) out.add(box(g2, f, g4, g3, f1, g5));
        }
        return out;
    }

    private static AABB torchBox(int meta) {
        double f = 0.15;
        switch (meta) {
            case 1: return box(0, 0.2, 0.5 - f, f * 2, 0.8, 0.5 + f); // EAST
            case 2: return box(1 - f * 2, 0.2, 0.5 - f, 1, 0.8, 0.5 + f); // WEST
            case 3: return box(0.5 - f, 0.2, 0, 0.5 + f, 0.8, f * 2); // SOUTH
            case 4: return box(0.5 - f, 0.2, 1 - f * 2, 0.5 + f, 0.8, 1); // NORTH
            default: return box(0.4, 0, 0.4, 0.6, 0.6, 0.6); // floor
        }
    }

    private static AABB chestBox(DGWorld w, BlockPos pos, int selfId) {
        if (idAt(w, pos.north()) == selfId) return box(0.0625, 0, 0, 0.9375, 0.875, 0.9375);
        if (idAt(w, pos.south()) == selfId) return box(0.0625, 0, 0.0625, 0.9375, 0.875, 1);
        if (idAt(w, pos.west()) == selfId) return box(0, 0, 0.0625, 0.9375, 0.875, 0.9375);
        if (idAt(w, pos.east()) == selfId) return box(0.0625, 0, 0.0625, 1, 0.875, 0.9375);
        return box(0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375);
    }

    /** BlockDoor.setBoundBasedOnMeta(combineMetadata(world, pos)) */
    private static int doorHalfMeta(BlockState s) {
        if ("upper".equals(strProp(s, "half"))) {
            int m = 8;
            if ("right".equals(strProp(s, "hinge"))) m |= 1;
            if (boolProp(s, "powered")) m |= 2;
            return m;
        }
        return doorFacing(dirProp(s, "facing")) | (boolProp(s, "open") ? 4 : 0);
    }

    private static AABB doorBox(DGWorld w, BlockPos pos) {
        BlockState self = w.getBlockState(pos);
        int selfMeta = doorHalfMeta(self);
        boolean top = "upper".equals(strProp(self, "half"));
        int lowerMeta = top ? doorHalfMeta(w.getBlockState(pos.below())) : selfMeta;
        int upperMeta = top ? selfMeta : doorHalfMeta(w.getBlockState(pos.above()));
        int combined = (lowerMeta & 7) | (top ? 8 : 0) | ((upperMeta & 1) != 0 ? 16 : 0) | ((upperMeta & 2) != 0 ? 32 : 0);
        // getFacing(combined) = EnumFacing.getHorizontal(combined & 3).rotateYCCW()
        Direction facing = horizontal(combined & 3).getCounterClockWise();
        boolean open = (combined & 4) != 0;
        boolean hingeLeft = (combined & 16) != 0;
        double f = 0.1875;
        if (open) {
            switch (facing) {
                case EAST: return !hingeLeft ? box(0, 0, 0, 1, 1, f) : box(0, 0, 1 - f, 1, 1, 1);
                case SOUTH: return !hingeLeft ? box(1 - f, 0, 0, 1, 1, 1) : box(0, 0, 0, f, 1, 1);
                case WEST: return !hingeLeft ? box(0, 0, 1 - f, 1, 1, 1) : box(0, 0, 0, 1, 1, f);
                default: return !hingeLeft ? box(0, 0, 0, f, 1, 1) : box(1 - f, 0, 0, 1, 1, 1);
            }
        }
        switch (facing) {
            case EAST: return box(0, 0, 0, f, 1, 1);
            case SOUTH: return box(0, 0, 0, 1, 1, f);
            case WEST: return box(1 - f, 0, 0, 1, 1, 1);
            default: return box(0, 0, 1 - f, 1, 1, 1);
        }
    }

    private static AABB ladderBox(int meta) { // meta: 2=N,3=S,4=W,5=E
        double f = 0.125;
        switch (meta) {
            case 2: return box(0, 0, 1 - f, 1, 1, 1);
            case 3: return box(0, 0, 0, 1, 1, f);
            case 4: return box(1 - f, 0, 0, 1, 1, 1);
            default: return box(0, 0, 0, f, 1, 1);
        }
    }

    private static AABB wallSignBox(int meta) { // meta: 2=N,3=S,4=W,5=E
        double f = 0.28125, f1 = 0.78125, f4 = 0.125;
        switch (meta) {
            case 2: return box(0, f, 1 - f4, 1, f1, 1);
            case 3: return box(0, f, 0, 1, f1, f4);
            case 4: return box(1 - f4, f, 0, 1, f1, 1);
            default: return box(0, f, 0, f4, f1, 1);
        }
    }

    private static AABB wallBannerBox(int meta) { // same plate as wall sign
        return wallSignBox(meta);
    }

    private static AABB leverBox(int meta) {
        double f = 0.1875;
        switch (meta & 7) {
            case 1: return box(0, 0.2, 0.5 - f, f * 2, 0.8, 0.5 + f); // EAST
            case 2: return box(1 - f * 2, 0.2, 0.5 - f, 1, 0.8, 0.5 + f); // WEST
            case 3: return box(0.5 - f, 0.2, 0, 0.5 + f, 0.8, f * 2); // SOUTH
            case 4: return box(0.5 - f, 0.2, 1 - f * 2, 0.5 + f, 0.8, 1); // NORTH
            case 5: case 6: return box(0.25, 0, 0.25, 0.75, 0.6, 0.75); // UP_X / UP_Z
            default: return box(0.25, 0.4, 0.25, 0.75, 1, 0.75); // DOWN_X / DOWN_Z
        }
    }

    private static AABB buttonBox(int meta) {
        double f2 = ((meta & 8) != 0 ? 1 : 2) / 16.0;
        switch (meta & 7) {
            case 1: return box(0, 0.375, 0.3125, f2, 0.625, 0.6875); // EAST
            case 2: return box(1 - f2, 0.375, 0.3125, 1, 0.625, 0.6875); // WEST
            case 3: return box(0.3125, 0.375, 0, 0.6875, 0.625, f2); // SOUTH
            case 4: return box(0.3125, 0.375, 1 - f2, 0.6875, 0.625, 1); // NORTH
            case 5: return box(0.3125, 0, 0.375, 0.6875, f2, 0.625); // UP
            default: return box(0.3125, 1 - f2, 0.375, 0.6875, 1, 0.625); // DOWN
        }
    }

    private static AABB portalBox(int meta) { // axis: 1=X, 2=Z
        double f = 0.125, f1 = 0.125;
        if ((meta & 3) == 1) f = 0.5;
        if ((meta & 3) == 2) f1 = 0.5;
        return box(0.5 - f, 0, 0.5 - f1, 0.5 + f, 1, 0.5 + f1);
    }

    private static AABB trapdoorBox(int meta) {
        double f = 0.1875;
        if ((meta & 4) != 0) { // open: plate on the hinged side
            switch (meta & 3) {
                case 0: return box(0, 0, 0.8125, 1, 1, 1); // NORTH
                case 1: return box(0, 0, 0, 1, 1, 0.1875); // SOUTH
                case 2: return box(0.8125, 0, 0, 1, 1, 1); // WEST
                default: return box(0, 0, 0, 0.1875, 1, 1); // EAST
            }
        }
        return (meta & 8) != 0 ? box(0, 0.8125, 0, 1, 1, 1) : box(0, 0, 0, 1, f, 1);
    }

    private static AABB tripwireHookBox(int meta) {
        double f = 0.1875;
        switch (meta & 3) { // getHorizontal: 0=S,1=W,2=N,3=E
            case 0: return box(0.5 - f, 0.2, 0, 0.5 + f, 0.8, f * 2); // SOUTH: z-min plate
            case 1: return box(1 - f * 2, 0.2, 0.5 - f, 1, 0.8, 0.5 + f); // WEST: x-max plate
            case 2: return box(0.5 - f, 0.2, 1 - f * 2, 0.5 + f, 0.8, 1); // NORTH: z-max plate
            default: return box(0, 0.2, 0.5 - f, f * 2, 0.8, 0.5 + f); // EAST: x-min plate
        }
    }

    private static AABB cocoaBox(int meta) {
        int age = (meta & 12) >> 2;
        double j = 4 + age * 2, k = 5 + age * 2;
        double f = j / 2.0;
        switch (meta & 3) { // getHorizontal: 0=S,1=W,2=N,3=E
            case 0: return box((8 - f) / 16, (12 - k) / 16, (15 - j) / 16, (8 + f) / 16, 0.75, 0.9375);
            case 2: return box((8 - f) / 16, (12 - k) / 16, 0.0625, (8 + f) / 16, 0.75, (1 + j) / 16);
            case 1: return box(0.0625, (12 - k) / 16, (8 - f) / 16, (1 + j) / 16, 0.75, (8 + f) / 16);
            default: return box((15 - j) / 16, (12 - k) / 16, (8 - f) / 16, 0.9375, 0.75, (8 + f) / 16);
        }
    }

    private static AABB skullBox(int meta) {
        switch (meta) {
            case 2: return box(0.25, 0.25, 0.5, 0.75, 0.75, 1); // NORTH
            case 3: return box(0.25, 0.25, 0, 0.75, 0.75, 0.5); // SOUTH
            case 4: return box(0.5, 0.25, 0.25, 1, 0.75, 0.75); // WEST
            case 5: return box(0, 0.25, 0.25, 0.5, 0.75, 0.75); // EAST
            default: return box(0.25, 0, 0.25, 0.75, 0.5, 0.75); // UP
        }
    }

    private static AABB pistonBox(int meta) {
        if ((meta & 8) == 0) return FULL;
        switch (meta & 7) {
            case 0: return box(0, 0.25, 0, 1, 1, 1); // DOWN
            case 1: return box(0, 0, 0, 1, 0.75, 1); // UP
            case 2: return box(0, 0, 0.25, 1, 1, 1); // NORTH
            case 3: return box(0, 0, 0, 1, 1, 0.75); // SOUTH
            case 4: return box(0.25, 0, 0, 1, 1, 1); // WEST
            default: return box(0, 0, 0, 0.75, 1, 1); // EAST
        }
    }

    /** 1.8.9 vine bounds from meta face bits + canPlaceOn(up) when no face is set. */
    private static AABB vineBox(DGWorld w, BlockPos pos, int meta) {
        double f1 = 1, f2 = 1, f3 = 1, f4 = 0, f5 = 0, f6 = 0;
        boolean any = false;
        if ((meta & 2) != 0) { // WEST
            f4 = Math.max(f4, 0.0625); f1 = 0; f2 = 0; f5 = 1; f3 = 0; f6 = 1; any = true;
        }
        if ((meta & 8) != 0) { // EAST
            f1 = Math.min(f1, 0.9375); f4 = 1; f2 = 0; f5 = 1; f3 = 0; f6 = 1; any = true;
        }
        if ((meta & 4) != 0) { // NORTH
            f6 = Math.max(f6, 0.0625); f3 = 0; f1 = 0; f4 = 1; f2 = 0; f5 = 1; any = true;
        }
        if ((meta & 1) != 0) { // SOUTH
            f3 = Math.min(f3, 0.9375); f6 = 1; f1 = 0; f4 = 1; f2 = 0; f5 = 1; any = true;
        }
        if (!any && vineCanPlaceOn(idAt(w, pos.above()))) {
            f2 = Math.min(f2, 0.9375); f5 = 1; f1 = 0; f4 = 1; f3 = 0; f6 = 1;
        }
        return box(f1, f2, f3, f4, f5, f6);
    }

    private static boolean vineCanPlaceOn(int otherId) { // isFullCube && material.blocksMovement
        return opaqueFullCube(otherId);
    }

    /** Fence/pane selection AABB: single fat box spanning all connections (height = top). */
    private static AABB fenceLikeBox(DGWorld w, BlockPos pos, int selfId, double lo, double hi, double top) {
        boolean n = connects(w, pos, selfId, Direction.NORTH);
        boolean s = connects(w, pos, selfId, Direction.SOUTH);
        boolean we = connects(w, pos, selfId, Direction.WEST);
        boolean e = connects(w, pos, selfId, Direction.EAST);
        return box(we ? 0 : lo, 0, n ? 0 : lo, e ? 1 : hi, top, s ? 1 : hi);
    }

    private static boolean connects(DGWorld w, BlockPos pos, int selfId, Direction dir) {
        int other = idAt(w, pos.relative(dir));
        if (isPane(selfId)) return paneConnects(selfId, other);
        if (selfId == 139) return wallConnects(selfId, other);
        return fenceConnects(selfId, other);
    }

    /** BlockWall.setBlockBoundsBasedOnState */
    private static AABB wallBox(DGWorld w, BlockPos pos) {
        boolean n = wallConnects(139, idAt(w, pos.north()));
        boolean s = wallConnects(139, idAt(w, pos.south()));
        boolean we = wallConnects(139, idAt(w, pos.west()));
        boolean e = wallConnects(139, idAt(w, pos.east()));
        double f = 0.25, f1 = 0.75, f2 = 0.25, f3 = 0.75, f4 = 1.0;
        if (n) f2 = 0;
        if (s) f3 = 1;
        if (we) f = 0;
        if (e) f1 = 1;
        if (n && s && !we && !e) { f4 = 0.8125; f = 0.3125; f1 = 0.6875; }
        else if (!n && !s && we && e) { f4 = 0.8125; f2 = 0.3125; f3 = 0.6875; }
        return box(f, 0, f2, f1, f4, f3);
    }

    /** BlockFence.addCollisionBoxesToList: two possible 1.5-tall boxes. */
    private static List<AABB> fenceCollision(DGWorld w, BlockPos pos, int selfId) {
        boolean n = connects(w, pos, selfId, Direction.NORTH);
        boolean s = connects(w, pos, selfId, Direction.SOUTH);
        boolean we = connects(w, pos, selfId, Direction.WEST);
        boolean e = connects(w, pos, selfId, Direction.EAST);
        double f = 0.375, f1 = 0.625, f2 = 0.375, f3 = 0.625;
        if (n) f2 = 0;
        if (s) f3 = 1;
        java.util.List<AABB> out = new java.util.ArrayList<>(2);
        if (n || s) out.add(box(f, 0, f2, f1, 1.5, f3));
        f2 = 0.375; f3 = 0.625;
        if (we) f = 0;
        if (e) f1 = 1;
        if (we || e || (!n && !s)) out.add(box(f, 0, f2, f1, 1.5, f3));
        return out;
    }

    /** BlockPane.addCollisionBoxesToList: west/east plate then north/south plate. */
    private static List<AABB> paneCollision(DGWorld w, BlockPos pos, int selfId) {
        boolean n = connects(w, pos, selfId, Direction.NORTH);
        boolean s = connects(w, pos, selfId, Direction.SOUTH);
        boolean we = connects(w, pos, selfId, Direction.WEST);
        boolean e = connects(w, pos, selfId, Direction.EAST);
        java.util.List<AABB> out = new java.util.ArrayList<>(2);
        if ((!we || !e) && (we || e || n || s)) {
            if (we) out.add(box(0, 0, 0.4375, 0.5, 1, 0.5625));
            else if (e) out.add(box(0.5, 0, 0.4375, 1, 1, 0.5625));
        } else {
            out.add(box(0, 0, 0.4375, 1, 1, 0.5625));
        }
        if ((!n || !s) && (we || e || n || s)) {
            if (n) out.add(box(0.4375, 0, 0, 0.5625, 1, 0.5));
            else if (s) out.add(box(0.4375, 0, 0.5, 0.5625, 1, 1));
        } else {
            out.add(box(0.4375, 0, 0, 0.5625, 1, 1));
        }
        return out;
    }
}

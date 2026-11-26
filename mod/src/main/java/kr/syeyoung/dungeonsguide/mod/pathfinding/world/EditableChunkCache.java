/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2024  cyoung06 (syeyoung)
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
//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package kr.syeyoung.dungeonsguide.mod.pathfinding.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicReferenceArray;

// the chunks of a region of the level. 1.8.9 extended vanilla's ChunkCache, 26.1 has none, so it keeps its own chunk array
public class EditableChunkCache {
    @Getter
    private final Level level;
    @Getter
    private final int chunkX;
    @Getter
    private final int chunkZ;
    // flat atomic array: updateChunk runs on the render thread while DG-LivePathfinder
    // reads through getBlockState - AtomicReferenceArray gives each slot volatile semantics
    private final AtomicReferenceArray<LevelChunk> chunkArray;
    private final int xWidth;
    private final int zWidth;

    public EditableChunkCache(Level worldIn, BlockPos posFromIn, BlockPos posToIn, int subIn) {
        this.level = worldIn;
        // same bounds as 1.8.9's ChunkCache
        this.chunkX = posFromIn.getX() - subIn >> 4;
        this.chunkZ = posFromIn.getZ() - subIn >> 4;
        int maxX = posToIn.getX() + subIn >> 4;
        int maxZ = posToIn.getZ() + subIn >> 4;
        this.xWidth = maxX - this.chunkX + 1;
        this.zWidth = maxZ - this.chunkZ + 1;
        this.chunkArray = new AtomicReferenceArray<>(xWidth * zWidth);
        for (int x = this.chunkX; x <= maxX; x++) {
            for (int z = this.chunkZ; z <= maxZ; z++) {
                this.chunkArray.set((x - this.chunkX) * zWidth + (z - this.chunkZ), worldIn.getChunk(x, z));
            }
        }
    }

    public boolean isManaged(int cx, int cz) {
        int localX = cx- this.chunkX;
        int localZ = cz - this.chunkZ;
        return localZ >= 0 && localX >= 0 && localX < xWidth && localZ < zWidth;
    }

    public void updateChunk(LevelChunk c) {
        int localX = c.getPos().x() - this.chunkX;
        int localZ = c.getPos().z() - this.chunkZ;
        if (localZ < 0 || localX < 0 || localX >= xWidth || localZ >= zWidth) throw new IllegalArgumentException("This "+c.getPos().x()+"/"+c.getPos().z()+" chunk doesn't belong here "+this.chunkX+"/"+this.chunkZ+" width "+xWidth+"x"+zWidth);
        this.chunkArray.set(localX * zWidth + localZ, c);
    }
    public void updateChunk(BlockPos p) {
        LevelChunk c = this.level.getChunkAt(p);
        // 26.x can hand back a placeholder empty chunk (pos 0/0) for unloaded positions;
        // trusting its coordinates corrupts the cache - only accept the chunk actually asked for
        if (!isManaged(c.getPos().x(), c.getPos().z())) return;
        updateChunk(c);
    }

    private LevelChunk getChunk(BlockPos pos) {
        int localX = (pos.getX() >> 4) - this.chunkX;
        int localZ = (pos.getZ() >> 4) - this.chunkZ;
        if (localX < 0 || localZ < 0 || localX >= xWidth || localZ >= zWidth) return null;
        return this.chunkArray.get(localX * zWidth + localZ);
    }

    // what 1.8.9's ChunkCache.getBlockState did: air outside the cached chunks
    public BlockState getBlockState(BlockPos pos) {
        LevelChunk chunk = getChunk(pos);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        LevelChunk chunk = getChunk(pos);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    public int getMinX() {
        return this.chunkX * 16;
    }

    public int getMinZ() {
        return this.chunkZ * 16;
    }

    public int getMaxX() {
        return (this.chunkX + this.xWidth) * 16;
    }

    public int getMaxZ() {
        return (this.chunkZ + this.zWidth) * 16;
    }
}

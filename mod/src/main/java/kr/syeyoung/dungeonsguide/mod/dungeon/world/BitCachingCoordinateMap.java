package kr.syeyoung.dungeonsguide.mod.dungeon.world;

import lombok.Getter;

public class BitCachingCoordinateMap<T extends Enum<T>> implements ICoordinateMap<Enum<T>> {
    private final ICoordinateMap<T> backingMap;
    private final T outOfScope;
    private final T[] values;

    private final BitStorage bitStorage;

    /**
     * Special Contract: values[0] is for UNCACHED.
     *
     * @param backingMap
     * @param values
     * @param outOfScope
     */
    public BitCachingCoordinateMap(ICoordinateMap<T> backingMap, T[] values, T outOfScope) {
        this.backingMap = backingMap;
        this.values = values;
        this.outOfScope = outOfScope;

        minX = backingMap.getMinX();
        minY = backingMap.getMinY();
        minZ = backingMap.getMinZ();
        maxX = backingMap.getMaxX();
        maxY = backingMap.getMaxY();
        maxZ = backingMap.getMaxZ();
        lenX = maxX - minX;
        lenY = maxY - minY;
        lenZ = maxZ - minZ;

        int bitsPer = (int) Math.ceil(Math.log(values.length) / Math.log(2));

        this.bitStorage = new BitStorage(lenX, lenY, lenZ, bitsPer);
    }

    @Getter
    private int minX, minY, minZ, maxX, maxY, maxZ, lenX, lenY, lenZ;

    @Override
    public T getBlock(int x, int y, int z) {
        if (x < minX || z < minZ || x >= maxX || z >= maxZ || y < minY || y >= maxY) return outOfScope;
        int dx = x - minX, dy = y - minY, dz = z - minZ;
        int data = bitStorage.read(dx, dy, dz);
        if (data != 0) return values[data];
        // the backing read can race a render-thread invalidation; commit only if none
        // committed in between, so a stale value can't resurrect a cleared cell
        long gen = bitStorage.generation();
        T val = backingMap.getBlock(x, y, z);
        bitStorage.storeIfUnchanged(dx, dy, dz, val.ordinal(), gen);
        return val;
    }


    public boolean update(int x, int y, int z) {
        if (x < minX || z < minZ || x >= maxX || z >= maxZ || y < minY || y >= maxY) return false;
        int dx = x - minX, dy = y - minY, dz = z - minZ;

        T val = backingMap.getBlock(x, y, z);
        return bitStorage.store(dx,dy,dz, val.ordinal());
    }

    public void invalidateCache(int x, int y, int z) {
        if (x < minX || z < minZ || x >= maxX || z >= maxZ || y < minY || y >= maxY) return;
        bitStorage.store(x - minX, y - minY, z - minZ, 0);
    }

    // [from, to) in map coordinates. z-runs are contiguous inside an x-slice's page, so a
    // chunk invalidation costs one masked write per row instead of one store per cell
    public void invalidateRange(int x0, int y0, int z0, int x1, int y1, int z1) {
        int fx = Math.max(x0, minX), tx = Math.min(x1, maxX);
        int fy = Math.max(y0, minY), ty = Math.min(y1, maxY);
        int fz = Math.max(z0, minZ), tz = Math.min(z1, maxZ);
        if (fx >= tx || fy >= ty || fz >= tz) return;
        for (int x = fx; x < tx; x++) {
            for (int y = fy; y < ty; y++) {
                int dx = x - minX, dy = y - minY;
                int from = dy * lenZ + (fz - minZ);
                int to = dy * lenZ + (tz - 1 - minZ);
                bitStorage.clearBits(dx, from, to);
            }
        }
    }

    @Override
    public boolean isInScope(int x, int y, int z) {
        return backingMap.isInScope(x, y, z);
    }
}

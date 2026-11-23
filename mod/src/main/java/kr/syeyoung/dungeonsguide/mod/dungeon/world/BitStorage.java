/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2023  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.dungeon.world;

public class BitStorage {
    // one page per x-slice; unwritten slices stay null since 0 already reads as UNCACHED
    private final long[][] pages;
    private final int dataPerLong;
    private int xLen, yLen, zLen;
    private final int longsPerPage;

    private final int mask;
    private final int bitsPer;
    public BitStorage(int xLen, int yLen, int zLen, int bitsPer) {
        dataPerLong = (int) Math.floor(64.0 / bitsPer);
        this.bitsPer = bitsPer;
        mask = (1 << bitsPer) - 1;
        longsPerPage = yLen * zLen / dataPerLong + 1; // plus 1 , because I don't wanna do floating point op for dividing and ceiling
        pages = new long[xLen][];

        this.xLen = xLen;
        this.yLen = yLen;
        this.zLen = zLen;
    }

    public int getMask() {
        return mask;
    }

    public boolean store(int x, int y, int z, int data) {
        int bitIdx = y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        long[] page = pages[x];
        if (page == null) {
            if (data == 0) return false;
            synchronized (pages) {
                if (pages[x] == null) pages[x] = new long[longsPerPage];
                page = pages[x];
            }
        }
        int orig = (int) ((page[location] >>> bitStart) & mask);
        if (orig == data) return false;
        long theBit = page[location];
        theBit &= ~((long) mask << bitStart);
        theBit |= (long) (data & mask) << bitStart;
        page[location] = theBit;

        return true;
    }

    public int read(int x, int y, int z) {
        long[] page = pages[x];
        if (page == null) return 0;
        int bitIdx = y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        long theBit = page[location];

        return (int) ((theBit >>> bitStart) & mask);
    }
}

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
    private long[] arr;
    private final int dataPerLong;
    private int xLen, yLen, zLen;

    private final int mask;
    private final int bitsPer;
    public BitStorage(int xLen, int yLen, int zLen, int bitsPer) {
        dataPerLong = (int) Math.floor(64.0 / bitsPer);
        this.bitsPer = bitsPer;
        mask = (1 << bitsPer) - 1;
        arr = new long[xLen * yLen * zLen / dataPerLong + 1]; // plus 1 , because I don't wanna do floating point op for dividing and ceiling

        this.xLen = xLen;
        this.yLen = yLen;
        this.zLen = zLen;
    }

    public int getMask() {
        return mask;
    }

    public boolean store(int x, int y, int z, int data) {
        int bitIdx = x * yLen * zLen + y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        int orig = read(x,y,z);
        if (orig == data) return false;
        long theBit = arr[location];
        theBit &= ~((long) mask << bitStart);
        theBit |= (long) (data & mask) << bitStart;
        arr[location] = theBit;

        return true;
    }

    public int read(int x, int y, int z) {
        int bitIdx = x * yLen * zLen + y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        long theBit = arr[location];

        return (int) ((theBit >>> bitStart) & mask);
    }

    // zeros entries [fromBitIdx, toBitIdx] inclusive - chunk invalidation clears z-runs
    // instead of writing every cell one at a time
    public void clearBits(int fromBitIdx, int toBitIdx) {
        if (toBitIdx < fromBitIdx) return;
        int firstLong = fromBitIdx / dataPerLong;
        int lastLong = toBitIdx / dataPerLong;
        int firstBit = bitsPer * (fromBitIdx % dataPerLong);
        int lastBit = bitsPer * (toBitIdx % dataPerLong);

        if (firstLong == lastLong) {
            long lo = firstBit == 0 ? 0L : ((1L << firstBit) - 1);
            long hi = lastBit + bitsPer >= 64 ? 0L : (-1L << (lastBit + bitsPer));
            arr[firstLong] &= (lo | hi);
            return;
        }
        if (firstBit == 0) {
            arr[firstLong] = 0;
        } else {
            arr[firstLong] &= ((1L << firstBit) - 1);
        }
        for (int i = firstLong + 1; i < lastLong; i++) {
            arr[i] = 0;
        }
        if (lastBit + bitsPer >= 64) {
            arr[lastLong] = 0;
        } else {
            arr[lastLong] &= (-1L << (lastBit + bitsPer));
        }
    }
}

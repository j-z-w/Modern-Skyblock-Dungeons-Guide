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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

// accessed from both the render thread (chunk/block invalidation) and DG-LivePathfinder
// (cache stores while searching). pages publish atomically; every read-modify-write of a
// packed long is serialized per page so a clear can never be silently overwritten; and the
// generation counter lets a worker drop a store whose value was computed before an
// invalidation that raced it - no resurrected stale cache entries.
public class BitStorage {
    // one page per x-slice; unwritten slices stay null since 0 already reads as UNCACHED
    private final AtomicReferenceArray<AtomicLongArray> pages;
    private final AtomicLong generation = new AtomicLong();
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
        pages = new AtomicReferenceArray<>(xLen);

        this.xLen = xLen;
        this.yLen = yLen;
        this.zLen = zLen;
    }

    public int getMask() {
        return mask;
    }

    // bumped by every invalidation (clearBits / store of 0) BEFORE the page lock is
    // acquired, which is strictly stronger than under it - a worker can never observe
    // a generation whose invalidation is still waiting on the lock
    public long generation() {
        return generation.get();
    }

    private AtomicLongArray page(int x, boolean create) {
        AtomicLongArray page = pages.get(x);
        if (page == null && create) {
            AtomicLongArray fresh = new AtomicLongArray(longsPerPage);
            if (pages.compareAndSet(x, null, fresh)) page = fresh;
            else page = pages.get(x);
        }
        return page;
    }

    public boolean store(int x, int y, int z, int data) {
        int bitIdx = y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        // invalidation (data == 0) bumps the generation even when the page is absent:
        // the backing still changed and worker stores keyed to the old generation must fail
        if (data == 0) generation.incrementAndGet();
        AtomicLongArray page = page(x, data != 0);
        if (page == null) return false;
        synchronized (page) {
            long cur = page.get(location);
            int orig = (int) ((cur >>> bitStart) & mask);
            if (orig == data) return false;
            page.set(location, (cur & ~((long) mask << bitStart)) | ((long) (data & mask) << bitStart));
        }
        return true;
    }

    // worker-side store: the cached value was computed against a backing read that may have
    // raced an invalidation - commit it only if no invalidation committed since expectedGen.
    // the check runs inside the page lock so a clear can't slip between check and write.
    public boolean storeIfUnchanged(int x, int y, int z, int data, long expectedGen) {
        int bitIdx = y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));
        AtomicLongArray page = page(x, true);
        synchronized (page) {
            if (generation.get() != expectedGen) return false;
            long cur = page.get(location);
            int orig = (int) ((cur >>> bitStart) & mask);
            if (orig == data) return false;
            page.set(location, (cur & ~((long) mask << bitStart)) | ((long) (data & mask) << bitStart));
        }
        return true;
    }

    public int read(int x, int y, int z) {
        AtomicLongArray page = page(x, false);
        if (page == null) return 0;
        int bitIdx = y * zLen + z;
        int location = bitIdx / dataPerLong;
        int bitStart = (bitsPer * (bitIdx % dataPerLong));

        return (int) ((page.get(location) >>> bitStart) & mask);
    }

    // zeros entries [fromBitIdx, toBitIdx] inclusive inside the x-slice's page (bitIdx is
    // y * zLen + z like store/read) - chunk invalidation clears z-runs instead of writing
    // every cell one at a time. a null page already reads as all-UNCACHED
    public void clearBits(int x, int fromBitIdx, int toBitIdx) {
        if (toBitIdx < fromBitIdx) return;
        generation.incrementAndGet();
        AtomicLongArray arr = page(x, false);
        if (arr == null) return;
        synchronized (arr) {
            int firstLong = fromBitIdx / dataPerLong;
            int lastLong = toBitIdx / dataPerLong;
            int firstBit = bitsPer * (fromBitIdx % dataPerLong);
            int lastBit = bitsPer * (toBitIdx % dataPerLong);

            if (firstLong == lastLong) {
                long lo = firstBit == 0 ? 0L : ((1L << firstBit) - 1);
                long hi = lastBit + bitsPer >= 64 ? 0L : (-1L << (lastBit + bitsPer));
                arr.set(firstLong, arr.get(firstLong) & (lo | hi));
            } else {
                if (firstBit == 0) {
                    arr.set(firstLong, 0);
                } else {
                    arr.set(firstLong, arr.get(firstLong) & ((1L << firstBit) - 1));
                }
                for (int i = firstLong + 1; i < lastLong; i++) {
                    arr.set(i, 0);
                }
                if (lastBit + bitsPer >= 64) {
                    arr.set(lastLong, 0);
                } else {
                    arr.set(lastLong, arr.get(lastLong) & (-1L << (lastBit + bitsPer)));
                }
            }
        }
    }
}

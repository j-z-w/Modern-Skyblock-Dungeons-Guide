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

package kr.syeyoung.dungeonsguide.mod.debug;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// "dgdebug slowtasks": logs every DG event handler / scheduled task that runs longer than
// the threshold on the render thread. Off by default: every check below is a single
// volatile boolean read when disabled.
public final class SlowTaskTracker {
    private static final Logger LOGGER = LogManager.getLogger("DG-SlowTasks");

    private static volatile boolean enabled = false;
    private static volatile long thresholdNanos = Long.parseLong(
            System.getProperty("dg.slowtasks.thresholdNanos", "2000000")); // 2 ms

    private SlowTaskTracker() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        SlowTaskTracker.enabled = enabled;
    }

    public static long getThresholdNanos() {
        return thresholdNanos;
    }

    public static void setThresholdNanos(long thresholdNanos) {
        SlowTaskTracker.thresholdNanos = thresholdNanos;
    }

    // minecraft isn't always around (unit tests, early boot); those are never the render thread
    public static boolean isRenderThread() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.isSameThread();
        } catch (Throwable t) {
            return false;
        }
    }

    // one check for "should I pay for timing": enabled AND running on the render thread
    public static boolean measureRenderThread() {
        return enabled && isRenderThread();
    }

    // start = measureRenderThread() ? System.nanoTime() : 0, then reportIfSlow(name, start)
    public static void reportIfSlow(String name, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed > thresholdNanos) {
            LOGGER.warn("[slowtasks] {} took {} ms on the render thread", name, String.format("%.2f", elapsed / 1_000_000.0));
        }
    }

    public static void runTimed(String name, Runnable runnable) {
        if (!enabled) {
            runnable.run();
            return;
        }
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            if (isRenderThread()) reportIfSlow(name, start);
        }
    }
}

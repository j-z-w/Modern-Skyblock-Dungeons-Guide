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

package kr.syeyoung.dungeonsguide.mod.utils;

import kr.syeyoung.dungeonsguide.mod.debug.SlowTaskTracker;
import net.minecraft.client.Minecraft;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// DG's named replacement for Minecraft.execute: a burst of tasks submitted off-thread
// (one per handled packet, room match callbacks, chat routines) used to become one
// scheduledExecutables entry each. Here they share a single drain runnable that runs
// them in submission order and yields once its time budget is spent, so a packet storm
// can never pin the render thread in one task.
public final class RenderThreadDispatcher {
    private static class NamedTask {
        final String name;
        final Runnable runnable;
        NamedTask(String name, Runnable runnable) {
            this.name = name;
            this.runnable = runnable;
        }
    }

    private static final Queue<NamedTask> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();

    // max render-thread time one drain may consume before rescheduling the remainder
    private static volatile long drainBudgetNanos = Long.parseLong(
            System.getProperty("dg.renderdrain.budgetNanos", "2000000")); // 2 ms

    private RenderThreadDispatcher() {}

    public static void execute(String name, Runnable runnable) {
        // already on the render thread: run inline so ordering is identical to a direct call
        if (SlowTaskTracker.isRenderThread()) {
            SlowTaskTracker.runTimed(name, runnable);
            return;
        }
        QUEUE.add(new NamedTask(name, runnable));
        if (DRAIN_SCHEDULED.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(RenderThreadDispatcher::drain);
        }
    }

    private static void drain() {
        long deadline = System.nanoTime() + drainBudgetNanos;
        try {
            for (;;) {
                NamedTask task = QUEUE.poll();
                if (task == null) return;
                try {
                    SlowTaskTracker.runTimed(task.name, task.runnable);
                } catch (Throwable t) {
                    // one bad task must not drop the rest of the queue
                    t.printStackTrace();
                }
                if (System.nanoTime() > deadline && !QUEUE.isEmpty()) return;
            }
        } finally {
            DRAIN_SCHEDULED.set(false);
            // tasks enqueued between the last poll() and the reset still need a drain
            if (!QUEUE.isEmpty() && DRAIN_SCHEDULED.compareAndSet(false, true)) {
                Minecraft.getInstance().execute(RenderThreadDispatcher::drain);
            }
        }
    }
}

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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// for tasks submitted off-thread that are NOT ordering-sensitive (room match callbacks,
// chat routines). they are drained once per client tick with a real time budget: when the
// budget is spent the remainder waits for the next tick instead of re-entering
// BlockableEventLoop.runAllTasks in the same frame. packet events must NOT go through
// here either - they have a hard Pre -> vanilla -> Post contract kept by wrapping
// PacketProcessor's packet.handle invokes (PacketInjector.handleVanillaPacket)
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

    // max render-thread time one tick's drain may consume; leftovers run next tick
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
    }

    // called once per client tick on the render thread (DungeonListener.onTick, START phase)
    public static void drain() {
        long deadline = System.nanoTime() + drainBudgetNanos;
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
    }
}

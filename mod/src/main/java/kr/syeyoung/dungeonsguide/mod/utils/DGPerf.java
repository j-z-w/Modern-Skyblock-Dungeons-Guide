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

import java.lang.management.ManagementFactory;

/**
 * Cumulative render/tick cost counters for the performance benchmark. Always-on but cheap:
 * each measured section costs two nanoTime calls. The gametest stress scenario diffs the
 * counters over a measurement window; dgdebug perf prints them. All counters reset together
 * so a window is attributable.
 */
public final class DGPerf {
    private DGPerf() {}

    // instrumentation is diagnostic-only: each measured section costs two nanoTime calls plus
    // two ThreadMXBean alloc queries per frame/tick, which is real cost at 200+ fps. everything
    // feeds through ENABLED: -Ddg.perf, DGPerfBenchmark.enable, or reset() turns it on.
    public static volatile boolean ENABLED = Boolean.getBoolean("dg.perf");

    private static final com.sun.management.ThreadMXBean ALLOC_MX;
    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ALLOC_MX = bean instanceof com.sun.management.ThreadMXBean ? (com.sun.management.ThreadMXBean) bean : null;
    }

    // bytes allocated on the calling thread so far; 0 when disabled or on JVMs without the
    // hotspot mx bean, so call sites can keep the simple a0 + delta pattern unconditionally
    public static long alloc() {
        return ENABLED && ALLOC_MX != null ? ALLOC_MX.getCurrentThreadAllocatedBytes() : 0;
    }

    // same pattern for the nanoTime sides: callers replace System.nanoTime() with nanos()
    public static long nanos() {
        return ENABLED ? System.nanoTime() : 0;
    }

    // frames that ran the world render pipeline (END_MAIN)
    public static long frames;
    // RenderWorldEvent dispatch: display engines recording states during END_EXTRACTION
    public static long extractNanos, extractBytes;
    // DGWorldRenderer.submitAll on COLLECT_SUBMITS; submitCalls = submitCustomGeometry + individual submits
    public static long submitNanos, submitBytes, submitCalls, submittedStates;
    // DGGuiRenderer offscreen passes (HUD events and overlay); guiDraws = staged draws issued
    public static long guiNanos, guiBytes, guiDraws, guiPasses;
    // route upkeep: LiveRouteRegistry.onTick2 and the benchmark's synthetic equivalent
    public static long tickNanos, tickBytes, ticks;

    public static void reset() {
        ENABLED = true; // reset implies someone is about to measure
        frames = 0;
        extractNanos = extractBytes = 0;
        submitNanos = submitBytes = submitCalls = submittedStates = 0;
        guiNanos = guiBytes = guiDraws = guiPasses = 0;
        tickNanos = tickBytes = ticks = 0;
    }

    public static String report() {
        double f = Math.max(1, frames);
        double t = Math.max(1, ticks);
        return String.format(
                "frames=%d | extract %.3f ms/f %.0f B/f | submit %.3f ms/f %.0f B/f, %.1f calls/f over %.0f states/f | gui %.3f ms/f %.0f B/f, %.2f passes %.1f draws/f | tick %.3f ms/t %.0f B/t over %d ticks",
                frames,
                extractNanos / 1e6 / f, extractBytes / f,
                submitNanos / 1e6 / f, submitBytes / f, (double) submitCalls / f, (double) submittedStates / f,
                guiNanos / 1e6 / f, guiBytes / f, (double) guiPasses / f, (double) guiDraws / f,
                tickNanos / 1e6 / t, tickBytes / t, ticks);
    }
}

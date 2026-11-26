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

package kr.syeyoung.dungeonsguide.mod.events;

import kr.syeyoung.dungeonsguide.mod.features.impl.etc.FeatureCollectDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared reporting for contained event-listener failures. A listener throwing every
 * tick must neither crash the client nor flood the log, so only the first few
 * consecutive failures are reported in full; once they repeat, one suppression note
 * is logged and the listener goes quiet until it succeeds again. Reporting goes
 * through the same diagnostics pipeline as every other mod error
 * ({@link FeatureCollectDiagnostics#queueSendLogAsync}, which itself dedupes
 * identical stack traces for a minute).
 */
public final class ListenerFailureReporter {
    private static final Logger logger = LogManager.getLogger("DG-EventBus");

    // failures 1..REPORTED_FAILURES of a listener are logged with the full stack trace,
    // the next one only notes that suppression kicked in
    public static final int REPORTED_FAILURES = 3;

    private ListenerFailureReporter() {}

    /**
     * @param consecutiveFailures the listener's failure count including this one; callers
     *                            own the counter and reset it when the listener succeeds
     */
    public static void report(Throwable t, String listenerName, String eventName, int consecutiveFailures) {
        if (consecutiveFailures <= REPORTED_FAILURES) {
            logger.error("Event listener " + listenerName + " threw while handling " + eventName
                    + " (consecutive failure #" + consecutiveFailures + ") - the client is NOT crashing because the failure was contained", t);
            FeatureCollectDiagnostics.queueSendLogAsync(t);
        } else if (consecutiveFailures == REPORTED_FAILURES + 1) {
            logger.error("Event listener " + listenerName + " failed " + consecutiveFailures + " times in a row while handling "
                    + eventName + " - suppressing further reports until it succeeds again");
        }
    }
}

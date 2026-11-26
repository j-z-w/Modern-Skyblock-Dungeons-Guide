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

package kr.syeyoung.dungeonsguide.mod.events.listenerlist;

import kr.syeyoung.dungeonsguide.mod.debug.SlowTaskTracker;
import kr.syeyoung.dungeonsguide.mod.events.DGEvent;
import kr.syeyoung.dungeonsguide.mod.events.EventListener;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerFailureReporter;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.ListenerRegistration;

public class BasicEventListeners<T extends DGEvent> {
    private final Class<T> clazz;
    // copy-on-write snapshots indexed by priority: reads are allocation-free, writes republish.
    private volatile BasicListenerRegistration<? super T>[][] registrations;

    public BasicEventListeners(Class<T> eventClass) {
        this.clazz = eventClass;
        registrations = new BasicListenerRegistration[ListenerPriority.SIZE][0];
    }

    public static class BasicListenerRegistration<T extends DGEvent> implements ListenerRegistration<T> {
        private final ListenerPriority priority;
        private final EventListener<? super T> invokeTarget;
        private final Class<T> eventClass;
        // consecutive dispatch failures, reset on the next success; only a spam guard,
        // so the racy read/write across event-firing threads doesn't matter
        volatile int consecutiveFailures;

        public BasicListenerRegistration(ListenerPriority priority, EventListener<? super T> invokeTarget, Class<T> eventClass) {
            this.priority = priority;
            this.invokeTarget = invokeTarget;
            this.eventClass = eventClass;
        }

        @Override
        public Class<T> getEventClass() {
            return eventClass;
        }

        @Override
        public ListenerPriority getPriority() {
            return priority;
        }
    }

    public void invoke(T event, int priority) {
        boolean measure = SlowTaskTracker.measureRenderThread();
        for (BasicListenerRegistration<? super T> registration : registrations[priority]) {
            long start = measure ? System.nanoTime() : 0;
            EventProcessResult result = invokeContained(registration, event);
            if (measure) SlowTaskTracker.reportIfSlow(() -> registration.invokeTarget + " <- " + clazz.getSimpleName(), start);
            if (result == EventProcessResult.REMOVE_LISTENER)
                unregisterEventListener((BasicListenerRegistration<T>) registration); // snapshot semantics match CopyOnWriteArrayList's
        }
    }

    // one broken listener must neither crash the client nor keep the event's other
    // listeners from running: throwables are contained per listener invocation. only
    // VirtualMachineError escapes - the vm can't be trusted to continue anyway
    private EventProcessResult invokeContained(BasicListenerRegistration<? super T> registration, T event) {
        try {
            EventProcessResult result = ((EventListener<T>) registration.invokeTarget).onEvent(event);
            if (registration.consecutiveFailures != 0) registration.consecutiveFailures = 0;
            return result;
        } catch (VirtualMachineError err) {
            throw err;
        } catch (Throwable t) {
            ListenerFailureReporter.report(t, String.valueOf(registration.invokeTarget), event.getClass().getSimpleName(), ++registration.consecutiveFailures);
            return EventProcessResult.COMPLETE;
        }
    }

    public synchronized BasicListenerRegistration<T> registerEventListener(ListenerPriority priority, EventListener<? super T> invokeTarget) {
        BasicListenerRegistration<T> registration = new BasicListenerRegistration<>(priority, invokeTarget, clazz);
        int p = priority.getPriority();
        BasicListenerRegistration<? super T>[] prev = registrations[p];
        BasicListenerRegistration<? super T>[] next = new BasicListenerRegistration[prev.length + 1];
        System.arraycopy(prev, 0, next, 0, prev.length);
        next[prev.length] = registration;
        BasicListenerRegistration<? super T>[][] snapshot = registrations.clone();
        snapshot[p] = next;
        registrations = snapshot;
        return registration;
    }

    public synchronized boolean unregisterEventListener(BasicListenerRegistration<T> registration) {
        int p = registration.priority.getPriority();
        BasicListenerRegistration<? super T>[] prev = registrations[p];
        int idx = -1;
        for (int i = 0; i < prev.length; i++) {
            if (prev[i] == registration) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;
        BasicListenerRegistration<? super T>[] next = new BasicListenerRegistration[prev.length - 1];
        System.arraycopy(prev, 0, next, 0, idx);
        System.arraycopy(prev, idx + 1, next, idx, prev.length - idx - 1);
        BasicListenerRegistration<? super T>[][] snapshot = registrations.clone();
        snapshot[p] = next;
        registrations = snapshot;
        return true;
    }
}

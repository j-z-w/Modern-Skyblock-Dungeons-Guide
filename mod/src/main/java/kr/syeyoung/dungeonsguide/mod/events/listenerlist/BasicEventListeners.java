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

import kr.syeyoung.dungeonsguide.mod.events.DGEvent;
import kr.syeyoung.dungeonsguide.mod.events.EventListener;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.ListenerRegistration;
import lombok.AllArgsConstructor;

public class BasicEventListeners<T extends DGEvent> {
    private final Class<T> clazz;
    // copy-on-write snapshots indexed by priority: reads are allocation-free, writes republish.
    private volatile BasicListenerRegistration<? super T>[][] registrations;

    public BasicEventListeners(Class<T> eventClass) {
        this.clazz = eventClass;
        registrations = new BasicListenerRegistration[ListenerPriority.SIZE][0];
    }

    @AllArgsConstructor
    public static class BasicListenerRegistration<T extends DGEvent> implements ListenerRegistration<T> {
        private ListenerPriority priority;
        private EventListener<? super T> invokeTarget;
        private Class<T> eventClass;

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
        for (BasicListenerRegistration<? super T> registration : registrations[priority]) {
            EventProcessResult result = ((EventListener<T>) registration.invokeTarget).onEvent(event);
            if (result == EventProcessResult.REMOVE_LISTENER)
                unregisterEventListener((BasicListenerRegistration<T>) registration); // snapshot semantics match CopyOnWriteArrayList's
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

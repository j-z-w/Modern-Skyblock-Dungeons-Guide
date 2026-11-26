/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026 Modern Dungeons Guide contributors
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

package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPointSet;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts the DG-canonical secret/interactable positions of a room for
 * fingerprint matching against community route data.
 *
 * Every no-arg getter returning OffsetPoint or OffsetPointSet on a
 * DungeonMechanicData is used, so secrets, levers, journals, mushrooms,
 * pressure plates, doors, tombs and other clickable mechanics all become
 * anchors. SRM "interact" secrets can correspond to any of them.
 * (Trap danger regions are skipped by name.)
 */
public final class SecretFingerprint {

    /** canonical anchor positions of all secret-like/interactable mechanics */
    public static List<RouteVec3> extract(DungeonRoomInfo info) {
        List<RouteVec3> out = new ArrayList<>();
        if (info == null || info.getMechanics() == null) return out;
        for (DungeonMechanicData data : info.getMechanics().values()) {
            collect(data, out);
        }
        return out;
    }

    /** a fingerprint anchor with the mechanic key it came from (for runtime secret mapping) */
    public static final class Anchor {
        public final String mechanicKey;
        public final RouteVec3 pos;
        public final Class<?> dataClass;

        Anchor(String mechanicKey, RouteVec3 pos, Class<?> dataClass) {
            this.mechanicKey = mechanicKey;
            this.pos = pos;
            this.dataClass = dataClass;
        }
    }

    public static List<Anchor> extractAnchors(DungeonRoomInfo info) {
        List<Anchor> out = new ArrayList<>();
        if (info == null || info.getMechanics() == null) return out;
        for (Map.Entry<String, DungeonMechanicData> e : info.getMechanics().entrySet()) {
            List<RouteVec3> tmp = new ArrayList<>();
            collect(e.getValue(), tmp);
            for (RouteVec3 v : tmp) {
                out.add(new Anchor(e.getKey(), v, e.getValue().getClass()));
            }
        }
        return out;
    }

    private static void collect(DungeonMechanicData data, List<RouteVec3> out) {
        // getMethods() order is JVM-implementation-defined and varies run to run;
        // anchor order feeds CanonicalTransform.fit tie-breaks, so sort for determinism
        Method[] methods = data.getClass().getMethods();
        java.util.Arrays.sort(methods,
                java.util.Comparator.comparing(Method::getName)
                        .thenComparing(m -> java.util.Arrays.toString(m.getParameterTypes())));
        for (Method m : methods) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName();
            if (name.contains("anger") || name.contains("locked") || name.contains("tnt") || name.contains("Tnt")) continue;
            try {
                if (OffsetPoint.class.isAssignableFrom(m.getReturnType())) {
                    add(out, (OffsetPoint) m.invoke(data));
                } else if (OffsetPointSet.class.isAssignableFrom(m.getReturnType())) {
                    OffsetPointSet set = (OffsetPointSet) m.invoke(data);
                    // cap large regions (walls/trap fields): with hundreds of
                    // anchors in a 33x33 room any point is "near" one, which
                    // inflates the fingerprint match rate
                    if (set != null && set.getOffsetPointList().size() <= 48) {
                        for (OffsetPoint pt : set.getOffsetPointList()) add(out, pt);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void add(List<RouteVec3> out, OffsetPoint pt) {
        if (pt == null) return;
        out.add(new RouteVec3(pt.getX(), pt.getY(), pt.getZ()));
    }
}

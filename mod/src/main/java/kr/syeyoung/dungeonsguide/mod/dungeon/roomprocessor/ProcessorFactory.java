/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
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

package kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ProcessorFactory {
    private static final Map<String, RoomProcessorGenerator> map = new HashMap<>();

    public static RoomProcessorGenerator getRoomProcessorGenerator(String processorId) {
        RoomProcessorGenerator generator = map.get(processorId);
        if (generator == null) {
            // puzzle/boss room processors are excluded in this build; fall back to
            // the default processor so the room still gets secret routing
            System.out.println("Unknown room processor id '" + processorId + "', falling back to 'default'");
            generator = map.get("default");
        }
        return generator;
    }

    public static void registerRoomProcessor(String processorId, RoomProcessorGenerator generator) {
        map.put(processorId, generator);
    }

    public static Set<String> getProcessors() {
        return map.keySet();
    }

    static {
        registerRoomProcessor("default", new GeneralRoomProcessor.Generator());
    }
}

package kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PrecalculatedStonk;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// regression: every mechanic-name reference in bundled roomdata must resolve to a
// mechanic that exists in the same room. covers `triggering` / `crystal` name fields
// (a dangling one leaves a lever/plate/key 'undeterminable' forever, e.g. flags'
// lever-1 -> "door-1a") and every `*Requisite` / `dependentRouteBlocker` list entry
// ("name:state" or bare "name"). found by RouteSweepTest; scan = reflection over the
// data objects so new mechanic types are checked automatically.
public class BundledMechanicReferenceTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
    }

    private static void checkRef(List<String> problems, String room, String mechName,
                                 String field, String ref, Map<String, ?> mechanics) {
        if (ref == null || ref.isEmpty()) return;
        String target = ref.split(":")[0];
        if (!mechanics.containsKey(target)) {
            problems.add(room + ": " + mechName + "." + field + " -> '" + ref + "' (no such mechanic)");
        }
    }

    private static void scanData(List<String> problems, String room, String mechName,
                                 Object data, Map<String, ?> mechanics) throws IllegalAccessException {
        for (Class<?> cls = data.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(data);
                String name = f.getName();
                if (value instanceof String && (name.equals("triggering") || name.equals("crystal"))) {
                    checkRef(problems, room, mechName, name, (String) value, mechanics);
                } else if (value instanceof List
                        && (name.toLowerCase(java.util.Locale.ROOT).endsWith("requisite")
                            || name.equals("dependentRouteBlocker"))) {
                    for (Object e : (List<?>) value) {
                        if (e instanceof String)
                            checkRef(problems, room, mechName, name, (String) e, mechanics);
                    }
                } else if (value instanceof PrecalculatedStonk) {
                    for (String ref : ((PrecalculatedStonk) value).getDependentRouteBlocker()) {
                        checkRef(problems, room, mechName, name + ".dependentRouteBlocker", ref, mechanics);
                    }
                }
            }
        }
    }

    @Test
    void allMechanicReferencesResolve() throws Exception {
        Path dir = Files.createTempDirectory("dg-roomdatas");
        DungeonRoomInfoRegistry.loadAll(dir.toFile());
        List<DungeonRoomInfo> rooms = DungeonRoomInfoRegistry.getRegistered();
        assertFalse(rooms.isEmpty(), "no bundled rooms loaded");

        List<String> problems = new ArrayList<>();
        for (DungeonRoomInfo dri : rooms) {
            for (Map.Entry<String, DungeonMechanicData> e : dri.getMechanics().entrySet()) {
                scanData(problems, dri.getName(), e.getKey(), e.getValue(), dri.getMechanics());
            }
        }
        for (String p : problems) System.out.println("DANGLING: " + p);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}

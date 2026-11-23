package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonOnewayDoorState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.PathfindRequest;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// G5 review B5/N5: the alternative-door probing order depended on HashSet iteration and a
// finished-but-unprobed load could stall or never retry. The candidate list must be
// deterministic: one candidate per blocking door, then the all-doors-open state last.
public class PrecalcFallbackTest {

    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
        new DungeonsGuide();
    }

    // the first bundled room with at least one blocking door
    private static DungeonRoom roomWithDoors() throws Exception {
        CBORMapper objectMapper = new CBORMapper();
        for (String name : IOUtils.readLines(DungeonsGuide.class.getResourceAsStream("/roomdata/datas.txt"))) {
            if (!name.endsWith(".roomdata.cbor")) continue;
            DungeonRoomInfo dri;
            try (InputStream is = DungeonsGuide.class.getResourceAsStream("/" + name)) {
                dri = objectMapper.readValue(is, DungeonRoomInfo.class);
            }
            if (dri.getFormatVersion() != 1) continue;
            RoomDataConverter.convert(dri, LegacyBlock.stateTable());
            DungeonContext context = new DungeonContext("TEST DG",
                    new DRIWorld(dri, PathfindPresetRegistry.DEFAULT_PRESET),
                    PathfindPresetRegistry.DEFAULT_PRESET, false);
            context.setScaffoldParser(new DungeonRoomScaffoldParser(null, context));
            try {
                DungeonRoom room = new DungeonRoom(context);
                if (countBlockingDoors(room) > 0) return room;
            } catch (Throwable t) {
                context.cleanup();
            }
        }
        return null;
    }

    private static int countBlockingDoors(DungeonRoom room) {
        int n = 0;
        for (DungeonMechanicState state : room.getMechanics().values()) {
            if (!(state instanceof DungeonDoorState) && !(state instanceof DungeonOnewayDoorState)) continue;
            try {
                if (((WorldMutatingMechanicState) state).isBlocking(room)) n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    @Test
    void alternativeHashesAreDeterministicAndOrdered() throws Exception {
        DungeonRoom room = roomWithDoors();
        assertNotNull(room, "no bundled room with a blocking door");
        int blockingDoors = countBlockingDoors(room);

        // algorithmSetting isn't part of the request hash, so null is fine here
        PathfindRequest request = new PathfindRequest(null, room.getDungeonRoomInfo(),
                Collections.emptySet(),
                Collections.singletonList(new OffsetVec3(1, 1, 1)));

        List<String> first = PrecalcFallback.alternativeRequestHashes(request, room);
        // one candidate per blocking door, then the all-doors-open candidate when >1
        assertEquals(blockingDoors + (blockingDoors > 1 ? 1 : 0), first.size());
        // same request -> same hash order on every call (HashSet iteration used to shuffle it)
        for (int i = 0; i < 3; i++) {
            assertEquals(first, PrecalcFallback.alternativeRequestHashes(request, room));
        }
        // none of the candidates may collide with the primary request hash
        assertFalse(first.contains(request.getHash()));
        // and every candidate is distinct
        assertEquals(first.size(), first.stream().distinct().count());
    }
}

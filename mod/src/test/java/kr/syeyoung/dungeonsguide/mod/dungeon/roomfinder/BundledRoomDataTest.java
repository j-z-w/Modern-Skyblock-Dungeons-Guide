package kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder;

import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BundledRoomDataTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
    }

    @Test
    void bundledRoomsConvertOnLoad() throws Exception {
        Path dir = Files.createTempDirectory("dg-roomdatas");
        long start = System.currentTimeMillis();
        DungeonRoomInfoRegistry.loadAll(dir.toFile());
        long elapsed = System.currentTimeMillis() - start;

        List<DungeonRoomInfo> rooms = DungeonRoomInfoRegistry.getRegistered();
        System.out.println("loaded " + rooms.size() + " rooms in " + elapsed + "ms");
        // loadAll swallows per-file failures, so compare against the index instead of a rough lower bound
        long indexed;
        try (InputStream index = DungeonRoomInfoRegistry.class.getResourceAsStream("/roomdata/datas.txt")) {
            indexed = new String(index.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.trim().endsWith(".roomdata.cbor")).count();
        }
        assertEquals(indexed, rooms.size(), "every room in datas.txt should load");

        for (DungeonRoomInfo dri : rooms) {
            assertEquals(2, dri.getFormatVersion(), dri.getName() + " (" + dri.getUuid() + ")");
            assertNotNull(dri.getPalette(), dri.getName());
            assertNotNull(dri.getWorld(), dri.getName());
            int nonNull = 0;
            for (int i = 0; i < dri.getPalette().size(); i++) {
                BlockState state = dri.getPaletteState(i); // builds getPaletteStates(), throws if it can't
                assertNotNull(state, dri.getName() + " palette[" + i + "]=" + dri.getPalette().get(i));
                if (!state.isAir()) nonNull++;
            }
            System.out.println(dri.getName() + " shape=" + Integer.toHexString(dri.getShape() & 0xFFFF)
                    + " " + dri.getWidth() + "x" + dri.getLength()
                    + " palette=" + dri.getPalette().size() + " (" + nonNull + " non-air)"
                    + " mechanics=" + dri.getMechanics().size()
                    + " secrets=" + dri.getTotalSecrets());
            assertTrue(nonNull > 0, dri.getName() + " converted to nothing but air");
        }
    }
}

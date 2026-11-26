package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonMapLayout;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assumptions;

import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

// shared bootstrap for tests that need a real DungeonRoom: mirrors
// SmartRouteRegressionTest's setup (read-only config dir, bundled roomdata,
// DRIWorld-backed room with a live processor)
public final class TestRooms {
    private static boolean booted;
    private static PathfindPreset defaultPreset;

    private TestRooms() {}

    public static PathfindPreset defaultPreset() {
        return defaultPreset;
    }

    public static synchronized void boot() throws Exception {
        if (booted) return;
        String configDirProperty = System.getProperty("dg.configDir");
        Assumptions.assumeTrue(configDirProperty != null, "dg.configDir not set, skipping");
        File configDir = new File(configDirProperty);

        McBootstrap.boot();

        Field configDirField = Main.class.getDeclaredField("configDir");
        configDirField.setAccessible(true);
        configDirField.set(null, configDir);

        new DungeonsGuide();

        if (PathfindPrecalculationRegistry.getINSTANCE() == null)
            new PathfindPrecalculationRegistry(new File(configDir, "precalculations"), false);
        if (PathfindPresetRegistry.getINSTANCE() == null)
            new PathfindPresetRegistry(new File(configDir, "presets"));

        for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
            if (preset.getFile() != null && preset.getFile().getName().equals("default.json"))
                defaultPreset = preset;
        }
        if (defaultPreset == null) {
            for (PathfindPreset preset : PathfindPresetRegistry.getINSTANCE().getLoadedPresets()) {
                if (preset != PathfindPresetRegistry.DEFAULT_PRESET) defaultPreset = preset;
            }
        }
        if (defaultPreset == null) defaultPreset = PathfindPresetRegistry.DEFAULT_PRESET;

        DungeonRoomInfoRegistry.loadAll(Files.createTempDirectory("dg-roomdatas").toFile());
        new TSPCacheRegistry(Files.createTempDirectory("dg-tspcache").toFile(), false);
        booted = true;
    }

    public static DungeonRoom buildRoom(RoomPreset roomPreset, DungeonRoomInfo info) {
        DRIWorld driWorld = new DRIWorld(info, roomPreset.getParent());
        DungeonContext fakeContext = new DungeonContext("TEST DG", driWorld, roomPreset.getParent(), false);
        DungeonMapLayout dungeonMapLayout = new DungeonMapLayout(
                new Dimension(16, 16), 5, new Point(0, 0), new BlockPos(0, 70, 0));
        fakeContext.setScaffoldParser(new DungeonRoomScaffoldParser(dungeonMapLayout, fakeContext));
        DungeonRoom room = new DungeonRoom(fakeContext);
        // see SmartRouteRegressionTest: updateRoomProcessor tails with a Minecraft
        // prefetch that NPEs headless - roomProcessor is already assigned by then
        try {
            room.updateRoomProcessor();
        } catch (NullPointerException | NoClassDefFoundError ignored) {
        }
        return room;
    }

    // any registered room whose bounds footprint isn't square (2+ unit rooms):
    // needed because rotation-inverse bugs only show when xLen != zLen
    public static DungeonRoomInfo nonSquareRoom() {
        for (DungeonRoomInfo info : DungeonRoomInfoRegistry.getRegistered()) {
            try {
                if (info.getBlocks()[0].length != info.getBlocks().length) return info;
            } catch (Throwable ignored) {}
        }
        return DungeonRoomInfoRegistry.getRegistered().get(0);
    }

    public static DungeonRoomInfo anyRoom() {
        return DungeonRoomInfoRegistry.getRegistered().get(0);
    }

    public static RoomPreset presetFor(DungeonRoomInfo info) {
        RoomPreset preset = defaultPreset.getRoomPreset(info.getUuid());
        return preset == null ? new RoomPreset(defaultPreset, info.getUuid()) : preset;
    }
}

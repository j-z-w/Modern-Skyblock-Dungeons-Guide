package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Validates packaged Walk+Etherwarp / Breaker+Etherwarp presets in the generation
// output dir: JSON loads, every registered .pfres resolves to a real file, room
// coverage matches the on-disk set, and a missing preset id falls back to Default.
public class LocalPresetValidationTest {

    private static final File outDir = new File(System.getProperty("dg.genOut",
            System.getProperty("user.home") + "/dg-presets"));

    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
        new DungeonsGuide();
    }

    @Test
    void missingPresetFallsBackToDefault() {
        // FeaturePrecalcList.getSelectedPreset relies on this: unknown id -> null -> DEFAULT_PRESET
        assertNull(PathfindPresetRegistry.getINSTANCE().getPreset("preset-that-does-not-exist"));
        assertNotNull(PathfindPresetRegistry.DEFAULT_PRESET);
    }

    @Test
    void packagedPresetsLoadAndCoverRooms() throws Exception {
        for (String mode : new String[]{"walk-etherwarp", "breaker-etherwarp"}) {
            File presetFile = new File(outDir, "presets/" + mode + ".json");
            Assumptions.assumeTrue(presetFile.exists(), mode + " not packaged yet");

            JsonObject json;
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(presetFile))) {
                json = JsonParser.parseReader(r).getAsJsonObject();
            }
            PathfindPreset preset = PathfindPreset.loadFromJson(json);
            assertNotNull(preset.getPresetId());
            assertNotNull(preset.getAlgorithmSetting());
            assertTrue(preset.getAlgorithmSetting().isRouteEtherwarp(),
                    mode + " preset must route etherwarp");
            if (mode.equals("breaker-etherwarp")) {
                assertTrue(preset.getAlgorithmSetting().isDungeonBreaker());
                assertTrue(preset.getAlgorithmSetting().getDigBudget() > 0);
                assertFalse(preset.getAlgorithmSetting().isStonkTeleport());
                assertFalse(preset.getAlgorithmSetting().isStonkEChest());
            }

            File precalcDir = new File(outDir, "precalculations/" + preset.getPresetId());
            Set<String> onDisk = new HashSet<>();
            File[] files = precalcDir.listFiles((d, n) -> n.endsWith(".pfres"));
            assertNotNull(files);
            for (File f : files) onDisk.add(f.getName().replace(".pfres", ""));
            assertFalse(onDisk.isEmpty());

            Set<String> registered = new HashSet<>();
            Set<UUID> rooms = new HashSet<>();
            for (RoomPreset rp : preset.getPresets().values()) {
                registered.addAll(rp.getPrecalculations());
                if (!rp.getPrecalculations().isEmpty()) rooms.add(rp.getRoomId());
            }
            // every .pfres on disk must be referenced by some room preset and vice versa
            assertEquals(onDisk, registered, mode + ": precalc ids on disk != registered in preset");
            System.out.println(mode + ": " + files.length + " precalcs across "
                    + rooms.size() + " rooms, id " + preset.getPresetId());
        }
    }
}

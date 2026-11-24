package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// opt-in (-Pdg.genOut): parse the header and load the node buffer of every packaged
// local-preset .pfres, so a truncated file from an interrupted generation run is caught
public class LocalPresetLoadAllTest {
    @BeforeAll
    static void bootstrap() {
        LocalPresetValidationTest.bootstrap();
    }

    @Test
    void everyPackagedPrecalcLoads() {
        String out = System.getProperty("dg.genOut");
        Assumptions.assumeTrue(out != null, "requires -Pdg.genOut=<output root>");
        File root = new File(out, "precalculations");
        File[] presetDirs = root.listFiles(File::isDirectory);
        Assumptions.assumeTrue(presetDirs != null && presetDirs.length > 0, "no packaged precalculations under " + root);

        List<String> bad = new ArrayList<>();
        int loaded = 0;
        for (File dir : presetDirs) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".pfres"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    PathfindPrecalculation p = new PathfindPrecalculation(f);
                    if (!p.loadBlocking()) bad.add(dir.getName() + "/" + f.getName() + ": loadBlocking=false");
                    else loaded++;
                    p.evictBuffer();
                } catch (Throwable t) {
                    bad.add(dir.getName() + "/" + f.getName() + ": " + t);
                }
            }
        }
        System.out.println("local preset load-all: " + loaded + " loaded, " + bad.size() + " bad");
        for (String b : bad) System.out.println("  BAD " + b);
        assertTrue(bad.isEmpty(), bad.size() + " precalcs failed to load");
    }
}

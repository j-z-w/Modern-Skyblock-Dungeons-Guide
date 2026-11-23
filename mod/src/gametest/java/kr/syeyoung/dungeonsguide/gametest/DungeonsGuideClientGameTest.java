package kr.syeyoung.dungeonsguide.gametest;

import kr.syeyoung.dungeonsguide.mod.utils.DGPerf;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Path;

/**
 * Smoke test for the 26.3 port: loads a singleplayer world, toggles
 * `dgdebug renderstates`, opens /dg, screenshots each step.
 * Run with :mod:runClient -Pdg.clientGametest=true -Pdg.devauth=false
 *
 * With -Ddg.perf.benchmark (i.e. -Pdg.perfBenchmark=true) it instead runs the route
 * rendering benchmark in a flat world: N real precalculated routes through the production
 * display engines, DGPerf counters sampled over a measurement window, report in
 * runtime/dgperf-<mode>.txt. Combine with -Pdg.noopt=true for the simulated-BEFORE numbers.
 */
public class DungeonsGuideClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path screenshots = Path.of("screenshots");
        if (Boolean.getBoolean("dg.perf.benchmark")) {
            runPerfBenchmark(context, screenshots);
            return;
        }
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            // let chunks render before the baseline shot
            context.waitTicks(40);
            context.takeScreenshot(TestScreenshotOptions.of("world").withDestinationDir(screenshots));

            // DG client command: toggle render-state recording, then let the test shapes draw
            context.runOnClient(minecraft -> minecraft.getConnection().sendCommand("dgdebug renderstates"));
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("renderstates").withDestinationDir(screenshots));

            // /dg opens the config gui on the next tick
            context.runOnClient(minecraft -> minecraft.getConnection().sendCommand("dg"));
            context.waitTicks(60);
            context.takeScreenshot(TestScreenshotOptions.of("dg_gui").withDestinationDir(screenshots));
        }
    }

    // mode comes from -Ddg.perf.mode (neo | classic | both), default neo: the worst case from the brief
    private void runPerfBenchmark(ClientGameTestContext context, Path screenshots) {
        String mode = System.getProperty("dg.perf.mode", "neo");
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    for (WorldCreationUiState.WorldTypeEntry entry : state.getAltPresetList()) {
                        if (entry.preset().is(WorldPresets.FLAT)) {
                            state.setWorldType(entry);
                            break;
                        }
                    }
                    state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                })
                .create()) {
            world.getConnection().waitForChunksRender();

            // load real precalculated routes, drop a dark floor under them for contrast,
            // then stand on the first route looking down the path: the ribbons are thin
            // and horizontal, so they only resolve in screenshots from a player-eye view
            context.runOnClient(minecraft -> {
                DGPerfBenchmark.install();
                System.out.println("[DGBENCH] " + DGPerfBenchmark.load(8));
            });
            double[] b = DGPerfBenchmark.bounds();
            int floorY = (int) Math.floor(b[1]) - 3;
            world.getServer().runCommand(String.format(
                    "fill %d %d %d %d %d %d minecraft:black_concrete",
                    (int) b[0] - 10, floorY, (int) b[2] - 10, (int) b[3] + 10, floorY, (int) b[5] + 10));
            double[] view = DGPerfBenchmark.viewPose();
            context.runOnClient(minecraft -> minecraft.getConnection().sendCommand(
                    String.format("tp @s %.3f %.3f %.3f %.1f %.1f", view[0], view[1], view[2], view[3], view[4])));
            context.waitTicks(20);
            context.runOnClient(minecraft -> DGPerfBenchmark.enable(mode));
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("perf_" + mode).withDestinationDir(screenshots));

            // warmup: caches, JIT, buffers; then a clean measurement window
            context.waitTicks(100);
            final long[] window = new long[2];
            context.runOnClient(minecraft -> {
                DGPerf.reset();
                window[0] = System.nanoTime();
            });
            context.waitTicks(300);
            context.runOnClient(minecraft -> {
                window[1] = System.nanoTime();
                DGPerfBenchmark.disable();
            });
            try {
                DGPerfBenchmark.dumpReport(Path.of("dgperf-" + mode + ".txt"), mode, window[1] - window[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

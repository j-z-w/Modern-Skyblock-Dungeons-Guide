package kr.syeyoung.dungeonsguide.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.nio.file.Path;

/**
 * Smoke test for the 26.3 port: loads a singleplayer world, toggles
 * `dgdebug renderstates`, opens /dg, screenshots each step.
 * Run with :mod:runClient -Pdg.clientGametest=true -Pdg.devauth=false
 */
public class DungeonsGuideClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path screenshots = Path.of("screenshots");
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
}

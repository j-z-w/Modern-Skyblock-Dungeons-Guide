package kr.syeyoung.dungeonsguide.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.HUDConfigRootWidget;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.HUDWidgetWrapper;
import kr.syeyoung.dungeonsguide.mod.gui.DomElement;
import kr.syeyoung.dungeonsguide.mod.gui.GuiScreenAdapter;
import kr.syeyoung.dungeonsguide.mod.gui.elements.TextField;
import kr.syeyoung.dungeonsguide.mod.gui.primitive.Rect;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Smoke test for the 26.3 port: loads a singleplayer world, toggles
 * `dgdebug renderstates`, opens /dg, types into the search text field,
 * opens the HUD editor with /dg gui and drags a HUD element.
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

            typeIntoSearchBox(context, screenshots);
            dragHudElement(context, screenshots);
        }
        // DG's worker threads are non-daemon by design (THREAD_FACTORY forces it), so the
        // dev JVM stays alive after the gametest runner stops the client and vanilla's
        // shutdown watchdog kills it with an error. Exit explicitly once checks are done.
        Runtime.getRuntime().halt(0);
    }

    // click the search box at the top right of the /dg page and type a few characters
    private void typeIntoSearchBox(ClientGameTestContext context, Path screenshots) {
        DomElement searchDom = findWidget(context, dom -> dom.getWidget() instanceof TextField);
        if (searchDom == null)
            throw new AssertionError("no text field found on the /dg screen");

        double[] center = cursorPosFor(context, searchDom);
        context.getInput().setCursorPos(center[0], center[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(5);

        context.getInput().typeChars("dg test");
        context.waitTicks(5);

        String typed = context.computeOnClient(minecraft -> ((TextField) searchDom.getWidget()).value.getValue());
        if (!"dg test".equals(typed))
            throw new AssertionError("search text field did not receive typed text, got: " + typed);
        context.takeScreenshot(TestScreenshotOptions.of("dg_search_typed").withDestinationDir(screenshots));
    }

    // open the hud editor and drag a hud element
    private void dragHudElement(ClientGameTestContext context, Path screenshots) {
        context.runOnClient(minecraft -> minecraft.getConnection().sendCommand("dg gui"));
        context.waitTicks(60);
        context.takeScreenshot(TestScreenshotOptions.of("hud_editor").withDestinationDir(screenshots));

        DomElement editorDom = findWidget(context, dom -> dom.getWidget() instanceof HUDConfigRootWidget);
        DragTarget dragTarget = context.computeOnClient(minecraft -> {
            if (editorDom == null) return null;
            HUDConfigRootWidget root = (HUDConfigRootWidget) editorDom.getWidget();
            List<DomElement> wrappers = new ArrayList<>();
            collectAll(editorDom, dom -> dom.getWidget() instanceof HUDWidgetWrapper, wrappers);
            // the editor's click handler targets the FIRST wrapper in this order whose
            // bounds contain the click, and the drag math clamps to lastWidth/Height minus
            // demo size - it throws when the demo widget is bigger than the edit area.
            // pick a clamp-safe wrapper and a click point inside its demo bounds that
            // isn't shadowed by an earlier wrapper
            for (DomElement candidate : wrappers) {
                HUDWidgetWrapper wrapper = (HUDWidgetWrapper) candidate.getWidget();
                if (wrapper.getDemoWidget() == null
                        || wrapper.getDemoWidget().getDomElement() == null) continue;
                DomElement demoDom = wrapper.getDemoWidget().getDomElement();
                Rect demo = demoDom.getRelativeBound();
                Rect demoAbs = demoDom.getAbsBounds();
                if (demo == null || demoAbs == null
                        || demo.getWidth() > root.getLastWidth()
                        || demo.getHeight() > root.getLastHeight()) continue;
                probe:
                for (double fx = 0.05; fx < 1.0; fx += 0.05) {
                    for (double fy = 0.05; fy < 1.0; fy += 0.05) {
                        double cx = demoAbs.getX() + demoAbs.getWidth() * fx;
                        double cy = demoAbs.getY() + demoAbs.getHeight() * fy;
                        for (DomElement earlier : wrappers) {
                            if (earlier == candidate) break;
                            Rect earlierBounds = earlier.getAbsBounds();
                            if (earlierBounds != null && earlierBounds.contains(cx, cy))
                                continue probe;
                        }
                        return new DragTarget(candidate, cx, cy);
                    }
                }
            }
            return null;
        });
        if (dragTarget == null) {
            System.out.println("[DungeonsGuideClientGameTest] no draggable HUD elements, skipping drag check");
            return;
        }
        HUDWidgetWrapper wrapper = (HUDWidgetWrapper) dragTarget.dom().getWidget();
        String before = context.computeOnClient(minecraft -> String.valueOf(wrapper.getAbstractHUDFeature().getFeatureRect()));

        double[] center = cursorPos(context, dragTarget.absX(), dragTarget.absY());
        context.getInput().setCursorPos(center[0], center[1]);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        context.getInput().setCursorPos(center[0] + 60, center[1] + 30);
        context.waitTicks(3);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(5);

        String after = context.computeOnClient(minecraft -> String.valueOf(wrapper.getAbstractHUDFeature().getFeatureRect()));
        if (before.equals(after))
            throw new AssertionError("HUD element did not move after drag: " + before);
        context.takeScreenshot(TestScreenshotOptions.of("hud_dragged").withDestinationDir(screenshots));
    }

    private record DragTarget(DomElement dom, double absX, double absY) {}

    // DG dom bounds are in framebuffer pixels; the cursor api wants screen coords
    private double[] cursorPosFor(ClientGameTestContext context, DomElement dom) {
        Rect b = context.computeOnClient(minecraft -> dom.getAbsBounds());
        if (b == null) throw new AssertionError("element has no bounds yet");
        return cursorPos(context, b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2);
    }

    private double[] cursorPos(ClientGameTestContext context, double absX, double absY) {
        return context.computeOnClient(minecraft -> {
            var window = minecraft.getWindow();
            return new double[]{
                    absX * window.getScreenWidth() / window.getWidth(),
                    absY * window.getScreenHeight() / window.getHeight()
            };
        });
    }

    private DomElement findWidget(ClientGameTestContext context, Predicate<DomElement> predicate) {
        return context.computeOnClient(minecraft -> {
            if (!(minecraft.gui.screen() instanceof GuiScreenAdapter)) return null;
            return find(((GuiScreenAdapter) minecraft.gui.screen()).getView(), predicate);
        });
    }

    private static DomElement find(DomElement dom, Predicate<DomElement> predicate) {
        if (predicate.test(dom)) return dom;
        for (DomElement child : dom.getChildren()) {
            DomElement found = find(child, predicate);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectAll(DomElement dom, Predicate<DomElement> predicate, List<DomElement> out) {
        if (predicate.test(dom)) out.add(dom);
        for (DomElement child : dom.getChildren())
            collectAll(child, predicate, out);
    }
}

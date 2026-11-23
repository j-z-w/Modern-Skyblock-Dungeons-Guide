package kr.syeyoung.dungeonsguide.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.HUDConfigRootWidget;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.HUDWidgetWrapper;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngine;
import kr.syeyoung.dungeonsguide.mod.gui.DomElement;
import kr.syeyoung.dungeonsguide.mod.gui.GuiScreenAdapter;
import kr.syeyoung.dungeonsguide.mod.gui.elements.TextField;
import kr.syeyoung.dungeonsguide.mod.gui.primitive.Rect;
import kr.syeyoung.dungeonsguide.mod.pathfinding.PathfindResult;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Smoke test for the 26.3 port: loads a singleplayer world, toggles
 * `dgdebug renderstates`, opens /dg, types into the search text field,
 * opens the HUD editor with /dg gui and drags a HUD element.
 * Also drives NeoRouteDisplayEngine's ribbon/arrow path with a synthetic route
 * over a flat platform (depth-tested, see-through, and etherwarp segments).
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

            neoRouteLineTest(context, world, screenshots);

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

    // --- NeoRoute ribbon/arrow scenario -------------------------------------
    // Drives the same static calls drawLinesPathfindNode makes, over a flat stone
    // platform with a wall the route passes behind: WALK (depth-tested),
    // STONK_WALK (see-through) and an ETHERWARP segment (drawIcon).
    private static boolean neoRouteScenarioEnabled;
    private static boolean neoRouteListenerRegistered;
    private static List<NeoRouteDisplayEngine.PathSegment> neoWalk;
    private static List<NeoRouteDisplayEngine.PathSegment> neoStonk;
    private static List<NeoRouteDisplayEngine.PathSegment> neoEther;
    private static final AColor NEO_BG = new AColor(0x6400FFFF, true);
    private static final AColor NEO_ARROW = new AColor(0xFFFFFFFF, true);

    private void neoRouteLineTest(ClientGameTestContext context, TestSingleplayerContext world, Path screenshots) {
        // flat platform, open sky. Each line gets its own wall so through-wall bleed is
        // attributable: WALK (z=0) behind wall at x=8..9,z=-2..2; STONK (z=6) behind x=8..9,z=4..8;
        // ETHERWARP (z=-6) uncovered. Any arrow seen through the z=0 wall is a depth-test bug.
        world.getServer().runCommand("fill -12 95 -12 30 130 12 minecraft:air");
        world.getServer().runCommand("fill -12 95 -12 30 99 12 minecraft:stone");
        world.getServer().runCommand("fill 8 100 -2 9 104 2 minecraft:stone_bricks");
        world.getServer().runCommand("fill 8 100 4 9 104 8 minecraft:stone_bricks");
        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runCommand("tp @p 0 102.2 -1.5 -75 45");
        context.runOnClient(minecraft -> enableNeoRouteScenario());
        context.waitTicks(40);
        context.takeScreenshot(TestScreenshotOptions.of("neoroute_close").withDestinationDir(screenshots));

        // oblique far view: WALK behind the z=-2..2 wall must stay hidden, STONK shows through
        world.getServer().runCommand("tp @p -8 106 -12 -55 21");
        context.waitTicks(20);
        context.takeScreenshot(TestScreenshotOptions.of("neoroute_far").withDestinationDir(screenshots));

        // same views with 'Improved Transparency' (OIT path); top-down shows all three lines
        context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
        world.getServer().runCommand("tp @p 8 116 0 0 90");
        context.waitTicks(30);
        context.takeScreenshot(TestScreenshotOptions.of("neoroute_far_oit").withDestinationDir(screenshots));
        world.getServer().runCommand("tp @p 0 102.2 -1.5 -75 45");
        context.waitTicks(20);
        context.takeScreenshot(TestScreenshotOptions.of("neoroute_close_oit").withDestinationDir(screenshots));

        context.runOnClient(minecraft -> {
            minecraft.options.improvedTransparency().set(false);
            neoRouteScenarioEnabled = false;
        });
    }

    private static void enableNeoRouteScenario() {
        if (neoWalk == null) {
            // ribbon width/smoothness match the Neo Route defaults (lineWidth 0.3, lineSmooth 1.0)
            // routes hover ~0.1 over the floor like real pathfind nodes; quads exactly
            // coplanar with the surface would mask a depth-compare bug via equality
            neoWalk = NeoRouteDisplayEngine.transformPathfindResult(Arrays.asList(
                    node(0, 100.1, 0, PathfindResult.PathfindNode.NodeType.WALK),
                    node(6, 100.1, 0, PathfindResult.PathfindNode.NodeType.WALK),
                    node(6, 100.1, 1, PathfindResult.PathfindNode.NodeType.WALK),
                    node(16, 100.1, 1, PathfindResult.PathfindNode.NodeType.WALK)), 0.3, 1.0);
            neoStonk = NeoRouteDisplayEngine.transformPathfindResult(Arrays.asList(
                    node(0, 100.1, 6, PathfindResult.PathfindNode.NodeType.STONK_WALK),
                    node(16, 100.1, 6, PathfindResult.PathfindNode.NodeType.STONK_WALK)), 0.3, 1.0);
            neoEther = NeoRouteDisplayEngine.transformPathfindResult(Arrays.asList(
                    node(2, 100.1, -6, PathfindResult.PathfindNode.NodeType.ETHERWARP),
                    node(12, 101.1, -6, PathfindResult.PathfindNode.NodeType.ETHERWARP)), 0.3, 1.0);
        }
        neoRouteScenarioEnabled = true;
        if (neoRouteListenerRegistered) return;
        neoRouteListenerRegistered = true;
        DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(RenderWorldEvent.class, ListenerPriority.THIRD, e -> {
            if (!neoRouteScenarioEnabled) return EventProcessResult.COMPLETE;
            double animate = System.currentTimeMillis() / 1000.0;
            animate = animate - Math.floor(animate);
            animate *= -1;
            if (!neoWalk.isEmpty())
                NeoRouteDisplayEngine.drawLinesNormal(neoWalk, 0, neoWalk.size(),
                        PathfindResult.PathfindNode.NodeType.WALK, NEO_BG, NEO_ARROW, e, animate);
            if (!neoStonk.isEmpty())
                NeoRouteDisplayEngine.drawLinesNormal(neoStonk, 0, neoStonk.size(),
                        PathfindResult.PathfindNode.NodeType.STONK_WALK, NEO_BG, NEO_ARROW, e, animate);
            for (NeoRouteDisplayEngine.PathSegment segment : neoEther)
                NeoRouteDisplayEngine.drawIcon(40, segment, NEO_BG, NEO_ARROW, e, animate, true);
            return EventProcessResult.COMPLETE;
        });
    }

    private static PathfindResult.PathfindNode node(double x, double y, double z, PathfindResult.PathfindNode.NodeType type) {
        return new PathfindResult.PathfindNode(x, y, z, type);
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

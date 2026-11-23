package kr.syeyoung.dungeonsguide.mod.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStep;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStepTracker;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteVec3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step advancement is the only game-logic piece of the overlay and must work
 * without a client: all inputs are plain values (player pos, secret-found
 * flags, block-air probes, teleport landings).
 */
public class RouteStepTrackerTest {

    private static RouteAction act(RouteAction.Kind kind, double x, double y, double z) {
        return new RouteAction(kind, new RouteVec3(x, y, z));
    }

    private static RouteStep step(RouteStep.Secret secret, RouteAction... actions) {
        return new RouteStep(Arrays.asList(actions), secret);
    }

    private static final RouteVec3 FAR = new RouteVec3(-100, -100, -100);
    private static final boolean[] NONE = new boolean[0];

    @Test
    void passesLastPointAdvances() {
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(10, 70, 10)),
                        act(RouteAction.Kind.WALK, 5, 70, 5)),
                step(new RouteStep.Secret("item", new RouteVec3(20, 70, 10)),
                        act(RouteAction.Kind.WALK, 15, 70, 10)));
        RouteStepTracker t = new RouteStepTracker(steps);
        assertEquals(0, t.guideStep());
        t.tick(new RouteVec3(10, 70, 10), NONE, null);
        assertEquals(1, t.guideStep());
        assertTrue(t.isStepDone(0));
    }

    @Test
    void secretFoundAdvances() {
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(10, 70, 10)),
                        act(RouteAction.Kind.WALK, 5, 70, 5)),
                step(new RouteStep.Secret("item", new RouteVec3(20, 70, 10)),
                        act(RouteAction.Kind.WALK, 15, 70, 10)));
        RouteStepTracker t = new RouteStepTracker(steps);
        boolean[] found = new boolean[]{true, false};
        t.tick(FAR, found, null);
        assertEquals(1, t.guideStep());
    }

    @Test
    void breakerWaitsForAir() {
        RouteVec3 mine = new RouteVec3(10, 70, 10);
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(30, 70, 10)),
                        act(RouteAction.Kind.BREAKER, mine.x, mine.y, mine.z)),
                step(new RouteStep.Secret("item", new RouteVec3(30, 70, 11)),
                        act(RouteAction.Kind.WALK, 29, 70, 11)));
        final boolean[] air = {false};
        RouteStepTracker t = new RouteStepTracker(steps);
        // standing near the last point won't finish the step - it has a gate
        t.tick(new RouteVec3(0, 0, 0), NONE, p -> air[0]);
        assertEquals(0, t.guideStep());
        // mine the block -> step gates clear, then lastPoint proximity finishes it
        air[0] = true;
        t.tick(new RouteVec3(30, 70, 10), NONE, p -> air[0]);
        assertEquals(1, t.guideStep());
    }

    @Test
    void etherwarpCompletesOnTeleport() {
        RouteVec3 warp = new RouteVec3(20, 70, 20);
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(28, 70, 28)),
                        act(RouteAction.Kind.ETHERWARP, warp.x, warp.y, warp.z)),
                step(new RouteStep.Secret("item", new RouteVec3(30, 70, 30)),
                        act(RouteAction.Kind.WALK, 29, 70, 30)));
        RouteStepTracker t = new RouteStepTracker(steps);
        t.tick(new RouteVec3(0, 70, 0), NONE, null);
        assertEquals(0, t.guideStep());
        // landing far from the target doesn't count
        t.onTeleport(new RouteVec3(0, 70, 0));
        assertEquals(0, t.guideStep());
        // landing near the authored warp target finishes the gate action;
        // the step still completes via its last-point proximity fallback or gates
        t.onTeleport(warp);
        // the only gate cleared -> the whole step completed on the teleport
        assertTrue(t.isActionDone(0, 0));
        assertEquals(1, t.guideStep());
        // and stays there while the player isn't near the next step's secret
        t.tick(new RouteVec3(28, 70, 60), NONE, null);
        assertEquals(1, t.guideStep());
    }

    @Test
    void ambientStepsNeverGate() {
        List<RouteStep> steps = List.of(
                new RouteStep(List.of(act(RouteAction.Kind.MARKER, 1, 70, 1)),
                        new RouteStep.Secret("bat", new RouteVec3(1, 70, 1)), true),
                step(new RouteStep.Secret("item", new RouteVec3(10, 70, 10)),
                        act(RouteAction.Kind.WALK, 8, 70, 10)));
        RouteStepTracker t = new RouteStepTracker(steps);
        assertEquals(1, t.guideStep());
    }

    @Test
    void seedsIntoMiddleOfRoute() {
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(5, 70, 15)),
                        act(RouteAction.Kind.WALK, 4, 70, 5)),
                step(new RouteStep.Secret("item", new RouteVec3(15, 70, 15)),
                        act(RouteAction.Kind.WALK, 14, 70, 5)),
                step(new RouteStep.Secret("item", new RouteVec3(25, 70, 15)),
                        act(RouteAction.Kind.WALK, 24, 70, 5)));
        RouteStepTracker t = new RouteStepTracker(steps);
        // player spawns next to step 2's first point: earlier steps fast-forward
        t.tick(new RouteVec3(14.5, 70, 5), NONE, null);
        assertEquals(1, t.guideStep());
        assertTrue(t.isStepDone(0));
    }

    @Test
    void walkBreadcrumbsDontGate() {
        List<RouteStep> steps = List.of(
                step(null, act(RouteAction.Kind.WALK, 5, 70, 5),
                        act(RouteAction.Kind.WALK, 8, 70, 5),
                        act(RouteAction.Kind.MARKER, 11, 70, 5)),
                step(new RouteStep.Secret("item", new RouteVec3(15, 70, 5)),
                        act(RouteAction.Kind.WALK, 14, 70, 5)));
        RouteStepTracker t = new RouteStepTracker(steps);
        // no gate in step 0; passing its last point finishes it even though the
        // player never touched the first breadcrumbs
        t.tick(new RouteVec3(11, 70, 5), NONE, null);
        assertEquals(1, t.guideStep());
    }

    @Test
    void superboomClearsOnAir() {
        List<RouteStep> steps = List.of(
                step(new RouteStep.Secret("item", new RouteVec3(30, 70, 10)),
                        act(RouteAction.Kind.SUPERBOOM, 10, 70, 10)),
                step(new RouteStep.Secret("item", new RouteVec3(30, 70, 11)),
                        act(RouteAction.Kind.WALK, 29, 70, 11)));
        RouteStepTracker t = new RouteStepTracker(steps);
        t.tick(FAR, NONE, p -> false);
        assertEquals(0, t.guideStep());
        // wall blown open
        t.tick(new RouteVec3(30, 70, 10), NONE, p -> true);
        assertEquals(1, t.guideStep());
    }

    @Test
    void routeCompletesToMinusOne() {
        List<RouteStep> steps = Collections.singletonList(
                step(new RouteStep.Secret("item", new RouteVec3(10, 70, 10)),
                        act(RouteAction.Kind.WALK, 9, 70, 10)));
        RouteStepTracker t = new RouteStepTracker(steps);
        t.tick(new RouteVec3(10, 70, 10), NONE, null);
        assertEquals(-1, t.guideStep());
    }
}

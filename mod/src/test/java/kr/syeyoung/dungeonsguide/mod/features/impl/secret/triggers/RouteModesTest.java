package kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers;

import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RouteModesTest {

    // SimpleFeature's constructor is protected; subclass it so tests don't need the registry
    private static class TestMode extends SimpleFeature {
        TestMode(String key, boolean enabled) {
            super("category", "name", "description", key, enabled);
        }
    }

    private static List<TestMode> modes(boolean all, boolean one, boolean smart) {
        return Arrays.asList(
                new TestMode(RouteModes.ALL_BROWSE_KEY, all),
                new TestMode(RouteModes.AUTO_UPON_ENTER_KEY, one),
                new TestMode(RouteModes.SMART_ROUTE_KEY, smart));
    }

    @Test
    void enablingOneDisablesTheOthers() {
        List<TestMode> modes = modes(true, true, false);
        RouteModes.enforceExclusive(modes, modes.get(2));
        modes.get(2).setEnabled(true);
        assertFalse(modes.get(0).isEnabled());
        assertFalse(modes.get(1).isEnabled());
        assertTrue(modes.get(2).isEnabled());
    }

    @Test
    void reconcileKeepsOnlySmartRoute() {
        List<TestMode> modes = modes(true, true, true);
        RouteModes.reconcile(modes, modes.get(2));
        assertFalse(modes.get(0).isEnabled());
        assertFalse(modes.get(1).isEnabled());
        assertTrue(modes.get(2).isEnabled());
    }

    @Test
    void reconcileEnablesSmartRouteWhenOthersEnabled() {
        // config carried over from an older build: both old modes on, smart route off
        List<TestMode> modes = modes(true, true, false);
        RouteModes.reconcile(modes, modes.get(2));
        assertFalse(modes.get(0).isEnabled());
        assertFalse(modes.get(1).isEnabled());
        assertTrue(modes.get(2).isEnabled());
    }

    @Test
    void reconcileLeavesSingleModeAlone() {
        List<TestMode> modes = modes(false, true, false);
        RouteModes.reconcile(modes, modes.get(2));
        assertFalse(modes.get(0).isEnabled());
        assertTrue(modes.get(1).isEnabled());
        assertFalse(modes.get(2).isEnabled());
    }

    @Test
    void reconcileLeavesAllDisabledAlone() {
        List<TestMode> modes = modes(false, false, false);
        RouteModes.reconcile(modes, modes.get(2));
        assertFalse(modes.get(0).isEnabled());
        assertFalse(modes.get(1).isEnabled());
        assertFalse(modes.get(2).isEnabled());
    }
}

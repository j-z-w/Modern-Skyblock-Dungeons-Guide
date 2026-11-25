package kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle;

import com.google.gson.JsonObject;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.PathfindLineProperties;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngineLineProperties;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.linestyle.arrowpath.NeoRouteDisplayEngineRegistration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoRouteLineSettingsTest {
    @Test
    void builderDefaultsShowEtherwarpIconsAtDefaultThickness() {
        NeoRouteDisplayEngineLineProperties properties = NeoRouteDisplayEngineLineProperties.builder().build();

        assertTrue(properties.isEtherwarpIcons());
        assertEquals(0.3, properties.getEtherwarpLineWidth());
    }

    @Test
    void editsReachTheSharedLiveSettingsObjectAndSerialize() {
        NeoRouteDisplayEngineRegistration.ArrowPathDisplayEngineSetting setting =
                new NeoRouteDisplayEngineRegistration.ArrowPathDisplayEngineSetting();

        NeoRouteDisplayEngineLineProperties live = setting.getSettings();
        assertEquals(0.3, live.getWidth());
        assertEquals(3.0f, live.getEtherwarpTracerWidth());
        assertTrue(live.isEtherwarpIcons());
        assertEquals(0.3, live.getEtherwarpLineWidth());

        setting.<Double>getParameter("lineWidth").setValue(0.12);
        setting.<Double>getParameter("etherwarpTracerWidth").setValue(9.0);
        setting.<Boolean>getParameter("etherwarpIcons").setValue(false);
        setting.<Double>getParameter("etherwarpLineWidth").setValue(0.18);

        NeoRouteDisplayEngineLineProperties refreshed = setting.getSettings();
        assertSame(live, refreshed);
        assertEquals(0.12, live.getWidth());
        assertEquals(9.0f, live.getEtherwarpTracerWidth());
        assertFalse(live.isEtherwarpIcons());
        assertEquals(0.18, live.getEtherwarpLineWidth());

        JsonObject serialized = setting.serialize();
        assertEquals(0.12, serialized.get("lineWidth").getAsDouble());
        assertEquals(9.0, serialized.get("etherwarpTracerWidth").getAsDouble());
        assertFalse(serialized.get("etherwarpIcons").getAsBoolean());
        assertEquals(0.18, serialized.get("etherwarpLineWidth").getAsDouble());
    }

    @Test
    void inheritedSettingsSerializeTheEffectiveParentValues() {
        PathfindLineProperties parent = new PathfindLineProperties(
                "category", "parent", "description", "test.parent", false, null);
        PathfindLineProperties child = new PathfindLineProperties(
                "category", "child", "description", "test.child", true, parent);

        ((NeoRouteDisplayEngineRegistration.ArrowPathDisplayEngineSetting) parent.getSetting())
                .<Double>getParameter("lineWidth").setValue(0.12);

        JsonObject saved = child.saveConfig();
        assertEquals(0.12, saved.getAsJsonObject("data").get("lineWidth").getAsDouble());
    }
}

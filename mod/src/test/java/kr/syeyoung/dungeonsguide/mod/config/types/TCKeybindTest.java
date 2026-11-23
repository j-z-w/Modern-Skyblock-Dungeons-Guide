package kr.syeyoung.dungeonsguide.mod.config.types;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.utils.GlfwKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TCKeybindTest {
    @BeforeAll
    static void boot() {
        McBootstrap.boot();
    }

    private static void assertStableRoundTrip(InputConstants.Key key) {
        JsonElement first = TCKeybind.INSTANCE.serialize(key);
        InputConstants.Key k1 = TCKeybind.INSTANCE.deserialize(first);
        JsonElement second = TCKeybind.INSTANCE.serialize(k1);
        InputConstants.Key k2 = TCKeybind.INSTANCE.deserialize(second);
        assertEquals(key, k1, "first round-trip of " + key.getName());
        assertEquals(key, k2, "second round-trip of " + key.getName());
        assertEquals(first, second, "reserialization of " + key.getName());
    }

    @Test
    void roundTripIsStable() {
        // sdl scancodes: a=4, space=44, f5=62, left shift=225
        assertStableRoundTrip(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_A));
        assertStableRoundTrip(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_SPACE));
        assertStableRoundTrip(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F5));
        assertStableRoundTrip(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_LSHIFT));
        assertStableRoundTrip(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT));
        assertStableRoundTrip(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT));
        assertStableRoundTrip(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertStableRoundTrip(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_4));
        assertStableRoundTrip(InputConstants.UNKNOWN);
    }

    @Test
    void serializedFormIsCanonicalName() {
        JsonElement mouse = TCKeybind.INSTANCE.serialize(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals("name", mouse.getAsJsonObject().get("type").getAsString());
        assertEquals("key.mouse.left", mouse.getAsJsonObject().get("name").getAsString());
    }

    private static JsonObject legacy(String type, int code) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("code", code);
        return object;
    }

    @Test
    void legacyGlfwKeycodeMigrates() {
        // "key" entries were glfw keycodes pre-26.3
        assertEquals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_A),
                TCKeybind.INSTANCE.deserialize(legacy("key", GlfwKeys.GLFW_KEY_A)));
        assertEquals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F5),
                TCKeybind.INSTANCE.deserialize(legacy("key", GlfwKeys.GLFW_KEY_F5)));
        assertEquals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_RSHIFT),
                TCKeybind.INSTANCE.deserialize(legacy("key", GlfwKeys.GLFW_KEY_RIGHT_SHIFT)));
        // glfw keys without an sdl scancode become unknown
        assertEquals(InputConstants.UNKNOWN,
                TCKeybind.INSTANCE.deserialize(legacy("key", GlfwKeys.GLFW_KEY_F25)));
    }

    @Test
    void legacyGlfwMouseButtonMigrates() {
        // "mouse" entries were 0-based glfw buttons pre-26.3: 0=left, 1=right, 2=middle, 3..=extra
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT),
                TCKeybind.INSTANCE.deserialize(legacy("mouse", GlfwKeys.GLFW_MOUSE_BUTTON_LEFT)));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT),
                TCKeybind.INSTANCE.deserialize(legacy("mouse", GlfwKeys.GLFW_MOUSE_BUTTON_RIGHT)));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE),
                TCKeybind.INSTANCE.deserialize(legacy("mouse", GlfwKeys.GLFW_MOUSE_BUTTON_MIDDLE)));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_4),
                TCKeybind.INSTANCE.deserialize(legacy("mouse", GlfwKeys.GLFW_MOUSE_BUTTON_4)));
        assertEquals(InputConstants.UNKNOWN,
                TCKeybind.INSTANCE.deserialize(legacy("mouse", 8)));
    }

    @Test
    void legacyNativeScancodeIsUnknown() {
        // "scancode" entries were os-native scancodes pre-26.3, unportable to sdl
        assertEquals(InputConstants.UNKNOWN, TCKeybind.INSTANCE.deserialize(legacy("scancode", 30)));
    }

    @Test
    void unknownBindNeverMatches() {
        // S10: an unmapped keybind (UNKNOWN / null) must not fire on any key event -
        // otherwise every unrelated keypress triggered route regeneration/step-skips
        InputConstants.Key someKey = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_A);
        assertFalse(TCKeybind.matches(InputConstants.UNKNOWN, someKey));
        assertFalse(TCKeybind.matches(null, someKey));
        // even an UNKNOWN key event can't match the UNKNOWN bind
        assertFalse(TCKeybind.matches(InputConstants.UNKNOWN, InputConstants.UNKNOWN));
        // a real bind still matches exactly its own event
        assertTrue(TCKeybind.matches(someKey, someKey));
        assertFalse(TCKeybind.matches(someKey,
                InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_B)));
    }

    @Test
    void legacyLwjgl2PrimitiveMigrates() {
        // bare ints were lwjgl2 key codes pre-26.1; 30 = lwjgl2 KEY_A, -100 + button = mouse
        assertEquals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_A),
                TCKeybind.INSTANCE.deserialize(new JsonPrimitive(30)));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT),
                TCKeybind.INSTANCE.deserialize(new JsonPrimitive(-100)));
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT),
                TCKeybind.INSTANCE.deserialize(new JsonPrimitive(-99)));
    }
}

/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.config.types;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.configv3.ParameterItem;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.gui.BindableAttribute;
import kr.syeyoung.dungeonsguide.mod.gui.Widget;
import kr.syeyoung.dungeonsguide.mod.gui.xml.AnnotatedImportOnlyWidget;
import kr.syeyoung.dungeonsguide.mod.gui.xml.annotations.Bind;
import kr.syeyoung.dungeonsguide.mod.gui.xml.annotations.On;
import kr.syeyoung.dungeonsguide.mod.utils.GlfwKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.input.KeyEvent;

public class TCKeybind implements FeatureTypeHandler<InputConstants.Key> {
    public static final TCKeybind INSTANCE = new TCKeybind();
    @Override
    public InputConstants.Key deserialize(JsonElement element) {
        // configs before 26.1 saved a bare lwjgl2 key code
        if (element.isJsonPrimitive()) return fromLegacy(element.getAsInt());

        JsonObject object = element.getAsJsonObject();
        switch (object.get("type").getAsString()) {
            // 26.3+: vanilla's canonical key name, covers keyboard + mouse + key.keyboard.unknown
            case "name": {
                JsonElement name = object.get("name");
                return name == null ? InputConstants.UNKNOWN : InputConstants.getKey(name.getAsString());
            }
            // "key" was a glfw keycode pre-26.3; KEYBOARD keys are sdl scancodes now
            case "key": return toKeyboardKey(GlfwKeys.keycodeToScancode(object.get("code").getAsInt()));
            // "scancode" was an os-native scancode pre-26.3, not an sdl scancode; can't port it
            case "scancode": return InputConstants.UNKNOWN;
            // "mouse" was a 0-based glfw button pre-26.3; sdl buttons are 1-based
            case "mouse": {
                int sdl = GlfwKeys.mouseButtonToSdl(object.get("code").getAsInt());
                return sdl == -1 ? InputConstants.UNKNOWN : InputConstants.Type.MOUSE.getOrCreate(sdl);
            }
        }
        return InputConstants.UNKNOWN;
    }

    @Override
    public JsonElement serialize(InputConstants.Key element) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "name");
        object.addProperty("name", element.getName());
        return object;
    }

    private static InputConstants.Key toKeyboardKey(int scancode) {
        return scancode == -1 ? InputConstants.UNKNOWN : InputConstants.Type.KEYBOARD.getOrCreate(scancode);
    }

    // lwjgl2: keyboard codes, -100 + button for mouse, typed char + 256 for keys lwjgl2 didn't know
    private static InputConstants.Key fromLegacy(int legacy) {
        if (legacy < 0) {
            int sdl = GlfwKeys.mouseButtonToSdl(legacy + 100);
            return sdl == -1 ? InputConstants.UNKNOWN : InputConstants.Type.MOUSE.getOrCreate(sdl);
        }
        if (legacy > 256) {
            char c = Character.toUpperCase((char) (legacy - 256));
            // glfw letter/digit keycodes are the ascii values, so this doubles as the glfw keycode
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return toKeyboardKey(GlfwKeys.keycodeToScancode(c));
            return InputConstants.UNKNOWN;
        }
        int glfw = legacy < LEGACY_TO_GLFW.length ? LEGACY_TO_GLFW[legacy] : GlfwKeys.GLFW_KEY_UNKNOWN;
        if (glfw == GlfwKeys.GLFW_KEY_UNKNOWN) return InputConstants.UNKNOWN;
        return toKeyboardKey(GlfwKeys.keycodeToScancode(glfw));
    }

    private static final int[] LEGACY_TO_GLFW = new int[256];
    static {
        java.util.Arrays.fill(LEGACY_TO_GLFW, GlfwKeys.GLFW_KEY_UNKNOWN);
        int[][] pairs = {
                {1, GlfwKeys.GLFW_KEY_ESCAPE},
                {2, GlfwKeys.GLFW_KEY_1}, {3, GlfwKeys.GLFW_KEY_2}, {4, GlfwKeys.GLFW_KEY_3}, {5, GlfwKeys.GLFW_KEY_4}, {6, GlfwKeys.GLFW_KEY_5},
                {7, GlfwKeys.GLFW_KEY_6}, {8, GlfwKeys.GLFW_KEY_7}, {9, GlfwKeys.GLFW_KEY_8}, {10, GlfwKeys.GLFW_KEY_9}, {11, GlfwKeys.GLFW_KEY_0},
                {12, GlfwKeys.GLFW_KEY_MINUS}, {13, GlfwKeys.GLFW_KEY_EQUAL}, {14, GlfwKeys.GLFW_KEY_BACKSPACE}, {15, GlfwKeys.GLFW_KEY_TAB},
                {16, GlfwKeys.GLFW_KEY_Q}, {17, GlfwKeys.GLFW_KEY_W}, {18, GlfwKeys.GLFW_KEY_E}, {19, GlfwKeys.GLFW_KEY_R}, {20, GlfwKeys.GLFW_KEY_T},
                {21, GlfwKeys.GLFW_KEY_Y}, {22, GlfwKeys.GLFW_KEY_U}, {23, GlfwKeys.GLFW_KEY_I}, {24, GlfwKeys.GLFW_KEY_O}, {25, GlfwKeys.GLFW_KEY_P},
                {26, GlfwKeys.GLFW_KEY_LEFT_BRACKET}, {27, GlfwKeys.GLFW_KEY_RIGHT_BRACKET}, {28, GlfwKeys.GLFW_KEY_ENTER}, {29, GlfwKeys.GLFW_KEY_LEFT_CONTROL},
                {30, GlfwKeys.GLFW_KEY_A}, {31, GlfwKeys.GLFW_KEY_S}, {32, GlfwKeys.GLFW_KEY_D}, {33, GlfwKeys.GLFW_KEY_F}, {34, GlfwKeys.GLFW_KEY_G},
                {35, GlfwKeys.GLFW_KEY_H}, {36, GlfwKeys.GLFW_KEY_J}, {37, GlfwKeys.GLFW_KEY_K}, {38, GlfwKeys.GLFW_KEY_L},
                {39, GlfwKeys.GLFW_KEY_SEMICOLON}, {40, GlfwKeys.GLFW_KEY_APOSTROPHE}, {41, GlfwKeys.GLFW_KEY_GRAVE_ACCENT}, {42, GlfwKeys.GLFW_KEY_LEFT_SHIFT},
                {43, GlfwKeys.GLFW_KEY_BACKSLASH},
                {44, GlfwKeys.GLFW_KEY_Z}, {45, GlfwKeys.GLFW_KEY_X}, {46, GlfwKeys.GLFW_KEY_C}, {47, GlfwKeys.GLFW_KEY_V}, {48, GlfwKeys.GLFW_KEY_B},
                {49, GlfwKeys.GLFW_KEY_N}, {50, GlfwKeys.GLFW_KEY_M},
                {51, GlfwKeys.GLFW_KEY_COMMA}, {52, GlfwKeys.GLFW_KEY_PERIOD}, {53, GlfwKeys.GLFW_KEY_SLASH}, {54, GlfwKeys.GLFW_KEY_RIGHT_SHIFT},
                {55, GlfwKeys.GLFW_KEY_KP_MULTIPLY}, {56, GlfwKeys.GLFW_KEY_LEFT_ALT}, {57, GlfwKeys.GLFW_KEY_SPACE}, {58, GlfwKeys.GLFW_KEY_CAPS_LOCK},
                {59, GlfwKeys.GLFW_KEY_F1}, {60, GlfwKeys.GLFW_KEY_F2}, {61, GlfwKeys.GLFW_KEY_F3}, {62, GlfwKeys.GLFW_KEY_F4}, {63, GlfwKeys.GLFW_KEY_F5},
                {64, GlfwKeys.GLFW_KEY_F6}, {65, GlfwKeys.GLFW_KEY_F7}, {66, GlfwKeys.GLFW_KEY_F8}, {67, GlfwKeys.GLFW_KEY_F9}, {68, GlfwKeys.GLFW_KEY_F10},
                {69, GlfwKeys.GLFW_KEY_NUM_LOCK}, {70, GlfwKeys.GLFW_KEY_SCROLL_LOCK},
                {71, GlfwKeys.GLFW_KEY_KP_7}, {72, GlfwKeys.GLFW_KEY_KP_8}, {73, GlfwKeys.GLFW_KEY_KP_9}, {74, GlfwKeys.GLFW_KEY_KP_SUBTRACT},
                {75, GlfwKeys.GLFW_KEY_KP_4}, {76, GlfwKeys.GLFW_KEY_KP_5}, {77, GlfwKeys.GLFW_KEY_KP_6}, {78, GlfwKeys.GLFW_KEY_KP_ADD},
                {79, GlfwKeys.GLFW_KEY_KP_1}, {80, GlfwKeys.GLFW_KEY_KP_2}, {81, GlfwKeys.GLFW_KEY_KP_3}, {82, GlfwKeys.GLFW_KEY_KP_0}, {83, GlfwKeys.GLFW_KEY_KP_DECIMAL},
                {87, GlfwKeys.GLFW_KEY_F11}, {88, GlfwKeys.GLFW_KEY_F12},
                {100, GlfwKeys.GLFW_KEY_F13}, {101, GlfwKeys.GLFW_KEY_F14}, {102, GlfwKeys.GLFW_KEY_F15}, {103, GlfwKeys.GLFW_KEY_F16},
                {104, GlfwKeys.GLFW_KEY_F17}, {105, GlfwKeys.GLFW_KEY_F18}, {113, GlfwKeys.GLFW_KEY_F19},
                {141, GlfwKeys.GLFW_KEY_KP_EQUAL}, {156, GlfwKeys.GLFW_KEY_KP_ENTER}, {157, GlfwKeys.GLFW_KEY_RIGHT_CONTROL},
                {181, GlfwKeys.GLFW_KEY_KP_DIVIDE}, {183, GlfwKeys.GLFW_KEY_PRINT_SCREEN}, {184, GlfwKeys.GLFW_KEY_RIGHT_ALT},
                {197, GlfwKeys.GLFW_KEY_PAUSE}, {199, GlfwKeys.GLFW_KEY_HOME}, {200, GlfwKeys.GLFW_KEY_UP}, {201, GlfwKeys.GLFW_KEY_PAGE_UP},
                {203, GlfwKeys.GLFW_KEY_LEFT}, {205, GlfwKeys.GLFW_KEY_RIGHT}, {207, GlfwKeys.GLFW_KEY_END}, {208, GlfwKeys.GLFW_KEY_DOWN},
                {209, GlfwKeys.GLFW_KEY_PAGE_DOWN}, {210, GlfwKeys.GLFW_KEY_INSERT}, {211, GlfwKeys.GLFW_KEY_DELETE},
                {219, GlfwKeys.GLFW_KEY_LEFT_SUPER}, {220, GlfwKeys.GLFW_KEY_RIGHT_SUPER}, {221, GlfwKeys.GLFW_KEY_MENU},
        };
        for (int[] pair : pairs) LEGACY_TO_GLFW[pair[0]] = pair[1];
    }


    @Override
    public Widget createDefaultWidgetFor(FeatureParameter parameter) {
        ParameterItem parameterItem = new ParameterItem(parameter, new KeybindEditWidget(parameter));
        return parameterItem;
    }

    public static class KeybindEditWidget extends AnnotatedImportOnlyWidget {
        @Bind(variableName = "key")
        public final BindableAttribute<String> value = new BindableAttribute<>(String.class);

        private InputConstants.Key currentKey = InputConstants.UNKNOWN;
        private boolean listen = false;
        private FeatureParameter<InputConstants.Key> parameter;
        public KeybindEditWidget(FeatureParameter<InputConstants.Key> featureParameter) {
            super(Identifier.parse("dungeonsguide:gui/config/parameter/keybind.gui"));
            this.parameter = featureParameter;
            currentKey = featureParameter.getValue();
            value.setValue(currentKey.getDisplayName().getString());
        }

        @On(functionName = "setKey")
        public void listen() {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            getDomElement().obtainFocus();
            value.setValue("> "+currentKey.getDisplayName().getString()+" <");
            listen =true;
        }

        @Override
        public boolean keyPressed(KeyEvent keyEvent) {
            if (!listen) return false;
            if (keyEvent.isEscape()) {
                currentKey = InputConstants.UNKNOWN;
            } else {
                // keys glfw has no key code for come back as scancodes
                currentKey = InputConstants.getKey(keyEvent);
            }
            listen = false;
            parameter.setValue(currentKey);
            value.setValue(currentKey.getDisplayName().getString());
            return true;
        }
    }
}

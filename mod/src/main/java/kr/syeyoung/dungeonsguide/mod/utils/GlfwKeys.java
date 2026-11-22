/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.utils;

// 26.3 dropped lwjgl-glfw for lwjgl-sdl and InputConstants.KEY_* are now SDL scancodes.
// Saved DG configs still carry GLFW key codes; these map them onto the new key space.
public final class GlfwKeys {
    private GlfwKeys() {}

    public static final int GLFW_KEY_UNKNOWN = -1;

    public static final int GLFW_KEY_SPACE = 32;
    public static final int GLFW_KEY_APOSTROPHE = 39;
    public static final int GLFW_KEY_COMMA = 44;
    public static final int GLFW_KEY_MINUS = 45;
    public static final int GLFW_KEY_PERIOD = 46;
    public static final int GLFW_KEY_SLASH = 47;
    public static final int GLFW_KEY_0 = 48, GLFW_KEY_1 = 49, GLFW_KEY_2 = 50, GLFW_KEY_3 = 51, GLFW_KEY_4 = 52, GLFW_KEY_5 = 53, GLFW_KEY_6 = 54, GLFW_KEY_7 = 55, GLFW_KEY_8 = 56, GLFW_KEY_9 = 57;
    public static final int GLFW_KEY_SEMICOLON = 59;
    public static final int GLFW_KEY_EQUAL = 61;
    public static final int GLFW_KEY_A = 65, GLFW_KEY_B = 66, GLFW_KEY_C = 67, GLFW_KEY_D = 68, GLFW_KEY_E = 69, GLFW_KEY_F = 70, GLFW_KEY_G = 71, GLFW_KEY_H = 72, GLFW_KEY_I = 73, GLFW_KEY_J = 74, GLFW_KEY_K = 75, GLFW_KEY_L = 76, GLFW_KEY_M = 77, GLFW_KEY_N = 78, GLFW_KEY_O = 79, GLFW_KEY_P = 80, GLFW_KEY_Q = 81, GLFW_KEY_R = 82, GLFW_KEY_S = 83, GLFW_KEY_T = 84, GLFW_KEY_U = 85, GLFW_KEY_V = 86, GLFW_KEY_W = 87, GLFW_KEY_X = 88, GLFW_KEY_Y = 89, GLFW_KEY_Z = 90;
    public static final int GLFW_KEY_LEFT_BRACKET = 91;
    public static final int GLFW_KEY_BACKSLASH = 92;
    public static final int GLFW_KEY_RIGHT_BRACKET = 93;
    public static final int GLFW_KEY_GRAVE_ACCENT = 96;
    public static final int GLFW_KEY_WORLD_1 = 161;
    public static final int GLFW_KEY_WORLD_2 = 162;
    public static final int GLFW_KEY_ESCAPE = 256;
    public static final int GLFW_KEY_ENTER = 257;
    public static final int GLFW_KEY_TAB = 258;
    public static final int GLFW_KEY_BACKSPACE = 259;
    public static final int GLFW_KEY_INSERT = 260;
    public static final int GLFW_KEY_DELETE = 261;
    public static final int GLFW_KEY_RIGHT = 262;
    public static final int GLFW_KEY_LEFT = 263;
    public static final int GLFW_KEY_DOWN = 264;
    public static final int GLFW_KEY_UP = 265;
    public static final int GLFW_KEY_PAGE_UP = 266;
    public static final int GLFW_KEY_PAGE_DOWN = 267;
    public static final int GLFW_KEY_HOME = 268;
    public static final int GLFW_KEY_END = 269;
    public static final int GLFW_KEY_CAPS_LOCK = 280;
    public static final int GLFW_KEY_SCROLL_LOCK = 281;
    public static final int GLFW_KEY_NUM_LOCK = 282;
    public static final int GLFW_KEY_PRINT_SCREEN = 283;
    public static final int GLFW_KEY_PAUSE = 284;
    public static final int GLFW_KEY_F1 = 290, GLFW_KEY_F2 = 291, GLFW_KEY_F3 = 292, GLFW_KEY_F4 = 293, GLFW_KEY_F5 = 294, GLFW_KEY_F6 = 295, GLFW_KEY_F7 = 296, GLFW_KEY_F8 = 297, GLFW_KEY_F9 = 298, GLFW_KEY_F10 = 299, GLFW_KEY_F11 = 300, GLFW_KEY_F12 = 301, GLFW_KEY_F13 = 302, GLFW_KEY_F14 = 303, GLFW_KEY_F15 = 304, GLFW_KEY_F16 = 305, GLFW_KEY_F17 = 306, GLFW_KEY_F18 = 307, GLFW_KEY_F19 = 308, GLFW_KEY_F20 = 309, GLFW_KEY_F21 = 310, GLFW_KEY_F22 = 311, GLFW_KEY_F23 = 312, GLFW_KEY_F24 = 313, GLFW_KEY_F25 = 314;
    public static final int GLFW_KEY_KP_0 = 320, GLFW_KEY_KP_1 = 321, GLFW_KEY_KP_2 = 322, GLFW_KEY_KP_3 = 323, GLFW_KEY_KP_4 = 324, GLFW_KEY_KP_5 = 325, GLFW_KEY_KP_6 = 326, GLFW_KEY_KP_7 = 327, GLFW_KEY_KP_8 = 328, GLFW_KEY_KP_9 = 329;
    public static final int GLFW_KEY_KP_DECIMAL = 330;
    public static final int GLFW_KEY_KP_DIVIDE = 331;
    public static final int GLFW_KEY_KP_MULTIPLY = 332;
    public static final int GLFW_KEY_KP_SUBTRACT = 333;
    public static final int GLFW_KEY_KP_ADD = 334;
    public static final int GLFW_KEY_KP_ENTER = 335;
    public static final int GLFW_KEY_KP_EQUAL = 336;
    public static final int GLFW_KEY_LEFT_SHIFT = 340;
    public static final int GLFW_KEY_LEFT_CONTROL = 341;
    public static final int GLFW_KEY_LEFT_ALT = 342;
    public static final int GLFW_KEY_LEFT_SUPER = 343;
    public static final int GLFW_KEY_RIGHT_SHIFT = 344;
    public static final int GLFW_KEY_RIGHT_CONTROL = 345;
    public static final int GLFW_KEY_RIGHT_ALT = 346;
    public static final int GLFW_KEY_RIGHT_SUPER = 347;
    public static final int GLFW_KEY_MENU = 348;

    public static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    public static final int GLFW_MOUSE_BUTTON_RIGHT = 1;
    public static final int GLFW_MOUSE_BUTTON_MIDDLE = 2;
    public static final int GLFW_MOUSE_BUTTON_4 = 3;
    public static final int GLFW_MOUSE_BUTTON_5 = 4;
    public static final int GLFW_MOUSE_BUTTON_6 = 5;
    public static final int GLFW_MOUSE_BUTTON_7 = 6;
    public static final int GLFW_MOUSE_BUTTON_8 = 7;

    public static final int GLFW_PRESS = 1;
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_REPEAT = 2;

    // glfw keycode -> SDL scancode (the values InputConstants.KEY_* hold in 26.3). -1 = no key
    public static int keycodeToScancode(int glfw) {
        if (glfw >= GLFW_KEY_A && glfw <= GLFW_KEY_Z) return glfw - GLFW_KEY_A + 4;
        if (glfw >= GLFW_KEY_1 && glfw <= GLFW_KEY_9) return glfw - GLFW_KEY_1 + 30;
        if (glfw == GLFW_KEY_0) return 39;
        if (glfw >= GLFW_KEY_F1 && glfw <= GLFW_KEY_F12) return glfw - GLFW_KEY_F1 + 58;
        if (glfw >= GLFW_KEY_F13 && glfw <= GLFW_KEY_F24) return glfw - GLFW_KEY_F13 + 104;
        if (glfw >= GLFW_KEY_KP_1 && glfw <= GLFW_KEY_KP_9) return glfw - GLFW_KEY_KP_1 + 89;
        if (glfw == GLFW_KEY_KP_0) return 98;
        switch (glfw) {
            case GLFW_KEY_SPACE: return 44;
            case GLFW_KEY_APOSTROPHE: return 52;
            case GLFW_KEY_COMMA: return 54;
            case GLFW_KEY_MINUS: return 45;
            case GLFW_KEY_PERIOD: return 55;
            case GLFW_KEY_SLASH: return 56;
            case GLFW_KEY_SEMICOLON: return 51;
            case GLFW_KEY_EQUAL: return 46;
            case GLFW_KEY_LEFT_BRACKET: return 47;
            case GLFW_KEY_BACKSLASH: return 49;
            case GLFW_KEY_RIGHT_BRACKET: return 48;
            case GLFW_KEY_GRAVE_ACCENT: return 53;
            case GLFW_KEY_ESCAPE: return 41;
            case GLFW_KEY_ENTER: return 40;
            case GLFW_KEY_TAB: return 43;
            case GLFW_KEY_BACKSPACE: return 42;
            case GLFW_KEY_INSERT: return 73;
            case GLFW_KEY_DELETE: return 76;
            case GLFW_KEY_RIGHT: return 79;
            case GLFW_KEY_LEFT: return 80;
            case GLFW_KEY_DOWN: return 81;
            case GLFW_KEY_UP: return 82;
            case GLFW_KEY_PAGE_UP: return 75;
            case GLFW_KEY_PAGE_DOWN: return 78;
            case GLFW_KEY_HOME: return 74;
            case GLFW_KEY_END: return 77;
            case GLFW_KEY_CAPS_LOCK: return 57;
            case GLFW_KEY_SCROLL_LOCK: return 71;
            case GLFW_KEY_NUM_LOCK: return 83;
            case GLFW_KEY_PRINT_SCREEN: return 70;
            case GLFW_KEY_PAUSE: return 72;
            case GLFW_KEY_KP_DECIMAL: return 99;
            case GLFW_KEY_KP_DIVIDE: return 84;
            case GLFW_KEY_KP_MULTIPLY: return 85;
            case GLFW_KEY_KP_SUBTRACT: return 86;
            case GLFW_KEY_KP_ADD: return 87;
            case GLFW_KEY_KP_ENTER: return 88;
            case GLFW_KEY_KP_EQUAL: return 103;
            case GLFW_KEY_LEFT_SHIFT: return 225;
            case GLFW_KEY_LEFT_CONTROL: return 224;
            case GLFW_KEY_LEFT_ALT: return 226;
            case GLFW_KEY_LEFT_SUPER: return 227;
            case GLFW_KEY_RIGHT_SHIFT: return 229;
            case GLFW_KEY_RIGHT_CONTROL: return 228;
            case GLFW_KEY_RIGHT_ALT: return 230;
            case GLFW_KEY_RIGHT_SUPER: return 231;
            case GLFW_KEY_MENU: return 101;
            default: return -1;
        }
    }

    // glfw mouse button (0=left, 1=right, 2=middle, 3..=extra) -> SDL button (1=left, 2=middle, 3=right, 4..=extra)
    public static int mouseButtonToSdl(int glfwButton) {
        switch (glfwButton) {
            case GLFW_MOUSE_BUTTON_LEFT: return 1;
            case GLFW_MOUSE_BUTTON_RIGHT: return 3;
            case GLFW_MOUSE_BUTTON_MIDDLE: return 2;
            default: return glfwButton >= 3 && glfwButton <= 7 ? glfwButton + 1 : -1;
        }
    }
}

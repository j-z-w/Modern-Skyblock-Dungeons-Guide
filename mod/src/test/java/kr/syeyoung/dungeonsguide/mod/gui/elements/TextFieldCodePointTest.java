package kr.syeyoung.dungeonsguide.mod.gui.elements;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFieldCodePointTest {
    private static final String EMOJI = "a\uD83D\uDE00b"; // a, U+1F600 as a surrogate pair, b

    @Test
    void stepsOverSurrogatePairs() {
        assertEquals(1, TextField.previousCodePoint(EMOJI, 3));
        assertEquals(0, TextField.previousCodePoint(EMOJI, 1));
        assertEquals(3, TextField.nextCodePoint(EMOJI, 1));
        assertEquals(4, TextField.nextCodePoint(EMOJI, 3));
    }

    @Test
    void clampsAtBounds() {
        assertEquals(0, TextField.previousCodePoint(EMOJI, 0));
        assertEquals(EMOJI.length(), TextField.nextCodePoint(EMOJI, EMOJI.length()));
        assertEquals(0, TextField.previousCodePoint("", 0));
        assertEquals(0, TextField.nextCodePoint("", 0));
    }
}

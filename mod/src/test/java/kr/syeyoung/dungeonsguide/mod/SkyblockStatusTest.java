package kr.syeyoung.dungeonsguide.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SkyblockStatusTest {
    @Test
    void locationLineMarkers() {
        // 1.8.9-era sidebar
        assertTrue(SkyblockStatus.isLocationLine(" §7⏣ §cThe Catacombs (F3)"));
        // captured from Hypixel on 2026-11-22: the marker is now an icon font glyph
        assertTrue(SkyblockStatus.isLocationLine(" §7\uE067 §cDungeon H§cub"));
        assertFalse(SkyblockStatus.isLocationLine(" §7Late Autumn 8th"));
        assertFalse(SkyblockStatus.isLocationLine("§fPurse: 612,136"));
        assertFalse(SkyblockStatus.isLocationLine(" §7"));
    }

    @Test
    void sidebarPaddingIsStripped() {
        // Hypixel splits sidebar lines with invisible non-format pairs (§u, §q); they must not leave letters behind
        assertEquals("Dungeon Hub", kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor("§cDungeon H§r§u§cub"));
        assertEquals("Time Elapsed: 00s", kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor("Time Elapsed: §r§q00s"));
        assertEquals("plain", kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor("plain"));
    }
}

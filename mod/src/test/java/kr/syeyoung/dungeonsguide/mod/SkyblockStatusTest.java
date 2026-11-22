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
}

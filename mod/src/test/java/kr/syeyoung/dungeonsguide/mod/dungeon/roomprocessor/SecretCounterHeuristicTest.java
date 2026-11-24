package kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretBatState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretEssenceState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.DungeonSecretItemDropState;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// G5 review B2: the action-bar "N/M Secrets" counter heuristic misattributed finds -
// green M/M went unparsed, a wrong §7 segment crashed parsing, the counter stayed above
// the local count while a teammate found things out of range, chest interacts raced the
// bar message, and a 30-block radius pulled in unrelated secrets.
public class SecretCounterHeuristicTest {

    @BeforeAll
    static void bootstrap() {
        // ChatFormatting in TextUtils.stripColor pulls in SharedConstants' version check
        McBootstrap.boot();
    }

    @Test
    void parsesNormalCounter() {
        int[] c = GeneralRoomProcessor.parseSecretCounter("§74/9 Secrets");
        assertNotNull(c);
        assertArrayEquals(new int[]{4, 9}, c);
    }

    @Test
    void parsesGreenCompletedCounter() {
        // Hypixel colors the whole group green once the room is done; the old parser
        // anchored on §7 and returned -1 or threw on these
        int[] c = GeneralRoomProcessor.parseSecretCounter("§a9/9 Secrets");
        assertNotNull(c);
        assertArrayEquals(new int[]{9, 9}, c);
    }

    @Test
    void ignoresNonSecretFractionsBeforeTheCounter() {
        // an earlier "N/M" elsewhere in the bar must not win over the actual counter:
        // only groups directly followed by " Secrets" match
        int[] c = GeneralRoomProcessor.parseSecretCounter("§71/2 something §73/7 Secrets");
        assertNotNull(c);
        assertArrayEquals(new int[]{3, 7}, c);
    }

    @Test
    void returnsNullWithoutCounter() {
        assertNull(GeneralRoomProcessor.parseSecretCounter("§7Kill something"));
        assertNull(GeneralRoomProcessor.parseSecretCounter("§7a/b Secrets"));
        // absurdly large numbers used to throw NumberFormatException
        assertNull(GeneralRoomProcessor.parseSecretCounter("§79999999999999/99999999999999 Secrets"));
    }

    @Test
    void deltaIsClampedByLocalCount() {
        // ordinary bump: one new secret nobody locally detected
        assertEquals(1, GeneralRoomProcessor.unattributedDelta(3, 2, 2));
        // teammate's persistent lead: counter above local count but nothing new since
        // the last bar message - no attribution
        assertEquals(0, GeneralRoomProcessor.unattributedDelta(3, 3, 1));
        // local detection already caught the secret the counter just counted
        assertEquals(0, GeneralRoomProcessor.unattributedDelta(3, 2, 3));
        // multiple secrets counted between two bar messages
        assertEquals(3, GeneralRoomProcessor.unattributedDelta(5, 2, 2));
        // counter says 4, last seen 2, local 1: only the two new bumps are attributable
        assertEquals(2, GeneralRoomProcessor.unattributedDelta(4, 2, 1));
    }

    @Test
    void roomReentryRebaselinesWithoutMarking() {
        // after a room border crossing lastCounterSecrets resets to -1; the first bar
        // just re-baselines (handled by the -1 branch) and the delta formula agrees
        assertEquals(0, GeneralRoomProcessor.unattributedDelta(2, -1, 2));
    }

    @Test
    void chestInteractSuppressesAttribution() {
        long now = 10_000L;
        // chest opened 200ms before the counter tick: the bump is almost certainly the
        // chest's own detection catching up, don't blame a nearby bat
        assertTrue(GeneralRoomProcessor.attributionSuppressed(now, now - 200, -1));
        // same for a local find that just flipped
        assertTrue(GeneralRoomProcessor.attributionSuppressed(now, -1, now - 300));
        // but an old chest open must not keep suppressing real bumps
        assertFalse(GeneralRoomProcessor.attributionSuppressed(now, now - 5_000, -1));
        assertFalse(GeneralRoomProcessor.attributionSuppressed(now, -1, -1));
    }

    @Test
    void secretChatTriggersMatchOnStrippedText() {
        // chatReceived compares against these constants on color-stripped text - pin the
        // historical Hypixel formatting stripping down to them (an exact-formatted
        // compare used to dead-end the detection on any §-layout change)
        assertEquals(GeneralRoomProcessor.CHEST_ALREADY_SEARCHED,
                kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor(
                        "§r§cThis chest has already been searched!§r"));
        assertEquals(GeneralRoomProcessor.REDSTONE_KEY_FOUND,
                kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor(
                        "§r§aYou found a Secret Redstone Key!§r"));
        assertEquals(GeneralRoomProcessor.WIZARD_QUEST_DONE,
                kr.syeyoung.dungeonsguide.mod.utils.TextUtils.stripColor(
                        "§e[NPC] Wizard§f: §rOh my lovely crystal ball, mi so happy§r"));
    }

    private static DungeonSecretBatState bat() {
        return new DungeonSecretBatState(new DungeonSecretBatState.DungeonSecretBatData(), null);
    }

    private static DungeonSecretItemDropState itemDrop() {
        return new DungeonSecretItemDropState(new DungeonSecretItemDropState.DungeonSecretItemDropData(), null);
    }

    private static DungeonSecretEssenceState essence() {
        return new DungeonSecretEssenceState(new DungeonSecretEssenceState.DungeonSecretEssenceData(), null);
    }

    @Test
    void pickCounterCandidatePrefersBatsThenNearest() {
        DungeonMechanicState b1 = bat();
        DungeonMechanicState d1 = itemDrop();
        DungeonMechanicState e1 = essence();
        List<GeneralRoomProcessor.AttributionCandidate> candidates = new ArrayList<>();
        candidates.add(new GeneralRoomProcessor.AttributionCandidate(d1, 4, GeneralRoomProcessor.AttributionCandidate.ITEM_DROP));
        candidates.add(new GeneralRoomProcessor.AttributionCandidate(b1, 100, GeneralRoomProcessor.AttributionCandidate.BAT));
        candidates.add(new GeneralRoomProcessor.AttributionCandidate(e1, 9, GeneralRoomProcessor.AttributionCandidate.ESSENCE));
        // bats are attributed first no matter the distance: their detection is the weakest
        assertSame(b1, GeneralRoomProcessor.pickCounterCandidate(candidates));

        candidates.removeIf(c -> c.kind == GeneralRoomProcessor.AttributionCandidate.BAT);
        // then the nearest drop/essence
        assertSame(d1, GeneralRoomProcessor.pickCounterCandidate(candidates));
        assertNull(GeneralRoomProcessor.pickCounterCandidate(new ArrayList<>()));
    }

    @Test
    void counterRadiusExcludesMidRangeDrops() {
        // the 7-30 block band the review flagged: far enough that old code still attributed
        // but too far for reliable attribution now - outside the tightened radius
        assertTrue(15 * 15 > GeneralRoomProcessor.SECRET_COUNTER_RADIUS_SQR);
        assertTrue(12 * 12 <= GeneralRoomProcessor.SECRET_COUNTER_RADIUS_SQR + 0.5);
    }
}

package kr.syeyoung.dungeonsguide.mod.dungeon.actions;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

// route-step advancement: when a later/terminal action completes out of order (a bat dies
// to any detection signal while the atomic is still on its move step), the atomic must
// fast-forward to the completed tail instead of stalling on the travel step
public class AtomicActionTest {

    private static AbstractAction stub(boolean complete) {
        return new AbstractAction() {
            @Override
            public boolean isComplete(DungeonRoom dungeonRoom) {
                return complete;
            }
        };
    }

    @Test
    void completedKillTailFastForwardsAtomic() {
        ActionKill kill = new ActionKill(new OffsetPoint(1, 1, 1));
        kill.setCompletedBy(() -> true); // secret already found via sound/removal
        AtomicAction atomic = new AtomicAction(Arrays.asList(stub(false), kill), "MoveAndKill");

        atomic.onTick(null);

        // cursor jumped to the kill; the while-complete pass then advances past it
        assertTrue(atomic.isComplete(null));
    }

    @Test
    void incompleteKillTailDoesNotSkip() {
        ActionKill kill = new ActionKill(new OffsetPoint(1, 1, 1)) {
            @Override
            public boolean isComplete(DungeonRoom dungeonRoom) {
                return false;
            }
        };
        AtomicAction atomic = new AtomicAction(Arrays.asList(stub(false), kill), "MoveAndKill");
        atomic.onTick(null);
        // current travel step is incomplete and the tail isn't either: no skip
        assertEquals(0, atomic.getCurrent());
        assertFalse(atomic.isComplete(null));
    }

    @Test
    void nonTerminalTailStillRequiresOrdering() {
        // an atomic whose last action is a plain travel step must not jump when it happens
        // to be complete - intermediate steps may still be required
        AtomicAction atomic = new AtomicAction(Arrays.asList(stub(false), stub(true)), "Move");
        atomic.onTick(null);
        assertEquals(0, atomic.getCurrent());
    }

    @Test
    void consumedAtomicIgnoresFurtherEventsAndTicks() {
        // once the last child completes the atomic has no current action; a stray
        // dispatch before the route advances must be a no-op, not an NPE
        AtomicAction atomic = new AtomicAction(Arrays.asList(stub(true)), "Move");
        atomic.onTick(null); // completes -> current advances past the end
        assertTrue(atomic.isComplete(null));
        atomic.onTick(null); // used to NPE on getCurrentAction()
        atomic.onPlayerInteract(null, null);
        atomic.onLivingDeath(null, null);
        atomic.onLivingInteract(null, null);
    }

    @Test
    void actionKillCompletedByBacker() {
        ActionKill kill = new ActionKill(new OffsetPoint(1, 1, 1));
        kill.setCompletedBy(() -> true);
        // the mechanic's own signals complete the action before any world lookups run
        assertTrue(kill.isComplete(null));
    }
}

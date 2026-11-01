package ai.resourcepack.ui.framework.pack;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether ours is on top, and the raise that puts it back (ResourcePack.raise, Views.packLoading). */
class OnTopTest {
    private final UUID player = UUID.randomUUID();

    @Test
    void aRaiseNobodyAnswersIsWaitedForThirtySecondsOnly() {
        OnTop t = new OnTop();
        t.buried(player, "dialog");
        assertTrue(t.raise(player, 100));
        assertTrue(t.raising(player, 100 + OnTop.RAISE_TIMEOUT - 1));
        // The client never says anything: the next page must not wait 30 s and close as well.
        assertFalse(t.raising(player, 100 + OnTop.RAISE_TIMEOUT));
        assertFalse(t.raising(player, 101));
        assertFalse(t.raise(player, 2000), "not buried any more: nothing to send");
    }

    @Test
    void aRaiseEndsWithAnyAnswer() {
        OnTop t = new OnTop();
        t.buried(player, "dialog");
        assertTrue(t.raise(player, 0));
        t.raised(player);
        assertFalse(t.raising(player, 1));
    }

    @Test
    void onlyASiblingPackThatReplacesOursBuriesIt() {
        OnTop t = new OnTop();
        t.buried(player, "rp-engine");
        t.harmless("rp-engine");
        assertFalse(t.buried(player));
        assertFalse(t.raise(player, 0), "no pointless reload for a pack that replaces none of our files");

        t.buried(player, "dialog");
        t.buried(player, "rp-engine");
        t.harmless("rp-engine");
        assertTrue(t.buried(player), "another pack over ours still buries it");
        assertTrue(t.buriedBy(player, "dialog"));
        assertTrue(t.raise(player, 0));
    }

    @Test
    void oursTakenAgainIsOnTop() {
        OnTop t = new OnTop();
        t.buried(player, "dialog");
        t.accepted(player);
        assertFalse(t.buried(player));
        assertFalse(t.raise(player, 0));
    }
}

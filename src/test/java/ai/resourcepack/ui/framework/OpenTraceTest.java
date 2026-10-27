package ai.resourcepack.ui.framework;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The open trace is off until a player is traced, and off again once nobody is: then every hook finds nothing. */
class OpenTraceTest {
    private final UUID player = UUID.randomUUID(), other = UUID.randomUUID();

    @AfterEach
    void off() {
        OpenTrace.trace(player, false);
        OpenTrace.trace(other, false);
    }

    @Test
    void offUntilSomebodyIsTraced() {
        assertFalse(OpenTrace.on());
        assertNull(OpenTrace.of(player));
        assertNull(OpenTrace.of(null));
    }

    @Test
    void onWhileAnybodyIs() {
        OpenTrace.trace(player, true);
        OpenTrace.trace(other, true);
        assertTrue(OpenTrace.on());
        assertTrue(OpenTrace.traced(player));
        assertNull(OpenTrace.of(player), "no open begun");
        OpenTrace.trace(player, false);
        assertTrue(OpenTrace.on(), "the other is still traced");
        assertFalse(OpenTrace.traced(player));
        OpenTrace.trace(other, false);
        assertFalse(OpenTrace.on());
    }
}

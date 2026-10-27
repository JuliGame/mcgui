package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.net.ViaVersionState.Stand;
import com.viaversion.viaversion.api.platform.ViaChannelHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared compression may write past ViaVersion's handler only where it passes packets on untouched. Stand-ins for
 * Via's {@code ViaChannelHandler} and {@code UserConnection} (the real ones are only on a server) show each answer.
 */
class ViaVersionStateTest {
    public static final class User {
        private final boolean active;

        User(boolean active) {
            this.active = active;
        }

        public boolean shouldTransformPacket() {
            return active;
        }
    }

    public static class Encoder implements ViaChannelHandler {
        private final Object connection;

        Encoder(Object connection) {
            this.connection = connection;
        }

        @Override
        public Object connection() {
            return connection;
        }
    }

    /** Via's real handlers extend a base class that implements the interface, so a subclass must count too. */
    public static class Base implements ViaChannelHandler {
        @Override
        public Object connection() {
            return new User(false);
        }
    }

    public static final class Sub extends Base {}

    public static final class NoMethod implements ViaChannelHandler {
        @Override
        public Object connection() {
            return new Object();
        }
    }

    public static final class Throws implements ViaChannelHandler {
        @Override
        public Object connection() {
            throw new IllegalStateException("gone");
        }
    }

    @Test
    void aHandlerThatIsNotViasIsNotVia() {
        assertEquals(Stand.NOT_VIA, ViaVersionState.stand(new Object()));
    }

    @Test
    void anInactiveConnectionPassesPacketsThrough() {
        assertEquals(Stand.PASSES, ViaVersionState.stand(new Encoder(new User(false))));
    }

    @Test
    void anActiveConnectionTranslates() {
        assertEquals(Stand.TRANSLATES, ViaVersionState.stand(new Encoder(new User(true))));
    }

    @Test
    void itIsAskedAgainEachTime() {
        User[] user = {new User(true)};
        Encoder e = new Encoder(null) {
            @Override
            public Object connection() {
                return user[0];
            }
        };
        // A subclass of the stand-in, as Via's Bukkit handler is of its base: still Via's.
        assertEquals(Stand.TRANSLATES, ViaVersionState.stand(e));
        user[0] = new User(false);
        assertEquals(Stand.PASSES, ViaVersionState.stand(e));
    }

    @Test
    void aSubclassOfViasBaseHandlerCounts() {
        assertEquals(Stand.PASSES, ViaVersionState.stand(new Sub()));
    }

    @Test
    void whatCannotBeAskedIsUnknown() {
        assertEquals(Stand.UNKNOWN, ViaVersionState.stand(new Encoder(null)));
        assertEquals(Stand.UNKNOWN, ViaVersionState.stand(new NoMethod()));
        assertEquals(Stand.UNKNOWN, ViaVersionState.stand(new Throws()));
    }
}

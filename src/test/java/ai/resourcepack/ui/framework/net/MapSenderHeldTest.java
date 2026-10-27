package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player who leaves every screen keeps, on the sender's side, what their client still holds: a view of the same
 * page opened again on the same map ids (a menu reopened) is sent nothing and is delivered at once. Not across a
 * reset (rejoin, pack change), another connection, or a write that had not landed when they left.
 */
class MapSenderHeldTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 2, ROWS = 2, W = COLS * 128, H = ROWS * 128, FIRST = 7000;

    private Client client;
    private MapSender sender;
    private Canvas page;
    private final Player player = player();

    @BeforeEach
    void open() {
        client = new Client();
        sender = new MapSender(client, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        page = new Canvas(PALETTE, COLS, ROWS);
        page.draw(noise(), W, H, 0, 0);
    }

    @Test
    void aPageShownAgainIsSentNothing() {
        assertEquals(COLS * ROWS, show(), "the first time, every tile");
        leave();
        MapSender.Assignment again = assign();
        sender.pass();
        assertEquals(0, client.sent, "shown again");
        assertTrue(sender.delivered(again));
    }

    @Test
    void notAfterAReset() {
        show();
        leave();
        sender.reset(player.getUniqueId());
        assertEquals(COLS * ROWS, show());
    }

    @Test
    void notOnAnotherConnection() {
        show();
        leave();
        client.connection = new Connection();
        assertEquals(COLS * ROWS, show());
    }

    @Test
    void notWhatWasStillOnItsWay() {
        assign();
        sender.pass(); // written, not landed
        sender.update(List.of());
        sender.pass(); // gone with it still in flight
        client.deliver();
        client.sent = 0;
        MapSender.Assignment again = assign();
        sender.pass();
        assertFalse(client.sent == 0, "sent again");
        client.deliver();
        assertTrue(sender.delivered(again));
    }

    /** What an open trace says the client holds: nothing at first, the page once shown and after leaving, nothing after a reset. */
    @Test
    void heldCountsTheMapsTheClientHas() {
        assertEquals(0, sender.held(player.getUniqueId()));
        show();
        assertEquals(COLS * ROWS, sender.held(player.getUniqueId()));
        leave();
        assertEquals(COLS * ROWS, sender.held(player.getUniqueId()), "kept for a reopen");
        sender.reset(player.getUniqueId());
        assertEquals(0, sender.held(player.getUniqueId()));
    }

    /** A new view's wall at the same ids, given [page]: tiles sent until it is delivered. */
    private int show() {
        client.sent = 0;
        MapSender.Assignment a = assign();
        for (int i = 0; i < 10 && !sender.delivered(a); i++) {
            sender.pass();
            client.deliver();
        }
        assertTrue(sender.delivered(a));
        return client.sent;
    }

    private MapSender.Assignment assign() {
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, FIRST);
        MapSender.Assignment a = new MapSender.Assignment(player, wall, page, Canvas.Encoding.HI);
        sender.update(List.of(a));
        return a;
    }

    private void leave() {
        sender.update(List.of());
        sender.pass();
        client.sent = 0;
    }

    private static int[] noise() {
        int[] px = new int[W * H];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | r.nextInt(0x1000000);
        return px;
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            default -> null;
        });
    }

    /** One connection's frames: never re-pointed. */
    private static final class Connection implements Wire.Frames {
        @Override public int[] pointing(MapWall wall) { return null; }
        @Override public void point(MapWall wall, int[] pointing) {}
    }

    /** Counts the patches written; writes land when told. */
    private static final class Client implements Wire {
        int sent;
        Connection connection = new Connection();
        private final ArrayDeque<Consumer<Boolean>> queue = new ArrayDeque<>();

        @Override public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) { return mapId; }
        @Override public Object repoint(MapWall wall, int tile, int mapId) { return null; }
        @Override public Frames frames(Player player) { return connection; }

        @Override
        public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
            sent += new ArrayList<>(packets).size();
            queue.add(done);
            return true;
        }

        void deliver() {
            while (!queue.isEmpty()) queue.poll().accept(true);
        }
    }
}

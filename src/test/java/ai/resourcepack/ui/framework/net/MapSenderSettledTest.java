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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The sender skips a wall whose drawing, pointing and pointer have not moved since every tile was last found on
 * the client. These are the two ways the client can lack a tile with none of those moving.
 */
class MapSenderSettledTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 2, ROWS = 2, W = COLS * 128, H = ROWS * 128;

    private Client client;
    private MapSender sender;
    private Canvas canvas;

    @BeforeEach
    void open() {
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 7000);
        client = new Client(wall);
        canvas = new Canvas(PALETTE, COLS, ROWS);
        sender = new MapSender(client, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(player(), wall, canvas, Canvas.Encoding.HI)));
    }

    /**
     * A canvas can be handed an earlier drawing's very tiles again (Canvas#show). Taken back straight after a
     * pass sent another drawing, before any pass found the client settled on that one, it must still be sent.
     */
    @Test
    void aDrawingTakenBackIsSentAgain() {
        Canvas red = new Canvas(PALETTE, COLS, ROWS);
        red.draw(fill(0xFF0000), W, H, 0, 0);
        canvas.show(red);
        settle();
        canvas.draw(fill(0x0000FF), W, H, 0, 0);
        sender.pass();
        client.deliver(true);
        canvas.show(red);
        settle();
        assertShows(red);
    }

    /** A write that never reached the client, failing after a later pass had already found every tile sent. */
    @Test
    void aWriteThatFailedLateIsSentAgain() {
        canvas.draw(fill(0x00FF00), W, H, 0, 0);
        sender.pass(); // written, not yet delivered
        sender.pass(); // every tile now counts as sent: the wall settles
        client.deliver(false);
        settle();
        assertShows(canvas);
    }

    private void assertShows(Canvas c) {
        for (int t = 0; t < COLS * ROWS; t++) assertArrayEquals(c.tile(t, Canvas.Encoding.HI), client.maps.get(7000 + t), "tile " + t);
    }

    private void settle() {
        for (int i = 0; i < 20; i++) {
            sender.pass();
            client.deliver(true);
        }
    }

    private static int[] fill(int rgb) {
        int[] px = new int[W * H];
        Arrays.fill(px, 0xFF000000 | rgb);
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

    /** One client of one wall, frames never re-pointed: each map's colours as patches leave them. */
    private static final class Client implements Wire {
        private record Patch(int id, int x, int y, int w, int h, byte[] colors) {}

        private record Write(List<Object> packets, Consumer<Boolean> done) {}

        final Map<Integer, byte[]> maps = new HashMap<>();
        private final ArrayDeque<Write> queue = new ArrayDeque<>();

        Client(MapWall wall) {}

        @Override
        public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
            return new Patch(mapId, x, y, w, h, colors);
        }

        @Override
        public Object repoint(MapWall wall, int tile, int mapId) {
            return null;
        }

        @Override
        public Frames frames(Player player) {
            return null;
        }

        @Override
        public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
            queue.add(new Write(List.copyOf(packets), done));
            return true;
        }

        /** Every waiting write reaches the client ([ok]) or is lost on the way. */
        void deliver(boolean ok) {
            while (!queue.isEmpty()) {
                Write w = queue.poll();
                if (ok) for (Object o : w.packets) apply((Patch) o);
                w.done.accept(ok);
            }
        }

        private void apply(Patch p) {
            byte[] map = maps.computeIfAbsent(p.id, k -> new byte[128 * 128]);
            for (int y = 0; y < p.h; y++) System.arraycopy(p.colors, y * p.w, map, (p.y + y) * 128 + p.x, p.w);
        }
    }
}

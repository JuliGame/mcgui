package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send loop runs every 8 ms only while a picture is part sent; with nothing left to send it sleeps until a
 * canvas publishes, the screens change or a write lands. Asleep it costs nothing, and it must still send at once.
 */
class MapSenderSleepTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 2, ROWS = 2, W = COLS * 128, H = ROWS * 128;
    private final MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 7000);
    private final Client client = new Client();
    private MapSender sender;

    @AfterEach
    void close() {
        if (sender != null) sender.close();
    }

    /** Out of budget part way through a picture: the loop must come back for the rest without being woken. */
    @Test
    void aPictureLeftPartSentKeepsTheLoopLooking() throws InterruptedException {
        Canvas canvas = new Canvas(PALETTE, COLS, ROWS);
        // Four tiles a second, a quarter second's worth to start with: the first pass gets one tile of four out.
        sender = new MapSender(client, PALETTE, 4 * 128 * 128, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(player(), wall, canvas, Canvas.Encoding.HI, null)));
        canvas.draw(noise(1), W, H, 0, 0);
        sender.pass();
        client.deliver();
        assertTrue(sender.unsent(), "tiles are left to send");
        for (int i = 0; i < 2000 && sender.unsent(); i++) {
            Thread.sleep(5);
            sender.pass();
            client.deliver();
        }
        assertFalse(sender.unsent(), "the whole picture went out");
        assertShows(canvas);
    }

    /** At rest the loop thread waits for good (not on a timer); a new picture still goes out straight away. */
    @Test
    void asleepAtRestAndAwakeForANewPicture() throws InterruptedException {
        sender = new MapSender(client, PALETTE, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        Canvas canvas = new Canvas(PALETTE, COLS, ROWS, sender::wake);
        sender.update(List.of(new MapSender.Assignment(player(), wall, canvas, Canvas.Encoding.HI, null)));
        sender.start();
        Thread loop = thread("UIEngine-send");
        canvas.draw(noise(2), W, H, 0, 0);
        awaitShown(canvas);
        awaitState(loop, Thread.State.WAITING);
        canvas.draw(noise(3), W, H, 0, 0);
        awaitShown(canvas);
        awaitState(loop, Thread.State.WAITING);
    }

    private void awaitShown(Canvas canvas) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            client.deliver();
            if (shows(canvas)) return;
            Thread.sleep(5);
        }
        assertShows(canvas);
    }

    private static void awaitState(Thread t, Thread.State want) throws InterruptedException {
        for (int i = 0; i < 400 && t.getState() != want; i++) Thread.sleep(5);
        assertEquals(want, t.getState(), "the send loop's state at rest");
    }

    private static Thread thread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals(name) && t.isAlive()) return t;
        throw new AssertionError("no thread " + name);
    }

    private boolean shows(Canvas c) {
        for (int t = 0; t < COLS * ROWS; t++) if (!Arrays.equals(c.tile(t, Canvas.Encoding.HI), client.maps.get(7000 + t))) return false;
        return true;
    }

    private void assertShows(Canvas c) {
        for (int t = 0; t < COLS * ROWS; t++) assertArrayEquals(c.tile(t, Canvas.Encoding.HI), client.maps.get(7000 + t), "tile " + t);
    }

    /** Pixels that differ everywhere, so every tile changes whole between seeds. */
    private static int[] noise(int seed) {
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ((i * 0x9E3779B1 + seed * 0x85EBCA6B) & 0xFFFFFF);
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

    /** One client of one wall, frames never re-pointed, written to from the send loop's thread. */
    private static final class Client implements Wire {
        private record Patch(int id, int x, int y, int w, int h, byte[] colors) {}

        private record Write(List<Object> packets, Consumer<Boolean> done) {}

        final Map<Integer, byte[]> maps = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Write> queue = new ConcurrentLinkedQueue<>();

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

        void deliver() {
            for (Write w; (w = queue.poll()) != null; ) {
                for (Object o : w.packets) apply((Patch) o);
                w.done.accept(true);
            }
        }

        private void apply(Patch p) {
            byte[] map = maps.computeIfAbsent(p.id, k -> new byte[128 * 128]);
            synchronized (map) {
                for (int y = 0; y < p.h; y++) System.arraycopy(p.colors, y * p.w, map, (p.y + y) * 128 + p.x, p.w);
            }
        }
    }
}

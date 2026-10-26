package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.MapPool;
import ai.resourcepack.ui.framework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placed walls share maps ({@link MapPool}): the client holds one map for each distinct tile across them, every frame
 * shows its own wall's picture once the sender settles, and a wall nobody drew on keeps showing its picture whatever
 * the others do, after every write the client takes.
 */
class MapPoolTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 3, ROWS = 2, TILES = COLS * ROWS, W = COLS * 128, H = ROWS * 128;
    private static final int[] COLOURS = {0x203040, 0xE0B050, 0x40A060, 0xA03070, 0x7090D0, 0xD06040, 0x30C0C0, 0xF0F0E0, 0x101010, 0x806020};

    private final MapPool pool = new MapPool(9000);
    private final List<MapWall> walls = new ArrayList<>();
    private final List<Canvas> canvases = new ArrayList<>();
    private final Client client = new Client();
    private final Player player = player();
    private MapSender sender;

    private void walls(int n) {
        walls(n, Integer.MAX_VALUE);
    }

    private void walls(int n, int bytesPerSecond) {
        List<MapSender.Assignment> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MapWall wall = new MapWall(null, i * 4, 100, 0, BlockFace.NORTH, COLS, ROWS, 9000 + i * TILES);
            pool.add(wall);
            walls.add(wall);
            Canvas canvas = new Canvas(PALETTE, COLS, ROWS);
            canvas.shareAlike(true);
            canvases.add(canvas);
            out.add(new MapSender.Assignment(player, wall, canvas, Canvas.Encoding.HI));
        }
        sender = new MapSender(client, bytesPerSecond, Logger.getAnonymousLogger());
        sender.update(out);
    }

    @Test
    void wallsOfOnePictureShareTheirMaps() {
        walls(4);
        for (Canvas c : canvases) c.draw(tiles(0, 1, 2, 3, 0, 1), W, H, 0, 0);
        settle();
        assertShowCanvases();
        // Six tiles, two of them twice: four maps for the 24 frames.
        assertEquals(4, client.distinctShown(walls));
    }

    @Test
    void aWallThatChangesLeavesTheOthersAlone() {
        walls(3);
        for (Canvas c : canvases) c.draw(tiles(0, 1, 2, 3, 2, 1), W, H, 0, 0);
        settle();
        byte[][] before = shownOn(1), last = shownOn(2);
        client.check = () -> {
            assertPicture(1, before);
            assertPicture(2, last);
        };
        canvases.get(0).draw(tiles(3, 3, 0, 1, 1, 2), W, H, 0, 0);
        settle();
        assertShowCanvases();
    }

    /**
     * Random pictures of a few colours a tile on four walls (so tiles repeat, and new ones come), drawn a wall at a time with writes held in flight between
     * passes: whatever the order, a wall left alone never changes on the client, and all end on their canvases.
     */
    @Test
    void randomDrawingsNeverShowAnotherWallsPicture() {
        walls(4);
        random(new Random(7), 300);
    }

    /** The same with a budget of a few tiles at a time: pictures go out over several passes, maps waiting their turn. */
    @Test
    void randomDrawingsSentAFewTilesAtATime() {
        walls(4, 600_000);
        random(new Random(11), 40);
    }

    private void random(Random r, int steps) {
        for (Canvas c : canvases) c.draw(randomTiles(r), W, H, 0, 0);
        settle();
        // Walls drawn on since the client last settled: only the others must stay as they are.
        Set<Integer> dirty = new HashSet<>();
        for (int step = 0; step < steps; step++) {
            int w = r.nextInt(walls.size());
            dirty.add(w);
            Map<Integer, byte[][]> still = new HashMap<>();
            for (int i = 0; i < walls.size(); i++) if (!dirty.contains(i)) still.put(i, shownOn(i));
            client.check = () -> still.forEach((i, pic) -> assertPicture(i, pic));
            if (r.nextBoolean()) {
                canvases.get(w).draw(randomTiles(r), W, H, 0, 0);
            } else {
                int t = r.nextInt(TILES);
                // One of the colours, or one no tile has had: a map nobody holds.
                int rgb = r.nextBoolean() ? COLOURS[r.nextInt(COLOURS.length)] : r.nextInt(0x1000000);
                canvases.get(w).draw(fill(rgb), 128, 128, (t % COLS) * 128, (t / COLS) * 128);
            }
            // Sometimes a pass with its writes still on the way when the next wall draws.
            sender.pass();
            if (r.nextBoolean()) {
                client.deliver();
                settle();
                client.check = () -> {};
                assertShowCanvases();
                dirty.clear();
            }
        }
        client.check = () -> {};
        settle();
        assertShowCanvases();
        Set<byte[]> distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Canvas c : canvases) for (int t = 0; t < TILES; t++) distinct.add(c.tile(t, Canvas.Encoding.HI));
        int shown = client.distinctShown(walls);
        assertTrue(shown <= distinct.size(), shown + " maps shown for " + distinct.size() + " distinct tiles");
    }

    /**
     * A map still waiting for its new tile (out of budget) holds its old one only until that goes: a wall earlier in
     * the order that wants the old tile then must not be pointed at it, or it shows the new one when that lands.
     */
    @Test
    void aMapWaitingForItsTileIsNotShownForTheOldOne() throws InterruptedException {
        walls(2, 100_000);
        Canvas borrower = canvases.get(0), changing = canvases.get(1);
        borrower.draw(tiles(6, 7, 8, 9, 6, 7), W, H, 0, 0);
        changing.draw(tiles(0, 1, 2, 3, 4, 5), W, H, 0, 0);
        settle();
        byte[] before = client.shown(walls.get(0), 0).clone();
        byte[] old = changing.tile(4, Canvas.Encoding.HI);
        // Six new tiles: more than the budget, so the last of them wait for a later pass.
        Random r = new Random(3);
        int[] fresh = new int[W * H];
        for (int t = 0; t < TILES; t++) {
            int rgb = 0xFF000000 | r.nextInt(0x1000000);
            for (int y = 0; y < 128; y++) Arrays.fill(fresh, ((t / COLS) * 128 + y) * W + (t % COLS) * 128, ((t / COLS) * 128 + y) * W + (t % COLS) * 128 + 128, rgb);
        }
        changing.draw(fresh, W, H, 0, 0);
        sender.pass();
        assertTrue(sender.unsent(), "the budget holds some of the new tiles back");
        borrower.draw(fill(COLOURS[4]), 128, 128, 0, 0);
        assertTrue(borrower.tile(0, Canvas.Encoding.HI) == old, "the borrower's new tile is the very array the other wall had");
        byte[] wanted = old.clone();
        client.check = () -> {
            byte[] now = client.shown(walls.get(0), 0);
            assertTrue(Arrays.equals(now, before) || Arrays.equals(now, wanted), "the borrower's tile showed another wall's new tile");
        };
        Thread.sleep(300); // the budget refills, and the borrower comes first in the order
        settle();
        client.check = () -> {};
        assertShowCanvases();
    }

    @Test
    void pooledKeepsWhatIsShownAndMovesWhatChanges() {
        walls(2);
        byte[] a = new byte[1], b = new byte[1], c = new byte[1];
        Map<Integer, byte[]> sent = new HashMap<>();
        int[] maps0 = walls.get(0).ownMaps(), maps1 = walls.get(1).ownMaps();
        for (int t = 0; t < TILES; t++) {
            sent.put(maps0[t], a);
            sent.put(maps1[t], a);
        }
        int[] counts = new int[pool.end() - pool.base];
        // Wall 1 wants what wall 0's first map holds everywhere: every frame of it shows that map.
        byte[][] wantA = new byte[TILES][];
        Arrays.fill(wantA, a);
        int[] to = MapSender.pooled(walls.get(1), pool, maps1, wantA, w -> w.ownMaps(), sent, Map.of(), counts);
        assertTrue(to == null, "every frame already shows its tile");
        // Wall 0's tiles 0 and 1 now want b: one free map each would do, but they want the same tile, so they share one.
        byte[][] want = wantA.clone();
        want[0] = b;
        want[1] = b;
        int[] shared = new int[TILES];
        for (int t = 0; t < TILES; t++) shared[t] = maps0[0];
        to = MapSender.pooled(walls.get(1), pool, shared, wantA, w -> w == walls.get(0) ? maps0 : shared, sent, Map.of(), counts);
        assertTrue(to == null);
        // Wall 1 shows map maps0[0] in every frame, so maps1's are free; wall 0's first two frames move to one of them.
        to = MapSender.pooled(walls.get(0), pool, maps0, want, w -> shared, sent, Map.of(), counts);
        assertEquals(to[0], to[1]);
        assertTrue(to[0] != maps0[0], "a map another frame shows is never sent another tile");
        for (int t = 2; t < TILES; t++) assertEquals(maps0[t], to[t]);
        // A map waiting to be sent another tile is not counted on for the one it holds.
        sent.put(maps1[5], c);
        byte[][] wantC = wantA.clone();
        wantC[3] = c;
        byte[] d = new byte[1];
        to = MapSender.pooled(walls.get(0), pool, maps0, wantC, w -> shared, sent, Map.of(maps1[5], d), counts);
        assertTrue(to == null, "sent in place, not shown from a map about to be sent another tile");
        to = MapSender.pooled(walls.get(0), pool, maps0, wantC, w -> shared, sent, Map.of(), counts);
        assertEquals(maps1[5], to[3]);
        // A tile on its way to another map: wait for it rather than send it a second time.
        byte[][] wantD = wantA.clone();
        wantD[3] = d;
        assertTrue(MapSender.pooled(walls.get(0), pool, maps0, wantD, w -> shared, sent, Map.of(maps1[5], d), counts) == MapSender.WAIT);
    }

    /** Walls of one page coming into view together, a few tiles a pass: each distinct tile is sent once. */
    @Test
    void wallsOfOnePageComingIntoViewTogetherSendItOnce() {
        walls(4, 100_000);
        for (Canvas c : canvases) c.draw(tiles(0, 1, 2, 3, 4, 5), W, H, 0, 0);
        settle();
        assertShowCanvases();
        assertEquals(6L * 128 * 128, client.patchedBytes, "bytes of map patches sent");
        assertEquals(6, client.distinctShown(walls));
    }

    // ---------------------------------------------------------------- helpers

    private void settle() {
        int quiet = 0;
        for (int i = 0; i < 20000; i++) {
            sender.pass();
            // Out of budget is not settled: the rest goes as it refills.
            if (client.deliver() || sender.unsent()) quiet = 0;
            else if (++quiet == 3) return;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("the sender never settled");
    }

    private void assertShowCanvases() {
        for (int i = 0; i < walls.size(); i++) {
            for (int t = 0; t < TILES; t++) {
                assertArrayEquals(canvases.get(i).tile(t, Canvas.Encoding.HI), client.shown(walls.get(i), t), "wall " + i + " tile " + t);
            }
        }
    }

    private byte[][] shownOn(int wall) {
        byte[][] out = new byte[TILES][];
        for (int t = 0; t < TILES; t++) out[t] = client.shown(walls.get(wall), t).clone();
        return out;
    }

    private void assertPicture(int wall, byte[][] picture) {
        for (int t = 0; t < TILES; t++) assertArrayEquals(picture[t], client.shown(walls.get(wall), t), "wall " + wall + " tile " + t + " changed");
    }

    /** One colour per tile, by index into COLOURS, in tile order. */
    private static int[] tiles(int... colour) {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = 0xFF000000 | COLOURS[colour[(y / 128) * COLS + x / 128]];
        return px;
    }

    private static int[] randomTiles(Random r) {
        int[] c = new int[TILES];
        for (int t = 0; t < TILES; t++) c[t] = r.nextInt(COLOURS.length);
        return tiles(c);
    }

    private static int[] fill(int rgb) {
        int[] px = new int[128 * 128];
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

    /** One client of many walls: which map each frame shows and each map's colours, a write at a time. */
    private static final class Client implements Wire {
        private record Patch(int id, int x, int y, int w, int h, byte[] colors) {}

        private record Repoint(MapWall wall, int tile, int id) {}

        private record Write(List<Object> packets, Consumer<Boolean> done) {}

        final Map<Integer, byte[]> maps = new HashMap<>();
        long patchedBytes;
        private final Map<MapWall, int[]> shows = new HashMap<>();
        private final Map<MapWall, int[]> pointing = new HashMap<>();
        private final ArrayDeque<Write> queue = new ArrayDeque<>();
        /** Run after each write the client takes: what it would draw next. */
        Runnable check = () -> {};

        @Override
        public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
            return new Patch(mapId, x, y, w, h, colors);
        }

        @Override
        public Object repoint(MapWall wall, int tile, int mapId) {
            return new Repoint(wall, tile, mapId);
        }

        @Override
        public Frames frames(Player player) {
            return new Frames() {
                @Override
                public int[] pointing(MapWall w) {
                    return pointing.get(w);
                }

                @Override
                public void point(MapWall w, int[] to) {
                    pointing.put(w, to.clone());
                }
            };
        }

        @Override
        public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
            queue.add(new Write(List.copyOf(packets), done));
            return true;
        }

        boolean deliver() {
            if (queue.isEmpty()) return false;
            while (!queue.isEmpty()) {
                Write w = queue.poll();
                for (Object p : w.packets) {
                    if (p instanceof Repoint r) {
                        shows.computeIfAbsent(r.wall, k -> k.ownMaps().clone())[r.tile] = r.id;
                    } else {
                        Patch q = (Patch) p;
                        patchedBytes += (long) q.w * q.h;
                        byte[] map = maps.computeIfAbsent(q.id, k -> new byte[128 * 128]);
                        for (int y = 0; y < q.h; y++) System.arraycopy(q.colors, y * q.w, map, (q.y + y) * 128 + q.x, q.w);
                    }
                }
                w.done.accept(true);
                check.run();
            }
            return true;
        }

        byte[] shown(MapWall wall, int tile) {
            int[] s = shows.get(wall);
            byte[] m = maps.get(s == null ? wall.mapId(tile) : s[tile]);
            return m == null ? new byte[0] : m;
        }

        int distinctShown(List<MapWall> walls) {
            Set<Integer> ids = new HashSet<>();
            for (MapWall w : walls) {
                int[] s = shows.get(w);
                for (int t = 0; t < w.tiles(); t++) ids.add(s == null ? w.mapId(t) : s[t]);
            }
            return ids.size();
        }
    }
}

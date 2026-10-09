package ai.resourcepack.screens.net;

import ai.resourcepack.screens.api.Canvas;
import ai.resourcepack.screens.api.Pointer;
import ai.resourcepack.screens.render.Palette;
import ai.resourcepack.screens.world.MapWall;
import io.netty.channel.ChannelFutureListener;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends each player only the rectangle of each map that changed since what their client last
 * received, within a per-player byte budget and only as fast as their connection drains: a slow link
 * gets fewer pictures, never a growing backlog, so what they see stays current.
 *
 * <p>Pictures go out whole: the changed tiles of one moment are snapshotted and all sent before
 * anything newer is looked at, so a change never arrives as mismatched squares. Runs on its own thread
 * every few milliseconds rather than on the server tick, so a new picture goes out as soon as it is
 * drawn: map packets are safe to send from any thread, and the tile arrays it compares are immutable
 * once a {@link Canvas} publishes them.
 */
public final class MapSender implements AutoCloseable {
    /**
     * One player looking at one wall, showing [canvas] in [encoding]. [pointer] is drawn into PLAIN tiles
     * only (players with the pack see a smooth pointer entity instead); null for none.
     */
    public record Assignment(Player player, MapWall wall, Canvas canvas, Canvas.Encoding encoding, PointerSpot pointer) {}

    /** A pointer drawn into plain maps: where its hot pixel is, in canvas pixels, and its shape. */
    public record PointerSpot(int x, int y, Pointer shape) {}

    private static final class Viewer {
        final Map<Integer, byte[]> sent = new ConcurrentHashMap<>(); // map id -> exact array the client last got
        long budget;
        final Map<MapWall, Frame> frames = new HashMap<>();
        final AtomicLong inFlight = new AtomicLong();
        /** Plain tiles with the pointer drawn in, by map id, with what each was made from. */
        final Map<Integer, Pointed> pointed = new ConcurrentHashMap<>(); // also read by delivered(), on the main thread
    }

    private record Pointed(byte[] base, PointerSpot spot, byte[] tile) {}

    /** The tiles of one snapshot of a canvas still to send, in order. */
    private static final class Frame {
        final Canvas canvas;
        final byte[][] tile;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();

        Frame(Canvas canvas, int tiles) {
            this.canvas = canvas;
            this.tile = new byte[tiles][];
        }
    }

    private final Nms nms;
    private final Palette palette;
    private final int bytesPerSecond;
    /** Bytes a player may have queued but unwritten: about 50 ms at the full rate. */
    private final long maxInFlight;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final AtomicReference<List<Assignment>> latest = new AtomicReference<>(List.of());
    private final AtomicLong bytesSent = new AtomicLong();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ScreenEngine-send");
        t.setDaemon(true);
        return t;
    });
    private long lastRun = System.nanoTime();

    public MapSender(Nms nms, Palette palette, int bytesPerSecond) {
        this.nms = nms;
        this.palette = palette;
        this.bytesPerSecond = bytesPerSecond;
        this.maxInFlight = Math.max(512 * 1024L, bytesPerSecond / 20L);
    }

    public void update(List<Assignment> assignments) {
        latest.set(assignments);
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    /** Whether [player]'s connection has written out every tile of [wall]'s current picture. */
    public boolean delivered(UUID player, MapWall wall) {
        Assignment a = null;
        for (Assignment x : latest.get()) {
            if (x.player.getUniqueId().equals(player) && x.wall == wall) {
                a = x;
                break;
            }
        }
        Viewer v = viewers.get(player);
        if (a == null || v == null || v.inFlight.get() > 0) return false;
        for (int t = 0; t < wall.tiles(); t++) {
            if (v.sent.get(wall.mapId(t)) != tileFor(a, v, t)) return false;
        }
        return true;
    }

    /** Forget what a player has, so everything is resent (new world, rejoin, pack change). */
    public void reset(UUID player) {
        viewers.remove(player);
    }

    public void start() {
        loop.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                // A player left mid-send; the next run sees the new list.
            }
        }, 0, 8, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        loop.shutdownNow();
    }

    private void tick() {
        List<Assignment> assignments = latest.get();
        long now = System.nanoTime();
        long refill = bytesPerSecond * (now - lastRun) / 1_000_000_000L;
        lastRun = now;
        Set<UUID> seen = new HashSet<>();
        for (Assignment a : assignments) {
            Viewer v = viewers.computeIfAbsent(a.player.getUniqueId(), k -> new Viewer());
            // A quarter of a second of budget can build up, so a burst after a quiet moment goes out at once.
            if (seen.add(a.player.getUniqueId())) v.budget = Math.min(v.budget + refill, bytesPerSecond / 4L);
            send(a, v);
        }
        // Players who left every screen: drop their state so walking back resends everything.
        viewers.keySet().retainAll(seen);
        for (Viewer v : viewers.values()) {
            v.frames.keySet().removeIf(w -> assignments.stream().noneMatch(a -> a.wall == w));
        }
    }

    /** The tile [a]'s viewer should have: the canvas's, with the pointer drawn in for plain viewers. */
    private byte[] tileFor(Assignment a, Viewer v, int t) {
        byte[] base = a.canvas.tile(t, a.encoding);
        PointerSpot spot = a.pointer;
        if (spot == null || a.encoding != Canvas.Encoding.PLAIN || spot.shape == Pointer.HIDDEN) return base;
        int tx = (t % a.wall.cols) * 128, ty = (t / a.wall.cols) * 128;
        int left = spot.x - spot.shape.hotX, top = spot.y - spot.shape.hotY;
        if (left >= tx + 128 || left + 16 <= tx || top >= ty + 128 || top + 16 <= ty) return base;
        int id = a.wall.mapId(t);
        Pointed cached = v.pointed.get(id);
        if (cached != null && cached.base == base && cached.spot.equals(spot)) return cached.tile;
        byte[] tile = base.clone();
        byte black = palette.plain(0x000000), white = palette.plain(0xFFFFFF);
        String[] art = spot.shape.art;
        for (int y = 0; y < art.length; y++) {
            for (int x = 0; x < art[y].length(); x++) {
                char c = art[y].charAt(x);
                if (c == ' ') continue;
                int px = left + x - tx, py = top + y - ty;
                if (px < 0 || py < 0 || px >= 128 || py >= 128) continue;
                tile[py * 128 + px] = c == 'o' ? white : black;
            }
        }
        v.pointed.put(id, new Pointed(base, spot, tile));
        return tile;
    }

    private void send(Assignment a, Viewer v) {
        int tiles = a.wall.tiles();
        if (a.canvas.cols() != a.wall.cols || a.canvas.rows() != a.wall.rows) return;
        Frame frame = v.frames.get(a.wall);
        if (frame == null || frame.canvas != a.canvas) {
            frame = new Frame(a.canvas, tiles);
            v.frames.put(a.wall, frame);
        }
        if (frame.queue.isEmpty()) {
            // Snapshot every tile that differs from what the client has; this set is what goes next.
            for (int t = 0; t < tiles; t++) {
                byte[] current = tileFor(a, v, t);
                if (current != v.sent.get(a.wall.mapId(t))) {
                    frame.tile[t] = current;
                    frame.queue.add(t);
                }
            }
        }
        while (true) {
            Integer t = frame.queue.peekFirst();
            if (t == null) return;
            int id = a.wall.mapId(t);
            byte[] current = frame.tile[t];
            int[] rect = diff(v.sent.get(id), current);
            if (rect == null) {
                v.sent.put(id, current);
                frame.queue.pollFirst();
                continue;
            }
            int cost = rect[2] * rect[3];
            if (v.budget <= 0 || v.inFlight.get() >= maxInFlight) return; // carry on from here next run
            byte[] data = new byte[cost];
            for (int y = 0; y < rect[3]; y++) System.arraycopy(current, (rect[1] + y) * 128 + rect[0], data, y * rect[2], rect[2]);
            v.inFlight.addAndGet(cost);
            // Completes once netty has compressed the packet and the socket took it, whether or not it succeeded.
            boolean sent = nms.send(a.player, nms.mapPatch(id, rect[0], rect[1], rect[2], rect[3], data),
                    (ChannelFutureListener) f -> v.inFlight.addAndGet(-cost));
            if (!sent) {
                v.inFlight.addAndGet(-cost);
                return;
            }
            v.budget -= cost;
            bytesSent.addAndGet(cost);
            v.sent.put(id, current);
            frame.queue.pollFirst();
        }
    }

    /** The smallest rectangle {x, y, w, h} covering every byte that differs, or null if none does. */
    private static int[] diff(byte[] previous, byte[] current) {
        if (previous == null) return new int[]{0, 0, 128, 128};
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int row = y * 128;
            for (int x = 0; x < 128; x++) {
                if (previous[row + x] != current[row + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }
}

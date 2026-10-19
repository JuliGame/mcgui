package ai.resourcepack.uiframework.net;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends each player only the rectangle of each map that changed since what their client last
 * received, within a per-player byte budget and only as fast as their connection drains: a slow link
 * gets fewer pictures, never a growing backlog, so what they see stays current.
 *
 * <p>Pictures go out whole: the changed tiles of one moment are snapshotted and all sent before
 * anything newer is looked at, so a change never arrives as mismatched squares. Runs on its own thread,
 * woken the moment a canvas publishes new tiles (and every few milliseconds regardless, for budgets),
 * and writes straight to each player's connection with one flush per pass, so a new picture goes out as
 * soon as it is drawn rather than with the next tick. The tile arrays it compares are immutable once a
 * {@link Canvas} publishes them.
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
        /** The walls this pass assigned them; frames for any other are dropped. */
        final Set<MapWall> walls = new HashSet<>();
        /** Bytes queued and not yet written, all walls together: what back-pressure goes by. */
        final AtomicLong inFlight = new AtomicLong();
        /** The same for each wall, so delivered() for one wall never waits on another that keeps changing. */
        final Map<MapWall, AtomicLong> wallInFlight = new ConcurrentHashMap<>();
        /** Plain tiles with the pointer drawn in, by map id, with what each was made from. */
        final Map<Integer, Pointed> pointed = new ConcurrentHashMap<>(); // also read by delivered(), on the main thread
        /** This pass's patches, written together with one flush. */
        final List<Object> packets = new ArrayList<>();
        final List<Patch> patches = new ArrayList<>();
        long batchCost;
    }

    /** One patch on its way: the tile it brings map [id] up to, and the wall's in-flight count it is in. */
    private record Patch(int id, byte[] tile, int cost, AtomicLong wallInFlight) {}

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
    private final Logger log;
    private final Thread loop = new Thread(this::run, "UIFramework-send");
    private volatile boolean closed;
    private long lastRun = System.nanoTime();
    /** The most a player's budget can build up to: a quarter of a second, so a burst after a quiet moment goes out at once. */
    private final long burst;
    /**
     * Budgets of players dropped in the last quarter second ({budget, when}). One who comes straight back carries on
     * from there, so stepping out of range and back cannot earn a fresh burst; anyone else starts with a full one.
     */
    private final Map<UUID, long[]> left = new ConcurrentHashMap<>();
    private long lastWarning;

    public MapSender(Nms nms, Palette palette, int bytesPerSecond, Logger log) {
        this.nms = nms;
        this.palette = palette;
        this.bytesPerSecond = bytesPerSecond;
        this.maxInFlight = Math.max(512 * 1024L, bytesPerSecond / 20L);
        this.burst = bytesPerSecond / 4L;
        this.log = log;
        loop.setDaemon(true);
    }

    public void update(List<Assignment> assignments) {
        latest.set(assignments);
        wake();
    }

    /** Something may have changed (a canvas published tiles): look now rather than at the next poll. */
    public void wake() {
        LockSupport.unpark(loop);
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
        if (a == null || v == null) return false;
        AtomicLong flying = v.wallInFlight.get(wall);
        if (flying != null && flying.get() > 0) return false;
        for (int t = 0; t < wall.tiles(); t++) {
            if (v.sent.get(wall.mapId(t)) != tileFor(a, v, t)) return false;
        }
        return true;
    }

    /** Forget what a player has, so everything is resent (new world, rejoin, pack change). */
    public void reset(UUID player) {
        Viewer v = viewers.remove(player);
        if (v != null) left.put(player, new long[]{v.budget, System.nanoTime()});
    }

    /**
     * A player new to the screens. They start with the full burst: a view opened or a wall walked up to is the
     * quietest moment there is, and starting from nothing made a 16x9 picture trickle out over 150 ms.
     */
    private Viewer fresh(UUID id, long now) {
        Viewer v = new Viewer();
        long[] was = left.remove(id);
        v.budget = was == null ? burst : Math.min(burst, was[0] + bytesPerSecond * (now - was[1]) / 1_000_000_000L);
        return v;
    }

    public void start() {
        loop.start();
    }

    private void run() {
        while (!closed) {
            try {
                tick();
            } catch (Throwable e) {
                // Errors too (out of memory, a broken reflective call): this thread is all that sends pictures,
                // so it must never die, and an exception that ended it would be lost without a word.
                warn(e);
            }
            // Woken early by wake(); otherwise every 8 ms, so budgets refill and a full connection is retried.
            LockSupport.parkNanos(8_000_000L);
        }
    }

    @Override
    public void close() {
        closed = true;
        wake();
    }

    /** Usually a player leaving mid-send, which the next run gets past; said at most once a minute if it keeps happening. */
    private void warn(Throwable e) {
        long now = System.nanoTime();
        if (now - lastWarning > 60_000_000_000L) {
            lastWarning = now;
            log.log(Level.WARNING, "Sending map pictures failed (shown at most once a minute)", e);
        }
    }

    private void tick() {
        List<Assignment> assignments = latest.get();
        long now = System.nanoTime();
        long refill = bytesPerSecond * (now - lastRun) / 1_000_000_000L;
        lastRun = now;
        Set<UUID> seen = new HashSet<>();
        for (Assignment a : assignments) {
            Viewer v = viewers.computeIfAbsent(a.player.getUniqueId(), k -> fresh(k, now));
            if (seen.add(a.player.getUniqueId())) v.budget = Math.min(v.budget + refill, burst);
            v.walls.add(a.wall);
            // Each on its own: one that fails must not hold up every assignment after it.
            try {
                send(a, v);
            } catch (RuntimeException e) {
                warn(e);
            }
        }
        for (Assignment a : assignments) {
            try {
                flush(a.player, viewers.get(a.player.getUniqueId()));
            } catch (RuntimeException e) {
                warn(e);
            }
        }
        // Players who left every screen: drop their state so walking back resends everything, keeping their budget a moment.
        for (var it = viewers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (seen.contains(e.getKey())) continue;
            left.put(e.getKey(), new long[]{e.getValue().budget, now});
            it.remove();
        }
        left.values().removeIf(was -> now - was[1] > 250_000_000L); // refilled to the full burst by now anyway
        // And each player's frames for walls they are no longer near, even where others still are.
        for (Viewer v : viewers.values()) {
            v.frames.keySet().retainAll(v.walls);
            v.wallInFlight.entrySet().removeIf(e -> !v.walls.contains(e.getKey()) && e.getValue().get() == 0);
            v.walls.clear();
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
            Object packet = nms.mapPatch(id, rect[0], rect[1], rect[2], rect[3], data);
            AtomicLong wallInFlight = v.wallInFlight.computeIfAbsent(a.wall, w -> new AtomicLong());
            // Counted in flight from now, so delivered() never sees the tile as sent before it is written.
            v.inFlight.addAndGet(cost);
            wallInFlight.addAndGet(cost);
            v.packets.add(packet);
            v.patches.add(new Patch(id, current, cost, wallInFlight));
            v.batchCost += cost;
            v.budget -= cost;
            v.sent.put(id, current);
            frame.queue.pollFirst();
        }
    }

    /** Writes [v]'s patches from this pass to [player] together, with one flush. */
    private void flush(Player player, Viewer v) {
        if (v == null || v.packets.isEmpty()) return;
        List<Object> packets = List.copyOf(v.packets);
        List<Patch> patches = List.copyOf(v.patches);
        long cost = v.batchCost;
        v.packets.clear();
        v.patches.clear();
        v.batchCost = 0;
        boolean queued = false;
        try {
            // Completes once netty has compressed the last packet and the socket took it.
            queued = nms.writeNow(player, packets, ok -> {
                if (ok) bytesSent.addAndGet(cost);
                else forget(v, patches);
                landed(v, patches, cost);
            });
        } finally {
            // Never queued (no connection, or the write threw): nothing else will ever settle them.
            if (!queued) {
                forget(v, patches);
                landed(v, patches, cost);
            }
        }
    }

    /**
     * Patches that never reached the client: forget that it has them, so they are sent again. Before
     * they stop counting as in flight, so delivered() never sees them as both written and sent.
     */
    private static void forget(Viewer v, List<Patch> patches) {
        for (Patch p : patches) v.sent.remove(p.id, p.tile);
    }

    /** Patches written (or given up on): no longer in flight. */
    private void landed(Viewer v, List<Patch> patches, long cost) {
        v.inFlight.addAndGet(-cost);
        for (Patch p : patches) p.wallInFlight.addAndGet(-p.cost);
        // Room in flight again: send the rest of a picture now, not at the next 8 ms run. A 16x9 picture is
        // several times the in-flight limit, so without this each remaining slice waited out a timer
        // (measured: whole picture on screen 83.6 -> 66.6 ms median).
        wake();
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

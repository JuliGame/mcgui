package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.OpenTrace;
import ai.resourcepack.ui.framework.world.MapPool;
import ai.resourcepack.ui.framework.world.MapWall;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends each player only the rectangle of each map that changed since what their client last
 * received, within a per-player byte budget and only as fast as their connection drains: a slow link
 * gets fewer pictures, never a growing backlog, so what they see stays current.
 *
 * <p>Pictures go out whole: the changed tiles of one moment are snapshotted and all sent before
 * anything newer is looked at, so a change never arrives as mismatched squares. Runs on its own thread,
 * woken the moment a canvas publishes new tiles, the screens change or a write lands (and every few
 * milliseconds while a picture is part sent, for budgets; otherwise it sleeps), and writes straight to each
 * player's connection with one flush per pass, so a new picture goes out as
 * soon as it is drawn rather than with the next tick. The tile arrays it compares are immutable once a
 * {@link Canvas} publishes them.
 *
 * <p>When a tile a frame should show is one the client already holds in another of the wall's maps (the
 * canvas scrolled by whole tiles, {@link Canvas#scroll}), the frame is pointed at that map instead of being
 * sent the tile again: each frame's map is per player ({@link Wire.Frames}), the wall's maps are shuffled
 * between its frames, and the maps nobody shows any more take the tiles that are new. The re-pointing and
 * those tiles go in one bundle, so the client shows the whole new picture in one frame.
 *
 * <p>Placed walls go further ({@link MapPool}, {@link #pooled}): a frame may show any of their maps, and frames that
 * should show the same tile, on one wall or several, show one map. The client draws each distinct map with its own
 * draw call every frame, so a lobby of walls showing one page costs it one wall's maps.
 */
public final class MapSender implements AutoCloseable {
    /** One player looking at one wall, showing [canvas] in [encoding]. */
    public record Assignment(Player player, MapWall wall, Canvas canvas, Canvas.Encoding encoding) {}

    private static final class Viewer {
        final Map<Integer, byte[]> sent = new ConcurrentHashMap<>(); // map id -> exact array the client last got
        /** Bytes they may be sent now, as they go on the wire (compressed): see {@link #charge}. */
        long budget;
        /** Bytes on the wire per raw map byte, as their patches the wire could size came out: for those it cannot. */
        double ratio = 1;
        final Map<MapWall, Frame> frames = new HashMap<>();
        /** The walls this pass assigned them; frames for any other are dropped. */
        final Set<MapWall> walls = new HashSet<>();
        /** Bytes queued and not yet written, all walls together: what back-pressure goes by. */
        final AtomicLong inFlight = new AtomicLong();
        /** The same for each wall, so delivered() for one wall never waits on another that keeps changing. */
        final Map<MapWall, AtomicLong> wallInFlight = new ConcurrentHashMap<>();
        /** Which map each frame shows on their client; null until the wire can keep it, and until then each shows its own. */
        volatile Wire.Frames pointing;
        /** This pass's patches, made together (on several threads, see Wire#patches) and written with one flush. */
        final List<Wire.MapPatch> pending = new ArrayList<>();
        final List<Patch> patches = new ArrayList<>();
        long batchCost;
        /** What this pass's patches took from the budget so far: their estimated bytes on the wire. */
        long batchCharged;
        /** Bumped whenever patches are forgotten (a failed write), which no snapshot or pointing would show. */
        final AtomicInteger forgets = new AtomicInteger();
    }

    /** One patch on its way: the tile it brings map [id] up to, and the wall's in-flight count it is in. */
    private record Patch(int id, byte[] tile, int cost, AtomicLong wallInFlight) {}

    /** The tiles of one snapshot of a canvas still to send, in order. */
    private static final class Frame {
        final Canvas canvas;
        final byte[][] tile;
        /** The map each tile is sent to: what each frame showed when the snapshot was taken. */
        int[] maps;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        /**
         * The last time every tile was found on the client already: that drawing, pointing and forget
         * count. While all three stay the same nothing can differ, and the tile by tile check is skipped: with
         * dozens of walls in range of dozens of players, that check was most of this thread's time.
         */
        Canvas.Snapshot settled;
        int[] settledMaps;
        int settledForgets;
        /** The wall's {@link MapWall#sendsVersion} when settled: an overlay's panel that grew has new tiles to send. */
        int settledSends;

        Frame(Canvas canvas, int tiles) {
            this.canvas = canvas;
            this.tile = new byte[tiles][];
        }
    }

    private final Wire wire;
    private final int bytesPerSecond;
    /**
     * Map bytes (uncompressed) a player may have queued but unwritten: 800 KB at the default budget, which a 4 MB
     * window measured slower than (whole picture 78.2 against 66.6 ms), and a tenth of a second's budget above it.
     */
    private final long maxInFlight;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final AtomicReference<List<Assignment>> latest = new AtomicReference<>(List.of());
    private final AtomicLong bytesSent = new AtomicLong();
    private final Logger log;
    private final Thread loop = new Thread(this::run, "MCGUI-send");
    private volatile boolean closed;
    /** Whether the last pass left tiles to send (out of budget, or the connection full): then the loop looks again soon. */
    private volatile boolean unsent;
    private long lastRun = System.nanoTime();
    /**
     * The most a player's budget can build up to: half a second, so a burst after a quiet moment goes out at once (a
     * new player's first patches count uncompressed until compression has been seen, and a 16x9 picture is 2.4 MB).
     */
    private final long burst;
    /**
     * Budgets of players dropped in the last half second ({budget, when}). One who comes straight back carries on
     * from there, so stepping out of range and back cannot earn a fresh burst; anyone else starts with a full one.
     */
    private final Map<UUID, long[]> left = new ConcurrentHashMap<>();
    /**
     * What the clients of players who left every screen still hold, by map id, until they come back: a client keeps a
     * map's colours for as long as its connection lasts, whatever world it goes to, so a page shown again (a menu
     * reopened, a wall walked back to) costs only what differs from what it had. The tiles are held weakly: one nobody
     * else keeps alive any more can never be the tile asked for again (tiles are compared by identity), so it is
     * simply sent. Tied to the connection it was sent on ({@link Wire.Frames}, one per connection).
     */
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    /** Run whenever a pass ends or a write lands (any thread): what was not delivered may be now. */
    private volatile Runnable progress;

    private record Held(Wire.Frames connection, Map<Integer, java.lang.ref.WeakReference<byte[]>> tiles) {}
    private long lastWarning;
    /** This pass: how many viewers each canvas has in each encoding, so a tile more than one is sent is made once. */
    private final Map<Watch, Integer> watching = new HashMap<>();
    /** This pass: by tile, the last tile it was compared with and the rectangle that differs (null for none). */
    private final IdentityHashMap<byte[], Object[]> diffs = new IdentityHashMap<>();
    /** This pass: a pool's wall could not move its frames (not in the world yet), so the loop looks again soon. */
    private boolean again;
    /** {@link #pooled}'s count of frames showing each map of a pool, kept between calls: a pool's maps, by id - base. */
    private int[] counts = new int[0];

    private record Watch(Canvas canvas, Canvas.Encoding encoding) {}

    public MapSender(Wire wire, int bytesPerSecond, Logger log) {
        this.wire = wire;
        this.bytesPerSecond = bytesPerSecond;
        this.maxInFlight = Math.max(800_000L, bytesPerSecond / 10L);
        this.burst = bytesPerSecond / 2L;
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

    /** Whether the last {@link #pass} left tiles to send: the loop then looks again in 8 ms, else sleeps until woken. */
    boolean unsent() {
        return unsent;
    }

    /** Whether [player]'s connection has written out every tile of [wall]'s current picture. */
    public boolean delivered(UUID player, MapWall wall) {
        for (Assignment x : latest.get()) {
            if (x.player.getUniqueId().equals(player) && x.wall == wall) return delivered(x);
        }
        return false;
    }

    /** The same for an assignment handed to {@link #update} (the latest or one before): no looking it up among them all. */
    public boolean delivered(Assignment a) {
        Viewer v = viewers.get(a.player.getUniqueId());
        if (v == null) return false;
        MapWall wall = a.wall;
        AtomicLong flying = v.wallInFlight.get(wall);
        if (flying != null && flying.get() > 0) return false;
        int[] maps = maps(v, wall);
        Canvas.Snapshot snap = a.canvas.snapshot();
        for (int t = 0; t < wall.tiles(); t++) {
            if (wall.sends(t) && v.sent.get(maps[t]) != snap.tile(t, a.encoding)) return false;
        }
        return true;
    }

    /** Forget what a player has, so everything is resent (rejoin, pack change). */
    public void reset(UUID player) {
        held.remove(player);
        Viewer v = viewers.remove(player);
        if (v != null) left.put(player, new long[]{v.budget, System.nanoTime()});
    }

    /** How many maps [player]'s client holds from the sender, on screens now or kept from before (for a trace). */
    public long held(UUID player) {
        Viewer v = viewers.get(player);
        if (v != null) return v.sent.size();
        Held h = held.get(player);
        return h == null ? 0 : h.tiles.size();
    }

    /** [run] is run whenever a pass ends or a write lands, on whichever thread that was: something may be delivered now. */
    public void onProgress(Runnable run) {
        progress = run;
    }

    private void progressed() {
        Runnable run = progress;
        if (run != null) run.run();
    }

    /** [v], who left every screen, is dropped: what their client holds is kept for when they come back (see {@link #held}). */
    private void remember(UUID id, Viewer v) {
        // Patches still on their way could yet fail, and then the client never had them.
        if (v.pointing == null || v.inFlight.get() != 0) {
            held.remove(id);
            return;
        }
        Map<Integer, java.lang.ref.WeakReference<byte[]>> tiles = new HashMap<>(v.sent.size() * 2);
        v.sent.forEach((map, tile) -> tiles.put(map, new java.lang.ref.WeakReference<>(tile)));
        held.put(id, new Held(v.pointing, tiles));
    }

    /** [v] is back on a screen, on [connection]: what their client still holds, as it was when they left. */
    private void recall(UUID id, Viewer v, Wire.Frames connection) {
        Held h = held.remove(id);
        if (h == null || h.connection != connection) return;
        h.tiles.forEach((map, ref) -> {
            byte[] tile = ref.get();
            if (tile != null) v.sent.putIfAbsent(map, tile);
        });
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
                pass();
            } catch (Throwable e) {
                // Errors too (out of memory, a broken reflective call): this thread is all that sends pictures,
                // so it must never die, and an exception that ended it would be lost without a word.
                warn(e);
                // Cut short: whatever it had left is tried again soon.
                unsent = true;
            }
            // Woken early by wake(). Every 8 ms while tiles wait, so budgets refill and a full connection is
            // retried; with nothing left to send, nothing to do until woken (measured: 0.6% of a core at rest).
            if (unsent) LockSupport.parkNanos(8_000_000L);
            else LockSupport.park(this);
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

    /**
     * One pass of the send loop: what {@link #start} runs on its own thread every few milliseconds. Call it
     * yourself only where that thread is not started (a test or a benchmark driving the sender by hand).
     */
    public void pass() {
        List<Assignment> assignments = latest.get();
        long now = System.nanoTime();
        long refill = bytesPerSecond * (now - lastRun) / 1_000_000_000L;
        lastRun = now;
        Set<UUID> seen = new HashSet<>();
        watching.clear();
        diffs.clear();
        for (Assignment a : assignments) watching.merge(new Watch(a.canvas, a.encoding), 1, Integer::sum);
        for (Assignment a : assignments) {
            Viewer v = viewers.computeIfAbsent(a.player.getUniqueId(), k -> fresh(k, now));
            if (seen.add(a.player.getUniqueId())) {
                v.budget = Math.min(v.budget + refill, burst);
                if (v.pointing == null && (v.pointing = wire.frames(a.player)) != null) recall(a.player.getUniqueId(), v, v.pointing);
            }
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
        // Players who left every screen: drop their state, keeping their budget a moment and what their client holds.
        for (var it = viewers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (seen.contains(e.getKey())) continue;
            left.put(e.getKey(), new long[]{e.getValue().budget, now});
            remember(e.getKey(), e.getValue());
            it.remove();
        }
        left.values().removeIf(was -> now - was[1] > 500_000_000L); // refilled to the full burst by now anyway
        // And each player's frames for walls they are no longer near, even where others still are.
        for (Viewer v : viewers.values()) {
            v.frames.keySet().retainAll(v.walls);
            v.wallInFlight.entrySet().removeIf(e -> !v.walls.contains(e.getKey()) && e.getValue().get() == 0);
            v.walls.clear();
        }
        boolean left = again;
        again = false;
        for (Viewer v : viewers.values()) {
            for (Frame f : v.frames.values()) left |= !f.queue.isEmpty();
        }
        unsent = left;
        progressed();
    }

    /** The map each of [wall]'s frames shows [v]'s client. Never changed: a new pointing is a new array. */
    private static int[] maps(Viewer v, MapWall wall) {
        Wire.Frames f = v.pointing;
        int[] p = f == null ? null : f.pointing(wall);
        if (p != null && p.length == wall.tiles()) return p;
        return wall.ownMaps();
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
            Canvas.Snapshot snap = a.canvas.snapshot();
            int[] maps = maps(v, a.wall);
            int forgets = v.forgets.get();
            if (snap.sameAs(frame.settled) && maps == frame.settledMaps && forgets == frame.settledForgets
                    && a.wall.sendsVersion() == frame.settledSends) return;
            boolean changed = false;
            for (int t = 0; t < tiles; t++) {
                // Outside an overlay's panel: never sent, as if the client had it already.
                frame.tile[t] = a.wall.sends(t) ? snap.tile(t, a.encoding) : v.sent.get(maps[t]);
                changed |= frame.tile[t] != v.sent.get(maps[t]);
            }
            if (!changed) {
                frame.settled = snap;
                frame.settledMaps = maps;
                frame.settledForgets = forgets;
                frame.settledSends = a.wall.sendsVersion();
                return;
            }
            // Sending again: the client is no longer known to hold this drawing (a canvas can take back an
            // earlier drawing's very tiles, see Canvas#show), until a check finds every tile there once more.
            frame.settled = null;
            MapPool pool = a.wall.pool();
            int[] to = null;
            if (v.pointing != null && pool == null) {
                to = repointed(maps, frame.tile, v.sent);
            } else if (v.pointing != null) {
                if (counts.length < pool.end() - pool.base) counts = new int[pool.end() - pool.base];
                to = pooled(a.wall, pool, maps, frame.tile, w -> maps(v, w), v.sent, pending(v), counts);
                // A tile another wall is about to send: shown from there once it is out, rather than sent twice.
                if (to == WAIT) {
                    again = true;
                    return;
                }
            }
            // Pointing never changes while a snapshot is part sent, so its tiles all go where it was taken.
            if (to != null) {
                if (shift(a, v, frame, maps, to)) return;
                // A frame not in the world yet: a pool's maps may be shown by other walls' frames too, so none is
                // sent anything in place of the move; again in a moment, once the wall's frames are back.
                if (pool != null) {
                    again = true;
                    return;
                }
            }
            frame.maps = maps;
            for (int t = 0; t < tiles; t++) if (frame.tile[t] != v.sent.get(maps[t])) frame.queue.add(t);
        }
        while (true) {
            Integer t = frame.queue.peekFirst();
            if (t == null) return;
            int id = frame.maps[t];
            byte[] current = frame.tile[t];
            int[] rect = changed(v.sent.get(id), current);
            if (rect == null) {
                v.sent.put(id, current);
                frame.queue.pollFirst();
                continue;
            }
            int cost = rect[2] * rect[3];
            if (v.budget <= 0 || v.inFlight.get() >= maxInFlight) return; // carry on from here next run
            AtomicLong wallInFlight = v.wallInFlight.computeIfAbsent(a.wall, w -> new AtomicLong());
            // Counted in flight from now, so delivered() never sees the tile as sent before it is written.
            v.inFlight.addAndGet(cost);
            wallInFlight.addAndGet(cost);
            v.pending.add(patch(a, id, rect, current));
            v.patches.add(new Patch(id, current, cost, wallInFlight));
            v.batchCost += cost;
            long take = estimate(v, cost);
            v.batchCharged += take;
            v.budget -= take;
            v.sent.put(id, current);
            frame.queue.pollFirst();
        }
    }

    /** The patch for [rect] of [tile] to map [id], made by {@link Wire#patches}: the same packet for every viewer the wire can give it to. */
    private Wire.MapPatch patch(Assignment a, int id, int[] rect, byte[] tile) {
        Integer viewers = watching.get(new Watch(a.canvas, a.encoding));
        return new Wire.MapPatch(id, rect[0], rect[1], rect[2], rect[3], tile, viewers != null && viewers > 1);
    }

    /**
     * {@link #diff}, worked out once a pass for each pair of tiles: viewers of one wall who are in step all
     * compare the same two, and a 16x9 wall is 2.4 MB to compare for each of them.
     */
    private int[] changed(byte[] previous, byte[] current) {
        if (previous == null) return diff(null, current);
        Object[] last = diffs.get(current);
        if (last != null && last[0] == previous) return (int[]) last[1];
        int[] rect = diff(previous, current);
        diffs.put(current, new Object[]{previous, rect});
        return rect;
    }

    /**
     * Where to point each frame so that as many as possible show a tile the client already has: [maps] is what
     * each shows now, [want] what each should, [sent] what each map holds. A frame that already shows its tile
     * keeps its map; one whose tile is in another map of the wall that no frame keeps is pointed at that map; the
     * rest take the maps left over (their own first) and are sent their tile. Null if no frame would move.
     */
    static int[] repointed(int[] maps, byte[][] want, Map<Integer, byte[]> sent) {
        int n = maps.length;
        Map<byte[], Integer> holder = new IdentityHashMap<>();
        for (int id : maps) {
            byte[] s = sent.get(id);
            if (s != null) holder.put(s, id);
        }
        int[] to = new int[n];
        Arrays.fill(to, -1);
        Set<Integer> used = new HashSet<>();
        for (int t = 0; t < n; t++) {
            if (sent.get(maps[t]) == want[t]) {
                to[t] = maps[t];
                used.add(maps[t]);
            }
        }
        boolean moved = false;
        for (int t = 0; t < n; t++) {
            if (to[t] >= 0) continue;
            Integer id = holder.get(want[t]);
            if (id != null && used.add(id)) {
                to[t] = id;
                moved = true;
            }
        }
        if (!moved) return null;
        for (int t = 0; t < n; t++) {
            if (to[t] < 0 && used.add(maps[t])) to[t] = maps[t];
        }
        ArrayDeque<Integer> free = new ArrayDeque<>();
        for (int id : maps) if (!used.contains(id)) free.add(id);
        for (int t = 0; t < n; t++) if (to[t] < 0) to[t] = free.poll();
        return to;
    }

    /**
     * Where to point the frames of [wall], a wall of [pool], so that the client holds one map for each distinct tile
     * across the pool: [maps] is what each frame shows now, [want] what each should, [mapsOf] what another wall's
     * frames show, [sent] what each map holds, [pending] the maps waiting to be sent a tile and the tile each waits
     * for. Canvases share tiles of the same colours (Canvas#shareAlike), so the same tile is the same array wherever
     * it is.
     *
     * <p>A frame that shows its tile keeps its map. One whose tile a map of the pool holds is pointed at that map, any
     * wall's, whatever other frames show it. The rest take a map no frame shows (the tile's own when it can) and it is
     * sent their tile; frames wanting the same tile share it. So a map more than one frame shows
     * is never sent anything, and whoever changes moves to a free map. There always is one: there are as many maps as
     * frames, and a frame left without one means another map is shown twice or not at all. [counts] is scratch, at
     * least {@code pool.end() - pool.base} long. Null if no frame moves: then each frame's map is sent its tile in place,
     * and only that frame shows it. {@link #WAIT} if a tile no map holds yet is on its way to one: the wall then shows
     * it from there once it is out (walls of one page coming into view together send it once, not once each).
     */
    static int[] pooled(MapWall wall, MapPool pool, int[] maps, byte[][] want, Function<MapWall, int[]> mapsOf,
                        Map<Integer, byte[]> sent, Map<Integer, byte[]> pending, int[] counts) {
        int base = pool.base, size = pool.end() - base, n = maps.length;
        Arrays.fill(counts, 0, size, 0);
        MapWall[] walls = pool.walls();
        for (MapWall w : walls) {
            if (w == wall) continue;
            for (int id : mapsOf.apply(w)) if (id >= base && id - base < size) counts[id - base]++;
        }
        for (int id : maps) if (id >= base && id - base < size) counts[id - base]++;
        int[] to = new int[n];
        boolean keepAll = true;
        for (int t = 0; t < n; t++) {
            if (want[t] == null || sent.get(maps[t]) == want[t]) {
                to[t] = maps[t];
                continue;
            }
            to[t] = -1;
            keepAll = false;
            if (maps[t] >= base && maps[t] - base < size) counts[maps[t] - base]--;
        }
        if (keepAll) return null;
        // What each map of the pool holds, preferring maps some frame keeps showing.
        Map<byte[], Integer> holder = new IdentityHashMap<>();
        for (MapWall w : walls) {
            for (int id : w.ownMaps()) {
                byte[] s = sent.get(id);
                if (s == null || pending.containsKey(id)) continue;
                Integer had = holder.putIfAbsent(s, id);
                if (had != null && counts[had - base] == 0 && counts[id - base] > 0) holder.put(s, id);
            }
        }
        for (int t = 0; t < n; t++) {
            if (to[t] >= 0) continue;
            Integer id = holder.get(want[t]);
            if (id == null) continue;
            to[t] = id;
            counts[id - base]++;
        }
        if (!pending.isEmpty()) {
            Set<byte[]> coming = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            coming.addAll(pending.values());
            for (int t = 0; t < n; t++) if (to[t] < 0 && coming.contains(want[t])) return WAIT;
        }
        Map<byte[], Integer> claimed = new IdentityHashMap<>();
        int cursor = 0;
        for (int t = 0; t < n; t++) {
            if (to[t] >= 0) continue;
            Integer same = claimed.get(want[t]);
            int id = same != null ? same : -1;
            // A free map: the tile's own first, so every player's frames come back to the same maps and a patch is
            // compressed once for all of them; then the one it shows, this wall's others, any other wall's.
            if (id < 0 && free(wall.mapId(t), base, size, counts, pending)) id = wall.mapId(t);
            if (id < 0 && free(maps[t], base, size, counts, pending)) id = maps[t];
            for (int i = 0; id < 0 && i < n; i++) if (free(wall.mapId(i), base, size, counts, pending)) id = wall.mapId(i);
            for (; id < 0 && cursor < walls.length; cursor++) {
                for (int other : walls[cursor].ownMaps()) {
                    if (free(other, base, size, counts, pending)) {
                        id = other;
                        break;
                    }
                }
                if (id >= 0) break;
            }
            if (id < 0) throw new IllegalStateException("No free map in the pool for tile " + t);
            to[t] = id;
            counts[id - base]++;
            claimed.putIfAbsent(want[t], id);
        }
        return Arrays.equals(to, maps) ? null : to;
    }

    /** Whether no frame shows map [id] and none waits to send it a tile. */
    private static boolean free(int id, int base, int size, int[] counts, Map<Integer, byte[]> pending) {
        return id >= base && id - base < size && counts[id - base] == 0 && !pending.containsKey(id);
    }

    /** {@link #pooled}'s answer when a tile the wall wants is still on its way to another map: ask again next pass. */
    static final int[] WAIT = new int[0];

    /**
     * The maps [v]'s frames still have tiles to send to, each with the tile it waits for: what {@link #pooled} must
     * not count on keeping their tiles, and must wait for rather than send again.
     */
    private static Map<Integer, byte[]> pending(Viewer v) {
        Map<Integer, byte[]> out = new HashMap<>();
        for (Frame f : v.frames.values()) for (int t : f.queue) out.put(f.maps[t], f.tile[t]);
        return out;
    }

    /**
     * Points [a]'s frames at [to] (they show [maps] now) and sends the tiles the maps they then show lack, all in
     * one bundle, so the client never draws a frame with some moved and some not. Goes whole or not at all: false
     * if a frame is not in the world (then send as usual); true once written, or when it must wait for room.
     */
    private boolean shift(Assignment a, Viewer v, Frame frame, int[] maps, int[] to) {
        List<Object> packets = new ArrayList<>();
        for (int t = 0; t < to.length; t++) {
            if (to[t] == maps[t]) continue;
            Object p = wire.repoint(a.wall, t, to[t]);
            if (p == null) return false;
            packets.add(p);
        }
        if (v.budget <= 0 || v.inFlight.get() >= maxInFlight) {
            // All of it next run, from a fresh snapshot: soon, as the budget refills, since nothing is queued to wake for.
            again = true;
            return true;
        }
        AtomicLong wallInFlight = v.wallInFlight.computeIfAbsent(a.wall, w -> new AtomicLong());
        List<Patch> patches = new ArrayList<>();
        List<Wire.MapPatch> made = new ArrayList<>();
        // Frames of a pool share a map where they want the same tile: it is sent once.
        Set<Integer> done = new HashSet<>();
        long cost = 0;
        for (int t = 0; t < to.length; t++) {
            int id = to[t];
            if (!done.add(id)) continue;
            byte[] current = frame.tile[t];
            int[] rect = changed(v.sent.get(id), current);
            if (rect == null) {
                v.sent.put(id, current);
                continue;
            }
            int c = rect[2] * rect[3];
            made.add(patch(a, id, rect, current));
            patches.add(new Patch(id, current, c, wallInFlight));
            cost += c;
        }
        // Before the write: a frame the client is sent afresh from now on must show where these point it.
        v.pointing.point(a.wall, to);
        v.inFlight.addAndGet(cost);
        wallInFlight.addAndGet(cost);
        long taken = estimate(v, cost);
        v.budget -= taken;
        for (Patch p : patches) v.sent.put(p.id, p.tile);
        List<Object> sized = made(a.player, v, made, patches, cost);
        charge(v, made, sized, taken);
        packets.addAll(sized);
        write(a.player, v, packets, patches, cost, true);
        return true;
    }

    /** Writes [v]'s patches from this pass to [player] together, with one flush. */
    private void flush(Player player, Viewer v) {
        if (v == null || v.pending.isEmpty()) return;
        List<Wire.MapPatch> pending = List.copyOf(v.pending);
        List<Patch> patches = List.copyOf(v.patches);
        long cost = v.batchCost, charged = v.batchCharged;
        v.pending.clear();
        v.patches.clear();
        v.batchCost = 0;
        v.batchCharged = 0;
        List<Object> packets = made(player, v, pending, patches, cost);
        charge(v, pending, packets, charged);
        write(player, v, packets, patches, cost, false);
    }

    /** What [raw] map bytes are likely to take on [v]'s wire: what is taken from their budget before they are made. */
    private static long estimate(Viewer v, long raw) {
        return (long) Math.ceil(raw * v.ratio);
    }

    /**
     * Settles [v]'s budget for [made], which took [taken] from it ({@link #estimate}) and became [packets]: the
     * budget is spent by the bytes on the wire, compressed. It is for the player's connection and the server's
     * uplink, and a moving game's frame of pixel art scaled up shrinks fourteen-fold (1.9 MB of map pixels to
     * 136 KB), so counting map pixels held such a game to 8 frames a second. A patch the wire cannot size (its
     * connection compresses it later) counts at the ratio this player's patches have come out at.
     */
    private void charge(Viewer v, List<Wire.MapPatch> made, List<Object> packets, long taken) {
        long known = 0, knownRaw = 0, unknownRaw = 0;
        for (int i = 0; i < made.size(); i++) {
            Wire.MapPatch p = made.get(i);
            long raw = (long) p.w() * p.h(), size = i < packets.size() ? wire.wireBytes(packets.get(i)) : -1;
            if (size >= 0) {
                known += size;
                knownRaw += raw;
            } else {
                unknownRaw += raw;
            }
        }
        long spent = known + estimate(v, unknownRaw);
        if (knownRaw > 0) v.ratio = 0.7 * v.ratio + 0.3 * Math.min(1.0, (double) known / knownRaw);
        v.budget = Math.min(burst, v.budget + taken - spent);
    }

    /**
     * The packets for [made], [v]'s patches [patches] costing [cost], already counted as sent and in flight: if they
     * cannot be made, they are given up on as a failed write would be, so they are sent again.
     */
    private List<Object> made(Player player, Viewer v, List<Wire.MapPatch> made, List<Patch> patches, long cost) {
        try {
            return wire.patches(player, made);
        } catch (RuntimeException | Error e) {
            forget(v, patches);
            landed(v, patches, cost);
            throw e;
        }
    }

    /** Writes [packets], which carry [patches] costing [cost], to [player]; [together]: as one bundle. */
    private void write(Player player, Viewer v, List<Object> packets, List<Patch> patches, long cost, boolean together) {
        boolean queued = false;
        try {
            // Completes once netty has compressed the last packet and the socket took it.
            queued = wire.write(player, packets, together, ok -> {
                if (ok) bytesSent.addAndGet(cost);
                else forget(v, patches);
                if (ok && OpenTrace.on()) traced(OpenTrace.of(player.getUniqueId()), packets, patches.size(), cost);
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

    /** A traced player's [patches] patches went out in [packets], [cost] map bytes in all: what of them was compressed for all. */
    private void traced(OpenTrace.Open trace, List<Object> packets, int patches, long cost) {
        if (trace == null) return;
        long wireBytes = 0;
        int shared = 0;
        for (Object p : packets) {
            long size = wire.wireBytes(p);
            if (size >= 0) {
                wireBytes += size;
                shared++;
            }
        }
        trace.written(cost, wireBytes, patches, shared);
    }

    /**
     * Patches that never reached the client: forget that it has them, so they are sent again. Before
     * they stop counting as in flight, so delivered() never sees them as both written and sent.
     */
    private static void forget(Viewer v, List<Patch> patches) {
        for (Patch p : patches) v.sent.remove(p.id, p.tile);
        v.forgets.incrementAndGet();
    }

    /** Patches written (or given up on): no longer in flight. */
    private void landed(Viewer v, List<Patch> patches, long cost) {
        v.inFlight.addAndGet(-cost);
        for (Patch p : patches) p.wallInFlight.addAndGet(-p.cost);
        // Room in flight again: send the rest of a picture now, not at the next 8 ms run. A 16x9 picture is
        // several times the in-flight limit, so without this each remaining slice waited out a timer
        // (measured: whole picture on screen 83.6 -> 66.6 ms median).
        wake();
        // The last of a picture written: a view waiting for it can be shown now, not at the next tick.
        progressed();
    }

    /** The smallest rectangle {x, y, w, h} covering every byte that differs, or null if none does. */
    static int[] diff(byte[] previous, byte[] current) {
        if (previous == null) return new int[]{0, 0, 128, 128};
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int row = y * 128;
            // Whole rows at a time (the JIT compares these many bytes at once), then only the bytes that can widen it.
            int first = Arrays.mismatch(previous, row, row + 128, current, row, row + 128);
            if (first < 0) continue;
            if (first < minX) minX = first;
            for (int x = 127; x > maxX && x > first; x--) {
                if (previous[row + x] != current[row + x]) {
                    maxX = x;
                    break;
                }
            }
            if (first > maxX) maxX = first;
            if (minY == 128) minY = y;
            maxY = y;
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }
}

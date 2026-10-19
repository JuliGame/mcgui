package ai.resourcepack.ui.html;

import ai.resourcepack.uiframework.net.Wire;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * A {@link Wire} to one client of one wall, kept as the client keeps it: which map each frame shows and each
 * map's colours. Writes wait until {@link #deliver}, so a test can hold some in flight; a bundle is applied
 * whole, as the client applies one between two frames.
 */
final class RecordingWire implements Wire {
    record Patch(int id, int x, int y, int w, int h, byte[] colors) {}

    record Repoint(int tile, int id) {}

    private record Write(List<Object> packets, boolean together, Consumer<Boolean> done) {}

    final MapWall wall;
    private final int[] shows;
    private final Map<Integer, byte[]> maps = new HashMap<>();
    private final ArrayDeque<Write> queue = new ArrayDeque<>();
    private final Map<MapWall, int[]> pointing = new HashMap<>();
    /** Run after each bundle is applied: the client draws its next frame from this. */
    Runnable afterBundle = () -> {};
    /** Counted as delivered. */
    long patches, repoints, bundles, rawBytes;
    /** Patch colours deflated one packet at a time, as a compressed connection sends them (counted when [deflate]). */
    long deflatedBytes;
    boolean deflate;
    private final Deflater deflater = new Deflater();
    private final byte[] scratch = new byte[64 * 1024];

    RecordingWire(MapWall wall) {
        this.wall = wall;
        this.shows = new int[wall.tiles()];
        for (int t = 0; t < shows.length; t++) shows[t] = wall.mapId(t);
    }

    /** A player who is only an id: all the sender asks of one. */
    static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            case "toString" -> "player " + id;
            default -> null;
        });
    }

    @Override
    public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
        return new Patch(mapId, x, y, w, h, colors);
    }

    @Override
    public Object repoint(MapWall wall, int tile, int mapId) {
        return new Repoint(tile, mapId);
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
        queue.add(new Write(List.copyOf(packets), together, done));
        return true;
    }

    /** Whether writes are waiting. */
    boolean inFlight() {
        return !queue.isEmpty();
    }

    /** Hands every waiting write to the client, in order; false if none was waiting. */
    boolean deliver() {
        if (queue.isEmpty()) return false;
        while (!queue.isEmpty()) {
            Write w = queue.poll();
            for (Object p : w.packets) apply(p);
            if (w.together) {
                bundles++;
                afterBundle.run();
            }
            w.done.accept(true);
        }
        return true;
    }

    private void apply(Object packet) {
        if (packet instanceof Repoint r) {
            shows[r.tile] = r.id;
            repoints++;
        } else if (packet instanceof Patch p) {
            byte[] map = maps.computeIfAbsent(p.id, k -> new byte[128 * 128]);
            for (int y = 0; y < p.h; y++) System.arraycopy(p.colors, y * p.w, map, (p.y + y) * 128 + p.x, p.w);
            patches++;
            rawBytes += p.colors.length;
            if (deflate) {
                deflater.reset();
                deflater.setInput(p.colors);
                deflater.finish();
                while (!deflater.finished()) deflatedBytes += deflater.deflate(scratch);
            }
        } else {
            throw new IllegalArgumentException("Not a packet of this wire: " + packet);
        }
    }

    /** What the client shows in [tile]: the colours of the map its frame shows, or null if it has none yet. */
    byte[] shown(int tile) {
        return maps.get(shows[tile]);
    }
}

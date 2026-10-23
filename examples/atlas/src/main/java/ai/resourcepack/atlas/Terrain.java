package ai.resourcepack.atlas;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the map knows of the world: per chunk, each column's map colour, height and water depth, worked
 * out the way the game's own maps do. Only chunks that are loaded are ever read (the map never loads one),
 * so a place nobody has been near stays blank parchment, as on a real map.
 *
 * <p>The render thread says which chunks it wants ({@link #want}); the server thread takes a snapshot of
 * a few loaded ones each tick ({@link #capture}); the render thread turns them into tiles ({@link #build}).
 */
final class Terrain {
    record Key(UUID world, int x, int z) {}

    /** One chunk's columns, x + z × 16. */
    static final class Tile {
        final int[] rgb = new int[256];
        final short[] y = new short[256];
        /** Water depth in blocks, 0 on land. */
        final byte[] depth = new byte[256];
        final Material[] material = new Material[256];
        final Biome[] biome = new Biome[256];
        volatile long taken;
    }

    private record Snap(Key key, ChunkSnapshot snapshot, int minY) {}

    private static final long STALE_MS = 20_000, RETRY_MS = 5_000;
    private static final int WATER = 0x4040FF;

    private final Map<Key, Tile> tiles = new ConcurrentHashMap<>();
    /** Chunks asked for and not loaded, and when: not asked again for a while. */
    private final Map<Key, Long> missing = new ConcurrentHashMap<>();
    private final Set<Key> wanted = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Snap> taken = new ConcurrentLinkedQueue<>();
    /** Goes up whenever a tile changes, so a map that shows nothing new is not drawn again. */
    final AtomicLong version = new AtomicLong();

    Tile tile(UUID world, int cx, int cz) {
        return tiles.get(new Key(world, cx, cz));
    }

    /** The render thread would like this chunk, if it is new or out of date. */
    void want(UUID world, int cx, int cz, long now) {
        Key k = new Key(world, cx, cz);
        Tile t = tiles.get(k);
        if (t != null && now - t.taken < STALE_MS) return;
        Long miss = missing.get(k);
        if (miss != null && now - miss < RETRY_MS) return;
        if (wanted.size() < 8192) wanted.add(k);
    }

    /** A block changed in this chunk: read it again soon. */
    void stale(UUID world, int cx, int cz) {
        Tile t = tiles.get(new Key(world, cx, cz));
        if (t != null) t.taken = 0;
    }

    /** Server thread: snapshot up to [max] wanted chunks that are loaded. */
    void capture(int max) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Iterator<Key> it = wanted.iterator(); it.hasNext() && n < max; ) {
            Key k = it.next();
            it.remove();
            World w = Bukkit.getWorld(k.world());
            if (w == null || !w.isChunkLoaded(k.x(), k.z())) {
                missing.put(k, now);
                continue;
            }
            missing.remove(k);
            taken.add(new Snap(k, w.getChunkAt(k.x(), k.z()).getChunkSnapshot(true, true, false), w.getMinHeight()));
            n++;
        }
    }

    /** Render thread: turn the snapshots taken since last time into tiles. */
    void build() {
        boolean changed = false;
        for (Snap s; (s = taken.poll()) != null; ) {
            tiles.put(s.key(), tile(s.snapshot(), s.minY()));
            changed = true;
        }
        if (changed) version.incrementAndGet();
    }

    /** As the game draws a map: the highest block with a map colour, and for water, how deep it is. */
    private static Tile tile(ChunkSnapshot s, int minY) {
        Tile t = new Tile();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z << 4 | x;
                int y = s.getHighestBlockYAt(x, z);
                int rgb = 0;
                BlockData data = null;
                for (; y >= minY; y--) {
                    data = s.getBlockData(x, y, z);
                    rgb = colour(data);
                    if (rgb != 0) break;
                }
                int depth = 0;
                if (rgb == WATER) {
                    int below = y;
                    while (below > minY && colour(s.getBlockData(x, below - 1, z)) == WATER && depth < 120) {
                        below--;
                        depth++;
                    }
                    depth++;
                }
                t.rgb[i] = rgb;
                t.y[i] = (short) y;
                t.depth[i] = (byte) Math.min(127, depth);
                t.material[i] = data == null ? Material.AIR : data.getMaterial();
                t.biome[i] = s.getBiome(x, Math.max(y, minY), z);
            }
        }
        t.taken = System.currentTimeMillis();
        return t;
    }

    private static int colour(BlockData data) {
        Color c = data.getMapColor();
        return c == null ? 0 : c.asRGB();
    }
}

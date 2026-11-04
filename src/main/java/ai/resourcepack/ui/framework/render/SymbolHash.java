package ai.resourcepack.ui.framework.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A minimal perfect hash of the symbol colours, which is what makes a colour's symbol: {@link Palette} gives symbol
 * s to the colour that hashes to s, so the pack's text shader finds a map pixel's meaning with no table of symbols
 * at all, and reads the one small table it has with selects alone (Shaders' {@code mcb_symbol_of}). Never a table
 * read by a computed index: AMD's Windows OpenGL driver turns one into seconds a frame and minutes of compiling
 * (Mr Crash's RX 7700 XT, 2026-11-03: 2 FPS with the old 1,024-word table, 119 without it).
 *
 * <p>Hash and displace, with k = r << 16 | g << 8 | b: bucket = (k × mul1) >>> 26 (64 buckets), home = ((k × mul2)
 * >>> 24) × n >>> 8, and symbol = (home + displacement[bucket]) mod n, for n colours. Displacements are bytes, four
 * to an int ({@link #displacements}, 16 ints), so a lookup costs 15 selects. Java's int multiplication wraps exactly
 * like GLSL's uint, so {@link #lookup} here and the shader agree bit for bit. The search is seeded, so the same
 * colours always give the same hash, and so the same pack.
 */
public final class SymbolHash {
    public static final int BUCKET_BITS = 6;
    public static final int BUCKETS = 1 << BUCKET_BITS;

    public final int size, mul1, mul2;
    /** Bucket b's displacement in byte b % 4 (lowest first) of int b / 4. */
    public final int[] displacements = new int[BUCKETS / 4];

    /** A hash of [keys] (distinct) onto 0 to keys.length - 1. */
    public SymbolHash(int[] keys) {
        if (keys.length < 1 || keys.length > 256) throw new IllegalArgumentException("1 to 256 colours, not " + keys.length);
        size = keys.length;
        Random rnd = new Random(0);
        int[] disp = null;
        int m1 = 0, m2 = 0;
        for (int attempt = 0; attempt < 100_000 && disp == null; attempt++) {
            m1 = rnd.nextInt() | 1;
            m2 = rnd.nextInt() | 1;
            disp = place(keys, m1, m2);
        }
        if (disp == null) throw new IllegalStateException("No perfect hash for " + keys.length + " palette colours");
        mul1 = m1;
        mul2 = m2;
        for (int b = 0; b < BUCKETS; b++) displacements[b >> 2] |= disp[b] << ((b & 3) * 8);
        boolean[] seen = new boolean[size];
        for (int k : keys) {
            int s = lookup(k);
            if (seen[s]) throw new IllegalStateException("Symbol hash is not perfect at " + Integer.toHexString(k));
            seen[s] = true;
        }
    }

    /** Each bucket's displacement, biggest bucket first, so that no two keys share a symbol; null if some bucket finds none. */
    private int[] place(int[] keys, int m1, int m2) {
        List<List<Integer>> buckets = new ArrayList<>();
        for (int b = 0; b < BUCKETS; b++) buckets.add(new ArrayList<>());
        for (int k : keys) buckets.get(bucket(k, m1)).add(home(k, m2));
        List<Integer> order = new ArrayList<>();
        for (int b = 0; b < BUCKETS; b++) order.add(b);
        order.sort(Comparator.comparingInt((Integer b) -> -buckets.get(b).size()).thenComparingInt(b -> b));
        boolean[] used = new boolean[size];
        int[] disp = new int[BUCKETS];
        for (int b : order) {
            List<Integer> homes = buckets.get(b);
            if (homes.isEmpty()) break;
            int found = -1;
            int[] taken = new int[homes.size()];
            for (int d = 0; d < size && found < 0; d++) {
                boolean ok = true;
                for (int i = 0; i < homes.size() && ok; i++) {
                    int s = (homes.get(i) + d) % size;
                    if (used[s]) ok = false;
                    for (int j = 0; j < i && ok; j++) if (taken[j] == s) ok = false;
                    taken[i] = s;
                }
                if (ok) {
                    found = d;
                    for (int s : taken) used[s] = true;
                }
            }
            if (found < 0) return null;
            disp[b] = found;
        }
        return disp;
    }

    /** The symbol of colour [k], 0 to size - 1; for a colour no symbol has, whichever symbol it lands on. */
    public int lookup(int k) {
        int b = bucket(k, mul1);
        return (home(k, mul2) + (displacements[b >> 2] >>> ((b & 3) * 8) & 255)) % size;
    }

    private static int bucket(int k, int m) {
        return (k * m) >>> (32 - BUCKET_BITS);
    }

    private int home(int k, int m) {
        return ((k * m) >>> 24) * size >>> 8;
    }
}

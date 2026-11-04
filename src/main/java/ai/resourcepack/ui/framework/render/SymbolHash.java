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
 * <p>Hash and displace, with k = r << 16 | g << 8 | b: bucket = (k × mul1) >>> (32 − {@link #bucketBits}), home =
 * ((k × mul2) >>> 24) × n >>> 8, and symbol = (home + displacement[bucket]) mod n, for n colours. Displacements are
 * bytes, four to an int ({@link #displacements}), and a lookup costs a select per int less one. Java's int
 * multiplication wraps exactly like GLSL's uint, so {@link #lookup} here and the shader agree bit for bit.
 *
 * <p>64 buckets (15 selects) cost the market page 0.1 ms a frame at 1080p on an RTX 5060, 128 (31) 0.4 ms, but
 * 64 are hard to fill: a random search takes 44,000 tries for the vanilla palette (1.6 s at startup) and finds
 * none in 100,000 for 9% of random ones, where 128 take a try or two. So: vanilla's multipliers first, then a
 * short search at 64, then 128. Every step is deterministic, so the same colours always give the same pack.
 */
public final class SymbolHash {
    /** mul1, mul2 that give the vanilla map palette (1.21.11 to 26.3) a 64-bucket hash, found by the search below. */
    private static final int[][] KNOWN = {{1372926309, (int) 3137834733L}};
    private static final int SMALL_BITS = 6, LARGE_BITS = 7, SMALL_TRIES = 20_000, LARGE_TRIES = 100_000;

    public final int size, bucketBits, mul1, mul2;
    /** Bucket b's displacement in byte b % 4 (lowest first) of int b / 4. */
    public final int[] displacements;

    /** A hash of [keys] (distinct) onto 0 to keys.length - 1. */
    public SymbolHash(int[] keys) {
        if (keys.length < 1 || keys.length > 256) throw new IllegalArgumentException("1 to 256 colours, not " + keys.length);
        size = keys.length;
        int[] found = null;
        for (int[] m : KNOWN) if (found == null) found = attempt(keys, SMALL_BITS, m[0], m[1]);
        Random rnd = new Random(0);
        for (int t = 0; t < SMALL_TRIES && found == null; t++) found = attempt(keys, SMALL_BITS, rnd.nextInt() | 1, rnd.nextInt() | 1);
        rnd = new Random(0);
        for (int t = 0; t < LARGE_TRIES && found == null; t++) found = attempt(keys, LARGE_BITS, rnd.nextInt() | 1, rnd.nextInt() | 1);
        if (found == null) throw new IllegalStateException("No perfect hash for " + keys.length + " palette colours");
        bucketBits = found[0];
        mul1 = found[1];
        mul2 = found[2];
        displacements = new int[(1 << bucketBits) / 4];
        for (int b = 0; b < 1 << bucketBits; b++) displacements[b >> 2] |= found[3 + b] << ((b & 3) * 8);
        boolean[] seen = new boolean[size];
        for (int k : keys) {
            int s = lookup(k);
            if (seen[s]) throw new IllegalStateException("Symbol hash is not perfect at " + Integer.toHexString(k));
            seen[s] = true;
        }
    }

    /**
     * Each bucket's displacement, biggest bucket first, so that no two keys share a symbol: {bits, m1, m2, then one
     * displacement per bucket}, or null if some bucket finds none.
     */
    private int[] attempt(int[] keys, int bits, int m1, int m2) {
        int buckets = 1 << bits;
        List<List<Integer>> homes = new ArrayList<>();
        for (int b = 0; b < buckets; b++) homes.add(new ArrayList<>());
        for (int k : keys) homes.get(bucket(k, m1, bits)).add(home(k, m2));
        List<Integer> order = new ArrayList<>();
        for (int b = 0; b < buckets; b++) order.add(b);
        order.sort(Comparator.comparingInt((Integer b) -> -homes.get(b).size()).thenComparingInt(b -> b));
        boolean[] used = new boolean[size];
        int[] out = new int[3 + buckets];
        out[0] = bits;
        out[1] = m1;
        out[2] = m2;
        for (int b : order) {
            List<Integer> hs = homes.get(b);
            if (hs.isEmpty()) break;
            int found = -1;
            int[] taken = new int[hs.size()];
            for (int d = 0; d < size && found < 0; d++) {
                boolean ok = true;
                for (int i = 0; i < hs.size() && ok; i++) {
                    int s = (hs.get(i) + d) % size;
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
            out[3 + b] = found;
        }
        return out;
    }

    /** The symbol of colour [k], 0 to size - 1; for a colour no symbol has, whichever symbol it lands on. */
    public int lookup(int k) {
        int b = bucket(k, mul1, bucketBits);
        return (home(k, mul2) + (displacements[b >> 2] >>> ((b & 3) * 8) & 255)) % size;
    }

    private static int bucket(int k, int m, int bits) {
        return (k * m) >>> (32 - bits);
    }

    private int home(int k, int m) {
        return ((k * m) >>> 24) * size >>> 8;
    }
}

package ai.resourcepack.ui.framework.pack;

import java.util.Random;

/**
 * A perfect hash from a symbol's RGB colour back to the symbol, so the shader finds a map pixel's
 * meaning in one table read instead of scanning all 240 colours (the scan made smooth filtering too
 * slow).
 *
 * <p>slot = (k × mul) >>> (32 − BITS), with k = r << 16 | g << 8 | b: no two symbols share a slot. A slot
 * holds its symbol plus one in a byte, 0 for no symbol, four slots to an int ({@link #packed}). Java's int
 * multiplication wraps exactly like GLSL's uint, so {@link #lookup} here and the shader agree bit for bit.
 * The search is seeded, so the same palette always gives the same table, and so the same pack. It used to
 * be hash and displace (two dependent reads, a 64-entry table then a 512-entry one); one read of a bigger
 * table is cheaper on the GPU, where every pixel of a map does this up to 16 times.
 */
public final class SymbolHash {
    public static final int BITS = 12;
    public static final int SLOTS = 1 << BITS;

    public final int mul;
    /** Slot s's symbol plus one (0: none) in byte s % 4 (lowest first) of int s / 4. */
    public final int[] packed = new int[SLOTS / 4];

    public SymbolHash(int[] keys) {
        if (keys.length > 255) throw new IllegalArgumentException("A byte holds at most 255 symbols, not " + keys.length);
        boolean[] used = new boolean[SLOTS];
        Random rnd = new Random(0);
        int found = 0;
        for (int attempt = 0; attempt < 1_000_000 && found == 0; attempt++) {
            int m = rnd.nextInt() | 1;
            java.util.Arrays.fill(used, false);
            boolean ok = true;
            for (int k : keys) {
                int s = slot(k, m);
                if (used[s]) {
                    ok = false;
                    break;
                }
                used[s] = true;
            }
            if (ok) found = m;
        }
        if (found == 0) throw new IllegalStateException("No perfect hash for " + keys.length + " palette colours");
        mul = found;
        for (int i = 0; i < keys.length; i++) {
            int s = slot(keys[i], mul);
            packed[s >> 2] |= (i + 1) << ((s & 3) * 8);
        }
        for (int i = 0; i < keys.length; i++) {
            if (lookup(keys[i]) != i) throw new IllegalStateException("Symbol hash is not perfect at " + i);
        }
    }

    /** The symbol of colour [k], or -1 if [k] is no symbol's colour and lands on an empty slot. */
    public int lookup(int k) {
        int s = slot(k, mul);
        return (packed[s >> 2] >>> ((s & 3) * 8) & 255) - 1;
    }

    private static int slot(int k, int m) {
        return (k * m) >>> (32 - BITS);
    }
}

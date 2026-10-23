package ai.resourcepack.ui.framework.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A perfect hash from a symbol's RGB colour back to the symbol, so the shader finds a map pixel's
 * meaning in two table reads instead of scanning all 240 colours (the scan made smooth filtering too
 * slow).
 *
 * <p>Hash and displace: bucket = (k × mulA >>> 16) % BUCKETS, slot = ((k × mulB >>> 16) +
 * displace[bucket]) % SLOTS, with k = r << 16 | g << 8 | b. Java's int multiplication wraps exactly
 * like GLSL's uint, so {@link #lookup} here and the shader agree bit for bit. The search is seeded, so
 * the same palette always gives the same tables, and so the same pack.
 */
public final class SymbolHash {
    public static final int BUCKETS = 64;
    public static final int SLOTS = 512;

    public final int mulA;
    public final int mulB;
    public final int[] displace = new int[BUCKETS];
    public final int[] slots = new int[SLOTS];

    public SymbolHash(int[] keys) {
        int[] found = null;
        for (int attempt = 0; attempt < 1000 && found == null; attempt++) {
            Random rnd = new Random(attempt);
            int a = rnd.nextInt() | 1;
            int b = rnd.nextInt() | 1;
            if (tryBuild(keys, a, b)) found = new int[]{a, b};
        }
        if (found == null) throw new IllegalStateException("No perfect hash for " + keys.length + " palette colours");
        mulA = found[0];
        mulB = found[1];
        for (int i = 0; i < keys.length; i++) {
            if (lookup(keys[i]) != i) throw new IllegalStateException("Symbol hash is not perfect at " + i);
        }
    }

    public int lookup(int k) {
        return slots[slot(k, mulB, displace[bucket(k, mulA)])];
    }

    private boolean tryBuild(int[] keys, int a, int b) {
        Arrays.fill(slots, -1);
        Arrays.fill(displace, 0);
        Map<Integer, List<Integer>> byBucket = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) byBucket.computeIfAbsent(bucket(keys[i], a), x -> new ArrayList<>()).add(i);
        List<Map.Entry<Integer, List<Integer>>> buckets = new ArrayList<>(byBucket.entrySet());
        buckets.sort((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()));
        for (Map.Entry<Integer, List<Integer>> e : buckets) {
            List<Integer> members = e.getValue();
            int placed = -1;
            for (int d = 0; d < SLOTS && placed < 0; d++) {
                Set<Integer> targets = new HashSet<>();
                boolean ok = true;
                for (int m : members) {
                    int s = slot(keys[m], b, d);
                    if (slots[s] != -1 || !targets.add(s)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) placed = d;
            }
            if (placed < 0) return false;
            displace[e.getKey()] = placed;
            for (int m : members) slots[slot(keys[m], b, placed)] = m;
        }
        return true;
    }

    private static int bucket(int k, int a) {
        return Integer.remainderUnsigned((k * a) >>> 16, BUCKETS);
    }

    private static int slot(int k, int b, int d) {
        return Integer.remainderUnsigned(((k * b) >>> 16) + d, SLOTS);
    }
}

package ai.resourcepack.ui.html.css;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;

/**
 * The tags, ids, classes and attribute names of the elements above the one being styled, as a counting
 * Bloom filter (Blink's SelectorFilter): a rule whose {@code .nav a} needs a {@code .nav} ancestor is
 * dropped without walking the ancestors when no ancestor can have one. It may say yes wrongly, never no
 * wrongly. Names are hashed ASCII case-folded, so one filter serves documents in quirks mode (where ids
 * and classes ignore case) and out of it (where the full match then checks the case).
 */
public final class AncestorFilter {
    private static final int BITS = 12, SIZE = 1 << BITS, MASK = SIZE - 1;
    static final int TAG = 0x2F1A3B5D, ID = 0x6C8E9CF5, CLASS = 0x58B4F1E3, ATTR = 0x1D2C3B4A;

    private final int[] counts = new int[SIZE];

    /** Adds [e], whose children are about to be styled. */
    public void push(Element e) {
        add(e, 1);
    }

    /** Takes [e] out again, after its children: every push is matched by a pop of the same element. */
    public void pop(Element e) {
        add(e, -1);
    }

    /** False if some element above can certainly not have each of the names [hashes] stands for. */
    boolean mayHave(int[] hashes) {
        for (int h : hashes) if (counts[h & MASK] == 0 || counts[(h >>> BITS) & MASK] == 0) return false;
        return true;
    }

    private void add(Element e, int by) {
        bump(hash(e.normalName(), TAG), by);
        if (e.attributesSize() > 0) {
            for (Attribute a : e.attributes()) {
                String key = a.getKey();
                bump(hash(key, ATTR), by);
                if (key.equalsIgnoreCase("id")) {
                    if (!a.getValue().isEmpty()) bump(hash(a.getValue(), ID), by);
                } else if (key.equalsIgnoreCase("class")) {
                    String v = a.getValue();
                    int n = v.length();
                    for (int i = 0; i < n; ) {
                        while (i < n && Selector.space(v.charAt(i))) i++;
                        int start = i;
                        while (i < n && !Selector.space(v.charAt(i))) i++;
                        if (i > start) bump(hash(v, start, i, CLASS), by);
                    }
                }
            }
        }
    }

    private void bump(int h, int by) {
        counts[h & MASK] += by;
        counts[(h >>> BITS) & MASK] += by;
    }

    static int hash(String s, int salt) {
        return hash(s, 0, s.length(), salt);
    }

    /** A hash of s[from, to) with A-Z read as a-z; two 12-bit keys come from its low 24 bits. */
    static int hash(String s, int from, int to, int salt) {
        int h = salt;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            h = 31 * h + c;
        }
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }
}

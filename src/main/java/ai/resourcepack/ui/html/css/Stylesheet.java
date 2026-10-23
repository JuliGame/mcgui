package ai.resourcepack.ui.html.css;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every rule a page's styles add up to, in order, indexed as Blink's RuleSet is: by what the rightmost
 * part of each selector needs (an id, being the root, a class, an attribute, a tag) and by the
 * pseudo-element it styles, so matching an element only tries the rules that could apply. Built once per
 * page as its sheets are read, and only read while rendering.
 */
public final class Stylesheet {
    /** What a rule styles: the element itself, or one of its pseudo-elements. */
    public static final int ELEMENT = 0, BEFORE = 1, AFTER = 2, PLACEHOLDER = 3, MARKER = 4, KINDS = 5;

    public final List<FontFace> fontFaces = new ArrayList<>();
    /** {@code @keyframes}, by name, each in order of its offsets. */
    public final Map<String, List<Keyframe>> keyframes = new HashMap<>();
    final List<Rule> all = new ArrayList<>();
    final Index[] kinds = new Index[KINDS];
    /** Whether any rule depends on :hover or :active, so a page without one never re-renders on hover. */
    public boolean dynamic;
    /** The selector parts that say :hover (or :active): what decides whether a move of the pointer changes anything. */
    private final List<Selector.Compound> hoverParts = new ArrayList<>(), activeParts = new ArrayList<>();
    /** Of those, the parts that are a rule's subject and whose rule sets nothing but z-index: see {@link #orderOnly}. */
    private final Set<Selector.Compound> orderParts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Whether some :hover or :active is inside another pseudo-class ({@code :not(:hover)}), which no part lists. */
    private boolean nestedStates;

    /** One step of a {@code @keyframes}: where it is (0 to 1) and the values there, shorthands expanded. */
    public record Keyframe(double offset, Map<String, String> values) {}

    /** One selector of a rule, with its declarations: a rule with three selectors is three of these. */
    public record Rule(Selector selector, List<Declaration> declarations, Media media, int order) {}

    /** The rules of one kind, each in exactly one bucket. Keys are ASCII lower case, so quirks mode finds them too. */
    static final class Index {
        final Map<String, List<Rule>> byId = new HashMap<>(), byClass = new HashMap<>(), byAttr = new HashMap<>(), byTag = new HashMap<>();
        final List<Rule> root = new ArrayList<>(), universal = new ArrayList<>();
    }

    public static int kind(String pseudoElement) {
        if (pseudoElement == null) return ELEMENT;
        return switch (pseudoElement) {
            case "before" -> BEFORE;
            case "after" -> AFTER;
            case "placeholder" -> PLACEHOLDER;
            case "marker" -> MARKER;
            default -> -1;
        };
    }

    void add(List<Selector> selectors, List<Declaration> declarations, Media media) {
        for (Selector s : selectors) {
            Rule r = new Rule(s, declarations, media, all.size());
            all.add(r);
            if (s.dynamic) dynamic = true;
            for (Selector.Compound c : s.compounds) {
                boolean state = false;
                for (Selector.Pseudo p : c.pseudos) {
                    if (p.name().equals("hover") && !hoverParts.contains(c)) hoverParts.add(c);
                    if (p.name().equals("active") && !activeParts.contains(c)) activeParts.add(c);
                    state |= p.name().equals("hover") || p.name().equals("active");
                    if (p.args() != null) for (Selector a : p.args()) nestedStates |= a.dynamic;
                }
                if (state && c == s.subject() && s.pseudoElement == null && onlyZIndex(declarations)) orderParts.add(c);
            }
            int kind = kind(s.pseudoElement);
            // One that can never match is kept in the list of rules (its urls still load) but not indexed.
            if (s.never || kind < 0) continue;
            Index ix = kinds[kind];
            if (ix == null) ix = kinds[kind] = new Index();
            Selector.Compound key = s.subject();
            String attr = null;
            for (String[] a : key.attrs) {
                if (!a[0].startsWith("abs:")) {
                    attr = a[0];
                    break;
                }
            }
            if (key.id != null) bucket(ix.byId, key.id).add(r);
            else if (isRoot(key)) ix.root.add(r);
            else if (!key.classes.isEmpty()) bucket(ix.byClass, rarest(ix.byClass, key.classes)).add(r);
            else if (attr != null) bucket(ix.byAttr, attr).add(r);
            else if (key.tag != null) bucket(ix.byTag, key.tag).add(r);
            else ix.universal.add(r);
        }
    }

    /** Of a subject's classes ({@code .btn.btn-sm}), the one fewest rules so far are keyed by: likely the rarer on elements too. */
    private static String rarest(Map<String, List<Rule>> byClass, List<String> classes) {
        String best = null;
        int fewest = Integer.MAX_VALUE;
        for (String c : classes) {
            List<Rule> l = byClass.get(lower(c));
            int n = l == null ? 0 : l.size();
            if (n <= fewest) {
                best = c;
                fewest = n;
            }
        }
        return best;
    }

    private static boolean isRoot(Selector.Compound c) {
        for (Selector.Pseudo p : c.pseudos) if (p.name().equals("root")) return true;
        return false;
    }

    private static List<Rule> bucket(Map<String, List<Rule>> map, String key) {
        return map.computeIfAbsent(lower(key), k -> new ArrayList<>());
    }

    /** [s] with A-Z as a-z: the same string when there are none. */
    static String lower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] a = s.toCharArray();
                for (int k = i; k < a.length; k++) if (a[k] >= 'A' && a[k] <= 'Z') a[k] += 32;
                return new String(a);
            }
        }
        return s;
    }

    public int size() {
        return all.size();
    }

    /** Whether any rule says :hover, so that moving the pointer can change how the page looks. */
    public boolean hovers() {
        return !hoverParts.isEmpty();
    }

    /**
     * The elements whose :hover (or :active) state would change how the page looks, of [e] and its
     * ancestors, innermost first. Two pointer positions with the same list draw the same picture, so
     * moving the pointer within a button redraws nothing.
     */
    public List<Element> stateKey(Element e, boolean active) {
        List<Selector.Compound> parts = active ? activeParts : hoverParts;
        if (e == null || parts.isEmpty()) return List.of();
        org.jsoup.nodes.Document doc = e.ownerDocument();
        boolean quirks = doc != null && doc.quirksMode() == org.jsoup.nodes.Document.QuirksMode.quirks;
        List<Element> out = new ArrayList<>();
        for (Element p = e; p != null; p = p.parent()) {
            Selector.State st = active ? new Selector.State(Set.of(), Set.of(p)) : new Selector.State(Set.of(p), Set.of());
            for (Selector.Compound c : parts) {
                if (Selector.matchCompound(c, p, st, quirks)) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Whether a change of [e]'s own :hover or :active state changes only where it comes in the painting order: every
     * hover and active part that picks [e] in that state is the subject of a rule that sets z-index and nothing else
     * ({@code .card:hover { z-index: 2 }}, which lifts a card so its tooltip shows over its neighbours). Nothing in
     * [e] or round it is restyled, so only where it overlaps something it is now painted over (or under) can change.
     */
    public boolean orderOnly(Element e) {
        if (e == null || nestedStates || orderParts.isEmpty()) return false;
        org.jsoup.nodes.Document doc = e.ownerDocument();
        boolean quirks = doc != null && doc.quirksMode() == org.jsoup.nodes.Document.QuirksMode.quirks;
        boolean any = false;
        Selector.State hovered = new Selector.State(Set.of(e), Set.of()), active = new Selector.State(Set.of(), Set.of(e));
        for (Selector.Compound c : hoverParts) {
            if (!Selector.matchCompound(c, e, hovered, quirks)) continue;
            if (!orderParts.contains(c)) return false;
            any = true;
        }
        for (Selector.Compound c : activeParts) {
            if (!Selector.matchCompound(c, e, active, quirks)) continue;
            if (!orderParts.contains(c)) return false;
            any = true;
        }
        return any;
    }

    private static boolean onlyZIndex(List<Declaration> declarations) {
        if (declarations.isEmpty()) return false;
        for (Declaration d : declarations) if (!d.property().equals("z-index")) return false;
        return true;
    }

    /** Every rule, in order. */
    public List<Rule> rules() {
        return all;
    }
}

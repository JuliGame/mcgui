package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.RenderBudget;
import ai.resourcepack.ui.html.css.CssParser;
import ai.resourcepack.ui.html.css.Declaration;
import ai.resourcepack.ui.html.css.Matcher;
import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Shorthands;
import ai.resourcepack.ui.html.css.Stylesheet;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The cascade: which declarations win for each element, with {@code var()} substituted and
 * shorthands expanded, then computed into a {@link Style}. Precedence, lowest first: the browser's
 * defaults, presentational attributes ({@code <img width>}), the page's rules, {@code style=""},
 * then the same again for {@code !important} in reverse order of origin.
 *
 * <p>A pass over a document that was styled before (same sheets, same size) starts from what that pass
 * found, which is kept per document (see {@link Memo}): an element's rules that do not depend on hover
 * state are not matched again, the ones that do are tried again, and an element whose rules came out
 * the same, under a parent that came out the same, keeps its computed style. So a hover restyles only
 * what the hover can change. A document is never edited once drawn (a click edits a fresh copy), which
 * is what makes this safe.
 */
public final class Styler {
    private final Stylesheet ua;
    private final Stylesheet author;
    private final double vw, vh;
    private final Selector.State state;
    private final URI base;
    private final RenderBudget budget;

    /** Styles of elements and of their ::before / ::after / ::marker, by element. */
    public final Map<Element, Style> styles = new IdentityHashMap<>();
    public final Map<Element, Style> before = new IdentityHashMap<>();
    public final Map<Element, Style> after = new IdentityHashMap<>();
    public final Map<Element, Style> placeholder = new IdentityHashMap<>();
    public final Map<Element, Style> marker = new IdentityHashMap<>();
    private final Map<Element, Map<String, String>> declared = new IdentityHashMap<>();
    private final Map<Element, Customs> customs = new IdentityHashMap<>();
    private double rootFontSize = 16;

    private Matcher matcher;
    /** What the last pass over this document found (null if none, or once the document stops fitting it), and this pass. */
    private Memo previous, memo;
    /** The place in document order of the element being styled, counting those under display: none too. */
    private int next;
    private final List<Stylesheet.Rule> matched = new ArrayList<>(), dynamic = new ArrayList<>();
    private final Map<Shared, Shared> shared = new HashMap<>();
    private final List<Weighted> scratch = new ArrayList<>();

    /** The motion of the picture being drawn, or null: nothing moves. */
    private final Motion motion = Motion.current();

    public Styler(Stylesheet ua, Stylesheet author, double vw, double vh, Selector.State state, URI base, RenderBudget budget) {
        this.ua = ua;
        this.author = author;
        this.vw = vw;
        this.vh = vh;
        this.state = state;
        this.base = base;
        this.budget = budget;
    }

    private record Weighted(int tier, int specificity, int order, Declaration d) {}

    /** Styles [root] and everything under it. */
    public void run(Element root) {
        Document doc = root.ownerDocument();
        matcher = new Matcher(state, doc != null && doc.quirksMode() == Document.QuirksMode.quirks, vw, vh);
        for (Element p = root.parent(); p != null; p = p.parent()) matcher.ancestors.push(p);
        previous = Memo.take(root, this);
        memo = new Memo(ua, author, vw, vh, previous == null ? 64 : previous.size);
        boolean done = false;
        if (motion != null) motion.begin();
        try {
            walk(root, null, true);
            done = true;
            if (motion != null) motion.end();
        } finally {
            memo.size = next;
            // An abandoned pass leaves the last whole one, which still fits the document.
            Memo.keep(root, done ? memo : previous);
        }
    }

    private void walk(Element e, Element parent, boolean parentKept) {
        budget.check();
        int at = next++;
        Node old = previous == null ? null : previous.node(at);
        if (old != null && !old.fits(e)) {
            // Not the document the memo was made from after all: nothing further on can be trusted.
            previous = null;
            old = null;
        }
        Node node = new Node(e);
        memo.put(at, node);
        Found found = found(e, Stylesheet.ELEMENT, old);
        node.found[Stylesheet.ELEMENT] = found;
        boolean kept = parentKept && old != null && found == old.found[Stylesheet.ELEMENT];
        // What moves is worked out afresh for every picture: its values are of the moment.
        if (kept && motion != null && Motion.moves(old.declared)) kept = false;
        Style s;
        Customs own;
        Map<String, String> decl;
        if (kept) {
            s = old.style;
            own = old.customs;
            decl = old.declared;
        } else {
            Style parentStyle = parent == null ? null : styles.get(parent);
            Customs parentCustoms = parent == null ? Customs.NONE : customs.get(parent);
            Shared key = parent == null || !plain(e) ? null : new Shared(Stylesheet.ELEMENT, found, parentStyle, parentCustoms, declared.get(parent));
            Shared same = key == null ? null : shared.get(key);
            if (same != null && motion != null && Motion.moves(same.declared)) same = null;
            if (same != null) {
                s = same.style;
                own = same.customs;
                decl = same.declared;
            } else {
                List<Weighted> list = weighted(e, Stylesheet.ELEMENT, found);
                own = customProps(list, parentCustoms);
                Map<String, String> all = resolve(list, own, parent);
                if (motion != null && Motion.moves(all)) motion.apply(at, all, author);
                s = Style.compute(all, parentStyle, rootFontSize, vw, vh);
                decl = notInherited(all);
                if (key != null) shared.put(key, key.with(s, own, decl));
            }
        }
        node.style = s;
        node.customs = own;
        node.declared = decl;
        customs.put(e, own);
        declared.put(e, decl);
        if (parent == null) rootFontSize = s.fontSize;
        styles.put(e, s);
        if (!s.display.equals("none")) {
            pseudo(e, Stylesheet.BEFORE, s, own, decl, before, old, node, kept);
            pseudo(e, Stylesheet.AFTER, s, own, decl, after, old, node, kept);
            if (e.hasAttr("placeholder")) pseudo(e, Stylesheet.PLACEHOLDER, s, own, decl, placeholder, old, node, kept);
            if (s.display.equals("list-item")) pseudo(e, Stylesheet.MARKER, s, own, decl, marker, old, node, kept);
            matcher.ancestors.push(e);
            for (Element child : e.children()) walk(child, e, kept);
            matcher.ancestors.pop(e);
        } else {
            next += descendants(e);
        }
    }

    private static int descendants(Element e) {
        int n = 0;
        for (Element c = e.firstElementChild(); c != null; c = c.nextElementSibling()) n += 1 + descendants(c);
        return n;
    }

    /** Whether only rules style [e]: no style="" and no presentational attributes, so another element with its rules styles the same. */
    private boolean plain(Element e) {
        if (!e.attr("style").isBlank()) return false;
        scratch.clear();
        presentational(e, scratch);
        return scratch.isEmpty();
    }

    private void pseudo(Element e, int kind, Style parentStyle, Customs parentCustoms, Map<String, String> parentDeclared,
                        Map<Element, Style> into, Node old, Node node, boolean kept) {
        Found found = found(e, kind, old);
        node.found[kind] = found;
        Style st;
        if (kept && found == old.found[kind]) {
            st = old.pseudo[kind];
        } else if (found == Found.NONE && kind != Stylesheet.PLACEHOLDER) {
            st = null;
        } else {
            Shared key = new Shared(kind, found, parentStyle, parentCustoms, parentDeclared);
            Shared same = shared.get(key);
            if (same != null) {
                st = same.style;
            } else {
                st = null;
                List<Weighted> list = weighted(e, kind, found);
                if (!list.isEmpty() || kind == Stylesheet.PLACEHOLDER) {
                    Customs own = customProps(list, parentCustoms);
                    Map<String, String> decl = resolve(list, own, e);
                    String content = decl.get("content");
                    boolean drawn = (kind != Stylesheet.BEFORE && kind != Stylesheet.AFTER)
                            || !(content == null || content.equals("none") || content.equals("normal"));
                    if (drawn) st = Style.compute(decl, parentStyle, rootFontSize, vw, vh);
                }
                shared.put(key, key.with(st, null, null));
            }
        }
        node.pseudo[kind] = st;
        if (st != null) into.put(e, st);
    }

    /**
     * What an element (or pseudo-element) with these rules computes to under a parent with this style,
     * these custom properties and these declared values (Blink's MatchedPropertiesCache, which also needs
     * the inherited values to be the same): siblings with the same classes, and the children of those,
     * work it out once. A shared style is never one of the element's ancestors' (those had other parents).
     */
    private static final class Shared {
        final int kind;
        final Stylesheet.Rule[] rules;
        final Style parent;
        final Customs parentCustoms;
        final Map<String, String> parentDeclared;
        final int hash;
        Style style;
        Customs customs;
        Map<String, String> declared;

        Shared(int kind, Found found, Style parent, Customs parentCustoms, Map<String, String> parentDeclared) {
            this.kind = kind;
            this.rules = found.applied();
            this.parent = parent;
            this.parentCustoms = parentCustoms;
            this.parentDeclared = parentDeclared;
            int h = kind;
            for (Stylesheet.Rule r : rules) h = 31 * h + r.order();
            h = 31 * h + System.identityHashCode(parent);
            h = 31 * h + System.identityHashCode(parentCustoms);
            this.hash = 31 * h + System.identityHashCode(parentDeclared);
        }

        Shared with(Style style, Customs customs, Map<String, String> declared) {
            this.style = style;
            this.customs = customs;
            this.declared = declared;
            return this;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Shared k) || k.hash != hash || k.kind != kind || k.parent != parent || k.parentCustoms != parentCustoms
                    || k.parentDeclared != parentDeclared || k.rules.length != rules.length) return false;
            for (int i = 0; i < rules.length; i++) if (k.rules[i] != rules[i]) return false;
            return true;
        }
    }

    /**
     * The rules that style [e] as [kind]. With the [old] node of a pass over the same document, only the
     * rules that depend on hover state are tried again, and [old]'s own answer comes back if they match
     * as they did, so the caller can tell nothing changed.
     */
    private Found found(Element e, int kind, Node old) {
        Found was = old == null ? null : old.found[kind];
        if (was != null) {
            if (was.dynamic.length == 0) return was;
            boolean[] hits = new boolean[was.dynamic.length];
            for (int i = 0; i < hits.length; i++) hits[i] = matcher.matches(was.dynamic[i], e);
            return Arrays.equals(hits, was.hits) ? was : new Found(was.fixed, was.fixedUa, was.dynamic, was.dynamicUa, hits);
        }
        matched.clear();
        dynamic.clear();
        matcher.collect(ua, e, kind, matched, dynamic);
        int fixedUa = matched.size(), dynamicUa = dynamic.size();
        matcher.collect(author, e, kind, matched, dynamic);
        if (matched.isEmpty() && dynamic.isEmpty()) return Found.NONE;
        Stylesheet.Rule[] d = dynamic.toArray(Found.NO_RULES);
        boolean[] hits = new boolean[d.length];
        for (int i = 0; i < d.length; i++) hits[i] = matcher.matches(d[i], e);
        return new Found(matched.toArray(Found.NO_RULES), fixedUa, d, dynamicUa, hits);
    }

    /** Every declaration that applies to [e] (or its [kind] of pseudo-element) with [found]'s rules, lowest precedence first. */
    private List<Weighted> weighted(Element e, int kind, Found found) {
        List<Weighted> out = new ArrayList<>();
        for (int i = 0; i < found.fixed.length; i++) add(found.fixed[i], i < found.fixedUa, out);
        for (int i = 0; i < found.dynamic.length; i++) if (found.hits[i]) add(found.dynamic[i], i < found.dynamicUa, out);
        if (kind == Stylesheet.ELEMENT) {
            presentational(e, out);
            String inline = e.attr("style");
            if (!inline.isBlank()) {
                int i = 0;
                for (Declaration d : CssParser.declarations(inline, base)) out.add(new Weighted(d.important() ? 5 : 3, 0, i++, d));
            }
        }
        out.sort((a, b) -> a.tier != b.tier ? Integer.compare(a.tier, b.tier)
                : a.specificity != b.specificity ? Integer.compare(a.specificity, b.specificity) : Integer.compare(a.order, b.order));
        return out;
    }

    private static void add(Stylesheet.Rule r, boolean ua, List<Weighted> out) {
        for (Declaration d : r.declarations()) {
            int tier = d.important() ? (ua ? 6 : 4) : (ua ? 0 : 2);
            out.add(new Weighted(tier, r.selector().specificity, r.order(), d));
        }
    }

    /** The rules one element matched for one kind: those that do not depend on hover state, and those that do with whether they did. */
    private static final class Found {
        static final Stylesheet.Rule[] NO_RULES = new Stylesheet.Rule[0];
        static final Found NONE = new Found(NO_RULES, 0, NO_RULES, 0, new boolean[0]);
        /** The first fixedUa of fixed (dynamicUa of dynamic) are the browser's. */
        final Stylesheet.Rule[] fixed, dynamic;
        final int fixedUa, dynamicUa;
        final boolean[] hits;

        private Stylesheet.Rule[] applied;

        Found(Stylesheet.Rule[] fixed, int fixedUa, Stylesheet.Rule[] dynamic, int dynamicUa, boolean[] hits) {
            this.fixed = fixed;
            this.fixedUa = fixedUa;
            this.dynamic = dynamic;
            this.dynamicUa = dynamicUa;
            this.hits = hits;
        }

        /** The rules that apply: the fixed ones, then the dynamic ones that matched. */
        Stylesheet.Rule[] applied() {
            if (applied == null) {
                int n = fixed.length;
                for (boolean h : hits) if (h) n++;
                if (n == fixed.length) {
                    applied = fixed;
                } else {
                    applied = Arrays.copyOf(fixed, n);
                    for (int i = 0, k = fixed.length; i < hits.length; i++) if (hits[i]) applied[k++] = dynamic[i];
                }
            }
            return applied;
        }
    }

    /** What a pass found for one element. */
    private static final class Node {
        final String tag;
        final int children;
        final Found[] found = new Found[Stylesheet.KINDS];
        final Style[] pseudo = new Style[Stylesheet.KINDS];
        Style style;
        Customs customs;
        /** Only the properties that are not inherited: all a descendant's 'inherit' reads. */
        Map<String, String> declared;

        Node(Element e) {
            tag = e.normalName();
            children = e.childrenSize();
        }

        boolean fits(Element e) {
            return tag.equals(e.normalName()) && children == e.childrenSize();
        }
    }

    /**
     * One pass's nodes, by place in document order, kept for the next pass over the same document. Kept
     * by the root element, weakly, and holding no element: it goes when the document does.
     */
    private static final class Memo {
        private static final Map<Element, Memo> KEPT = Collections.synchronizedMap(new WeakHashMap<>());
        final Stylesheet ua, author;
        final double vw, vh;
        Node[] nodes;
        int size;

        Memo(Stylesheet ua, Stylesheet author, double vw, double vh, int capacity) {
            this.ua = ua;
            this.author = author;
            this.vw = vw;
            this.vh = vh;
            nodes = new Node[Math.max(16, capacity)];
        }

        /** The memo of [root]'s last pass if it is for the same sheets and size, taken so no other pass shares it. */
        static Memo take(Element root, Styler s) {
            Memo m = KEPT.remove(root);
            return m != null && m.ua == s.ua && m.author == s.author && m.vw == s.vw && m.vh == s.vh ? m : null;
        }

        static void keep(Element root, Memo m) {
            if (m != null) KEPT.put(root, m);
        }

        Node node(int at) {
            return at < size ? nodes[at] : null;
        }

        void put(int at, Node n) {
            if (at >= nodes.length) nodes = Arrays.copyOf(nodes, Math.max(at + 1, nodes.length * 2));
            nodes[at] = n;
        }
    }

    /** HTML attributes that style: width and height on images, bgcolor, align, color on font. */
    private static void presentational(Element e, List<Weighted> out) {
        String tag = e.normalName();
        switch (tag) {
            case "img", "svg", "video", "canvas", "iframe", "embed", "object", "table", "td", "th", "input" -> {
                dim(e, "width", out);
                dim(e, "height", out);
            }
            default -> {}
        }
        if (e.hasAttr("bgcolor")) out.add(new Weighted(1, 0, 0, new Declaration("background-color", e.attr("bgcolor"), false)));
        if (e.hasAttr("align") && !tag.equals("img")) {
            String a = e.attr("align").toLowerCase(Locale.ROOT);
            if (tag.equals("table")) {
                if (a.equals("center")) {
                    out.add(new Weighted(1, 0, 0, new Declaration("margin-left", "auto", false)));
                    out.add(new Weighted(1, 0, 0, new Declaration("margin-right", "auto", false)));
                }
            } else {
                out.add(new Weighted(1, 0, 0, new Declaration("text-align", a, false)));
            }
        }
        if (tag.equals("font") && e.hasAttr("color")) out.add(new Weighted(1, 0, 0, new Declaration("color", e.attr("color"), false)));
        if (tag.equals("table") && e.hasAttr("cellpadding")) {
            // Read by the table's cells through their own rule in the UA sheet; approximated here.
        }
    }

    private static void dim(Element e, String attr, List<Weighted> out) {
        if (!e.hasAttr(attr)) return;
        String v = e.attr(attr).trim();
        if (v.matches("\\d+(\\.\\d+)?")) v += "px";
        else if (!v.matches("\\d+(\\.\\d+)?%")) return;
        out.add(new Weighted(1, 0, 0, new Declaration(attr, v, false)));
    }

    /** The custom properties of an element: its parent's, with its own declared ones on top. */
    private static Customs customProps(List<Weighted> list, Customs parent) {
        Map<String, String> own = null;
        for (Weighted w : list) {
            String name = w.d.property();
            if (!name.startsWith("--")) continue;
            // Sheets like Tailwind's set the same few dozen on every element: the parent's serve until
            // a value differs from them.
            if (own == null) {
                if (w.d.value().equals(parent.get(name))) continue;
                own = new HashMap<>();
            }
            own.put(name, w.d.value());
        }
        return own == null ? parent : parent.with(own);
    }

    /**
     * Custom properties: an element's own over its parent's, not a copy of them (github's :root sets
     * hundreds, and an element setting one more copied them all). Past a depth the chain is flattened,
     * so a lookup stays short however deep the page.
     */
    private static final class Customs {
        static final Customs NONE = new Customs(null, Map.of(), 0);
        private static final int MAX_DEPTH = 16;
        private final Customs parent;
        private final Map<String, String> own;
        private final int depth;

        private Customs(Customs parent, Map<String, String> own, int depth) {
            this.parent = parent;
            this.own = own;
            this.depth = depth;
        }

        String get(String name) {
            for (Customs c = this; c != null; c = c.parent) {
                String v = c.own.get(name);
                if (v != null) return v;
            }
            return null;
        }

        Customs with(Map<String, String> mine) {
            if (depth < MAX_DEPTH) return new Customs(this, mine, depth + 1);
            Map<String, String> all = new HashMap<>();
            collect(all);
            all.putAll(mine);
            return new Customs(null, all, 0);
        }

        /** Every property here into [into], nearer ones winning. */
        private void collect(Map<String, String> into) {
            if (parent != null) parent.collect(into);
            into.putAll(own);
        }
    }

    /** [all] without the inherited properties, which a child takes from its parent's style, not from these. */
    private static Map<String, String> notInherited(Map<String, String> all) {
        Map<String, String> out = null;
        for (Map.Entry<String, String> en : all.entrySet()) {
            if (Style.INHERITED.contains(en.getKey())) continue;
            if (out == null) out = new HashMap<>();
            out.put(en.getKey(), en.getValue());
        }
        return out == null ? Map.of() : out;
    }

    /** The winning value of each longhand, vars substituted. */
    private Map<String, String> resolve(List<Weighted> list, Customs customs, Element parent) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Optional<String>> expanded = null;
        for (Weighted w : list) {
            String prop = w.d.property();
            if (prop.startsWith("--")) continue;
            String value = w.d.value();
            if (value.contains("var(")) {
                if (expanded == null) expanded = new HashMap<>();
                value = substitute(value, customs, expanded, 0, Integer.MAX_VALUE);
                if (value == null) value = "unset";
            }
            Shorthands.expand(prop, value, out::put);
        }
        // 'inherit' on a property that is not inherited takes the parent's declared value.
        for (Map.Entry<String, String> entry : new ArrayList<>(out.entrySet())) {
            String v = entry.getValue().trim().toLowerCase(Locale.ROOT);
            if (!v.equals("inherit") && !(v.equals("unset") && Style.INHERITED.contains(entry.getKey()))) continue;
            if (Style.INHERITED.contains(entry.getKey())) {
                out.remove(entry.getKey());
                continue;
            }
            String inherited = null;
            for (Element p = parent; p != null && inherited == null; p = p.parent()) {
                Map<String, String> d = declared.get(p);
                if (d != null) inherited = d.get(entry.getKey());
            }
            if (inherited == null || inherited.equalsIgnoreCase("inherit")) out.remove(entry.getKey());
            else out.put(entry.getKey(), inherited);
        }
        return out;
    }

    /**
     * How long a custom property may grow by substituting the var()s in its own value; longer is invalid,
     * as in browsers (both use 1 MB). Only these can grow exponentially, one level doubling the next.
     */
    private static final int MAX_EXPANDED = 1024 * 1024;

    /**
     * Replaces every var(--x, fallback); null if one has neither a value nor a fallback, or the result
     * is longer than [limit]. [expanded] holds each custom property already substituted for these
     * [customs] (empty if it was invalid), so one used twice at every level of a chain is worked out
     * once, not 2^levels times.
     */
    private static String substitute(String value, Customs customs, Map<String, Optional<String>> expanded, int depth, int limit) {
        if (depth > 16) return null;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (true) {
            int at = value.indexOf("var(", i);
            if (at < 0) {
                b.append(value, i, value.length());
                return b.length() > limit ? null : b.toString();
            }
            b.append(value, i, at);
            int close = closeParen(value, at + 3);
            if (close < 0) return null;
            String inside = value.substring(at + 4, close);
            int comma = topComma(inside);
            String name = (comma < 0 ? inside : inside.substring(0, comma)).trim();
            String fallback = comma < 0 ? null : inside.substring(comma + 1).trim();
            String custom = customs.get(name);
            String v;
            if (custom != null && !(custom.isBlank() && fallback != null)) {
                v = custom.contains("var(") ? expand(name, custom, customs, expanded, depth) : custom;
            } else {
                v = fallback != null && fallback.contains("var(") ? substitute(fallback, customs, expanded, depth + 1, limit) : fallback;
            }
            if (v == null) return null;
            b.append(v);
            if (b.length() > limit) return null;
            i = close + 1;
        }
    }

    private static String expand(String name, String custom, Customs customs, Map<String, Optional<String>> expanded, int depth) {
        Optional<String> done = expanded.get(name);
        if (done != null) return done.orElse(null);
        // Invalid while it is being worked out: a property that refers back to itself is a cycle.
        expanded.put(name, Optional.empty());
        String v = substitute(custom, customs, expanded, depth + 1, MAX_EXPANDED);
        expanded.put(name, Optional.ofNullable(v));
        return v;
    }

    private static int closeParen(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return k;
        }
        return -1;
    }

    private static int topComma(String s) {
        int depth = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return k;
        }
        return -1;
    }

    /** The browser's own stylesheet, the part of it a static page shows. */
    public static final String UA_CSS = """
            html, address, blockquote, body, dd, div, dl, dt, fieldset, form, frame, frameset, h1, h2, h3, h4, h5, h6, noframes,
            ol, p, ul, center, dir, hr, menu, pre, header, footer, nav, section, article, aside, main, figure, figcaption,
            details, summary, hgroup, legend, search, dialog[open], picture > source { display: block }
            head, script, style, title, meta, link, template, noscript, [hidden], datalist, param, source, track, area, base,
            dialog:not([open]), input[type=hidden], details:not([open]) > :not(summary) { display: none }
            html { color: canvastext; font-family: serif; font-size: 16px; line-height: normal }
            body { margin: 8px }
            li { display: list-item; text-align: match-parent }
            table { display: table; border-collapse: separate; border-spacing: 2px; box-sizing: border-box; text-indent: 0 }
            caption { display: table-caption; text-align: center }
            thead { display: table-header-group; vertical-align: middle }
            tbody { display: table-row-group; vertical-align: middle }
            tfoot { display: table-footer-group; vertical-align: middle }
            tr { display: table-row; vertical-align: inherit }
            td, th { display: table-cell; vertical-align: middle; padding: 1px }
            th { font-weight: bold; text-align: center }
            col { display: table-column } colgroup { display: table-column-group }
            h1 { font-size: 2em; margin-top: 0.67em; margin-bottom: 0.67em; font-weight: bold }
            h2 { font-size: 1.5em; margin-top: 0.83em; margin-bottom: 0.83em; font-weight: bold }
            h3 { font-size: 1.17em; margin-top: 1em; margin-bottom: 1em; font-weight: bold }
            h4 { margin-top: 1.33em; margin-bottom: 1.33em; font-weight: bold }
            h5 { font-size: 0.83em; margin-top: 1.67em; margin-bottom: 1.67em; font-weight: bold }
            h6 { font-size: 0.67em; margin-top: 2.33em; margin-bottom: 2.33em; font-weight: bold }
            p, dl, pre, menu { margin-top: 1em; margin-bottom: 1em }
            blockquote, figure { margin: 1em 40px }
            ol, ul { margin-top: 1em; margin-bottom: 1em; padding-left: 40px }
            menu { padding-left: 40px }
            ol { list-style-type: decimal }
            ul ul, ol ul { list-style-type: circle }
            ol ol ul, ol ul ul, ul ol ul, ul ul ul { list-style-type: square }
            ul ul, ul ol, ol ul, ol ol { margin-top: 0; margin-bottom: 0 }
            dd { margin-left: 40px }
            b, strong { font-weight: bolder }
            i, em, cite, var, dfn, address { font-style: italic }
            code, kbd, samp, pre, tt { font-family: monospace }
            pre { white-space: pre }
            small { font-size: smaller }
            big { font-size: larger }
            sub { vertical-align: sub; font-size: smaller }
            sup { vertical-align: super; font-size: smaller }
            a:any-link { color: #0000ee; text-decoration: underline; cursor: pointer }
            u, ins { text-decoration: underline }
            s, strike, del { text-decoration: line-through }
            mark { background-color: yellow; color: black }
            center { text-align: center }
            hr { color: gray; border-style: inset; border-width: 1px; margin: 0.5em auto; overflow: hidden }
            img, svg, video, canvas, iframe, embed, object { overflow: clip }
            button, input, select, textarea { display: inline-block; font-family: system-ui, sans-serif; font-size: 13.333px;
                font-weight: 400; color: fieldtext; letter-spacing: normal; text-transform: none; text-indent: 0; text-align: start;
                box-sizing: border-box; line-height: normal }
            input, textarea, select { background-color: field; border: 1px solid #767676; border-radius: 2px }
            input { padding: 1px 2px; width: 150px; white-space: pre }
            input[type=checkbox], input[type=radio] { width: 13px; height: 13px; padding: 0; margin: 3px 3px 3px 4px }
            input[type=radio] { border-radius: 50% }
            input[type=range] { width: 129px; height: 16px; border: none; background: transparent }
            input[type=submit], input[type=button], input[type=reset], button { background-color: #efefef; color: buttontext;
                border: 1px solid #767676; border-radius: 3px; padding: 1px 6px; text-align: center; cursor: pointer; width: auto; white-space: nowrap }
            textarea { padding: 2px; white-space: pre-wrap; width: 182px; height: 36px; vertical-align: text-bottom }
            select { padding: 1px 2px 1px 4px; white-space: nowrap }
            ::placeholder { color: #757575 }
            fieldset { margin: 0 2px; padding: 0.35em 0.75em 0.625em; border: 2px groove #c0c0c0 }
            legend { padding: 0 2px }
            summary { display: list-item; list-style: disclosure-closed inside; cursor: pointer }
            details[open] > summary { list-style-type: disclosure-open }
            progress { display: inline-block; width: 160px; height: 16px }
            meter { display: inline-block; width: 80px; height: 16px }
            """;
}

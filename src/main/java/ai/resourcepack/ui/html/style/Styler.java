package ai.resourcepack.ui.html.style;

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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cascade: which declarations win for each element, with {@code var()} substituted and
 * shorthands expanded, then computed into a {@link Style}. Precedence, lowest first: the browser's
 * defaults, presentational attributes ({@code <img width>}), the page's rules, {@code style=""},
 * then the same again for {@code !important} in reverse order of origin.
 */
public final class Styler {
    private final Stylesheet ua;
    private final Stylesheet author;
    private final double vw, vh;
    private final Selector.State state;
    private final URI base;

    /** Styles of elements and of their ::before / ::after / ::marker, by element. */
    public final Map<Element, Style> styles = new IdentityHashMap<>();
    public final Map<Element, Style> before = new IdentityHashMap<>();
    public final Map<Element, Style> after = new IdentityHashMap<>();
    public final Map<Element, Style> placeholder = new IdentityHashMap<>();
    public final Map<Element, Style> marker = new IdentityHashMap<>();
    private final Map<Element, Map<String, String>> declared = new IdentityHashMap<>();
    private final Map<Element, Map<String, String>> customs = new IdentityHashMap<>();
    private double rootFontSize = 16;

    private Matcher matcher;
    private final List<Stylesheet.Rule> matched = new ArrayList<>(), dynamic = new ArrayList<>();

    public Styler(Stylesheet ua, Stylesheet author, double vw, double vh, Selector.State state, URI base) {
        this.ua = ua;
        this.author = author;
        this.vw = vw;
        this.vh = vh;
        this.state = state;
        this.base = base;
    }

    private record Weighted(int tier, int specificity, int order, Declaration d) {}

    /** Styles [root] and everything under it. */
    public void run(Element root) {
        Document doc = root.ownerDocument();
        matcher = new Matcher(state, doc != null && doc.quirksMode() == Document.QuirksMode.quirks, vw, vh);
        for (Element p = root.parent(); p != null; p = p.parent()) matcher.ancestors.push(p);
        walk(root, null);
    }

    private void walk(Element e, Element parent) {
        Style parentStyle = parent == null ? null : styles.get(parent);
        Map<String, String> parentCustoms = parent == null ? Map.of() : customs.get(parent);
        List<Weighted> list = weighted(e, Stylesheet.ELEMENT, found(e, Stylesheet.ELEMENT));
        Map<String, String> own = customProps(list, parentCustoms);
        customs.put(e, own);
        Map<String, String> decl = resolve(list, own, parent);
        declared.put(e, decl);
        Style s = Style.compute(decl, parentStyle, rootFontSize, vw, vh);
        if (parent == null) rootFontSize = s.fontSize;
        styles.put(e, s);
        if (!s.display.equals("none")) {
            pseudo(e, Stylesheet.BEFORE, s, own, before);
            pseudo(e, Stylesheet.AFTER, s, own, after);
            if (e.hasAttr("placeholder")) pseudo(e, Stylesheet.PLACEHOLDER, s, own, placeholder);
            if (s.display.equals("list-item")) pseudo(e, Stylesheet.MARKER, s, own, marker);
            matcher.ancestors.push(e);
            for (Element child : e.children()) walk(child, e);
            matcher.ancestors.pop(e);
        }
    }

    private void pseudo(Element e, int kind, Style parentStyle, Map<String, String> parentCustoms, Map<Element, Style> into) {
        List<Weighted> list = weighted(e, kind, found(e, kind));
        if (list.isEmpty() && kind != Stylesheet.PLACEHOLDER) return;
        Map<String, String> own = customProps(list, parentCustoms);
        Map<String, String> decl = resolve(list, own, e);
        if (kind == Stylesheet.BEFORE || kind == Stylesheet.AFTER) {
            String content = decl.get("content");
            if (content == null || content.equals("none") || content.equals("normal")) return;
        }
        into.put(e, Style.compute(decl, parentStyle, rootFontSize, vw, vh));
    }

    /** The rules that style [e] as [kind]. */
    private Found found(Element e, int kind) {
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

        Found(Stylesheet.Rule[] fixed, int fixedUa, Stylesheet.Rule[] dynamic, int dynamicUa, boolean[] hits) {
            this.fixed = fixed;
            this.fixedUa = fixedUa;
            this.dynamic = dynamic;
            this.dynamicUa = dynamicUa;
            this.hits = hits;
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
    private static Map<String, String> customProps(List<Weighted> list, Map<String, String> parent) {
        Map<String, String> own = null;
        for (Weighted w : list) {
            if (!w.d.property().startsWith("--")) continue;
            if (own == null) own = new HashMap<>(parent);
            own.put(w.d.property(), w.d.value());
        }
        return own == null ? parent : own;
    }

    /** The winning value of each longhand, vars substituted. */
    private Map<String, String> resolve(List<Weighted> list, Map<String, String> customs, Element parent) {
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
    private static String substitute(String value, Map<String, String> customs, Map<String, Optional<String>> expanded, int depth, int limit) {
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

    private static String expand(String name, String custom, Map<String, String> customs, Map<String, Optional<String>> expanded, int depth) {
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

package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.css.CssParser;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.res.Resources;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns styled elements into boxes: what each display value means, anonymous blocks round loose
 * text, ::before and ::after, list markers, images, inline SVG and form controls.
 */
public final class BoxBuilder {
    private final Styler styler;
    private final Resources res;
    private final URI base;

    public BoxBuilder(Styler styler, Resources res, URI base) {
        this.styler = styler;
        this.res = res;
        this.base = base;
    }

    public Box build(Element html) {
        Style s = styler.styles.get(html);
        Box root = new Box(html, s);
        root.blockLevel = true;
        root.inner = innerOf(s.display, html);
        if (root.inner == Box.Inner.REPLACED || root.inner == Box.Inner.TEXT) root.inner = Box.Inner.FLOW;
        children(html, root);
        fix(root);
        return root;
    }

    private Box.Inner innerOf(String display, Element e) {
        if (isReplaced(e)) return Box.Inner.REPLACED;
        return switch (display) {
            case "flex", "inline-flex" -> Box.Inner.FLEX;
            case "grid", "inline-grid" -> Box.Inner.GRID;
            case "table", "inline-table" -> Box.Inner.TABLE;
            case "table-row-group", "table-header-group", "table-footer-group" -> Box.Inner.ROW_GROUP;
            case "table-row" -> Box.Inner.ROW;
            case "table-cell" -> Box.Inner.CELL;
            default -> Box.Inner.FLOW;
        };
    }

    private static boolean isReplaced(Element e) {
        return switch (e.normalName()) {
            case "img", "svg", "video", "canvas", "iframe", "embed", "object", "input", "select", "textarea", "progress", "meter" -> true;
            default -> false;
        };
    }

    /** Boxes for [e]'s children (and pseudo-elements), appended to [parent]. */
    private void children(Element e, Box parent) {
        Style ps = styler.before.get(e);
        if (ps != null) pseudo(e, ps, "before", parent);
        if (parent.inner != Box.Inner.REPLACED) {
            for (Node n : e.childNodes()) {
                if (n instanceof TextNode t) {
                    String text = t.getWholeText();
                    if (text.isEmpty()) continue;
                    Box tb = new Box(null, parent.style);
                    tb.inner = Box.Inner.TEXT;
                    tb.text = transform(text, parent.style);
                    tb.owner = e;
                    add(parent, tb);
                } else if (n instanceof Element child) {
                    element(child, parent);
                }
            }
        }
        Style as = styler.after.get(e);
        if (as != null) pseudo(e, as, "after", parent);
    }

    private void element(Element e, Box parent) {
        Style s = styler.styles.get(e);
        if (s == null || s.display.equals("none")) return;
        if (s.display.equals("contents")) {
            children(e, parent);
            return;
        }
        if (s.display.startsWith("table-column")) return;
        if (e.normalName().equals("br")) {
            Box br = new Box(e, s);
            br.inner = Box.Inner.BREAK;
            add(parent, br);
            return;
        }
        Box box = new Box(e, s);
        box.inner = innerOf(s.display, e);
        boolean blockified = parent.inner == Box.Inner.FLEX || parent.inner == Box.Inner.GRID || s.isOutOfFlow() || !s.floatSide.equals("none");
        String d = s.display;
        box.blockLevel = blockified || switch (d) {
            case "block", "list-item", "flex", "grid", "table", "table-caption", "flow-root" -> true;
            case "table-row-group", "table-header-group", "table-footer-group", "table-row", "table-cell" -> true;
            default -> false;
        };
        box.atomic = !box.blockLevel && (box.inner != Box.Inner.FLOW || d.equals("inline-block") || d.startsWith("inline-"));
        if (box.inner == Box.Inner.REPLACED) replaced(e, box);
        if (d.equals("list-item")) marker(e, box);
        children(e, box);
        add(parent, box);
    }

    private void add(Box parent, Box child) {
        child.parent = parent;
        parent.children.add(child);
    }

    /** ::before and ::after: their content as text (or an image), in a box with their style. */
    private void pseudo(Element e, Style s, String which, Box parent) {
        if (s.display.equals("none")) return;
        Box box = new Box(null, s);
        box.pseudo = which;
        box.owner = e;
        box.inner = innerOf(s.display, e) == Box.Inner.REPLACED ? Box.Inner.FLOW : innerOf(s.display, e);
        boolean blockified = parent.inner == Box.Inner.FLEX || parent.inner == Box.Inner.GRID || s.isOutOfFlow();
        box.blockLevel = blockified || s.display.equals("block") || s.display.equals("flex") || s.display.equals("grid") || s.display.equals("list-item");
        box.atomic = !box.blockLevel && !s.display.equals("inline");
        StringBuilder text = new StringBuilder();
        for (String part : Values.splitSpaces(s.content)) {
            String l = part.toLowerCase(Locale.ROOT);
            if (part.length() >= 2 && (part.charAt(0) == '"' || part.charAt(0) == '\'')) text.append(cssString(part));
            else if (l.startsWith("attr(")) text.append(e.attr(part.substring(5, part.length() - 1).trim()));
            else if (l.equals("open-quote")) text.append('“');
            else if (l.equals("close-quote")) text.append('”');
            else if (l.startsWith("url(")) {
                String uri = part.substring(4, part.length() - 1).trim();
                box.picture = res.image(stripQuotes(uri));
                if (box.picture != null) {
                    box.inner = Box.Inner.REPLACED;
                    box.atomic = !box.blockLevel;
                }
            }
        }
        if (!text.isEmpty()) {
            Box tb = new Box(null, s);
            tb.inner = Box.Inner.TEXT;
            tb.text = transform(text.toString(), s);
            tb.owner = e;
            add(box, tb);
        }
        add(parent, box);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')) return s.substring(1, s.length() - 1);
        return s;
    }

    /** A CSS string literal, with its escapes ("\201C", "\a"). */
    static String cssString(String s) {
        String inner = s.substring(1, s.length() - 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\' || i + 1 >= inner.length()) {
                b.append(c);
                continue;
            }
            int j = i + 1;
            while (j < inner.length() && j < i + 7 && Character.digit(inner.charAt(j), 16) >= 0) j++;
            if (j > i + 1) {
                int cp = Integer.parseInt(inner.substring(i + 1, j), 16);
                // CSS reads zero, a surrogate or anything past Unicode's last code point as U+FFFD.
                b.appendCodePoint(cp == 0 || cp > Character.MAX_CODE_POINT || cp >= 0xD800 && cp <= 0xDFFF ? 0xFFFD : cp);
                if (j < inner.length() && inner.charAt(j) == ' ') j++;
                i = j - 1;
            } else {
                b.append(inner.charAt(++i));
            }
        }
        return b.toString();
    }

    private static String transform(String text, Style s) {
        return switch (s.textTransform) {
            case "uppercase" -> text.toUpperCase(Locale.ROOT);
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "capitalize" -> {
                StringBuilder b = new StringBuilder(text.length());
                boolean start = true;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    b.append(start && Character.isLetter(c) ? Character.toUpperCase(c) : c);
                    start = Character.isWhitespace(c) || c == '-';
                }
                yield b.toString();
            }
            default -> text;
        };
    }

    private void marker(Element e, Box box) {
        String type = box.style.listStyleType;
        if (type.equals("none")) return;
        int index = 1;
        Element list = e.parent();
        if (list != null && list.normalName().equals("ol") && list.hasAttr("start")) {
            try {
                index = Integer.parseInt(list.attr("start").trim());
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        if (list != null) {
            for (Element sib : list.children()) {
                if (sib == e) break;
                Style ss = styler.styles.get(sib);
                if (ss != null && ss.display.equals("list-item")) index++;
            }
        }
        if (e.hasAttr("value")) {
            try {
                index = Integer.parseInt(e.attr("value").trim());
            } catch (NumberFormatException ignored) {
                // keep counted
            }
        }
        String text = switch (type) {
            case "disc" -> "•";
            case "circle" -> "◦";
            case "square" -> "▪";
            case "decimal" -> index + ".";
            case "decimal-leading-zero" -> (index < 10 ? "0" : "") + index + ".";
            // Out of their ranges these counters fall back to decimal, as CSS says: roman stops at
            // 3999, and <ol start=2147483647 type=I> would otherwise be two million M's.
            case "lower-alpha", "lower-latin" -> (index < 1 ? Integer.toString(index) : alpha(index, 'a')) + ".";
            case "upper-alpha", "upper-latin" -> (index < 1 ? Integer.toString(index) : alpha(index, 'A')) + ".";
            case "lower-roman" -> (index < 1 || index > 3999 ? Integer.toString(index) : roman(index).toLowerCase(Locale.ROOT)) + ".";
            case "upper-roman" -> (index < 1 || index > 3999 ? Integer.toString(index) : roman(index)) + ".";
            case "disclosure-closed" -> "▸";
            case "disclosure-open" -> "▾";
            default -> type.startsWith("\"") || type.startsWith("'") ? cssString(type) : "•";
        };
        Style ms = styler.marker.get(e);
        if (box.style.listStylePosition.equals("inside")) {
            // An inside marker is just text at the start of the item.
            Box tb = new Box(null, ms != null ? ms : box.style);
            tb.inner = Box.Inner.TEXT;
            tb.text = text + " ";
            tb.owner = e;
            add(box, tb);
            return;
        }
        box.marker = text;
        box.markerStyle = ms;
    }

    private static String alpha(int n, char base) {
        StringBuilder b = new StringBuilder();
        while (n > 0) {
            n--;
            b.insert(0, (char) (base + n % 26));
            n /= 26;
        }
        return b.toString();
    }

    private static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length && n > 0; i++) while (n >= v[i]) {
            b.append(s[i]);
            n -= v[i];
        }
        return b.toString();
    }

    private void replaced(Element e, Box box) {
        String tag = e.normalName();
        switch (tag) {
            case "img" -> {
                String src = e.attr("src");
                if (src.isBlank() && e.hasAttr("srcset")) src = e.attr("srcset").trim().split("[\\s,]")[0];
                box.picture = src.isBlank() ? null : res.image(CssParser.resolve(base, src));
            }
            case "video" -> box.picture = e.hasAttr("poster") ? res.image(CssParser.resolve(base, e.attr("poster"))) : null;
            case "svg" -> box.picture = res.svg(svgMarkup(e, box.style.color));
            case "object", "embed" -> {
                String src = e.hasAttr("data") ? e.attr("data") : e.attr("src");
                box.picture = src.isBlank() ? null : res.image(CssParser.resolve(base, src));
            }
            case "input" -> {
                String type = e.attr("type").toLowerCase(Locale.ROOT);
                switch (type) {
                    case "checkbox", "radio" -> {
                        box.control = type;
                        box.controlValue = e.hasAttr("checked") ? 1 : 0;
                    }
                    case "range" -> {
                        box.control = "range";
                        double min = num(e.attr("min"), 0), max = num(e.attr("max"), 100);
                        double value = num(e.attr("value"), (min + max) / 2);
                        box.controlValue = max > min ? (value - min) / (max - min) : 0;
                    }
                    case "submit", "button", "reset" -> {
                        box.control = "button";
                        box.controlText = e.hasAttr("value") ? e.attr("value") : type.equals("reset") ? "Reset" : type.equals("submit") ? "Submit" : "";
                    }
                    case "color" -> {
                        box.control = "color";
                        box.controlText = e.attr("value").isEmpty() ? "#000000" : e.attr("value");
                    }
                    default -> text(e, box, type.equals("password"));
                }
            }
            case "textarea" -> {
                box.control = "textarea";
                String v = e.wholeText();
                box.controlPlaceholder = v.isEmpty();
                box.controlText = v.isEmpty() ? e.attr("placeholder") : v;
            }
            case "select" -> {
                box.control = "select";
                Element chosen = e.selectFirst("option[selected]");
                if (chosen == null) chosen = e.selectFirst("option");
                box.controlText = chosen == null ? "" : chosen.text();
            }
            case "progress", "meter" -> {
                box.control = "progress";
                double max = num(e.attr("max"), 1), min = tag.equals("meter") ? num(e.attr("min"), 0) : 0;
                box.controlValue = max > min ? (num(e.attr("value"), 0) - min) / (max - min) : 0;
            }
            default -> {}
        }
    }

    private static void text(Element e, Box box, boolean password) {
        box.control = "text";
        String v = e.attr("value");
        box.controlPlaceholder = v.isEmpty();
        box.controlText = v.isEmpty() ? e.attr("placeholder") : password ? "•".repeat(v.length()) : v;
    }

    private static double num(String s, double fallback) {
        try {
            return s.isBlank() ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** An inline SVG as a standalone document: namespace added, currentColor made the element's colour. */
    private static String svgMarkup(Element e, int color) {
        String hex = String.format("#%06x", color & 0xFFFFFF);
        double alpha = (color >>> 24) / 255.0;
        Element copy = e.clone();
        if (!copy.hasAttr("xmlns")) copy.attr("xmlns", "http://www.w3.org/2000/svg");
        String markup = copy.outerHtml().replace("currentColor", hex).replace("currentcolor", hex);
        if (alpha < 1) markup = markup.replaceFirst("<svg", "<svg opacity=\"" + alpha + "\"");
        return markup;
    }

    // ---------------------------------------------------------------- fixing up the tree

    /** Anonymous blocks, blocks inside inline boxes, and loose text in flex and grid containers. */
    private void fix(Box box) {
        for (Box c : box.children) fix(c);
        if (box.inner == Box.Inner.TEXT || box.inner == Box.Inner.BREAK || box.inner == Box.Inner.REPLACED) return;
        // An inline box holding a block becomes a block itself (CSS splits it; this is close enough).
        if (box.isInline() && box.parent != null) {
            for (Box c : box.children) {
                if (c.blockLevel && !c.isOutOfFlow()) {
                    box.blockLevel = true;
                    break;
                }
            }
        }
        boolean container = box.inner == Box.Inner.FLEX || box.inner == Box.Inner.GRID;
        boolean anyBlock = false, anyInline = false;
        for (Box c : box.children) {
            if (c.isOutOfFlow()) continue;
            if (c.blockLevel) anyBlock = true;
            else if (!isCollapsibleSpace(c)) anyInline = true;
        }
        if (!(container || box.inner == Box.Inner.FLOW || box.inner == Box.Inner.CELL) || !(container ? anyInline : anyBlock)) {
            if (container) dropSpace(box);
            return;
        }
        // Wrap each run of inline-level children in an anonymous block (a flex or grid item, in a container).
        List<Box> out = new ArrayList<>();
        List<Box> run = new ArrayList<>();
        for (Box c : box.children) {
            if (c.blockLevel || (c.isOutOfFlow() && run.isEmpty())) {
                flushRun(box, run, out);
                out.add(c);
            } else {
                run.add(c);
            }
        }
        flushRun(box, run, out);
        box.children.clear();
        box.children.addAll(out);
    }

    private void flushRun(Box parent, List<Box> run, List<Box> out) {
        if (run.isEmpty()) return;
        boolean onlySpace = true;
        for (Box c : run) if (!isCollapsibleSpace(c) && !c.isOutOfFlow()) onlySpace = false;
        if (onlySpace) {
            for (Box c : run) if (c.isOutOfFlow()) out.add(c);
            run.clear();
            return;
        }
        Box anon = new Box(null, Style.compute(Map.of(), parent.style, parent.style.context.rootFontSize(),
                parent.style.context.viewportWidth(), parent.style.context.viewportHeight()));
        anon.blockLevel = true;
        anon.inner = Box.Inner.FLOW;
        anon.owner = parent.owner;
        anon.parent = parent;
        for (Box c : run) {
            c.parent = anon;
            anon.children.add(c);
        }
        out.add(anon);
        run.clear();
    }

    private static void dropSpace(Box box) {
        box.children.removeIf(BoxBuilder::isCollapsibleSpace);
    }

    static boolean isCollapsibleSpace(Box c) {
        if (!c.isText()) return false;
        if (c.style.whiteSpace.startsWith("pre") || c.style.whiteSpace.equals("break-spaces")) return false;
        return c.text.isBlank();
    }
}

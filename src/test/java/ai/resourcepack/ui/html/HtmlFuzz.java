package ai.resourcepack.ui.html;

import java.util.Random;

/**
 * Random pages for {@link Torture}: a small grammar over the elements, properties and values the engine
 * reads, with junk mixed in. {@link #page} is a pure function of (seed, index), so a flagged page can be
 * made again from its name alone.
 */
final class HtmlFuzz {
    private HtmlFuzz() {}

    private static final String[] PROPS = ("align-content align-items align-self aspect-ratio backdrop-filter background background-clip background-color "
            + "background-image background-position background-repeat background-size block-size border border-block border-bottom border-bottom-left-radius "
            + "border-collapse border-color border-inline border-left border-radius border-right border-spacing border-style border-top border-top-width "
            + "border-width bottom box-shadow box-sizing color column-gap columns content cursor display filter flex flex-basis flex-direction flex-flow "
            + "flex-grow flex-shrink flex-wrap float font font-family font-size font-style font-variant font-weight gap grid grid-area grid-auto-columns "
            + "grid-auto-flow grid-auto-rows grid-column grid-column-end grid-column-start grid-row grid-row-start grid-template grid-template-areas "
            + "grid-template-columns grid-template-rows height inline-size inset inset-inline justify-content justify-items justify-self left letter-spacing "
            + "line-clamp -webkit-line-clamp line-height list-style list-style-position list-style-type margin margin-block margin-bottom margin-inline "
            + "margin-left margin-right margin-top max-height max-width min-height min-width object-fit object-position opacity order outline overflow "
            + "overflow-wrap overflow-x overflow-y padding padding-block padding-bottom padding-inline padding-left padding-right padding-top place-content "
            + "place-items place-self pointer-events position right rotate row-gap scale tab-size table-layout text-align text-align-last text-decoration "
            + "text-decoration-color text-decoration-line text-decoration-style text-decoration-thickness text-indent text-overflow text-shadow "
            + "text-transform text-underline-offset text-wrap top transform transform-origin translate vertical-align visibility white-space word-break "
            + "word-spacing word-wrap z-index -webkit-text-fill-color -webkit-box-orient caret-color unknown-prop").split(" ");
    private static final String[] KEYWORDS = ("auto none normal block inline inline-block flex inline-flex grid inline-grid table table-row table-cell "
            + "table-caption list-item flow-root contents -webkit-box absolute relative fixed sticky static hidden visible scroll clip ellipsis wrap nowrap "
            + "wrap-reverse row column row-reverse column-reverse center start end flex-start flex-end space-between space-around space-evenly stretch "
            + "baseline left right top bottom middle sub super text-top text-bottom bold bolder lighter italic oblique small-caps uppercase lowercase "
            + "capitalize underline line-through overline dashed dotted solid double groove ridge inset outset pre pre-wrap pre-line break-spaces "
            + "break-all break-word anywhere keep-all cover contain fill scale-down repeat no-repeat repeat-x repeat-y space round border-box padding-box "
            + "content-box text disc circle square decimal lower-roman upper-alpha inside outside collapse separate dense min-content max-content "
            + "fit-content inherit initial unset revert revert-layer currentColor transparent serif sans-serif monospace system-ui x-large xx-small "
            + "larger smaller thick thin medium").split(" ");
    private static final String[] UNITS = {"px", "px", "px", "em", "rem", "%", "%", "vw", "vh", "vmin", "vmax", "ch", "ex", "fr", "pt", "cm", "deg", "s", "", "q", "dvh", "lh"};
    private static final String[] COLOURS = {"red", "blue", "#fff", "#0008", "#123456", "#12345678", "rgb(1 2 3)", "rgba(255,0,0,.5)", "hsl(120 50% 50%)",
            "hsla(0,100%,50%,0.3)", "transparent", "currentcolor", "rebeccapurple", "#ggg", "rgb(300,-5,1e9)", "hsl(1e9deg,200%,-1%)", "color-mix(in srgb, red, blue)",
            "oklch(0.5 0.2 120)", "black", "white"};
    private static final String[] TAGS = {"div", "div", "div", "span", "span", "p", "a", "b", "i", "em", "strong", "small", "img", "ul", "ol", "li", "table",
            "tr", "td", "th", "thead", "tbody", "tfoot", "caption", "colgroup", "col", "details", "summary", "input", "button", "select", "option", "textarea",
            "progress", "meter", "pre", "code", "h1", "h2", "h3", "h4", "br", "hr", "section", "article", "nav", "header", "footer", "main", "label", "q",
            "sup", "sub", "blockquote", "figure", "figcaption", "svg", "canvas", "video", "iframe", "dl", "dt", "dd", "fieldset", "legend", "mark", "abbr",
            "wbr", "font", "center", "marquee", "x-custom"};
    private static final String[] WORDS = {"lorem", "ipsum", "dolor", "sit", "amet", "Hello", "World", "über", "日本語", "😀", "مرحبا", "שלום", "\u200b",
            "\u00ad", "a", "supercalifragilisticexpialidocious", "&amp;", "&nbsp;", "&lt;", "{placeholder}", "\t", "  ", "x\u0301\u0302", "١٢٣", "ﬁ"};
    private static final String[] PSEUDO = {":hover", ":active", ":first-child", ":last-child", ":nth-child(2n+1)", ":nth-child(-n+3)", ":nth-of-type(odd)",
            ":not(.a)", ":has(> b)", ":has(+ p)", ":is(div,p)", ":where(.b)", ":empty", ":root", ":checked", ":focus", ":only-child", ":nth-last-child(2)",
            "::before", "::after", "::marker", "::first-line", "::placeholder", ":open", ":lang(en)", ":nth-child(99999999999n)"};

    static String page(long seed, int index) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + index * 0xBF58476D1CE4E5B9L);
        StringBuilder b = new StringBuilder(4096);
        if (r.nextInt(10) > 0) b.append("<!doctype html>");
        b.append("<html><head><style>");
        if (r.nextInt(3) == 0) b.append(":root{").append(customProps(r)).append("}");
        int rules = r.nextInt(40);
        for (int i = 0; i < rules; i++) {
            if (r.nextInt(20) == 0) b.append("@media (min-width:").append(r.nextInt(2000)).append("px){");
            b.append(selector(r)).append('{');
            int decls = 1 + r.nextInt(8);
            for (int d = 0; d < decls; d++) b.append(declaration(r)).append(';');
            b.append('}');
            if (r.nextInt(20) == 0) b.append('}');
            if (r.nextInt(50) == 0) b.append(junk(r, 20));
        }
        b.append("</style></head><body");
        if (r.nextBoolean()) b.append(" style=\"").append(inline(r)).append('"');
        b.append('>');
        int[] budget = {1 + r.nextInt(r.nextInt(10) == 0 ? 2000 : 200)};
        while (budget[0] > 0) element(r, b, 0, budget);
        if (r.nextInt(30) == 0) b.insert(r.nextInt(b.length()), junk(r, 50));
        return b.append("</body></html>").toString();
    }

    private static void element(Random r, StringBuilder b, int depth, int[] budget) {
        budget[0]--;
        String tag = TAGS[r.nextInt(TAGS.length)];
        b.append('<').append(tag);
        if (r.nextInt(3) == 0) b.append(" class=\"").append(cls(r)).append(r.nextBoolean() ? " " + cls(r) : "").append('"');
        if (r.nextInt(8) == 0) b.append(" id=i").append(r.nextInt(20));
        if (r.nextInt(3) == 0) b.append(" style=\"").append(inline(r)).append('"');
        if (r.nextInt(10) == 0) b.append(" colspan=").append(num(r));
        if (r.nextInt(10) == 0) b.append(" rowspan=").append(num(r));
        if (r.nextInt(10) == 0) b.append(" width=").append(num(r)).append(" height=").append(num(r));
        if (r.nextInt(12) == 0) b.append(r.nextBoolean() ? " open" : " checked");
        if (r.nextInt(12) == 0) b.append(" type=").append(new String[]{"checkbox", "radio", "range", "text", "submit", "color", "date", "x"}[r.nextInt(8)]);
        if (r.nextInt(15) == 0) b.append(" value=").append(num(r)).append(" max=").append(num(r));
        if (tag.equals("img") || r.nextInt(40) == 0) b.append(" src=\"").append(image(r)).append('"');
        b.append('>');
        if (tag.equals("svg")) {
            b.append("<rect width=").append(num(r)).append(" height=").append(num(r)).append(" fill=").append(COLOURS[r.nextInt(COLOURS.length)].replace(' ', '_')).append(" />");
            b.append("<circle r=").append(num(r)).append(" cx=50 cy=50 /></svg>");
            return;
        }
        int children = depth > 12 ? 0 : r.nextInt(depth < 2 ? 8 : 4);
        for (int i = 0; i < children && budget[0] > 0; i++) {
            if (r.nextInt(3) == 0) text(r, b);
            else element(r, b, depth + 1, budget);
        }
        if (children == 0 || r.nextBoolean()) text(r, b);
        if (r.nextInt(12) > 0) b.append("</").append(tag).append('>');
    }

    private static void text(Random r, StringBuilder b) {
        int n = r.nextInt(r.nextInt(20) == 0 ? 300 : 12);
        for (int i = 0; i < n; i++) b.append(WORDS[r.nextInt(WORDS.length)]).append(r.nextInt(6) == 0 ? "" : " ");
    }

    private static String selector(Random r) {
        StringBuilder s = new StringBuilder();
        int parts = 1 + r.nextInt(4);
        for (int i = 0; i < parts; i++) {
            if (i > 0) s.append(new String[]{" ", " > ", " + ", " ~ ", ", "}[r.nextInt(5)]);
            switch (r.nextInt(6)) {
                case 0 -> s.append('*');
                case 1, 2 -> s.append(TAGS[r.nextInt(TAGS.length)]);
                case 3 -> s.append('.').append(cls(r));
                case 4 -> s.append("#i").append(r.nextInt(20));
                default -> s.append('[').append(new String[]{"class", "id", "style", "open", "type"}[r.nextInt(5)])
                        .append(new String[]{"", "~=a", "^=\"c\"", "*=b", "$=x i"}[r.nextInt(5)]).append(']');
            }
            if (r.nextInt(3) == 0) s.append(PSEUDO[r.nextInt(PSEUDO.length)]);
        }
        return s.toString();
    }

    private static String cls(Random r) {
        return "c" + r.nextInt(12);
    }

    private static String inline(Random r) {
        StringBuilder s = new StringBuilder();
        int n = 1 + r.nextInt(6);
        for (int i = 0; i < n; i++) s.append(declaration(r).replace('"', '\'')).append(';');
        return s.toString();
    }

    private static String customProps(Random r) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) s.append("--v").append(i).append(':').append(i > 0 && r.nextBoolean() ? "var(--v" + r.nextInt(6) + ")" : value(r, "width")).append(';');
        return s.toString();
    }

    private static String declaration(Random r) {
        String p = PROPS[r.nextInt(PROPS.length)];
        StringBuilder v = new StringBuilder(value(r, p));
        int extra = r.nextInt(4) == 0 ? 1 + r.nextInt(4) : 0;
        for (int i = 0; i < extra; i++) v.append(r.nextInt(5) == 0 ? ", " : " ").append(value(r, p));
        if (r.nextInt(25) == 0) v.append(" !important");
        if (r.nextInt(60) == 0) v.append(junk(r, 8));
        return p + ":" + v;
    }

    private static String value(Random r, String prop) {
        int k = r.nextInt(100);
        if (k < 3) return "var(--v" + r.nextInt(8) + (r.nextBoolean() ? "" : "," + length(r)) + ")";
        if (k < 5) return junk(r, 6);
        if (prop.contains("color")) return COLOURS[r.nextInt(COLOURS.length)];
        if (prop.equals("background") || prop.equals("background-image") || prop.equals("border-image")) {
            return r.nextBoolean() ? gradient(r) : COLOURS[r.nextInt(COLOURS.length)] + (r.nextBoolean() ? " url(" + image(r) + ")" : "");
        }
        if (prop.contains("shadow")) return length(r) + " " + length(r) + " " + length(r) + (r.nextBoolean() ? " " + length(r) : "") + " " + COLOURS[r.nextInt(COLOURS.length)]
                + (r.nextInt(4) == 0 ? " inset" : "");
        if (prop.equals("transform")) return transform(r);
        if (prop.contains("filter")) return filter(r);
        if (prop.startsWith("grid-template") || prop.equals("grid-auto-columns") || prop.equals("grid-auto-rows")) {
            return switch (r.nextInt(5)) {
                case 0 -> "repeat(" + (r.nextBoolean() ? "auto-fill" : num(r)) + ", minmax(" + length(r) + ", 1fr))";
                case 1 -> "\"a b\" \"c " + (r.nextBoolean() ? "d" : "") + "\"";
                case 2 -> "[x] " + length(r) + " [y] auto";
                default -> length(r) + " " + length(r) + " 1fr";
            };
        }
        if (prop.equals("content")) return r.nextBoolean() ? "'" + WORDS[r.nextInt(WORDS.length)] + "'" : "counter(c) open-quote attr(class)";
        if (k < 50) return length(r);
        if (k < 80) return KEYWORDS[r.nextInt(KEYWORDS.length)];
        if (k < 90) return num(r);
        return COLOURS[r.nextInt(COLOURS.length)];
    }

    private static String length(Random r) {
        return switch (r.nextInt(14)) {
            case 0 -> "calc(" + length(r) + (new String[]{" + ", " - ", " * ", " / "}[r.nextInt(4)]) + (r.nextBoolean() ? num(r) : length(r)) + ")";
            case 1 -> (new String[]{"min(", "max("}[r.nextInt(2)]) + length(r) + "," + length(r) + ")";
            case 2 -> "clamp(" + length(r) + "," + length(r) + "," + length(r) + ")";
            case 3 -> "0";
            default -> num(r) + UNITS[r.nextInt(UNITS.length)];
        };
    }

    private static String num(Random r) {
        return switch (r.nextInt(16)) {
            case 0 -> "1e9";
            case 1 -> "-1e9";
            case 2 -> "1e400";
            case 3 -> Integer.toString(r.nextInt());
            case 4 -> "." + r.nextInt(100);
            case 5 -> "-" + r.nextInt(100);
            case 6 -> String.format("%.3f", r.nextGaussian() * 50);
            default -> Integer.toString(r.nextInt(400));
        };
    }

    private static String gradient(Random r) {
        StringBuilder g = new StringBuilder(new String[]{"linear-gradient(", "radial-gradient(", "conic-gradient(", "repeating-linear-gradient("}[r.nextInt(4)]);
        if (r.nextBoolean()) g.append(num(r)).append("deg, ");
        int stops = 1 + r.nextInt(5);
        for (int i = 0; i < stops; i++) g.append(i > 0 ? ", " : "").append(COLOURS[r.nextInt(COLOURS.length)]).append(r.nextBoolean() ? " " + length(r) : "");
        return g.append(')').toString();
    }

    private static String transform(Random r) {
        StringBuilder t = new StringBuilder();
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            t.append(switch (r.nextInt(8)) {
                case 0 -> "translate(" + length(r) + "," + length(r) + ")";
                case 1 -> "scale(" + num(r) + ")";
                case 2 -> "rotate(" + num(r) + "deg)";
                case 3 -> "skew(" + num(r) + "deg," + num(r) + "deg)";
                case 4 -> "matrix(" + num(r) + "," + num(r) + "," + num(r) + "," + num(r) + "," + num(r) + "," + num(r) + ")";
                case 5 -> "translateY(" + length(r) + ")";
                case 6 -> "scaleX(" + num(r) + ")";
                default -> "none";
            }).append(' ');
        }
        return t.toString().trim();
    }

    private static String filter(Random r) {
        return switch (r.nextInt(7)) {
            case 0 -> "blur(" + length(r) + ")";
            case 1 -> "drop-shadow(" + length(r) + " " + length(r) + " " + length(r) + " " + COLOURS[r.nextInt(COLOURS.length)] + ")";
            case 2 -> "brightness(" + num(r) + ")";
            case 3 -> "hue-rotate(" + num(r) + "deg) saturate(" + num(r) + "%)";
            case 4 -> "grayscale(" + num(r) + ") invert(" + num(r) + ")";
            case 5 -> "contrast(" + num(r) + ") sepia(1) opacity(" + num(r) + ")";
            default -> "url(#x)";
        };
    }

    private static String image(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='" + num(r) + "' height='" + num(r) + "'><rect width='10' height='10'/></svg>";
            case 1 -> "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
            case 2 -> "missing" + r.nextInt(3) + ".png";
            default -> "data:image/png;base64,garbage";
        };
    }

    private static String junk(Random r, int n) {
        StringBuilder s = new StringBuilder();
        String chars = "{}[]()<>;:,'\"\\/*!@#$%^&-+=~`|\u0000\u00ff\uffff\ud800";
        for (int i = 0; i < n; i++) s.append(chars.charAt(r.nextInt(chars.length())));
        return s.toString();
    }
}

package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.css.CssParser;
import ai.resourcepack.ui.html.css.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One element's computed style: every property the renderer reads, typed, with font- and
 * viewport-relative lengths already in pixels. Inherited properties start as the parent's; the rest
 * start at their initial value.
 */
public final class Style {
    public static final Set<String> INHERITED = Set.of("color", "font-family", "font-size", "font-weight", "font-style", "line-height",
            "letter-spacing", "word-spacing", "text-align", "text-transform", "white-space", "visibility", "cursor", "list-style-type",
            "list-style-position", "text-shadow", "word-break", "overflow-wrap", "word-wrap", "text-indent", "border-collapse", "border-spacing",
            "pointer-events", "-webkit-text-fill-color", "text-decoration-line", "text-decoration-color", "text-decoration-style",
            "text-decoration-thickness", "direction", "white-space-collapse", "text-wrap", "font-variant", "tab-size", "text-align-last",
            "text-underline-offset", "-webkit-text-stroke-color", "-webkit-text-stroke-width", "quotes", "hyphens");

    /** Initial values, for {@code initial} and for {@code unset} on a property that is not inherited. */
    static final Map<String, String> INITIAL = new HashMap<>();

    static {
        String[] t = {"display", "inline", "position", "static", "width", "auto", "height", "auto", "min-width", "auto", "min-height", "auto",
                "max-width", "none", "max-height", "none", "margin-top", "0", "margin-right", "0", "margin-bottom", "0", "margin-left", "0",
                "padding-top", "0", "padding-right", "0", "padding-bottom", "0", "padding-left", "0", "top", "auto", "right", "auto",
                "bottom", "auto", "left", "auto", "background-color", "transparent", "background-image", "none", "opacity", "1",
                "flex-grow", "0", "flex-shrink", "1", "flex-basis", "auto", "z-index", "auto", "overflow-x", "visible", "overflow-y", "visible",
                "transform", "none", "box-shadow", "none", "color", "black", "font-size", "16px", "font-weight", "400", "font-style", "normal",
                "line-height", "normal", "text-align", "start", "white-space", "normal", "visibility", "visible", "letter-spacing", "normal",
                "text-transform", "none", "text-decoration-line", "none", "flex-direction", "row", "flex-wrap", "nowrap",
                "justify-content", "normal", "align-items", "normal", "align-self", "auto", "align-content", "normal", "order", "0",
                "row-gap", "normal", "column-gap", "normal", "border-top-style", "none", "border-right-style", "none", "border-bottom-style", "none",
                "border-left-style", "none", "border-top-width", "medium", "border-right-width", "medium", "border-bottom-width", "medium",
                "border-left-width", "medium", "list-style-type", "disc", "cursor", "auto", "vertical-align", "baseline", "content", "normal"};
        for (int i = 0; i < t.length; i += 2) INITIAL.put(t[i], t[i + 1]);
    }

    // Box
    public String display = "inline";
    public String position = "static";
    public String floatSide = "none";
    public Len top, right, bottom, left;          // null: auto
    public Integer zIndex;                        // null: auto
    public Len width, height;                     // null: auto (or a sizing keyword, below)
    public String widthKeyword, heightKeyword;    // fit-content, max-content, min-content
    public Len minWidth, minHeight;               // null: auto
    public Len maxWidth, maxHeight;               // null: none
    public boolean borderBox;
    public final Len[] margin = {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO}; // null entries: auto
    public final Len[] padding = {Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO};
    public final double[] borderWidth = new double[4];
    public final String[] borderStyle = {"none", "none", "none", "none"};
    public final int[] borderColor = new int[4];
    final boolean[] borderColorSet = new boolean[4];
    /** Corner radii, top-left, top-right, bottom-right, bottom-left, each {horizontal, vertical}. */
    public final Len[][] radius = {{Len.ZERO, Len.ZERO}, {Len.ZERO, Len.ZERO}, {Len.ZERO, Len.ZERO}, {Len.ZERO, Len.ZERO}};
    public String overflowX = "visible", overflowY = "visible";
    public double opacity = 1;
    public String visibility = "visible";
    public Double aspectRatio;
    public String boxSizingRaw = "content-box";

    // Background
    public int backgroundColor;
    public List<String> backgroundImages = List.of();
    public List<String> backgroundPositions = List.of();
    public List<String> backgroundSizes = List.of();
    public List<String> backgroundRepeats = List.of();
    public List<String> backgroundClips = List.of();
    public String boxShadow = "none";
    public String filter = "none", backdropFilter = "none";

    // Text
    public int color = 0xFF000000;
    public List<String> fontFamilies = List.of("serif");
    public double fontSize = 16;
    public int fontWeight = 400;
    public boolean italic;
    /** line-height as a multiple of the font size, or NaN when it is a length (then {@link #lineHeightPx}) or normal. */
    public double lineHeightFactor = Double.NaN;
    public double lineHeightPx = Double.NaN;
    public double letterSpacing, wordSpacing;
    public String textAlign = "start", textTransform = "none", whiteSpace = "normal", textOverflow = "clip";
    public String textDecorationLine = "none", textDecorationStyle = "solid";
    public Integer textDecorationColor;
    public String textShadow = "none";
    public Len textIndent = Len.ZERO;
    public String verticalAlign = "baseline";
    public Len verticalAlignLength;
    public String wordBreak = "normal", overflowWrap = "normal";
    public Integer textFillColor;                 // -webkit-text-fill-color
    public int lineClamp;
    public String listStyleType = "disc", listStylePosition = "outside";
    public String cursor = "auto";
    public String pointerEvents = "auto";

    // Flex and grid
    public String flexDirection = "row", flexWrap = "nowrap";
    public String justifyContent = "normal", alignItems = "normal", alignSelf = "auto", alignContent = "normal";
    public String justifyItems = "normal", justifySelf = "auto";
    public double flexGrow, flexShrink = 1;
    public Len flexBasis;                         // null: auto
    public boolean flexBasisContent;
    public int order;
    public Len rowGap, columnGap;                 // null: normal
    public String gridTemplateColumns = "none", gridTemplateRows = "none", gridAutoRows = "auto", gridAutoColumns = "auto";
    public String gridAutoFlow = "row", gridTemplateAreas = "none", gridAreaName;
    public String gridColumnStart = "auto", gridColumnEnd = "auto", gridRowStart = "auto", gridRowEnd = "auto";

    // Other
    public String transform = "none";
    public String transformOrigin = "50% 50%";
    public String objectFit = "fill", objectPosition = "50% 50%";
    public String content = "normal";
    public String tableLayout = "auto", borderCollapse = "separate";
    public Len borderSpacing = Len.ZERO;

    /** What em, rem and the viewport units mean for this element. */
    public Len.Context context;
    private final Style parent;

    private Style(Style parent) {
        this.parent = parent;
        if (parent == null) return;
        color = parent.color;
        fontFamilies = parent.fontFamilies;
        fontSize = parent.fontSize;
        fontWeight = parent.fontWeight;
        italic = parent.italic;
        lineHeightFactor = parent.lineHeightFactor;
        lineHeightPx = parent.lineHeightPx;
        letterSpacing = parent.letterSpacing;
        wordSpacing = parent.wordSpacing;
        textAlign = parent.textAlign;
        textTransform = parent.textTransform;
        whiteSpace = parent.whiteSpace;
        visibility = parent.visibility;
        cursor = parent.cursor;
        listStyleType = parent.listStyleType;
        listStylePosition = parent.listStylePosition;
        textShadow = parent.textShadow;
        wordBreak = parent.wordBreak;
        overflowWrap = parent.overflowWrap;
        textIndent = parent.textIndent;
        borderCollapse = parent.borderCollapse;
        borderSpacing = parent.borderSpacing;
        pointerEvents = parent.pointerEvents;
        textFillColor = parent.textFillColor;
        textDecorationLine = parent.textDecorationLine;
        textDecorationColor = parent.textDecorationColor;
        textDecorationStyle = parent.textDecorationStyle;
    }

    public Style parent() {
        return parent;
    }

    /** The style of an element (or pseudo-element) whose declared values are [declared]. */
    public static Style compute(Map<String, String> declared, Style parent, double rootFontSize, double vw, double vh) {
        Style s = new Style(parent);
        double parentSize = parent == null ? 16 : parent.fontSize;
        // font-size first: every em in the other properties is this element's font size.
        String fs = declared.get("font-size");
        if (fs != null) s.fontSize(fs, parentSize, rootFontSize, vw, vh);
        s.context = new Len.Context(s.fontSize, parent == null && fs != null ? s.fontSize : rootFontSize, vw, vh);
        // color before anything that can say currentcolor.
        String col = declared.get("color");
        if (col != null) s.apply("color", col);
        for (Map.Entry<String, String> e : declared.entrySet()) {
            String p = e.getKey();
            if (p.equals("font-size") || p.equals("color") || p.startsWith("--")) continue;
            try {
                s.apply(p, e.getValue());
            } catch (RuntimeException ignored) {
                // A value we cannot read is ignored, like a browser drops an invalid declaration.
            }
        }
        // Border colours default to the element's own colour.
        for (int i = 0; i < 4; i++) if (!s.borderColorSet[i]) s.borderColor[i] = s.color;
        // A border with no style has no width.
        for (int i = 0; i < 4; i++) if (s.borderStyle[i].equals("none") || s.borderStyle[i].equals("hidden")) s.borderWidth[i] = 0;
        if (!declared.containsKey("border-top-width") && !s.borderStyle[0].equals("none")) s.borderWidth[0] = s.borderWidth[0] == 0 ? 3 : s.borderWidth[0];
        if (!declared.containsKey("border-right-width") && !s.borderStyle[1].equals("none")) s.borderWidth[1] = s.borderWidth[1] == 0 ? 3 : s.borderWidth[1];
        if (!declared.containsKey("border-bottom-width") && !s.borderStyle[2].equals("none")) s.borderWidth[2] = s.borderWidth[2] == 0 ? 3 : s.borderWidth[2];
        if (!declared.containsKey("border-left-width") && !s.borderStyle[3].equals("none")) s.borderWidth[3] = s.borderWidth[3] == 0 ? 3 : s.borderWidth[3];
        return s;
    }

    private void fontSize(String v, double parentSize, double rootFontSize, double vw, double vh) {
        String l = v.trim().toLowerCase(Locale.ROOT);
        Double kw = switch (l) {
            case "xx-small" -> 9.0;
            case "x-small" -> 10.0;
            case "small" -> 13.0;
            case "medium" -> 16.0;
            case "large" -> 18.0;
            case "x-large" -> 24.0;
            case "xx-large" -> 32.0;
            case "xxx-large" -> 48.0;
            case "smaller" -> parentSize / 1.2;
            case "larger" -> parentSize * 1.2;
            default -> null;
        };
        if (kw != null) {
            fontSize = kw;
            return;
        }
        Len len = Len.parse(v, new Len.Context(parentSize, rootFontSize, vw, vh));
        if (len != null) fontSize = Math.max(0, len.of(parentSize));
    }

    private Len len(String v) {
        return Len.parse(v, context);
    }

    private Len lenOrAuto(String v) {
        return v.trim().equalsIgnoreCase("auto") ? null : len(v);
    }

    private static int side(String p) {
        if (p.contains("top")) return 0;
        if (p.contains("right")) return 1;
        if (p.contains("bottom")) return 2;
        return 3;
    }

    void apply(String p, String raw) {
        String v = raw.trim();
        String l = v.toLowerCase(Locale.ROOT);
        if (l.equals("initial") || l.equals("revert") || l.equals("revert-layer") || (l.equals("unset") && !INHERITED.contains(p))) {
            String init = INITIAL.get(p);
            if (init == null) return;
            v = init;
            l = init;
        } else if (l.equals("unset") || l.equals("inherit")) {
            return; // handled by the cascade: inherited properties already are, the rest were replaced
        }
        switch (p) {
            case "display" -> display = displayValue(l);
            case "position" -> position = l;
            case "float" -> floatSide = l;
            case "top" -> top = lenOrAuto(v);
            case "right" -> right = lenOrAuto(v);
            case "bottom" -> bottom = lenOrAuto(v);
            case "left" -> left = lenOrAuto(v);
            case "z-index" -> zIndex = l.equals("auto") ? null : (int) num(l);
            case "width" -> {
                widthKeyword = keyword(l);
                width = widthKeyword != null ? null : lenOrAuto(v);
            }
            case "height" -> {
                heightKeyword = keyword(l);
                height = heightKeyword != null ? null : lenOrAuto(v);
            }
            case "min-width" -> minWidth = l.equals("auto") || keyword(l) != null ? null : len(v);
            case "min-height" -> minHeight = l.equals("auto") || keyword(l) != null ? null : len(v);
            case "max-width" -> maxWidth = l.equals("none") || keyword(l) != null ? null : len(v);
            case "max-height" -> maxHeight = l.equals("none") || keyword(l) != null ? null : len(v);
            case "box-sizing" -> {
                borderBox = l.equals("border-box");
                boxSizingRaw = l;
            }
            case "margin-top", "margin-right", "margin-bottom", "margin-left" -> margin[side(p)] = lenOrAuto(v);
            case "padding-top", "padding-right", "padding-bottom", "padding-left" -> {
                Len len = len(v);
                if (len != null) padding[side(p)] = len;
            }
            case "border-top-width", "border-right-width", "border-bottom-width", "border-left-width" -> {
                double w = switch (l) {
                    case "thin" -> 1;
                    case "medium" -> 3;
                    case "thick" -> 5;
                    default -> {
                        Len len = len(v);
                        yield len == null ? borderWidth[side(p)] : len.of(0);
                    }
                };
                borderWidth[side(p)] = Math.max(0, w);
            }
            case "border-top-style", "border-right-style", "border-bottom-style", "border-left-style" -> borderStyle[side(p)] = l;
            case "border-top-color", "border-right-color", "border-bottom-color", "border-left-color" -> {
                Integer c = Values.color(v, color);
                if (c != null) {
                    borderColor[side(p)] = c;
                    borderColorSet[side(p)] = true;
                }
            }
            case "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius" -> {
                int corner = switch (p) {
                    case "border-top-left-radius" -> 0;
                    case "border-top-right-radius" -> 1;
                    case "border-bottom-right-radius" -> 2;
                    default -> 3;
                };
                List<String> parts = Values.splitSpaces(v);
                Len h = len(parts.get(0)), vert = parts.size() > 1 ? len(parts.get(1)) : h;
                if (h != null && vert != null) radius[corner] = new Len[]{h, vert};
            }
            case "overflow-x" -> overflowX = l;
            case "overflow-y" -> overflowY = l;
            case "opacity" -> opacity = Math.max(0, Math.min(1, l.endsWith("%") ? num(l.substring(0, l.length() - 1)) / 100 : num(l)));
            case "visibility" -> visibility = l;
            case "aspect-ratio" -> {
                String r = l.replace("auto", "").trim();
                if (r.isEmpty()) aspectRatio = null;
                else {
                    String[] parts = r.split("/");
                    double a = num(parts[0].trim());
                    double b = parts.length > 1 ? num(parts[1].trim()) : 1;
                    aspectRatio = b == 0 ? null : a / b;
                }
            }
            case "background-color" -> {
                Integer c = Values.color(v, color);
                if (c != null) backgroundColor = c;
            }
            case "background-image" -> backgroundImages = l.equals("none") ? List.of() : Values.splitCommas(v);
            case "background-position" -> backgroundPositions = Values.splitCommas(v);
            case "background-size" -> backgroundSizes = Values.splitCommas(v);
            case "background-repeat" -> backgroundRepeats = Values.splitCommas(l);
            case "background-clip", "-webkit-background-clip" -> backgroundClips = Values.splitCommas(l);
            case "box-shadow" -> boxShadow = v;
            case "filter" -> filter = v;
            case "backdrop-filter", "-webkit-backdrop-filter" -> backdropFilter = v;
            case "color" -> {
                Integer c = Values.color(v, parent == null ? 0xFF000000 : parent.color);
                if (c != null) color = c;
            }
            case "font-family" -> {
                List<String> fams = new ArrayList<>();
                for (String f : Values.splitCommas(v)) fams.add(CssParser.resolve(null, unquote(f.trim())));
                if (!fams.isEmpty()) fontFamilies = fams;
            }
            case "font-weight" -> fontWeight = switch (l) {
                case "normal" -> 400;
                case "bold" -> 700;
                case "bolder" -> Math.min(900, (parent == null ? 400 : parent.fontWeight) + 300);
                case "lighter" -> Math.max(100, (parent == null ? 400 : parent.fontWeight) - 300);
                default -> (int) Math.max(1, Math.min(1000, num(l)));
            };
            case "font-style" -> italic = l.startsWith("italic") || l.startsWith("oblique");
            case "line-height" -> {
                if (l.equals("normal")) {
                    lineHeightFactor = Double.NaN;
                    lineHeightPx = Double.NaN;
                } else {
                    Double n = Values.number(l);
                    if (n != null) {
                        lineHeightFactor = n;
                        lineHeightPx = Double.NaN;
                    } else {
                        Len len = len(v);
                        if (len != null) {
                            lineHeightPx = len.of(fontSize);
                            lineHeightFactor = Double.NaN;
                        }
                    }
                }
            }
            case "letter-spacing" -> letterSpacing = l.equals("normal") ? 0 : len(v).of(fontSize);
            case "word-spacing" -> wordSpacing = l.equals("normal") ? 0 : len(v).of(fontSize);
            case "text-align" -> textAlign = l.equals("-webkit-center") ? "center" : l;
            case "text-transform" -> textTransform = l;
            case "white-space" -> whiteSpace = l;
            case "text-wrap", "text-wrap-mode" -> {
                if (l.equals("nowrap")) whiteSpace = whiteSpace.startsWith("pre") ? "pre" : "nowrap";
            }
            case "text-overflow" -> textOverflow = l;
            case "text-decoration-line", "-webkit-text-decoration-line" -> textDecorationLine = l;
            case "text-decoration-style" -> textDecorationStyle = l;
            case "text-decoration-color" -> textDecorationColor = Values.color(v, color);
            case "text-shadow" -> textShadow = v;
            case "text-indent" -> {
                Len len = len(v);
                if (len != null) textIndent = len;
            }
            case "vertical-align" -> {
                Len len = Set.of("baseline", "middle", "top", "bottom", "text-top", "text-bottom", "sub", "super").contains(l) ? null : len(v);
                verticalAlign = len != null ? "length" : l;
                verticalAlignLength = len;
            }
            case "word-break" -> wordBreak = l;
            case "overflow-wrap", "word-wrap" -> overflowWrap = l;
            case "-webkit-text-fill-color" -> textFillColor = l.equals("currentcolor") ? null : Values.color(v, color);
            case "-webkit-line-clamp", "line-clamp" -> lineClamp = l.equals("none") ? 0 : (int) num(l);
            case "list-style-type" -> listStyleType = l;
            case "list-style-position" -> listStylePosition = l;
            case "cursor" -> cursor = Values.splitCommas(l).get(Values.splitCommas(l).size() - 1).trim();
            case "pointer-events" -> pointerEvents = l;
            case "flex-direction" -> flexDirection = l;
            case "flex-wrap" -> flexWrap = l;
            case "justify-content" -> justifyContent = alignment(l);
            case "align-items" -> alignItems = alignment(l);
            case "align-self" -> alignSelf = alignment(l);
            case "align-content" -> alignContent = alignment(l);
            case "justify-items" -> justifyItems = alignment(l);
            case "justify-self" -> justifySelf = alignment(l);
            case "flex-grow" -> flexGrow = Math.max(0, num(l));
            case "flex-shrink" -> flexShrink = Math.max(0, num(l));
            case "flex-basis" -> {
                flexBasisContent = l.equals("content") || keyword(l) != null;
                flexBasis = l.equals("auto") || flexBasisContent ? null : len(v);
            }
            case "order" -> order = (int) num(l);
            case "row-gap" -> rowGap = l.equals("normal") ? null : len(v);
            case "column-gap" -> columnGap = l.equals("normal") ? null : len(v);
            case "grid-template-columns" -> gridTemplateColumns = v;
            case "grid-template-rows" -> gridTemplateRows = v;
            case "grid-auto-rows" -> gridAutoRows = v;
            case "grid-auto-columns" -> gridAutoColumns = v;
            case "grid-auto-flow" -> gridAutoFlow = l;
            case "grid-template-areas" -> gridTemplateAreas = v;
            case "grid-area-name" -> gridAreaName = v;
            case "grid-column-start" -> gridColumnStart = l;
            case "grid-column-end" -> gridColumnEnd = l;
            case "grid-row-start" -> gridRowStart = l;
            case "grid-row-end" -> gridRowEnd = l;
            case "transform" -> transform = v;
            case "transform-origin" -> transformOrigin = v;
            case "translate" -> transform = (transform.equals("none") ? "" : transform + " ") + "translate(" + v.replace(" ", ",") + ")";
            case "scale" -> transform = (transform.equals("none") ? "" : transform + " ") + "scale(" + v.replace(" ", ",") + ")";
            case "rotate" -> transform = (transform.equals("none") ? "" : transform + " ") + "rotate(" + v + ")";
            case "object-fit" -> objectFit = l;
            case "object-position" -> objectPosition = v;
            case "content" -> content = v;
            case "table-layout" -> tableLayout = l;
            case "border-collapse" -> borderCollapse = l;
            case "border-spacing" -> {
                Len len = len(Values.splitSpaces(v).get(0));
                if (len != null) borderSpacing = len;
            }
            default -> {}
        }
    }

    /** A number as {@link Values#number} reads it; anything else throws, which drops the declaration (see {@link #compute}). */
    private static double num(String l) {
        Double d = Values.number(l);
        if (d == null) throw new NumberFormatException(l);
        return d;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String keyword(String l) {
        return switch (l) {
            case "fit-content", "-moz-fit-content", "-webkit-fit-content", "max-content", "min-content", "-webkit-max-content", "-webkit-min-content" -> l.replace("-webkit-", "").replace("-moz-", "");
            default -> l.startsWith("fit-content(") ? "fit-content" : null;
        };
    }

    private static String displayValue(String l) {
        return switch (l) {
            case "block flow", "flow-root", "block flow-root" -> "block";
            case "inline flow" -> "inline";
            case "inline flow-root" -> "inline-block";
            case "block flex" -> "flex";
            case "inline flex" -> "inline-flex";
            case "block grid" -> "grid";
            case "inline grid" -> "inline-grid";
            case "-webkit-box" -> "block";
            case "-webkit-inline-box" -> "inline-block";
            default -> l;
        };
    }

    private static String alignment(String l) {
        // "safe center" and the like: the keyword that matters is the last one.
        l = l.replace("safe ", "").replace("unsafe ", "").replace("first ", "").replace("last ", "");
        return switch (l) {
            case "start", "self-start", "left" -> "flex-start";
            case "end", "self-end", "right" -> "flex-end";
            default -> l;
        };
    }

    // ---------------------------------------------------------------- helpers for layout and paint

    public boolean isPositioned() {
        return !position.equals("static");
    }

    public boolean isOutOfFlow() {
        return position.equals("absolute") || position.equals("fixed");
    }

    public boolean clips() {
        return !overflowX.equals("visible") || !overflowY.equals("visible");
    }

    public boolean hidden() {
        return !visibility.equals("visible");
    }

    /** The used line height in pixels for a font with this "normal" height. */
    public double lineHeight(double normal) {
        if (!Double.isNaN(lineHeightPx)) return lineHeightPx;
        if (!Double.isNaN(lineHeightFactor)) return lineHeightFactor * fontSize;
        return normal;
    }

    public boolean createsStackingContext() {
        return (isPositioned() && zIndex != null) || opacity < 1 || !transform.equals("none") || position.equals("fixed")
                || !filter.equals("none");
    }
}

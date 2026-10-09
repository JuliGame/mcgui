package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.text.TextShaper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out a box tree: block flow with margin collapsing, inline content ({@link InlineLayout}),
 * flexbox ({@link FlexLayout}), grid ({@link GridLayout}), tables ({@link TableLayout}), and
 * absolute, fixed and relative positioning. All in CSS pixels.
 *
 * <p>Every box is laid out by {@link #layout}, given the size of its containing block and,
 * optionally, a border-box size its parent has already decided (a flex item's main size, a
 * stretched cross size). A height of NaN means "not definite": percentages of it are auto.
 */
public final class Layout {
    final TextShaper shaper;
    final double vw, vh;
    final InlineLayout inline;
    final FlexLayout flex;
    final GridLayout grid;
    final TableLayout table;
    /** Absolutely positioned boxes waiting for their containing block to be sized. */
    private final Map<Box, List<Box>> pending = new IdentityHashMap<>();
    private final Map<Box, double[]> intrinsic = new IdentityHashMap<>();
    Box root;

    public Layout(TextShaper shaper, double viewportWidth, double viewportHeight) {
        this.shaper = shaper;
        this.vw = viewportWidth;
        this.vh = viewportHeight;
        this.inline = new InlineLayout(this);
        this.flex = new FlexLayout(this);
        this.grid = new GridLayout(this);
        this.table = new TableLayout(this);
    }

    /** Lays out the whole page in a viewport; returns the height of the scrollable page. */
    public double run(Box rootBox) {
        root = rootBox;
        layout(root, vw, vh, Double.NaN, Double.NaN);
        root.x = root.m[3];
        root.y = root.m[0];
        positionAbsolutes(root);
        double bottom = Math.max(vh, extent(root, root.x, root.y));
        root.overflowBottom = bottom;
        return bottom;
    }

    // ---------------------------------------------------------------- the box model

    /** Resolves margins, borders and paddings; percentages are of the containing block's width. */
    void edges(Box box, double cbW) {
        Style s = box.style;
        for (int i = 0; i < 4; i++) {
            box.m[i] = s.margin[i] == null ? 0 : s.margin[i].of(cbW);
            box.p[i] = Math.max(0, s.padding[i].of(cbW));
            box.b[i] = s.borderWidth[i];
        }
        if (box.inner == Box.Inner.TEXT) {
            for (int i = 0; i < 4; i++) box.m[i] = box.p[i] = box.b[i] = 0;
        }
    }

    double hEdges(Box box) {
        return box.b[1] + box.b[3] + box.p[1] + box.p[3];
    }

    double vEdges(Box box) {
        return box.b[0] + box.b[2] + box.p[0] + box.p[2];
    }

    /** A specified width as a border-box width, or NaN for auto (or a percentage of an unknown width). */
    double specifiedWidth(Box box, double cbW) {
        Len w = box.style.width;
        if (w == null || (w.relative() && Double.isNaN(cbW))) return Double.NaN;
        double v = w.of(cbW);
        return box.style.borderBox ? Math.max(v, hEdges(box)) : v + hEdges(box);
    }

    double specifiedHeight(Box box, double cbH) {
        Len h = box.style.height;
        if (h == null || (h.relative() && Double.isNaN(cbH))) return Double.NaN;
        double v = h.of(cbH);
        return box.style.borderBox ? Math.max(v, vEdges(box)) : v + vEdges(box);
    }

    double clampWidth(Box box, double w, double cbW) {
        Style s = box.style;
        if (s.maxWidth != null && !(s.maxWidth.relative() && Double.isNaN(cbW))) {
            double max = s.maxWidth.of(cbW) + (s.borderBox ? 0 : hEdges(box));
            w = Math.min(w, max);
        }
        if (s.minWidth != null && !(s.minWidth.relative() && Double.isNaN(cbW))) {
            double min = s.minWidth.of(cbW) + (s.borderBox ? 0 : hEdges(box));
            w = Math.max(w, min);
        }
        return Math.max(w, hEdges(box));
    }

    double clampHeight(Box box, double h, double cbH) {
        Style s = box.style;
        if (s.maxHeight != null && !(s.maxHeight.relative() && Double.isNaN(cbH))) {
            double max = s.maxHeight.of(cbH) + (s.borderBox ? 0 : vEdges(box));
            h = Math.min(h, max);
        }
        if (s.minHeight != null && !(s.minHeight.relative() && Double.isNaN(cbH))) {
            double min = s.minHeight.of(cbH) + (s.borderBox ? 0 : vEdges(box));
            h = Math.max(h, min);
        }
        return Math.max(h, vEdges(box));
    }

    /** Whether the box's width comes from its content rather than from its container. */
    boolean shrinkToFit(Box box) {
        if (box.style.widthKeyword != null) return true;
        if (box.atomic || box.isOutOfFlow() || !box.style.floatSide.equals("none")) return true;
        return box.inner == Box.Inner.TABLE;
    }

    /**
     * Lays out [box] for a containing block [cbW] × [cbH]. [forcedW]/[forcedH], when not NaN, are the
     * border-box size its parent has decided. Sets w, h, the edges and everything inside; not x and y.
     */
    void layout(Box box, double cbW, double cbH, double forcedW, double forcedH) {
        // Flex and grid lay an item out more than once (measuring, then sizing, then stretching):
        // the same inputs give the same result, so only changed ones are worked out again.
        double[] in = {cbW, cbH, forcedW, forcedH};
        if (box.laidOutWith != null && java.util.Arrays.equals(box.laidOutWith, in)) return;
        box.laidOutWith = in;
        if (box.lines != null) box.lines = null;
        clearFragments(box);
        edges(box, cbW);
        double w;
        if (!Double.isNaN(forcedW)) {
            w = forcedW;
        } else {
            w = specifiedWidth(box, cbW);
            if (Double.isNaN(w)) w = autoWidth(box, cbW, cbH);
            w = clampWidth(box, w, cbW);
        }
        box.w = w;
        // A definite height lets percentages inside resolve.
        double h = !Double.isNaN(forcedH) ? forcedH : specifiedHeight(box, cbH);
        if (Double.isNaN(h) && box.style.aspectRatio != null && box.style.height == null) {
            double cw = w - hEdges(box);
            h = box.style.borderBox ? w / box.style.aspectRatio : cw / box.style.aspectRatio + vEdges(box);
        }
        if (!Double.isNaN(h) && Double.isNaN(forcedH)) h = clampHeight(box, h, cbH);
        box.h = Double.isNaN(h) ? vEdges(box) : h;
        double contentH = Double.isNaN(h) ? Double.NaN : Math.max(0, h - vEdges(box));
        double content = contents(box, contentH);
        if (Double.isNaN(h)) box.h = clampHeight(box, content + vEdges(box), cbH);
        relativeOffsets(box);
        // The root's absolutes wait for run(), which knows where the root itself sits.
        if ((box.style.isPositioned() || !box.style.transform.equals("none")) && box != root) positionAbsolutes(box);
    }

    /** An inline box's fragments belong to its block's lines, which are about to be made again. */
    private static void clearFragments(Box box) {
        for (Box c : box.children) {
            if (c.isInline()) {
                c.fragments = null;
                clearFragments(c);
            }
        }
    }

    /** The border-box width of a box whose width is auto. */
    private double autoWidth(Box box, double cbW, double cbH) {
        if (box.inner == Box.Inner.REPLACED) return replacedWidth(box, cbW, cbH);
        double fill = Double.isNaN(cbW) ? Double.NaN : cbW - box.m[1] - box.m[3];
        String kw = box.style.widthKeyword;
        if (kw != null) {
            if (kw.equals("max-content")) return maxContent(box);
            if (kw.equals("min-content")) return minContent(box);
        }
        if (shrinkToFit(box) || Double.isNaN(fill)) {
            double max = maxContent(box);
            if (Double.isNaN(fill)) return max;
            return Math.max(minContent(box), Math.min(max, fill));
        }
        return fill;
    }

    /** Lays out what is inside [box] (its border box width already set); returns its content height. */
    private double contents(Box box, double contentH) {
        return switch (box.inner) {
            case FLEX -> flex.layout(box, contentH);
            case GRID -> grid.layout(box, contentH);
            case TABLE -> table.layout(box, contentH);
            case REPLACED -> replacedHeight(box, contentH);
            case TEXT, BREAK -> 0;
            default -> flow(box, contentH);
        };
    }

    // ---------------------------------------------------------------- block flow

    /** A block container: block children stacked, or lines of inline content. */
    double flow(Box box, double contentH) {
        boolean hasBlocks = false;
        for (Box c : box.children) if (c.blockLevel && !c.isOutOfFlow()) hasBlocks = true;
        if (!hasBlocks) return inline.layout(box, contentH);
        double cw = box.contentW();
        double cursor = box.contentY();
        // The margin owed before the next box: the bottom margin of the last one, collapsed with
        // the margins of any empty boxes since.
        double pending = 0;
        double leading = Double.NaN;
        Box firstChild = null, lastChild = null;
        for (Box c : box.children) {
            if (c.isOutOfFlow()) {
                c.staticX = box.contentX();
                c.staticY = cursor + pending;
                defer(c);
                continue;
            }
            layout(c, cw, contentH, Double.NaN, Double.NaN);
            c.x = box.contentX() + horizontalPosition(c, cw);
            if (c.h == 0 && (c.lines == null || c.lines.isEmpty()) && c.children.isEmpty() && c.inner == Box.Inner.FLOW) {
                // An empty block: its margins collapse together, and with those either side of it.
                pending = collapse(pending, collapse(c.m[0], c.m[2]));
                c.y = cursor + pending;
                continue;
            }
            double before = collapse(pending, c.m[0]);
            if (Double.isNaN(leading)) leading = before;
            c.y = cursor + before;
            cursor = c.y + c.h;
            pending = c.m[2];
            if (firstChild == null) firstChild = c;
            lastChild = c;
        }
        double end = cursor + pending;
        // The first child's top margin collapses through this box when nothing separates them.
        if (firstChild != null && leading != 0 && collapsesThrough(box, true)) {
            box.m[0] = collapse(box.m[0], leading);
            for (Box c : box.children) if (!c.isOutOfFlow()) c.y -= leading;
            for (Box c : box.children) if (c.isOutOfFlow()) c.staticY -= leading;
            end -= leading;
        }
        if (lastChild != null && pending != 0 && Double.isNaN(contentH) && collapsesThrough(box, false)) {
            box.m[2] = collapse(box.m[2], pending);
            end -= pending;
        }
        if (firstChild != null) {
            box.baseline = Double.isNaN(firstChild.baseline) ? Double.NaN : firstChild.y + firstChild.baseline;
            box.lastBaseline = Double.isNaN(lastChild.lastBaseline) ? Double.NaN : lastChild.y + lastChild.lastBaseline;
        }
        return Math.max(0, end - box.contentY());
    }

    /** Where a block child sits across its container: margins, with auto margins centring it. */
    private double horizontalPosition(Box c, double cw) {
        Len ml = c.style.margin[3], mr = c.style.margin[1];
        double free = cw - c.w - c.m[1] - c.m[3];
        if (ml == null && mr == null) {
            c.m[3] = c.m[1] = Math.max(0, free / 2);
            return c.m[3];
        }
        if (ml == null) {
            c.m[3] = Math.max(0, free);
            return c.m[3];
        }
        return c.m[3];
    }

    /** Whether a child's margin collapses with this box's at its top (or bottom). */
    private boolean collapsesThrough(Box box, boolean top) {
        if (box == root || box.parent == null) return false;
        if (box.parent.inner == Box.Inner.FLEX || box.parent.inner == Box.Inner.GRID) return false;
        if (box.atomic || box.isOutOfFlow() || box.style.clips() || box.inner != Box.Inner.FLOW) return false;
        if (box.style.display.equals("flow-root") || box.style.display.equals("inline-block")) return false;
        return top ? box.b[0] == 0 && box.p[0] == 0 : box.b[2] == 0 && box.p[2] == 0;
    }

    /** Two adjoining margins as one: the largest positive plus the most negative. */
    static double collapse(double a, double b) {
        if (a >= 0 && b >= 0) return Math.max(a, b);
        if (a < 0 && b < 0) return Math.min(a, b);
        return a + b;
    }

    // ---------------------------------------------------------------- replaced elements

    private double replacedWidth(Box box, double cbW, double cbH) {
        double[] n = natural(box);
        double hSpec = specifiedHeight(box, cbH);
        if (!Double.isNaN(hSpec) && n[1] > 0) return (hSpec - vEdges(box)) * n[0] / n[1] + hEdges(box);
        if (box.style.aspectRatio != null && !Double.isNaN(hSpec)) return (hSpec - vEdges(box)) * box.style.aspectRatio + hEdges(box);
        if (box.blockLevel && n[0] == 0 && !Double.isNaN(cbW)) return cbW - box.m[1] - box.m[3];
        return n[0] + hEdges(box);
    }

    private double replacedHeight(Box box, double contentH) {
        if (!Double.isNaN(contentH)) return contentH;
        double[] n = natural(box);
        double cw = box.contentW();
        if (box.style.aspectRatio != null) return cw / box.style.aspectRatio;
        if (n[0] > 0 && n[1] > 0) return cw * n[1] / n[0];
        return n[1];
    }

    /** A replaced box's natural content size. */
    double[] natural(Box box) {
        if (box.picture != null) {
            double w = box.picture.width(), h = box.picture.height();
            if (w > 0 && h > 0) return new double[]{w, h};
            return new double[]{w > 0 ? w : 300, h > 0 ? h : 150};
        }
        if (box.control != null) {
            double fs = box.style.fontSize;
            return switch (box.control) {
                case "checkbox", "radio" -> new double[]{13, 13};
                case "range" -> new double[]{129, 16};
                case "progress" -> new double[]{160, 16};
                case "color" -> new double[]{50, 27};
                case "textarea" -> new double[]{176, fs * 2.4};
                case "button", "select" -> {
                    double tw = inline.textWidth(box.controlText == null ? "" : box.controlText, box.style);
                    yield new double[]{tw + (box.control.equals("select") ? 20 : 0), fs * 1.2 + 2};
                }
                default -> new double[]{146, fs * 1.2 + 2};
            };
        }
        String tag = box.element == null ? "" : box.element.normalName();
        if (tag.equals("img")) return new double[]{0, 0};
        return new double[]{300, 150};
    }

    // ---------------------------------------------------------------- intrinsic widths

    /** The border-box width [box] would take with no line breaks other than forced ones. */
    double maxContent(Box box) {
        return intrinsicPair(box)[1];
    }

    /** The narrowest border-box width [box] can take without overflowing: its longest word. */
    double minContent(Box box) {
        return intrinsicPair(box)[0];
    }

    private double[] intrinsicPair(Box box) {
        double[] cached = intrinsic.get(box);
        if (cached != null) return cached;
        // Measuring must not disturb edges a layout in progress has already resolved.
        double[] saved = new double[12];
        for (int i = 0; i < 4; i++) {
            saved[i] = box.m[i];
            saved[4 + i] = box.p[i];
            saved[8 + i] = box.b[i];
        }
        double[] out = measure(box);
        for (int i = 0; i < 4; i++) {
            box.m[i] = saved[i];
            box.p[i] = saved[4 + i];
            box.b[i] = saved[8 + i];
        }
        intrinsic.put(box, out);
        return out;
    }

    private double[] measure(Box box) {
        edges(box, Double.NaN);
        double edgesW = hEdges(box);
        double spec = specifiedWidth(box, Double.NaN);
        double[] out;
        if (!Double.isNaN(spec)) {
            out = new double[]{spec, spec};
        } else if (box.inner == Box.Inner.REPLACED) {
            double[] n = natural(box);
            out = new double[]{n[0] + edgesW, n[0] + edgesW};
            // A percentage-wide image (max-width: 100%) can shrink to nothing.
            if (box.style.maxWidth != null && box.style.maxWidth.relative()) out[0] = edgesW;
        } else {
            double[] c = switch (box.inner) {
                case FLEX -> flex.intrinsic(box);
                case GRID -> grid.intrinsic(box);
                case TABLE -> table.intrinsic(box);
                default -> flowIntrinsic(box);
            };
            out = new double[]{c[0] + edgesW, c[1] + edgesW};
        }
        // max-width and min-width bound the contributions too.
        if (box.style.maxWidth != null && !box.style.maxWidth.relative()) {
            double max = box.style.maxWidth.of(0) + (box.style.borderBox ? 0 : edgesW);
            out[0] = Math.min(out[0], max);
            out[1] = Math.min(out[1], max);
        }
        if (box.style.minWidth != null && !box.style.minWidth.relative()) {
            double min = box.style.minWidth.of(0) + (box.style.borderBox ? 0 : edgesW);
            out[0] = Math.max(out[0], min);
            out[1] = Math.max(out[1], min);
        }
        out[1] = Math.max(out[0], out[1]);
        return out;
    }

    /** Content-box {min, max} of a block container. */
    private double[] flowIntrinsic(Box box) {
        boolean hasBlocks = false;
        for (Box c : box.children) if (c.blockLevel && !c.isOutOfFlow()) hasBlocks = true;
        if (!hasBlocks) return inline.intrinsic(box);
        double min = 0, max = 0;
        for (Box c : box.children) {
            if (c.isOutOfFlow()) continue;
            double margins = fixedMargins(c);
            min = Math.max(min, minContent(c) + margins);
            max = Math.max(max, maxContent(c) + margins);
        }
        return new double[]{min, max};
    }

    /** Horizontal margins that are lengths (auto and percentages count as 0 when sizing to content). */
    double fixedMargins(Box c) {
        double m = 0;
        Len l = c.style.margin[3], r = c.style.margin[1];
        if (l != null && !l.relative()) m += l.of(0);
        if (r != null && !r.relative()) m += r.of(0);
        return m;
    }

    // ---------------------------------------------------------------- positioning

    /** Shifts relatively positioned children by their offsets, after this box's own size is known. */
    private void relativeOffsets(Box box) {
        for (Box c : box.children) shiftRelative(c, box.contentW(), box.contentH());
        if (box.lines != null) {
            for (Box.Line line : box.lines) for (Box.Item item : line.items) {
                if (item.text == null && item.box != null && item.box.parent != box) shiftRelative(item.box, box.contentW(), box.contentH());
            }
        }
    }

    private void shiftRelative(Box c, double cw, double ch) {
        Style s = c.style;
        if (!s.position.equals("relative") && !s.position.equals("sticky")) return;
        if (c.isInline()) return;
        double dx = s.left != null ? s.left.of(cw) : s.right != null ? -s.right.of(cw) : 0;
        double dy = s.top != null ? s.top.of(ch) : s.bottom != null ? -s.bottom.of(ch) : 0;
        if (s.position.equals("sticky")) dy = 0;
        c.x += dx;
        c.y += dy;
    }

    /** Queues an absolutely positioned box for its containing block. */
    void defer(Box c) {
        Box cb = containingBlock(c);
        List<Box> list = pending.computeIfAbsent(cb, k -> new ArrayList<>());
        // A box laid out twice (a stretched flex item) defers its absolutes twice.
        for (Box x : list) if (x == c) return;
        list.add(c);
    }

    private Box containingBlock(Box c) {
        if (c.style.position.equals("fixed")) return root;
        for (Box p = c.parent; p != null; p = p.parent) {
            if (p == root || (p.style.isPositioned() && !p.isInline()) || !p.style.transform.equals("none")) return p;
        }
        return root;
    }

    /** The offset of [ref]'s border box from [cb]'s, following the boxes that carry coordinates. */
    double[] offsetWithin(Box ref, Box cb) {
        double x = 0, y = 0;
        for (Box b = ref; b != null && b != cb; b = b.parent) {
            if (b.isInline() && !b.isText()) continue;
            x += b.x;
            y += b.y;
        }
        return new double[]{x, y};
    }

    /** The box whose coordinates [c]'s are relative to: its nearest ancestor that is not an inline box. */
    static Box reference(Box c) {
        Box p = c.parent;
        while (p != null && p.isInline()) p = p.parent;
        return p;
    }

    void positionAbsolutes(Box cb) {
        List<Box> list = pending.remove(cb);
        if (list == null) return;
        boolean viewport = cb == root;
        double pw = viewport ? vw : cb.w - cb.b[1] - cb.b[3];
        double ph = viewport ? vh : cb.h - cb.b[0] - cb.b[2];
        for (Box c : list) {
            Box ref = reference(c);
            double[] off = ref == null ? new double[]{0, 0} : offsetWithin(ref, cb);
            // The containing block's padding box, in the containing block's coordinates.
            double px0 = viewport ? -cb.x : cb.b[3], py0 = viewport ? -cb.y : cb.b[0];
            if (c.style.position.equals("fixed")) c.fixed = true;
            absolute(c, px0, py0, pw, ph, off);
        }
    }

    private void absolute(Box c, double px0, double py0, double pw, double ph, double[] off) {
        Style s = c.style;
        edges(c, pw);
        Len L = s.left, R = s.right, T = s.top, B = s.bottom;
        double width = specifiedWidth(c, pw);
        boolean autoMarginsX = s.margin[1] == null && s.margin[3] == null;
        boolean autoMarginsY = s.margin[0] == null && s.margin[2] == null;
        if (Double.isNaN(width)) {
            if (L != null && R != null && c.inner != Box.Inner.REPLACED) {
                width = pw - L.of(pw) - R.of(pw) - c.m[1] - c.m[3];
            } else {
                double avail = pw - (L != null ? L.of(pw) : 0) - (R != null ? R.of(pw) : 0) - c.m[1] - c.m[3];
                width = c.inner == Box.Inner.REPLACED ? replacedWidth(c, pw, ph)
                        : Math.max(minContent(c), Math.min(maxContent(c), Math.max(0, avail)));
            }
        }
        width = clampWidth(c, width, pw);
        double height = specifiedHeight(c, ph);
        if (Double.isNaN(height) && T != null && B != null && c.inner != Box.Inner.REPLACED) {
            height = ph - T.of(ph) - B.of(ph) - c.m[0] - c.m[2];
        }
        if (!Double.isNaN(height)) height = clampHeight(c, height, ph);
        layout(c, pw, ph, width, height);
        double x, y;
        if (L != null && R != null && autoMarginsX) {
            double free = pw - L.of(pw) - R.of(pw) - c.w;
            x = px0 + L.of(pw) + Math.max(0, free / 2);
        } else if (L != null) {
            x = px0 + L.of(pw) + c.m[3];
        } else if (R != null) {
            x = px0 + pw - R.of(pw) - c.w - c.m[1];
        } else {
            x = off[0] + c.staticX + c.m[3];
        }
        if (T != null && B != null && autoMarginsY) {
            double free = ph - T.of(ph) - B.of(ph) - c.h;
            y = py0 + T.of(ph) + Math.max(0, free / 2);
        } else if (T != null) {
            y = py0 + T.of(ph) + c.m[0];
        } else if (B != null) {
            y = py0 + ph - B.of(ph) - c.h - c.m[2];
        } else {
            y = off[1] + c.staticY + c.m[0];
        }
        // Stored relative to the box that carries its coordinates, like every other box.
        c.x = x - off[0];
        c.y = y - off[1];
    }

    /** The bottom of the page: the lowest edge of anything not clipped away, in page coordinates. */
    private double extent(Box box, double ox, double oy) {
        double bottom = oy + box.h + (box == root ? box.m[2] : 0);
        if (box.fixed) return 0;
        if (box.style.clips() && box != root) return bottom;
        if (box.lines != null) for (Box.Line l : box.lines) bottom = Math.max(bottom, oy + l.y + l.h);
        for (Box c : box.children) {
            if (c.isText()) continue;
            boolean carries = !c.isInline();
            double cx = carries ? ox + c.x : ox, cy = carries ? oy + c.y : oy;
            bottom = Math.max(bottom, extent(c, cx, cy) + (carries ? c.m[2] : 0));
        }
        return bottom;
    }
}

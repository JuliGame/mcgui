package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code clip-path}: the shape a box and everything in it is cut to. The basic shapes ({@code polygon() circle()
 * ellipse() inset()}), each against a reference box ({@code border-box} by default, or {@code padding-box},
 * {@code content-box}, {@code margin-box}), or a reference box alone (its rounded border shape). Anything else
 * ({@code url(#id)}, {@code path()}, a typo) is no shape at all: the box is drawn uncut, as before.
 */
public final class ClipPaths {
    private ClipPaths() {}

    /** [box]'s clip-path with its border box at ([x], [y]), in the same space; null for none or one this cannot read. */
    public static Shape shape(Box box, double x, double y) {
        String v = box.style.clipPath;
        if (v == null || v.equals("none")) return null;
        try {
            return read(v, box, x, y);
        } catch (RuntimeException e) {
            return null; // a value we cannot read draws unclipped, as a browser ignores an invalid one
        }
    }

    private static Shape read(String value, Box box, double x, double y) {
        List<String> parts = Values.splitSpaces(value.trim());
        String fn = null, ref = null;
        for (String p : parts) {
            String l = p.toLowerCase(Locale.ROOT);
            if (l.endsWith(")") && l.contains("(")) {
                if (fn != null) return null;
                fn = p;
            } else if (l.equals("border-box") || l.equals("padding-box") || l.equals("content-box") || l.equals("margin-box")
                    || l.equals("fill-box") || l.equals("stroke-box") || l.equals("view-box")) {
                if (ref != null) return null;
                ref = l;
            } else {
                return null;
            }
        }
        if (fn == null && ref == null) return null;
        Style s = box.style;
        double[] b = box.b, p = box.p, m = box.m;
        // fill-box, stroke-box and view-box are for SVG elements; an HTML box uses its border box for them.
        Rectangle2D r = switch (ref == null ? "border-box" : ref) {
            case "padding-box" -> new Rectangle2D.Double(x + b[3], y + b[0], box.w - b[1] - b[3], box.h - b[0] - b[2]);
            case "content-box" -> new Rectangle2D.Double(x + b[3] + p[3], y + b[0] + p[0], box.contentW(), Math.max(0, box.h - b[0] - b[2] - p[0] - p[2]));
            case "margin-box" -> new Rectangle2D.Double(x - m[3], y - m[0], box.w + m[1] + m[3], box.h + m[0] + m[2]);
            default -> new Rectangle2D.Double(x, y, box.w, box.h);
        };
        if (fn == null) {
            // A reference box alone: its own shape, rounded corners and all.
            double[] radii = Shapes.radii(box);
            return switch (ref) {
                case "padding-box" -> Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), Shapes.inset(radii, b[0], b[1], b[2], b[3]));
                case "content-box" -> Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(),
                        Shapes.inset(radii, b[0] + p[0], b[1] + p[1], b[2] + p[2], b[3] + p[3]));
                case "margin-box" -> Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), Shapes.outset(radii, Math.max(Math.max(m[0], m[1]), Math.max(m[2], m[3]))));
                default -> Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), radii);
            };
        }
        int open = fn.indexOf('(');
        String name = fn.substring(0, open).trim().toLowerCase(Locale.ROOT);
        String args = fn.substring(open + 1, fn.length() - 1).trim();
        Len.Context c = s.context;
        return switch (name) {
            case "polygon" -> polygon(args, r, c);
            case "circle" -> circle(args, r, c);
            case "ellipse" -> ellipse(args, r, c);
            case "inset" -> inset(args, r, c);
            default -> null;
        };
    }

    private static Shape polygon(String args, Rectangle2D r, Len.Context c) {
        List<String> points = Values.splitCommas(args);
        if (points.isEmpty()) return null;
        int rule = Path2D.WIND_NON_ZERO;
        String first = points.get(0).trim().toLowerCase(Locale.ROOT);
        if (first.equals("nonzero") || first.equals("evenodd")) {
            if (first.equals("evenodd")) rule = Path2D.WIND_EVEN_ODD;
            points = points.subList(1, points.size());
        }
        Path2D.Double path = new Path2D.Double(rule);
        int n = 0;
        for (String point : points) {
            List<String> xy = Values.splitSpaces(point.trim());
            if (xy.size() != 2) return null;
            double px = r.getX() + length(xy.get(0), r.getWidth(), c), py = r.getY() + length(xy.get(1), r.getHeight(), c);
            if (n++ == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        if (n < 3) return new Rectangle2D.Double(); // a polygon of fewer than three points encloses nothing
        path.closePath();
        return path;
    }

    private static Shape circle(String args, Rectangle2D r, Len.Context c) {
        String[] split = splitAt(args);
        double[] center = position(split[1], r, c);
        List<String> radius = Values.splitSpaces(split[0]);
        if (radius.size() > 1) return null;
        double cx = center[0], cy = center[1];
        double rad;
        String v = radius.isEmpty() ? "closest-side" : radius.get(0).toLowerCase(Locale.ROOT);
        if (v.equals("closest-side")) {
            rad = Math.min(Math.min(cx - r.getX(), r.getMaxX() - cx), Math.min(cy - r.getY(), r.getMaxY() - cy));
        } else if (v.equals("farthest-side")) {
            rad = Math.max(Math.max(cx - r.getX(), r.getMaxX() - cx), Math.max(cy - r.getY(), r.getMaxY() - cy));
        } else {
            // A percentage is of the reference box's diagonal over root two.
            rad = length(v, Math.hypot(r.getWidth(), r.getHeight()) / Math.sqrt(2), c);
        }
        rad = Math.max(0, rad);
        return new Ellipse2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad);
    }

    private static Shape ellipse(String args, Rectangle2D r, Len.Context c) {
        String[] split = splitAt(args);
        double[] center = position(split[1], r, c);
        List<String> radii = Values.splitSpaces(split[0]);
        if (radii.size() == 1 || radii.size() > 2) return null;
        double cx = center[0], cy = center[1];
        double rx = radius(radii.isEmpty() ? "closest-side" : radii.get(0), cx - r.getX(), r.getMaxX() - cx, r.getWidth(), c);
        double ry = radius(radii.isEmpty() ? "closest-side" : radii.get(1), cy - r.getY(), r.getMaxY() - cy, r.getHeight(), c);
        return new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
    }

    private static double radius(String v, double before, double after, double size, Len.Context c) {
        String l = v.toLowerCase(Locale.ROOT);
        if (l.equals("closest-side")) return Math.max(0, Math.min(before, after));
        if (l.equals("farthest-side")) return Math.max(0, Math.max(before, after));
        return Math.max(0, length(v, size, c));
    }

    private static Shape inset(String args, Rectangle2D r, Len.Context c) {
        String lower = args.toLowerCase(Locale.ROOT);
        int round = indexOfWord(lower, "round");
        String edges = round < 0 ? args : args.substring(0, round);
        List<String> e = Values.splitSpaces(edges.trim());
        if (e.isEmpty() || e.size() > 4) return null;
        String top = e.get(0), right = e.size() > 1 ? e.get(1) : top, bottom = e.size() > 2 ? e.get(2) : top, left = e.size() > 3 ? e.get(3) : right;
        double t = length(top, r.getHeight(), c), rt = length(right, r.getWidth(), c), bt = length(bottom, r.getHeight(), c), lt = length(left, r.getWidth(), c);
        double x = r.getX() + lt, y = r.getY() + t, w = r.getWidth() - lt - rt, h = r.getHeight() - t - bt;
        if (w <= 0 || h <= 0) return new Rectangle2D.Double();
        double[] radii = round < 0 ? null : radii(args.substring(round + 5).trim(), w, h, c);
        return Shapes.rect(x, y, w, h, radii);
    }

    /** {@code round}'s radii, as border-radius writes them (one to four, an optional slash and one to four more), fitted to [w] x [h]. */
    private static double[] radii(String v, double w, double h, Len.Context c) {
        String[] halves = v.split("/");
        List<String> hs = Values.splitSpaces(halves[0].trim()), vs = halves.length > 1 ? Values.splitSpaces(halves[1].trim()) : hs;
        if (hs.isEmpty() || vs.isEmpty() || hs.size() > 4 || vs.size() > 4) return null;
        double[] r = new double[8];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            r[i * 2] = Math.max(0, length(corner(hs, i), w, c));
            r[i * 2 + 1] = Math.max(0, length(corner(vs, i), h, c));
            if (r[i * 2] <= 0 || r[i * 2 + 1] <= 0) r[i * 2] = r[i * 2 + 1] = 0;
            else any = true;
        }
        if (!any) return null;
        double f = 1;
        f = Math.min(f, fit(w, r[0] + r[2]));
        f = Math.min(f, fit(w, r[6] + r[4]));
        f = Math.min(f, fit(h, r[1] + r[7]));
        f = Math.min(f, fit(h, r[3] + r[5]));
        if (f < 1) for (int i = 0; i < 8; i++) r[i] *= f;
        return r;
    }

    private static double fit(double side, double sum) {
        return sum <= 0 ? 1 : side / sum;
    }

    /** Corner [i] (top-left, top-right, bottom-right, bottom-left) of a one-to-four value list, as border-radius fills them in. */
    private static String corner(List<String> v, int i) {
        return switch (v.size()) {
            case 1 -> v.get(0);
            case 2 -> v.get(i % 2);
            case 3 -> i == 3 ? v.get(1) : v.get(i);
            default -> v.get(i);
        };
    }

    /** [args] split at a top-level " at ": what is before it, and the position after it ("" when there is none). */
    private static String[] splitAt(String args) {
        int at = indexOfWord(args.toLowerCase(Locale.ROOT), "at");
        return at < 0 ? new String[]{args.trim(), ""} : new String[]{args.substring(0, at).trim(), args.substring(at + 2).trim()};
    }

    /** Where [word] stands alone in [s] outside parentheses, or -1. */
    private static int indexOfWord(String s, String word) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && s.startsWith(word, i) && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))
                    && (i + word.length() == s.length() || Character.isWhitespace(s.charAt(i + word.length())))) return i;
        }
        return -1;
    }

    /**
     * A {@code <position>} in [r] (absolute coordinates): one to four values, keywords and lengths, as background-position
     * reads them; the centre when [v] is empty.
     */
    static double[] position(String v, Rectangle2D r, Len.Context c) {
        List<String> parts = new ArrayList<>(Values.splitSpaces(v.trim()));
        double fx = 0.5, fy = 0.5, ox = 0, oy = 0;
        boolean xPct = true, yPct = true;
        if (parts.size() == 1) {
            String a = parts.get(0).toLowerCase(Locale.ROOT);
            if (a.equals("top") || a.equals("bottom")) {
                fy = a.equals("top") ? 0 : 1;
            } else if (a.equals("left") || a.equals("right") || a.equals("center")) {
                fx = a.equals("left") ? 0 : a.equals("right") ? 1 : 0.5;
            } else {
                xPct = false;
                ox = length(a, r.getWidth(), c);
            }
        } else if (parts.size() == 2) {
            String a = parts.get(0).toLowerCase(Locale.ROOT), b = parts.get(1).toLowerCase(Locale.ROOT);
            // "top left" means the same as "left top".
            if (a.equals("top") || a.equals("bottom") || (b.equals("left") || b.equals("right"))) {
                String t = a;
                a = b;
                b = t;
            }
            switch (a) {
                case "left" -> fx = 0;
                case "right" -> fx = 1;
                case "center" -> fx = 0.5;
                default -> {
                    xPct = false;
                    ox = length(a, r.getWidth(), c);
                }
            }
            switch (b) {
                case "top" -> fy = 0;
                case "bottom" -> fy = 1;
                case "center" -> fy = 0.5;
                default -> {
                    yPct = false;
                    oy = length(b, r.getHeight(), c);
                }
            }
        } else if (parts.size() == 3 || parts.size() == 4) {
            // Keyword-offset pairs: "right 10px bottom 20px", "left 10% top".
            for (int i = 0; i < parts.size(); i++) {
                String k = parts.get(i).toLowerCase(Locale.ROOT);
                String off = i + 1 < parts.size() && !isKeyword(parts.get(i + 1)) ? parts.get(++i) : null;
                switch (k) {
                    case "left", "right" -> {
                        double d = off == null ? 0 : length(off, r.getWidth(), c);
                        xPct = false;
                        ox = k.equals("left") ? d : r.getWidth() - d;
                    }
                    case "top", "bottom" -> {
                        double d = off == null ? 0 : length(off, r.getHeight(), c);
                        yPct = false;
                        oy = k.equals("top") ? d : r.getHeight() - d;
                    }
                    case "center" -> {}
                    default -> throw new IllegalArgumentException(k);
                }
            }
        } else if (!parts.isEmpty()) {
            throw new IllegalArgumentException(v);
        }
        double x = r.getX() + (xPct ? fx * r.getWidth() : ox), y = r.getY() + (yPct ? fy * r.getHeight() : oy);
        return new double[]{x, y};
    }

    private static boolean isKeyword(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.equals("left") || l.equals("right") || l.equals("top") || l.equals("bottom") || l.equals("center");
    }

    /** A length or percentage of [of]; anything else throws, which makes the whole clip-path one that is not read. */
    private static double length(String v, double of, Len.Context c) {
        Len l = Len.parse(v, c);
        if (l == null) throw new IllegalArgumentException(v);
        return l.of(of);
    }
}

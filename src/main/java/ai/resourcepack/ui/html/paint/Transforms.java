package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Locale;

/** CSS 2D transforms, and transform-origin. 3D functions keep only their 2D part. */
final class Transforms {
    private Transforms() {}

    static AffineTransform parse(String value, double w, double h, Style s) {
        AffineTransform t = new AffineTransform();
        String v = value.trim();
        int i = 0;
        while (i < v.length()) {
            while (i < v.length() && Character.isWhitespace(v.charAt(i))) i++;
            int open = v.indexOf('(', i);
            if (open < 0) break;
            int close = open, depth = 0;
            for (; close < v.length(); close++) {
                if (v.charAt(close) == '(') depth++;
                else if (v.charAt(close) == ')' && --depth == 0) break;
            }
            if (close >= v.length()) break;
            String name = v.substring(i, open).trim().toLowerCase(Locale.ROOT);
            List<String> args = Values.splitSpaces(v.substring(open + 1, close).replace(",", " "));
            i = close + 1;
            switch (name) {
                case "translate", "translate3d" -> t.translate(len(args, 0, w, s), args.size() > 1 ? len(args, 1, h, s) : 0);
                case "translatex" -> t.translate(len(args, 0, w, s), 0);
                case "translatey" -> t.translate(0, len(args, 0, h, s));
                case "scale", "scale3d" -> {
                    double sx = num(args, 0, 1);
                    t.scale(sx, args.size() > 1 ? num(args, 1, sx) : sx);
                }
                case "scalex" -> t.scale(num(args, 0, 1), 1);
                case "scaley" -> t.scale(1, num(args, 0, 1));
                case "rotate", "rotatez" -> t.rotate(Math.toRadians(angle(args, 0)));
                case "skew" -> t.shear(Math.tan(Math.toRadians(angle(args, 0))), args.size() > 1 ? Math.tan(Math.toRadians(angle(args, 1))) : 0);
                case "skewx" -> t.shear(Math.tan(Math.toRadians(angle(args, 0))), 0);
                case "skewy" -> t.shear(0, Math.tan(Math.toRadians(angle(args, 0))));
                case "matrix" -> {
                    if (args.size() == 6) t.concatenate(new AffineTransform(num(args, 0, 1), num(args, 1, 0), num(args, 2, 0), num(args, 3, 1), num(args, 4, 0), num(args, 5, 0)));
                }
                default -> {} // rotateX/Y, perspective: nothing a flat picture can show
            }
        }
        return t;
    }

    private static double len(List<String> a, int i, double basis, Style s) {
        if (i >= a.size()) return 0;
        Len l = Len.parse(a.get(i), s.context);
        return l == null ? 0 : l.of(basis);
    }

    private static double num(List<String> a, int i, double fallback) {
        if (i >= a.size()) return fallback;
        String v = a.get(i).trim();
        if (v.endsWith("%")) {
            Double d = Values.number(v.substring(0, v.length() - 1));
            return d == null ? fallback : d / 100;
        }
        Double d = Values.number(v);
        return d == null ? fallback : d;
    }

    private static double angle(List<String> a, int i) {
        if (i >= a.size()) return 0;
        Double d = Values.angle(a.get(i));
        return d == null ? 0 : d;
    }

    /** transform-origin in pixels from the border box's top-left. */
    static double[] origin(String value, double w, double h, Style s) {
        List<String> p = Values.splitSpaces(value.toLowerCase(Locale.ROOT));
        String x = p.isEmpty() ? "center" : p.get(0), y = p.size() > 1 ? p.get(1) : "center";
        if (x.equals("top") || x.equals("bottom") || y.equals("left") || y.equals("right")) {
            String t = x;
            x = y;
            y = t;
        }
        return new double[]{axis(x, w, s), axis(y, h, s)};
    }

    private static double axis(String v, double size, Style s) {
        return switch (v) {
            case "left", "top" -> 0;
            case "center" -> size / 2;
            case "right", "bottom" -> size;
            default -> {
                Len l = Len.parse(v, s.context);
                yield l == null ? size / 2 : l.of(size);
            }
        };
    }
}

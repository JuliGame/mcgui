package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.css.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A computed length: absolute units and font- and viewport-relative ones are already pixels, and only
 * percentages wait for layout to say what they are a percentage of. {@code calc()}, {@code min()},
 * {@code max()} and {@code clamp()} stay as a small expression for the same reason.
 */
public abstract class Len {
    /** The length in pixels, percentages taken of [basis]. A NaN basis (unknown) makes a percentage 0. */
    public abstract double of(double basis);

    /** Whether the value depends on its basis at all. */
    public abstract boolean relative();

    public static final Len ZERO = px(0);

    /**
     * Lengths are clamped to this many px either way, as browsers' fixed-point layout units saturate
     * (Chrome's at 2^25 px): nothing that far away can be seen, and an infinite length (1e400px,
     * calc(1e308px * 10)) would make NaN of every sum it enters, the page height included.
     */
    static final double LIMIT = 33_554_432;

    /** calc(), min(), max() and clamp() nested deeper than this are invalid, so parsing never runs out of stack. */
    private static final int MAX_NESTING = 32;

    static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(-LIMIT, Math.min(LIMIT, v));
    }

    public static Len px(double v) {
        return new Fixed(v);
    }

    public static Len pct(double v) {
        return new Percent(v);
    }

    private static final class Fixed extends Len {
        final double px;

        Fixed(double px) {
            this.px = clamp(px);
        }

        @Override public double of(double basis) { return px; }
        @Override public boolean relative() { return false; }
        @Override public String toString() { return px + "px"; }
    }

    private static final class Percent extends Len {
        final double pct;

        Percent(double pct) {
            this.pct = pct;
        }

        @Override public double of(double basis) { return Double.isNaN(basis) ? 0 : clamp(basis * pct / 100); }
        @Override public boolean relative() { return true; }
        @Override public String toString() { return pct + "%"; }
    }

    private static final class Expr extends Len {
        final char op; // '+', '*', 'm' (min), 'M' (max)
        final List<Len> terms;
        final double factor;

        Expr(char op, List<Len> terms, double factor) {
            this.op = op;
            this.terms = terms;
            this.factor = factor;
        }

        @Override
        public double of(double basis) {
            return clamp(switch (op) {
                case '+' -> {
                    double s = 0;
                    for (Len t : terms) s += t.of(basis);
                    yield s;
                }
                case '*' -> terms.get(0).of(basis) * factor;
                case 'm' -> {
                    double m = Double.MAX_VALUE;
                    for (Len t : terms) m = Math.min(m, t.of(basis));
                    yield m;
                }
                default -> {
                    double m = -Double.MAX_VALUE;
                    for (Len t : terms) m = Math.max(m, t.of(basis));
                    yield m;
                }
            });
        }

        @Override
        public boolean relative() {
            for (Len t : terms) if (t.relative()) return true;
            return false;
        }
    }

    /** What em, rem and the viewport units mean for the element being computed. */
    public record Context(double fontSize, double rootFontSize, double viewportWidth, double viewportHeight) {}

    /**
     * Reads [s] as a length or percentage, or null if it is neither. A bare number other than 0 is
     * accepted only if [unitless] (line-height multipliers and the like are handled by their caller).
     */
    public static Len parse(String s, Context c) {
        return parse(s, c, 0);
    }

    private static Len parse(String s, Context c, int depth) {
        s = s.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        boolean function = lower.startsWith("calc(") || lower.startsWith("-webkit-calc(") || lower.startsWith("min(") || lower.startsWith("max(")
                || lower.startsWith("clamp(");
        if (function && depth >= MAX_NESTING) return null;
        if (lower.startsWith("calc(") || lower.startsWith("-webkit-calc(")) {
            String inner = s.substring(s.indexOf('(') + 1, s.length() - 1);
            Object v = new Calc(inner, c, depth + 1).sum();
            return v instanceof Len l ? l : v instanceof Double d && d == 0 ? ZERO : null;
        }
        for (String fn : new String[]{"min", "max", "clamp"}) {
            String inner = Values.fn(s, fn);
            if (inner == null) continue;
            List<Len> args = new ArrayList<>();
            for (String part : Values.splitCommas(inner)) {
                Object v = new Calc(part, c, depth + 1).sum();
                if (v instanceof Len l) args.add(l);
                else if (v instanceof Double d && d == 0) args.add(ZERO);
                else return null;
            }
            if (args.isEmpty()) return null;
            return switch (fn) {
                case "min" -> new Expr('m', args, 1);
                case "max" -> new Expr('M', args, 1);
                default -> args.size() == 3 ? new Expr('M', List.of(args.get(0), new Expr('m', List.of(args.get(1), args.get(2)), 1)), 1) : null;
            };
        }
        return unit(lower, c);
    }

    /** A single number with a unit. */
    static Len unit(String s, Context c) {
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-' || s.charAt(i) == '+'
                || (s.charAt(i) == 'e' && i > 0 && i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '-')))) i++;
        if (i == 0) return null;
        double v;
        try {
            v = Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = s.substring(i);
        return switch (unit) {
            case "px" -> px(v);
            case "" -> v == 0 ? ZERO : null;
            case "%" -> pct(v);
            case "em" -> px(v * c.fontSize);
            case "rem" -> px(v * c.rootFontSize);
            case "ex", "ch" -> px(v * c.fontSize * 0.5);
            case "lh" -> px(v * c.fontSize * 1.2);
            case "vw", "svw", "lvw", "dvw" -> px(v * c.viewportWidth / 100);
            case "vh", "svh", "lvh", "dvh" -> px(v * c.viewportHeight / 100);
            case "vmin" -> px(v * Math.min(c.viewportWidth, c.viewportHeight) / 100);
            case "vmax" -> px(v * Math.max(c.viewportWidth, c.viewportHeight) / 100);
            case "pt" -> px(v * 4 / 3);
            case "pc" -> px(v * 16);
            case "in" -> px(v * 96);
            case "cm" -> px(v * 96 / 2.54);
            case "mm" -> px(v * 96 / 25.4);
            case "q" -> px(v * 96 / 101.6);
            default -> null;
        };
    }

    /** calc(): sums of products, where a product has at most one length in it. Values are Len or Double. */
    private static final class Calc {
        private final String s;
        private final Context c;
        /** How many calc()s and parentheses this one is inside. */
        private int depth;
        private int pos;

        Calc(String s, Context c, int depth) {
            this.s = s.trim();
            this.c = c;
            this.depth = depth;
        }

        /** A sum of lengths is one flat list of terms, so a long sum is no deeper than a short one. */
        Object sum() {
            Object left = product();
            List<Len> terms = null;
            while (true) {
                skip();
                if (pos >= s.length()) break;
                char op = s.charAt(pos);
                if (op != '+' && op != '-') break;
                pos++;
                Object right = product();
                if (left == null || right == null) return null;
                if (terms == null && left instanceof Double a && right instanceof Double b) {
                    left = op == '+' ? a + b : a - b;
                    continue;
                }
                Len b = asLen(right);
                if (terms == null) {
                    Len a = asLen(left);
                    if (a == null) return null;
                    terms = new ArrayList<>();
                    terms.add(a);
                }
                if (b == null) return null;
                terms.add(op == '+' ? b : new Expr('*', List.of(b), -1));
            }
            return terms == null ? left : new Expr('+', terms, 1);
        }

        private Object product() {
            Object left = atom();
            while (true) {
                skip();
                if (pos >= s.length()) return left;
                char op = s.charAt(pos);
                if (op != '*' && op != '/') return left;
                pos++;
                Object right = atom();
                if (left == null || right == null) return null;
                if (op == '/') {
                    if (!(right instanceof Double d) || d == 0) return null;
                    left = left instanceof Double a ? a / d : new Expr('*', List.of((Len) left), 1 / d);
                } else if (left instanceof Double a && right instanceof Double b) {
                    left = a * b;
                } else if (left instanceof Double a) {
                    left = new Expr('*', List.of((Len) right), a);
                } else if (right instanceof Double b) {
                    left = new Expr('*', List.of((Len) left), b);
                } else {
                    return null;
                }
            }
        }

        private Object atom() {
            skip();
            if (pos >= s.length()) return null;
            char ch = s.charAt(pos);
            if (ch == '(') {
                if (depth >= MAX_NESTING) return null;
                pos++;
                depth++;
                Object v = sum();
                depth--;
                skip();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            int start = pos;
            if (Character.isLetter(ch)) {
                // A nested function: calc(), min(), max(), clamp(), var() already substituted.
                while (pos < s.length() && s.charAt(pos) != '(') pos++;
                int open = 0;
                for (; pos < s.length(); pos++) {
                    if (s.charAt(pos) == '(') open++;
                    else if (s.charAt(pos) == ')' && --open == 0) {
                        pos++;
                        break;
                    }
                }
                return parse(s.substring(start, pos), c, depth);
            }
            if (ch == '+' || ch == '-') pos++;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == '%')) pos++;
            String tok = s.substring(start, pos).toLowerCase(Locale.ROOT);
            Double num = Values.number(tok);
            if (num != null) return num;
            return unit(tok, c);
        }

        private static Len asLen(Object o) {
            if (o instanceof Len l) return l;
            if (o instanceof Double d && d == 0) return ZERO;
            return null;
        }

        private void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}

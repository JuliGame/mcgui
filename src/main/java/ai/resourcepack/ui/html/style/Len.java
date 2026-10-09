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

    public static Len px(double v) {
        return new Fixed(v);
    }

    public static Len pct(double v) {
        return new Percent(v);
    }

    private static final class Fixed extends Len {
        final double px;

        Fixed(double px) {
            this.px = px;
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

        @Override public double of(double basis) { return Double.isNaN(basis) ? 0 : basis * pct / 100; }
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
            return switch (op) {
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
            };
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
        s = s.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("calc(") || lower.startsWith("-webkit-calc(")) {
            String inner = s.substring(s.indexOf('(') + 1, s.length() - 1);
            Object v = new Calc(inner, c).sum();
            return v instanceof Len l ? l : v instanceof Double d && d == 0 ? ZERO : null;
        }
        for (String fn : new String[]{"min", "max", "clamp"}) {
            String inner = Values.fn(s, fn);
            if (inner == null) continue;
            List<Len> args = new ArrayList<>();
            for (String part : Values.splitCommas(inner)) {
                Object v = new Calc(part, c).sum();
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
        private int pos;

        Calc(String s, Context c) {
            this.s = s.trim();
            this.c = c;
        }

        Object sum() {
            Object left = product();
            while (true) {
                skip();
                if (pos >= s.length()) return left;
                char op = s.charAt(pos);
                if (op != '+' && op != '-') return left;
                pos++;
                Object right = product();
                if (left == null || right == null) return null;
                if (left instanceof Double a && right instanceof Double b) {
                    left = op == '+' ? a + b : a - b;
                    continue;
                }
                Len a = asLen(left), b = asLen(right);
                if (a == null || b == null) return null;
                left = new Expr('+', List.of(a, op == '+' ? b : new Expr('*', List.of(b), -1)), 1);
            }
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
                pos++;
                Object v = sum();
                skip();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            int start = pos;
            if (Character.isLetter(ch)) {
                // A nested function: calc(), min(), max(), clamp(), var() already substituted.
                while (pos < s.length() && s.charAt(pos) != '(') pos++;
                int depth = 0;
                for (; pos < s.length(); pos++) {
                    if (s.charAt(pos) == '(') depth++;
                    else if (s.charAt(pos) == ')' && --depth == 0) {
                        pos++;
                        break;
                    }
                }
                Len l = parse(s.substring(start, pos), c);
                return l;
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

package ai.resourcepack.ui.html.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A media query list, kept as written and evaluated per render against the screen's size: the same
 * page can be on a wide wall and a narrow one at once. A screen is a light-mode colour screen that
 * cannot hover-and-hold or print.
 */
public final class Media {
    private final List<String> queries;
    private final Media and;

    private Media(List<String> queries, Media and) {
        this.queries = queries;
        this.and = and;
    }

    public static Media parse(String text) {
        List<String> qs = new ArrayList<>();
        for (String q : Values.splitCommas(text)) if (!q.isBlank()) qs.add(q.trim().toLowerCase(Locale.ROOT));
        return new Media(qs, null);
    }

    static Media and(Media outer, Media inner) {
        if (outer == null) return inner;
        return new Media(inner.queries, outer);
    }

    /** The last answer, for the size it was for: rules share a Media per block, and are tried on every element. */
    private volatile Answer last;

    private record Answer(double width, double height, boolean matches) {}

    public boolean matches(double width, double height) {
        Answer a = last;
        if (a != null && a.width == width && a.height == height) return a.matches;
        boolean m = evaluate(width, height);
        last = new Answer(width, height, m);
        return m;
    }

    private boolean evaluate(double width, double height) {
        if (and != null && !and.matches(width, height)) return false;
        if (queries.isEmpty()) return true;
        for (String q : queries) if (query(q, width, height)) return true;
        return false;
    }

    private static boolean query(String q, double width, double height) {
        boolean not = false;
        String s = q;
        if (s.startsWith("not ")) {
            not = true;
            s = s.substring(4);
        } else if (s.startsWith("only ")) {
            s = s.substring(5);
        }
        boolean result = true;
        for (String part : s.split("\\band\\b")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (!part.startsWith("(")) {
                if (!part.equals("all") && !part.equals("screen")) result = false;
                continue;
            }
            if (!feature(part.substring(1, part.lastIndexOf(')') > 0 ? part.lastIndexOf(')') : part.length()).trim(), width, height)) result = false;
        }
        return not != result;
    }

    private static boolean feature(String f, double width, double height) {
        // Range syntax: (width >= 600px), (400px <= width <= 800px).
        if (f.contains("<") || f.contains(">")) return range(f, width, height);
        int colon = f.indexOf(':');
        String name = colon < 0 ? f : f.substring(0, colon).trim();
        String value = colon < 0 ? "" : f.substring(colon + 1).trim();
        double px = length(value);
        return switch (name) {
            case "min-width" -> width >= px;
            case "max-width" -> width <= px;
            case "width" -> Math.abs(width - px) < 0.5;
            case "min-height" -> height >= px;
            case "max-height" -> height <= px;
            case "height" -> Math.abs(height - px) < 0.5;
            case "orientation" -> value.equals("landscape") == (width >= height);
            case "prefers-color-scheme" -> value.equals("light");
            case "prefers-reduced-motion" -> value.equals("reduce");
            case "hover", "any-hover" -> value.equals("hover") || value.isEmpty();
            case "pointer", "any-pointer" -> value.equals("fine") || value.isEmpty();
            case "color" -> true;
            case "min-aspect-ratio" -> width / height >= ratio(value);
            case "max-aspect-ratio" -> width / height <= ratio(value);
            default -> false;
        };
    }

    private static boolean range(String f, double width, double height) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '<' || c == '>' || c == '=') {
                if (!cur.toString().isBlank()) parts.add(cur.toString().trim());
                cur.setLength(0);
                String op = String.valueOf(c);
                if (c != '=' && i + 1 < f.length() && f.charAt(i + 1) == '=') {
                    op += "=";
                    i++;
                }
                parts.add(op);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) parts.add(cur.toString().trim());
        if (parts.size() < 3 || parts.size() % 2 == 0) return false;
        double[] values = new double[parts.size()];
        for (int i = 0; i < parts.size(); i += 2) {
            String p = parts.get(i);
            values[i] = p.equals("width") ? width : p.equals("height") ? height : length(p);
        }
        for (int i = 1; i + 1 < parts.size(); i += 2) {
            double a = values[i - 1], b = values[i + 1];
            boolean ok = switch (parts.get(i)) {
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                case ">=" -> a >= b;
                case "=" -> Math.abs(a - b) < 0.5;
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    private static double ratio(String v) {
        String[] p = v.split("/");
        try {
            return p.length == 2 ? Double.parseDouble(p[0].trim()) / Double.parseDouble(p[1].trim()) : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static double length(String v) {
        try {
            if (v.endsWith("px")) return Double.parseDouble(v.substring(0, v.length() - 2));
            if (v.endsWith("rem") || v.endsWith("em")) return Double.parseDouble(v.replaceAll("r?em$", "")) * 16;
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

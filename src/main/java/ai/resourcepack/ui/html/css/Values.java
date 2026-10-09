package ai.resourcepack.ui.html.css;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Splitting CSS values into parts, and reading colours and numbers. */
public final class Values {
    private Values() {}

    /** Splits on top-level commas (not inside parentheses or strings). */
    public static List<String> splitCommas(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start <= s.length()) {
            int end = CssParser.scan(s, start, ",");
            out.add(s.substring(start, Math.min(end, s.length())).trim());
            if (end >= s.length()) break;
            start = end + 1;
        }
        return out;
    }

    /** Splits on top-level whitespace and around a top-level '/', which comes back as its own part. */
    public static List<String> splitSpaces(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int paren = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '(') {
                paren++;
                cur.append(c);
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
                cur.append(c);
            } else if (paren == 0 && Character.isWhitespace(c)) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else if (paren == 0 && c == '/') {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                out.add("/");
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    public static Double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The inside of {@code name(...)} if [s] is exactly that function, else null. */
    public static String fn(String s, String name) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(name + "(") || !s.endsWith(")")) return null;
        return s.substring(name.length() + 1, s.length() - 1);
    }

    /** An angle in degrees, or null. */
    public static Double angle(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("deg")) return Double.parseDouble(s.substring(0, s.length() - 3));
            if (s.endsWith("grad")) return Double.parseDouble(s.substring(0, s.length() - 4)) * 0.9;
            if (s.endsWith("rad")) return Math.toDegrees(Double.parseDouble(s.substring(0, s.length() - 3)));
            if (s.endsWith("turn")) return Double.parseDouble(s.substring(0, s.length() - 4)) * 360;
            if (s.equals("0")) return 0.0;
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- colour

    /** ARGB, or null if [s] is not a colour. {@code currentcolor} returns [current]. */
    public static Integer color(String s, int current) {
        s = s.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("currentcolor")) return current;
        if (lower.equals("transparent")) return 0;
        if (lower.startsWith("#")) return hex(lower.substring(1));
        Integer named = NAMED.get(lower);
        if (named != null) return named;
        int paren = lower.indexOf('(');
        if (paren < 0 || !lower.endsWith(")")) return null;
        String name = lower.substring(0, paren);
        List<String> parts = new ArrayList<>();
        for (String p : lower.substring(paren + 1, lower.length() - 1).replace(",", " ").split("\\s+")) {
            if (p.isEmpty()) continue;
            if (p.contains("/") && !p.equals("/")) {
                String[] split = p.split("/", -1);
                if (!split[0].isEmpty()) parts.add(split[0]);
                parts.add("/");
                if (split.length > 1 && !split[1].isEmpty()) parts.add(split[1]);
            } else {
                parts.add(p);
            }
        }
        double alpha = 1;
        int slash = parts.indexOf("/");
        if (slash >= 0) {
            if (slash + 1 < parts.size()) alpha = fraction(parts.get(slash + 1));
            parts = parts.subList(0, slash);
        } else if (parts.size() == 4) {
            alpha = fraction(parts.get(3));
            parts = parts.subList(0, 3);
        }
        if (parts.size() < 3) return null;
        try {
            int[] rgb = switch (name) {
                case "rgb", "rgba" -> new int[]{channel(parts.get(0)), channel(parts.get(1)), channel(parts.get(2))};
                case "hsl", "hsla" -> hsl(hue(parts.get(0)), percent(parts.get(1)), percent(parts.get(2)));
                case "hwb" -> hwb(hue(parts.get(0)), percent(parts.get(1)), percent(parts.get(2)));
                case "oklch" -> oklch(lightness(parts.get(0)), chroma(parts.get(1), 0.4), hue(parts.get(2)));
                case "oklab" -> oklab(lightness(parts.get(0)), chroma(parts.get(1), 0.4), chroma(parts.get(2), 0.4));
                case "lch" -> oklch(lightness100(parts.get(0)), chroma(parts.get(1), 150) / 150 * 0.4, hue(parts.get(2)));
                default -> null;
            };
            if (rgb == null) return null;
            int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
            return (a << 24) | (clamp(rgb[0]) << 16) | (clamp(rgb[1]) << 8) | clamp(rgb[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer hex(String h) {
        try {
            return switch (h.length()) {
                case 3 -> 0xFF000000 | expand(h.charAt(0)) << 16 | expand(h.charAt(1)) << 8 | expand(h.charAt(2));
                case 4 -> expand(h.charAt(3)) << 24 | expand(h.charAt(0)) << 16 | expand(h.charAt(1)) << 8 | expand(h.charAt(2));
                case 6 -> 0xFF000000 | Integer.parseInt(h, 16);
                case 8 -> (int) (Long.parseLong(h.substring(6), 16) << 24 | Long.parseLong(h.substring(0, 6), 16));
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int expand(char c) {
        int v = Character.digit(c, 16);
        if (v < 0) throw new NumberFormatException();
        return v * 17;
    }

    private static int channel(String p) {
        if (p.equals("none")) return 0;
        if (p.endsWith("%")) return (int) Math.round(Double.parseDouble(p.substring(0, p.length() - 1)) * 2.55);
        return (int) Math.round(Double.parseDouble(p));
    }

    private static double fraction(String p) {
        if (p.equals("none")) return 0;
        if (p.endsWith("%")) return Double.parseDouble(p.substring(0, p.length() - 1)) / 100;
        return Double.parseDouble(p);
    }

    /** HSL's saturation and lightness: a percentage, or (modern syntax) a bare number out of 100. */
    private static double percent(String p) {
        if (p.equals("none")) return 0;
        return Double.parseDouble(p.endsWith("%") ? p.substring(0, p.length() - 1) : p) / 100;
    }

    private static double lightness(String p) {
        if (p.endsWith("%")) return Double.parseDouble(p.substring(0, p.length() - 1)) / 100;
        return Double.parseDouble(p);
    }

    private static double lightness100(String p) {
        if (p.endsWith("%")) return Double.parseDouble(p.substring(0, p.length() - 1)) / 100;
        return Double.parseDouble(p) / 100;
    }

    private static double chroma(String p, double full) {
        if (p.equals("none")) return 0;
        if (p.endsWith("%")) return Double.parseDouble(p.substring(0, p.length() - 1)) / 100 * full;
        return Double.parseDouble(p);
    }

    private static double hue(String p) {
        if (p.equals("none")) return 0;
        Double a = angle(p);
        return a != null ? a : Double.parseDouble(p);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int[] hsl(double h, double s, double l) {
        h = ((h % 360) + 360) % 360 / 360;
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new int[]{(int) Math.round(hue2rgb(p, q, h + 1 / 3.0) * 255), (int) Math.round(hue2rgb(p, q, h) * 255),
                (int) Math.round(hue2rgb(p, q, h - 1 / 3.0) * 255)};
    }

    private static double hue2rgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6.0) return p + (q - p) * 6 * t;
        if (t < 1 / 2.0) return q;
        if (t < 2 / 3.0) return p + (q - p) * (2 / 3.0 - t) * 6;
        return p;
    }

    private static int[] hwb(double h, double w, double b) {
        if (w + b >= 1) {
            int g = (int) Math.round(w / (w + b) * 255);
            return new int[]{g, g, g};
        }
        int[] rgb = hsl(h, 1, 0.5);
        for (int i = 0; i < 3; i++) rgb[i] = (int) Math.round((rgb[i] / 255.0 * (1 - w - b) + w) * 255);
        return rgb;
    }

    private static int[] oklch(double l, double c, double h) {
        double rad = Math.toRadians(h);
        return oklab(l, c * Math.cos(rad), c * Math.sin(rad));
    }

    /** OKLab to sRGB (Björn Ottosson's matrices). */
    private static int[] oklab(double L, double a, double b) {
        double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = L - 0.0894841775 * a - 1.2914855480 * b;
        double l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
        double r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
        return new int[]{gamma(r), gamma(g), gamma(bl)};
    }

    private static int gamma(double x) {
        x = Math.max(0, Math.min(1, x));
        double v = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
        return (int) Math.round(v * 255);
    }

    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String[] table = ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 "
                + "black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 "
                + "chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff "
                + "darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b "
                + "darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a "
                + "darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 "
                + "deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 "
                + "forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 grey 808080 "
                + "green 008000 greenyellow adff2f honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c "
                + "lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff "
                + "lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a "
                + "lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 "
                + "lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd "
                + "mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a "
                + "mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 "
                + "navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 "
                + "palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 "
                + "peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f "
                + "royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d "
                + "silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f "
                + "steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 "
                + "white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32 canvas ffffff canvastext 000000 buttonface efefef "
                + "buttontext 000000 field ffffff fieldtext 000000 highlight 3390ff graytext 808080 linktext 0000ee").split(" ");
        for (int i = 0; i + 1 < table.length; i += 2) NAMED.put(table[i], 0xFF000000 | Integer.parseInt(table[i + 1], 16));
    }
}

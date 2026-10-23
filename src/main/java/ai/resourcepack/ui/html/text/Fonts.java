package ai.resourcepack.ui.html.text;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fonts a page can use: the four families bundled in the plugin (Inter for sans-serif and every
 * system UI font name, Source Serif 4 for serif, JetBrains Mono for monospace, Mac's Minecraft for
 * "Minecraft" (proportional, as the game's own font is; Monocraft is still there by name), plus whatever the page loads with {@code @font-face}. Picks faces the way CSS does:
 * the first family in the list that exists, then the nearest weight, then a slanted copy if there is
 * no italic. Characters a face lacks fall back to the other bundled ones, then to Java's own.
 */
public final class Fonts {
    /** Antialiased with fractional metrics: what text is both measured and drawn with. */
    public static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private record Face(Font font, int weight, boolean italic) {}

    private final Map<String, List<Face>> families = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, Font> sized = new ConcurrentHashMap<>();
    private final Map<Font, Metrics> metrics = new ConcurrentHashMap<>();
    private final List<Font> fallbacks = new ArrayList<>();

    /** One shared copy of the bundled fonts; pages add their own on top with {@link #withPageFonts}. */
    private static volatile Fonts bundled;

    public static Fonts bundled() {
        Fonts b = bundled;
        if (b == null) {
            synchronized (Fonts.class) {
                if (bundled == null) bundled = loadBundled();
                b = bundled;
            }
        }
        return b;
    }

    private static Fonts loadBundled() {
        Fonts f = new Fonts();
        f.load("inter", "Inter-Light.ttf", 300, false);
        f.load("inter", "Inter-Regular.ttf", 400, false);
        f.load("inter", "Inter-Italic.ttf", 400, true);
        f.load("inter", "Inter-Medium.ttf", 500, false);
        f.load("inter", "Inter-SemiBold.ttf", 600, false);
        f.load("inter", "Inter-Bold.ttf", 700, false);
        f.load("inter", "Inter-BoldItalic.ttf", 700, true);
        f.load("inter", "Inter-ExtraBold.ttf", 800, false);
        f.load("inter", "Inter-Black.ttf", 900, false);
        f.load("source serif 4", "SourceSerif4-Regular.ttf", 400, false);
        f.load("source serif 4", "SourceSerif4-It.ttf", 400, true);
        f.load("source serif 4", "SourceSerif4-Bold.ttf", 700, false);
        f.load("jetbrains mono", "JetBrainsMono-Regular.ttf", 400, false);
        f.load("jetbrains mono", "JetBrainsMono-Bold.ttf", 700, false);
        f.load("monocraft", "Monocraft.ttf", 400, false);
        f.load("monocraft", "Monocraft-Bold.ttf", 700, false);
        f.load("macs minecraft", "MacsMinecraft-Regular.ttf", 400, false);
        f.load("macs minecraft", "MacsMinecraft-Italic.ttf", 400, true);
        f.load("macs minecraft", "MacsMinecraft-Bold.ttf", 700, false);
        f.load("macs minecraft", "MacsMinecraft-BoldItalic.ttf", 700, true);
        for (String n : new String[]{"sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont", "segoe ui", "segoe ui variable",
                "roboto", "helvetica", "helvetica neue", "arial", "ui-sans-serif", "noto sans", "ubuntu", "cantarell", "oxygen",
                "fira sans", "droid sans", "liberation sans", "verdana", "tahoma", "trebuchet ms", "sf pro display", "sf pro text",
                "-webkit-system-font", "cursive", "fantasy", "emoji", "math", "fangsong", "apple color emoji", "segoe ui emoji",
                "segoe ui symbol", "noto color emoji"}) f.aliases.put(n, "inter");
        for (String n : new String[]{"serif", "georgia", "times", "times new roman", "ui-serif", "cambria", "garamond", "palatino",
                "book antiqua", "liberation serif", "source serif pro", "merriweather"}) f.aliases.put(n, "source serif 4");
        for (String n : new String[]{"monospace", "ui-monospace", "menlo", "monaco", "consolas", "courier", "courier new", "sfmono-regular",
                "sf mono", "fira code", "fira mono", "source code pro", "liberation mono", "dejavu sans mono", "cascadia code",
                "cascadia mono", "roboto mono", "ibm plex mono"}) f.aliases.put(n, "jetbrains mono");
        for (String n : new String[]{"minecraft", "minecraftia", "mojangles", "minecraft-font", "minecraftfont", "minecraft ten", "mac's minecraft", "macsminecraft"}) f.aliases.put(n, "macs minecraft");
        f.fallbacks.add(f.families.get("inter").get(1).font);
        f.fallbacks.add(f.families.get("jetbrains mono").get(0).font);
        f.fallbacks.add(f.families.get("source serif 4").get(0).font);
        f.fallbacks.add(f.families.get("macs minecraft").get(0).font);
        f.fallbacks.add(f.families.get("monocraft").get(0).font);
        f.fallbacks.add(new Font(Font.DIALOG, Font.PLAIN, 1));
        return f;
    }

    private void load(String family, String file, int weight, boolean italic) {
        try (InputStream in = Fonts.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) return;
            add(family, Font.createFont(Font.TRUETYPE_FONT, in), weight, italic);
        } catch (IOException | FontFormatException e) {
            // A missing bundled face just narrows the choice of weights.
        }
    }

    private void add(String family, Font font, int weight, boolean italic) {
        families.computeIfAbsent(family.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(new Face(font, weight, italic));
    }

    /** A copy of these fonts with a page's {@code @font-face} fonts added: [faces] of family, weight, italic, data. */
    public Fonts withPageFonts(List<PageFont> faces) {
        if (faces.isEmpty()) return this;
        Fonts f = new Fonts();
        families.forEach((k, v) -> f.families.put(k, new ArrayList<>(v)));
        f.aliases.putAll(aliases);
        f.fallbacks.addAll(fallbacks);
        for (PageFont pf : faces) {
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(pf.data()));
                // A page's own font replaces whatever the name meant before (a page can call its font "Arial").
                String key = pf.family().toLowerCase(Locale.ROOT);
                f.aliases.remove(key);
                f.add(key, font, pf.weight(), pf.italic());
            } catch (IOException | FontFormatException e) {
                // woff and woff2 cannot be read by Java; the next family in the list is used instead.
            }
        }
        return f;
    }

    public record PageFont(String family, int weight, boolean italic, byte[] data) {}

    /** The font for a CSS font-family list, weight and style, at [size] pixels. */
    public Font font(List<String> familyList, int weight, boolean italic, double size) {
        String key = String.join(",", familyList) + "|" + weight + "|" + italic + "|" + (float) size;
        Font cached = sized.get(key);
        if (cached != null) return cached;
        Face face = null;
        for (String fam : familyList) {
            String name = fam.toLowerCase(Locale.ROOT).trim();
            List<Face> faces = families.get(name);
            if (faces == null) {
                String alias = aliases.get(name);
                if (alias != null) faces = families.get(alias);
            }
            if (faces != null && !faces.isEmpty()) {
                face = pick(faces, weight, italic);
                break;
            }
        }
        if (face == null) face = pick(families.get("source serif 4"), weight, italic);
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attrs.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        Font f = face.font.deriveFont((float) size).deriveFont(attrs);
        // No italic face: slant the upright one, as browsers do.
        if (italic && !face.italic) f = f.deriveFont(AffineTransform.getShearInstance(-0.2, 0));
        // No bold face: thicken the nearest one.
        if (weight >= 600 && face.weight < 600) f = f.deriveFont(Map.of(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD));
        sized.put(key, f);
        return f;
    }

    /** CSS font matching: the exact weight, else for heavy requests the next heavier, for light ones the next lighter. */
    private static Face pick(List<Face> faces, int weight, boolean italic) {
        List<Face> style = new ArrayList<>();
        for (Face f : faces) if (f.italic == italic) style.add(f);
        if (style.isEmpty()) style = faces;
        Face best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Face f : style) {
            int d = f.weight - weight;
            int score;
            if (d == 0) score = 0;
            else if (weight > 500) score = d > 0 ? d : 1000 - d;
            else if (weight < 400) score = d < 0 ? -d : 1000 + d;
            else score = (f.weight >= weight && f.weight <= 500) ? d : (d < 0 ? 500 - d : 1500 + d);
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /**
     * The bundled fallback that can draw [cp], or null: one Font per fallback face and [like], so a run
     * of characters falling back together stays one segment (one draw, shaped as a whole).
     */
    public Font fallbackFor(int cp, Font like) {
        for (Font f : fallbacks) {
            if (!f.canDisplay(cp)) continue;
            return derived.computeIfAbsent(new Derived(f, like), k -> {
                // [like]'s size, slant, letter spacing, kerning, ligatures and synthetic bold, on the fallback face.
                Map<TextAttribute, Object> attrs = new HashMap<>();
                like.getAttributes().forEach((a, v) -> {
                    if (v != null && a != TextAttribute.FAMILY) attrs.put((TextAttribute) a, v);
                });
                return f.deriveFont(attrs);
            });
        }
        return null;
    }

    private record Derived(Font fallback, Font like) {}

    private final Map<Derived, Font> derived = new ConcurrentHashMap<>();

    /** Ascent, descent and line gap in pixels, and the height CSS calls "normal". */
    public record Metrics(double ascent, double descent, double normalLineHeight, double xHeight) {}

    public Metrics metrics(Font font) {
        return metrics.computeIfAbsent(font, f -> {
            LineMetrics lm = f.getLineMetrics("Hxg", FRC);
            double ascent = lm.getAscent(), descent = lm.getDescent();
            double x = f.createGlyphVector(FRC, "x").getVisualBounds().getHeight();
            return new Metrics(ascent, descent, ascent + descent + lm.getLeading(), x > 0 ? x : ascent * 0.5);
        });
    }
}

package ai.resourcepack.ui.html.text;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures and draws runs of text, splitting a run where its font lacks a character so the rest of
 * the run keeps its own font. Letter spacing is the font's tracking, so measuring and drawing agree.
 */
public final class TextShaper {
    private final Fonts fonts;
    private final Map<Font, Map<String, Double>> widths = new ConcurrentHashMap<>();
    private final Map<Font, Map<Float, Font>> tracked = new ConcurrentHashMap<>();

    public TextShaper(Fonts fonts) {
        this.fonts = fonts;
    }

    public Fonts fonts() {
        return fonts;
    }

    /** [font] with [letterSpacing] pixels after every character. */
    public Font withSpacing(Font font, double letterSpacing) {
        if (letterSpacing == 0) return font;
        float em = (float) (letterSpacing / font.getSize2D());
        return tracked.computeIfAbsent(font, f -> new ConcurrentHashMap<>())
                .computeIfAbsent(em, k -> font.deriveFont(Map.of(TextAttribute.TRACKING, k)));
    }

    public record Segment(String text, Font font) {}

    public List<Segment> segments(String s, Font font) {
        int bad = -1;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp) && !font.canDisplay(cp)) {
                bad = i;
                break;
            }
            i += Character.charCount(cp);
        }
        if (bad < 0) return List.of(new Segment(s, font));
        List<Segment> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Font curFont = font;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Font f = Character.isWhitespace(cp) || font.canDisplay(cp) ? font : fonts.fallbackFor(cp, font);
            if (f == null) f = font;
            if (f != curFont && !cur.isEmpty()) {
                out.add(new Segment(cur.toString(), curFont));
                cur.setLength(0);
            }
            curFont = f;
            cur.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (!cur.isEmpty()) out.add(new Segment(cur.toString(), curFont));
        return out;
    }

    public double width(String s, Font font) {
        if (s.isEmpty()) return 0;
        Map<String, Double> cache = widths.computeIfAbsent(font, f -> new ConcurrentHashMap<>());
        Double w = cache.get(s);
        if (w != null) return w;
        double total = 0;
        for (Segment seg : segments(s, font)) total += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        if (cache.size() < 20000) cache.put(s, total);
        return total;
    }

    public void draw(Graphics2D g, String s, Font font, double x, double y) {
        for (Segment seg : segments(s, font)) {
            g.setFont(seg.font);
            g.drawString(seg.text, (float) x, (float) y);
            x += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        }
    }

    /** The outline of [s] at (x, y): what gradient text is clipped to. */
    public java.awt.Shape outline(String s, Font font, double x, double y) {
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
        for (Segment seg : segments(s, font)) {
            java.awt.font.TextLayout layout = new java.awt.font.TextLayout(seg.text, seg.font, Fonts.FRC);
            path.append(layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(x, y)), false);
            x += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        }
        return path;
    }
}

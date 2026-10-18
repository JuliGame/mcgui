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

    /**
     * Runs longer than this are measured a piece at a time and the widths added. Java's shaper takes
     * superlinear time on a very long run (a megabyte of '<' in one word took minutes), and a piece that
     * repeats is measured once. Only kerning across the joins is lost: nothing, in a run this long.
     */
    private static final int PIECE = 1024;

    public double width(String s, Font font) {
        if (s.isEmpty()) return 0;
        if (s.length() > PIECE) {
            double total = 0;
            for (String piece : pieces(s)) total += width(piece, font);
            return total;
        }
        Map<String, Double> cache = widths.computeIfAbsent(font, f -> new ConcurrentHashMap<>());
        Double w = cache.get(s);
        if (w != null) return w;
        double total = 0;
        for (Segment seg : segments(s, font)) total += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        if (cache.size() < 20000) cache.put(s, total);
        return total;
    }

    /** [s] in pieces of at most {@link #PIECE} characters, never between the halves of a surrogate pair. */
    private static List<String> pieces(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); ) {
            int end = Math.min(s.length(), i + PIECE);
            if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) end--;
            out.add(s.substring(i, end));
            i = end;
        }
        return out;
    }

    /**
     * Draws [s] with its baseline starting at (x, y). A long run is drawn a piece at a time, each where
     * {@link #width} put it, and pieces left or right of what [g] can reach are skipped: a megabyte-long
     * word is shaped only where it shows.
     */
    public void draw(Graphics2D g, String s, Font font, double x, double y) {
        if (s.length() <= PIECE) {
            drawRun(g, s, font, x, y);
            return;
        }
        java.awt.geom.Rectangle2D view = reach(g);
        double margin = font.getSize2D() * 2;
        for (String piece : pieces(s)) {
            double w = width(piece, font);
            if (view == null || (x + w + margin >= view.getMinX() && x - margin <= view.getMaxX())) drawRun(g, piece, font, x, y);
            x += w;
        }
    }

    /** The user-space bounds of what [g] can draw on (its device, within its clip), or null if unknown. */
    private static java.awt.geom.Rectangle2D reach(Graphics2D g) {
        try {
            java.awt.geom.Rectangle2D r = g.getTransform().createInverse().createTransformedShape(g.getDeviceConfiguration().getBounds()).getBounds2D();
            java.awt.Rectangle clip = g.getClipBounds();
            if (clip != null) java.awt.geom.Rectangle2D.intersect(r, clip, r);
            return r;
        } catch (java.awt.geom.NoninvertibleTransformException | RuntimeException e) {
            return null;
        }
    }

    private void drawRun(Graphics2D g, String s, Font font, double x, double y) {
        for (Segment seg : segments(s, font)) {
            g.setFont(seg.font);
            g.drawString(seg.text, (float) x, (float) y);
            x += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        }
    }

    /** The outline of [s] at (x, y): what gradient text is clipped to. */
    public java.awt.Shape outline(String s, Font font, double x, double y) {
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
        if (s.length() > PIECE) {
            for (String piece : pieces(s)) {
                path.append(outline(piece, font, x, y), false);
                x += width(piece, font);
            }
            return path;
        }
        for (Segment seg : segments(s, font)) {
            java.awt.font.TextLayout layout = new java.awt.font.TextLayout(seg.text, seg.font, Fonts.FRC);
            path.append(layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(x, y)), false);
            x += seg.font.getStringBounds(seg.text, Fonts.FRC).getWidth();
        }
        return path;
    }
}

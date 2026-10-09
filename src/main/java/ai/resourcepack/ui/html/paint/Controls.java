package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.text.Fonts;
import ai.resourcepack.ui.html.text.TextShaper;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** The insides of form controls: their text, check marks, sliders and bars. Their boxes are styled like any box. */
final class Controls {
    private Controls() {}

    static void draw(Graphics2D g, Box b, double x, double y, double w, double h, TextShaper shaper) {
        Style s = b.style;
        switch (b.control) {
            case "checkbox", "radio" -> {
                if (b.controlValue <= 0) return;
                g.setColor(new Color(0xFF0075FF, true));
                if (b.control.equals("radio")) {
                    double d = Math.min(w, h) * 0.5;
                    g.fill(new Ellipse2D.Double(x + (w - d) / 2, y + (h - d) / 2, d, d));
                } else {
                    g.fill(new RoundRectangle2D.Double(x - 1, y - 1, w + 2, h + 2, 3, 3));
                    g.setColor(Color.WHITE);
                    Path2D.Double tick = new Path2D.Double();
                    tick.moveTo(x + w * 0.2, y + h * 0.52);
                    tick.lineTo(x + w * 0.42, y + h * 0.74);
                    tick.lineTo(x + w * 0.8, y + h * 0.28);
                    g.setStroke(new BasicStroke((float) Math.max(1.5, w / 7), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(tick);
                }
            }
            case "range" -> {
                double cy = y + h / 2, t = Math.max(0, Math.min(1, b.controlValue));
                g.setColor(new Color(0xFFDDDDDD, true));
                g.fill(new RoundRectangle2D.Double(x, cy - 2, w, 4, 4, 4));
                g.setColor(new Color(0xFF0075FF, true));
                g.fill(new RoundRectangle2D.Double(x, cy - 2, w * t, 4, 4, 4));
                double r = Math.min(8, h / 2);
                g.fill(new Ellipse2D.Double(x + w * t - r, cy - r, r * 2, r * 2));
            }
            case "progress" -> {
                double t = Math.max(0, Math.min(1, Double.isNaN(b.controlValue) ? 0 : b.controlValue));
                Shape track = new RoundRectangle2D.Double(x, y, w, h, h, h);
                g.setColor(new Color(0xFFE5E5E5, true));
                g.fill(track);
                g.setColor(new Color(0xFF0075FF, true));
                g.fill(new RoundRectangle2D.Double(x, y, w * t, h, h, h));
            }
            case "color" -> {
                Integer c = ai.resourcepack.ui.html.css.Values.color(b.controlText, 0xFF000000);
                g.setColor(new Color(c == null ? 0xFF000000 : c, true));
                g.fill(new java.awt.geom.Rectangle2D.Double(x + 2, y + 2, Math.max(0, w - 4), Math.max(0, h - 4)));
            }
            default -> {
                String text = b.controlText == null ? "" : b.controlText;
                Font font = shaper.withSpacing(shaper.fonts().font(s.fontFamilies, s.fontWeight, s.italic, s.fontSize), s.letterSpacing);
                Fonts.Metrics m = shaper.fonts().metrics(font);
                double tw = shaper.width(text, font);
                int color = s.color;
                if (b.controlPlaceholder) color = 0xFF757575;
                g.setColor(new Color(color, true));
                java.awt.Shape old = g.getClip();
                g.clip(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
                double baseline = b.control.equals("textarea") ? y + m.ascent() : y + (h + m.ascent() - m.descent()) / 2;
                double tx = switch (b.control) {
                    case "button" -> x + (w - tw) / 2;
                    default -> s.textAlign.equals("center") ? x + (w - tw) / 2 : s.textAlign.equals("right") ? x + w - tw : x;
                };
                shaper.draw(g, text, font, tx, baseline);
                g.setClip(old);
                if (b.control.equals("select")) {
                    // The drop-down arrow.
                    double ax = x + w - 10, ay = y + h / 2;
                    Path2D.Double arrow = new Path2D.Double();
                    arrow.moveTo(ax - 4, ay - 2);
                    arrow.lineTo(ax, ay + 2);
                    arrow.lineTo(ax + 4, ay - 2);
                    g.setColor(new Color(s.color, true));
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(arrow);
                }
            }
        }
    }
}

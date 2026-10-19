package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.res.Picture;
import ai.resourcepack.ui.html.res.Resources;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Background layers: linear, radial and conic gradients (repeating ones too) and images, each sized,
 * positioned and repeated per background-size, -position and -repeat.
 */
final class Backgrounds {
    private Backgrounds() {}

    /**
     * Paints every background layer of [s] into [area] (the positioning box), clipped to [clip].
     * [surface] is the device bounds of what [g] draws on: tiles are laid only where they can be seen.
     */
    static void paint(Graphics2D g, Style s, Resources res, Rectangle2D area, Shape clip, java.awt.Rectangle surface) {
        List<String> images = s.backgroundImages;
        Shape old = g.getClip();
        g.clip(clip);
        // The first layer is on top: paint from the last.
        for (int i = images.size() - 1; i >= 0; i--) {
            String layer = images.get(i).trim();
            if (layer.equalsIgnoreCase("none")) continue;
            String size = pick(s.backgroundSizes, i, "auto");
            String pos = pick(s.backgroundPositions, i, "0% 0%");
            String repeat = pick(s.backgroundRepeats, i, "repeat");
            layer(g, layer, s, res, area, size, pos, repeat, surface);
        }
        g.setClip(old);
    }

    static String pick(List<String> list, int i, String fallback) {
        if (list.isEmpty()) return fallback;
        return list.get(i % list.size()).trim();
    }

    private static void layer(Graphics2D g, String layer, Style s, Resources res, Rectangle2D area, String size, String pos, String repeat,
                              java.awt.Rectangle surface) {
        String lower = layer.toLowerCase(Locale.ROOT);
        Picture pic = null;
        double natW, natH;
        boolean gradient = lower.contains("gradient(");
        if (gradient) {
            natW = area.getWidth();
            natH = area.getHeight();
        } else {
            String uri = url(layer);
            if (uri == null) return;
            pic = res.image(uri);
            if (pic == null) return;
            natW = pic.width();
            natH = pic.height();
            if (natW <= 0 || natH <= 0) {
                natW = area.getWidth();
                natH = area.getHeight();
            }
        }
        double[] tile = size(size, natW, natH, area, s);
        if (tile[0] <= 0 || tile[1] <= 0) return;
        double[] at = position(pos, tile, area, s);
        String rl = repeat.toLowerCase(Locale.ROOT);
        boolean rx = rl.equals("repeat") || rl.startsWith("repeat-x") || rl.startsWith("repeat repeat") || rl.startsWith("round") || rl.startsWith("space");
        boolean ry = rl.equals("repeat") || rl.startsWith("repeat-y") || rl.endsWith("repeat") && !rl.equals("repeat-x") || rl.startsWith("round") || rl.startsWith("space");
        if (rl.equals("repeat-x")) ry = false;
        if (rl.equals("repeat-y")) rx = false;
        if (rl.equals("no-repeat")) rx = ry = false;
        // A gradient the size of its box needs no tiling, whatever the repeat says.
        if (gradient && Math.abs(tile[0] - area.getWidth()) < 0.5 && Math.abs(tile[1] - area.getHeight()) < 0.5) rx = ry = false;
        // Tiles only where they can be seen: the clip [paint] set, within the surface. A full render has
        // no clip of its own, so without the surface a page-long background would be tiled from top to bottom.
        Rectangle2D visible = Painter.visible(g, surface);
        if (visible == null) return;
        double x0 = at[0], y0 = at[1];
        if (rx) x0 -= Math.ceil((x0 - visible.getMinX()) / tile[0]) * tile[0];
        if (ry) y0 -= Math.ceil((y0 - visible.getMinY()) / tile[1]) * tile[1];
        // A gradient's Paint is built once, for the first tile, and moved to each of the others.
        Rectangle2D first = new Rectangle2D.Double(x0, y0, tile[0], tile[1]);
        Paint paint = gradient ? gradientPaint(layer, s, first) : null;
        if (gradient && paint == null) return;
        // Past its last coloured stop a radial gradient is transparent, which paints nothing over what is there.
        // Filling only the part that can still show colour skips most of a full-page glow.
        Rectangle2D fill = gradient && g.getComposite() == AlphaComposite.SrcOver ? first.createIntersection(coloured(paint, first)) : first;
        if (fill.isEmpty()) return;
        Paint oldPaint = g.getPaint();
        AffineTransform base = g.getTransform();
        if (gradient) g.setPaint(paint);
        int tiles = 0;
        tiling:
        for (double y = y0; ry ? y < visible.getMaxY() : y == y0; y += tile[1]) {
            for (double x = x0; rx ? x < visible.getMaxX() : x == x0; x += tile[0]) {
                // Only against absurdly small tiles: a 2 px pattern over a whole 2x view is about 150 000.
                if (++tiles > MAX_TILES) break tiling;
                if (gradient) {
                    g.translate(x - x0, y - y0);
                    g.fill(fill);
                    g.setTransform(base);
                } else {
                    pic.draw(g, x, y, tile[0], tile[1]);
                }
                if (!rx) break;
            }
            if (!ry) break;
        }
        g.setPaint(oldPaint);
    }

    private static final int MAX_TILES = 250_000;

    /** Where [paint] can show any colour within [r]: all of [r], except for a radial gradient that ends transparent. */
    static Rectangle2D coloured(Paint paint, Rectangle2D r) {
        if (!(paint instanceof RadialGradientPaint g) || g.getCycleMethod() != MultipleGradientPaint.CycleMethod.NO_CYCLE) return r;
        Color[] colors = g.getColors();
        float[] fractions = g.getFractions();
        int last = colors.length - 1;
        while (last >= 0 && colors[last].getAlpha() == 0) last--;
        if (last == colors.length - 1) return r; // the last colour runs on to the edges
        if (last < 0) return new Rectangle2D.Double(); // transparent throughout
        // The colour fades to nothing between stop [last] and the next one; beyond that every pixel is transparent.
        double reach = g.getRadius() * fractions[last + 1] + 1;
        Point2D c = g.getCenterPoint();
        Rectangle2D circle = new Rectangle2D.Double(c.getX() - reach, c.getY() - reach, 2 * reach, 2 * reach);
        return g.getTransform().createTransformedShape(circle).getBounds2D();
    }

    static String url(String layer) {
        int at = layer.toLowerCase(Locale.ROOT).indexOf("url(");
        if (at < 0) return null;
        int close = layer.lastIndexOf(')');
        if (close < at) return null;
        String u = layer.substring(at + 4, close).trim();
        if (u.length() >= 2 && (u.charAt(0) == '"' || u.charAt(0) == '\'')) u = u.substring(1, u.length() - 1);
        return u;
    }

    private static double[] size(String size, double natW, double natH, Rectangle2D area, Style s) {
        String l = size.toLowerCase(Locale.ROOT);
        double aw = area.getWidth(), ah = area.getHeight();
        if (l.equals("cover") || l.equals("contain")) {
            double scale = l.equals("cover") ? Math.max(aw / natW, ah / natH) : Math.min(aw / natW, ah / natH);
            return new double[]{natW * scale, natH * scale};
        }
        List<String> parts = Values.splitSpaces(l);
        String a = parts.isEmpty() ? "auto" : parts.get(0), b = parts.size() > 1 ? parts.get(1) : "auto";
        Len la = a.equals("auto") ? null : Len.parse(a, s.context), lb = b.equals("auto") ? null : Len.parse(b, s.context);
        double w = la == null ? Double.NaN : la.of(aw), h = lb == null ? Double.NaN : lb.of(ah);
        if (Double.isNaN(w) && Double.isNaN(h)) return new double[]{natW, natH};
        if (Double.isNaN(w)) w = natH > 0 ? h * natW / natH : h;
        if (Double.isNaN(h)) h = natW > 0 ? w * natH / natW : w;
        return new double[]{w, h};
    }

    private static double[] position(String pos, double[] tile, Rectangle2D area, Style s) {
        List<String> parts = Values.splitSpaces(pos.toLowerCase(Locale.ROOT));
        String px = "0%", py = "0%";
        String xOffset = null, yOffset = null;
        List<String> kws = new ArrayList<>(parts);
        if (kws.size() == 1) {
            String k = kws.get(0);
            if (k.equals("top") || k.equals("bottom")) {
                px = "50%";
                py = k;
            } else {
                px = k;
                py = "50%";
            }
        } else if (kws.size() >= 2) {
            // "right 10px bottom 20px", or "x y" with keywords in either order.
            if (kws.size() == 4) {
                px = kws.get(0);
                xOffset = kws.get(1);
                py = kws.get(2);
                yOffset = kws.get(3);
                if (px.equals("top") || px.equals("bottom")) {
                    String t = px;
                    px = py;
                    py = t;
                    t = xOffset;
                    xOffset = yOffset;
                    yOffset = t;
                }
            } else {
                px = kws.get(0);
                py = kws.get(1);
                if (px.equals("top") || px.equals("bottom") || py.equals("left") || py.equals("right")) {
                    String t = px;
                    px = py;
                    py = t;
                }
            }
        }
        double fx = axis(px, area.getWidth() - tile[0], s, xOffset);
        double fy = axis(py, area.getHeight() - tile[1], s, yOffset);
        return new double[]{area.getX() + fx, area.getY() + fy};
    }

    private static double axis(String v, double free, Style s, String offset) {
        double off = offset == null ? 0 : lenOf(offset, free, s);
        return switch (v) {
            case "left", "top" -> off;
            case "center" -> free / 2;
            case "right", "bottom" -> free - off;
            default -> lenOf(v, free, s);
        };
    }

    private static double lenOf(String v, double basis, Style s) {
        Len l = Len.parse(v, s.context);
        return l == null ? 0 : l.of(basis);
    }

    // ---------------------------------------------------------------- gradients

    private record Stop(int color, double pos) {}

    /** The Paint of the gradient [layer] for a tile at [r], or null if it cannot be read. */
    private static Paint gradientPaint(String layer, Style s, Rectangle2D r) {
        String lower = layer.toLowerCase(Locale.ROOT).trim();
        boolean repeating = lower.startsWith("repeating-");
        String name = lower.substring(0, lower.indexOf('('));
        String inner = layer.substring(layer.indexOf('(') + 1, layer.lastIndexOf(')'));
        List<String> args = Values.splitCommas(inner);
        if (args.isEmpty()) return null;
        if (name.endsWith("linear-gradient")) return linear(args, s, r, repeating);
        if (name.endsWith("radial-gradient")) return radial(args, s, r, repeating);
        if (name.endsWith("conic-gradient")) return conic(args, s, r);
        return null;
    }

    private static Paint linear(List<String> args, Style s, Rectangle2D r, boolean repeating) {
        double angle = 180;
        int first = 0;
        String a0 = args.get(0).trim().toLowerCase(Locale.ROOT);
        Double ang = Values.angle(a0);
        double w = r.getWidth(), h = r.getHeight();
        if (ang != null) {
            angle = ang;
            first = 1;
        } else if (a0.startsWith("to ")) {
            String[] dir = a0.substring(3).trim().split("\\s+");
            int sx = 0, sy = 0;
            for (String d : dir) {
                switch (d) {
                    case "left" -> sx = -1;
                    case "right" -> sx = 1;
                    case "top" -> sy = -1;
                    case "bottom" -> sy = 1;
                    default -> {}
                }
            }
            if (sx != 0 && sy != 0) {
                // To a corner: perpendicular to the diagonal joining the other two.
                angle = Math.toDegrees(Math.atan2(sx * h, -sy * w));
            } else if (sx != 0) {
                angle = sx > 0 ? 90 : 270;
            } else {
                angle = sy > 0 ? 180 : 0;
            }
            first = 1;
        }
        double rad = Math.toRadians(angle);
        double dx = Math.sin(rad), dy = -Math.cos(rad);
        // An angle too big to mean anything (1e400deg reads as Infinity): no direction, no gradient.
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return null;
        double len = Math.abs(w * dx) + Math.abs(h * dy);
        double cx = r.getCenterX(), cy = r.getCenterY();
        List<Stop> stops = stops(args.subList(first, args.size()), s, len);
        if (stops.isEmpty()) return null;
        double startPos = 0, endPos = 1;
        if (repeating) {
            startPos = stops.get(0).pos;
            endPos = stops.get(stops.size() - 1).pos;
            if (endPos - startPos < 1e-6) return new Color(stops.get(0).color, true);
            stops = rescale(stops, startPos, endPos);
        } else {
            stops = clampStops(stops);
        }
        Point2D p0 = new Point2D.Double(cx - dx * len / 2 + dx * len * startPos, cy - dy * len / 2 + dy * len * startPos);
        Point2D p1 = new Point2D.Double(cx - dx * len / 2 + dx * len * endPos, cy - dy * len / 2 + dy * len * endPos);
        // A gradient line of no length (or none that can be measured) is its last colour, as CSS says.
        if (!(p0.distance(p1) >= 1e-6)) return new Color(stops.get(stops.size() - 1).color, true);
        return new LinearGradientPaint(p0, p1, fractions(stops), colors(stops),
                repeating ? MultipleGradientPaint.CycleMethod.REPEAT : MultipleGradientPaint.CycleMethod.NO_CYCLE);
    }

    private static Paint radial(List<String> args, Style s, Rectangle2D r, boolean repeating) {
        String a0 = args.get(0).trim().toLowerCase(Locale.ROOT);
        boolean circle = false;
        String extent = "farthest-corner";
        double cxp = 0.5, cyp = 0.5;
        double rx = Double.NaN, ry = Double.NaN;
        int first = 0;
        if (Values.color(a0.split("\\s+")[0], 0) == null || a0.contains(" at ") || a0.startsWith("at ")) {
            String shape = a0, at = null;
            int atIdx = a0.indexOf("at ");
            if (atIdx >= 0) {
                shape = a0.substring(0, atIdx).trim();
                at = a0.substring(atIdx + 3).trim();
            }
            List<Len> sizes = new ArrayList<>();
            for (String t : shape.split("\\s+")) {
                switch (t) {
                    case "circle" -> circle = true;
                    case "ellipse", "" -> {}
                    case "closest-side", "closest-corner", "farthest-side", "farthest-corner" -> extent = t;
                    default -> {
                        Len l = Len.parse(t, s.context);
                        if (l != null) sizes.add(l);
                    }
                }
            }
            if (sizes.size() == 1) {
                rx = ry = sizes.get(0).of(r.getWidth());
                circle = true;
            } else if (sizes.size() >= 2) {
                rx = sizes.get(0).of(r.getWidth());
                ry = sizes.get(1).of(r.getHeight());
            }
            if (at != null) {
                double[] c = at(at, r, s);
                cxp = c[0];
                cyp = c[1];
            }
            first = 1;
        }
        double w = r.getWidth(), h = r.getHeight();
        double cx = r.getX() + cxp * w, cy = r.getY() + cyp * h;
        if (Double.isNaN(rx)) {
            double left = cx - r.getX(), right = r.getMaxX() - cx, top = cy - r.getY(), bottom = r.getMaxY() - cy;
            double sx = extent.startsWith("closest") ? Math.min(left, right) : Math.max(left, right);
            double sy = extent.startsWith("closest") ? Math.min(top, bottom) : Math.max(top, bottom);
            if (circle) {
                double d = extent.endsWith("corner") ? Math.hypot(sx, sy) : extent.startsWith("closest") ? Math.min(sx, sy) : Math.max(sx, sy);
                rx = ry = d;
            } else if (extent.endsWith("corner")) {
                rx = sx * Math.sqrt(2);
                ry = sy * Math.sqrt(2);
            } else {
                rx = sx;
                ry = sy;
            }
        }
        if (rx <= 0 || ry <= 0) return null;
        List<Stop> stops = stops(args.subList(first, args.size()), s, rx);
        if (stops.isEmpty()) return null;
        stops = repeating ? rescale(stops, 0, Math.max(1e-3, stops.get(stops.size() - 1).pos)) : clampStops(stops);
        double radius = rx;
        if (repeating) radius = rx * Math.max(1e-3, stopsEnd(args.subList(first, args.size()), s, rx));
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.scale(1, ry / rx);
        t.translate(-cx, -cy);
        return new RadialGradientPaint(new Point2D.Double(cx, cy), (float) radius, new Point2D.Double(cx, cy), fractions(stops), colors(stops),
                repeating ? MultipleGradientPaint.CycleMethod.REPEAT : MultipleGradientPaint.CycleMethod.NO_CYCLE,
                MultipleGradientPaint.ColorSpaceType.SRGB, t);
    }

    private static double stopsEnd(List<String> args, Style s, double len) {
        List<Stop> st = stops(args, s, len);
        return st.isEmpty() ? 1 : st.get(st.size() - 1).pos;
    }

    /** A conic gradient as a fan of thin wedges. */
    private static Paint conic(List<String> args, Style s, Rectangle2D r) {
        ConicPaint p = new ConicPaint(args, s, r);
        // No readable colour at all: no gradient, as linear() and radial() say with null.
        return p.stops.isEmpty() ? null : p;
    }

    private static double[] at(String at, Rectangle2D r, Style s) {
        List<String> p = Values.splitSpaces(at);
        String x = p.isEmpty() ? "center" : p.get(0), y = p.size() > 1 ? p.get(1) : "center";
        if (x.equals("top") || x.equals("bottom")) {
            String t = x;
            x = y;
            y = t;
        }
        return new double[]{frac(x, r.getWidth(), s, true), frac(y, r.getHeight(), s, false)};
    }

    private static double frac(String v, double size, Style s, boolean horizontal) {
        return switch (v) {
            case "left", "top" -> 0;
            case "center" -> 0.5;
            case "right", "bottom" -> 1;
            default -> {
                Len l = Len.parse(v, s.context);
                yield l == null || size <= 0 ? 0.5 : l.of(size) / size;
            }
        };
    }

    /** Colour stops with positions as fractions of [len]; missing positions spread evenly, as CSS does. */
    static List<Stop> stops(List<String> args, Style s, double len) {
        List<Integer> colors = new ArrayList<>();
        List<Double> positions = new ArrayList<>();
        for (String arg : args) {
            List<String> parts = Values.splitSpaces(arg.trim());
            if (parts.isEmpty()) continue;
            Integer c = Values.color(parts.get(0), s.color);
            if (c == null) {
                // A colour hint (a lone position) or something unreadable: skip it.
                continue;
            }
            List<Double> ps = new ArrayList<>();
            for (int i = 1; i < parts.size(); i++) {
                Len l = Len.parse(parts.get(i), s.context);
                if (l != null) ps.add(len > 0 ? l.of(len) / len : 0);
            }
            if (ps.isEmpty()) {
                colors.add(c);
                positions.add(Double.NaN);
            } else {
                for (double p : ps) {
                    colors.add(c);
                    positions.add(p);
                }
            }
        }
        int n = colors.size();
        if (n == 0) return List.of();
        if (n == 1) {
            colors.add(colors.get(0));
            positions.add(Double.NaN);
            n = 2;
        }
        if (Double.isNaN(positions.get(0))) positions.set(0, 0.0);
        if (Double.isNaN(positions.get(n - 1))) positions.set(n - 1, 1.0);
        // Each position at least the one before it.
        double max = positions.get(0);
        for (int i = 1; i < n; i++) {
            double p = positions.get(i);
            if (!Double.isNaN(p)) {
                if (p < max) positions.set(i, max);
                max = Math.max(max, p);
            }
        }
        for (int i = 1; i < n; i++) {
            if (!Double.isNaN(positions.get(i))) continue;
            int j = i;
            while (Double.isNaN(positions.get(j))) j++;
            double a = positions.get(i - 1), b = positions.get(j);
            for (int k = i; k < j; k++) positions.set(k, a + (b - a) * (k - i + 1) / (j - i + 1));
        }
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Stop(colors.get(i), positions.get(i)));
        return out;
    }

    /** Stops limited to [0, 1], with the colours at the ends interpolated where the stops went past them. */
    private static List<Stop> clampStops(List<Stop> stops) {
        if (stops.isEmpty()) return stops;
        List<Stop> out = new ArrayList<>();
        if (stops.get(0).pos > 0) out.add(new Stop(stops.get(0).color, 0));
        for (int i = 0; i < stops.size(); i++) {
            Stop st = stops.get(i);
            if (st.pos < 0) {
                if (i + 1 < stops.size() && stops.get(i + 1).pos > 0) out.add(new Stop(mix(st, stops.get(i + 1), 0), 0));
                continue;
            }
            if (st.pos > 1) {
                if (i > 0 && stops.get(i - 1).pos < 1) out.add(new Stop(mix(stops.get(i - 1), st, 1), 1));
                break;
            }
            out.add(st);
        }
        if (out.isEmpty()) out.add(new Stop(stops.get(stops.size() - 1).color, 0));
        if (out.get(out.size() - 1).pos < 1) out.add(new Stop(out.get(out.size() - 1).color, 1));
        return out;
    }

    private static int mix(Stop a, Stop b, double at) {
        double t = b.pos == a.pos ? 0 : (at - a.pos) / (b.pos - a.pos);
        return lerp(a.color, b.color, Math.max(0, Math.min(1, t)));
    }

    static int lerp(int a, int b, double t) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return ((int) Math.round(aa + (ba - aa) * t) << 24) | ((int) Math.round(ar + (br - ar) * t) << 16)
                | ((int) Math.round(ag + (bg - ag) * t) << 8) | (int) Math.round(ab + (bb - ab) * t);
    }

    private static List<Stop> rescale(List<Stop> stops, double from, double to) {
        List<Stop> out = new ArrayList<>();
        for (Stop st : stops) out.add(new Stop(st.color, (st.pos - from) / (to - from)));
        return clampStops(out);
    }

    private static float[] fractions(List<Stop> stops) {
        float[] f = new float[stops.size()];
        float last = -1;
        for (int i = 0; i < f.length; i++) {
            float v = (float) Math.max(0, Math.min(1, stops.get(i).pos));
            // Java needs strictly increasing fractions; a hard stop becomes a very short blend.
            if (v <= last) v = Math.min(1f, last + 1e-5f);
            if (v <= last) v = last;
            f[i] = v;
            last = v;
        }
        // Equal trailing fractions (both at 1) are not allowed either: nudge the earlier ones down.
        for (int i = f.length - 2; i >= 0; i--) if (f[i] >= f[i + 1]) f[i] = Math.max(0, f[i + 1] - 1e-5f);
        return f;
    }

    private static Color[] colors(List<Stop> stops) {
        Color[] c = new Color[stops.size()];
        for (int i = 0; i < c.length; i++) c[i] = new Color(stops.get(i).color, true);
        return c;
    }

    /** conic-gradient(from angle at position, stops): a Paint that looks up each pixel's angle. */
    private static final class ConicPaint implements Paint {
        private final List<Stop> stops;
        private final double cx, cy, from;

        ConicPaint(List<String> args, Style s, Rectangle2D r) {
            String a0 = args.get(0).trim().toLowerCase(Locale.ROOT);
            double fromDeg = 0;
            double[] c = {0.5, 0.5};
            int first = 0;
            if (a0.startsWith("from ") || a0.startsWith("at ")) {
                int atIdx = a0.indexOf("at ");
                String fromPart = atIdx >= 0 ? a0.substring(0, atIdx) : a0;
                if (fromPart.startsWith("from ")) {
                    Double d = Values.angle(fromPart.substring(5).trim());
                    if (d != null) fromDeg = d;
                }
                if (atIdx >= 0) c = at(a0.substring(atIdx + 3).trim(), r, s);
                first = 1;
            }
            List<String> rest = new ArrayList<>();
            for (String arg : args.subList(first, args.size())) {
                // Angle positions as percentages of a turn.
                StringBuilder b = new StringBuilder();
                for (String p : Values.splitSpaces(arg)) {
                    Double d = Values.angle(p);
                    b.append(b.isEmpty() ? "" : " ").append(d != null && !p.equals("0") ? (d / 360 * 100) + "%" : p);
                }
                rest.add(b.toString());
            }
            this.stops = clampStops(stops(rest, s, 1));
            this.cx = r.getX() + c[0] * r.getWidth();
            this.cy = r.getY() + c[1] * r.getHeight();
            this.from = fromDeg;
        }

        @Override
        public java.awt.PaintContext createContext(java.awt.image.ColorModel cm, java.awt.Rectangle deviceBounds, Rectangle2D userBounds,
                                                   AffineTransform xform, java.awt.RenderingHints hints) {
            AffineTransform inv;
            try {
                inv = xform.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                inv = new AffineTransform();
            }
            AffineTransform toUser = inv;
            return new java.awt.PaintContext() {
                @Override public void dispose() {}

                @Override public java.awt.image.ColorModel getColorModel() {
                    return java.awt.image.ColorModel.getRGBdefault();
                }

                @Override
                public java.awt.image.Raster getRaster(int x, int y, int w, int h) {
                    java.awt.image.WritableRaster raster = getColorModel().createCompatibleWritableRaster(w, h);
                    int[] data = new int[w * h];
                    Point2D.Double p = new Point2D.Double();
                    for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                        p.setLocation(x + i + 0.5, y + j + 0.5);
                        toUser.transform(p, p);
                        double ang = Math.toDegrees(Math.atan2(p.x - cx, -(p.y - cy))) - from;
                        double t = ((ang % 360) + 360) % 360 / 360;
                        data[j * w + i] = colorAt(t);
                    }
                    raster.setDataElements(0, 0, w, h, data);
                    return raster;
                }
            };
        }

        private int colorAt(double t) {
            for (int i = 1; i < stops.size(); i++) {
                Stop a = stops.get(i - 1), b = stops.get(i);
                if (t <= b.pos) return mix(a, b, t);
            }
            return stops.get(stops.size() - 1).color;
        }

        @Override
        public int getTransparency() {
            return TRANSLUCENT;
        }
    }
}

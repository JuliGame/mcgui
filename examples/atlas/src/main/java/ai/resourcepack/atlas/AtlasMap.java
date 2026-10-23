package ai.resourcepack.atlas;

import ai.resourcepack.ui.framework.api.Canvas;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * One map: a fullscreen view somebody is looking at, or a wall. It looks like the game's own filled map
 * (the parchment, the colours, the shading) and is drawn on its own thread; the server thread only moves
 * its centre, zoom and pointer.
 */
final class AtlasMap {
    /** Canvas pixels per block. The game's maps are 1 (1:1) down to 1:16; these go both ways. */
    static final double[] ZOOMS = {0.25, 0.5, 1, 2, 4, 8};

    /** A player on the map, as of the last tick. */
    record Marker(UUID id, String name, UUID world, double x, double z, float yaw) {}

    private static final Color PARCHMENT = new Color(0xD6BE96), EDGE = new Color(0x99876C), INK = new Color(0x4A3B28);

    final Canvas canvas;
    final UUID owner;
    final String ownerName;
    private final Terrain terrain;
    private final Pins pins;
    private final int width, height, margin, u;
    private final BufferedImage image;
    private final int[] pixels, paper;
    private final Graphics2D g;
    private final Font font, small;

    volatile UUID world;
    volatile double cx, cz;
    volatile int zoom;
    volatile boolean follow;
    /** The pointer, in canvas pixels; -1 when there is none (walls). */
    volatile int px = -1, py = -1;
    volatile boolean north, south, west, east;
    private long drawn = Long.MIN_VALUE;

    AtlasMap(Canvas canvas, Terrain terrain, Pins pins, Font base, UUID owner, String ownerName, UUID world, double cx, double cz, int zoom) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.pins = pins;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.zoom = zoom;
        this.follow = owner != null;
        width = canvas.width();
        height = canvas.height();
        // A "GUI pixel": the game's interface on a screen 288 of them tall, as at a usual GUI scale.
        u = Math.max(2, Math.round(height / 288f));
        margin = Math.round(Math.min(width, height) * 0.035f);
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        paper = parchment();
        g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        font = base.deriveFont((float) (8 * u));
        small = base.deriveFont((float) (4 * u));
    }

    double scale() {
        return ZOOMS[zoom];
    }

    int mapLeft() { return margin; }
    int mapTop() { return margin; }
    int mapWidth() { return width - 2 * margin; }
    int mapHeight() { return height - 2 * margin; }

    /** The block under canvas pixel (x, y). */
    int blockX(int x) {
        return (int) Math.floor(cx + (x - margin - mapWidth() / 2.0) / scale());
    }

    int blockZ(int y) {
        return (int) Math.floor(cz + (y - margin - mapHeight() / 2.0) / scale());
    }

    private double screenX(double bx) {
        return margin + mapWidth() / 2.0 + (bx - cx) * scale();
    }

    private double screenY(double bz) {
        return margin + mapHeight() / 2.0 + (bz - cz) * scale();
    }

    boolean onMap(int x, int y) {
        return x >= margin && y >= margin && x < width - margin && y < height - margin;
    }

    /** The pin within reach of canvas pixel (x, y), or null. */
    Pins.Pin pinAt(int x, int y) {
        Pins.Pin best = null;
        double reach = 8 * u;
        for (Pins.Pin p : pins.all) {
            if (!p.world().equals(world)) continue;
            double d = Math.hypot(screenX(p.x() + 0.5) - x, screenY(p.z() + 0.5) - 5 * u - y);
            if (d < reach) {
                reach = d;
                best = p;
            }
        }
        return best;
    }

    /** Moves, asks for the chunks it shows, and draws if anything it shows has changed. Render thread. */
    void frame(double dt, List<Marker> players, long now) {
        double step = 450 * u * dt / scale();
        if (north || south || west || east) follow = false;
        if (north) cz -= step;
        if (south) cz += step;
        if (west) cx -= step;
        if (east) cx += step;
        if (follow && owner != null) {
            for (Marker m : players) {
                if (m.id().equals(owner)) {
                    world = m.world();
                    cx = m.x();
                    cz = m.z();
                }
            }
        }
        UUID w = world;
        // Only chunks near somebody can be loaded, so only those are asked for.
        int x0 = Math.floorDiv(blockX(margin), 16) - 1, x1 = Math.floorDiv(blockX(width - margin), 16) + 1;
        int z0 = Math.floorDiv(blockZ(margin), 16) - 1, z1 = Math.floorDiv(blockZ(height - margin), 16) + 1;
        for (Marker m : players) {
            if (!m.world().equals(w)) continue;
            int mx = (int) Math.floor(m.x()) >> 4, mz = (int) Math.floor(m.z()) >> 4;
            for (int cz = Math.max(z0, mz - 12); cz <= Math.min(z1, mz + 12); cz++)
                for (int cx = Math.max(x0, mx - 12); cx <= Math.min(x1, mx + 12); cx++) terrain.want(w, cx, cz, now);
        }

        long key = w.hashCode();
        key = key * 31 + Double.hashCode(cx);
        key = key * 31 + Double.hashCode(cz);
        key = key * 31 + zoom;
        key = key * 31 + terrain.version.get();
        key = key * 31 + px * 7919L + py;
        key = key * 31 + (follow ? 1 : 0);
        for (Marker m : players) key = key * 31 + m.id().hashCode() + Math.round(m.x() * 4) * 131 + Math.round(m.z() * 4) * 7 + Math.round(m.yaw() / 8);
        for (Pins.Pin p : pins.all) key = key * 31 + p.hashCode();
        if (key == drawn) return;
        drawn = key;
        draw(players);
        canvas.draw(image, 0, 0);
    }

    // ---- Drawing ----

    private void draw(List<Marker> players) {
        System.arraycopy(paper, 0, pixels, 0, pixels.length);
        paintTerrain();
        g.setClip(margin, margin, mapWidth(), mapHeight());
        for (Pins.Pin p : pins.all) if (p.world().equals(world)) paintPin(p);
        for (Marker m : players) if (m.world().equals(world)) paintPlayer(m);
        g.setClip(null);
        paintHud();
        if (owner != null && px >= 0) paintHover();
    }

    /** The map's colours, shaded as the game shades a map: lighter facing north-up slopes, darker facing away. */
    private void paintTerrain() {
        int mw = mapWidth(), mh = mapHeight();
        double s = scale();
        UUID w = world;
        for (int y = 0; y < mh; y++) {
            int bz = (int) Math.floor(cz + (y - mh / 2.0 + 0.5) / s);
            int row = (margin + y) * width + margin;
            int tileX = Integer.MIN_VALUE;
            Terrain.Tile tile = null, above = null;
            for (int x = 0; x < mw; x++) {
                int bx = (int) Math.floor(cx + (x - mw / 2.0 + 0.5) / s);
                int tx = bx >> 4;
                if (tx != tileX) {
                    tileX = tx;
                    tile = terrain.tile(w, tx, bz >> 4);
                    above = (bz & 15) == 0 ? terrain.tile(w, tx, (bz - 1) >> 4) : tile;
                }
                if (tile == null) continue;
                int i = (bz & 15) << 4 | (bx & 15);
                int rgb = tile.rgb[i];
                if (rgb == 0) continue;
                int light;
                if (tile.depth[i] > 0) {
                    double f = tile.depth[i] * 0.1 + ((bx + bz) & 1) * 0.2;
                    light = f < 0.5 ? 255 : f > 0.9 ? 180 : 220;
                } else {
                    int north = above == null ? tile.y[i] : above.y[((bz - 1) & 15) << 4 | (bx & 15)];
                    double d = (tile.y[i] - north) + (((bx + bz) & 1) - 0.5) * 0.4;
                    light = d > 0.6 ? 255 : d < -0.6 ? 180 : 220;
                }
                pixels[row + x] = ((rgb >> 16 & 255) * light / 255) << 16 | ((rgb >> 8 & 255) * light / 255) << 8 | (rgb & 255) * light / 255;
            }
        }
    }

    private void paintPlayer(Marker m) {
        double sx = screenX(m.x()), sy = screenY(m.z());
        boolean off = !onMap((int) sx, (int) sy);
        if (off) {
            // As the game does for a player beyond the map's edge: a dot on the edge.
            sx = Math.max(margin + 3 * u, Math.min(width - margin - 3 * u, sx));
            sy = Math.max(margin + 3 * u, Math.min(height - margin - 3 * u, sy));
            g.setColor(Color.BLACK);
            g.fillOval((int) sx - 3 * u, (int) sy - 3 * u, 6 * u, 6 * u);
            g.setColor(Color.WHITE);
            g.fillOval((int) sx - 2 * u, (int) sy - 2 * u, 4 * u, 4 * u);
            return;
        }
        Polygon arrow = new Polygon(new int[]{0, 4, 0, -4}, new int[]{-6, 5, 2, 5}, 4);
        AffineTransform was = g.getTransform();
        g.translate(sx, sy);
        g.rotate(Math.toRadians(m.yaw() + 180));
        g.scale(u, u);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(arrow);
        g.setColor(m.id().equals(owner) ? new Color(0x7FFF7F) : Color.WHITE);
        g.fill(arrow);
        g.setTransform(was);
        if (zoom >= 1) nameplate(m.name(), (int) sx, (int) sy + 9 * u, Color.WHITE);
    }

    private void paintPin(Pins.Pin p) {
        int sx = (int) Math.round(screenX(p.x() + 0.5)), sy = (int) Math.round(screenY(p.z() + 0.5));
        Color c = new Color(Pins.colour(p.owner()));
        // A banner on a pole, as the game marks a banner on a map.
        g.setColor(new Color(0x2B1D10));
        g.fillRect(sx - u, sy - 12 * u, 2 * u, 12 * u);
        Polygon cloth = new Polygon(new int[]{sx - 5 * u, sx + 5 * u, sx + 5 * u, sx, sx - 5 * u},
                new int[]{sy - 13 * u, sy - 13 * u, sy - 4 * u, sy - 6 * u, sy - 4 * u}, 5);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(u));
        g.draw(cloth);
        g.setColor(c);
        g.fill(cloth);
        g.setColor(new Color(255, 255, 255, 60));
        g.fillRect(sx - 5 * u, sy - 13 * u, 10 * u, u);
        if (zoom >= 2) nameplate(p.name(), sx, sy + 3 * u, new Color(0xE0E0E0));
    }

    /** White text on the translucent plate the game draws behind a name tag. */
    private void nameplate(String text, int cx, int top, Color colour) {
        g.setFont(small);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text), h = fm.getAscent() + fm.getDescent();
        g.setColor(new Color(0, 0, 0, 64));
        g.fillRect(cx - w / 2 - u, top, w + 2 * u, h);
        g.setColor(colour);
        g.drawString(text, cx - w / 2, top + fm.getAscent());
    }

    private void paintHud() {
        String where = String.format(Locale.ROOT, "X %d  Z %d", (int) Math.floor(cx), (int) Math.floor(cz));
        String scale = zoom >= 2 ? (int) ZOOMS[zoom] + ":1" : "1:" + (int) Math.round(1 / ZOOMS[zoom]);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"f", where});
        lines.add(new String[]{"7", "Zoom " + scale + (owner != null && follow ? "  ·  following you" : "")});
        panel(lines, margin + 4 * u, margin + 4 * u);
        String hint = owner != null
                ? "WASD move · Scroll zoom · Click pin · " + "Right-click go · F you · Sneak close"
                : "Click to open the map · Scroll to zoom";
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(hint);
        int x = (width - w) / 2, y = height - margin - 6 * u;
        g.setColor(new Color(0, 0, 0, 96));
        g.fillRect(x - 3 * u, y - fm.getAscent() - 2 * u, w + 6 * u, fm.getAscent() + 4 * u);
        text(hint, x, y, Color.WHITE);
    }

    /** The tooltip the game shows over an item, here for the place under the pointer. */
    private void paintHover() {
        int x = px, y = py;
        if (!onMap(x, y)) return;
        List<String[]> lines = new ArrayList<>();
        Pins.Pin pin = pinAt(x, y);
        if (pin != null) {
            lines.add(new String[]{"e", pin.name() + "'s pin"});
            lines.add(new String[]{"7", "X " + pin.x() + "  Z " + pin.z()});
            if (pin.owner().equals(owner)) lines.add(new String[]{"8", "Click to take it down"});
        } else {
            int bx = blockX(x), bz = blockZ(y);
            Terrain.Tile t = terrain.tile(world, bx >> 4, bz >> 4);
            int i = (bz & 15) << 4 | (bx & 15);
            if (t == null) {
                lines.add(new String[]{"7", "Unexplored"});
                lines.add(new String[]{"8", "X " + bx + "  Z " + bz});
            } else {
                lines.add(new String[]{"f", name(t.material[i])});
                lines.add(new String[]{"7", "X " + bx + "  Y " + t.y[i] + "  Z " + bz});
                if (t.depth[i] > 1) lines.add(new String[]{"9", t.depth[i] + " blocks deep"});
                lines.add(new String[]{"3", biome(t.biome[i])});
            }
            if (zoom >= 3) {
                // The block under the pointer, outlined.
                int sx = (int) Math.floor(screenX(bx)), sy = (int) Math.floor(screenY(bz)), s = (int) Math.ceil(scale());
                g.setClip(margin, margin, mapWidth(), mapHeight());
                g.setColor(new Color(255, 255, 255, 200));
                g.setStroke(new BasicStroke(Math.max(1, u / 2)));
                g.drawRect(sx, sy, s, s);
                g.setClip(null);
            }
        }
        tooltip(lines, x + 12 * u, y - 12 * u);
    }

    private void tooltip(List<String[]> lines, int x, int y) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int lh = 10 * u, w = 0;
        for (String[] l : lines) w = Math.max(w, fm.stringWidth(l[1]));
        int h = lines.size() * lh - 2 * u + (lines.size() > 1 ? 2 * u : 0);
        int pad = 4 * u;
        if (x + w + pad * 2 > width) x = Math.max(0, x - w - 24 * u - pad * 2);
        if (y < pad) y = pad;
        int bx = x - pad, by = y - pad, bw = w + 2 * pad, bh = h + 2 * pad;
        Color bg = new Color(16, 0, 16, 240);
        g.setColor(bg);
        g.fillRect(bx + u, by, bw - 2 * u, bh);
        g.fillRect(bx, by + u, bw, bh - 2 * u);
        g.setPaint(new GradientPaint(0, by + u, new Color(80, 0, 255, 80), 0, by + bh - u, new Color(40, 0, 127, 80)));
        g.fillRect(bx + u, by + u, u, bh - 2 * u);
        g.fillRect(bx + bw - 2 * u, by + u, u, bh - 2 * u);
        g.setColor(new Color(80, 0, 255, 80));
        g.fillRect(bx + 2 * u, by + u, bw - 4 * u, u);
        g.setColor(new Color(40, 0, 127, 80));
        g.fillRect(bx + 2 * u, by + bh - 2 * u, bw - 4 * u, u);
        int ty = y + fm.getAscent() - u;
        for (int i = 0; i < lines.size(); i++) {
            text(lines.get(i)[1], x, ty, code(lines.get(i)[0]));
            ty += lh + (i == 0 ? 2 * u : 0);
        }
    }

    private void panel(List<String[]> lines, int x, int y) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (String[] l : lines) w = Math.max(w, fm.stringWidth(l[1]));
        int lh = 10 * u;
        g.setColor(new Color(0, 0, 0, 96));
        g.fillRect(x, y, w + 6 * u, lines.size() * lh + 4 * u);
        int ty = y + 2 * u + fm.getAscent();
        for (String[] l : lines) {
            text(l[1], x + 3 * u, ty, code(l[0]));
            ty += lh;
        }
    }

    /** Text with the game's shadow: its colour at a quarter of the brightness, a GUI pixel down and right. */
    private void text(String s, int x, int y, Color c) {
        g.setColor(new Color(c.getRed() / 4, c.getGreen() / 4, c.getBlue() / 4));
        g.drawString(s, x + u, y + u);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    private static Color code(String c) {
        return new Color(switch (c) {
            case "7" -> 0xAAAAAA;
            case "8" -> 0x555555;
            case "9" -> 0x5555FF;
            case "3" -> 0x00AAAA;
            case "e" -> 0xFFFF55;
            default -> 0xFFFFFF;
        });
    }

    private static String name(Material m) {
        return words(m == null ? "air" : m.name());
    }

    private static String biome(Biome b) {
        return b == null ? "" : words(b.getKey().getKey());
    }

    private static String words(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** The parchment a map is drawn on: flecked, with a darker edge and cut corners, as the game's map background. */
    private int[] parchment() {
        int[] out = new int[width * height];
        Random r = new Random(42);
        int base = PARCHMENT.getRGB() & 0xFFFFFF;
        int blot = Math.max(1, u);
        for (int y = 0; y < height; y += blot) {
            for (int x = 0; x < width; x += blot) {
                int v = r.nextInt(100);
                int c = v < 6 ? 0xCDB38A : v < 10 ? 0xDCC6A0 : base;
                for (int dy = 0; dy < blot && y + dy < height; dy++)
                    for (int dx = 0; dx < blot && x + dx < width; dx++) out[(y + dy) * width + x + dx] = c;
            }
        }
        int edge = EDGE.getRGB() & 0xFFFFFF, e = 2 * u, in = u;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int d = Math.min(Math.min(x, y), Math.min(width - 1 - x, height - 1 - y));
                boolean corner = (x < in + e && y < in + e || x >= width - in - e && y < in + e
                        || x < in + e && y >= height - in - e || x >= width - in - e && y >= height - in - e)
                        && Math.min(x, width - 1 - x) + Math.min(y, height - 1 - y) < in + e + e / 2;
                if (d < in) out[y * width + x] = 0;
                else if (d < in + e || corner) out[y * width + x] = edge;
            }
        }
        // A faint ink line round the map itself.
        int ink = INK.getRGB() & 0xFFFFFF;
        for (int x = margin - u; x < width - margin + u; x++) {
            for (int t = 0; t < Math.max(1, u / 2); t++) {
                out[(margin - u + t) * width + x] = ink;
                out[(height - margin + u - 1 - t) * width + x] = ink;
            }
        }
        for (int y = margin - u; y < height - margin + u; y++) {
            for (int t = 0; t < Math.max(1, u / 2); t++) {
                out[y * width + margin - u + t] = ink;
                out[y * width + width - margin + u - 1 - t] = ink;
            }
        }
        return out;
    }
}

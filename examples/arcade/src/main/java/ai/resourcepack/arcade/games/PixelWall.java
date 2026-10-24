package ai.resourcepack.arcade.games;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent eight-player pixel-art wall.
 *
 * Clicks are individual stamps: Scene supplies no mouse-button release event.
 * SPACE is a genuine hold-to-draw key; F optionally toggles continuous ink.
 * Demo pixels never enter, clear, or overwrite the permanent shared artwork.
 */
public class PixelWall implements Scene {
    private static final int W = 1536, H = 864;
    private static final int GW = 128, GH = 72, CELL = 9;
    private static final int BX = 192, BY = 82;
    private static final int BW = GW * CELL, BH = GH * CELL;
    private static final int PX = 173, PY = 751, STEP = 70, SWATCH = 60;
    private static final int FX = 46, FY = 29, FS = 32;

    private static final Color WHITE = new Color(0xF5F8FF);
    private static final Color MUTED = new Color(0xBCCADA);
    private static final Color MINT = new Color(0x75F0CE);
    private static final Color GOLD = new Color(0xFED83D);
    private static final Color DARK = new Color(0x101927);
    private static final Color EDGE = new Color(0x52677F);
    private static final Color[] DYES = {
        new Color(0xF9FFFE), new Color(0xF9801D),
        new Color(0xC74EBD), new Color(0x3AB3DA),
        new Color(0xFED83D), new Color(0x80C71F),
        new Color(0xF38BAA), new Color(0x474F52),
        new Color(0x9D9D97), new Color(0x169C9C),
        new Color(0x8932B8), new Color(0x3C44AA),
        new Color(0x835432), new Color(0x5E7C16),
        new Color(0xB02E26), new Color(0x1D1D21)
    };
    private static final String[] NAMES = {
        "White", "Orange", "Magenta", "Light blue", "Yellow", "Lime",
        "Pink", "Gray", "Light gray", "Cyan", "Purple", "Blue",
        "Brown", "Green", "Red", "Black", "Eraser"
    };
    private static final int[] START = {3, 1, 5, 2, 4, 6, 9, 14};
    private static final String[] FACE = {
        "........", "........", ".XX..XX.", ".XX..XX.",
        "...XX...", "..XXXX..", "..XXXX..", "..X..X.."
    };
    private static final Font[] FONTS = new Font[81];

    static {
        for (int i = 1; i < FONTS.length; i++) {
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
        }
    }

    private final int[] pixels = new int[GW * GH];
    private final int[] demoOrder = new int[GW * GH];
    private final BufferedImage artwork =
            new BufferedImage(GW, GH, BufferedImage.TYPE_INT_RGB);
    private final BufferedImage demo =
            new BufferedImage(GW, GH, BufferedImage.TYPE_INT_RGB);
    private final Map<UUID, Painter> painters = new LinkedHashMap<>();
    private final List<Painter> ordered = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Rectangle> labels = new ArrayList<>();

    private Host host;
    private BufferedImage chrome;
    private int chromeW, chromeH;
    private int painted, demoStep, demoX = -1, demoY = -1, demoPhase = -1;
    private double clock, soundWait, demoCredit, demoRest, welcome;
    private boolean demonstrating;

    public PixelWall() {
        Arrays.fill(pixels, -1);
        clearImage(artwork);
        clearImage(demo);
        buildDemoOrder();
        resetDemo();
    }

    @Override public int maxPlayers() { return 8; }
    @Override public boolean showPointer() { return false; }

    @Override
    public void start(Host host) {
        this.host = host;
        chrome = null;
        if (host != null) {
            for (Seat seat : host.seats()) joined(seat);
        }
        if (painters.isEmpty() && painted == 0) resetDemo();
    }

    @Override
    public void joined(Seat seat) {
        if (seat == null || seat.id() == null
                || painters.containsKey(seat.id()) || painters.size() >= 8) return;

        boolean first = painters.isEmpty();
        Painter p = new Painter(seat);
        int slot = Math.floorMod(seat.index(), 8);
        p.colour = START[slot];
        p.x = BX + BW / 2.0 + (slot % 4 - 1.5) * 72;
        p.y = BY + BH / 2.0 + (slot / 4) * 72;
        painters.put(seat.id(), p);
        sortPainters();
        demonstrating = false;
        if (first) {
            sparks.clear();
            welcome = painted == 0 ? 12 : 0;
        }
        p.notice = "Ready!";
        p.noticeTime = 1.5;
        sound("block.note_block.chime", 0.22f, 1.3f);
    }

    @Override
    public void left(Seat seat) {
        if (seat == null) return;
        painters.remove(seat.id());
        sortPainters();
        // Shared artwork belongs to the scene, not to any seat.
        if (painters.isEmpty() && painted == 0) resetDemo();
    }

    private void sortPainters() {
        ordered.clear();
        ordered.addAll(painters.values());
        ordered.sort(Comparator.comparingInt(p -> p.seat.index()));
    }

    private Painter painter(Seat seat) {
        return seat == null ? null : painters.get(seat.id());
    }

    private void move(Painter p, int x, int y) {
        p.x = x * (double) W / Math.max(1, host.width());
        p.y = y * (double) H / Math.max(1, host.height());
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        Painter p = painter(seat);
        if (p == null || host == null) return;
        move(p, x, y);
        if (inking(p)) stroke(p);
        else p.lastX = p.lastY = -1;
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        Painter p = painter(seat);
        if (p == null || host == null) return;
        move(p, x, y);
        p.lastX = p.lastY = -1;

        if (!right) {
            int swatch = swatchAt(p.x, p.y);
            if (swatch >= 0) {
                p.colour = swatch == 16 ? -1 : swatch;
                notice(p, name(p));
                sound("block.note_block.hat", 0.16f, 1.3f);
                return;
            }
            int size = sizeAt(p.x, p.y);
            if (size > 0) {
                setBrush(p, size);
                return;
            }
        }

        int cx = cellX(p.x), cy = cellY(p.y);
        if (cx < 0 || cy < 0) return;

        if (right) {
            p.colour = pixels[cy * GW + cx];
            notice(p, "Picked " + name(p));
            sound("block.note_block.pling", 0.18f, 1.6f);
        } else {
            feedback(p, cx, cy, stamp(p, cx, cy));
            if (inking(p)) {
                p.lastX = cx;
                p.lastY = cy;
            }
        }
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        Painter p = painter(seat);
        if (p == null || host == null || key == null) return;
        boolean changed = down ? p.keys.add(key) : p.keys.remove(key);
        if (!changed) return;

        if (key == Key.SWAP_HANDS && down) {
            p.latch = !p.latch;
            p.lastX = p.lastY = -1;
            notice(p, p.latch ? "INK ON" : "INK OFF");
            sound("block.note_block.hat", 0.2f, p.latch ? 1.8f : 0.8f);
            if (inking(p)) stroke(p);
        } else if (key == Key.JUMP) {
            p.lastX = p.lastY = -1;
            if (inking(p)) stroke(p);
        } else if (isDirection(key)) {
            p.nudgeWait = 0;
        }
    }

    @Override
    public void scroll(Seat seat, int notches) {
        Painter p = painter(seat);
        if (p == null || notches == 0) return;
        setBrush(p, (int) Math.max(1L, Math.min(3L, (long) p.brush + notches)));
    }

    private static boolean isDirection(Key key) {
        return key == Key.LEFT || key == Key.RIGHT
                || key == Key.UP || key == Key.DOWN;
    }

    private static boolean inking(Painter p) {
        return p.latch || p.keys.contains(Key.JUMP);
    }

    private void setBrush(Painter p, int size) {
        if (p.brush == size) return;
        p.brush = size;
        p.lastX = p.lastY = -1;
        notice(p, size + " × " + size + " brush");
        sound("block.note_block.hat", 0.16f, 0.6f + size * 0.35f);
    }

    private static void notice(Painter p, String message) {
        p.notice = message;
        p.noticeTime = 1.4;
        p.pulse = 1;
    }

    private void stroke(Painter p) {
        int cx = cellX(p.x), cy = cellY(p.y);
        if (cx < 0 || cy < 0) {
            p.lastX = p.lastY = -1;
            return;
        }
        if (cx == p.lastX && cy == p.lastY) return;
        int x = p.lastX < 0 ? cx : p.lastX;
        int y = p.lastY < 0 ? cy : p.lastY;
        int dx = Math.abs(cx - x), dy = -Math.abs(cy - y);
        int sx = x < cx ? 1 : -1, sy = y < cy ? 1 : -1;
        int error = dx + dy, changed = 0;

        // Bounded Bresenham interpolation; no gaps during fast mouse movement.
        while (true) {
            changed += stamp(p, x, y);
            if (x == cx && y == cy) break;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x += sx; }
            if (twice <= dx) { error += dx; y += sy; }
        }
        p.lastX = cx;
        p.lastY = cy;
        feedback(p, cx, cy, changed);
    }

    private int stamp(Painter p, int cx, int cy) {
        int offset = (p.brush - 1) / 2;
        int changed = 0;
        for (int y = cy - offset; y < cy - offset + p.brush; y++) {
            for (int x = cx - offset; x < cx - offset + p.brush; x++) {
                if (x < 0 || y < 0 || x >= GW || y >= GH) continue;
                int at = y * GW + x, previous = pixels[at];
                if (previous == p.colour) continue;
                if (previous < 0) painted++;
                if (p.colour < 0) painted--;
                pixels[at] = p.colour;
                artwork.setRGB(x, y, pixelRGB(x, y, p.colour));
                changed++;
            }
        }
        return changed;
    }

    private void feedback(Painter p, int cx, int cy, int changed) {
        p.pulse = 1;
        p.hitX = cx;
        p.hitY = cy;
        p.hitLife = 0.38;
        if (changed == 0) return;
        if (p.sparkWait <= 0) {
            burst(BX + (cx + 0.5) * CELL, BY + (cy + 0.5) * CELL,
                    colour(p), Math.min(8, changed + 3));
            p.sparkWait = 0.085;
        }
        if (soundWait <= 0) {
            sound("block.note_block.bit", 0.14f,
                    (float) (0.8 + (p.colour + 1) * 0.045));
            soundWait = 0.1;
        }
    }

    @Override
    public void tick(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        soundWait = Math.max(0, soundWait - dt);
        welcome = Math.max(0, welcome - dt);

        for (Painter p : ordered) {
            p.pulse = Math.max(0, p.pulse - dt * 3);
            p.hitLife = Math.max(0, p.hitLife - dt);
            p.sparkWait = Math.max(0, p.sparkWait - dt);
            p.noticeTime = Math.max(0, p.noticeTime - dt);
            p.nudgeWait -= dt;

            int dx = (p.keys.contains(Key.RIGHT) ? 1 : 0)
                    - (p.keys.contains(Key.LEFT) ? 1 : 0);
            int dy = (p.keys.contains(Key.DOWN) ? 1 : 0)
                    - (p.keys.contains(Key.UP) ? 1 : 0);
            if ((dx != 0 || dy != 0) && p.nudgeWait <= 0) {
                int cx = cellX(p.x), cy = cellY(p.y);
                if (cx >= 0 && cy >= 0) {
                    cx = Math.max(0, Math.min(GW - 1, cx + dx));
                    cy = Math.max(0, Math.min(GH - 1, cy + dy));
                    p.x = BX + (cx + 0.5) * CELL;
                    p.y = BY + (cy + 0.5) * CELL;
                    if (inking(p)) stroke(p);
                }
                p.nudgeWait = 0.075;
            }
        }

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= dt;
            if (s.life <= 0) sparks.remove(i);
            else {
                s.x += s.vx * dt;
                s.y += s.vy * dt;
                s.vy += 90 * dt;
            }
        }

        if (painters.isEmpty() && painted == 0) {
            if (!demonstrating) resetDemo();
            advanceDemo(dt);
        } else {
            demonstrating = false;
        }
    }

    private void buildDemoOrder() {
        boolean[] used = new boolean[GW * GH];
        int at = 0;

        // The face resolves in under half a second.
        for (int y = FY; y < FY + FS; y++) {
            for (int i = 0; i < FS; i++) {
                int x = FX + ((y & 1) == 0 ? i : FS - 1 - i);
                int index = y * GW + x;
                used[index] = true;
                demoOrder[at++] = index;
            }
        }

        // Reveal the sun next, so the scene has two recognizable focal points.
        for (int y = 20; y <= 44; y++) {
            for (int x = 82; x <= 106; x++) {
                int index = y * GW + x;
                if (!used[index]) {
                    used[index] = true;
                    demoOrder[at++] = index;
                }
            }
        }

        // Row by row, alternating direction, so the demo always looks like one stroke in progress.
        for (int y = 0; y < GH; y++) {
            for (int i = 0; i < GW; i++) {
                int x = (y & 1) == 0 ? i : GW - 1 - i;
                int index = y * GW + x;
                if (!used[index]) demoOrder[at++] = index;
            }
        }
    }

    private void resetDemo() {
        demonstrating = true;
        demoStep = 0;
        demoCredit = demoRest = 0;
        demoPhase = -1;
        demoX = demoY = -1;
        clearImage(demo);
    }

    private void advanceDemo(double dt) {
        if (demoStep < demoOrder.length) {
            demoCredit += dt * 3200;
            int count = Math.min((int) demoCredit, demoOrder.length - demoStep);
            demoCredit -= count;
            for (int i = 0; i < count; i++) {
                int index = demoOrder[demoStep++];
                demoX = index % GW;
                demoY = index / GW;
                demo.setRGB(demoX, demoY, DYES[demoColour(demoX, demoY)].getRGB());
            }
            return;
        }

        // Only the disposable demonstration animates. Saved artwork is untouched.
        demoRest += dt;
        int phase = (int) (demoRest * 4);
        if (phase == demoPhase) return;
        demoPhase = phase;

        for (int y = 49; y < 67; y++) {
            for (int x = 80; x < 110; x++) {
                int c = demoColour(x, y);
                int center = 94 + (int) (2 * Math.sin(phase * 0.3 + y));
                if (Math.abs(x - center) < (y - 39) / 2
                        && Math.floorMod(y + phase, 6) < 2) {
                    c = y < 57 ? 4 : 1;
                }
                demo.setRGB(x, y, DYES[c].getRGB());
            }
        }

        for (int i = 0; i < 18; i++) {
            int x = 7 + (i * 37) % 116, y = 3 + (i * 11) % 15;
            int c = Math.floorMod(phase + i * 3, 13) < 3 ? 0 : demoColour(x, y);
            demo.setRGB(x, y, DYES[c].getRGB());
        }
        demoX = 82 + Math.floorMod(phase * 3, 25);
        demoY = 51 + Math.floorMod(phase, 13);
    }

    private static boolean inFace(int x, int y) {
        return x >= FX && x < FX + FS && y >= FY && y < FY + FS;
    }

    private static int demoColour(int x, int y) {
        if (inFace(x, y)) {
            int u = (x - FX) / 4, v = (y - FY) / 4;
            if (FACE[v].charAt(u) == 'X') return 15;
            if (x == FX || y == FY + FS - 1) return 13;
            return (x / 2 + y / 3) % 9 == 0 ? 13 : 5;
        }

        int c = y < 10 ? 11 : y < 19 ? 10 : y < 28 ? 2
                : y < 36 ? 6 : y < 43 ? 1 : y < 49 ? 4 : 11;
        int dx = x - 94, dy = y - 32;
        if (dx * dx + dy * dy <= 144 && y < 45) c = 4;

        int mountain = 45 - (int) (7 * Math.sin(x * 0.075)
                + 4 * Math.sin(x * 0.18));
        if (y >= mountain && y < 50) c = 10;
        if (y >= 48) {
            c = y % 8 < 2 ? 9 : 11;
            if (Math.abs(x - 94) < (y - 40) / 2 && y % 5 < 2)
                c = y < 60 ? 1 : 6;
        }
        if (y > 66 + 2 * Math.sin(x * 0.09)) c = 15;
        return c;
    }

    private static int pixelRGB(int x, int y, int colour) {
        if (colour >= 0) return DYES[colour].getRGB();
        // Very low contrast: the empty canvas should not overpower a single pixel.
        return ((x / 4 + y / 4) & 1) == 0 ? 0xFF253244 : 0xFF273446;
    }

    private static void clearImage(BufferedImage image) {
        for (int y = 0; y < GH; y++)
            for (int x = 0; x < GW; x++)
                image.setRGB(x, y, pixelRGB(x, y, -1));
    }

    private void burst(double x, double y, Color colour, int count) {
        for (int i = 0; i < count && sparks.size() < 128; i++) {
            double angle = i * Math.PI * 2 / count + clock;
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            s.vx = Math.cos(angle) * (30 + i * 5);
            s.vy = Math.sin(angle) * (30 + i * 5) - 22;
            s.life = 0.4;
            s.colour = colour;
            sparks.add(s);
        }
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && !painters.isEmpty()) {
            host.sound(id, volume, Math.max(0.5f, Math.min(2f, pitch)));
        }
    }

    @Override
    public void draw(Graphics2D output) {
        int width = host == null ? cols() * 128 : Math.max(1, host.width());
        int height = host == null ? rows() * 128 : Math.max(1, host.height());
        ensureChrome(width, height);

        Graphics2D g = (Graphics2D) output.create();
        try {
            g.drawImage(chrome, 0, 0, null);
            g.scale(width / (double) W, height / (double) H);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(demonstrating ? demo : artwork, BX, BY, BW, BH, null);

            right(g, painters.size() + " / 8 ARTISTS", 27, BX + BW, 48, WHITE);
            drawRoster(g);
            drawPalette(g);
            drawBoardEffects(g);
            drawWelcome(g);

            labels.clear();
            for (Painter p : ordered) drawCursor(g, p);

            if (painters.isEmpty()) {
                center(g, painted == 0
                        ? "TAKE A SEAT  •  MAKE PIXEL ART TOGETHER"
                        : "THE ART STAYS. TAKE A SEAT & ADD YOUR COLOUR.",
                        27, W / 2, 848, MINT);
                int x = BX + (int) ((0.5 + 0.5 * Math.sin(clock * 0.7)) * (BW - 80));
                g.setColor(MINT);
                g.fillRoundRect(x, BY + BH + 8, 80, 3, 3, 3);
            } else {
                center(g, "CLICK: stamp   •   SPACE + MOVE: draw   •   F: ink on/off"
                        + "   •   RIGHT CLICK: pick   •   SCROLL: size",
                        23, W / 2, 848, WHITE);
            }
        } finally {
            g.dispose();
        }
    }

    private void ensureChrome(int width, int height) {
        if (chrome != null && chromeW == width && chromeH == height) return;
        chromeW = width;
        chromeH = height;
        chrome = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = chrome.createGraphics();
        try {
            g.scale(width / (double) W, height / (double) H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x1C2B40),
                    0, H, new Color(0x0C1320)));
            g.fillRect(0, 0, W, H);

            g.setColor(new Color(0x2B3A4D));
            for (int x = 24; x < W; x += 48)
                for (int y = 24; y < H; y += 48) g.fillRect(x, y, 2, 2);

            text(g, "PIXEL", 46, BX, 49, WHITE);
            text(g, "WALL", 46, BX + 152, 49, MINT);
            text(g, "SMALL PIXELS. BIG IDEAS.", 24, BX + 314, 43, MUTED);

            g.setColor(new Color(0x080E18));
            g.fillRoundRect(BX - 8, BY - 4, BW + 16, BH + 18, 10, 10);
            g.setColor(EDGE);
            g.fillRoundRect(BX - 4, BY - 4, BW + 8, BH + 8, 6, 6);

            text(g, "ARTISTS", 26, 20, 103, MINT);
            text(g, "128 × 72", 28, 20, 690, WHITE);
            text(g, "SHARED PIXELS", 19, 20, 720, MUTED);

            int x = 1362;
            text(g, "YOUR TOOLS", 23, x, 103, MINT);
            text(g, "CLICK", 27, x, 151, WHITE);
            text(g, "Stamp", 23, x, 181, MUTED);

            text(g, "SPACE", 27, x, 235, WHITE);
            text(g, "Hold + move", 22, x, 266, MUTED);
            text(g, "to draw", 23, x, 295, MUTED);

            text(g, "F", 27, x, 345, GOLD);
            text(g, "Ink on / off", 22, x, 376, MUTED);

            text(g, "BRUSH", 27, x, 426, WHITE);
            for (int size = 1; size <= 3; size++) {
                int xx = x + (size - 1) * 51;
                panel(g, xx, 442, 43, 47);
                center(g, Integer.toString(size), 27, xx + 21, 475, WHITE);
            }
            text(g, "Scroll / click", 22, x, 520, MUTED);

            text(g, "RIGHT CLICK", 22, x, 570, WHITE);
            text(g, "Pick colour", 23, x, 601, MUTED);

            text(g, "W A S D", 25, x, 656, WHITE);
            text(g, "Nudge cursor", 21, x, 686, MUTED);
            text(g, "Art is kept.", 23, x, 720, MINT);

            for (int i = 0; i < 17; i++) {
                int xx = PX + i * STEP;
                g.setColor(new Color(0x070C16));
                g.fillRoundRect(xx, PY + 4, SWATCH, SWATCH, 9, 9);
                g.setColor(i == 16 ? new Color(0x40516A) : DYES[i]);
                g.fillRoundRect(xx, PY, SWATCH, SWATCH, 9, 9);
                g.setColor(new Color(255, 255, 255, 90));
                g.setStroke(new BasicStroke(1));
                g.drawRoundRect(xx + 1, PY + 1, SWATCH - 2, SWATCH - 2, 9, 9);
                if (i == 16) eraser(g, xx + 18, PY + 8, 25);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawRoster(Graphics2D g) {
        if (ordered.isEmpty()) {
            text(g, demonstrating ? "LIVE DEMO" : "GALLERY", 25, 20, 154, WHITE);
            text(g, "8 artists.", 24, 20, 217, WHITE);
            text(g, "16 dyes.", 24, 20, 251, WHITE);
            text(g, "One wall.", 24, 20, 285, MINT);
            text(g, "No timer.", 23, 20, 355, MUTED);
            text(g, "No pressure.", 22, 20, 389, MUTED);
            text(g, "Just create.", 23, 20, 423, MUTED);

            if (demonstrating) {
                text(g, "GOLDEN", 26, 20, 510, GOLD);
                text(g, "HOUR", 26, 20, 542, GOLD);
                text(g, "An example", 22, 20, 582, MUTED);
                text(g, "in 16 dyes", 22, 20, 612, MUTED);
                g.setColor(EDGE);
                g.fillRoundRect(20, 636, 145, 5, 5, 5);
                g.setColor(MINT);
                g.fillRoundRect(20, 636,
                        Math.max(5, 145 * demoStep / demoOrder.length), 5, 5, 5);
            } else {
                text(g, Integer.toString(painted), 36, 20, 535, WHITE);
                text(g, "painted", 24, 20, 573, MUTED);
                text(g, "pixels", 24, 20, 605, MUTED);
            }
            return;
        }

        int y = 119;
        for (Painter p : ordered) {
            panel(g, 12, y, 165, 63);
            g.setColor(colour(p));
            g.fillRoundRect(19, y + 9, 6, 43, 3, 3);
            text(g, number(p) + " " + fit(g, p.seat.name(), 22, 129),
                    22, 32, y + 26, WHITE);
            String detail = name(p) + " · " + p.brush;
            text(g, fit(g, detail, 19, 137), 19, 32, y + 51,
                    inking(p) ? MINT : MUTED);
            if (inking(p)) {
                g.setColor(MINT);
                g.fillOval(158, y + 5, 9, 9);
            }
            y += 66;
        }
    }

    private void drawPalette(Graphics2D g) {
        for (int i = 0; i < 17; i++) {
            int count = 0;
            for (Painter p : ordered) {
                if ((p.colour < 0 ? 16 : p.colour) == i) {
                    int x = PX + i * STEP + 4 + (count % 3) * 18;
                    int y = PY + 7 + (count / 3) * 18;
                    g.setColor(DARK);
                    g.fillRoundRect(x, y, 17, 18, 4, 4);
                    center(g, Integer.toString(number(p)), 16, x + 8, y + 15, WHITE);
                    count++;
                }
                if (swatchAt(p.x, p.y) == i) {
                    g.setStroke(new BasicStroke(2));
                    g.setColor(WHITE);
                    g.drawRoundRect(PX + i * STEP - 3, PY - 3,
                            SWATCH + 6, SWATCH + 6, 12, 12);
                }
            }
        }
    }

    private void drawWelcome(Graphics2D g) {
        if (ordered.isEmpty() || welcome <= 0 || painted > 36) return;
        // Remains useful after the first few stamps; never blocks input.
        double fade = Math.min(1, welcome / 1.3);
        Graphics2D b = (Graphics2D) g.create();
        try {
            b.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) (fade * 0.94)));
            int x = BX + 211, y = BY + 233;
            panel(b, x, y, 730, 134);
            center(b, "MAKE YOUR FIRST MASTERPIECE.", 31, W / 2, y + 42, WHITE);
            center(b, "Click for pixels. Hold SPACE + move for lines.", 26,
                    W / 2, y + 80, MINT);
            center(b, "Or press F to turn continuous ink on / off.", 23,
                    W / 2, y + 113, MUTED);
        } finally {
            b.dispose();
        }
    }

    private void drawBoardEffects(Graphics2D g) {
        Graphics2D b = (Graphics2D) g.create();
        try {
            b.clipRect(BX, BY, BW, BH);
            for (Spark s : sparks) {
                b.setColor(alpha(s.colour, (int) (255 * s.life / 0.4)));
                b.fillRect((int) s.x - 2, (int) s.y - 2, 5, 5);
            }
            for (Painter p : ordered) {
                if (p.hitLife > 0) {
                    double t = 1 - p.hitLife / 0.38;
                    int r = (int) (7 + t * 21);
                    int x = BX + p.hitX * CELL + CELL / 2;
                    int y = BY + p.hitY * CELL + CELL / 2;
                    b.setStroke(new BasicStroke(2));
                    b.setColor(alpha(colour(p), (int) ((1 - t) * 190)));
                    b.drawRect(x - r, y - r, r * 2, r * 2);
                }

                int cx = cellX(p.x), cy = cellY(p.y);
                if (cx < 0 || cy < 0) continue;
                int off = (p.brush - 1) / 2;
                int x = BX + (cx - off) * CELL;
                int y = BY + (cy - off) * CELL;
                int size = p.brush * CELL;
                b.setColor(alpha(colour(p), inking(p) ? 105 : 55));
                b.fillRect(x, y, size, size);
                b.setStroke(new BasicStroke(4));
                b.setColor(DARK);
                b.drawRect(x, y, size, size);
                b.setStroke(new BasicStroke(1.5f));
                b.setColor(WHITE);
                b.drawRect(x, y, size, size);
            }

            if (demonstrating && demoX >= 0) {
                int x = BX + demoX * CELL, y = BY + demoY * CELL;
                b.setColor(DARK);
                b.setStroke(new BasicStroke(4));
                b.drawRect(x - 4, y - 4, CELL + 8, CELL + 8);
                b.setColor(WHITE);
                b.setStroke(new BasicStroke(1.5f));
                b.drawRect(x - 4, y - 4, CELL + 8, CELL + 8);
            }
        } finally {
            b.dispose();
        }
    }

    private void drawCursor(Graphics2D g, Painter p) {
        if (p.x < 0 || p.y < 0 || p.x >= W || p.y >= H) return;
        int x = (int) p.x, y = (int) p.y;
        Color c = colour(p);
        boolean onBoard = cellX(p.x) >= 0 && cellY(p.y) >= 0;

        if (!onBoard) {
            // Compact identified cursor over controls: never cover instructions
            // with a full name tag. Names remain visible in the artist roster.
            int xx = Math.max(12, Math.min(W - 13, x));
            int yy = Math.max(12, Math.min(H - 13, y));
            g.setColor(DARK);
            g.fillOval(xx - 12, yy - 12, 24, 24);
            g.setColor(c);
            g.setStroke(new BasicStroke(2));
            g.drawOval(xx - 12, yy - 12, 24, 24);
            center(g, Integer.toString(number(p)), 17, xx, yy + 6, WHITE);

            int swatch = swatchAt(p.x, p.y);
            if (swatch >= 0) {
                String s = number(p) + " · " + NAMES[swatch];
                g.setFont(FONTS[23]);
                int tw = g.getFontMetrics().stringWidth(s) + 24;
                int tx = Math.max(BX + 4, Math.min(BX + BW - tw - 4, x - tw / 2));
                panel(g, tx, BY + BH - 41, tw, 34);
                text(g, s, 23, tx + 12, BY + BH - 16, WHITE);
            }
            return;
        }

        g.setStroke(new BasicStroke(5));
        g.setColor(DARK);
        g.drawLine(x - 8, y, x + 8, y);
        g.drawLine(x, y - 8, x, y + 8);
        g.setStroke(new BasicStroke(2));
        g.setColor(WHITE);
        g.drawLine(x - 8, y, x + 8, y);
        g.drawLine(x, y - 8, x, y + 8);
        g.setColor(c);
        g.fillRect(x - 2, y - 2, 4, 4);

        String detail = p.noticeTime > 0 ? p.notice
                : p.brush + "px" + (inking(p) ? " · INK" : "");
        String label = number(p) + " " + fit(g, p.seat.name(), 23, 155) + " · " + detail;
        g.setFont(FONTS[23]);
        int width = Math.min(430, g.getFontMetrics().stringWidth(label) + 36);
        Rectangle box = placeLabel(x, y, width, 36);

        g.setStroke(new BasicStroke(1));
        g.setColor(EDGE);
        int endX = Math.max(box.x + 6, Math.min(box.x + box.width - 6, x));
        int endY = Math.max(box.y, Math.min(box.y + box.height, y));
        g.drawLine(x, y, endX, endY);
        panel(g, box.x, box.y, box.width, box.height);
        g.setColor(WHITE);
        g.fillRect(box.x + 8, box.y + 10, 15, 15);
        g.setColor(c);
        g.fillRect(box.x + 10, box.y + 12, 11, 11);
        text(g, fit(g, label, 23, width - 37), 23, box.x + 30, box.y + 26, WHITE);
    }

    private Rectangle placeLabel(int x, int y, int width, int height) {
        Rectangle best = null;
        long bestCost = Long.MAX_VALUE;
        // Search nearby rows on either side, always wholly inside the artwork.
        for (int row = 0; row < 18; row++) {
            int direction = (row & 1) == 0 ? -1 : 1;
            int distance = 1 + row / 2;
            for (int side = 0; side < 2; side++) {
                int xx = side == 0 ? x + 18 : x - width - 18;
                int yy = y + direction * distance * 41;
                xx = Math.max(BX + 5, Math.min(BX + BW - width - 5, xx));
                yy = Math.max(BY + 5, Math.min(BY + BH - height - 5, yy));
                Rectangle candidate = new Rectangle(xx, yy, width, height);
                long cost = row * 30L + side * 5L;
                for (Rectangle used : labels) {
                    Rectangle overlap = candidate.intersection(used);
                    if (!overlap.isEmpty()) cost += (long) overlap.width * overlap.height * 100;
                }
                for (Painter other : ordered) {
                    if (candidate.contains(other.x, other.y)) cost += 200_000;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
        }
        labels.add(best);
        return best;
    }

    private static int cellX(double x) {
        return x >= BX && x < BX + BW ? (int) ((x - BX) / CELL) : -1;
    }

    private static int cellY(double y) {
        return y >= BY && y < BY + BH ? (int) ((y - BY) / CELL) : -1;
    }

    private static int swatchAt(double x, double y) {
        if (x < PX || x >= PX + 16 * STEP + SWATCH
                || y < PY || y >= PY + SWATCH) return -1;
        int index = (int) ((x - PX) / STEP);
        return x - PX - index * STEP < SWATCH ? index : -1;
    }

    private static int sizeAt(double x, double y) {
        if (y < 442 || y >= 489 || x < 1362 || x >= 1507) return -1;
        int slot = (int) ((x - 1362) / 51);
        return slot < 3 && x - 1362 - slot * 51 < 43 ? slot + 1 : -1;
    }

    private static int number(Painter p) {
        return Math.floorMod(p.seat.index(), 8) + 1;
    }

    private static Color colour(Painter p) {
        return p.colour < 0 ? WHITE : DYES[p.colour];
    }

    private static String name(Painter p) {
        return NAMES[p.colour < 0 ? 16 : p.colour];
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, a)));
    }

    private static String fit(Graphics2D g, String value, int size, int maxWidth) {
        if (value == null || value.isEmpty()) value = "Artist";
        // Seat names are normally short; cap pathological input before measuring.
        if (value.length() > 64) value = value.substring(0, 64) + "…";
        g.setFont(FONTS[size]);
        if (g.getFontMetrics().stringWidth(value) <= maxWidth) return value;
        int end = value.length();
        while (end > 0) {
            end = value.offsetByCodePoints(end, -1);
            String shortened = value.substring(0, end) + "…";
            if (g.getFontMetrics().stringWidth(shortened) <= maxWidth) return shortened;
        }
        return "…";
    }

    private static void text(Graphics2D g, String value, int size,
                             int x, int y, Color colour) {
        g.setFont(FONTS[size]);
        g.setColor(colour);
        g.drawString(value, x, y);
    }

    private static void center(Graphics2D g, String value, int size,
                               int x, int y, Color colour) {
        g.setFont(FONTS[size]);
        text(g, value, size, x - g.getFontMetrics().stringWidth(value) / 2, y, colour);
    }

    private static void right(Graphics2D g, String value, int size,
                              int x, int y, Color colour) {
        g.setFont(FONTS[size]);
        text(g, value, size, x - g.getFontMetrics().stringWidth(value), y, colour);
    }

    private static void panel(Graphics2D g, int x, int y, int width, int height) {
        g.setColor(DARK);
        g.fillRoundRect(x, y, width, height, 9, 9);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(x, y, width, height, 9, 9);
    }

    private static void eraser(Graphics2D g, int x, int y, int size) {
        g.setColor(DYES[6]);
        g.fillPolygon(new int[]{x, x + size / 2, x + size, x + size / 2},
                new int[]{y + size / 2, y, y + size / 2, y + size}, 4);
        g.setColor(WHITE);
        g.fillPolygon(new int[]{x, x + size / 4, x + size * 3 / 4, x + size / 2},
                new int[]{y + size / 2, y + size / 4,
                        y + size * 3 / 4, y + size}, 4);
        g.setStroke(new BasicStroke(2));
        g.drawLine(x + 2, y + size + 3, x + size, y + size + 3);
    }

    private static final class Painter {
        final Seat seat;
        final EnumSet<Key> keys = EnumSet.noneOf(Key.class);
        int colour, brush = 1;
        int lastX = -1, lastY = -1, hitX, hitY;
        double x, y, pulse, sparkWait, noticeTime, nudgeWait, hitLife;
        String notice = "";
        boolean latch;

        Painter(Seat seat) {
            this.seat = seat;
        }
    }

    private static final class Spark {
        double x, y, vx, vy, life;
        Color colour;
    }
}

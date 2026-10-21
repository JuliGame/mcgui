package ai.resourcepack.arcade;

import ai.resourcepack.uiframework.api.Canvas;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One game of Neon Breakout on one canvas. Everything here runs on the arcade's loop thread; the server
 * thread only hands it input, through {@link #aim}, {@link #press()} and {@link #post(Runnable)}.
 *
 * <p>The point of the example is how it draws. A whole frame is never sent: the background and the bricks
 * are kept in a still picture that only changes when a brick does, and each frame redraws and hands the
 * canvas only the rectangles something moved through. The canvas then re-encodes only the 128-pixel tiles
 * those touch, and each viewer is sent only the part of each map that changed.
 */
final class Game implements Loop.Ticker {
    /** The playfield is 1024 units wide whatever the canvas, and as tall as the canvas's shape makes it. */
    static final double W = 1024;
    static final double HUD = 52, SIDE = 32, BRICK_TOP = 84, BRICK_H = 24, GAP = 6, PADDLE_H = 14, R = 8;
    static final int COLS = 14, ROWS = 8;
    static final double BRICK_W = (W - 2 * SIDE - (COLS - 1) * GAP) / COLS;

    static final Color PINK = new Color(0xFF2E88), CYAN = new Color(0x00E5FF), MINT = new Color(0x00FFA3),
            GOLD = new Color(0xFFD36E), VIOLET = new Color(0xB04DFF);
    private static final Color[] ROW_COLOURS = {
            new Color(0xFF2E88), new Color(0xFF4FD8), new Color(0xB04DFF), new Color(0x6B5CFF),
            new Color(0x2E9BFF), new Color(0x00E5FF), new Color(0x00FFA3), new Color(0xC6FF3D)};

    private static final String[] LEVEL_NAMES = {"THE WALL", "DIAMOND", "CHECKMATE", "INVADER", "BARRICADES"};
    /** '.' nothing, 'x' a brick, 'X' a brick that takes two hits. */
    private static final String[][] LEVELS = {
            {"xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx",
             "xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx", "xxxxxxxxxxxxxx"},
            {"......xx......", "....xxxxxx....", "..xxxxXXxxxx..", "xxxxXXXXXXxxxx",
             "xxxxXXXXXXxxxx", "..xxxxXXxxxx..", "....xxxxxx....", "......xx......"},
            {"XxXxXxXxXxXxXx", "xXxXxXxXxXxXxX", "x.x.x.x.x.x.x.", ".x.x.x.x.x.x.x",
             "x.x.x.x.x.x.x.", ".x.x.x.x.x.x.x", "x.x.x.x.x.x.x.", ".x.x.x.x.x.x.x"},
            {"...x......x...", "....x....x....", "...xxxxxxxx...", "..xxXxxxxXxx..",
             ".xxxxxxxxxxxx.", ".x.xxxxxxxx.x.", ".x.x......x.x.", "....xx..xx...."},
            {"XXXXXXXXXXXXXX", "..............", "xx.xx.xx.xx.xx", "xx.xx.xx.xx.xx",
             "..............", "XXX..XXXX..XXX", "xxx..xxxx..xxx", "xxx..xxxx..xxx"}};

    enum Mode { SERVE, PLAY, CLEAR, OVER }

    /** What a game can sound like; the plugin picks the Minecraft sound for each. */
    enum Sfx { START, LAUNCH, PADDLE, BRICK, METAL, LOSE, CLEAR, OVER, POWER }

    /** What the game tells the world: sounds, and a finished game's score. Called on the loop thread. */
    interface Host {
        void sound(Sfx sfx, float pitch);

        /** [player] finished with [score]: their place in the table (1 is the top), or 0. */
        int finished(String player, int score);

        List<Scores.Entry> top();
    }

    final Canvas canvas;
    final double scale, H;
    private final Host host;
    private final Random rng = new Random();

    /** Where the player's pointer is, in playfield units; NaN until it has moved. Written by the server thread. */
    volatile double aim = Double.NaN;
    private final AtomicInteger presses = new AtomicInteger();
    private final ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();

    // The game.
    private String player;
    private Mode mode = Mode.SERVE;
    private double modeTime, clock;
    private int level = 1, score, lives = 3, combo, rank;
    private double speed, paddleX = W / 2, paddleW = 120, wideTime, paddleFlash, aiOffset;
    private final double paddleY;
    private final List<Brick> bricks = new ArrayList<>();
    private final List<Ball> balls = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Drop> drops = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();

    // The drawing.
    private final BufferedImage background, still, frame;
    private final Graphics2D gs, gf;
    private final AffineTransform world;
    private final List<Rectangle2D> stillDirty = new ArrayList<>();
    private final List<Rectangle> frameDirty = new ArrayList<>();
    private List<Rectangle2D> lastMoving = List.of();
    private String hudKey = "", overlayKey = "";
    private Rectangle2D overlayArea;
    private List<Scores.Entry> top = List.of();

    Game(Canvas canvas, Host host) {
        this.canvas = canvas;
        this.host = host;
        this.scale = canvas.width() / W;
        this.H = canvas.height() / scale;
        this.paddleY = H - 46;
        world = AffineTransform.getScaleInstance(scale, scale);
        background = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
        still = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
        frame = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D gb = smooth(background.createGraphics());
        gb.transform(world);
        paintBackground(gb);
        gb.dispose();
        gs = smooth(still.createGraphics());
        gf = smooth(frame.createGraphics());
        demo();
    }

    // ---- Input, from the server thread ----

    void press() {
        presses.incrementAndGet();
    }

    /** Runs [task] on the loop thread at the start of the next frame. */
    void post(Runnable task) {
        posted.add(task);
    }

    void seat(String name) {
        post(() -> {
            player = name;
            level = 1;
            score = 0;
            lives = 3;
            build();
            serve();
            host.sound(Sfx.START, 1f);
        });
    }

    void unseat() {
        post(this::demo);
    }

    /** Back to the attract screen: the game plays itself until somebody sits down. */
    private void demo() {
        player = null;
        level = 1 + rng.nextInt(LEVELS.length);
        score = 0;
        lives = 3;
        build();
        serve();
    }

    // ---- The game ----

    @Override
    public void tick(double dt) {
        for (Runnable r; (r = posted.poll()) != null; ) r.run();
        int clicks = presses.getAndSet(0);
        clock += dt;
        modeTime += dt;
        movePaddle(dt);
        switch (mode) {
            case SERVE -> {
                stick();
                if (player != null ? clicks > 0 && modeTime > 0.25 : modeTime > 1.2) launch();
            }
            case PLAY -> simulate(dt);
            case CLEAR -> {
                if (modeTime > 2.4) {
                    level++;
                    build();
                    serve();
                }
            }
            case OVER -> {
                if (player != null && clicks > 0 && modeTime > 1.5) seat(player);
            }
        }
        effects(dt);
        render();
    }

    private void build() {
        bricks.clear();
        String[] rows = LEVELS[(level - 1) % LEVELS.length];
        int extra = (level - 1) / LEVELS.length;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = rows[r].charAt(c);
                if (ch == '.') continue;
                int hits = Math.min(3, (ch == 'X' ? 2 : 1) + extra);
                bricks.add(new Brick(SIDE + c * (BRICK_W + GAP), BRICK_TOP + r * (BRICK_H + GAP), r, hits));
            }
        }
        drops.clear();
        stillDirty.add(new Rectangle2D.Double(0, 0, W, H));
    }

    private void serve() {
        mode = Mode.SERVE;
        modeTime = 0;
        combo = 0;
        balls.clear();
        speed = baseSpeed();
        Ball b = new Ball();
        b.stuck = (rng.nextDouble() - 0.5) * 40;
        balls.add(b);
        stick();
    }

    private double baseSpeed() {
        return 430 + 35 * (Math.min(level, 10) - 1);
    }

    private void stick() {
        for (Ball b : balls) {
            b.x = paddleX + b.stuck;
            b.y = paddleY - R - 1;
            b.trail(false);
        }
    }

    private void launch() {
        double a = Math.toRadians((rng.nextBoolean() ? 1 : -1) * (12 + rng.nextDouble() * 22));
        for (Ball b : balls) {
            b.vx = speed * Math.sin(a);
            b.vy = -speed * Math.cos(a);
        }
        mode = Mode.PLAY;
        modeTime = 0;
        host.sound(Sfx.LAUNCH, 0.7f);
    }

    private void movePaddle(double dt) {
        double target;
        if (player != null) {
            double a = aim;
            target = Double.isNaN(a) ? paddleX : a;
            // Pointer reports come 20 times a second and frames 30: close most of the gap each frame so it glides.
            paddleX += (target - paddleX) * Math.min(1, dt * 28);
        } else {
            // A ball waiting on the paddle moves with it: chasing that would drive the paddle into a wall.
            Ball b = mode == Mode.PLAY ? lowest() : null;
            target = b == null ? W / 2 : b.x + aiOffset;
            double step = 780 * dt;
            paddleX += Math.max(-step, Math.min(step, target - paddleX));
        }
        wideTime = Math.max(0, wideTime - dt);
        double wantW = wideTime > 0 ? 190 : 120;
        paddleW += (wantW - paddleW) * Math.min(1, dt * 8);
        paddleX = Math.max(paddleW / 2, Math.min(W - paddleW / 2, paddleX));
        paddleFlash = Math.max(0, paddleFlash - dt);
    }

    /** The ball the demo should be chasing: the lowest one coming down. */
    private Ball lowest() {
        Ball best = null;
        for (Ball b : balls) if (b.vy >= 0 && (best == null || b.y > best.y)) best = b;
        return best != null ? best : balls.isEmpty() ? null : balls.get(0);
    }

    private void simulate(double dt) {
        List<Ball> lost = new ArrayList<>();
        for (Ball b : new ArrayList<>(balls)) {
            int steps = Math.max(1, (int) Math.ceil(speed * dt / (R * 0.5)));
            double sub = dt / steps;
            for (int i = 0; i < steps; i++) {
                b.x += b.vx * sub;
                b.y += b.vy * sub;
                if (b.x < R) { b.x = R; b.vx = Math.abs(b.vx); }
                if (b.x > W - R) { b.x = W - R; b.vx = -Math.abs(b.vx); }
                if (b.y < HUD + R) { b.y = HUD + R; b.vy = Math.abs(b.vy); }
                paddle(b);
                bricks(b);
                if (b.y > H + 30) { lost.add(b); break; }
            }
            b.trail(true);
        }
        balls.removeAll(lost);
        if (balls.isEmpty()) {
            if (player == null) {
                serve();
            } else if (--lives <= 0) {
                over();
            } else {
                host.sound(Sfx.LOSE, 0.6f);
                serve();
            }
        } else if (bricks.stream().noneMatch(Brick::alive)) {
            mode = Mode.CLEAR;
            modeTime = 0;
            int bonus = 500 * level;
            if (player != null) score += bonus;
            popups.add(new Popup(W / 2, H * 0.62, "BONUS +" + bonus, GOLD, 30));
            balls.clear();
            host.sound(Sfx.CLEAR, 1.2f);
        }
    }

    private void paddle(Ball b) {
        if (b.vy <= 0) return;
        double half = paddleW / 2;
        if (b.y + R < paddleY || b.y - R > paddleY + PADDLE_H || Math.abs(b.x - paddleX) > half + R) return;
        double off = Math.max(-1, Math.min(1, (b.x - paddleX) / half));
        double a = Math.toRadians(off * 62);
        b.vx = speed * Math.sin(a);
        b.vy = -speed * Math.cos(a);
        b.y = paddleY - R - 0.1;
        combo = 0;
        paddleFlash = 0.15;
        aiOffset = (rng.nextDouble() - 0.5) * paddleW * 0.7;
        host.sound(Sfx.PADDLE, 0.5f);
    }

    private void bricks(Ball b) {
        for (Brick k : bricks) {
            if (!k.alive()) continue;
            double cx = Math.max(k.x, Math.min(b.x, k.x + BRICK_W));
            double cy = Math.max(k.y, Math.min(b.y, k.y + BRICK_H));
            double nx = b.x - cx, ny = b.y - cy;
            if (nx * nx + ny * ny > R * R) continue;
            if (Math.abs(nx) > Math.abs(ny)) {
                b.vx = Math.copySign(Math.abs(b.vx), nx);
                b.x = cx + Math.copySign(R + 0.1, nx);
            } else {
                // Also when the centre got inside: send it back the way it came.
                double s = ny != 0 ? ny : -b.vy;
                b.vy = Math.copySign(Math.abs(b.vy), s);
                b.y = cy + Math.copySign(R + 0.1, s);
            }
            hit(k);
            speed = Math.min(baseSpeed() + 320, speed + 3);
            double v = Math.hypot(b.vx, b.vy);
            b.vx *= speed / v;
            b.vy *= speed / v;
            return;
        }
    }

    private void hit(Brick k) {
        stillDirty.add(k.glowBounds());
        if (--k.hits > 0) {
            host.sound(Sfx.METAL, 1.4f);
            burst(k, 5, Color.WHITE);
            return;
        }
        int value = (10 + (ROWS - 1 - k.row) * 5) * (k.tough ? 2 : 1) * (1 + combo / 4);
        if (player != null) score += value;
        combo++;
        // A rising scale: each brick broken without touching the paddle is a semitone higher.
        float pitch = (float) Math.min(2.0, 0.5 * Math.pow(2, Math.min(combo, 24) / 12.0));
        host.sound(Sfx.BRICK, pitch);
        burst(k, 16, k.colour);
        popups.add(new Popup(k.x + BRICK_W / 2, k.y, "+" + value, k.colour, 16));
        if (rng.nextDouble() < 0.14) {
            double roll = rng.nextDouble();
            Drop.Kind kind = roll < 0.4 ? Drop.Kind.MULTI : roll < 0.75 ? Drop.Kind.WIDE : roll < 0.93 ? Drop.Kind.SLOW : Drop.Kind.LIFE;
            drops.add(new Drop(k.x + BRICK_W / 2, k.y + BRICK_H / 2, kind));
        }
    }

    private void burst(Brick k, int n, Color colour) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = k.x + rng.nextDouble() * BRICK_W;
            p.y = k.y + rng.nextDouble() * BRICK_H;
            double a = rng.nextDouble() * Math.PI * 2, v = 60 + rng.nextDouble() * 220;
            p.vx = Math.cos(a) * v;
            p.vy = Math.sin(a) * v - 80;
            p.max = p.life = 0.5 + rng.nextDouble() * 0.6;
            p.size = 3 + rng.nextDouble() * 5;
            p.colour = colour;
            particles.add(p);
        }
    }

    private void over() {
        mode = Mode.OVER;
        modeTime = 0;
        rank = host.finished(player, score);
        host.sound(Sfx.OVER, 0.8f);
    }

    private void effects(double dt) {
        particles.removeIf(p -> (p.life -= dt) <= 0);
        for (Particle p : particles) {
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vy += 520 * dt;
        }
        popups.removeIf(p -> (p.life -= dt) <= 0);
        for (Popup p : popups) p.y -= 40 * dt;
        for (var it = drops.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.y += 170 * dt;
            if (d.y > H + 20) {
                it.remove();
            } else if (d.y + 8 >= paddleY && d.y - 8 <= paddleY + PADDLE_H && Math.abs(d.x - paddleX) <= paddleW / 2 + 17) {
                it.remove();
                collect(d.kind);
            }
        }
    }

    private void collect(Drop.Kind kind) {
        String text = switch (kind) {
            case MULTI -> {
                List<Ball> more = new ArrayList<>();
                for (Ball b : balls) {
                    if (balls.size() + more.size() >= 8) break;
                    for (int s = -1; s <= 1; s += 2) {
                        Ball c = new Ball();
                        c.x = b.x;
                        c.y = b.y;
                        double a = Math.atan2(b.vx, -b.vy) + s * Math.toRadians(24);
                        c.vx = speed * Math.sin(a);
                        c.vy = -speed * Math.cos(a);
                        c.trail(false);
                        more.add(c);
                    }
                }
                balls.addAll(more);
                yield "MULTIBALL";
            }
            case WIDE -> {
                wideTime = 12;
                yield "WIDE";
            }
            case SLOW -> {
                speed = Math.max(baseSpeed() * 0.8, speed * 0.72);
                for (Ball b : balls) {
                    double v = Math.hypot(b.vx, b.vy);
                    if (v > 0) { b.vx *= speed / v; b.vy *= speed / v; }
                }
                yield "SLOW";
            }
            case LIFE -> {
                lives = Math.min(6, lives + 1);
                yield "1UP";
            }
        };
        popups.add(new Popup(paddleX, paddleY - 24, text, kind.colour, 22));
        host.sound(Sfx.POWER, 1.2f);
    }

    // ---- Drawing ----

    private void render() {
        String hud = hudKey();
        if (!hud.equals(hudKey)) {
            hudKey = hud;
            stillDirty.add(new Rectangle2D.Double(0, 0, W, HUD + 6));
        }
        // The still picture: background, bricks and the scoreboard, rebuilt only where one of them changed.
        for (Rectangle2D area : stillDirty) {
            Rectangle d = device(area);
            if (d.isEmpty()) continue;
            gs.setTransform(new AffineTransform());
            gs.setClip(d);
            gs.drawImage(background, 0, 0, null);
            gs.transform(world);
            for (Brick k : bricks) if (k.alive() && k.glowBounds().intersects(area)) paintBrick(gs, k);
            if (area.getMinY() < HUD + 6) paintHud(gs);
            frameDirty.add(d);
        }
        stillDirty.clear();

        // The overlay is redrawn only when what it says changes, not every frame.
        String ov = overlayKey();
        if (!ov.equals(overlayKey)) {
            if (overlayArea != null) frameDirty.add(device(overlayArea));
            overlayKey = ov;
            overlayArea = overlayBounds();
            if (overlayArea != null) frameDirty.add(device(overlayArea));
        }

        // Everything that moves, where it was and where it is.
        List<Rectangle2D> moving = movingBounds();
        for (Rectangle2D r : lastMoving) frameDirty.add(device(r));
        for (Rectangle2D r : moving) frameDirty.add(device(r));
        lastMoving = moving;

        List<Rectangle> rects = merge(frameDirty);
        frameDirty.clear();
        for (Rectangle r : rects) {
            gf.setTransform(new AffineTransform());
            gf.setClip(r);
            gf.drawImage(still, 0, 0, null);
            gf.transform(world);
            paintMoving(gf);
            paintOverlay(gf);
        }
        for (Rectangle r : rects) canvas.draw(frame.getSubimage(r.x, r.y, r.width, r.height), r.x, r.y);
    }

    private Rectangle device(Rectangle2D r) {
        int x0 = (int) Math.floor(r.getMinX() * scale) - 2, y0 = (int) Math.floor(r.getMinY() * scale) - 2;
        int x1 = (int) Math.ceil(r.getMaxX() * scale) + 2, y1 = (int) Math.ceil(r.getMaxY() * scale) + 2;
        return new Rectangle(x0, y0, x1 - x0, y1 - y0).intersection(new Rectangle(0, 0, canvas.width(), canvas.height()));
    }

    /** Joins rectangles that overlap or nearly do; one whole-canvas rectangle once they cover half of it. */
    private List<Rectangle> merge(List<Rectangle> in) {
        List<Rectangle> out = new ArrayList<>();
        for (Rectangle r : in) if (!r.isEmpty()) out.add(new Rectangle(r));
        boolean again = true;
        while (again) {
            again = false;
            search:
            for (int i = 0; i < out.size(); i++) {
                Rectangle a = out.get(i), grown = new Rectangle(a.x - 16, a.y - 16, a.width + 32, a.height + 32);
                for (int j = i + 1; j < out.size(); j++) {
                    if (grown.intersects(out.get(j))) {
                        out.set(i, a.union(out.remove(j)));
                        again = true;
                        break search;
                    }
                }
            }
        }
        long area = 0;
        for (Rectangle r : out) area += (long) r.width * r.height;
        if (area > (long) canvas.width() * canvas.height() / 2) return List.of(new Rectangle(0, 0, canvas.width(), canvas.height()));
        return out;
    }

    private List<Rectangle2D> movingBounds() {
        List<Rectangle2D> out = new ArrayList<>();
        out.add(new Rectangle2D.Double(paddleX - paddleW / 2 - 16, paddleY - 16, paddleW + 32, PADDLE_H + 32));
        for (Ball b : balls) out.add(b.bounds());
        for (Drop d : drops) out.add(new Rectangle2D.Double(d.x - 26, d.y - 16, 52, 32));
        Rectangle2D sparks = null;
        for (Particle p : particles) {
            Rectangle2D r = new Rectangle2D.Double(p.x - p.size, p.y - p.size, p.size * 2, p.size * 2);
            sparks = sparks == null ? r : sparks.createUnion(r);
        }
        if (sparks != null) out.add(sparks);
        for (Popup p : popups) out.add(new Rectangle2D.Double(p.x - p.size * 4, p.y - p.size * 1.4, p.size * 8, p.size * 2));
        return out;
    }

    private void paintMoving(Graphics2D g) {
        Composite was = g.getComposite();
        for (Drop d : drops) {
            RoundRectangle2D pill = new RoundRectangle2D.Double(d.x - 17, d.y - 8, 34, 16, 16, 16);
            g.setColor(alpha(d.kind.colour, 60));
            g.fill(new RoundRectangle2D.Double(d.x - 21, d.y - 12, 42, 24, 24, 24));
            g.setPaint(new GradientPaint((float) d.x, (float) d.y - 8, d.kind.colour.brighter(), (float) d.x, (float) d.y + 8, d.kind.colour.darker()));
            g.fill(pill);
            text(g, d.kind.letter, bold(12), d.x, d.y + 4.5, Color.WHITE, null, 0);
        }
        for (Particle p : particles) {
            g.setColor(alpha(p.colour, (int) (255 * Math.max(0, p.life / p.max))));
            g.fill(new Rectangle2D.Double(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size));
        }
        // The paddle: a soft glow, then a cyan-to-pink bar with a highlight.
        double px = paddleX - paddleW / 2;
        for (int i = 3; i >= 1; i--) {
            g.setColor(alpha(i == 1 ? Color.WHITE : CYAN, paddleFlash > 0 ? 70 : 28));
            double m = i * 4;
            g.fill(new RoundRectangle2D.Double(px - m, paddleY - m, paddleW + 2 * m, PADDLE_H + 2 * m, PADDLE_H + 2 * m, PADDLE_H + 2 * m));
        }
        g.setPaint(new GradientPaint((float) px, 0, paddleFlash > 0 ? Color.WHITE : CYAN, (float) (px + paddleW), 0, PINK));
        g.fill(new RoundRectangle2D.Double(px, paddleY, paddleW, PADDLE_H, PADDLE_H, PADDLE_H));
        g.setColor(new Color(255, 255, 255, 150));
        g.fill(new RoundRectangle2D.Double(px + 6, paddleY + 2, paddleW - 12, 4, 4, 4));
        for (Ball b : balls) {
            for (int i = 0; i < Ball.TRAIL; i++) {
                int at = Math.floorMod(b.head - i, Ball.TRAIL);
                double f = 1 - (double) i / Ball.TRAIL, r = R * (0.35 + 0.6 * f);
                g.setColor(alpha(CYAN, (int) (110 * f * f)));
                g.fill(new Ellipse2D.Double(b.tx[at] - r, b.ty[at] - r, r * 2, r * 2));
            }
            g.setPaint(new RadialGradientPaint(new Point2D.Double(b.x, b.y), (float) (R * 3.4), new float[]{0f, 0.3f, 1f},
                    new Color[]{alpha(CYAN, 170), alpha(CYAN, 60), alpha(CYAN, 0)}, MultipleGradientPaint.CycleMethod.NO_CYCLE));
            g.fill(new Ellipse2D.Double(b.x - R * 3.4, b.y - R * 3.4, R * 6.8, R * 6.8));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(b.x - R, b.y - R, R * 2, R * 2));
        }
        for (Popup p : popups) {
            int a = (int) (255 * Math.min(1, p.life / 0.4));
            text(g, p.text, bold(p.size), p.x, p.y, alpha(Color.WHITE, a), alpha(p.colour, a / 2), 0);
        }
        g.setComposite(was);
    }

    // ---- The overlay: title, banners, game over ----

    private boolean blink() {
        return clock % 1.1 < 0.75;
    }

    private String overlayKey() {
        if (player == null) {
            top = host.top();
            boolean scores = (clock % 12) >= 6 && !top.isEmpty();
            return "demo|" + scores + "|" + blink() + "|" + (scores ? top.toString() : "");
        }
        return switch (mode) {
            case SERVE -> "serve|" + (modeTime < 1.8) + "|" + blink() + "|" + level;
            case CLEAR -> "clear|" + level;
            case OVER -> "over|" + score + "|" + rank + "|" + (modeTime > 1.5 && blink());
            case PLAY -> "";
        };
    }

    private Rectangle2D overlayBounds() {
        if (overlayKey.isEmpty()) return null;
        if (player == null || mode == Mode.OVER) return new Rectangle2D.Double(W / 2 - 300, H * 0.5 - 110, 600, 250);
        return new Rectangle2D.Double(W / 2 - 330, H * 0.5 - 30, 660, 170);
    }

    private void paintOverlay(Graphics2D g) {
        if (overlayKey.isEmpty() || overlayArea == null) return;
        double cy = H * 0.5;
        if (player == null) {
            panel(g, W / 2 - 280, cy - 96, 560, 222);
            if (overlayKey.startsWith("demo|true")) {
                text(g, "HIGH SCORES", bold(30), W / 2, cy - 52, Color.WHITE, CYAN, 0);
                for (int i = 0; i < Math.min(5, top.size()); i++) {
                    Scores.Entry e = top.get(i);
                    double y = cy - 12 + i * 26;
                    Color c = i == 0 ? GOLD : Color.WHITE;
                    text(g, (i + 1) + ".", mono(20), W / 2 - 170, y, c, null, -1);
                    text(g, e.name(), mono(20), W / 2 - 130, y, c, null, -1);
                    text(g, String.format("%06d", e.score()), mono(20), W / 2 + 170, y, c, null, 1);
                }
            } else {
                text(g, "NEON", italic(60), W / 2, cy - 30, Color.WHITE, PINK, 0);
                text(g, "BREAKOUT", italic(60), W / 2, cy + 30, Color.WHITE, CYAN, 0);
                text(g, "MOVE THE MOUSE  ·  CLICK TO LAUNCH  ·  SNEAK TO LEAVE", bold(14), W / 2, cy + 64, alpha(Color.WHITE, 170), null, 0);
            }
            if (blink()) text(g, "CLICK TO PLAY", bold(24), W / 2, cy + 108, GOLD, PINK, 0);
            return;
        }
        switch (mode) {
            case SERVE -> {
                if (modeTime < 1.8) {
                    text(g, "LEVEL " + level, italic(52), W / 2, cy + 30, Color.WHITE, PINK, 0);
                    text(g, LEVEL_NAMES[(level - 1) % LEVEL_NAMES.length], bold(20), W / 2, cy + 62, CYAN, null, 0);
                }
                if (blink()) text(g, "CLICK TO LAUNCH", bold(22), W / 2, cy + 110, GOLD, PINK, 0);
            }
            case CLEAR -> text(g, "LEVEL CLEAR!", italic(56), W / 2, cy + 40, Color.WHITE, MINT, 0);
            case OVER -> {
                panel(g, W / 2 - 280, cy - 96, 560, 222);
                text(g, "GAME OVER", italic(58), W / 2, cy - 22, Color.WHITE, PINK, 0);
                text(g, String.format("SCORE %06d", score), mono(26), W / 2, cy + 20, Color.WHITE, null, 0);
                if (rank > 0) text(g, "NEW HIGH SCORE  #" + rank, bold(22), W / 2, cy + 56, GOLD, PINK, 0);
                if (modeTime > 1.5 && blink()) text(g, "CLICK TO PLAY AGAIN", bold(20), W / 2, cy + 104, CYAN, null, 0);
            }
            case PLAY -> {}
        }
    }

    private void panel(Graphics2D g, double x, double y, double w, double h) {
        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, w, h, 28, 28);
        g.setColor(new Color(10, 2, 30, 200));
        g.fill(box);
        g.setStroke(new BasicStroke(6));
        g.setColor(alpha(PINK, 50));
        g.draw(box);
        g.setStroke(new BasicStroke(2));
        g.setColor(alpha(PINK, 220));
        g.draw(box);
    }

    // ---- The still picture ----

    private String hudKey() {
        List<Scores.Entry> t = host.top();
        String hi = t.isEmpty() ? "" : t.get(0).name() + t.get(0).score();
        return player + "|" + score + "|" + lives + "|" + level + "|" + hi;
    }

    private void paintHud(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new Rectangle2D.Double(0, 0, W, HUD));
        g.setColor(alpha(CYAN, 50));
        g.fill(new Rectangle2D.Double(0, HUD - 2, W, 6));
        g.setColor(CYAN);
        g.fill(new Rectangle2D.Double(0, HUD, W, 1.5));
        if (player == null) {
            text(g, "INSERT COIN", bold(13), 24, 20, PINK, null, -1);
            text(g, "DEMO", mono(22), 24, 42, alpha(Color.WHITE, 200), null, -1);
        } else {
            text(g, player.toUpperCase(), bold(13), 24, 20, PINK, null, -1);
            text(g, String.format("%06d", score), mono(22), 24, 42, Color.WHITE, null, -1);
        }
        List<Scores.Entry> t = host.top();
        text(g, "HIGH SCORE", bold(13), W / 2, 20, GOLD, null, 0);
        text(g, t.isEmpty() ? "------" : String.format("%06d  %s", t.get(0).score(), t.get(0).name().toUpperCase()),
                mono(22), W / 2, 42, Color.WHITE, null, 0);
        text(g, "LEVEL " + level, bold(13), W - 24, 20, MINT, null, 1);
        if (player != null) {
            for (int i = 0; i < lives; i++) {
                double x = W - 32 - i * 22;
                g.setColor(alpha(CYAN, 70));
                g.fill(new Ellipse2D.Double(x - 9, 27, 18, 18));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(x - 5.5, 30.5, 11, 11));
            }
        }
    }

    private void paintBrick(Graphics2D g, Brick k) {
        Color c = k.colour;
        g.setColor(alpha(c, 45));
        g.fill(new RoundRectangle2D.Double(k.x - 4, k.y - 4, BRICK_W + 8, BRICK_H + 8, 14, 14));
        Color top = k.hits > 1 ? c.darker() : c.brighter(), bottom = k.hits > 1 ? c.darker().darker() : c.darker();
        g.setPaint(new GradientPaint(0, (float) k.y, top, 0, (float) (k.y + BRICK_H), bottom));
        g.fill(new RoundRectangle2D.Double(k.x, k.y, BRICK_W, BRICK_H, 8, 8));
        g.setColor(new Color(255, 255, 255, 80));
        g.fill(new RoundRectangle2D.Double(k.x + 4, k.y + 3, BRICK_W - 8, BRICK_H * 0.32, 6, 6));
        if (k.hits > 1) {
            g.setStroke(new BasicStroke(2f));
            g.setColor(k.hits > 2 ? GOLD : new Color(235, 235, 255));
            g.draw(new RoundRectangle2D.Double(k.x + 1, k.y + 1, BRICK_W - 2, BRICK_H - 2, 8, 8));
        }
    }

    /** Synthwave: a night sky, a striped sun sinking into a grid. Drawn once, at the canvas's own resolution. */
    private void paintBackground(Graphics2D g) {
        double horizon = H * 0.64;
        g.setPaint(new java.awt.LinearGradientPaint(0, 0, 0, (float) horizon, new float[]{0f, 0.6f, 1f},
                new Color[]{new Color(0x07021A), new Color(0x1B0640), new Color(0x4A0E5C)}));
        g.fill(new Rectangle2D.Double(0, 0, W, horizon));
        Random stars = new Random(7);
        for (int i = 0; i < 260; i++) {
            double x = stars.nextDouble() * W, y = HUD + stars.nextDouble() * (horizon - HUD) * 0.8, s = 0.6 + stars.nextDouble() * 1.6;
            g.setColor(new Color(255, 255, 255, 40 + stars.nextInt(140)));
            g.fill(new Ellipse2D.Double(x, y, s, s));
        }
        double sr = 170;
        Area sun = new Area(new Ellipse2D.Double(W / 2 - sr, horizon - sr * 1.15, sr * 2, sr * 2));
        sun.intersect(new Area(new Rectangle2D.Double(0, 0, W, horizon)));
        for (int i = 0; i < 7; i++) {
            double y = horizon - 12 - i * 22, h = 9 - i * 1.2;
            if (h > 0) sun.subtract(new Area(new Rectangle2D.Double(0, y, W, h)));
        }
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(W / 2, horizon - sr * 0.15), (float) (sr * 1.9),
                new float[]{0f, 1f}, new Color[]{alpha(PINK, 110), alpha(PINK, 0)}));
        g.fill(new Rectangle2D.Double(W / 2 - sr * 2, horizon - sr * 2, sr * 4, sr * 2));
        g.setPaint(new GradientPaint(0, (float) (horizon - sr * 1.15), GOLD, 0, (float) horizon, PINK));
        g.fill(sun);
        g.setComposite(was);
        g.setPaint(new GradientPaint(0, (float) horizon, new Color(0x1A0433), 0, (float) H, new Color(0x05010F)));
        g.fill(new Rectangle2D.Double(0, horizon, W, H - horizon));
        g.setStroke(new BasicStroke(1.4f));
        for (int i = 1; i <= 12; i++) {
            double t = i / 12.0, y = horizon + (H - horizon) * t * t;
            g.setColor(alpha(PINK, (int) (40 + 120 * t)));
            g.draw(new java.awt.geom.Line2D.Double(0, y, W, y));
        }
        for (int i = -14; i <= 14; i++) {
            g.setColor(alpha(PINK, 70));
            g.draw(new java.awt.geom.Line2D.Double(W / 2 + i * 12, horizon, W / 2 + i * 150, H));
        }
        g.setStroke(new BasicStroke(3f));
        g.setColor(alpha(PINK, 90));
        g.draw(new java.awt.geom.Line2D.Double(0, horizon, W, horizon));
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 170, 220));
        g.draw(new java.awt.geom.Line2D.Double(0, horizon, W, horizon));
        // Side rails, so the walls the ball bounces off can be seen.
        for (double x : new double[]{1.5, W - 1.5}) {
            g.setColor(alpha(VIOLET, 60));
            g.fill(new Rectangle2D.Double(x - 4, HUD, 8, H - HUD));
            g.setColor(VIOLET);
            g.fill(new Rectangle2D.Double(x - 1, HUD, 2, H - HUD));
        }
    }

    // ---- Text ----

    private static Font bold(double size) {
        return new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((float) size);
    }

    private static Font italic(double size) {
        return new Font(Font.SANS_SERIF, Font.BOLD | Font.ITALIC, 1).deriveFont((float) size);
    }

    private static Font mono(double size) {
        return new Font(Font.MONOSPACED, Font.BOLD, 1).deriveFont((float) size);
    }

    /** [s] on its baseline at y, centred on x (align 0), starting at it (-1) or ending at it (1), with a neon [glow]. */
    private static void text(Graphics2D g, String s, Font font, double x, double y, Color core, Color glow, int align) {
        var gv = font.createGlyphVector(g.getFontRenderContext(), s);
        double w = gv.getLogicalBounds().getWidth();
        double x0 = align < 0 ? x : align > 0 ? x - w : x - w / 2;
        Shape shape = gv.getOutline((float) x0, (float) y);
        if (glow != null) {
            g.setColor(alpha(glow, glow.getAlpha() * 50 / 255));
            g.setStroke(new BasicStroke(font.getSize2D() * 0.32f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
            g.setColor(alpha(glow, glow.getAlpha() * 130 / 255));
            g.setStroke(new BasicStroke(font.getSize2D() * 0.12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
        }
        g.setColor(core);
        g.fill(shape);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    private static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    // ---- Pieces ----

    private static final class Brick {
        final double x, y;
        final int row;
        final boolean tough;
        final Color colour;
        int hits;

        Brick(double x, double y, int row, int hits) {
            this.x = x;
            this.y = y;
            this.row = row;
            this.hits = hits;
            this.tough = hits > 1;
            this.colour = ROW_COLOURS[row % ROW_COLOURS.length];
        }

        boolean alive() {
            return hits > 0;
        }

        Rectangle2D glowBounds() {
            return new Rectangle2D.Double(x - 5, y - 5, BRICK_W + 10, BRICK_H + 10);
        }
    }

    private static final class Ball {
        static final int TRAIL = 9;
        double x, y, vx, vy, stuck;
        final double[] tx = new double[TRAIL], ty = new double[TRAIL];
        int head;

        /** Records where the ball is now; [append] false fills the whole trail with it (no streak from nowhere). */
        void trail(boolean append) {
            if (!append) {
                java.util.Arrays.fill(tx, x);
                java.util.Arrays.fill(ty, y);
                return;
            }
            head = (head + 1) % TRAIL;
            tx[head] = x;
            ty[head] = y;
        }

        Rectangle2D bounds() {
            double minX = x, maxX = x, minY = y, maxY = y;
            for (int i = 0; i < TRAIL; i++) {
                minX = Math.min(minX, tx[i]);
                maxX = Math.max(maxX, tx[i]);
                minY = Math.min(minY, ty[i]);
                maxY = Math.max(maxY, ty[i]);
            }
            double m = R * 3.5;
            return new Rectangle2D.Double(minX - m, minY - m, maxX - minX + 2 * m, maxY - minY + 2 * m);
        }
    }

    private static final class Particle {
        double x, y, vx, vy, life, max, size;
        Color colour;
    }

    private static final class Drop {
        enum Kind {
            MULTI("M", PINK), WIDE("W", CYAN), SLOW("S", MINT), LIFE("+", GOLD);

            final String letter;
            final Color colour;

            Kind(String letter, Color colour) {
                this.letter = letter;
                this.colour = colour;
            }
        }

        double x, y;
        final Kind kind;

        Drop(double x, double y, Kind kind) {
            this.x = x;
            this.y = y;
            this.kind = kind;
        }
    }

    private static final class Popup {
        double x, y, life = 0.9;
        final double size;
        final String text;
        final Color colour;

        Popup(double x, double y, String text, Color colour, double size) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.colour = colour;
            this.size = size;
        }
    }
}

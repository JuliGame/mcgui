package ai.resourcepack.arcade.games;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Pixels;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Pong Duel
 *
 * Review of the supplied frames and report:
 * - No reported exceptions or performance problem: 6.4 ms worst case is healthy.
 * - The main brief already works. Short rallies under sweeping scripted input
 *   are not, by themselves, evidence of a physics bug.
 * - The grid and scanlines are too prominent; the speed display is too small.
 * - Enlarging the rasterized text makes it less crisp than native-resolution text.
 * - The attract card obscures part of the actual demonstration.
 * - The ball's dotted trail is visually disconnected, and point feedback is brief.
 * - A late join inherits the CPU's match score. Joining should start a fair duel.
 * - End-screen names and results should remain snapshots if somebody leaves.
 *
 * Improvements:
 * quieter court, native-resolution typography, larger ball and speed display,
 * continuous luminous trail, stronger point celebrations, unobstructed attract
 * play, deliberate AI reaction times, fair roster joins, and frozen final results.
 *
 * All simulation is in 1024 x 576 logical units. Effects use a reusable 512 x 288
 * image; text is drawn directly onto the supplied canvas. No external assets.
 */
public final class PongDuel implements Scene {
    private static final int W = 1024, H = 576;
    private static final int TOP = 112, BOTTOM = 498;
    private static final double MID = (TOP + BOTTOM) / 2.0;
    private static final int LEFT = 46, RIGHT = W - LEFT;
    private static final int HALF = 56, PW = 13, R = 10;
    private static final int WIN = 7, TRAIL = 12;
    private static final double START_SPEED = 375, MAX_SPEED = 950;

    private static final Color NAVY = new Color(0x080F20);
    private static final Color CYAN = new Color(0x43EAFF);
    private static final Color PINK = new Color(0xFF57B5);
    private static final Color WHITE = new Color(0xF1FAFF);
    private static final Color MUTED = new Color(0xA4B7D0);
    private static final Color GOLD = new Color(0xFFE09A);
    private static final Color[] COLORS = {CYAN, PINK};
    private static final Font[] FONTS = new Font[113];

    static {
        for (int i = 1; i < FONTS.length; i++) {
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
        }
    }

    private enum Mode { SERVE, PLAY, POINT, OVER }

    private Host host;
    private final Seat[] players = new Seat[2];
    private final int[] points = new int[2];
    private final int[] earned = new int[2];
    private final int[] ranks = new int[2];

    private final String[] finalNames = {"", ""};
    private final boolean[] finalHuman = new boolean[2];
    private final double[] paddle = {MID, MID};
    private final double[] target = {MID, MID};
    private final double[] mouseAge = {10, 10};
    private final boolean[] up = new boolean[2];
    private final boolean[] down = new boolean[2];
    private final boolean[] jump = new boolean[2];
    private final double[] hitFlash = new double[2];
    private final double[] scorePop = new double[2];
    private final double[] aiTarget = {MID, MID};
    private final double[] aiTimer = new double[2];
    private final double[] aiOffset = new double[2];

    // Cosmetic randomness never changes the course of a match.
    private final Random gameRandom = new Random(0x504F4E47L);
    private final Random effectsRandom = new Random(0x4455454CL);
    private final ArrayList<Particle> particles = new ArrayList<>(220);
    private final ArrayList<Ring> rings = new ArrayList<>(20);
    private final double[] trailX = new double[TRAIL];
    private final double[] trailY = new double[TRAIL];
    private int trailHead;

    private final BufferedImage background =
            new BufferedImage(W / 2, H / 2, BufferedImage.TYPE_INT_RGB);
    private final BufferedImage world =
            new BufferedImage(W / 2, H / 2, BufferedImage.TYPE_INT_RGB);

    private List<Host.Score> leaders = List.of();
    private Mode mode = Mode.SERVE;
    private double clock, age, serveDelay = 1.25, leaderTimer;
    private double bx = W / 2.0, by = MID, vx, vy;
    private double speed = START_SPEED;
    private double shake, pointFlash, bannerTime, hitStop;
    private int serveDirection = 1, rally, bestRally, lastPoint = -1, winner = -1;
    private int pointRally;
    private String banner = "";
    private String pointName = "";

    public PongDuel() {
        makeBackground();
        clearTrail();
    }

    @Override
    public int maxPlayers() {
        return 2;
    }

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        this.host = host;
        if (host == null) return;

        Arrays.fill(players, null);
        List<Seat> seated = host.seats();
        if (seated != null) {
            for (Seat seat : seated) {
                if (valid(seat)) players[seat.index()] = seat;
            }
        }
        refreshLeaders();
        resetMatch();
        if (demo()) launch();
    }

    private static boolean valid(Seat seat) {
        return seat != null && seat.index() >= 0 && seat.index() < 2;
    }

    private int owner(Seat seat) {
        if (!valid(seat)) return -1;
        int side = seat.index();
        return players[side] != null
                && Objects.equals(players[side].id(), seat.id()) ? side : -1;
    }

    private boolean demo() {
        return players[0] == null && players[1] == null;
    }

    @Override
    public void joined(Seat seat) {
        if (!valid(seat) || owner(seat) >= 0) return;
        int side = seat.index();
        players[side] = seat;
        clearInput(side);
        target[side] = MID;
        if (host == null) return;

        // Never hand a newly seated player the CPU's existing score.
        resetMatch();
        sound("block.note_block.chime", 0.55f, side == 0 ? 1f : 1.25f);
    }

    @Override
    public void left(Seat seat) {
        int side = owner(seat);
        if (side < 0) return;
        players[side] = null;
        clearInput(side);
        aiTimer[side] = 0;
        aiTarget[side] = paddle[side];
        if (host == null) return;

        if (demo()) {
            resetMatch();
            launch();
        } else if (mode != Mode.OVER) {
            banner = (side == 0 ? "CYAN" : "PINK") + " CPU TAKES OVER";
            bannerTime = 1.5;
        }
    }

    private void clearInput(int side) {
        up[side] = down[side] = jump[side] = false;
        mouseAge[side] = 10;
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        int side = owner(seat);
        if (host == null || side < 0) return;
        target[side] = clamp(y * (double) H / Math.max(1, host.height()),
                TOP + HALF, BOTTOM - HALF);
        mouseAge[side] = 0;
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (host == null || owner(seat) < 0) return;
        // Click coordinates are not substituted for real pointer motion.
        if (mode == Mode.OVER && age > 1.3) resetMatch();
    }

    @Override
    public void key(Seat seat, Key key, boolean pressed) {
        int side = owner(seat);
        if (host == null || side < 0 || key == null) return;
        switch (key) {
            case UP -> up[side] = pressed;
            case DOWN -> down[side] = pressed;
            case JUMP -> {
                boolean edge = pressed && !jump[side];
                jump[side] = pressed;
                if (edge && mode == Mode.OVER && age > 1.3) resetMatch();
            }
            default -> { }
        }
    }

    private void resetMatch() {
        Arrays.fill(points, 0);
        Arrays.fill(earned, 0);
        Arrays.fill(ranks, 0);
        Arrays.fill(hitFlash, 0);
        Arrays.fill(scorePop, 0);
        Arrays.fill(finalHuman, false);
        Arrays.fill(finalNames, "");
        particles.clear();
        rings.clear();
        bestRally = pointRally = 0;
        lastPoint = winner = -1;
        bannerTime = shake = pointFlash = hitStop = 0;
        banner = pointName = "";

        for (int i = 0; i < 2; i++) {
            // Retain a known mouse target across rematches.
            paddle[i] = players[i] == null ? MID : target[i];
            aiTarget[i] = paddle[i];
            aiTimer[i] = 0;
        }
        serveDirection = gameRandom.nextBoolean() ? 1 : -1;
        prepareServe(1.25);
    }

    private void prepareServe(double delay) {
        mode = Mode.SERVE;
        age = 0;
        serveDelay = delay;
        bx = W / 2.0;
        by = MID;
        vx = vy = 0;
        speed = START_SPEED;
        rally = 0;
        hitStop = 0;
        for (int i = 0; i < 2; i++) {
            aiOffset[i] = (gameRandom.nextDouble() * 2 - 1) * 38;
            aiTimer[i] = 0;
        }
        clearTrail();
    }

    private void launch() {
        double angle = (0.28 + gameRandom.nextDouble() * 0.35)
                * (gameRandom.nextBoolean() ? 1 : -1);
        vx = serveDirection * speed * Math.cos(angle);
        vy = speed * Math.sin(angle);
        mode = Mode.PLAY;
        age = 0;
        clearTrail();
        addRing(bx, by, WHITE, 64);
        sound("block.note_block.bit", 0.4f, 0.7f);
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        age += dt;
        shake = Math.max(0, shake - dt * 22);
        pointFlash = Math.max(0, pointFlash - dt * 2.1);
        bannerTime = Math.max(0, bannerTime - dt);

        for (int i = 0; i < 2; i++) {
            mouseAge[i] += dt;
            hitFlash[i] = Math.max(0, hitFlash[i] - dt * 4.5);
            scorePop[i] = Math.max(0, scorePop[i] - dt * 2.6);
        }
        updateEffects(dt);
        leaderTimer -= dt;
        if (leaderTimer <= 0) {
            refreshLeaders();
            leaderTimer = 3;
        }

        if (mode == Mode.OVER) {
            if (demo() && age > 2.6) {
                resetMatch();
                launch();
            }
            return;
        }

        double frozen = Math.min(dt, hitStop);
        hitStop = Math.max(0, hitStop - dt);
        double ballDt = dt - frozen;

        int steps = Math.max(1, (int) Math.ceil(dt * 200));
        double sub = dt / steps;
        double ballSub = ballDt / steps;
        for (int step = 0; step < steps; step++) {
            movePaddles(sub);
            if (mode == Mode.PLAY && ballSub > 0) {
                moveBall(ballSub);
                if (mode != Mode.PLAY || hitStop > 0) break;
            }
        }

        if (mode == Mode.POINT && age >= 0.85) prepareServe(0.65);
        else if (mode == Mode.SERVE && age >= serveDelay) launch();

        if (mode == Mode.PLAY && ballDt > 0) {
            trailHead = (trailHead + 1) % TRAIL;
            trailX[trailHead] = bx;
            trailY[trailHead] = by;
        }
    }

    private void movePaddles(double dt) {
        double ease = 1 - Math.exp(-32 * dt);
        for (int i = 0; i < 2; i++) {
            double wanted;
            if (players[i] == null) {
                aiTimer[i] -= dt;
                if (aiTimer[i] <= 0) {
                    aiTimer[i] = demo() ? 0.10 : 0.145;
                    boolean incoming = mode == Mode.PLAY && (i == 0 ? vx < 0 : vx > 0);
                    if (incoming) {
                        double face = i == 0 ? LEFT + PW / 2.0 + R : RIGHT - PW / 2.0 - R;
                        double arrival = Math.max(0, (face - bx) / vx);
                        double low = TOP + R;
                        double span = BOTTOM - R - low;
                        double predicted = by + vy * arrival - low;
                        double folded = ((predicted % (2 * span)) + 2 * span) % (2 * span);
                        aiTarget[i] = low + (folded <= span ? folded : 2 * span - folded)
                                + aiOffset[i];
                    } else {
                        aiTarget[i] = MID + Math.sin(clock * 1.35 + i * 2.4) * 28;
                    }
                }
                wanted = clamp(aiTarget[i], TOP + HALF, BOTTOM - HALF);
                double maxMove = (demo() ? 430 : 365) * dt;
                paddle[i] += clamp((wanted - paddle[i]) * (1 - Math.exp(-12 * dt)),
                        -maxMove, maxMove);
            } else {
                // The real mouse always wins over simultaneously held W/S.
                if (mouseAge[i] > 0.20 && up[i] != down[i]) {
                    target[i] += (down[i] ? 1 : -1) * 560 * dt;
                }
                target[i] = clamp(target[i], TOP + HALF, BOTTOM - HALF);
                wanted = target[i];
                paddle[i] += clamp((wanted - paddle[i]) * ease, -2000 * dt, 2000 * dt);
            }
            paddle[i] = clamp(paddle[i], TOP + HALF, BOTTOM - HALF);
        }
    }

    private void moveBall(double dt) {
        double oldX = bx;
        bx += vx * dt;
        by += vy * dt;

        if (by < TOP + R) {
            by = 2 * (TOP + R) - by;
            vy = Math.abs(vy);
            wallHit();
        } else if (by > BOTTOM - R) {
            by = 2 * (BOTTOM - R) - by;
            vy = -Math.abs(vy);
            wallHit();
        }

        double leftFace = LEFT + PW / 2.0 + R;
        double rightFace = RIGHT - PW / 2.0 - R;
        if (vx < 0 && oldX >= leftFace && bx <= leftFace
                && Math.abs(by - paddle[0]) <= HALF + R) {
            paddleHit(0, leftFace);
        } else if (vx > 0 && oldX <= rightFace && bx >= rightFace
                && Math.abs(by - paddle[1]) <= HALF + R) {
            paddleHit(1, rightFace);
        }

        if (bx < -R * 2) point(1);
        else if (bx > W + R * 2) point(0);
    }

    private void paddleHit(int side, double face) {
        bx = face;
        double offset = clamp((by - paddle[side]) / HALF, -1, 1);
        double angle = offset * Math.toRadians(61);
        speed = Math.min(MAX_SPEED, speed + 31);
        vx = (side == 0 ? 1 : -1) * speed * Math.cos(angle);
        vy = speed * Math.sin(angle);

        rally++;
        bestRally = Math.max(bestRally, rally);
        if (players[side] != null) earned[side] += 5;
        hitFlash[side] = 1;
        hitStop = 0.018;
        aiOffset[1 - side] = (gameRandom.nextDouble() * 2 - 1) * (demo() ? 49 : 43);
        aiTimer[1 - side] = Math.min(aiTimer[1 - side], 0.08);

        burst(bx, by, 17, COLORS[side], 225);
        addRing(bx, by, COLORS[side], 62);
        sound("block.note_block.bit", 0.65f,
                (float) Math.min(2, 0.7 * Math.pow(2, rally / 12.0)));

        if (rally == 5 || rally == 10 || rally == 20) {
            banner = rally >= 10 ? "REDLINE!  " + rally + " RETURNS" : "RALLY ON!  " + rally;
            bannerTime = 1.1;
        }
    }

    private void wallHit() {
        burst(bx, by, 6, WHITE, 120);
        sound("block.note_block.hat", 0.19f, 1.2f);
    }

    private void point(int side) {
        points[side]++;
        if (players[side] != null) earned[side] += 100;
        lastPoint = side;
        pointName = name(side);
        pointRally = rally;
        scorePop[side] = 1;
        shake = 9;
        pointFlash = 1;
        bannerTime = hitStop = 0;
        vx = vy = 0;
        age = 0;

        double x = side == 0 ? W - 6 : 6;
        double y = clamp(by, TOP + 8, BOTTOM - 8);
        burst(x, y, 65, COLORS[side], 490);
        addRing(x, y, COLORS[side], 205);
        sound("block.note_block.basedrum", 0.85f, 0.7f);

        if (points[side] < WIN) {
            mode = Mode.POINT;
            // Serve toward the player who conceded the point.
            serveDirection = side == 0 ? 1 : -1;
            return;
        }

        mode = Mode.OVER;
        winner = side;
        if (players[side] != null) earned[side] += 300;
        for (int i = 0; i < 2; i++) {
            finalNames[i] = name(i);
            finalHuman[i] = players[i] != null;
            if (players[i] != null && host != null) {
                ranks[i] = host.score(players[i], earned[i]);
            }
        }
        refreshLeaders();
        burst(W / 2.0, MID - 35, 65, GOLD, 350);
        sound("entity.player.levelup", 0.65f, 1.15f);
    }

    private void updateEffects(double dt) {
        double drag = Math.exp(-2.4 * dt);
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vx *= drag;
                p.vy *= drag;
            }
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring ring = rings.get(i);
            ring.life -= dt;
            if (ring.life <= 0) rings.remove(i);
        }
    }

    private void burst(double x, double y, int count, Color color, double force) {
        for (int i = 0; i < count && particles.size() < 220; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double angle = effectsRandom.nextDouble() * Math.PI * 2;
            double v = force * (0.2 + effectsRandom.nextDouble() * 0.8);
            p.vx = Math.cos(angle) * v;
            p.vy = Math.sin(angle) * v;
            p.max = p.life = 0.3 + effectsRandom.nextDouble() * 0.5;
            p.size = 2 + effectsRandom.nextInt(4);
            p.color = color;
            particles.add(p);
        }
    }

    private void addRing(double x, double y, Color color, double radius) {
        if (rings.size() >= 20) rings.remove(0);
        Ring ring = new Ring();
        ring.x = x;
        ring.y = y;
        ring.radius = radius;
        ring.life = 0.5;
        ring.color = color;
        rings.add(ring);
    }

    private void clearTrail() {
        Arrays.fill(trailX, bx);
        Arrays.fill(trailY, by);
        trailHead = 0;
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && !demo()) host.sound(id, volume, pitch);
    }

    private void refreshLeaders() {
        if (host == null) return;
        List<Host.Score> scores = host.scores();
        if (scores == null || scores.isEmpty()) {
            leaders = List.of();
            return;
        }
        ArrayList<Host.Score> top = new ArrayList<>(3);
        for (Host.Score score : scores) {
            if (score != null) top.add(score);
            if (top.size() == 3) break;
        }
        leaders = List.copyOf(top);
    }

    private void makeBackground() {
        Graphics2D g = background.createGraphics();
        g.scale(0.5, 0.5);
        g.setPaint(new GradientPaint(0, 0, NAVY, W, H, new Color(0x101A2C)));
        g.fillRect(0, 0, W, H);

        g.setColor(new Color(0x162237));
        for (int x = 32; x < W; x += 96) g.fillRect(x, TOP, 1, BOTTOM - TOP);
        for (int y = TOP + 48; y < BOTTOM; y += 96) g.fillRect(0, y, W, 1);

        // Restrained scanlines, not the dominant texture of the court.
        g.setColor(new Color(0, 0, 0, 13));
        for (int y = TOP; y < BOTTOM; y += 4) g.fillRect(0, y, W, 1);

        g.setColor(alpha(CYAN, 9));
        g.fillRect(0, TOP, 74, BOTTOM - TOP);
        g.setColor(alpha(PINK, 9));
        g.fillRect(W - 74, TOP, 74, BOTTOM - TOP);
        g.dispose();
    }

    @Override
    public void draw(Graphics2D output) {
        if (output == null) return;

        Graphics2D wg = world.createGraphics();
        wg.drawImage(background, 0, 0, null);
        wg.scale(0.5, 0.5);
        wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        wg.clipRect(0, TOP - 7, W, BOTTOM - TOP + 14);
        wg.translate(Math.sin(clock * 97) * shake,
                Math.cos(clock * 83) * shake * 0.55);
        drawCourt(wg);
        wg.dispose();

        int width = host == null ? cols() * 128 : Math.max(1, host.width());
        int height = host == null ? rows() * 128 : Math.max(1, host.height());
        Graphics2D g = (Graphics2D) output.create();
        Pixels.drawSmooth(g, world, 0, 0, width, height);

        // These glyphs are rasterized at the actual cabinet resolution.
        g.scale(width / (double) W, height / (double) H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawHud(g);

        if (mode == Mode.OVER) drawOver(g);
        else if (mode == Mode.POINT) drawPoint(g);
        else if (mode == Mode.SERVE && !demo()) drawServe(g);
        else if (!demo() && bannerTime > 0) {
            text(g, banner, 24, 512, 153, GOLD, 0);
        }

        if (demo() && mode != Mode.OVER) drawAttract(g);
        g.dispose();
    }

    private void drawCourt(Graphics2D g) {
        g.setColor(new Color(0x34445C));
        for (int y = TOP + 14; y < BOTTOM; y += 30) {
            g.fillRoundRect(510, y, 4, 15, 3, 3);
        }

        // A very quiet centre circle gives the open court a deliberate composition.
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(0x1B2A41));
        g.drawOval(444, (int) MID - 68, 136, 136);

        for (int side = 0; side < 2; side++) {
            Color color = COLORS[side];
            int startX = side == 0 ? 0 : W / 2;
            g.setColor(alpha(color, 22));
            g.fillRect(startX, TOP - 4, W / 2, 8);
            g.fillRect(startX, BOTTOM - 4, W / 2, 8);
            g.setColor(alpha(color, 165));
            g.fillRect(startX, TOP, W / 2, 2);
            g.fillRect(startX, BOTTOM, W / 2, 2);

            if (pointFlash > 0 && lastPoint == side) {
                g.setColor(alpha(color, (int) (pointFlash * 46)));
                g.fillRect(startX, TOP, W / 2, BOTTOM - TOP);
                int goalX = side == 0 ? W - 12 : 0;
                g.setColor(alpha(color, (int) (pointFlash * 225)));
                g.fillRect(goalX, TOP, 12, BOTTOM - TOP);
            }

            int x = side == 0 ? LEFT : RIGHT;
            int y = (int) Math.round(paddle[side]);
            double flash = hitFlash[side];
            for (int k = 4; k >= 1; k--) {
                int spread = k * 4;
                g.setColor(alpha(color, 12 + (int) (flash * 19)));
                g.fillRoundRect(x - PW / 2 - spread, y - HALF - spread,
                        PW + 2 * spread, 2 * HALF + 2 * spread, 15, 15);
            }

            g.setColor(flash > 0.45 ? WHITE : color);
            g.fillRoundRect(x - PW / 2, y - HALF, PW, HALF * 2, 7, 7);
            g.setColor(WHITE);
            g.fillRoundRect(x - 2, y - HALF + 5, 3, HALF * 2 - 10, 3, 3);
            g.setColor(alpha(NAVY, 145));
            for (int mark = -1; mark <= 1; mark++) {
                g.fillRect(x - PW / 2, y + mark * 28 - 1, PW, 2);
            }

            // Tip accents make the high-angle portions of the paddle readable.
            g.setColor(WHITE);
            g.fillRect(x - PW / 2, y - HALF + 3, PW, 3);
            g.fillRect(x - PW / 2, y + HALF - 6, PW, 3);
        }

        g.setStroke(new BasicStroke(2));
        for (Ring ring : rings) {
            double progress = clamp(1 - ring.life / 0.5, 0, 1);
            double radius = 8 + ring.radius * progress;
            g.setColor(alpha(ring.color, (int) (150 * (1 - progress))));
            g.drawOval((int) (ring.x - radius), (int) (ring.y - radius),
                    (int) (radius * 2), (int) (radius * 2));
        }

        for (Particle p : particles) {
            g.setColor(alpha(p.color, (int) (240 * p.life / p.max)));
            g.fillRect((int) p.x - p.size / 2, (int) p.y - p.size / 2, p.size, p.size);
        }

        if (mode == Mode.OVER || mode == Mode.POINT) return;
        Color ballColor = mode == Mode.SERVE ? WHITE : vx < 0 ? PINK : CYAN;

        if (mode == Mode.PLAY) {
            for (int i = TRAIL - 1; i > 0; i--) {
                int a = Math.floorMod(trailHead - i, TRAIL);
                int b = Math.floorMod(trailHead - i + 1, TRAIL);
                double strength = 1 - i / (double) TRAIL;
                g.setStroke(new BasicStroke((float) (3 + strength * 13),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(alpha(ballColor, (int) (135 * strength * strength)));
                g.drawLine((int) trailX[a], (int) trailY[a],
                        (int) trailX[b], (int) trailY[b]);
            }
        } else {
            double progress = clamp(age / serveDelay, 0, 1);
            int radius = (int) (37 - 23 * progress);
            g.setColor(alpha(WHITE, 130));
            g.setStroke(new BasicStroke(2));
            g.drawOval(512 - radius, (int) MID - radius, radius * 2, radius * 2);

            Color directionColor = COLORS[serveDirection > 0 ? 1 : 0];
            for (int i = 0; i < 3; i++) {
                int x = 512 + serveDirection * (55 + i * 22);
                int a = (int) (80 + 80 * (0.5 + 0.5 * Math.sin(clock * 8 - i)));
                g.setColor(alpha(directionColor, a));
                g.drawLine(x - serveDirection * 5, (int) MID - 7,
                        x + serveDirection * 3, (int) MID);
                g.drawLine(x + serveDirection * 3, (int) MID,
                        x - serveDirection * 5, (int) MID + 7);
            }
        }

        for (int i = 4; i >= 1; i--) {
            g.setColor(alpha(ballColor, 20));
            circle(g, bx, by, R + i * 4);
        }
        g.setColor(ballColor);
        circle(g, bx, by, R + 2);
        g.setColor(WHITE);
        circle(g, bx, by, R - 1);
        g.setColor(Color.WHITE);
        circle(g, bx - 2, by - 2, 3);
    }

    private void drawHud(Graphics2D g) {
        g.setColor(NAVY);
        g.fillRect(0, 0, W, TOP - 7);
        g.fillRect(0, BOTTOM + 7, W, H - BOTTOM - 7);

        text(g, "PONG", 22, 506, 28, CYAN, 1);
        text(g, "DUEL", 22, 516, 28, PINK, -1);
        text(g, ":", 38, 512, 78, MUTED, 0);

        for (int side = 0; side < 2; side++) {
            int x = side == 0 ? 28 : W - 28;
            int align = side == 0 ? -1 : 1;
            String label = players[side] == null ? "COMPUTER" : "PLAYER " + (side + 1);
            String displayName = mode == Mode.OVER ? finalNames[side] : name(side);
            if (mode == Mode.OVER) label = finalHuman[side] ? "PLAYER " + (side + 1) : "COMPUTER";
            text(g, label, 16, x, 27, COLORS[side], align);
            fitted(g, displayName, 27, x, 60, 305, WHITE, align);
            text(g, players[side] == null ? "PREDICTIVE AI" : "MOUSE UP / DOWN  ·  W / S",
                    17, x, 88, MUTED, align);

            int scoreX = side == 0 ? 446 : 578;
            int size = 75 + (int) (scorePop[side] * 10);
            text(g, Integer.toString(points[side]), size, scoreX, 92, COLORS[side], 0);

            int first = side == 0 ? 390 : 545;
            for (int p = 0; p < WIN; p++) {
                g.setColor(p < points[side] ? COLORS[side] : new Color(0x26364D));
                g.fillRoundRect(first + p * 13, 99, 9, 4, 3, 3);
            }
        }

        if (demo()) return;

        boolean matchPoint = points[0] == WIN - 1 || points[1] == WIN - 1;
        text(g, "TIP HITS ADD SPIN", 19, 28, 533, MUTED, -1);
        text(g, matchPoint ? "MATCH POINT" : "FIRST TO SEVEN",
                21, 512, 533, matchPoint ? GOLD : WHITE, 0);
        text(g, "RALLY " + rally + "  /  BEST " + bestRally,
                19, W - 28, 533, rally >= 5 ? GOLD : MUTED, 1);

        text(g, "PACE", 16, 412, 560, MUTED, -1);
        int bars = 1 + (int) Math.round(11 * clamp(
                (speed - START_SPEED) / (MAX_SPEED - START_SPEED), 0, 1));
        for (int i = 0; i < 12; i++) {
            g.setColor(i < bars ? (bars >= 9 ? GOLD : CYAN) : new Color(0x26364D));
            g.fillRoundRect(467 + i * 13, 548, 9, 12, 3, 3);
        }
    }

    private void drawAttract(Graphics2D g) {
        // The title lives in the header; the court is never covered by a card.
        // Fade the additional headline before it could conceal an approaching ball.
        double near = mode == Mode.PLAY
                ? Math.max(Math.abs(bx - 512) / 315, Math.abs(by - 162) / 65) : 3;
        int opacity = (int) (190 * clamp((near - 1) * 2, 0, 1));
        if (mode == Mode.PLAY && opacity > 0) {
            text(g, "THE NEON DUEL", 37, 512, 170, alpha(WHITE, opacity), 0);
            text(g, "READ THE ANGLE. OWN THE RALLY.", 18, 512, 197,
                    alpha(MUTED, opacity), 0);
        }

        Color invite = clock % 1.6 < 1.15 ? GOLD : WHITE;
        text(g, "TAKE A SEAT TO PLAY", 27, 512, 535, invite, 0);
        text(g, "LIVE AI DUEL", 18, 28, 535, CYAN, -1);
        text(g, "1–2 PLAYERS", 18, W - 28, 535, PINK, 1);

        if (!leaders.isEmpty() && clock % 12 >= 5) {
            int index = ((int) (clock / 3)) % leaders.size();
            Host.Score score = leaders.get(index);
            fitted(g, "TOP DUELISTS   #" + (index + 1) + "  "
                            + shortName(score.name(), 23) + "   " + score.score(),
                    19, 512, 564, 920, MUTED, 0);
        } else {
            text(g, "Move your mouse to defend  ·  Paddle tips add spin  ·  First to 7",
                    19, 512, 564, MUTED, 0);
        }
    }

    private void drawServe(Graphics2D g) {
        boolean fresh = lastPoint < 0;
        text(g, fresh ? "GET READY" : "NEXT SERVE", fresh ? 37 : 28,
                512, 223, WHITE, 0);
        text(g, fresh ? "Move your mouse to meet the ball" : "First to seven wins",
                21, 512, 255, MUTED, 0);

        double progress = clamp(age / serveDelay, 0, 1);
        g.setColor(new Color(0x283A52));
        g.fillRoundRect(424, 365, 176, 6, 6, 6);
        g.setColor(COLORS[serveDirection > 0 ? 1 : 0]);
        g.fillRoundRect(424, 365, (int) (176 * progress), 6, 6, 6);
    }

    private void drawPoint(Graphics2D g) {
        double entrance = 1 - Math.pow(1 - clamp(age / 0.18, 0, 1), 3);
        int lift = (int) ((1 - entrance) * 18);
        Color color = lastPoint < 0 ? WHITE : COLORS[lastPoint];
        text(g, "POINT", 62, 512, 270 + lift, color, 0);
        fitted(g, pointName, 28, 512, 312 + lift, 680, WHITE, 0);
        text(g, points[0] + "  :  " + points[1], 31, 512, 352 + lift, WHITE, 0);
        if (pointRally >= 3) {
            text(g, pointRally + "-RETURN RALLY", 20, 512, 389, GOLD, 0);
        }
    }

    private void drawOver(Graphics2D g) {
        panel(g, 186, 151, 652, 330);
        text(g, "MATCH COMPLETE", 19, 512, 185, MUTED, 0);
        String winName = winner < 0 ? "DUEL" : finalNames[winner];
        fitted(g, winName + " WINS!", 40, 512, 235, 590,
                winner < 0 ? WHITE : COLORS[winner], 0);
        text(g, points[0] + "  —  " + points[1], 62, 512, 301, WHITE, 0);

        for (int i = 0; i < 2; i++) {
            int x = i == 0 ? 353 : 671;
            fitted(g, finalNames[i], 20, x, 335, 278, COLORS[i], 0);
            text(g, finalHuman[i] ? earned[i] + " POINTS" : "COMPUTER",
                    25, x, 368, WHITE, 0);
            if (finalHuman[i] && ranks[i] > 0) {
                text(g, "HIGH SCORE #" + ranks[i], 18, x, 395, GOLD, 0);
            }
        }

        text(g, "100 / point  ·  5 / return  ·  300 / win", 18, 512, 427, MUTED, 0);
        if (demo()) {
            text(g, "TAKE A SEAT TO CHALLENGE", 23, 512, 461, GOLD, 0);
        } else if (age > 1.3) {
            text(g, "CLICK OR SPACE FOR A REMATCH", 23, 512, 461,
                    clock % 1.5 < 1.1 ? WHITE : CYAN, 0);
        }
    }

    private String name(int side) {
        if (side < 0 || side > 1) return "";
        return players[side] == null ? (side == 0 ? "CYAN CPU" : "PINK CPU")
                : shortName(players[side].name(), 24);
    }

    private static String shortName(String value, int limit) {
        if (value == null || value.isBlank()) return "PLAYER";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static void fitted(Graphics2D g, String value, int size,
                               double x, double y, int maxWidth, Color color, int align) {
        if (value == null) value = "";
        size = Math.max(1, Math.min(FONTS.length - 1, size));
        g.setFont(FONTS[size]);
        while (size > 17 && g.getFontMetrics().stringWidth(value) > maxWidth) {
            g.setFont(FONTS[--size]);
        }
        if (g.getFontMetrics().stringWidth(value) > maxWidth) {
            while (!value.isEmpty()
                    && g.getFontMetrics().stringWidth(value + "…") > maxWidth) {
                value = value.substring(0, value.length() - 1);
            }
            value += "…";
        }
        text(g, value, size, x, y, color, align);
    }

    private static void text(Graphics2D g, String value, int size,
                             double x, double y, Color color, int align) {
        if (value == null || value.isEmpty() || color.getAlpha() == 0) return;
        g.setFont(FONTS[Math.max(1, Math.min(FONTS.length - 1, size))]);
        int width = g.getFontMetrics().stringWidth(value);
        float left = (float) (align < 0 ? x : align > 0 ? x - width : x - width / 2.0);
        g.setColor(alpha(NAVY, color.getAlpha()));
        g.drawString(value, left, (float) y + 2);
        g.setColor(color);
        g.drawString(value, left, (float) y);
    }

    private static void panel(Graphics2D g, int x, int y, int width, int height) {
        g.setColor(new Color(0, 4, 14, 170));
        g.fillRoundRect(x - 7, y + 7, width + 14, height + 6, 24, 24);
        g.setColor(new Color(0x101B2D));
        g.fillRoundRect(x, y, width, height, 18, 18);
        g.setPaint(new GradientPaint(x, y, CYAN, x + width, y, PINK));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, width, height, 18, 18);
        g.setStroke(new BasicStroke(1));
    }

    private static void circle(Graphics2D g, double x, double y, double radius) {
        int diameter = Math.max(2, (int) Math.round(radius * 2));
        g.fillOval((int) Math.round(x - radius), (int) Math.round(y - radius),
                diameter, diameter);
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, alpha)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Particle {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
    }

    private static final class Ring {
        double x, y, radius, life;
        Color color;
    }
}

package ai.resourcepack.arcade.games;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Elytra Glide — a one-button, pixel-art firework flyer.
 *
 * Review of the supplied frames and report:
 * - No exceptions or performance-budget failures were reported.
 * - Three separate zero-score runs suggest an unforgiving opening, not necessarily
 *   duplicate score submission. The old -100 boost / 248 gravity tuning climbs
 *   steadily with 2/3-second taps; additional space presses make that worse.
 * - The first point took too long to reach, so players repeatedly crashed before
 *   receiving any positive feedback.
 * - A lethal ceiling immediately below the HUD made overboosting especially harsh.
 * - The mountain layers repeat the same steep tooth shape and compete with the
 *   pillars. The scenery needs larger, quieter, more varied silhouettes.
 * - Attract mode is active and the main text is readable, but its large panels
 *   obscure too much of the demonstration.
 * - Frequent retry input can make the crash/result screen almost disappear.
 *   A visible crash reason and a deliberate retry delay make failures clearer.
 *
 * Changes include cadence-balanced lift, merged near-simultaneous boost inputs,
 * generous introductory gates, an earlier first point, a nonlethal open skyline,
 * clearer obstacle outlines, varied scenery, smaller demo panels, gate progress,
 * stronger pass feedback, and a readable, locked-in result presentation.
 */
public final class ElytraGlide implements Scene {
    private static final int W = 480, H = 270;
    private static final int SKYLINE = 39, FLOOR = 250;
    private static final double PX = 120;
    private static final int PW = 32;
    private static final double GRAVITY = 210, LIFT = -72;
    private static final double COOLDOWN = 0.28;
    private static final double LEFT = -6, RIGHT = 10, TOP = -5, BOTTOM = 5;

    private static final Color WHITE = new Color(0xF5FAEF);
    private static final Color GOLD = new Color(0xFFE29A);
    private static final Color MINT = new Color(0x91F4D4);
    private static final Color INK = new Color(0x152A3B);
    private static final Color CORAL = new Color(0xFF927D);
    private static final Color[] SKIES = {
            new Color(0x79B9CE), new Color(0x463E68), new Color(0x303A50)
    };
    private static final Color[] HAZES = {
            new Color(0xDFEACB), new Color(0xCE9CAD), new Color(0xBFC3A3)
    };
    private static final Color[] STONES = {
            new Color(0x819598), new Color(0x463752), new Color(0xDDD4A1)
    };
    private static final Color[] SEAMS = {
            new Color(0x3C5662), new Color(0x211F32), new Color(0x93966E)
    };
    private static final Color[] EDGES = {
            new Color(0xC0CBC5), new Color(0x9982B1), new Color(0xFFF0C3)
    };
    private static final String[] REGIONS = {
            "STONE RIDGE", "OBSIDIAN REACH", "THE END"
    };
    private static final Font[] FONTS = new Font[49];

    static {
        for (int i = 1; i < FONTS.length; i++) {
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
        }
    }

    private enum Mode { READY, FLYING, CRASH, OVER }

    private Host host;
    private Seat player;
    private Mode mode = Mode.READY;
    private final BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private final Random courseRandom = new Random(17);
    private final Random effectsRandom = new Random(0xE17A);
    private final Random demoRandom = new Random(91);
    private final List<Gate> gates = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private List<Host.Score> leaders = List.of();

    private double clock, age, scenery, y = 149, vy;
    private double cooldown, flame, flash, shake, trailTimer;
    private double passTime, passY, banner, demoErrorIn, demoError;
    private double scoresIn, crashY, crashRotation;
    private int score, rank, spawned, bestAtStart, runNumber;
    private boolean spaceHeld;
    private String crashReason = "";
    private double nextCenter;

    public ElytraGlide() {}

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        player = host.seats().isEmpty() ? null : host.seats().get(0);
        refreshScores();
        spaceHeld = false;
        reset();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || player != null) return;
        player = seat;
        spaceHeld = false;
        reset();
        sound("block.note_block.chime", 0.55f, 1.3f);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        player = null;
        spaceHeld = false;
        reset();
    }

    private boolean owns(Seat seat) {
        return host != null && player != null && seat != null
                && Objects.equals(player.id(), seat.id());
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (owns(seat) && !right) press();
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (!owns(seat) || key != Key.JUMP) return;
        boolean pressed = down && !spaceHeld;
        spaceHeld = down;
        if (pressed) press();
    }

    private void press() {
        if (mode == Mode.READY) {
            launch();
        } else if (mode == Mode.FLYING) {
            boost();
        } else if (mode == Mode.OVER && age >= 1.15) {
            reset();
            launch();
        }
    }

    private void reset() {
        mode = Mode.READY;
        age = 0;
        y = 149;
        vy = 0;
        score = rank = spawned = 0;
        cooldown = flame = flash = shake = trailTimer = 0;
        passTime = banner = crashRotation = demoError = 0;
        crashReason = "";
        gates.clear();
        sparks.clear();
        bestAtStart = leaders.isEmpty() ? 0 : leaders.get(0).score();
        demoErrorIn = 13 + demoRandom.nextDouble() * 9;
        // Human openings are reproducible and learnable. Demo routes vary.
        courseRandom.setSeed(player == null ? 900L + runNumber++ : 7319L);
        nextCenter = 149;
        addGate(255);
        addGate(420);
        addGate(585);
    }

    private void launch() {
        if (mode != Mode.READY) return;
        mode = Mode.FLYING;
        age = 0;
        boost();
    }

    private void boost() {
        if (mode != Mode.FLYING || cooldown > 0) return;
        // Click and space are the same button, never additive impulses.
        vy = LIFT;
        cooldown = COOLDOWN;
        flame = 0.3;
        burst(PX - 10, y + 4, 16, GOLD, 52, false);
        sound("entity.firework_rocket.launch", 0.38f,
                1.12f + effectsRandom.nextFloat() * 0.12f);
    }

    private double speed() {
        return Math.min(116, 83 + score * 1.05);
    }

    private static int material(int number) {
        return number < 10 ? 0 : number < 22 ? 1 : 2;
    }

    private void addGate(double x) {
        Gate gate = new Gate();
        gate.x = x;
        gate.number = spawned++;
        gate.material = material(gate.number);
        gate.gap = gate.number < 3 ? 134 - gate.number * 3
                : Math.max(86, 121 - (gate.number - 3) * 1.25);
        if (gate.number > 0) {
            double variation = gate.number < 3 ? 13 : 25;
            nextCenter += (courseRandom.nextDouble() * 2 - 1) * variation;
        }
        nextCenter = clamp(nextCenter, SKYLINE + gate.gap / 2 + 12,
                FLOOR - gate.gap / 2 - 14);
        gate.center = nextCenter;
        gates.add(gate);
    }

    private Gate nextGate() {
        for (Gate gate : gates) {
            if (!gate.passed) return gate;
        }
        return null;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        age += dt;
        cooldown = Math.max(0, cooldown - dt);
        flame = Math.max(0, flame - dt);
        flash = Math.max(0, flash - dt);
        shake = Math.max(0, shake - 12 * dt);
        passTime = Math.max(0, passTime - dt);
        banner = Math.max(0, banner - dt);
        scoresIn -= dt;
        if (scoresIn <= 0) refreshScores();

        switch (mode) {
            case READY -> {
                scenery += dt * 18;
                y = 149 + Math.sin(clock * 3) * 3;
                if (player == null && age > 0.65) launch();
            }
            case FLYING -> {
                if (player == null) autopilot(dt);
                int steps = Math.max(1, (int) Math.ceil(dt / 0.008));
                for (int i = 0; i < steps && mode == Mode.FLYING; i++) {
                    simulate(dt / steps);
                }
                trailTimer -= dt;
                if (mode == Mode.FLYING && trailTimer <= 0) {
                    trailTimer = 0.035;
                    trail();
                }
            }
            case CRASH -> {
                if (age > 0.12) {
                    vy = Math.min(180, vy + GRAVITY * dt);
                    y = Math.min(FLOOR - 7, y + vy * dt);
                    crashRotation = Math.min(1.5, crashRotation + dt * 2.5);
                }
                if (age >= 0.75) {
                    mode = Mode.OVER;
                    age = 0;
                }
            }
            case OVER -> {
                if (player == null && age > 1.3) reset();
            }
        }

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark p = sparks.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                sparks.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += (p.debris ? 110 : 9) * dt;
            }
        }
    }

    private void autopilot(double dt) {
        demoErrorIn -= dt;
        demoError = Math.max(0, demoError - dt);
        if (demoErrorIn <= 0) {
            demoError = 0.85 + demoRandom.nextDouble() * 0.4;
            demoErrorIn = 14 + demoRandom.nextDouble() * 10;
        }
        if (demoError > 0) return;

        Gate gate = nextGate();
        double target = gate == null ? 151 : gate.center + 9;
        if (gate != null && gate.x > PX + 130) {
            target = 151 + (target - 151)
                    * clamp((PX + 240 - gate.x) / 110, 0, 1);
        }
        if (vy >= 0 && y + vy * 0.16 > target) boost();
        if (y > FLOOR - 24 && vy > 0) boost();
    }

    private void simulate(double dt) {
        double travel = speed() * dt;
        scenery += travel;
        vy = Math.min(162, vy + GRAVITY * dt);
        y += vy * dt;
        for (Gate gate : gates) gate.x -= travel;

        // The upper boundary is open sky, not an invisible lethal block.
        if (y + TOP < SKYLINE) {
            y = SKYLINE - TOP;
            vy = Math.max(0, vy);
        }
        if (y + BOTTOM >= FLOOR) {
            crash("LANDED TOO HARD", "Tap a little sooner to stay airborne.");
            return;
        }

        for (Gate gate : gates) {
            double left = gate.x - 2;
            double right = gate.x + PW + 2;
            double top = gate.center - gate.gap / 2;
            double bottom = gate.center + gate.gap / 2;
            if (PX + RIGHT > left && PX + LEFT < right) {
                if (y + TOP < top) {
                    crash("CLIPPED THE UPPER BLOCKS", "Let the elytra coast between fireworks.");
                    return;
                }
                if (y + BOTTOM > bottom) {
                    crash("CLIPPED THE LOWER BLOCKS", "Boost before you reach the opening.");
                    return;
                }
            }
            if (!gate.passed && right < PX + LEFT) {
                gate.passed = true;
                score++;
                passY = y;
                passTime = 0.9;
                burst(PX + 3, y, 24, MINT, 55, false);
                sound("entity.experience_orb.pickup", 0.55f,
                        (float) Math.min(2, 1.05 + (score % 8) * 0.11));
                if (score == 10 || score == 22) {
                    banner = 2.4;
                    sound("entity.player.levelup", 0.6f, 1.3f);
                } else if (score % 5 == 0) {
                    banner = 1.7;
                    sound("block.note_block.chime", 0.45f, 1.7f);
                }
            }
        }
        gates.removeIf(gate -> gate.x + PW < -12);
        if (gates.isEmpty()) {
            addGate(W + 10);
        } else {
            Gate last = gates.get(gates.size() - 1);
            if (last.x < W + 10) addGate(last.x + 165);
        }
    }

    private String crashTip = "";

    private void crash(String reason, String tip) {
        if (mode != Mode.FLYING) return;
        mode = Mode.CRASH;
        age = 0;
        crashY = y;
        crashReason = reason;
        crashTip = tip;
        vy = -32;
        flame = 0;
        flash = 0.16;
        shake = 4.2;
        burst(PX, y, 36, GOLD, 108, true);
        burst(PX, y, 18, EDGES[material(score)], 77, true);
        if (player != null) {
            // A run has exactly one transition into CRASH and one submission.
            rank = host.score(player, score);
            refreshScores();
            sound("entity.generic.explode", 0.45f, 1.5f);
        }
    }

    private void refreshScores() {
        if (host != null) leaders = List.copyOf(host.scores());
        scoresIn = 3;
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && player != null) {
            host.sound(id, volume, (float) clamp(pitch, 0.5, 2));
        }
    }

    private void trail() {
        if (sparks.size() >= 220) return;
        Spark p = new Spark();
        p.x = PX - 10;
        p.y = y + 3 + effectsRandom.nextDouble() * 4;
        p.vx = -70 - effectsRandom.nextDouble() * 20;
        p.vy = 5;
        p.life = p.max = flame > 0 ? 0.55 : 0.35;
        p.size = flame > 0 ? 2 : 1;
        p.color = flame > 0 ? GOLD : WHITE;
        sparks.add(p);
    }

    private void burst(double x, double y, int count, Color color,
                       double speed, boolean debris) {
        for (int i = 0; i < count && sparks.size() < 220; i++) {
            Spark p = new Spark();
            double angle = effectsRandom.nextDouble() * Math.PI * 2;
            double velocity = speed * (0.25 + effectsRandom.nextDouble() * 0.75);
            p.x = x;
            p.y = y;
            p.vx = Math.cos(angle) * velocity - 24;
            p.vy = Math.sin(angle) * velocity;
            p.life = p.max = 0.3 + effectsRandom.nextDouble() * 0.55;
            p.size = effectsRandom.nextBoolean() ? 1 : 2;
            p.color = i % 4 == 0 ? WHITE : color;
            p.debris = debris;
            sparks.add(p);
        }
    }

    @Override
    public void draw(Graphics2D graphics) {
        int width = host == null ? 2048 : Math.max(1, host.width());
        int height = host == null ? 1152 : Math.max(1, host.height());
        Graphics2D p = frame.createGraphics();
        p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        landscape(p);
        AffineTransform base = p.getTransform();
        if (shake > 0) {
            p.translate((int) (Math.sin(clock * 119) * shake),
                    (int) (Math.cos(clock * 91) * shake));
        }

        for (Gate gate : gates) drawGate(p, gate);
        if (player != null && mode == Mode.FLYING && score < 3) {
            drawOpeningGuide(p);
        }
        for (Spark spark : sparks) {
            p.setColor(alpha(spark.color, (int) (255 * spark.life / spark.max)));
            int x = (int) spark.x, yy = (int) spark.y;
            p.fillRect(x, yy, spark.size, spark.size);
            if (!spark.debris && spark.size == 2 && spark.life > 0.38) {
                p.fillRect(x - 2, yy, 6, 1);
                p.fillRect(x, yy - 2, 1, 6);
            }
        }
        if (passTime > 0.35) {
            double progress = 1 - (passTime - 0.35) / 0.55;
            int radius = (int) (11 + progress * 31);
            p.setColor(alpha(MINT, (int) (190 * (1 - progress))));
            p.drawOval((int) PX - radius, (int) passY - radius,
                    radius * 2, radius * 2);
        }
        flyer(p);
        if ((mode == Mode.CRASH || mode == Mode.OVER) && player != null) {
            p.setColor(alpha(CORAL, 170));
            p.drawOval((int) PX - 14, (int) crashY - 14, 28, 28);
            p.drawLine((int) PX - 4, (int) crashY - 4, (int) PX + 4, (int) crashY + 4);
            p.drawLine((int) PX + 4, (int) crashY - 4, (int) PX - 4, (int) crashY + 4);
        }
        p.setTransform(base);
        p.dispose();

        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, 0, 0, width, height, null);
        g.scale(width / (double) W, height / (double) H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (flash > 0) {
            g.setColor(alpha(GOLD, (int) (flash / 0.16 * 140)));
            g.fillRect(0, 0, W, H);
        }
        hud(g);
        overlays(g);
        g.dispose();
    }

    private void landscape(Graphics2D g) {
        int biome = material(score);
        Color sky = SKIES[biome], haze = HAZES[biome];
        g.setColor(sky);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < 11; i++) {
            g.setColor(mix(sky, haze, i / 13.0));
            g.fillRect(0, 72 + i * 18, W, 18);
        }

        g.setColor(new Color(0xFFF0BF));
        g.fillRect(363, 54, 26, 26);
        g.fillRect(358, 59, 36, 16);
        if (biome != 0) {
            g.setColor(sky);
            g.fillRect(354, 49, 25, 24);
            g.setColor(alpha(WHITE, 155));
            for (int i = 0; i < 32; i++) {
                int x = (i * 83 + 27) % W;
                int yy = 40 + (i * 47) % 92;
                g.fillRect(x, yy, 1, 1);
            }
        }

        for (int i = 0; i < 7; i++) {
            int x = (int) wrap(i * 101 - scenery * 0.1 - clock * 1.8, 707) - 80;
            int yy = 49 + hash(i + 12, 73) % 56;
            int wide = 38 + hash(i, 8) % 25;
            g.setColor(mix(sky, WHITE, biome == 0 ? 0.63 : 0.24));
            g.fillRect(x, yy + 7, wide, 9);
            g.fillRect(x + 8, yy + 1, wide / 2, 17);
            g.fillRect(x + wide / 2, yy + 4, wide / 3, 12);
            g.setColor(mix(sky, WHITE, biome == 0 ? 0.85 : 0.38));
            g.fillRect(x + 10, yy + 1, wide / 2 - 4, 3);
        }

        ridge(g, 0.12, 224, 83, 132,
                mix(sky, haze, 0.23), mix(sky, WHITE, 0.39), true, 11);
        ridge(g, 0.27, 258, 87, 154,
                mix(sky, INK, 0.22), mix(sky, haze, 0.16), false, 23);
        ridge(g, 0.51, 283, 62, 113,
                mix(sky, INK, 0.51), mix(sky, INK, 0.36), false, 37);

        for (int i = 0; i < 11; i++) {
            int x = (int) wrap(i * 69 - scenery * 0.65, 759) - 50;
            int yy = 240 + hash(i, 3) % 11;
            int tall = 12 + hash(i, 5) % 10;
            g.setColor(mix(sky, INK, 0.69));
            g.fillRect(x, yy - tall, 3, tall + 6);
            if (biome == 0) {
                g.fillRect(x - 5, yy - tall, 13, 8);
                g.fillRect(x - 2, yy - tall - 5, 7, 8);
            } else {
                g.fillRect(x - 5, yy - tall + 7, 7, 3);
                g.fillRect(x - 6, yy - tall + 1, 3, 8);
                g.fillRect(x + 2, yy - tall + 3, 7, 3);
                g.fillRect(x + 7, yy - tall - 3, 3, 9);
            }
        }

        g.setColor(new Color(0x263D47));
        g.fillRect(0, FLOOR, W, H - FLOOR);
        g.setColor(biome == 0 ? new Color(0xB8D6A5) : EDGES[biome]);
        g.fillRect(0, FLOOR, W, 3);
        for (int i = -1; i < 29; i++) {
            int x = i * 19 - (int) (scenery % 19);
            g.setColor(new Color(0x506570));
            g.fillRect(x, FLOOR + 7, 12, 3);
        }

        g.setColor(alpha(WHITE, 70));
        for (int i = 0; i < 7; i++) {
            int x = (int) wrap(i * 89 - scenery * 1.45 - clock * 16, 623) - 35;
            int yy = 62 + i * 26;
            g.fillRect(x, yy, 8 + i % 3 * 4, 1);
        }
    }

    private void ridge(Graphics2D g, double parallax, int bottom, int amplitude,
                       int span, Color face, Color light, boolean snow, int seed) {
        double scroll = scenery * parallax;
        long first = (long) Math.floor(scroll / span) - 1;
        int count = W / span + 4;
        for (int i = 0; i < count; i++) {
            long index = first + i;
            int h = hash(index, seed);
            int x = (int) (index * span - scroll);
            int summit = bottom - amplitude - h % 29;
            int summitX = x + 35 + h % Math.max(1, span - 80);
            int plateau = 15 + h % 27;
            int shoulder = summit + 24 + h % 16;
            g.setColor(face);
            g.fillPolygon(
                    new int[]{x - 16, x + 9, x + 9, summitX - 14,
                            summitX - 14, summitX, summitX,
                            summitX + plateau, summitX + plateau,
                            x + span - 16, x + span + 28, x + span + 28, x - 16},
                    new int[]{bottom, bottom - 24, shoulder + 17, shoulder + 17,
                            summit + 12, summit + 12, summit,
                            summit, summit + 24, bottom - 29,
                            bottom - 4, H + 20, H + 20}, 13);
            g.setColor(light);
            g.fillPolygon(new int[]{summitX + plateau - 5, summitX + plateau,
                            summitX + plateau, x + span - 16, x + span + 7},
                    new int[]{summit, summit, summit + 24, bottom - 29, bottom}, 5);
            if (snow) {
                g.setColor(mix(light, WHITE, 0.65));
                g.fillRect(summitX, summit, plateau, 4);
                g.fillRect(summitX - 14, summit + 12, 14, 3);
            }
        }
    }

    private void drawGate(Graphics2D g, Gate gate) {
        int x = (int) Math.round(gate.x);
        if (x > W + 4 || x + PW < -4) return;
        int top = (int) Math.round(gate.center - gate.gap / 2);
        int bottom = (int) Math.round(gate.center + gate.gap / 2);
        pillar(g, x, 30, top - 30, gate, true);
        pillar(g, x, bottom, FLOOR - bottom, gate, false);

        Color marker = gate.passed ? MINT : GOLD;
        g.setColor(marker);
        g.fillRect(x + 5, top - 2, 6, 2);
        g.fillRect(x + PW - 11, top - 2, 6, 2);
        g.fillRect(x + 5, bottom, 6, 2);
        g.fillRect(x + PW - 11, bottom, 6, 2);
        if (!gate.passed) {
            int center = (int) gate.center;
            g.setColor(alpha(GOLD, 125));
            g.drawLine(x + 12, center - 4, x + 17, center);
            g.drawLine(x + 17, center, x + 12, center + 4);
            g.drawLine(x + 18, center - 4, x + 23, center);
            g.drawLine(x + 23, center, x + 18, center + 4);
        }
    }

    private void pillar(Graphics2D g, int x, int yy, int height,
                        Gate gate, boolean upper) {
        if (height <= 0) return;
        Color base = STONES[gate.material];
        Color seam = SEAMS[gate.material];
        Color light = EDGES[gate.material];

        g.setColor(alpha(INK, 85));
        g.fillRect(x + 4, yy + 3, PW + 3, height);
        g.setColor(INK);
        g.fillRect(x - 1, yy, PW + 2, height);

        Shape oldClip = g.getClip();
        g.clipRect(x, yy, PW, height);
        g.setColor(base);
        g.fillRect(x, yy, PW, height);
        for (int row = yy / 9; row <= (yy + height) / 9; row++) {
            if (gate.material == 0) {
                g.setColor(seam);
                g.fillRect(x, row * 9, PW, 1);
                for (int col = -1; col < 3; col++) {
                    int bx = x + col * 16 + (row & 1) * 8;
                    g.setColor(seam);
                    g.fillRect(bx, row * 9, 1, 9);
                    g.setColor(light);
                    g.fillRect(bx + 2, row * 9 + 2, 11, 1);
                }
                if ((row + gate.number) % 7 == 0) {
                    g.setColor(new Color(0x617F72));
                    g.fillRect(x + 4, row * 9 + 5, 7, 3);
                }
            } else {
                for (int col = 0; col < 4; col++) {
                    int h = hash(row * 4L + col, gate.number + 41);
                    g.setColor(h % 3 == 0 ? light : seam);
                    g.fillRect(x + col * 8 + h % 3, row * 9 + h % 5,
                            3 + h % 4, 2 + h % 3);
                }
            }
        }
        g.setColor(alpha(WHITE, 68));
        g.fillRect(x + 1, yy, 2, height);
        g.setColor(alpha(INK, 85));
        g.fillRect(x + PW - 5, yy, 5, height);
        g.setClip(oldClip);

        int capY = upper ? yy + height - 5 : yy;
        g.setColor(INK);
        g.fillRect(x - 2, capY, PW + 4, 5);
        g.setColor(seam);
        g.fillRect(x - 1, capY + 1, PW + 2, 3);
        g.setColor(light);
        g.fillRect(x - 2, upper ? capY + 3 : capY, PW + 4, 2);
    }

    private void drawOpeningGuide(Graphics2D g) {
        Gate next = nextGate();
        if (next == null || next.x < PX + 25 || next.x > PX + 155) return;
        int x = (int) next.x - 10;
        int top = (int) (next.center - next.gap / 2 + 11);
        int bottom = (int) (next.center + next.gap / 2 - 11);
        g.setColor(alpha(MINT, 150));
        for (int yy = top; yy < bottom; yy += 8) g.fillRect(x, yy, 1, 3);
        g.drawLine(x, top, x + 5, top);
        g.drawLine(x, bottom, x + 5, bottom);
    }

    private void flyer(Graphics2D g) {
        AffineTransform old = g.getTransform();
        g.translate(PX, y);
        boolean wreck = mode == Mode.CRASH || mode == Mode.OVER;
        g.rotate(wreck ? crashRotation : clamp(vy / 300, -0.26, 0.48));
        if (flame > 0 && mode == Mode.FLYING) {
            int length = 22 + (int) (Math.sin(clock * 83) * 4);
            g.setColor(CORAL);
            g.fillPolygon(new int[]{-8, -length - 7, -13},
                    new int[]{2, 10, 11}, 3);
            g.setColor(GOLD);
            g.fillPolygon(new int[]{-8, -length, -12},
                    new int[]{3, 8, 9}, 3);
            g.setColor(WHITE);
            g.fillRect(-15, 4, 7, 3);
        }

        int flutter = mode == Mode.READY ? (int) (Math.sin(clock * 5) * 2) : 0;
        g.setColor(new Color(0x23344D));
        g.fillPolygon(new int[]{-1, -20, -13, 4},
                new int[]{-3, -15 - flutter, 1, 5}, 4);
        g.fillPolygon(new int[]{-1, -20, -11, 5},
                new int[]{0, 15 + flutter, 13, 1}, 4);
        g.setColor(new Color(0xB5D6DC));
        g.fillPolygon(new int[]{-2, -16, -11, 1},
                new int[]{-3, -11 - flutter, 0, 3}, 4);
        g.setColor(new Color(0x69B3BB));
        g.fillPolygon(new int[]{-1, -16, -10, 2},
                new int[]{2, 12 + flutter, 10, 2}, 4);
        g.setColor(WHITE);
        g.drawLine(-15, -11 - flutter, -3, 0);
        g.drawLine(-15, 11 + flutter, -2, 3);

        g.setColor(new Color(0x23344E));
        g.fillRect(-7, 2, 10, 5);
        g.setColor(new Color(0x268D9B));
        g.fillRect(-4, -2, 10, 7);
        g.setColor(new Color(0x593D35));
        g.fillRect(3, -7, 10, 9);
        g.setColor(new Color(0xEDC49B));
        g.fillRect(5, -5, 8, 7);
        g.setColor(new Color(0x684636));
        g.fillRect(4, -7, 9, 3);
        g.setColor(INK);
        g.fillRect(10, -3, 2, 2);
        g.setColor(WHITE);
        g.fillRect(9, -4, 3, 1);
        g.setColor(new Color(0xC95858));
        g.fillRect(-10, 4, 7, 3);
        g.setColor(WHITE);
        g.fillRect(-8, 4, 2, 3);
        g.setTransform(old);
    }

    private void hud(Graphics2D g) {
        g.setColor(new Color(14, 31, 45, 248));
        g.fillRect(0, 0, W, 30);
        g.setColor(new Color(0x83C5C6));
        g.fillRect(0, 29, W, 1);

        text(g, player == null ? "AUTOPILOT / DEMO" : shortName(player.name(), 19),
                10, 12, 12, MINT, false);
        text(g, "ELYTRA GLIDE", 11, 12, 25, WHITE, false);
        text(g, Integer.toString(score), passTime > 0.63 ? 25 : 22,
                240, 24, passTime > 0 ? GOLD : WHITE, true);
        text(g, REGIONS[material(score)], 9, 388, 12, GOLD, true);
        text(g, "BEST " + (leaders.isEmpty() ? 0 : leaders.get(0).score()),
                10, 388, 25, WHITE, true);

        if (player != null && mode == Mode.FLYING) {
            g.setColor(new Color(14, 31, 45, 235));
            g.fillRect(0, 254, W, 16);
            text(g, "CLICK / SPACE: BOOST", 10, 239, 266, WHITE, true);
            text(g, cooldown > 0 ? "LIFT" : "READY", 8, 13, 266,
                    cooldown > 0 ? GOLD : MINT, false);
            g.setColor(new Color(0x3A5261));
            g.fillRect(53, 260, 34, 4);
            g.setColor(cooldown > 0 ? GOLD : MINT);
            g.fillRect(53, 260, (int) (34 * clamp(1 - cooldown / COOLDOWN, 0, 1)), 4);

            Gate next = nextGate();
            if (next != null) {
                double remaining = Math.max(0, next.x + PW + 2 - (PX + LEFT));
                double progress = clamp(1 - remaining / 165, 0, 1);
                text(g, "NEXT GAP", 8, 365, 266, MINT, false);
                g.setColor(new Color(0x3A5261));
                g.fillRect(418, 260, 48, 4);
                g.setColor(GOLD);
                g.fillRect(418, 260, (int) (48 * progress), 4);
            }

            if (passTime > 0) {
                text(g, score == 1 ? "+1  FIRST GAP!" : score % 5 == 0 ? "+1  BEAUTIFUL!" : "+1",
                        14, PX + 52, passY - 14 - (0.9 - passTime) * 23,
                        GOLD, true);
            }
            if (banner > 0) {
                panel(g, 150, 41, 180, 36);
                text(g, score == 10 || score == 22 ? "NEW HORIZON" : "KEEP GLIDING",
                        9, 240, 54, MINT, true);
                text(g, score == 10 || score == 22 ? REGIONS[material(score)]
                                : score + " GAPS CLEARED",
                        14, 240, 71, GOLD, true);
            } else if (score == 0 && age < 2.2) {
                text(g, "TAP. COAST. GLIDE.", 12, 240, 49, WHITE, true);
            }
        }
    }

    private void overlays(Graphics2D g) {
        if (player == null) {
            boolean showScores = !leaders.isEmpty() && clock % 18 >= 11;
            if (showScores) {
                panel(g, 269, 43, 198, 86);
                text(g, "TOP GLIDERS", 15, 368, 62, GOLD, true);
                for (int i = 0; i < Math.min(3, leaders.size()); i++) {
                    Host.Score entry = leaders.get(i);
                    text(g, (i + 1) + ". " + shortName(entry.name(), 13),
                            10, 280, 81 + i * 18, WHITE, false);
                    text(g, Integer.toString(entry.score()), 10,
                            446, 81 + i * 18, MINT, true);
                }
            } else {
                panel(g, 219, 43, 248, 56);
                text(g, "ELYTRA GLIDE", 27, 343, 73, WHITE, true);
                text(g, "ONE BUTTON. ENDLESS HORIZONS.", 9,
                        343, 91, GOLD, true);
            }
            panel(g, 85, 218, 310, 30);
            text(g, "TAKE A SEAT TO FLY", 14, 240, 231,
                    clock % 1.5 < 1.1 ? GOLD : WHITE, true);
            text(g, "Click / Space boosts upward. Coast through gaps.",
                    9, 240, 243, WHITE, true);
            if (mode == Mode.CRASH || mode == Mode.OVER) {
                panel(g, 158, 161, 247, 31);
                text(g, "EVEN AUTOPILOT MISSES.", 13, 281, 181, WHITE, true);
            }
            return;
        }

        if (mode == Mode.READY) {
            panel(g, 165, 95, 303, 102);
            text(g, "READY TO GLIDE?", 25, 316, 124, WHITE, true);
            text(g, "CLICK / SPACE TO LAUNCH", 14, 316, 148, GOLD, true);
            text(g, "One tap lifts. Release and coast.", 12,
                    316, 170, MINT, true);
            text(g, "Clear each gap for +1. Wings are not solid.", 10,
                    316, 188, WHITE, true);
        } else if (mode == Mode.CRASH && age > 0.23) {
            panel(g, 140, 184, 324, 33);
            text(g, crashReason, 14, 302, 205, GOLD, true);
        } else if (mode == Mode.OVER) {
            panel(g, 98, 49, 284, 190);
            text(g, "FLIGHT ENDED", 29, 240, 83, WHITE, true);
            text(g, shortName(player.name(), 24), 11, 240, 101, MINT, true);
            text(g, score + (score == 1 ? " GAP CLEARED" : " GAPS CLEARED"),
                    23, 240, 132, GOLD, true);
            text(g, crashReason, 11, 240, 153, CORAL, true);
            text(g, crashTip, 10, 240, 171, WHITE, true);
            String achievement = score > bestAtStart
                    ? "NEW CABINET BEST!" : rank > 0 && score > 0
                    ? "HIGH SCORE #" + rank : "Every flight is another chance.";
            text(g, achievement, 10, 240, 189, GOLD, true);
            text(g, age >= 1.15 ? "CLICK / SPACE TO RETRY" : "REPACKING ELYTRA...",
                    age >= 1.15 ? 14 : 11, 240, 214, MINT, true);
            text(g, "Sneak to leave the cabinet", 9, 240, 230,
                    new Color(0xBDCDD5), true);
        }
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(0, 0, 0, 45));
        g.fillRoundRect(x + 3, y + 4, w, h, 9, 9);
        g.setColor(new Color(11, 28, 43, 242));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setStroke(new BasicStroke(1));
        g.setColor(new Color(0x8AC7CB));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.setColor(GOLD);
        g.fillRect(x + 12, y, 25, 2);
    }

    private static void text(Graphics2D g, String value, int size,
                             double x, double y, Color color, boolean center) {
        g.setFont(FONTS[Math.max(1, Math.min(FONTS.length - 1, size))]);
        float xx = (float) (center
                ? x - g.getFontMetrics().stringWidth(value) / 2.0 : x);
        g.setColor(new Color(8, 20, 31, 230));
        g.drawString(value, xx, (float) y + 1);
        g.setColor(color);
        g.drawString(value, xx, (float) y);
    }

    private static String shortName(String name, int length) {
        if (name == null || name.isEmpty()) return "GLIDER";
        return name.length() <= length ? name : name.substring(0, length - 1) + "…";
    }

    private static int hash(long index, int salt) {
        long value = index * 0x9E3779B97F4A7C15L + salt * 0x632BE59BD9B4E019L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        return (int) (value & 0x7FFFFFFF);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double wrap(double value, double size) {
        return value - Math.floor(value / size) * size;
    }

    private static Color alpha(Color color, int opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, opacity)));
    }

    private static Color mix(Color a, Color b, double amount) {
        amount = clamp(amount, 0, 1);
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * amount),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }

    private static final class Gate {
        double x, center, gap;
        int number, material;
        boolean passed;
    }

    private static final class Spark {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
        boolean debris;
    }
}

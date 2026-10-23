package ai.resourcepack.arcade.games;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Pixels;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * PHANTOM FIELD
 *
 * Review of the supplied sheet and actual harness report:
 * - No exception or performance failure was reported: 2.7 ms average / 6.6 ms
 *   worst is comfortably within budget. The old source's 26.3 / 58.4 ms
 *   commentary was stale and incorrect.
 * - The large opaque attract panel hides the demo ship, phantom and action.
 * - Text is generally readable, but fading instructions leave an opaque black
 *   text shadow. This is visible near the bottom of the five-second frame.
 * - The low-resolution nearest-neighbour world makes fine diagonal vector
 *   outlines rather stair-stepped. Rocks, ghosts and effects have similar
 *   visual weight, so the game's signature enemy does not stand out enough.
 * - A thin dashed warning is easy to miss. It needs direction markers and a
 *   stronger wind-up, rather than just a pale line through nearby rocks.
 * - Impact feedback is short-lived; sampled play looks quiet. Losing a hull
 *   produces a large modal-looking panel that hides the continuing field.
 * - Two lost lives by 11.5 seconds is not itself a bug (this is scripted input),
 *   but clearer protection, recovery and threat cues would improve onboarding.
 * - Wrapping, splitting, waves, lives, scoring and the 10,000-point extra life
 *   were already present; the screenshots do not demonstrate missing features.
 *
 * This revision keeps bounded, low-resolution world rendering, improves
 * vector sampling, reduces attract obstruction, fixes text fading, strengthens
 * phantom telegraphs and hit feedback, and makes hull recovery non-modal.
 * No new performance measurement is claimed.
 */
public class PhantomField implements Scene {
    private static final double W = 1024, H = 576;
    private static final double TOP = 78, FIELD = 454, TAU = Math.PI * 2;
    private static final int RW = 960, RH = 540;

    private static final Color BLACK = new Color(0x030208);
    private static final Color PANEL = new Color(0x0D091A);
    private static final Color WHITE = new Color(0xF8F4FF);
    private static final Color VIOLET = new Color(0xAC78FF);
    private static final Color LILAC = new Color(0xDCC4FF);
    private static final Color GOLD = new Color(0xFFE1AA);
    private static final Color MUTED = new Color(0xB0A2C8);
    private static final Color BORDER = new Color(0x49305F);
    private static final Color ROCK_FILL = new Color(0x0C0917);
    private static final Color GHOST_FILL = new Color(0x281141);
    private static final Color[] PALETTE = {WHITE, VIOLET, LILAC, GOLD, MUTED};
    private static final Color[][] INK = new Color[5][64];
    private static final Font[] FONT = new Font[81];

    private static final BasicStroke THIN = new BasicStroke(1.1f);
    private static final BasicStroke CORE =
            new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke BRIGHT =
            new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke HALO =
            new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke WIDE =
            new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke DASH =
            new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    10, new float[]{9, 8}, 0);
    private static final Path2D SHIP = makeShip();
    private static final Path2D[] GHOST_SHAPES = makeGhosts();

    static {
        for (int i = 1; i < FONT.length; i++) {
            FONT[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
        }
        for (int c = 0; c < PALETTE.length; c++) {
            Color p = PALETTE[c];
            for (int a = 0; a < 64; a++) {
                INK[c][a] = new Color(p.getRed(), p.getGreen(), p.getBlue(),
                        a * 255 / 63);
            }
        }
    }

    private final Random random = new Random(0x5048414E544F4DL);
    private final EnumSet<Key> keys = EnumSet.noneOf(Key.class);
    private final List<Rock> rocks = new ArrayList<>();
    private final List<Phantom> ghosts = new ArrayList<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();

    private Host host;
    private Seat player;
    private BufferedImage background, frame;
    private double clock, runTime, overTime;
    private double x, y, vx, vy, angle;
    private double shield, recovery, cooldown, exhaust, muzzle;
    private double waveWait, waveBanner, phantomTimer;
    private double shake, flash, hitStop, shotSound, impactSound;
    private double noticeTime, scorePulse, lifePulse;
    private int score, lives, wave, rank, pendingGhosts;
    private boolean over, extraLife, fireRequest, thrusting, braking;
    private String notice = "";
    private List<Host.Score> leaders = List.of();
    private double leaderboardTimer;

    public PhantomField() {}

    @Override public boolean showPointer() { return false; }
    @Override public int maxPlayers() { return 1; }
    @Override public int fps() { return 30; }

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        makeBackground();
        frame = new BufferedImage(RW, RH, BufferedImage.TYPE_INT_RGB);
        player = null;
        reset();
        refreshScores();
        if (!host.seats().isEmpty()) joined(host.seats().get(0));
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || player != null) return;
        player = seat;
        reset();
        sound("block.note_block.chime", 0.6f, 1);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        player = null;
        reset();
        refreshScores();
    }

    private boolean owns(Seat seat) {
        return seat != null && player != null
                && Objects.equals(seat.id(), player.id());
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (host == null || key == null || !owns(seat)) return;
        boolean changed = down ? keys.add(key) : keys.remove(key);
        if (changed && down && key == Key.JUMP && over && overTime > 1.1) {
            reset();
            keys.add(Key.JUMP);
            sound("block.note_block.chime", 0.6f, 1);
        }
    }

    @Override
    public void click(Seat seat, int px, int py, boolean right) {
        if (host == null || !owns(seat) || right) return;
        if (over) {
            if (overTime > 1.1) {
                reset();
                sound("block.note_block.chime", 0.6f, 1);
            }
        } else if (recovery <= 0) {
            fireRequest = true;
        }
    }

    private void reset() {
        keys.clear();
        rocks.clear();
        ghosts.clear();
        bullets.clear();
        particles.clear();
        rings.clear();
        popups.clear();
        score = wave = rank = pendingGhosts = 0;
        lives = 3;
        over = extraLife = fireRequest = thrusting = braking = false;
        runTime = overTime = recovery = cooldown = exhaust = muzzle = 0;
        shake = flash = hitStop = shotSound = impactSound = 0;
        waveWait = waveBanner = noticeTime = scorePulse = lifePulse = 0;
        notice = "";
        x = W / 2;
        y = FIELD / 2;
        vx = vy = 0;
        angle = -Math.PI / 2;
        shield = 3.6;
        beginWave();
        if (player == null) {
            spawnGhost();
            pendingGhosts--;
            vx = 100;
            vy = -55;
        }
    }

    private void refreshScores() {
        if (host == null) return;
        List<Host.Score> scores = host.scores();
        leaders = scores == null ? List.of() : new ArrayList<>(scores);
        leaderboardTimer = 3;
    }

    private void beginWave() {
        wave++;
        waveBanner = 2.7;
        waveWait = 0;
        int count = Math.min(10, 5 + wave);
        double phase = random.nextDouble() * TAU;
        for (int i = 0; i < count; i++) {
            double a = phase + i * TAU / count;
            double rx = wrap(x + Math.cos(a) * (345 + random.nextDouble() * 65), W);
            double ry = wrap(y + Math.sin(a) * (178 + random.nextDouble() * 22), FIELD);
            rocks.add(new Rock(rx, ry, 3,
                    a + Math.PI + (random.nextDouble() - 0.5) * 1.5));
        }
        pendingGhosts = Math.min(6, 1 + wave / 2);
        phantomTimer = wave == 1 ? 2.3 : 1.8;
        // A new wave cannot materialize on an unprotected ship.
        shield = Math.max(shield, 2.3);
        if (wave > 1) sound("block.note_block.bell", 0.6f, 1.2f);
    }

    private void spawnShip() {
        double best = -Double.MAX_VALUE;
        double bx = W / 2, by = FIELD / 2;
        for (int i = 0; i < 35; i++) {
            double cx = (i % 7 + 0.5) * W / 7;
            double cy = (i / 7 + 0.5) * FIELD / 5;
            double clearance = 1000;
            for (Rock r : rocks) {
                double futureX = wrap(r.x + r.vx * 0.6, W);
                double futureY = wrap(r.y + r.vy * 0.6, FIELD);
                clearance = Math.min(clearance,
                        Math.min(distance(cx, cy, r.x, r.y),
                                distance(cx, cy, futureX, futureY)) - r.radius);
            }
            for (Phantom p : ghosts) {
                clearance = Math.min(clearance, distance(cx, cy, p.x, p.y) - 70);
            }
            clearance -= distance(cx, cy, W / 2, FIELD / 2) * 0.035;
            if (clearance > best) {
                best = clearance;
                bx = cx;
                by = cy;
            }
        }
        x = bx;
        y = by;
        vx = vy = 0;
        angle = -Math.PI / 2;
        shield = 3.6;
        cooldown = 0.12;
        fireRequest = false;
        ring(x, y, 85, 2);
        notice = "SIGNAL RESTORED";
        noticeTime = 1.5;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        runTime += dt;
        waveBanner = Math.max(0, waveBanner - dt);
        noticeTime = Math.max(0, noticeTime - dt);
        scorePulse = Math.max(0, scorePulse - dt * 3);
        lifePulse = Math.max(0, lifePulse - dt);
        shake = Math.max(0, shake - dt * 18);
        flash = Math.max(0, flash - dt * 2.5);
        muzzle = Math.max(0, muzzle - dt);
        shotSound = Math.max(0, shotSound - dt);
        impactSound = Math.max(0, impactSound - dt);
        leaderboardTimer -= dt;
        if (player == null && leaderboardTimer <= 0) refreshScores();

        if (over) {
            overTime += dt;
            for (Rock r : rocks) moveRock(r, dt);
            for (Phantom p : ghosts) {
                p.age += dt;
                p.x = wrap(p.x + p.vx * dt, W);
                p.y = wrap(p.y + p.vy * dt, FIELD);
                p.warning = p.charging = false;
            }
        } else {
            double pause = Math.min(hitStop, dt);
            hitStop -= pause;
            double playDt = dt - pause;
            if (playDt > 0) advance(playDt);
        }
        effects(dt);
    }

    private void advance(double dt) {
        if (recovery > 0) {
            recovery -= dt;
            if (recovery <= 0) spawnShip();
        }
        if (waveWait > 0) {
            waveWait -= dt;
            if (waveWait <= 0) beginWave();
        } else if (pendingGhosts > 0) {
            phantomTimer -= dt;
            if (phantomTimer <= 0) {
                spawnGhost();
                pendingGhosts--;
                phantomTimer = Math.max(2.8, 4.7 - wave * 0.15);
            }
        }

        int steps = Math.max(1, (int) Math.ceil(dt * 100));
        for (int i = 0; i < steps && !over; i++) {
            simulate(dt / steps);
            if (hitStop > 0) break;
        }

        if (!over && waveWait <= 0 && rocks.isEmpty()
                && ghosts.isEmpty() && pendingGhosts == 0) {
            int bonus = 300 + Math.min(wave, 10000) * 100;
            addScore(bonus);
            notice = "FIELD CLEAR  +" + bonus;
            noticeTime = 2.6;
            waveWait = 2.6;
            shield = Math.max(shield, 2.6);
            ring(x, y, 180, 2);
            sound("entity.player.levelup", 0.65f, 1.4f);
        }
    }

    private void simulate(double dt) {
        cooldown = Math.max(0, cooldown - dt);
        shield = Math.max(0, shield - dt);
        thrusting = braking = false;
        if (recovery <= 0) {
            boolean shoot;
            if (player == null) {
                shoot = autopilot(dt);
            } else {
                int turn = (keys.contains(Key.RIGHT) ? 1 : 0)
                        - (keys.contains(Key.LEFT) ? 1 : 0);
                angle += turn * 3.65 * dt;
                thrusting = keys.contains(Key.UP);
                braking = keys.contains(Key.DOWN);
                shoot = fireRequest || keys.contains(Key.JUMP);
            }
            angle = angleDelta(angle);
            double dx = Math.cos(angle), dy = Math.sin(angle);
            if (thrusting) {
                vx += dx * 285 * dt;
                vy += dy * 285 * dt;
                exhaust -= dt;
                if (exhaust <= 0) {
                    exhaust += 0.025;
                    emit(x - dx * 15, y - dy * 15,
                            vx - dx * 130 + (random.nextDouble() - 0.5) * 32,
                            vy - dy * 130 + (random.nextDouble() - 0.5) * 32,
                            0.45, 2.5, 1);
                }
            }
            double drag = Math.exp(-(braking ? 2.2 : 0.18) * dt);
            vx *= drag;
            vy *= drag;
            double speed = Math.hypot(vx, vy);
            if (speed > 340) {
                vx *= 340 / speed;
                vy *= 340 / speed;
            }
            x = wrap(x + vx * dt, W);
            y = wrap(y + vy * dt, FIELD);
            if (shoot && cooldown <= 0 && bullets.size() < 48) fire();
        }

        for (Rock r : rocks) moveRock(r, dt);
        for (Phantom p : ghosts) moveGhost(p, dt);

        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            if ((b.life -= dt) <= 0) {
                bullets.remove(i);
                continue;
            }
            b.x = wrap(b.x + b.vx * dt, W);
            b.y = wrap(b.y + b.vy * dt, FIELD);
            boolean hit = false;
            for (int j = rocks.size() - 1; j >= 0; j--) {
                Rock r = rocks.get(j);
                if (near(b.x, b.y, r.x, r.y, r.radius * 0.87 + 3)) {
                    destroyRock(j);
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                for (int j = ghosts.size() - 1; j >= 0; j--) {
                    Phantom p = ghosts.get(j);
                    if (p.age < 0.75 || !near(b.x, b.y, p.x, p.y, 24)) continue;
                    hit = true;
                    p.flash = 0.2;
                    burst(b.x, b.y, 10, 0, 150);
                    if (--p.hp <= 0) {
                        ghosts.remove(j);
                        burst(p.x, p.y, 38, 1, 230);
                        burst(p.x, p.y, 14, 0, 150);
                        ring(p.x, p.y, 105, 2);
                        addScore(350);
                        popup(p.x, p.y - 25, "PHANTOM +350", 2);
                        shake = Math.max(shake, 4.2);
                        hitStop = Math.max(hitStop, 0.045);
                        sound("entity.experience_orb.pickup", 0.65f, 1.4f);
                    } else {
                        ring(b.x, b.y, 24, 0);
                        sound("block.note_block.pling", 0.4f, 1.8f);
                    }
                    break;
                }
            }
            if (hit) bullets.remove(i);
        }

        if (recovery <= 0 && shield <= 0) {
            for (Rock r : rocks) {
                if (near(x, y, r.x, r.y, r.radius * 0.79 + 8)) {
                    die();
                    return;
                }
            }
            for (Phantom p : ghosts) {
                if (p.age > 1 && near(x, y, p.x, p.y, 26)) {
                    die();
                    return;
                }
            }
        }
    }

    private boolean autopilot(double dt) {
        double aim = clock * 0.4;
        double best = Double.MAX_VALUE, threat = Double.MAX_VALUE;
        double tx = 0, ty = 0;
        boolean target = false;
        for (Rock r : rocks) {
            double dx = delta(x, r.x, W), dy = delta(y, r.y, FIELD);
            double d = Math.hypot(dx, dy);
            if (d < best) {
                best = d;
                aim = Math.atan2(dy + r.vy * d / 665, dx + r.vx * d / 665);
                target = true;
            }
            if (d - r.radius < threat) {
                threat = d - r.radius;
                tx = dx;
                ty = dy;
            }
        }
        for (Phantom p : ghosts) {
            double dx = delta(x, p.x, W), dy = delta(y, p.y, FIELD);
            double d = Math.hypot(dx, dy);
            if (d * 0.65 < best) {
                best = d * 0.65;
                aim = Math.atan2(dy + p.vy * d / 665, dx + p.vx * d / 665);
                target = true;
            }
            if (d - 26 < threat) {
                threat = d - 26;
                tx = dx;
                ty = dy;
            }
        }
        double desired = threat < 85 ? Math.atan2(-ty, -tx) + 0.3 : aim;
        angle += clamp(angleDelta(desired - angle), -4.2 * dt, 4.2 * dt);
        thrusting = threat < 135 || Math.hypot(vx, vy) < 155
                || (!target && Math.sin(clock) > 0);
        return target && Math.abs(angleDelta(aim - angle)) < 0.3;
    }

    private void fire() {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        Bullet b = new Bullet();
        b.x = wrap(x + dx * 21, W);
        b.y = wrap(y + dy * 21, FIELD);
        b.vx = dx * 660 + vx * 0.65;
        b.vy = dy * 660 + vy * 0.65;
        b.life = 1.18;
        double length = Math.hypot(b.vx, b.vy);
        b.dx = b.vx / length;
        b.dy = b.vy / length;
        bullets.add(b);
        cooldown = 0.145;
        fireRequest = false;
        muzzle = 0.075;
        if (shotSound <= 0) {
            sound("block.note_block.hat", 0.28f, 1.8f);
            shotSound = 0.12;
        }
    }

    private void moveRock(Rock r, double dt) {
        r.x = wrap(r.x + r.vx * dt, W);
        r.y = wrap(r.y + r.vy * dt, FIELD);
        r.angle += r.spin * dt;
    }

    private void spawnGhost() {
        Phantom p = new Phantom();
        p.x = wrap(x + (random.nextBoolean() ? 335 : -335), W);
        p.y = wrap(y + (random.nextBoolean() ? 125 : -125), FIELD);
        p.phase = random.nextDouble() * TAU;
        p.chargeAngle = Math.atan2(delta(p.y, y, FIELD), delta(p.x, x, W));
        ghosts.add(p);
        ring(p.x, p.y, 88, 1);
        if (noticeTime <= 0 || !notice.startsWith("10,000")) {
            notice = "PHANTOM CONTACT";
            noticeTime = 1.6;
        }
        sound("block.note_block.chime", 0.5f, 0.55f);
    }

    private void moveGhost(Phantom p, double dt) {
        p.age += dt;
        p.flash = Math.max(0, p.flash - dt);
        if (p.age < 0.75) return;
        double oldCycle = p.time % 4.5;
        p.time += dt;
        double cycle = p.time % 4.5;
        p.warning = cycle >= 2.0 && cycle < 2.9;
        boolean wasCharging = p.charging;
        p.charging = cycle >= 2.9 && cycle < 3.8;

        double aim = Math.atan2(delta(p.y, y, FIELD), delta(p.x, x, W));
        if (cycle >= 2 && oldCycle < 2) {
            p.chargeAngle = aim;
            ring(p.x, p.y, 44, 2);
            sound("block.note_block.bell", 0.3f, 0.8f);
        }
        double wantX, wantY, response;
        if (p.charging) {
            double speed = Math.min(335, 255 + wave * 7);
            wantX = Math.cos(p.chargeAngle) * speed;
            wantY = Math.sin(p.chargeAngle) * speed;
            response = 10;
            if (!wasCharging) {
                burst(p.x, p.y, 10, 1, 105);
                sound("entity.firework_rocket.launch", 0.24f, 1.5f);
            }
        } else if (p.warning) {
            wantX = wantY = 0;
            response = 12;
        } else {
            double a = aim + Math.sin(p.time * 2.5 + p.phase) * 1.0;
            double speed = Math.min(145, 80 + wave * 6);
            wantX = Math.cos(a) * speed;
            wantY = Math.sin(a) * speed;
            response = 2;
        }
        double f = 1 - Math.exp(-response * dt);
        p.vx += (wantX - p.vx) * f;
        p.vy += (wantY - p.vy) * f;
        p.x = wrap(p.x + p.vx * dt, W);
        p.y = wrap(p.y + p.vy * dt, FIELD);
        p.trail -= dt;
        if (p.trail <= 0) {
            p.trail = p.charging ? 0.035 : 0.085;
            emit(p.x, p.y, -p.vx * 0.18, -p.vy * 0.18,
                    p.charging ? 0.65 : 0.5, p.charging ? 4.5 : 3, 1);
        }
    }

    private void destroyRock(int index) {
        Rock r = rocks.remove(index);
        int value = r.size == 3 ? 25 : r.size == 2 ? 60 : 100;
        addScore(value);
        burst(r.x, r.y, 13 + r.size * 5, r.size == 1 ? 0 : 2, 100 + r.size * 30);
        ring(r.x, r.y, r.radius * 1.8, 1);
        popup(r.x, r.y - 12, "+" + value, r.size == 1 ? 0 : 2);
        shake = Math.max(shake, 0.7 + r.size * 0.65);
        if (r.size == 3) hitStop = Math.max(hitStop, 0.025);
        if (r.size > 1) {
            double base = Math.atan2(r.vy, r.vx);
            for (int side = -1; side <= 1; side += 2) {
                double a = base + side * (0.9 + random.nextDouble() * 0.3);
                Rock child = new Rock(
                        wrap(r.x + Math.cos(a) * r.radius * 0.36, W),
                        wrap(r.y + Math.sin(a) * r.radius * 0.36, FIELD),
                        r.size - 1, a);
                child.vx += r.vx * 0.2;
                child.vy += r.vy * 0.2;
                rocks.add(child);
            }
        }
        if (impactSound <= 0) {
            sound("block.note_block.bit", 0.5f,
                    r.size == 3 ? 0.6f : r.size == 2 ? 0.95f : 1.4f);
            impactSound = 0.045;
        }
    }

    private void addScore(int value) {
        if (over) return;
        score = (int) Math.min(Integer.MAX_VALUE, (long) score + value);
        scorePulse = 1;
        if (!extraLife && score >= 10000) {
            extraLife = true;
            lives++;
            lifePulse = 2.5;
            notice = "10,000 / EXTRA LIFE";
            noticeTime = 3;
            ring(x, y, 150, 3);
            sound("ui.toast.challenge_complete", 0.7f, 1.2f);
        }
    }

    private void die() {
        burst(x, y, 48, 0, 270);
        burst(x, y, 30, 1, 220);
        ring(x, y, 145, 2);
        shake = 9;
        flash = 0.6;
        hitStop = 0.085;
        thrusting = false;
        fireRequest = false;
        bullets.clear();
        sound("entity.generic.explode", 0.5f, 1.5f);
        if (player == null) {
            recovery = 0.9;
            return;
        }
        lifePulse = 1.4;
        if (--lives <= 0) {
            over = true;
            overTime = 0;
            keys.clear();
            rank = host.score(player, score);
            refreshScores();
            sound("block.note_block.bass", 0.6f, 0.5f);
        } else {
            recovery = 1.0;
            notice = "HULL LOST / RECONSTRUCTING";
            noticeTime = 1.1;
        }
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && player != null) host.sound(id, volume, pitch);
    }

    private void emit(double px, double py, double pvx, double pvy,
                      double life, double size, int color) {
        if (particles.size() >= 380) return;
        Particle p = new Particle();
        p.x = wrap(px, W);
        p.y = wrap(py, FIELD);
        p.vx = pvx;
        p.vy = pvy;
        p.life = p.max = life;
        p.size = size;
        p.color = color;
        particles.add(p);
    }

    private void burst(double px, double py, int count, int color, double speed) {
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * TAU;
            double v = speed * (0.25 + random.nextDouble() * 0.75);
            emit(px, py, Math.cos(a) * v, Math.sin(a) * v,
                    0.4 + random.nextDouble() * 0.65,
                    1.7 + random.nextDouble() * 2.3, color);
        }
    }

    private void ring(double px, double py, double radius, int color) {
        if (rings.size() >= 24) rings.remove(0);
        Ring r = new Ring();
        r.x = px;
        r.y = py;
        r.radius = radius;
        r.color = color;
        rings.add(r);
    }

    private void popup(double px, double py, String text, int color) {
        if (popups.size() >= 14) popups.remove(0);
        Popup p = new Popup();
        p.x = clamp(px, 90, W - 90);
        p.y = clamp(py, 45, FIELD - 35);
        p.text = text;
        p.color = color;
        popups.add(p);
    }

    private void effects(double dt) {
        double drag = Math.exp(-1.3 * dt);
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            if ((p.life -= dt) <= 0) {
                particles.remove(i);
                continue;
            }
            p.x = wrap(p.x + p.vx * dt, W);
            p.y = wrap(p.y + p.vy * dt, FIELD);
            p.vx *= drag;
            p.vy *= drag;
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            if ((rings.get(i).life -= dt) <= 0) rings.remove(i);
        }
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup p = popups.get(i);
            p.y -= dt * 24;
            if ((p.life -= dt) <= 0) popups.remove(i);
        }
    }

    private void makeBackground() {
        background = new BufferedImage(RW, RH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background.createGraphics();
        g.setColor(BLACK);
        g.fillRect(0, 0, RW, RH);
        g.scale(RW / W, RH / H);
        Random stars = new Random(84);
        for (int i = 0; i < 285; i++) {
            int sx = stars.nextInt((int) W);
            int sy = (int) TOP + stars.nextInt((int) FIELD);
            int b = 24 + stars.nextInt(37);
            g.setColor(new Color(b, b - 5, b + 16));
            g.fillRect(sx, sy, i % 29 == 0 ? 2 : 1, 1);
            if (i % 61 == 0) {
                g.setColor(new Color(0x37264B));
                g.drawLine(sx - 3, sy, sx + 3, sy);
                g.drawLine(sx, sy - 3, sx, sy + 3);
            }
        }
        g.setColor(new Color(0x291C38));
        for (int xx = 32; xx < W; xx += 64) {
            g.drawLine(xx, (int) TOP + 2, xx, (int) TOP + 6);
            g.drawLine(xx, 526, xx, 530);
        }
        for (int yy = 110; yy < 525; yy += 64) {
            g.drawLine(2, yy, 6, yy);
            g.drawLine(1018, yy, 1022, yy);
        }
        g.dispose();
    }

    @Override
    public void draw(Graphics2D graphics) {
        if (graphics == null) return;
        int width = host == null ? 2048 : Math.max(1, host.width());
        int height = host == null ? 1152 : Math.max(1, host.height());
        Graphics2D out = (Graphics2D) graphics.create();
        out.setColor(BLACK);
        out.fillRect(0, 0, width, height);
        if (frame == null || background == null) {
            out.dispose();
            return;
        }

        Graphics2D low = frame.createGraphics();
        low.drawImage(background, 0, 0, null);
        low.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        low.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        low.scale(RW / W, RH / H);
        low.clipRect(0, (int) TOP, (int) W, (int) FIELD);
        low.translate(Math.sin(clock * 71) * shake,
                TOP + Math.cos(clock * 89) * shake * 0.65);
        paintField(low);
        low.dispose();

        // Bilinear upscaling suits luminous vectors better than pixel-art sampling (Java2D's own takes 34 ms).
        Pixels.drawSmooth(out, frame, 0, 0, width, height);
        out.scale(width / W, height / H);
        out.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        out.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (Popup p : popups) {
            text(out, p.text, 20, p.x, TOP + p.y,
                    ink(p.color, Math.min(1, p.life / 0.35)), 0);
        }
        if (player != null && !over) paintFlightUI(out);
        paintHud(out);
        if (player == null) paintAttract(out);
        else if (over) paintOver(out);
        out.dispose();
    }

    private void paintField(Graphics2D g) {
        if (flash > 0) {
            g.setColor(ink(1, flash * 0.16));
            g.fillRect(0, 0, (int) W, (int) FIELD);
        }

        for (Ring r : rings) {
            double t = clamp(1 - r.life / 0.65, 0, 1);
            double radius = 5 + r.radius * (1 - (1 - t) * (1 - t));
            g.setStroke(CORE);
            g.setColor(ink(r.color, (1 - t) * 0.8));
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = r.x + ix * W, py = r.y + iy * FIELD;
                    if (visible(px, py, radius)) {
                        g.draw(new Ellipse2D.Double(px - radius, py - radius,
                                radius * 2, radius * 2));
                    }
                }
            }
        }

        for (Rock r : rocks) {
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = r.x + ix * W, py = r.y + iy * FIELD;
                    if (!visible(px, py, r.radius + 7)) continue;
                    AffineTransform saved = g.getTransform();
                    g.translate(px, py);
                    g.rotate(r.angle);
                    g.setColor(ROCK_FILL);
                    g.fill(r.path);
                    glow(g, r.path, r.size == 1 ? 2 : 1, CORE);
                    g.setStroke(THIN);
                    g.setColor(ink(2, 0.3));
                    g.draw(r.seam);
                    g.setTransform(saved);
                }
            }
        }

        Line2D.Double line = new Line2D.Double();
        for (Particle p : particles) {
            double f = p.life / p.max;
            g.setColor(ink(p.color, f * 0.95));
            if (p.size >= 3.8) {
                double size = Math.max(1, p.size * f);
                g.fill(new Ellipse2D.Double(p.x - size / 2, p.y - size / 2, size, size));
            } else {
                g.setStroke(CORE);
                line.setLine(p.x, p.y, p.x - p.vx * 0.038, p.y - p.vy * 0.038);
                g.draw(line);
            }
        }

        // Warnings are above rocks and particles; they must not disappear in clutter.
        for (Phantom p : ghosts) {
            if (!p.warning) continue;
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = p.x + ix * W, py = p.y + iy * FIELD;
                    if (visible(px, py, 325)) paintWarning(g, p, px, py);
                }
            }
        }

        for (Phantom p : ghosts) {
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = p.x + ix * W, py = p.y + iy * FIELD;
                    if (visible(px, py, 90)) paintGhost(g, p, px, py);
                }
            }
        }

        for (Bullet b : bullets) {
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = b.x + ix * W, py = b.y + iy * FIELD;
                    if (!visible(px, py, 28)) continue;
                    line.setLine(px - b.dx * 27, py - b.dy * 27, px, py);
                    g.setStroke(HALO);
                    g.setColor(ink(1, 0.25));
                    g.draw(line);
                    line.setLine(px - b.dx * 17, py - b.dy * 17, px, py);
                    g.setStroke(BRIGHT);
                    g.setColor(WHITE);
                    g.draw(line);
                }
            }
        }
        if (!over && recovery <= 0) {
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    double px = x + ix * W, py = y + iy * FIELD;
                    if (visible(px, py, 60)) paintShip(g, px, py);
                }
            }
        }
    }

    private void paintWarning(Graphics2D g, Phantom p, double px, double py) {
        double t = clamp((p.time % 4.5 - 2) / 0.9, 0, 1);
        double dx = Math.cos(p.chargeAngle), dy = Math.sin(p.chargeAngle);
        double length = Math.min(335, 255 + wave * 7) * 0.9;
        Line2D line = new Line2D.Double(px + dx * 34, py + dy * 34,
                px + dx * length, py + dy * length);
        g.setStroke(WIDE);
        g.setColor(ink(1, 0.08 + t * 0.07));
        g.draw(line);
        g.setStroke(DASH);
        g.setColor(ink(2, 0.4 + t * 0.5));
        g.draw(line);
        g.setStroke(CORE);
        for (int i = 0; i < 3; i++) {
            double d = 75 + i * 68 + t * 15;
            double ax = px + dx * d, ay = py + dy * d;
            g.draw(new Line2D.Double(ax, ay, ax - dx * 10 - dy * 6,
                    ay - dy * 10 + dx * 6));
            g.draw(new Line2D.Double(ax, ay, ax - dx * 10 + dy * 6,
                    ay - dy * 10 - dx * 6));
        }
        double radius = 52 - t * 20;
        g.setColor(ink(2, 0.5 + t * 0.5));
        g.draw(new Ellipse2D.Double(px - radius, py - radius, radius * 2, radius * 2));
        g.setStroke(BRIGHT);
        g.drawArc((int) px - 37, (int) py - 37, 74, 74, 90, -(int) (360 * t));
    }

    private void paintGhost(Graphics2D g, Phantom p, double px, double py) {
        AffineTransform saved = g.getTransform();
        g.translate(px, py);
        double direction = p.warning || p.charging
                ? p.chargeAngle : Math.atan2(p.vy, p.vx);
        g.rotate(direction + Math.PI / 2);
        double scale = Math.min(1, 0.2 + p.age * 1.15);
        g.scale(scale, scale);
        if (p.charging) {
            g.setColor(ink(1, 0.35));
            g.setStroke(HALO);
            g.drawLine(-14, 12, -7, 71);
            g.drawLine(14, 12, 7, 71);
            g.setColor(ink(2, 0.6));
            g.setStroke(CORE);
            g.drawLine(-9, 24, -4, 52);
            g.drawLine(9, 24, 4, 52);
        }
        Path2D body = GHOST_SHAPES[(int) (p.age * 10 + p.phase) & 3];
        g.setColor(GHOST_FILL);
        g.fill(body);
        g.setStroke(WIDE);
        g.setColor(ink(1, p.warning ? 0.23 : 0.12));
        g.draw(body);
        glow(g, body, p.charging || p.flash > 0 ? 0 : 2, BRIGHT);
        g.setColor(WHITE);
        g.fillRect(-12, -9, 7, 8);
        g.fillRect(5, -9, 7, 8);
        g.setStroke(CORE);
        g.drawLine(-4, 6, 4, 6);
        if (p.hp == 1) {
            g.setColor(LILAC);
            g.drawLine(-1, -23, 4, -16);
            g.drawLine(4, -16, -2, -9);
        }
        g.setTransform(saved);
    }

    private void paintShip(Graphics2D g, double px, double py) {
        AffineTransform saved = g.getTransform();
        g.translate(px, py);
        if (shield > 0) {
            g.setStroke(THIN);
            g.setColor(ink(1, 0.3));
            g.drawOval(-31, -31, 62, 62);
            g.setStroke(BRIGHT);
            g.setColor(ink(2, shield < 0.75 ? 0.55 + 0.4 * Math.sin(clock * 22) : 0.9));
            g.drawArc(-32, -32, 64, 64, 90,
                    -(int) (360 * Math.min(1, shield / 3.6)));
        }
        g.rotate(angle);
        if (thrusting) {
            double length = 24 + Math.sin(clock * 57) * 7;
            Path2D flame = new Path2D.Double();
            flame.moveTo(-11, -6);
            flame.lineTo(-15 - length, 0);
            flame.lineTo(-11, 6);
            glow(g, flame, 1, BRIGHT);
            g.setColor(WHITE);
            g.setStroke(CORE);
            g.drawLine(-12, 0, -27, 0);
        }
        g.setColor(ROCK_FILL);
        g.fill(SHIP);
        glow(g, SHIP, 0, BRIGHT);
        g.setColor(LILAC);
        g.setStroke(CORE);
        g.drawLine(9, 0, -4, -4);
        g.drawLine(9, 0, -4, 4);
        if (muzzle > 0) {
            g.setColor(WHITE);
            g.drawLine(24, -6, 24, 6);
            g.drawLine(18, 0, 32, 0);
        }
        if (braking) {
            g.setColor(LILAC);
            g.drawLine(-9, -14, -14, -22);
            g.drawLine(-9, 14, -14, 22);
        }
        g.setTransform(saved);
    }

    private void paintFlightUI(Graphics2D g) {
        if (recovery > 0) {
            // No modal box: the player can still read every moving threat.
            text(g, "RECONSTRUCTING", 28, W / 2, 280, WHITE, 0);
            text(g, lives + (lives == 1 ? " HULL REMAINS" : " HULLS REMAIN"),
                    18, W / 2, 309, LILAC, 0);
            g.setColor(BORDER);
            g.fillRect(442, 322, 140, 3);
            g.setColor(LILAC);
            g.fillRect(442, 322, (int) (140 * clamp(1 - recovery, 0, 1)), 3);
        } else if (shield > 0 && runTime > 0.4) {
            double labelY = y + TOP + 52;
            if (labelY > 510) labelY = y + TOP - 43;
            text(g, "SHIELD " + String.format(java.util.Locale.ROOT, "%.1f", shield),
                    15, clamp(x, 62, W - 62), labelY, ink(2, 0.9), 0);
        }
        if (runTime < 5 && recovery <= 0) {
            double a = Math.min(1, 5 - runTime);
            text(g, "HOLD SPACE TO FIRE  /  KEEP MOVING", 20, W / 2, 512, ink(2, a), 0);
        } else if (waveWait > 0) {
            text(g, "FIELD CLEAR", 37, W / 2, 270, WHITE, 0);
            text(g, "NEXT WAVE " + (wave + 1), 20, W / 2, 302, LILAC, 0);
        }
    }

    private void paintHud(Graphics2D g) {
        g.setColor(PANEL);
        g.fillRect(0, 0, (int) W, (int) TOP);
        g.fillRect(0, 533, (int) W, 43);
        g.setColor(BORDER);
        g.fillRect(24, 76, 976, 1);
        g.fillRect(24, 533, 976, 1);

        text(g, player == null ? "AUTOPILOT / LIVE DEMO" : shortName(player.name(), 19),
                18, 25, 25, LILAC, -1);
        text(g, Integer.toString(score), 35, 24, 63, WHITE, -1);
        if (scorePulse > 0) {
            g.setColor(ink(2, scorePulse * 0.7));
            g.fillRect(25, 69, 80, 2);
        }
        text(g, "PHANTOM FIELD", 28, W / 2, 31, WHITE, 0);
        String status;
        Color statusColor = MUTED;
        if (!over && noticeTime > 0) {
            status = notice;
            statusColor = notice.startsWith("10,000") ? GOLD : LILAC;
        } else if (!over && waveWait > 0) {
            status = "NEXT WAVE " + (wave + 1);
        } else if (!over && waveBanner > 0) {
            status = "WAVE " + wave + " / CONTACTS INBOUND";
            statusColor = LILAC;
        } else {
            status = "WAVE " + Math.max(1, wave) + " / "
                    + (rocks.size() + ghosts.size() + pendingGhosts) + " CONTACTS";
        }
        text(g, status, 18, W / 2, 60, statusColor, 0);

        text(g, extraLife ? "EXTRA LIFE CLAIMED" : "1UP AT 10,000", 17,
                998, 25, extraLife ? GOLD : LILAC, 1);
        for (int i = 0; i < Math.min(lives, 5); i++) {
            AffineTransform saved = g.getTransform();
            g.translate(984 - i * 31, 53);
            g.rotate(-Math.PI / 2);
            g.scale(0.65, 0.65);
            g.setColor(extraLife && lifePulse > 0 ? GOLD : WHITE);
            g.setStroke(BRIGHT);
            g.draw(SHIP);
            g.setTransform(saved);
        }
        if (!extraLife && player != null) {
            g.setColor(BORDER);
            g.fillRect(852, 70, 145, 2);
            g.setColor(VIOLET);
            g.fillRect(852, 70, (int) (145 * Math.min(1, score / 10000.0)), 2);
        }
        text(g, "W THRUST   A / D TURN   S BRAKE   CLICK / HOLD SPACE FIRE",
                19, W / 2, 563, LILAC, 0);
    }

    private void paintAttract(Graphics2D g) {
        // Text, not a tall opaque card: the entire live field remains visible.
        boolean scores = clock % 14 >= 8 && !leaders.isEmpty();
        if (scores) {
            text(g, "LOST SIGNALS / BEST RUNS", 21, 31, 112, LILAC, -1);
            for (int i = 0; i < Math.min(3, leaders.size()); i++) {
                Host.Score s = leaders.get(i);
                Color c = i == 0 ? GOLD : WHITE;
                text(g, (i + 1) + ". " + shortName(s.name(), 14),
                        19, 32, 143 + i * 27, c, -1);
                text(g, Integer.toString(s.score()), 19, 335, 143 + i * 27, c, 1);
            }
        } else {
            text(g, "PHANTOM", 42, 30, 128, WHITE, -1);
            text(g, "FIELD", 51, 28, 177, LILAC, -1);
            text(g, "THE VOID IS NOT EMPTY.", 18, 32, 207, MUTED, -1);
        }

        text(g, "SHATTER. EVADE. SURVIVE.", 20, 993, 110, WHITE, 1);
        text(g, "Phantoms warn, then charge.", 17, 993, 137, LILAC, 1);
        text(g, "Three lives. Endless waves.", 17, 993, 163, MUTED, 1);

        // Narrow invitation strip instead of hiding a third of the demo.
        g.setColor(new Color(13, 9, 26, 215));
        g.fillRoundRect(311, 474, 402, 46, 10, 10);
        g.setColor(BORDER);
        g.setStroke(THIN);
        g.drawRoundRect(311, 474, 402, 46, 10, 10);
        text(g, "SIT TO ENTER THE FIELD", 25, W / 2, 505,
                Math.sin(clock * 3) > -0.45 ? WHITE : LILAC, 0);
        text(g, "WRAP / NO SAFE EDGES", 15, 993, 515, MUTED, 1);
    }

    private void paintOver(Graphics2D g) {
        panel(g, 241, 143, 542, 322);
        text(g, "SIGNAL LOST", 49, W / 2, 206, WHITE, 0);
        text(g, shortName(player.name(), 25), 21, W / 2, 242, LILAC, 0);
        text(g, Integer.toString(score), 58, W / 2, 311, WHITE, 0);
        text(g, "WAVE " + wave + " REACHED", 20, W / 2, 346, MUTED, 0);
        text(g, rank > 0 ? "HIGH SCORE RECORD #" + rank : "THE FIELD REMEMBERS.",
                22, W / 2, 389, rank > 0 ? GOLD : LILAC, 0);
        if (overTime > 1.1) {
            text(g, "CLICK OR SPACE TO RECONSTRUCT", 21, W / 2, 440,
                    Math.sin(clock * 3) > -0.5 ? WHITE : VIOLET, 0);
        }
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(PANEL);
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setStroke(CORE);
        g.setColor(BORDER);
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setColor(LILAC);
        g.fillRect(x + 14, y, 47, 2);
        g.fillRect(x + w - 61, y + h - 1, 47, 2);
    }

    private static void glow(Graphics2D g, Shape shape, int color, BasicStroke core) {
        g.setStroke(HALO);
        g.setColor(INK[color][10]);
        g.draw(shape);
        g.setStroke(core);
        g.setColor(INK[color][63]);
        g.draw(shape);
    }

    private static void text(Graphics2D g, String s, int size,
                             double x, double y, Color color, int align) {
        // Both foreground and shadow fade; fully transparent text paints nothing.
        if (s == null || color.getAlpha() == 0) return;
        g.setFont(FONT[Math.max(1, Math.min(80, size))]);
        int width = g.getFontMetrics().stringWidth(s);
        float left = (float) (align < 0 ? x : align > 0 ? x - width : x - width / 2.0);
        int a = color.getAlpha();
        g.setColor(a == 255 ? BLACK : new Color(3, 2, 8, a));
        g.drawString(s, left - 1, (float) y);
        g.drawString(s, left + 1, (float) y);
        g.drawString(s, left, (float) y - 1);
        g.drawString(s, left, (float) y + 2);
        g.setColor(color);
        g.drawString(s, left, (float) y);
    }

    private static Color ink(int color, double opacity) {
        return INK[color][(int) clamp(Math.round(opacity * 63), 0, 63)];
    }

    private static String shortName(String name, int max) {
        if (name == null) return "UNKNOWN";
        return name.length() <= max ? name : name.substring(0, max - 1) + "\u2026";
    }

    private static boolean visible(double x, double y, double radius) {
        return x + radius >= 0 && x - radius <= W
                && y + radius >= 0 && y - radius <= FIELD;
    }

    private static double wrap(double value, double size) {
        return value - Math.floor(value / size) * size;
    }

    private static double delta(double from, double to, double size) {
        return wrap(to - from + size * 0.5, size) - size * 0.5;
    }

    private static double distance(double ax, double ay, double bx, double by) {
        return Math.hypot(delta(ax, bx, W), delta(ay, by, FIELD));
    }

    private static boolean near(double ax, double ay, double bx, double by, double radius) {
        double dx = delta(ax, bx, W), dy = delta(ay, by, FIELD);
        return dx * dx + dy * dy < radius * radius;
    }

    private static double angleDelta(double a) {
        return wrap(a + Math.PI, TAU) - Math.PI;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Path2D makeShip() {
        Path2D p = new Path2D.Double();
        p.moveTo(21, 0);
        p.lineTo(-14, -13);
        p.lineTo(-7, 0);
        p.lineTo(-14, 13);
        p.closePath();
        return p;
    }

    private static Path2D[] makeGhosts() {
        Path2D[] paths = new Path2D[4];
        for (int i = 0; i < paths.length; i++) {
            double f = Math.sin(i * Math.PI / 2) * 4;
            Path2D p = new Path2D.Double();
            p.moveTo(-22, 18);
            p.lineTo(-22, -3);
            p.curveTo(-22, -34, 22, -34, 22, -3);
            p.lineTo(22, 18 + f);
            p.lineTo(12, 11);
            p.lineTo(3, 27 - f);
            p.lineTo(-5, 13);
            p.lineTo(-14, 25 + f);
            p.closePath();
            paths[i] = p;
        }
        return paths;
    }

    private final class Rock {
        double x, y, vx, vy, angle, spin;
        final int size;
        final double radius;
        final Path2D path = new Path2D.Double();
        final Path2D seam = new Path2D.Double();

        Rock(double x, double y, int size, double direction) {
            this.x = x;
            this.y = y;
            this.size = size;
            radius = size == 3 ? 48 : size == 2 ? 28 : 16;
            double speed = (size == 3 ? 31 : size == 2 ? 57 : 89)
                    + random.nextDouble() * 22 + Math.min(50, wave * 3);
            vx = Math.cos(direction) * speed;
            vy = Math.sin(direction) * speed;
            angle = random.nextDouble() * TAU;
            spin = (random.nextDouble() - 0.5) * (size == 1 ? 2.2 : 0.85);
            int vertices = 11 + random.nextInt(3);
            for (int i = 0; i < vertices; i++) {
                double a = i * TAU / vertices;
                double radiusAt = radius * (0.69 + random.nextDouble() * 0.31);
                double px = Math.cos(a) * radiusAt, py = Math.sin(a) * radiusAt;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.closePath();
            seam.moveTo(-radius * 0.72, -radius * 0.31);
            seam.lineTo(-radius * 0.1, -radius * 0.14);
            seam.lineTo(radius * 0.15, radius * 0.25);
            seam.lineTo(radius * 0.65, radius * 0.46);
            seam.moveTo(-radius * 0.1, -radius * 0.14);
            seam.lineTo(radius * 0.11, -radius * 0.66);
        }
    }

    private static final class Phantom {
        double x, y, vx, vy, time, phase, age, flash, chargeAngle, trail;
        int hp = 2;
        boolean charging, warning;
    }

    private static final class Bullet {
        double x, y, vx, vy, dx, dy, life;
    }

    private static final class Particle {
        double x, y, vx, vy, life, max, size;
        int color;
    }

    private static final class Ring {
        double x, y, radius, life = 0.65;
        int color;
    }

    private static final class Popup {
        double x, y, life = 1.0;
        String text;
        int color;
    }
}

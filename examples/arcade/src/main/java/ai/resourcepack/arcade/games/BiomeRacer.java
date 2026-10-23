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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A three-lap, pseudo-3D minecart racer.
 * Mouse/A/D steer, W accelerates, S brakes, space/right click boosts.
 *
 * High scores encode the fastest completed lap as 180000 - milliseconds.
 * The best completed lap is submitted once at race end or departure.
 */
public final class BiomeRacer implements Scene {
    private static final int W = 1024, H = 576;
    private static final int SEGMENTS = 360, VIEW = 124;
    private static final double SEG = 210, TRACK = SEGMENTS * SEG;
    private static final double ROAD = 1500, DEPTH = 0.88;
    private static final double BACK = 1000, CAMERA_H = 1144, HORIZON = 210;
    private static final double MAX_SPEED = 4900, BOOST_SPEED = 6250;
    private static final int SCORE_BASE = 180000;

    private static final Color INK = new Color(0x102230);
    private static final Color WHITE = new Color(0xF3FAFF);
    private static final Color CYAN = new Color(0x78EFF2);
    private static final Color GOLD = new Color(0xFFDB81);
    private static final Color RED = new Color(0xFF7875);
    private static final Color MUTED = new Color(0xB9D0DC);
    private static final Color PANEL = new Color(9, 25, 37, 235);
    private static final Color BORDER = new Color(116, 217, 228, 125);
    private static final Color BAR = new Color(0x334C5B);
    private static final Color SHADOW = new Color(0, 0, 0, 65);
    private static final Color[] SKY = {
            new Color(0x78BFD8), new Color(0xDEA172), new Color(0x88B0D2)
    };
    private static final Color[] HAZE = {
            new Color(0xDEEBD0), new Color(0xFFE1AE), new Color(0xEEF8FF)
    };
    private static final Color[] GROUND = {
            new Color(0x579A4D), new Color(0xDDB77A), new Color(0xDFEAF2)
    };
    private static final Color[] GROUND_ALT = {
            new Color(0x539649), new Color(0xD8B173), new Color(0xD9E6EF)
    };
    private static final Color[] ASPHALT = {
            new Color(0x52626D), new Color(0x706B69), new Color(0x698494)
    };
    private static final Color[] ASPHALT_ALT = {
            new Color(0x50606B), new Color(0x6E6967), new Color(0x678292)
    };
    private static final Color[] MOUNTAIN = {
            new Color(0x69968A), new Color(0xB78062), new Color(0x93AEC5)
    };
    private static final Color[] RIVAL_COLORS = {
            new Color(0xFFA75E), new Color(0xC0A3EF), new Color(0xF08094)
    };
    private static final String[] BIOMES = {
            "EMERALD WOODS", "REDSTONE DUNES", "FROSTPEAK PASS"
    };

    private static final class Projection {
        int index, clip;
        double x1, x2, y1, y2, w1, w2, scale;
        boolean valid, visible;
    }

    private static final class Rival {
        double z, lane, speed;
        int color;
    }

    private static final class Particle {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
    }

    private final double[] heights = new double[SEGMENTS + 1];
    private final double[] curves = new double[SEGMENTS];
    private final Projection[] road = new Projection[VIEW];
    private final ArrayList<Rival> rivals = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Random random = new Random(84231);
    private final Font[] fonts = new Font[90];
    private final int[] px = new int[4], py = new int[4];
    private final BufferedImage frame =
            new BufferedImage(512, 288, BufferedImage.TYPE_INT_RGB);
    private final BufferedImage[][] plants = new BufferedImage[3][3];
    private final BufferedImage[] carts = new BufferedImage[3];
    private final BufferedImage[] backgrounds = new BufferedImage[3];

    private Host host;
    private Seat driver;
    private boolean up, down, left, right, jump;
    private boolean over, won, submitted;
    private double position, lateral, target, speed, lean;
    private double clock, countdown, lapTime, raceTime, remaining;
    private double bestLap, lastLap, resultTime, grace;
    private double boost, charge, shake, hitFlash, lapFlash;
    private double bannerTime, passTime, dustTimer, engineTimer, recordTimer;
    private String banner = "";
    private int laps, biome, passes, rank;
    private List<Host.Score> records = List.of();

    public BiomeRacer() {
        for (int i = 0; i < fonts.length; i++) {
            fonts[i] = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(1, i));
        }
        for (int i = 0; i < VIEW; i++) road[i] = new Projection();

        for (int i = 0; i <= SEGMENTS; i++) {
            double a = i * Math.PI * 2 / SEGMENTS;
            heights[i] = 840 * Math.sin(a * 2)
                    + 360 * Math.sin(a * 4) + 170 * Math.sin(a * 7);
            if (i < SEGMENTS) {
                double ramp = Math.min(1, Math.min(i, SEGMENTS - i) / 20.0);
                curves[i] = ramp * (0.83 * Math.sin(a * 3)
                        + 0.32 * Math.sin(a * 7));
            }
        }
        heights[SEGMENTS] = heights[0];
        buildArt();
    }

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        this.host = host;
        if (host == null) return;
        List<Seat> seats = host.seats();
        driver = seats == null || seats.isEmpty() ? null : seats.get(0);
        refreshRecords();
        reset();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || driver != null) return;
        driver = seat;
        reset();
        sound("block.note_block.chime", 0.5f, 1.2f);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        submit();
        driver = null;
        reset();
    }

    private boolean owns(Seat seat) {
        return host != null && driver != null && seat != null
                && driver.id().equals(seat.id());
    }

    private void reset() {
        random.setSeed(84231);
        position = lateral = target = lean = 0;
        speed = driver == null ? MAX_SPEED : 0;
        countdown = driver == null ? 0 : 1.2;
        up = down = left = right = jump = false;
        over = won = submitted = false;
        laps = biome = passes = rank = 0;
        lapTime = raceTime = lastLap = resultTime = 0;
        remaining = 44;
        bestLap = Double.POSITIVE_INFINITY;
        boost = grace = shake = hitFlash = lapFlash = passTime = 0;
        charge = 1;
        dustTimer = engineTimer = 0;
        banner = BIOMES[0];
        bannerTime = driver == null ? 0 : 2.5;
        particles.clear();
        rivals.clear();

        for (int i = 0; i < 13; i++) {
            Rival r = new Rival();
            // Two visible opponents establish the race immediately.
            r.z = 4800 + i * (TRACK - 6500) / 13;
            r.lane = (i % 3 - 1) * 0.61;
            r.speed = 1900 + (i % 4) * 190;
            r.color = i % 3;
            rivals.add(r);
        }
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        if (!owns(seat) || host.width() <= 0) return;
        target = clamp((x / (double) host.width() * 2 - 1) * 1.19, -1.32, 1.32);
    }

    @Override
    public void click(Seat seat, int x, int y, boolean rightClick) {
        if (!owns(seat)) return;
        if (over) {
            if (resultTime > 1) reset();
        } else if (rightClick) {
            activateBoost();
        }
    }

    @Override
    public void key(Seat seat, Key key, boolean pressed) {
        if (!owns(seat) || key == null) return;
        switch (key) {
            case UP -> up = pressed;
            case DOWN -> down = pressed;
            case LEFT -> left = pressed;
            case RIGHT -> right = pressed;
            case JUMP -> {
                boolean edge = pressed && !jump;
                jump = pressed;
                if (edge && over && resultTime > 1) {
                    reset();
                    jump = true;
                } else if (edge) {
                    activateBoost();
                }
            }
            default -> { }
        }
    }

    private void activateBoost() {
        if (over || countdown > 0 || charge < 0.999 || down) return;
        charge = 0;
        boost = 2.1;
        shake = 1.4;
        banner = "REDSTONE OVERDRIVE";
        bannerTime = 1.1;
        burst(CYAN, 22);
        sound("entity.firework_rocket.launch", 0.45f, 1.3f);
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(0.1, dt);
        clock += dt;
        bannerTime = Math.max(0, bannerTime - dt);
        passTime = Math.max(0, passTime - dt);
        hitFlash = Math.max(0, hitFlash - dt);
        lapFlash = Math.max(0, lapFlash - dt);
        shake = Math.max(0, shake - 12 * dt);
        updateParticles(dt);

        recordTimer -= dt;
        if (recordTimer <= 0) {
            refreshRecords();
            recordTimer = 5;
        }

        if (over) {
            resultTime += dt;
            speed = Math.max(0, speed - 3500 * dt);
            return;
        }

        if (countdown > 0) {
            double old = countdown;
            countdown = Math.max(0, countdown - dt);
            if (old > 0.6 && countdown <= 0.6) {
                sound("block.note_block.bit", 0.4f, 0.9f);
            }
            if (countdown == 0) {
                // Respect a held brake rather than jumping to a launch speed.
                speed = down ? 0 : 1300;
                banner = "GO!  HOLD W FOR FULL POWER";
                bannerTime = 1.6;
                sound("block.note_block.bit", 0.55f, 1.8f);
            }
            return;
        }

        if (driver == null && charge >= 1 && Math.abs(curveAt(position)) < 0.35) {
            activateBoost();
        }

        int steps = Math.max(1, (int) Math.ceil(dt / 0.016));
        for (int i = 0; i < steps && !over; i++) simulate(dt / steps);

        if (!over && speed > 650) {
            dustTimer -= dt;
            if (dustTimer <= 0) {
                dustTimer = boost > 0 || Math.abs(lateral) > 1 ? 0.035 : 0.10;
                for (int side = -1; side <= 1; side += 2) {
                    addParticle(512 + side * 43, 498,
                            side * (35 + random.nextDouble() * 35),
                            80 + random.nextDouble() * 70,
                            boost > 0 ? CYAN : HAZE[biome],
                            boost > 0 ? 9 : 5, 0.35);
                }
            }
        }

        engineTimer -= dt;
        if (driver != null && engineTimer <= 0 && speed > 1800 && !over) {
            engineTimer = 0.95;
            if (up && boost <= 0 && grace <= 0) {
                sound("block.note_block.bass", 0.045f,
                        (float) (0.7 + speed / MAX_SPEED * 0.65));
            }
        }
    }

    private void simulate(double dt) {
        grace = Math.max(0, grace - dt);
        boost = Math.max(0, boost - dt);
        if (boost == 0) charge = Math.min(1, charge + dt / 8.5);

        boolean braking = driver != null && down;
        if (braking) {
            speed -= 2900 * dt;
            boost = 0;
        } else if (driver == null || up || boost > 0) {
            speed += (boost > 0 ? 4300 : 2550) * dt;
        } else {
            speed += clamp(MAX_SPEED * 0.64 - speed, -220, 1050) * dt;
        }

        if (Math.abs(lateral) > 1.03 && speed > 2100) speed -= 3800 * dt;
        double limit = boost > 0 ? BOOST_SPEED : MAX_SPEED;
        if (speed > limit) speed = Math.max(limit, speed - 2100 * dt);
        speed = clamp(speed, 0, BOOST_SPEED);

        double wanted;
        if (driver == null) {
            wanted = autopilot();
        } else {
            if (left || right) {
                target = clamp(target + ((right ? 1 : 0) - (left ? 1 : 0))
                        * dt * 1.65, -1.32, 1.32);
            }
            wanted = target;
        }

        double steering = clamp((wanted - lateral) * 5, -2.7, 2.7);
        double ratio = speed / MAX_SPEED;
        double drift = curveAt(position) * ratio * ratio * 0.25;
        lateral = clamp(lateral + (steering - drift) * Math.min(1, speed / 650) * dt,
                -1.5, 1.5);
        lean += (clamp(steering * 0.5, -1, 1) - lean) * Math.min(1, dt * 10);

        double old = position;
        double movement = speed * dt;
        position += movement;

        for (Rival r : rivals) {
            double before = signed(r.z - old);
            r.z = wrap(r.z + r.speed * dt);
            double after = signed(r.z - position);
            double separation = Math.abs(lateral - r.lane);

            if (grace <= 0 && speed > r.speed + 150
                    && before > -100 && before < 1100 && after < 180
                    && separation < 0.255) {
                speed = Math.max(1000, r.speed * 0.82);
                boost = 0;
                grace = 1.35;
                shake = 5;
                hitFlash = 0.22;
                banner = "CART BUMP!  FIND A CLEAR LANE";
                bannerTime = 1.2;
                burst(GOLD, 28);
                sound("entity.generic.explode", 0.25f, 1.5f);
            } else if (before > 0 && after <= 0 && before < 1100
                    && speed > r.speed && grace <= 0 && separation >= 0.255) {
                passes++;
                charge = Math.min(1, charge + 0.12);
                passTime = 0.9;
                if (driver != null) remaining += 0.7;
                sound("entity.experience_orb.pickup", 0.25f, 1.55f);
            }
        }

        lapTime += dt;
        raceTime += dt;
        if (driver != null) remaining -= dt;

        if (position >= TRACK) {
            double remainder = movement > 0 ? dt * (position - TRACK) / movement : 0;
            position -= TRACK;
            lastLap = lapTime - remainder;
            boolean best = lastLap < bestLap;
            bestLap = Math.min(bestLap, lastLap);
            laps++;

            if (driver != null && laps == 3) {
                raceTime -= remainder;
                lapTime = lastLap;
                finish(true);
                return;
            }

            biome = laps % 3;
            lapTime = remainder;
            charge = 1;
            if (driver != null) remaining += 27;
            banner = driver == null ? BIOMES[biome]
                    : (best ? "BEST LAP " : "LAP ") + time(lastLap) + "  /  +27s";
            bannerTime = 3;
            lapFlash = 0.55;
            burst(GOLD, 35);
            sound("entity.player.levelup", 0.5f, 1.2f);
        }

        if (driver != null && remaining <= 0) {
            remaining = 0;
            finish(false);
        }
    }

    private double autopilot() {
        double bestTarget = 0, bestValue = -Double.MAX_VALUE;
        for (int lane = -1; lane <= 1; lane++) {
            double candidate = lane * 0.61;
            double value = -Math.abs(candidate - lateral) * 2;
            for (Rival r : rivals) {
                double distance = signed(r.z - position);
                if (distance > -400 && distance < 6200
                        && Math.abs(candidate - r.lane) < 0.4) {
                    value -= (6200 - Math.max(0, distance)) / 900;
                }
            }
            if (value > bestValue) {
                bestValue = value;
                bestTarget = candidate;
            }
        }
        return bestTarget + curveAt(position) * 0.055;
    }

    private void finish(boolean success) {
        if (over) return;
        over = true;
        won = success;
        resultTime = 0;
        boost = 0;
        up = down = left = right = false;
        submit();
        sound(success ? "ui.toast.challenge_complete" : "block.note_block.didgeridoo",
                0.55f, success ? 1.15f : 0.65f);
        if (success) burst(GOLD, 65);
    }

    private void submit() {
        if (submitted || driver == null || host == null) return;
        submitted = true;
        if (Double.isFinite(bestLap)) {
            rank = host.score(driver,
                    Math.max(1, SCORE_BASE - (int) Math.round(bestLap * 1000)));
            refreshRecords();
        }
    }

    private void refreshRecords() {
        if (host == null) return;
        List<Host.Score> list = host.scores();
        records = list == null ? List.of() : new ArrayList<>(list);
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && driver != null) {
            host.sound(id, volume, (float) clamp(pitch, 0.5, 2));
        }
    }

    private void updateParticles(double dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 235 * dt;
            }
        }
    }

    private void burst(Color color, int count) {
        for (int i = 0; i < count; i++) {
            addParticle(512 + random.nextDouble() * 90 - 45, 472,
                    random.nextDouble() * 460 - 230,
                    -90 - random.nextDouble() * 240,
                    i % 3 == 0 ? WHITE : color,
                    4 + random.nextInt(5), 0.5 + random.nextDouble() * 0.5);
        }
    }

    private void addParticle(double x, double y, double vx, double vy,
                             Color color, int size, double life) {
        if (particles.size() >= 150) return;
        Particle p = new Particle();
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.color = color;
        p.size = size;
        p.life = p.max = life;
        particles.add(p);
    }

    private void project() {
        double cameraZ = position - BACK;
        double cameraY = heightAt(position) + CAMERA_H;
        long base = (long) Math.floor(cameraZ / SEG);
        double fraction = cameraZ / SEG - base;
        int baseIndex = (int) Math.floorMod(base, (long) SEGMENTS);
        double x = 0, dx = -curves[baseIndex] * fraction * 11;
        int clip = H;

        for (int n = 0; n < VIEW; n++) {
            Projection p = road[n];
            long absolute = base + n;
            int index = (int) Math.floorMod(absolute, (long) SEGMENTS);
            double z1 = absolute * SEG - cameraZ;
            double z2 = z1 + SEG;
            double nextX = x + dx;
            p.index = index;
            p.clip = clip;
            p.valid = z1 > 35;
            p.visible = false;

            if (p.valid) {
                double s1 = DEPTH / z1, s2 = DEPTH / z2;
                p.scale = s1;
                p.x1 = W * 0.5 + s1 * (x - lateral * ROAD) * W * 0.5;
                p.x2 = W * 0.5 + s2 * (nextX - lateral * ROAD) * W * 0.5;
                p.y1 = HORIZON - s1 * (heights[index] - cameraY) * H * 0.5;
                p.y2 = HORIZON - s2 * (heights[index + 1] - cameraY) * H * 0.5;
                p.w1 = s1 * ROAD * W * 0.5;
                p.w2 = s2 * ROAD * W * 0.5;
                p.visible = p.y1 > p.y2 && p.y2 < clip;
                if (p.visible) {
                    clip = Math.max(0, Math.min(clip, (int) Math.ceil(p.y2)));
                }
            }
            x = nextX;
            dx += curves[index] * 11;
        }
    }

    @Override
    public void draw(Graphics2D graphics) {
        if (host == null) {
            graphics.setColor(INK);
            graphics.fillRect(0, 0, cols() * 128, rows() * 128);
            return;
        }
        project();

        Graphics2D low = frame.createGraphics();
        low.scale(0.5, 0.5);
        low.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        low.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        drawBackground(low);
        Graphics2D world = (Graphics2D) low.create();
        world.translate(Math.sin(clock * 91) * shake,
                Math.cos(clock * 73) * shake * 0.35);
        drawRoad(world);
        drawScenery(world);
        drawSpeed(world);
        drawPlayer(world);
        drawParticles(world);
        world.dispose();
        low.dispose();

        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(host.width() / (double) W, host.height() / (double) H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, 0, 0, W, H, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        if (lapFlash > 0 || hitFlash > 0) {
            Color c = hitFlash > 0 ? RED : GOLD;
            int alpha = (int) Math.min(70, hitFlash * 190 + lapFlash * 80);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
            g.fillRect(0, 0, W, H);
        }

        drawHud(g);
        if (driver == null) {
            drawAttract(g);
        } else if (over) {
            drawResults(g);
        } else if (countdown > 0) {
            panel(g, 315, 154, 394, 162);
            text(g, "READY TO ROLL", 27, 512, 191, CYAN, 0);
            text(g, countdown > 0.6 ? "READY" : "SET", 59, 512, 253, WHITE, 0);
            text(g, "Mouse steers. Hold W for power.", 20, 512, 291, GOLD, 0);
        } else {
            if (bannerTime > 0) {
                panel(g, 232, 91, 560, 43);
                text(g, banner, 21, 512, 120, GOLD, 0);
            }
            if (passTime > 0) {
                text(g, "CLEAN PASS  +BOOST  +0.7s", 23, 512, 340, CYAN, 0);
            }
            if (Math.abs(lateral) > 1.03) {
                text(g, lateral > 0 ? "<< RETURN TO ROAD" : "RETURN TO ROAD >>",
                        22, 512, 372, GOLD, 0);
            }
        }
        g.dispose();
    }

    private void drawBackground(Graphics2D g) {
        g.drawImage(backgrounds[biome], 0, 0, W, H, null);

        g.setColor(new Color(255, 255, 255, 145));
        double scroll = (clock * 5 + lateral * 15) % 250;
        for (int i = -1; i < 6; i++) {
            int x = (int) (i * 250 - scroll);
            int y = 100 + Math.floorMod(i * 37, 62);
            g.fillRect(x, y, 102, 14);
            g.fillRect(x + 23, y - 13, 56, 14);
        }

        int drift = (int) (Math.sin(position / TRACK * Math.PI * 2) * 48 - lateral * 17);
        for (int i = -1; i < 9; i++) {
            int x = i * 160 + drift;
            int peak = 173 + Math.floorMod(i * 37, 59);
            g.setColor(MOUNTAIN[biome]);
            polygon(g, x - 35, 285, x + 40, peak,
                    x + 87, peak + 12, x + 182, 285);
            if (biome == 2) {
                g.setColor(HAZE[2]);
                polygon(g, x + 10, peak + 43, x + 40, peak,
                        x + 87, peak + 12, x + 110, peak + 45);
            } else if (biome == 1) {
                g.setColor(HAZE[1]);
                g.fillRect(x + 42, peak + 13, 40, 5);
            }
        }
        g.setColor(GROUND[biome]);
        g.fillRect(0, 278, W, H - 278);
    }

    private void drawRoad(Graphics2D g) {
        Shape original = g.getClip();
        for (Projection p : road) {
            if (!p.valid || !p.visible || p.clip <= 0) continue;
            g.setClip(original);
            g.clipRect(0, 0, W, p.clip);

            boolean stripe = (p.index / 4 & 1) == 0;
            int top = (int) Math.floor(Math.max(-4096, p.y2));
            int bottom = (int) Math.ceil(Math.min(H + 4096, p.y1));
            g.setColor(stripe ? GROUND[biome] : GROUND_ALT[biome]);
            if (bottom > top) g.fillRect(0, top, W, bottom - top + 1);

            // All trapezoids share the same projected vertices; no expanded
            // endpoints bending the lane markings at every segment boundary.
            quad(g, p.x1, p.y1, p.w1 * 1.13, p.x2, p.y2, p.w2 * 1.13, HAZE[biome]);
            quad(g, p.x1, p.y1, p.w1 * 1.055, p.x2, p.y2, p.w2 * 1.055,
                    stripe ? WHITE : RED);
            quad(g, p.x1, p.y1, p.w1, p.x2, p.y2, p.w2,
                    stripe ? ASPHALT[biome] : ASPHALT_ALT[biome]);

            // Drop subpixel dashes rather than rendering a sparkling dotted horizon.
            if (p.w2 > 48 && p.index % 12 < 7) {
                for (int lane = -1; lane <= 1; lane += 2) {
                    quad(g, p.x1 + p.w1 * lane / 3, p.y1, p.w1 * 0.011,
                            p.x2 + p.w2 * lane / 3, p.y2, p.w2 * 0.011, WHITE);
                }
            }

            if (p.index < 2) {
                for (int tile = 0; tile < 12; tile++) {
                    double a = -1 + tile / 6.0, b = a + 1.0 / 6;
                    g.setColor((tile + p.index) % 2 == 0 ? WHITE : INK);
                    polygon(g, p.x1 + p.w1 * a, p.y1,
                            p.x1 + p.w1 * b, p.y1,
                            p.x2 + p.w2 * b, p.y2,
                            p.x2 + p.w2 * a, p.y2);
                }
            }
        }
        g.setClip(original);
    }

    private void drawScenery(Graphics2D g) {
        Shape original = g.getClip();
        for (int n = VIEW - 1; n >= 0; n--) {
            Projection p = road[n];
            if (!p.valid || p.clip <= 0) continue;
            g.setClip(original);
            g.clipRect(0, 0, W, p.clip);

            // Unequal spacing, variants, and alternate sides break the tree tunnel.
            for (int side = -1; side <= 1; side += 2) {
                int hash = Math.floorMod(p.index * 1709 + side * 83, 997);
                if (hash % 7 < 2) {
                    int variant = hash % 3;
                    double size = p.scale * W * 0.5 * (850 + hash % 470);
                    double distance = 1.42 + (hash % 11) * 0.105;
                    sprite(g, plants[biome][variant],
                            p.x1 + side * p.w1 * distance, p.y1,
                            size * 0.8, size);
                }
            }

            if (p.index % 12 == 0) {
                double h = Math.min(1200, p.scale * W * 0.5 * 300);
                if (h > 3 && p.y1 - h < p.clip) {
                    for (int side = -1; side <= 1; side += 2) {
                        int x = pixel(p.x1 + side * p.w1 * 1.15);
                        int y = pixel(p.y1 - h);
                        int w = Math.max(2, (int) (h * 0.14));
                        g.setColor(WHITE);
                        g.fillRect(x - w / 2, y, w, (int) h);
                        g.setColor(biome == 2 ? CYAN : RED);
                        g.fillRect(x - w / 2, y, w, Math.max(2, (int) (h * 0.3)));
                    }
                }
            }

            // Low blocky flowers / stones provide near-ground motion cues.
            if (p.index % 5 == 0 && p.w1 > 35) {
                int side = (p.index & 1) == 0 ? -1 : 1;
                double x = p.x1 + side * p.w1 * 1.26;
                int size = Math.max(2, (int) (p.w1 * 0.025));
                g.setColor(biome == 0 ? GOLD : biome == 1 ? MOUNTAIN[1] : CYAN);
                g.fillRect(pixel(x), pixel(p.y1) - size, size * 2, size);
            }

            for (Rival r : rivals) {
                int segment = (int) (r.z / SEG);
                if (segment != p.index) continue;
                double f = r.z / SEG - segment;
                double width = lerp(p.w1, p.w2, f);
                double x = lerp(p.x1, p.x2, f) + r.lane * width;
                double y = lerp(p.y1, p.y2, f);
                sprite(g, carts[r.color], x, y, width * 0.32, width * 0.32);
            }
        }
        g.setClip(original);
    }

    private void sprite(Graphics2D g, BufferedImage image, double x, double ground,
                        double width, double height) {
        if (width < 2 || width > 3200 || height > 4000) return;
        if (x + width * 0.5 < 0 || x - width * 0.5 > W || ground - height > H) return;
        g.drawImage(image, pixel(x - width * 0.5), pixel(ground - height),
                Math.max(1, (int) width), Math.max(1, (int) height), null);
    }

    private void drawSpeed(Graphics2D g) {
        if (speed < MAX_SPEED * 0.9) return;
        g.setColor(boost > 0 ? new Color(120, 246, 255, 160)
                : new Color(255, 255, 255, 85));
        g.setStroke(new BasicStroke(boost > 0 ? 3 : 2));
        int amount = boost > 0 ? 18 : 8;
        for (int i = 0; i < amount; i++) {
            double f = (clock * (boost > 0 ? 2 : 1.1) + i / (double) amount) % 1;
            int side = i % 2 == 0 ? -1 : 1;
            int x = (int) (512 + side * (200 + f * 340));
            int y = (int) (290 + f * 250);
            g.drawLine(x, y, x + side * (int) (12 + f * 36), y + (int) (13 + f * 27));
        }
    }

    private void drawPlayer(Graphics2D g) {
        double bob = Math.sin(clock * (15 + speed / 170)) * speed / MAX_SPEED;
        if (Math.abs(lateral) > 1.03) bob *= 3;
        double ground = 502 + bob;

        if (boost > 0) {
            int flame = 24 + (int) (Math.sin(clock * 61) * 9);
            g.setColor(new Color(0x278CAE));
            g.fillRect(474, (int) ground - 4, 23, flame);
            g.fillRect(527, (int) ground - 4, 23, flame);
            g.setColor(CYAN);
            g.fillRect(480, (int) ground - 4, 11, flame - 5);
            g.fillRect(533, (int) ground - 4, 11, flame - 5);
            g.setColor(WHITE);
            g.fillRect(484, (int) ground - 4, 4, flame / 2);
            g.fillRect(537, (int) ground - 4, 4, flame / 2);
        }

        Graphics2D cart = (Graphics2D) g.create();
        cart.translate(512, ground);
        cart.shear(-lean * 0.1, 0);
        cart.scale(1.23, 1.23);
        paintCart(cart, grace > 0 && (int) (clock * 15) % 2 == 0 ? WHITE : CYAN,
                driver != null && down);
        cart.dispose();
    }

    private void drawParticles(Graphics2D g) {
        for (Particle p : particles) {
            double f = clamp(p.life / p.max, 0, 1);
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(),
                    p.color.getBlue(), (int) (f * 210)));
            int size = Math.max(2, (int) (p.size * (0.6 + f * 0.4)));
            g.fillRect((int) p.x, (int) p.y, size, size);
        }
    }

    private void drawHud(Graphics2D g) {
        panel(g, 16, 12, 992, 66);
        text(g, driver == null ? "AUTOPILOT" : shortName(driver.name(), 16),
                17, 32, 35, CYAN, -1);
        text(g, "LAP " + (driver == null ? laps % 3 + 1 : Math.min(3, laps + 1)) + " / 3",
                26, 32, 65, WHITE, -1);
        text(g, "LAP TIME", 16, 281, 34, MUTED, 0);
        text(g, time(lapTime), 26, 281, 65, WHITE, 0);
        text(g, "BEST LAP", 16, 487, 34, MUTED, 0);
        text(g, Double.isFinite(bestLap) ? time(bestLap) : "--:--.---",
                26, 487, 65, GOLD, 0);
        text(g, "TIME LEFT", 16, 680, 34, MUTED, 0);
        text(g, driver == null ? "DEMO" : (int) Math.ceil(remaining) + "s",
                27, 680, 65, remaining < 12 && driver != null ? RED : WHITE, 0);
        text(g, "BIOME RACER", 23, 987, 37, WHITE, 1);
        text(g, BIOMES[biome], 16, 987, 63, GOLD, 1);

        panel(g, 20, 425, 218, 110);
        text(g, Integer.toString((int) Math.round(speed / MAX_SPEED * 220)),
                49, 35, 478, WHITE, -1);
        text(g, "KM/H", 18, 221, 474, CYAN, 1);
        String power = driver != null && down ? "BRAKING"
                : boost > 0 ? "OVERDRIVE" : up || driver == null ? "FULL POWER" : "AUTO CRUISE";
        text(g, power, 18, 36, 506, boost > 0 ? GOLD : CYAN, -1);
        meter(g, 36, 518, 184, 6, speed / BOOST_SPEED, boost > 0 ? GOLD : CYAN);

        panel(g, 768, 425, 236, 110);
        text(g, boost > 0 ? "OVERDRIVE!"
                        : charge >= 1 ? "SPACE: BOOST READY" : "BOOST RECHARGING",
                18, 886, 453, charge >= 1 || boost > 0 ? GOLD : MUTED, 0);
        meter(g, 786, 464, 200, 9, boost > 0 ? boost / 2.1 : charge,
                boost > 0 ? CYAN : GOLD);
        text(g, passes + " CLEAN PASSES", 17, 886, 501, WHITE, 0);
        text(g, "Pass carts to earn charge + time", 13, 886, 522, MUTED, 0);

        // Compact bend advisory above, never in front of the player cart.
        double bend = upcomingBend();
        String direction = Math.abs(bend) < 0.24 ? "STRAIGHT"
                : bend > 0 ? "RIGHT BEND  >>" : "<<  LEFT BEND";
        panel(g, 786, 92, 218, 43);
        text(g, direction, 20, 895, 121, GOLD, 0);

        // A three-biome race ribbon replaces the large progress panel.
        for (int b = 0; b < 3; b++) {
            int x = 371 + b * 94;
            g.setColor(BAR);
            g.fillRect(x, 529, 88, 5);
            double progress = b < laps % 3 ? 1 : b == laps % 3 ? position / TRACK : 0;
            if (over && won) progress = 1;
            g.setColor(b == 0 ? CYAN : b == 1 ? GOLD : WHITE);
            g.fillRect(x, 529, (int) (88 * progress), 5);
        }

        g.setColor(INK);
        g.fillRect(0, 547, W, 29);
        text(g, "MOUSE / A D: STEER    W: POWER    S: BRAKE    SPACE / RIGHT CLICK: BOOST",
                17, 512, 567, WHITE, 0);
    }

    private double upcomingBend() {
        double strongest = 0;
        for (int i = 0; i < 8; i++) {
            double c = curveAt(position + 600 + i * 500);
            if (Math.abs(c) > Math.abs(strongest)) strongest = c;
        }
        return strongest;
    }

    private void drawAttract(Graphics2D g) {
        panel(g, 36, 107, 435, 254);
        if (clock % 14 > 8 && !records.isEmpty()) {
            text(g, "FASTEST LAPS", 34, 254, 154, GOLD, 0);
            for (int i = 0; i < Math.min(4, records.size()); i++) {
                Host.Score s = records.get(i);
                int y = 194 + i * 30;
                text(g, (i + 1) + ". " + shortName(s.name(), 12),
                        19, 55, y, WHITE, -1);
                text(g, time(Math.max(0L, (long) SCORE_BASE - s.score()) / 1000.0),
                        20, 450, y, i == 0 ? GOLD : CYAN, 1);
            }
        } else {
            text(g, "BIOME", 59, 58, 173, WHITE, -1);
            text(g, "RACER", 70, 55, 242, CYAN, -1);
            text(g, "3 WORLDS. ONE FAST MINECART.", 19, 58, 280, GOLD, -1);
            text(g, "Pass rivals. Boost. Beat your best lap.", 18, 58, 308, WHITE, -1);
        }
        if (clock % 1.4 < 1.1) {
            text(g, "TAKE A SEAT TO RACE", 23, 254, 344, GOLD, 0);
        }
    }

    private void drawResults(Graphics2D g) {
        panel(g, 221, 120, 582, 299);
        text(g, won ? "CIRCUIT COMPLETE!" : "OUT OF TIME",
                39, 512, 172, won ? GOLD : RED, 0);
        text(g, won ? "THREE WORLDS. CONQUERED."
                        : "Hold W for power. Clean passes earn extra time.",
                20, 512, 209, WHITE, 0);
        text(g, "RACE TIME", 20, 247, 253, MUTED, -1);
        text(g, time(raceTime), 28, 777, 253, WHITE, 1);
        text(g, "BEST LAP", 20, 247, 293, MUTED, -1);
        text(g, Double.isFinite(bestLap) ? time(bestLap) : "NO LAP FINISHED",
                26, 777, 293, GOLD, 1);
        text(g, (rank > 0 ? "LAP RECORD #" + rank + "  /  "
                        : Double.isFinite(bestLap) ? "BEST LAP SAVED  /  " : "")
                        + passes + " CLEAN PASSES",
                20, 512, 335, CYAN, 0);
        if (resultTime > 1 && clock % 1.4 < 1.1) {
            text(g, "CLICK OR SPACE TO RACE AGAIN", 23, 512, 390, WHITE, 0);
        }
    }

    private void buildArt() {
        for (int b = 0; b < 3; b++) {
            backgrounds[b] = new BufferedImage(512, 288, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = backgrounds[b].createGraphics();
            bg.setPaint(new GradientPaint(0, 0, SKY[b], 0, 155, HAZE[b]));
            bg.fillRect(0, 0, 512, 288);
            bg.setColor(new Color(0xFFEAB1));
            bg.fillRect(403, 49, 32, 32);
            bg.setColor(new Color(0xFFF7D8));
            bg.fillRect(409, 55, 20, 20);
            bg.dispose();

            for (int variant = 0; variant < 3; variant++) {
                plants[b][variant] = new BufferedImage(128, 160, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = plants[b][variant].createGraphics();
                g.translate(64, 158);
                paintPlant(g, b, variant);
                g.dispose();
            }
        }
        for (int i = 0; i < 3; i++) {
            carts[i] = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = carts[i].createGraphics();
            g.translate(64, 126);
            paintCart(g, RIVAL_COLORS[i], false);
            g.dispose();
        }
    }

    private void paintPlant(Graphics2D g, int biome, int variant) {
        g.setColor(SHADOW);
        g.fillOval(-45, -6, 90, 8);
        if (biome == 0) {
            if (variant == 2) {
                g.setColor(new Color(0x68746B));
                polygon(g, -40, 0, -28, -28, 23, -35, 42, 0);
                g.setColor(new Color(0x94AA80));
                polygon(g, -40, 0, -28, -28, 23, -35, 13, -15);
                g.setColor(GOLD);
                g.fillRect(-44, -14, 9, 9);
                g.setColor(RED);
                g.fillRect(30, -20, 10, 8);
                return;
            }
            int lift = variant == 1 ? 12 : 0;
            g.setColor(new Color(0x674A31));
            g.fillRect(-9, -92 - lift, 18, 92 + lift);
            g.setColor(variant == 1 ? new Color(0xE1D8BF) : new Color(0xB08354));
            g.fillRect(-9, -92 - lift, 7, 92 + lift);
            if (variant == 1) {
                g.setColor(INK);
                for (int i = 0; i < 4; i++) g.fillRect(-9, -20 - i * 19, 6, 4);
            }
            g.setColor(new Color(0x28563C));
            g.fillRect(-48, -100 - lift, 96, 45);
            g.fillRect(-32, -137 - lift, 69, 49);
            g.setColor(variant == 1 ? new Color(0x6D9C4F) : new Color(0x3F804B));
            g.fillRect(-48, -100 - lift, 80, 33);
            g.fillRect(-32, -137 - lift, 54, 40);
            g.setColor(new Color(0x86B65E));
            g.fillRect(-32, -137 - lift, 54, 10);
            g.fillRect(-48, -100 - lift, 42, 9);
            g.setColor(new Color(0x579550));
            g.fillRect(-19, -121 - lift, 18, 16);
            g.fillRect(6, -88 - lift, 18, 14);
        } else if (biome == 1) {
            if (variant == 2) {
                g.setColor(new Color(0xA9674D));
                g.fillRect(-38, -29, 76, 29);
                g.fillRect(-27, -49, 54, 22);
                g.setColor(new Color(0xD69A6B));
                g.fillRect(-27, -49, 54, 9);
                g.fillRect(-38, -29, 55, 8);
                g.setColor(RED);
                g.fillRect(-10, -39, 10, 8);
                g.fillRect(13, -17, 11, 9);
                return;
            }
            int h = variant == 1 ? 103 : 136;
            g.setColor(new Color(0x397B4F));
            g.fillRect(-13, -h, 28, h);
            g.fillRect(-43, -74, 32, 17);
            g.fillRect(-43, -105, 16, 48);
            g.fillRect(12, -43, 33, 17);
            g.fillRect(30, -82, 15, 56);
            g.setColor(new Color(0x89BA68));
            g.fillRect(-13, -h, 7, h);
            g.fillRect(-43, -105, 5, 42);
            g.fillRect(30, -82, 5, 45);
            g.setColor(new Color(0x285B3D));
            for (int i = 0; i < 6; i++) g.fillRect(4, -19 - i * 16, 4, 7);
            g.setColor(RED);
            g.fillRect(-15, -h - 7, 20, 9);
            g.setColor(GOLD);
            g.fillRect(-8, -h - 12, 6, 8);
        } else {
            if (variant == 2) {
                g.setColor(new Color(0xABCBDC));
                g.fillRect(-42, -23, 81, 23);
                g.fillRect(-28, -36, 53, 17);
                g.setColor(WHITE);
                g.fillRect(-28, -36, 53, 8);
                g.fillRect(-42, -23, 81, 7);
                return;
            }
            int h = variant == 0 ? 145 : 113;
            g.setColor(new Color(0x7DBAD7));
            polygon(g, -41, 0, -25, -h / 2, -8, -h, 9, 0);
            polygon(g, -8, -h, 5, -h, 24, -h / 2, 41, 0);
            g.setColor(new Color(0xDFFBFF));
            polygon(g, -41, 0, -25, -h / 2, -8, -h, -5, 0);
            g.setColor(new Color(0xB2E1F4));
            g.fillRect(7, -69, 9, 58);
            g.setColor(WHITE);
            g.fillRect(-18, -66, 5, 24);
            g.fillRect(23, -38, 5, 17);
            g.fillRect(-39, -5, 79, 5);
        }
    }

    private void paintCart(Graphics2D g, Color body, boolean braking) {
        g.setColor(SHADOW);
        g.fillOval(-56, -10, 112, 13);
        g.setColor(new Color(0x172530));
        g.fillRect(-48, -28, 17, 28);
        g.fillRect(31, -28, 17, 28);
        g.setColor(new Color(0x8BA1AB));
        g.fillRect(-44, -24, 7, 17);
        g.fillRect(37, -24, 7, 17);

        g.setColor(new Color(0xD7E8EC));
        polygon(g, -52, -57, -38, -73, 38, -73, 52, -57);
        g.setColor(new Color(0x334C5B));
        g.fillRect(-37, -67, 74, 25);
        g.setColor(new Color(0x50372A));
        g.fillRect(-18, -107, 36, 36);
        g.setColor(new Color(0xD7A779));
        g.fillRect(-16, -93, 32, 22);
        g.setColor(new Color(0x735039));
        g.fillRect(-18, -107, 36, 14);
        g.fillRect(-18, -96, 8, 14);
        g.setColor(body);
        g.fillRect(-23, -73, 46, 29);

        g.setColor(body.darker());
        polygon(g, -52, -58, 52, -58, 44, -14, -44, -14);
        g.setColor(body);
        polygon(g, -48, -52, 48, -52, 40, -23, -40, -23);
        g.setColor(new Color(0xD8E9EE));
        g.fillRect(-54, -60, 108, 9);
        g.fillRect(-45, -23, 90, 8);
        g.setColor(new Color(0x7897A5));
        g.fillRect(-32, -47, 5, 24);
        g.fillRect(27, -47, 5, 24);

        g.setColor(braking ? new Color(0xFF374B) : new Color(0xBF5960));
        g.fillRect(-40, -37, 12, 9);
        g.fillRect(28, -37, 12, 9);
        if (braking) {
            g.setColor(new Color(0xFFD0BA));
            g.fillRect(-38, -36, 8, 3);
            g.fillRect(30, -36, 8, 3);
        }
        g.setColor(GOLD);
        g.fillRect(-12, -41, 24, 14);
        g.setColor(INK);
        g.fillRect(-4, -38, 8, 8);
    }

    private void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(PANEL);
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1.3f));
        g.drawRoundRect(x, y, w, h, 12, 12);
    }

    private void meter(Graphics2D g, int x, int y, int w, int h, double f, Color color) {
        g.setColor(BAR);
        g.fillRect(x, y, w, h);
        g.setColor(color);
        g.fillRect(x, y, (int) (w * clamp(f, 0, 1)), h);
    }

    private void text(Graphics2D g, String value, int size, int x, int y,
                      Color color, int align) {
        g.setFont(fonts[Math.max(1, Math.min(fonts.length - 1, size))]);
        int width = g.getFontMetrics().stringWidth(value);
        int start = align < 0 ? x : align > 0 ? x - width : x - width / 2;
        g.setColor(SHADOW);
        g.drawString(value, start + 1, y + 2);
        g.setColor(color);
        g.drawString(value, start, y);
    }

    private void quad(Graphics2D g, double x1, double y1, double w1,
                      double x2, double y2, double w2, Color c) {
        g.setColor(c);
        polygon(g, x1 - w1, y1, x1 + w1, y1, x2 + w2, y2, x2 - w2, y2);
    }

    private void polygon(Graphics2D g, double x0, double y0, double x1, double y1,
                         double x2, double y2, double x3, double y3) {
        px[0] = pixel(x0);
        px[1] = pixel(x1);
        px[2] = pixel(x2);
        px[3] = pixel(x3);
        py[0] = pixel(y0);
        py[1] = pixel(y1);
        py[2] = pixel(y2);
        py[3] = pixel(y3);
        g.fillPolygon(px, py, 4);
    }

    private double heightAt(double z) {
        double segment = wrap(z) / SEG;
        int index = Math.min(SEGMENTS - 1, (int) segment);
        return lerp(heights[index], heights[index + 1], segment - index);
    }

    private double curveAt(double z) {
        return curves[Math.min(SEGMENTS - 1, (int) (wrap(z) / SEG))];
    }

    private static int pixel(double n) {
        return (int) Math.round(clamp(n, -50000, 50000));
    }

    private static double wrap(double z) {
        return z - Math.floor(z / TRACK) * TRACK;
    }

    private static double signed(double z) {
        return wrap(z + TRACK * 0.5) - TRACK * 0.5;
    }

    private static double clamp(double n, double low, double high) {
        return Math.max(low, Math.min(high, n));
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static String shortName(String s, int length) {
        if (s == null) return "RACER";
        return s.length() <= length ? s : s.substring(0, length - 1) + "…";
    }

    private static String time(double seconds) {
        long ms = Math.max(0, Math.round(seconds * 1000));
        long sec = ms / 1000 % 60;
        long fraction = ms % 1000;
        return ms / 60000 + ":" + (sec < 10 ? "0" : "") + sec + "."
                + (fraction < 100 ? "0" : "") + (fraction < 10 ? "0" : "") + fraction;
    }
}

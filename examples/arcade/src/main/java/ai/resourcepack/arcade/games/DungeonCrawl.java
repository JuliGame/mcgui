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
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * A procedural, first-person dungeon crawler.
 * World rendering is 384x216; text is rendered at cabinet resolution.
 * No external assets, background threads, or platform-specific APIs.
 */
public final class DungeonCrawl implements Scene {
    private static final int W = 384, H = 216, N = 19, T = 32;
    private static final double PROJECTION = 246;
    private static final int SLIME = 0, GEM = 1, HEART = 2, GATE = 3, SPARK = 4;
    private static final int[] DIRS = {1, -1, N, -N};
    private static final Color WHITE = new Color(0xEFF7EF);
    private static final Color GOLD = new Color(0xFFD18A);
    private static final Color MINT = new Color(0x83EFBB);
    private static final Color RED = new Color(0xFF8890);
    private static final Color MUTED = new Color(0xAFC5C9);
    private static final String[] DEPTH_NAMES = {
            "THE MOSS HALLS", "THE DROWNED VAULT", "THE EMBER CRYPT"
    };

    private Host host;
    private Seat player;
    private boolean ready, over, won;
    private final Random rng = new Random(0xD06E0L);
    private final EnumSet<Key> keys = EnumSet.noneOf(Key.class);

    private final int[][] map = new int[N][N];
    private final boolean[][] seen = new boolean[N][N];
    private final double[][] light = new double[N][N];
    private final int[][] textures = new int[3][T * T];
    private final int[] flow = new int[N * N];
    private final int[] queue = new int[N * N];
    private final ArrayList<Integer> route = new ArrayList<>();
    private final ArrayList<Entity> entities = new ArrayList<>();
    private final ArrayList<Entity> sorted = new ArrayList<>();

    private final BufferedImage frame =
            new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private final int[] pixels =
            ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
    private final double[] walls = new double[W];
    private final double[] depths = new double[W * H];

    private Sprite largeSlime, littleSlime, gemSprite, heartSprite, closedGate, openGate;

    private double x, y, angle, steering;
    private double clock, elapsed, floorTime, endTime, intro;
    private double health = 100, stamina = 100, immunity;
    private double damageFlash, pickupFlash, shake, hitMarker, hitStop;
    private double swordAge = 10, swordCooldown, bob, moving, stepDistance;
    private double navTimer, noticeTime, comboTime;
    private double damageDirection;
    private int floor = 1, score, emeralds, goal, rank, combo;
    private int exitX = 16, exitY = 16, objective = -1;
    private String notice = "";
    private Color noticeColor = MINT;
    private boolean strikePending;
    private Entity target;

    public DungeonCrawl() {}

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        this.host = host;
        if (host == null) return;
        makeTextures();
        makeSprites();
        ready = true;
        List<Seat> seats = host.seats();
        player = seats.isEmpty() ? null : seats.get(0);
        newRun();
    }

    private boolean owns(Seat seat) {
        return ready && seat != null && player != null
                && player.id().equals(seat.id());
    }

    @Override
    public void joined(Seat seat) {
        if (!ready || seat == null || player != null) return;
        player = seat;
        newRun();
        sound("block.note_block.chime", .65f, 1.2f);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        player = null;
        newRun();
    }

    @Override
    public void pointer(Seat seat, int px, int py) {
        if (!owns(seat) || host.width() <= 0) return;
        double p = clamp(2.0 * px / host.width() - 1, -1, 1);
        steering = Math.abs(p) < .19 ? 0
                : Math.copySign(Math.pow((Math.abs(p) - .19) / .81, 1.3), p);
    }

    @Override
    public void click(Seat seat, int px, int py, boolean right) {
        if (!owns(seat)) return;
        if (over) {
            if (endTime > 1.1) newRun();
        } else {
            swing();
        }
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (!owns(seat) || key == null) return;
        boolean first = down && keys.add(key);
        if (!down) keys.remove(key);
        if (first && key == Key.JUMP) {
            if (over) {
                if (endTime > 1.1) newRun();
            } else {
                swing();
            }
        }
    }

    private void newRun() {
        keys.clear();
        rng.setSeed(0xD06E0L);
        floor = 1;
        score = rank = combo = 0;
        health = stamina = 100;
        elapsed = endTime = steering = comboTime = 0;
        over = won = false;
        intro = player == null ? 0 : 4.5;
        buildFloor();
    }

    private void buildFloor() {
        entities.clear();
        sorted.clear();
        route.clear();
        target = null;

        for (int yy = 0; yy < N; yy++) {
            Arrays.fill(map[yy], 1);
            Arrays.fill(seen[yy], false);
            Arrays.fill(light[yy], 0);
        }

        // Connected chambers, with broad, navigable doorways.
        for (int ry = 0; ry < 3; ry++) {
            for (int rx = 0; rx < 3; rx++) {
                int cx = 3 + rx * 6, cy = 3 + ry * 6;
                carve(cx - 2, cy - 2, cx + 2, cy + 2);
                if (rx < 2) carve(cx + 2, cy - 1, cx + 4, cy + 1);
                if (ry < 2) carve(cx - 1, cy + 2, cx + 1, cy + 4);
            }
        }

        // Different silhouettes: paired columns, a central plinth, alcoves.
        int[][] pillars = {
                {9, 2}, {9, 4}, {8, 9}, {10, 9},
                {14, 8}, {16, 10}, {3, 14}, {15, 15}
        };
        for (int[] p : pillars) map[p[1]][p[0]] = 1;
        map[1][13] = 1;
        map[17][7] = 1;
        if (floor >= 2) {
            map[9][3] = 1;
            map[15][9] = 1;
        }

        for (int yy = 0; yy < N; yy++) {
            for (int xx = 0; xx < N; xx++) {
                if (map[yy][xx] == 0) continue;
                map[yy][xx] = (hash(xx / 2, yy / 2) & 3) == 0 ? 2 : 1;
            }
        }

        // Sparse, deliberately placed sconces instead of a torch on every brick.
        int[][] sconces = {
                {0, 3}, {3, 0}, {6, 1}, {6, 5},
                {12, 1}, {18, 3}, {0, 9}, {6, 7},
                {12, 11}, {18, 9}, {0, 15}, {6, 17},
                {12, 13}, {18, 15}, {15, 18}
        };
        for (int[] p : sconces)
            if (map[p[1]][p[0]] != 0) map[p[1]][p[0]] = 3;

        for (int yy = 0; yy < N; yy++) {
            for (int xx = 0; xx < N; xx++) {
                if (map[yy][xx] != 3) continue;
                for (int ly = Math.max(0, yy - 5); ly < Math.min(N, yy + 6); ly++) {
                    for (int lx = Math.max(0, xx - 5); lx < Math.min(N, xx + 6); lx++) {
                        double strength = Math.max(0, 1 - Math.hypot(lx - xx, ly - yy) / 5);
                        light[ly][lx] = Math.min(1, light[ly][lx] + strength * .8);
                    }
                }
            }
        }

        x = y = 3.5;
        angle = 0;
        immunity = 1.3;
        damageFlash = pickupFlash = shake = hitMarker = hitStop = 0;
        swordAge = 10;
        swordCooldown = 0;
        strikePending = false;
        bob = moving = stepDistance = floorTime = navTimer = 0;
        combo = 0;
        comboTime = 0;
        emeralds = 0;
        goal = 6 + floor * 2;
        objective = -1;
        exitX = exitY = 16;

        entities.add(new Entity(GATE, exitX + .5, exitY + .5));
        addGem(4.45, 3.45);
        addGem(3.3, 2.05);
        addSlime(5.15, 3.65, true);
        addSlime(3.25, 5.1, false);
        addSlime(7.65, 3.3, true);

        for (int i = 2; i < goal; i++) {
            int p = spawnCell(4);
            addGem(p % N + .5, p / N + .5);
        }
        for (int i = 0; i < 5 + floor * 2; i++) {
            int p = spawnCell(6);
            addSlime(p % N + .5, p / N + .5, i % 3 != 0);
        }
        entities.add(new Entity(HEART, 1.6, 4.7));
        for (int i = 0; i < 2; i++) {
            int p = spawnCell(6);
            entities.add(new Entity(HEART, p % N + .5, p / N + .5));
        }
        announce("DEPTH " + floor + "  /  " + DEPTH_NAMES[floor - 1], GOLD, 2.5);
        navigate();
        reveal();
    }

    private void carve(int x0, int y0, int x1, int y1) {
        for (int yy = y0; yy <= y1; yy++)
            for (int xx = x0; xx <= x1; xx++)
                if (xx > 0 && yy > 0 && xx < N - 1 && yy < N - 1)
                    map[yy][xx] = 0;
    }

    private int spawnCell(double minDistance) {
        int fallback = N + 1;
        for (int attempt = 0; attempt < 600; attempt++) {
            int xx = 1 + rng.nextInt(N - 2), yy = 1 + rng.nextInt(N - 2);
            if (wall(xx, yy)) continue;
            fallback = yy * N + xx;
            if (Math.hypot(xx + .5 - x, yy + .5 - y) < minDistance) continue;
            boolean occupied = false;
            for (Entity e : entities) {
                if (Math.hypot(e.x - xx - .5, e.y - yy - .5) < .8) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) return fallback;
        }
        return fallback;
    }

    private void addGem(double xx, double yy) {
        Entity e = new Entity(GEM, xx, yy);
        e.phase = rng.nextDouble() * 7;
        entities.add(e);
    }

    private void addSlime(double xx, double yy, boolean large) {
        Entity e = new Entity(SLIME, xx, yy);
        e.big = large;
        e.hp = large ? 2 : 1;
        e.phase = rng.nextDouble() * Math.PI * 2;
        entities.add(e);
    }

    @Override
    public void tick(double dt) {
        if (!ready || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(.1, dt);
        clock += dt;
        intro = Math.max(0, intro - dt);
        noticeTime = Math.max(0, noticeTime - dt);
        damageFlash = Math.max(0, damageFlash - dt);
        pickupFlash = Math.max(0, pickupFlash - dt);
        shake = Math.max(0, shake - dt * 6);
        hitMarker = Math.max(0, hitMarker - dt);
        comboTime = Math.max(0, comboTime - dt);
        if (comboTime == 0) combo = 0;

        if (over) {
            endTime += dt;
            updateParticles(dt);
            return;
        }

        elapsed += dt;
        floorTime += dt;
        immunity = Math.max(0, immunity - dt);
        swordCooldown = Math.max(0, swordCooldown - dt);
        swordAge += dt;
        double step = hitStop > 0 ? dt * .18 : dt;
        hitStop = Math.max(0, hitStop - dt);

        navTimer -= dt;
        if (navTimer <= 0) {
            navigate();
            navTimer = .2;
        }

        double oldX = x, oldY = y;
        if (player == null) {
            demo(step);
        } else {
            angle = wrap(angle + steering * 1.9 * step);
            double forward = (keys.contains(Key.UP) ? 1 : 0)
                    - (keys.contains(Key.DOWN) ? 1 : 0);
            double strafe = (keys.contains(Key.RIGHT) ? 1 : 0)
                    - (keys.contains(Key.LEFT) ? 1 : 0);
            double length = Math.hypot(forward, strafe);
            boolean sprint = keys.contains(Key.SPRINT) && length > 0 && stamina > 4;
            stamina = clamp(stamina + dt * (sprint ? -25 : 22), 0, 100);
            double speed = sprint ? 3.15 : 2.05;
            if (length > 0) {
                forward /= length;
                strafe /= length;
                movePlayer((Math.cos(angle) * forward - Math.sin(angle) * strafe) * speed * step,
                        (Math.sin(angle) * forward + Math.cos(angle) * strafe) * speed * step);
            }
        }

        double distance = Math.hypot(x - oldX, y - oldY);
        moving += ((distance > .001 ? 1 : 0) - moving) * Math.min(1, dt * 12);
        bob += distance * 7;
        stepDistance += distance;
        if (stepDistance > 1.55) {
            stepDistance = 0;
            sound("block.stone.step", .12f, .85f);
        }
        reveal();

        // Iterate backwards: collections may append particles, but never remove
        // an unrelated entity that has yet to be processed.
        for (int i = entities.size() - 1; i >= 0; i--) {
            Entity e = entities.get(i);
            e.age += step;
            e.flash = Math.max(0, e.flash - dt);
            e.squish = Math.max(0, e.squish - dt);

            if (e.kind == SLIME) {
                updateSlime(e, step);
                if (over) break;
            } else if (e.kind == GEM || e.kind == HEART) {
                e.z = .12 + Math.sin(clock * 3 + e.phase) * .035;
                double d = Math.hypot(e.x - x, e.y - y);
                if (e.kind == GEM && e.age > .25 && d < .92
                        && visible(x, y, e.x, e.y)) {
                    double pull = Math.min(d, step * 1.9);
                    e.x += (x - e.x) / Math.max(.01, d) * pull;
                    e.y += (y - e.y) / Math.max(.01, d) * pull;
                }
                if (d < .5 && (e.kind == GEM || health <= 80)
                        && visible(x, y, e.x, e.y)) {
                    entities.remove(i);
                    collect(e);
                }
            }
        }
        updateParticles(step);
        if (over) return;

        if (strikePending && swordAge >= .09) {
            strikePending = false;
            strike();
        }
        findTarget();

        if (emeralds >= goal && Math.hypot(x - exitX - .5, y - exitY - .5) < .68) {
            score += 800 + Math.max(0, 900 - (int) (floorTime * 5));
            sound("entity.player.levelup", .7f, 1.2f);
            if (floor == 3) {
                if (player == null) newRun();
                else finish(true);
            } else {
                floor++;
                health = Math.min(100, health + 25);
                buildFloor();
            }
        }
        if (player == null && elapsed > 100) newRun();
    }

    private void updateParticles(double dt) {
        for (int i = entities.size() - 1; i >= 0; i--) {
            Entity e = entities.get(i);
            if (e.kind != SPARK) continue;
            e.life -= dt;
            if (e.life <= 0) {
                entities.remove(i);
                continue;
            }
            double nx = e.x + e.vx * dt, ny = e.y + e.vy * dt;
            if (!wall((int) Math.floor(nx), (int) Math.floor(ny))) {
                e.x = nx;
                e.y = ny;
            } else {
                e.vx *= -.25;
                e.vy *= -.25;
            }
            e.z += e.vz * dt;
            e.vz -= 2.9 * dt;
            if (e.z < .02) {
                e.z = .02;
                e.vz = Math.abs(e.vz) * .25;
            }
        }
    }

    private void updateSlime(Entity e, double dt) {
        e.attackCooldown = Math.max(0, e.attackCooldown - dt);
        double distance = Math.hypot(e.x - x, e.y - y);
        int p = cell(e.x, e.y);
        boolean seesPlayer = distance < 8 && visible(e.x, e.y, x, y);
        if (seesPlayer || (flow[p] >= 0 && flow[p] < 5 + Math.min(5, floorTime / 24)))
            e.awake = true;

        if (e.windup > 0) {
            e.windup -= dt;
            e.z = .015;
            if (e.windup <= 0) {
                e.attackCooldown = 1.2;
                e.squish = .2;
                if (distance < (e.big ? .83 : .68) && immunity <= 0
                        && visible(x, y, e.x, e.y) && player != null) {
                    health = Math.max(0, health - (e.big ? 13 : 8));
                    immunity = .95;
                    damageFlash = .5;
                    shake = .65;
                    damageDirection = wrap(Math.atan2(e.y - y, e.x - x) - angle);
                    combo = 0;
                    e.vx = (e.x - x) / Math.max(.01, distance) * 1.6;
                    e.vy = (e.y - y) / Math.max(.01, distance) * 1.6;
                    announce("OUCH!  STRAFE AWAY FROM CHARGING SLIMES", RED, 1.4);
                    sound("entity.player.hurt", .55f, 1);
                    if (health <= 0) finish(false);
                }
            }
            return;
        }

        if (e.awake && distance < (e.big ? .72 : .58)
                && e.attackCooldown <= 0 && e.age > .65 && e.flash == 0
                && visible(x, y, e.x, e.y)) {
            e.windup = .38;
            return;
        }

        double tx = e.x, ty = e.y;
        if (e.awake) {
            if (seesPlayer) {
                tx = x;
                ty = y;
            } else {
                int next = lowerNeighbor(p);
                tx = next % N + .5;
                ty = next / N + .5;
            }
        } else {
            double a = e.phase + Math.floor(e.age / 3) * 1.7;
            tx += Math.cos(a);
            ty += Math.sin(a);
        }

        double dx = tx - e.x, dy = ty - e.y, len = Math.hypot(dx, dy);
        double bounce = Math.abs(Math.sin(clock * (e.big ? 4.1 : 5.6) + e.phase));
        double speed = e.awake ? (e.big ? .7 + floor * .07 : 1.0 + floor * .07) : .14;
        speed *= .5 + .65 * bounce;
        if (e.squish > 0) speed *= .3;

        double vx = len > .03 ? dx / len * speed : 0;
        double vy = len > .03 ? dy / len * speed : 0;

        // Soft separation preserves silhouettes instead of making a single mob blob.
        for (Entity other : entities) {
            if (other == e || other.kind != SLIME) continue;
            double sx = e.x - other.x, sy = e.y - other.y;
            double d2 = sx * sx + sy * sy;
            double spacing = e.big || other.big ? .65 : .43;
            if (d2 < spacing * spacing && d2 > .00001) {
                double d = Math.sqrt(d2), force = (spacing - d) * 1.6;
                vx += sx / d * force;
                vy += sy / d * force;
            }
        }
        moveEntity(e, (vx + e.vx) * dt, (vy + e.vy) * dt);
        e.vx *= Math.exp(-dt * 9);
        e.vy *= Math.exp(-dt * 9);
        e.z = bounce * (e.big ? .095 : .14);
    }

    private void collect(Entity e) {
        pickupFlash = .16;
        if (e.kind == GEM) {
            emeralds++;
            score += 100;
            burst(e.x, e.y, .26, 5, 0x9BF7C7);
            sound("entity.experience_orb.pickup", .48f,
                    (float) Math.min(2, .95 + emeralds * .09));
            if (emeralds == goal) {
                announce("SEAL BROKEN!  FOLLOW THE GOLDEN TRAIL", GOLD, 3.5);
                sound("block.beacon.activate", .5f, 1.4f);
            } else if (hitMarker <= 0) {
                announce("+100 EMERALD", MINT, .9);
            }
        } else {
            health = Math.min(100, health + 30);
            burst(e.x, e.y, .25, 5, 0xFFABB6);
            announce("HEALED +30", RED, 1.2);
            sound("block.note_block.chime", .5f, 1.5f);
        }
        navTimer = 0;
    }

    private void swing() {
        if (!ready || over || swordCooldown > 0) return;
        swordAge = 0;
        swordCooldown = .43;
        strikePending = true;
        sound("entity.player.attack.sweep", .32f, 1.35f);
    }

    private void strike() {
        boolean hit = false, split = false;
        int reward = 0, kills = 0;
        int count = entities.size();

        for (int i = count - 1; i >= 0; i--) {
            Entity e = entities.get(i);
            if (e.kind != SLIME) continue;
            double dx = e.x - x, dy = e.y - y, d = Math.hypot(dx, dy);
            double dot = (dx * Math.cos(angle) + dy * Math.sin(angle)) / Math.max(.001, d);
            if (d > 1.75 || (d > .48 && dot < .58)
                    || !visible(x, y, e.x, e.y)) continue;
            hit = true;
            e.flash = .10;
            e.squish = .27;
            e.windup = 0;
            e.attackCooldown = .55;
            e.awake = true;
            e.vx = dx / Math.max(.1, d) * 2.5;
            e.vy = dy / Math.max(.1, d) * 2.5;
            burst(e.x, e.y, e.z + .28, 9, e.big ? 0xB9EB83 : 0x7AE6C4);

            if (--e.hp <= 0) {
                entities.remove(i);
                reward += e.big ? 50 : 75;
                kills++;
                if (e.big) {
                    split = true;
                    for (int side = -1; side <= 1; side += 2) {
                        Entity child = new Entity(SLIME, e.x, e.y);
                        child.phase = rng.nextDouble() * 7;
                        child.awake = true;
                        child.squish = .22;
                        child.attackCooldown = .7;
                        child.vx = e.vx * .5 - Math.sin(angle) * side * 1.7;
                        child.vy = e.vy * .5 + Math.cos(angle) * side * 1.7;
                        moveEntity(child, -Math.sin(angle) * side * .19,
                                Math.cos(angle) * side * .19);
                        entities.add(child);
                    }
                } else {
                    addGem(e.x, e.y);
                    if (health < 60 && rng.nextDouble() < .15)
                        entities.add(new Entity(HEART, e.x, e.y));
                }
            }
        }

        if (hit) {
            combo = Math.min(8, combo + kills);
            comboTime = 3;
            reward += kills * Math.max(0, combo - 1) * 10;
            score += reward;
            hitMarker = .2;
            hitStop = .045;
            shake = .28;
            if (emeralds < goal || noticeTime < 1) {
                announce(split ? "SPLIT!  TWO LITTLE TROUBLES"
                                : reward > 0 ? "SQUISH!  +" + reward
                                : "HIT!  ONE MORE",
                        split ? GOLD : MINT, .9);
            }
            sound("entity.slime.squish", .65f, split ? .75f : 1.4f);
        }
    }

    private void burst(double xx, double yy, double zz, int count, int color) {
        for (int i = 0; i < count && entities.size() < 170; i++) {
            Entity e = new Entity(SPARK, xx, yy);
            double a = rng.nextDouble() * Math.PI * 2;
            double v = .25 + rng.nextDouble() * .95;
            e.z = zz;
            e.vx = Math.cos(a) * v;
            e.vy = Math.sin(a) * v;
            e.vz = .4 + rng.nextDouble() * .8;
            e.life = e.maxLife = .28 + rng.nextDouble() * .35;
            e.color = color;
            entities.add(e);
        }
    }

    private void findTarget() {
        target = null;
        double best = 4;
        for (Entity e : entities) {
            if (e.kind != SLIME) continue;
            double d = Math.hypot(e.x - x, e.y - y);
            if (d < best && Math.abs(wrap(Math.atan2(e.y - y, e.x - x) - angle)) < .32
                    && visible(x, y, e.x, e.y)) {
                target = e;
                best = d;
            }
        }
    }

    private void finish(boolean victory) {
        if (over) return;
        over = true;
        won = victory;
        endTime = 0;
        keys.clear();
        strikePending = false;
        if (victory) score += (int) health * 20;
        if (player != null) rank = host.score(player, score);
        sound(victory ? "ui.toast.challenge_complete" : "entity.player.death",
                .75f, victory ? 1.2f : .7f);
    }

    // ---- Navigation and autonomous cabinet demo ----

    private void navigate() {
        Arrays.fill(flow, -1);
        int start = cell(x, y), head = 0, tail = 0;
        flow[start] = 0;
        queue[tail++] = start;

        while (head < tail) {
            int p = queue[head++];
            for (int d : DIRS) {
                int next = p + d;
                if (!neighbors(p, next) || flow[next] >= 0
                        || wall(next % N, next / N)) continue;
                flow[next] = flow[p] + 1;
                queue[tail++] = next;
            }
        }

        objective = -1;
        double best = Double.POSITIVE_INFINITY;
        if (emeralds >= goal) {
            objective = exitY * N + exitX;
        } else {
            for (Entity e : entities) {
                if (e.kind != GEM) continue;
                int p = cell(e.x, e.y);
                if (flow[p] < 0) continue;
                double value = flow[p] + .05 * Math.hypot(e.x - x, e.y - y);
                if (value < best) {
                    best = value;
                    objective = p;
                }
            }
        }

        route.clear();
        if (objective >= 0 && flow[objective] >= 0) {
            int p = objective;
            while (p != start && route.size() < N * N) {
                route.add(p);
                int next = lowerNeighbor(p);
                if (next == p) break;
                p = next;
            }
            java.util.Collections.reverse(route);
        }
    }

    private int lowerNeighbor(int p) {
        if (p < 0 || p >= flow.length || flow[p] < 0) return p;
        int best = p;
        for (int d : DIRS) {
            int next = p + d;
            if (neighbors(p, next) && flow[next] >= 0 && flow[next] < flow[best])
                best = next;
        }
        return best;
    }

    private static boolean neighbors(int a, int b) {
        return b >= 0 && b < N * N
                && Math.abs(a % N - b % N) + Math.abs(a / N - b / N) == 1;
    }

    private void demo(double dt) {
        Entity enemy = null;
        double nearest = 2.6;
        for (Entity e : entities) {
            if (e.kind != SLIME) continue;
            double d = Math.hypot(e.x - x, e.y - y);
            if (d < nearest && visible(x, y, e.x, e.y)) {
                nearest = d;
                enemy = e;
            }
        }

        double tx = x, ty = y;
        if (enemy != null) {
            tx = enemy.x;
            ty = enemy.y;
        } else if (!route.isEmpty()) {
            // Take the furthest locally reachable waypoint. Clearance-aware
            // visibility prevents the demo cutting into doorway corners.
            for (int i = 0; i < Math.min(4, route.size()); i++) {
                int p = route.get(i);
                double nx = p % N + .5, ny = p / N + .5;
                if (!walkableLine(x, y, nx, ny)) break;
                tx = nx;
                ty = ny;
            }
            if (tx == x && ty == y) {
                int p = cell(x, y);
                tx = p % N + .5;
                ty = p / N + .5;
            }
        } else if (objective >= 0) {
            tx = objective % N + .5;
            ty = objective / N + .5;
            for (Entity e : entities) {
                if (e.kind == GEM && cell(e.x, e.y) == objective) {
                    tx = e.x;
                    ty = e.y;
                    break;
                }
            }
        }

        double dx = tx - x, dy = ty - y, d = Math.hypot(dx, dy);
        if (d < .02) return;
        double difference = wrap(Math.atan2(dy, dx) - angle);
        angle = wrap(angle + clamp(difference, -2.25 * dt, 2.25 * dt));
        if (Math.abs(difference) < .65 && (enemy == null || d > 1.2)) {
            double amount = Math.min(d, dt * 1.55);
            movePlayer(dx / d * amount, dy / d * amount);
        }
        if (enemy != null && d < 1.7 && Math.abs(difference) < .65) swing();
    }

    private void movePlayer(double dx, double dy) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / .1));
        for (int i = 0; i < steps; i++) {
            if (clear(x + dx / steps, y, .21)) x += dx / steps;
            if (clear(x, y + dy / steps, .21)) y += dy / steps;
        }
    }

    private void moveEntity(Entity e, double dx, double dy) {
        double r = e.big ? .26 : .16;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / .1));
        for (int i = 0; i < steps; i++) {
            if (clear(e.x + dx / steps, e.y, r)) e.x += dx / steps;
            if (clear(e.x, e.y + dy / steps, r)) e.y += dy / steps;
        }
    }

    private boolean wall(int xx, int yy) {
        return xx < 0 || yy < 0 || xx >= N || yy >= N || map[yy][xx] != 0;
    }

    private boolean clear(double xx, double yy, double r) {
        return !wall((int) Math.floor(xx - r), (int) Math.floor(yy - r))
                && !wall((int) Math.floor(xx + r), (int) Math.floor(yy - r))
                && !wall((int) Math.floor(xx - r), (int) Math.floor(yy + r))
                && !wall((int) Math.floor(xx + r), (int) Math.floor(yy + r));
    }

    private boolean visible(double ax, double ay, double bx, double by) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) * 12));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            if (wall((int) Math.floor(ax + (bx - ax) * t),
                    (int) Math.floor(ay + (by - ay) * t))) return false;
        }
        return true;
    }

    private boolean walkableLine(double ax, double ay, double bx, double by) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) * 10));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            if (!clear(ax + (bx - ax) * t, ay + (by - ay) * t, .23)) return false;
        }
        return true;
    }

    private void reveal() {
        int px = (int) x, py = (int) y;
        for (int yy = Math.max(0, py - 5); yy < Math.min(N, py + 6); yy++)
            for (int xx = Math.max(0, px - 5); xx < Math.min(N, px + 6); xx++)
                if (Math.hypot(xx + .5 - x, yy + .5 - y) < 5)
                    seen[yy][xx] = true;
    }

    private static int cell(double xx, double yy) {
        return (int) clamp(Math.floor(yy), 0, N - 1) * N
                + (int) clamp(Math.floor(xx), 0, N - 1);
    }

    private void announce(String s, Color color, double duration) {
        notice = s;
        noticeColor = color;
        noticeTime = duration;
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && player != null)
            host.sound(id, volume, (float) clamp(pitch, .5, 2));
    }

    // ---- Low-resolution software raycaster ----

    private void renderWorld() {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double planeX = -dy * W / (2 * PROJECTION);
        double planeY = dx * W / (2 * PROJECTION);
        int horizon = 104 + (int) (Math.sin(bob) * 1.35 * moving
                + Math.sin(clock * 67) * shake * 2);
        double flicker = .95 + .03 * Math.sin(clock * 17) + .025 * Math.sin(clock * 29);

        for (int sy = 0; sy < H; sy++) {
            boolean ceiling = sy < horizon;
            double distance = PROJECTION * .5 / Math.max(1, Math.abs(sy - horizon));
            double wx = x + distance * (dx - planeX);
            double wy = y + distance * (dy - planeY);
            double sxStep = distance * planeX * 2 / W;
            double syStep = distance * planeY * 2 / W;
            double shade = (ceiling ? .62 : .90) / (1 + distance * .16);

            for (int sx = 0; sx < W; sx++, wx += sxStep, wy += syStep) {
                int mx = (int) Math.floor(wx), my = (int) Math.floor(wy);
                int tx = (int) Math.floor(wx * 24), ty = (int) Math.floor(wy * 24);
                int noise = hash(tx, ty) & 7;
                boolean seam = Math.floorMod(tx, 24) == 0 || Math.floorMod(ty, 24) == 0;
                int c = ceiling ? 64 + noise : 87 + noise + (hash(mx, my) & 7);
                if (seam && distance < 10) c -= ceiling ? 8 : 14;
                int color = ceiling ? rgb(c - 9, c, c + 5) : rgb(c - 8, c + 3, c + 2);

                // Small damp patches, not high-contrast stripes across the room.
                if (!ceiling && (hash(tx / 3, ty / 3) & 127) < 9) {
                    color = floor == 1 ? 0x53694E : floor == 2 ? 0x456872 : 0x75634D;
                }
                double glow = mx >= 0 && my >= 0 && mx < N && my < N
                        ? light[my][mx] * flicker : 0;
                pixels[sy * W + sx] = lit(color, shade + glow * .12, glow * .17);
            }
        }

        for (int sx = 0; sx < W; sx++) {
            double cameraX = 2.0 * sx / W - 1;
            double rayX = dx + planeX * cameraX, rayY = dy + planeY * cameraX;
            int mx = (int) x, my = (int) y;
            double deltaX = Math.abs(rayX) < 1e-9 ? 1e9 : Math.abs(1 / rayX);
            double deltaY = Math.abs(rayY) < 1e-9 ? 1e9 : Math.abs(1 / rayY);
            int stepX = rayX < 0 ? -1 : 1, stepY = rayY < 0 ? -1 : 1;
            double sideX = (rayX < 0 ? x - mx : mx + 1 - x) * deltaX;
            double sideY = (rayY < 0 ? y - my : my + 1 - y) * deltaY;
            boolean side = false;

            for (int i = 0; i < N * 3; i++) {
                if (sideX < sideY) {
                    sideX += deltaX;
                    mx += stepX;
                    side = false;
                } else {
                    sideY += deltaY;
                    my += stepY;
                    side = true;
                }
                if (wall(mx, my)) break;
            }

            double distance = Math.max(.04, side ? sideY - deltaY : sideX - deltaX);
            walls[sx] = distance;
            double coordinate = side ? x + distance * rayX : y + distance * rayY;
            coordinate -= Math.floor(coordinate);
            int tx = (int) clamp(coordinate * T, 0, T - 1);
            if ((!side && rayX > 0) || (side && rayY < 0)) tx = T - 1 - tx;
            boolean inMap = mx >= 0 && my >= 0 && mx < N && my < N;
            int type = inMap ? Math.max(1, map[my][mx]) : 1;
            double glow = inMap ? light[my][mx] * flicker : 0;
            double height = PROJECTION / distance;
            double top = horizon - height / 2;
            int from = Math.max(0, (int) Math.ceil(top));
            int to = Math.min(H - 1, (int) (top + height));
            double amount = (side ? .83 : 1.02) / (1 + distance * .14) + glow * .15;
            double stoneVariation = .93 + (hash(mx, my) & 7) * .013;

            for (int sy = from; sy <= to; sy++) {
                int ty = (int) clamp((sy - top) * T / height, 0, T - 1);
                int color = textures[Math.min(2, type - 1)][ty * T + tx];

                if (type == 2) {
                    if (floor == 2) color = mix(color, 0x54818A, .28);
                    if (floor == 3) color = mix(color, 0x9A754C, .32);
                }

                // The upper flame sways; the metal bracket and wood do not.
                if (type == 3 && tx >= 11 && tx <= 21 && ty >= 4 && ty <= 14) {
                    double sway = Math.sin(clock * 12 + mx * 2 + my) * (14 - ty) * .13;
                    double center = 16 + sway;
                    double radius = ty < 9 ? (ty - 3) * .42 : (15 - ty) * .54 + .4;
                    double off = Math.abs(tx - center);
                    if (off < radius) {
                        int flame = off < radius * .4 && ty > 8
                                ? 0xFFF0B0 : off < radius * .8 ? 0xFFBC58 : 0xE97839;
                        pixels[sy * W + sx] = lit(flame, flicker, .03);
                        continue;
                    }
                }
                double edgeShade = ty > 27 ? .78 : ty < 2 ? .84 : 1;
                pixels[sy * W + sx] = lit(color,
                        amount * edgeShade * stoneVariation, glow * .24);
            }
        }

        Arrays.fill(depths, Double.POSITIVE_INFINITY);
        breadcrumbs(dx, dy, horizon);
        sorted.clear();
        for (Entity e : entities) {
            e.depth = (e.x - x) * dx + (e.y - y) * dy;
            if (e.depth > .07) sorted.add(e);
        }
        sorted.sort(Comparator.comparingDouble((Entity e) -> e.depth).reversed());

        for (Entity e : sorted)
            if (e.kind == SLIME || e.kind == GEM || e.kind == HEART)
                shadow(e, dx, dy, horizon);
        for (Entity e : sorted)
            if (e.kind != SPARK) billboard(e, dx, dy, horizon);
        for (Entity e : sorted)
            if (e.kind == SPARK) particle(e, dx, dy, horizon);

        weapon();
    }

    private void breadcrumbs(double dx, double dy, int horizon) {
        int color = emeralds >= goal ? 0xF7CA78 : 0x80D8B1;
        for (int i = 0; i < Math.min(8, route.size()); i++) {
            int p = route.get(i);
            double vx = p % N + .5 - x, vy = p / N + .5 - y;
            double depth = vx * dx + vy * dy;
            if (depth < .4 || depth > 9) continue;
            int cx = (int) (W / 2.0 + (-vx * dy + vy * dx) * PROJECTION / depth);
            int cy = (int) (horizon + PROJECTION * .487 / depth);
            int r = (int) clamp(PROJECTION * .025 / depth, 1, 4);
            double pulse = .45 + .25 * Math.sin(clock * 4.5 - i * .8);
            for (int yy = cy - 1; yy <= cy + 1; yy++) {
                for (int xx = cx - r; xx <= cx + r; xx++) {
                    if (xx < 0 || xx >= W || yy < 0 || yy >= H || depth >= walls[xx])
                        continue;
                    if (Math.abs(xx - cx) + Math.abs(yy - cy) <= r)
                        pixels[yy * W + xx] = mix(pixels[yy * W + xx], color, pulse);
                }
            }
        }
    }

    private void shadow(Entity e, double dx, double dy, int horizon) {
        double cx = W * .5 + (-(e.x - x) * dy + (e.y - y) * dx)
                * PROJECTION / e.depth;
        double cy = horizon + PROJECTION * .495 / e.depth;
        double rx = PROJECTION * (e.kind == SLIME ? (e.big ? .34 : .2) : .12) / e.depth;
        double ry = Math.max(1, rx * .18);
        int x0 = Math.max(0, (int) (cx - rx)), x1 = Math.min(W - 1, (int) (cx + rx));
        int y0 = Math.max(0, (int) (cy - ry)), y1 = Math.min(H - 1, (int) (cy + ry));
        for (int yy = y0; yy <= y1; yy++) {
            for (int xx = x0; xx <= x1; xx++) {
                double qx = (xx - cx) / rx, qy = (yy - cy) / ry;
                double q = qx * qx + qy * qy;
                if (q >= 1 || e.depth >= walls[xx]) continue;
                int at = yy * W + xx;
                if (e.windup > 0 && q > .53) {
                    pixels[at] = mix(pixels[at], 0xFF9967, .75);
                } else {
                    pixels[at] = mix(pixels[at], 0x07151B, .25 + (1 - q) * .14);
                }
            }
        }
    }

    private void billboard(Entity e, double dx, double dy, int horizon) {
        Sprite sprite;
        double width, height;
        if (e.kind == SLIME) {
            sprite = e.big ? largeSlime : littleSlime;
            double bounce = Math.abs(Math.sin(clock * (e.big ? 4.1 : 5.6) + e.phase));
            double squash = e.windup > 0 ? .25
                    : e.squish > 0 ? .23 * Math.sin(e.squish / .27 * Math.PI)
                    : .10 * (1 - bounce * 2);
            width = (e.big ? .73 : .42) * (1 + squash);
            height = (e.big ? .65 : .4) * (1 - squash);
        } else if (e.kind == GEM) {
            sprite = gemSprite;
            width = .28 * (.68 + .32 * Math.abs(Math.cos(clock * 2 + e.phase)));
            height = .35;
        } else if (e.kind == HEART) {
            sprite = heartSprite;
            width = height = .3;
        } else {
            sprite = emeralds >= goal ? openGate : closedGate;
            width = .91;
            height = .99;
        }

        double center = W * .5 + (-(e.x - x) * dy + (e.y - y) * dx)
                * PROJECTION / e.depth;
        double sw = PROJECTION * width / e.depth;
        double sh = PROJECTION * height / e.depth;
        double bottom = horizon + PROJECTION * (.5 - e.z) / e.depth;
        double left = center - sw / 2, top = bottom - sh;
        int x0 = Math.max(0, (int) Math.ceil(left));
        int x1 = Math.min(W - 1, (int) (left + sw));
        int y0 = Math.max(0, (int) Math.ceil(top));
        int y1 = Math.min(H - 1, (int) bottom);
        double amount = Math.max(e.kind == SLIME ? .45 : .8,
                1.1 / (1 + e.depth * .09));

        for (int xx = x0; xx <= x1; xx++) {
            if (e.depth >= walls[xx]) continue;
            int tx = (int) clamp((xx - left) * sprite.w / sw, 0, sprite.w - 1);
            for (int yy = y0; yy <= y1; yy++) {
                int at = yy * W + xx;
                if (e.depth >= depths[at]) continue;
                int ty = (int) clamp((yy - top) * sprite.h / sh, 0, sprite.h - 1);
                int color = sprite.data[ty * sprite.w + tx];
                if ((color >>> 24) == 0) continue;

                int shaded = lit(color, amount, 0);
                if (e.kind == SLIME) {
                    // Preserve dark eyes and mouth during damage feedback.
                    if (e.flash > 0 && ((color >> 8) & 255) > 90)
                        shaded = mix(shaded, 0xF8FFD7, .32 * e.flash / .1);
                    if (e.windup > 0 && ty >= 12 && ty <= 18
                            && ((color >> 8) & 255) < 90)
                        shaded = 0xFFD18A;
                } else if (e.kind == GATE && emeralds >= goal) {
                    shaded = mix(shaded, 0xB2FFD0, .05 + .04 * Math.sin(clock * 4));
                }
                pixels[at] = shaded;
                depths[at] = e.depth;
            }
        }
    }

    private void particle(Entity e, double dx, double dy, int horizon) {
        // Near-plane fade and a hard screen-space size cap prevent giant squares.
        if (e.depth < .25) return;
        double alpha = Math.min(1, e.life / .18)
                * Math.min(1, (e.depth - .25) / .5) * .8;
        if (alpha <= .01) return;
        int cx = (int) (W * .5 + (-(e.x - x) * dy + (e.y - y) * dx)
                * PROJECTION / e.depth);
        int cy = (int) (horizon + PROJECTION * (.5 - e.z) / e.depth);
        int size = (int) clamp(PROJECTION * .026 / e.depth, 1, 4);
        int half = size / 2;
        for (int yy = cy - half; yy < cy - half + size; yy++) {
            for (int xx = cx - half; xx < cx - half + size; xx++) {
                if (xx < 0 || xx >= W || yy < 0 || yy >= H) continue;
                int at = yy * W + xx;
                if (e.depth >= walls[xx] || e.depth >= depths[at]) continue;
                pixels[at] = mix(pixels[at], e.color, alpha);
            }
        }
    }

    private void weapon() {
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        double swing = swordAge < .34 ? Math.sin(swordAge / .34 * Math.PI) : 0;
        int bobY = (int) (Math.sin(bob) * 1.5 * moving);

        if (swordAge > .07 && swordAge < .24) {
            g.setColor(new Color(149, 241, 218, 65));
            g.setStroke(new BasicStroke(5));
            g.drawArc(126, 87, 151, 72, 188, 115);
            g.setColor(new Color(235, 255, 237, 185));
            g.setStroke(new BasicStroke(1));
            g.drawArc(126, 85, 151, 72, 188, 115);
        }

        AffineTransform old = g.getTransform();
        g.translate(303 - swing * 89, 225 + bobY + swing * 8);
        g.rotate(-.26 - swing * 1.32);
        g.scale(.84, .84);
        poly(g, new int[]{-14, 8, 14, 10, -8},
                new int[]{0, -3, -21, -37, -31}, 0x172936);
        poly(g, new int[]{-9, 6, 10, 6, -6},
                new int[]{-2, -6, -21, -33, -27}, 0xC68E6B);
        g.setColor(new Color(0x253A40));
        g.fillRect(-6, -54, 13, 30);
        g.setColor(new Color(0x765541));
        g.fillRect(-3, -51, 7, 27);
        g.setColor(new Color(0xCAA574));
        for (int yy = -48; yy < -25; yy += 6) g.fillRect(-3, yy, 7, 2);

        poly(g, new int[]{-8, -8, 0, 9, 8, 0},
                new int[]{-56, -108, -124, -107, -56, -50}, 0x173B47);
        poly(g, new int[]{-5, -5, 0, 6, 5, 0},
                new int[]{-56, -107, -119, -106, -56, -53}, 0xD2F1E7);
        poly(g, new int[]{0, 0, 6, 5},
                new int[]{-118, -54, -58, -106}, 0x6EAAB4);
        g.setColor(new Color(0xF2FFF0));
        g.drawLine(-4, -105, -4, -60);
        poly(g, new int[]{-20, -17, -5, 6, 17, 20, 17, 5, -6, -17},
                new int[]{-56, -62, -58, -58, -62, -56, -52, -54, -54, -51}, 0xDCC17C);
        g.setColor(new Color(0x20544E));
        g.fillRect(-4, -60, 9, 8);
        g.setColor(MINT);
        g.fillRect(-1, -59, 4, 5);
        g.setTransform(old);

        // Edge-only feedback leaves the central combat view legible.
        if (pickupFlash > 0) {
            g.setColor(new Color(105, 255, 187, (int) (pickupFlash * 230)));
            g.fillRect(0, H - 4, W, 4);
        }
        if (damageFlash > 0) {
            int alpha = (int) (damageFlash * 250);
            g.setColor(new Color(189, 33, 54, alpha));
            g.fillRect(0, 0, 5, H);
            g.fillRect(W - 5, 0, 5, H);
            g.fillRect(0, H - 5, W, 5);
            g.setColor(new Color(160, 24, 42, alpha / 3));
            g.fillRect(5, 0, 7, H);
            g.fillRect(W - 12, 0, 7, H);
        }
        g.dispose();
    }

    // ---- Crisp, full-resolution interface ----

    @Override
    public void draw(Graphics2D graphics) {
        if (!ready) {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, cols() * 128, rows() * 128);
            return;
        }
        renderWorld();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, 0, 0, host.width(), host.height(), null);
        g.scale(host.width() / 960.0, host.height() / 540.0);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(6, 17, 24, 245));
        g.fillRect(0, 0, 960, 62);
        g.setColor(new Color(88, 150, 133));
        g.fillRect(0, 61, 960, 1);
        text(g, "DUNGEON CRAWL", 25, 22, 29, GOLD, -1);
        text(g, player == null ? "LIVE AUTOPLAY" : shortName(player.name(), 22),
                17, 23, 51, MUTED, -1);
        text(g, "DEPTH " + floor + " / 3", 22, 338, 28, WHITE, -1);
        text(g, "SCORE  " + score, 18, 338, 51, GOLD, -1);
        text(g, "EMERALDS", 16, 566, 25, MINT, -1);
        text(g, emeralds + " / " + goal, 27, 566, 53, WHITE, -1);
        text(g, emeralds >= goal ? "SEAL BROKEN" : "BREAK THE SEAL",
                20, 938, 29, emeralds >= goal ? MINT : GOLD, 1);
        text(g, emeralds >= goal ? "Find the golden gate" : "Split slimes. Collect their loot.",
                16, 938, 51, MUTED, 1);

        minimap(g);
        if (player == null) attract(g);
        else if (over) gameOver(g);
        else playHud(g);
        g.dispose();
    }

    private void playHud(Graphics2D g) {
        int cx = 480, cy = 260;
        boolean inRange = target != null
                && Math.hypot(target.x - x, target.y - y) <= 1.75;
        g.setStroke(new BasicStroke(2));
        g.setColor(hitMarker > 0 ? GOLD : inRange ? MINT : WHITE);
        if (hitMarker > 0) {
            for (int sx : new int[]{-1, 1})
                for (int sy : new int[]{-1, 1})
                    g.drawLine(cx + sx * 5, cy + sy * 5, cx + sx * 11, cy + sy * 11);
        } else {
            g.drawLine(cx - 9, cy, cx - 4, cy);
            g.drawLine(cx + 4, cy, cx + 9, cy);
            g.drawLine(cx, cy - 9, cx, cy - 4);
            g.drawLine(cx, cy + 4, cx, cy + 9);
        }

        if (target != null) {
            int max = target.big ? 2 : 1;
            for (int i = 0; i < max; i++) {
                g.setColor(i < target.hp ? MINT : new Color(0x355451));
                g.fillRect(cx - max * 8 + i * 16, cy + 19, 12, 4);
            }
            if (target.windup > 0)
                text(g, "DODGE!", 19, cx, cy - 27, GOLD, 0);
        }
        if (damageFlash > 0) {
            int sx = cx + (int) (Math.sin(damageDirection) * 74);
            int sy = cy - (int) (Math.cos(damageDirection) * 50);
            text(g, "!", 29, sx, sy, RED, 0);
        }

        panel(g, 18, 430, 222, 72);
        text(g, "HEALTH", 16, 31, 451, MUTED, -1);
        text(g, Integer.toString((int) health), 24, 226, 452, WHITE, 1);
        g.setColor(new Color(0x49303A));
        g.fillRect(31, 462, 195, 13);
        g.setColor(health > 30 ? RED : GOLD);
        g.fillRect(31, 462, (int) (195 * health / 100), 13);
        for (int i = 1; i < 5; i++) {
            g.setColor(new Color(20, 27, 35, 150));
            g.fillRect(31 + i * 39, 462, 2, 13);
        }
        g.setColor(new Color(0x294648));
        g.fillRect(31, 488, 195, 4);
        g.setColor(MINT);
        g.fillRect(31, 488, (int) (195 * stamina / 100), 4);

        // Compact steering indicator; the central dead zone is shown explicitly.
        text(g, steering == 0 ? "MOUSE CENTERED"
                : steering < 0 ? "TURNING LEFT" : "TURNING RIGHT",
                14, 480, 477, MUTED, 0);
        g.setColor(new Color(0x57746E));
        g.fillRect(407, 488, 146, 2);
        g.setColor(new Color(0x3C7662));
        g.fillRect(465, 484, 30, 10);
        g.setColor(GOLD);
        g.fillRect(478 + (int) (steering * 71), 483, 4, 12);

        if (combo >= 2 && comboTime > 0)
            text(g, combo + " CHAIN", 20, 939, 491, GOLD, 1);

        g.setColor(new Color(6, 17, 24, 240));
        g.fillRect(0, 511, 960, 29);
        text(g, "W/S WALK   /   A/D STRAFE   /   MOUSE TURN   /   CLICK OR SPACE SWORD   /   SPRINT RUN",
                16, 480, 532, WHITE, 0);

        if (intro > 0) {
            panel(g, 197, 77, 566, 55);
            text(g, "MOUSE TO THE SIDE TURNS  /  CENTER STOPS TURNING",
                    17, 480, 99, WHITE, 0);
            text(g, "Follow green sparks. Get close, then click to swing.",
                    16, 480, 122, MINT, 0);
        } else {
            objectiveHud(g);
        }

        if (noticeTime > 0 && (intro <= 0 || hitMarker > 0 || damageFlash > 0)) {
            // A narrow strip below the compass, never over a slime's face.
            int size = notice.length() > 38 ? 16 : 19;
            text(g, notice, size, 480, intro > 0 ? 158 : 139, noticeColor, 0);
        }
    }

    private void objectiveHud(Graphics2D g) {
        if (objective < 0) return;
        int p = route.isEmpty() ? objective : route.get(0);
        double relative = wrap(Math.atan2(p / N + .5 - y, p % N + .5 - x) - angle);
        Color color = emeralds >= goal ? GOLD : MINT;
        panel(g, 339, 77, 282, 35);
        int ax = 360, ay = 94;
        AffineTransform old = g.getTransform();
        g.translate(ax, ay);
        g.rotate(relative);
        g.setColor(color);
        g.fillPolygon(new int[]{0, -6, 0, 6},
                new int[]{-9, 6, 2, 6}, 4);
        g.setTransform(old);
        int distance = Math.max(1, flow[objective]);
        text(g, (emeralds >= goal ? "GATE" : "EMERALD") + "  /  " + distance + " m",
                18, 493, 101, color, 0);
    }

    private void minimap(Graphics2D g) {
        int ox = 823, oy = 88, tile = 6;
        panel(g, ox - 10, oy - 10, 134, 154);
        for (int yy = 0; yy < N; yy++) {
            for (int xx = 0; xx < N; xx++) {
                if (player != null && !seen[yy][xx]) continue;
                g.setColor(map[yy][xx] == 0 ? new Color(0x29404A) : new Color(0x748D87));
                g.fillRect(ox + xx * tile, oy + yy * tile, tile - 1, tile - 1);
            }
        }
        g.setColor(emeralds >= goal ? GOLD : new Color(0x549F84));
        for (int i = 0; i < Math.min(12, route.size()); i++) {
            int p = route.get(i);
            if (player != null && !seen[p / N][p % N]) continue;
            g.fillRect(ox + p % N * tile + 2, oy + p / N * tile + 2, 2, 2);
        }
        for (Entity e : entities) {
            int xx = (int) e.x, yy = (int) e.y;
            if (xx < 0 || yy < 0 || xx >= N || yy >= N || e.kind == SPARK) continue;
            if (player != null && !seen[yy][xx]) continue;
            if (e.kind == SLIME && Math.hypot(e.x - x, e.y - y) > 5) continue;
            g.setColor(e.kind == GEM ? MINT : e.kind == HEART ? RED
                    : e.kind == GATE ? GOLD : new Color(0xEF9D7E));
            g.fillRect(ox + (int) (e.x * tile) - 1,
                    oy + (int) (e.y * tile) - 1, e.kind == SLIME ? 4 : 3, 3);
        }
        g.setColor(emeralds >= goal && clock % 1 < .5 ? WHITE : GOLD);
        g.drawRect(ox + exitX * tile, oy + exitY * tile, 6, 6);
        int px = ox + (int) (x * tile), py = oy + (int) (y * tile);
        g.setColor(WHITE);
        g.fillOval(px - 2, py - 2, 5, 5);
        g.setStroke(new BasicStroke(2));
        g.drawLine(px, py, px + (int) (Math.cos(angle) * 9),
                py + (int) (Math.sin(angle) * 9));
        text(g, "GOLD = GATE", 14, ox + 57, oy + 133, GOLD, 0);
    }

    private void attract(Graphics2D g) {
        panel(g, 22, 371, 468, 145);
        text(g, "DUNGEON CRAWL", 42, 40, 419, WHITE, -1);
        text(g, "Split slimes. Steal emeralds. Escape three depths.",
                17, 41, 449, MINT, -1);
        if (clock % 1.5 < 1.2)
            text(g, "TAKE A SEAT TO ENTER", 25, 41, 490, GOLD, -1);

        List<Host.Score> top = host.scores();
        if (!top.isEmpty() && clock % 15 > 7) {
            panel(g, 24, 81, 313, 145);
            text(g, "DUNGEON LEGENDS", 20, 40, 110, GOLD, -1);
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                Host.Score s = top.get(i);
                text(g, (i + 1) + ". " + shortName(s.name(), 13),
                        17, 40, 142 + i * 29, WHITE, -1);
                text(g, Integer.toString(s.score()), 17,
                        320, 142 + i * 29, MINT, 1);
            }
        } else {
            panel(g, 24, 81, 305, 39);
            text(g, "AUTOPLAY  /  A LIVING DUNGEON", 17, 176, 107, MINT, 0);
        }
        if (hitMarker > 0) text(g, "SQUISH!", 26, 480, 165, GOLD, 0);
    }

    private void gameOver(Graphics2D g) {
        g.setColor(new Color(4, 12, 20, 167));
        g.fillRect(0, 62, 960, 478);
        panel(g, 182, 137, 596, 316);
        text(g, won ? "DUNGEON CLEARED" : "YOU FELL",
                won ? 44 : 61, 480, 207, won ? GOLD : RED, 0);
        text(g, won ? "The emerald crypt is yours."
                : "Watch their glow. Strafe before they strike.",
                21, 480, 247, WHITE, 0);
        text(g, "SCORE  " + score, 36, 480, 303, MINT, 0);
        text(g, "DEPTH " + floor + " / 3   /   " + (int) elapsed + " SECONDS",
                20, 480, 340, MUTED, 0);
        if (rank > 0) text(g, "DUNGEON LEGEND  #" + rank, 23, 480, 379, GOLD, 0);
        if (endTime > 1.1 && clock % 1.4 < 1.1)
            text(g, "CLICK OR SPACE TO TRY AGAIN", 23, 480, 426, WHITE, 0);
    }

    // ---- Procedural texture and sprite creation ----

    private void makeTextures() {
        for (int yy = 0; yy < T; yy++) {
            for (int xx = 0; xx < T; xx++) {
                int bx = (xx + (yy / 8 & 1) * 8) & 15, by = yy & 7;
                int noise = hash(xx, yy) & 15;
                boolean mortar = bx == 0 || by == 0;
                int c = mortar ? 48 + noise / 3 : 113 + noise;
                if (!mortar && (bx == 1 || by == 1)) c += 13;
                if (bx == 15 || by == 7) c -= 14;
                if (bx == 10 && by > 3 && (hash(xx / 16, yy / 8) & 3) == 0) c -= 18;
                int at = yy * T + xx;
                textures[0][at] = rgb(c - 9, c + 2, c + 5);

                // Irregular cobbles and clustered moss rather than a flat green wash.
                int cobbleX = Math.floorMod(xx + ((yy / 6 & 1) * 4), 8);
                int cobbleY = yy % 6;
                int cobble = cobbleX == 0 || cobbleY == 0 ? 52 : 96 + noise;
                if (cobbleY == 1) cobble += 15;
                int moss = hash(xx / 3, yy / 3) & 31;
                textures[1][at] = moss < 11
                        ? rgb(48 + noise, 79 + noise, 51 + noise / 2)
                        : rgb(cobble - 8, cobble + 5, cobble + 2);
                double halo = Math.max(0, 1 - Math.hypot(xx - 16, yy - 12) / 19);
                textures[2][at] = mix(textures[0][at], 0xC49660, halo * .18);
            }
        }
        for (int yy = 14; yy <= 25; yy++)
            for (int xx = 15; xx <= 17; xx++)
                textures[2][yy * T + xx] = xx == 15 ? 0xB58B59 : 0x604431;
        for (int yy = 19; yy <= 22; yy++)
            for (int xx = 12; xx <= 20; xx++)
                textures[2][yy * T + xx] = yy == 19 ? 0x929A98 : 0x354048;
    }

    private void makeSprites() {
        largeSlime = slime(false);
        littleSlime = slime(true);

        BufferedImage image = new BufferedImage(20, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        poly(g, new int[]{10, 18, 18, 10, 2, 2},
                new int[]{0, 6, 16, 23, 16, 6}, 0x174A45);
        poly(g, new int[]{10, 16, 16, 10, 4, 4},
                new int[]{2, 7, 15, 21, 15, 7}, 0x4BE3A0);
        poly(g, new int[]{10, 10, 4, 4},
                new int[]{2, 11, 15, 7}, 0xBCFFDC);
        poly(g, new int[]{10, 16, 16, 10},
                new int[]{11, 7, 15, 21}, 0x199474);
        g.setColor(new Color(0xF3FFF0));
        g.fillRect(8, 4, 3, 3);
        g.dispose();
        gemSprite = new Sprite(image);

        image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        poly(g, new int[]{2, 6, 10, 14, 18, 18, 10, 2},
                new int[]{3, 1, 5, 1, 3, 10, 19, 10}, 0x6B3048);
        poly(g, new int[]{3, 6, 10, 14, 17, 17, 10, 3},
                new int[]{4, 3, 7, 3, 4, 10, 17, 10}, 0xEF8397);
        g.setColor(new Color(0xFFDBDF));
        g.fillRect(5, 5, 3, 3);
        g.dispose();
        heartSprite = new Sprite(image);

        closedGate = gate(false);
        openGate = gate(true);
    }

    private Sprite slime(boolean small) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x173C37));
        g.fillRoundRect(1, 6, 30, 26, 8, 8);
        g.setColor(new Color(small ? 0x45B79D : 0x6BA74F));
        g.fillRoundRect(2, 5, 28, 25, 8, 8);
        g.setColor(new Color(small ? 0x79DCBA : 0xA4D77B));
        g.fillRoundRect(3, 3, 26, 23, 8, 8);
        g.setColor(new Color(small ? 0xC3F4D9 : 0xD5F4AD));
        g.fillRect(7, 5, 14, 2);
        g.fillRect(4, 8, 3, 5);
        g.setColor(new Color(small ? 0x398C7B : 0x568A45));
        g.fillRect(5, 24, 21, 3);
        g.fillRect(25, 10, 2, 12);
        g.setColor(new Color(small ? 0x58C3A5 : 0x86B864));
        g.fillRect(6, 19, 4, 3);
        g.fillRect(19, 8, 4, 3);

        g.setColor(new Color(0x183A34));
        g.fillRect(9, 13, 5, 6);
        g.fillRect(20, 13, 5, 6);
        g.fillRect(15, 21, 5, 2);
        g.setColor(new Color(0xEDFFE5));
        g.fillRect(9, 13, 2, 2);
        g.fillRect(20, 13, 2, 2);
        g.dispose();
        return new Sprite(image);
    }

    private Sprite gate(boolean open) {
        BufferedImage image = new BufferedImage(40, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x172B35));
        g.fillRect(0, 5, 40, 43);
        g.fillRect(6, 0, 28, 6);
        g.setColor(new Color(0xA9A78E));
        for (int yy = 6; yy < 48; yy += 7) {
            g.fillRect(1, yy, 7, 6);
            g.fillRect(32, yy, 7, 6);
        }
        for (int xx = 8; xx < 33; xx += 8) g.fillRect(xx, 1, 7, 7);
        g.setColor(new Color(open ? 0x22584E : 0x20343D));
        g.fillRect(9, 10, 22, 37);
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(open
                    ? rgb(52 + i * 14, 115 + i * 18, 91 + i * 13) : 0x4D5B56));
            g.fillRect(10 + i, 43 - i * 5, 20 - i * 2, 3);
        }
        g.setColor(new Color(0xE0C27C));
        if (!open) {
            for (int xx = 12; xx < 32; xx += 7) g.fillRect(xx, 10, 2, 37);
            g.fillRect(10, 24, 20, 2);
            g.fillRect(17, 21, 7, 7);
        } else {
            g.fillRect(8, 9, 2, 38);
            g.fillRect(30, 9, 2, 38);
            g.setColor(new Color(0xB9FFD3));
            g.fillRect(17, 13, 6, 2);
            g.fillRect(19, 11, 2, 7);
        }
        g.setColor(GOLD);
        g.fillRect(17, 2, 6, 5);
        g.setColor(new Color(open ? 0xC8FFDA : 0xA57949));
        g.fillRect(18, 2, 4, 4);
        g.dispose();
        return new Sprite(image);
    }

    private static void poly(Graphics2D g, int[] xx, int[] yy, int color) {
        g.setColor(new Color(color));
        g.fillPolygon(xx, yy, xx.length);
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(6, 18, 25, 222));
        g.fillRoundRect(x, y, w, h, 9, 9);
        g.setColor(new Color(115, 168, 150, 145));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, h, 9, 9);
    }

    private static void text(Graphics2D g, String s, int size,
                             int x, int y, Color color, int align) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        int width = g.getFontMetrics().stringWidth(s);
        int left = align < 0 ? x : align > 0 ? x - width : x - width / 2;
        g.setColor(new Color(0, 0, 0, 205));
        g.drawString(s, left + 1, y + 2);
        g.setColor(color);
        g.drawString(s, left, y);
    }

    private static String shortName(String name, int limit) {
        if (name == null) return "Adventurer";
        return name.length() <= limit ? name : name.substring(0, limit - 1) + "…";
    }

    private static int hash(int x, int y) {
        int v = x * 374761393 + y * 668265263 + 0x51EAD;
        v = (v ^ (v >>> 13)) * 1274126177;
        return v ^ (v >>> 16);
    }

    private static int rgb(int r, int g, int b) {
        return (Math.max(0, Math.min(255, r)) << 16)
                | (Math.max(0, Math.min(255, g)) << 8)
                | Math.max(0, Math.min(255, b));
    }

    private static int lit(int c, double amount, double warmth) {
        amount = clamp(amount, 0, 1.15);
        double fog = 1 - Math.min(1, amount);
        return rgb((int) (((c >> 16) & 255) * amount + 8 * fog + warmth * 60),
                (int) (((c >> 8) & 255) * amount + 17 * fog + warmth * 28),
                (int) ((c & 255) * amount + 25 * fog - warmth * 7));
    }

    private static int mix(int a, int b, double t) {
        return rgb((int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t),
                (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                (int) ((a & 255) * (1 - t) + (b & 255) * t));
    }

    private static double clamp(double n, double min, double max) {
        return Math.max(min, Math.min(max, n));
    }

    private static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    private static final class Entity {
        final int kind;
        double x, y, z, vx, vy, vz, phase, age, depth;
        double flash, squish, windup, attackCooldown, life, maxLife;
        int hp = 1, color;
        boolean big, awake;

        Entity(int kind, double x, double y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Sprite {
        final int w, h;
        final int[] data;

        Sprite(BufferedImage image) {
            w = image.getWidth();
            h = image.getHeight();
            data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        }
    }
}

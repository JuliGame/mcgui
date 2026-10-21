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
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Snake Pit: four-player, buffered-turn Snake.
 * Pixel-art arena with native-resolution typography.
 */
public class SnakePit implements Scene {
    private static final int W = 512, H = 288;
    private static final int CELL = 14, BW = 32, BH = 15;
    private static final int BX = 32, BY = 54, SIZE = BW * BH;
    private static final double STEP = 1.0 / 120.0;
    private static final double NORMAL = 0.16, FAST = 0.105;

    private static final Color INK = new Color(0x101E25);
    private static final Color WHITE = new Color(0xF4F9E7);
    private static final Color MUTED = new Color(0xADC7B3);
    private static final Color GOLD = new Color(0xFFD35A);
    private static final Color RED = new Color(0xFF7974);
    private static final Color[] COLORS = {
            new Color(0xBEFA66), new Color(0x65E3FF),
            new Color(0xFF987C), new Color(0xD6A1FF)
    };
    private static final Color[] DARKS = {
            new Color(0x4B782A), new Color(0x24657F),
            new Color(0x924941), new Color(0x684686)
    };
    private static final String[] BOT_NAMES = {
            "MOSS", "RIPTIDE", "EMBER", "CHORUS"
    };
    private static final Font[] FONTS = new Font[49];
    static {
        for (int i = 1; i < FONTS.length; i++)
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
    }

    private final Random rng = new Random(0x51A4E51L);
    private final List<Snake> snakes = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final Apple[] apples = new Apple[SIZE];

    private final int[] occupancy = new int[SIZE];
    private final boolean[] due = new boolean[4];
    private final boolean[] doomed = new boolean[4];
    private final boolean[] vacates = new boolean[4];
    private final int[] next = new int[4];
    private final int[] visited = new int[SIZE];
    private final int[] floodQueue = new int[SIZE];
    private int visitStamp;

    private Host host;
    private BufferedImage frame, background;
    private boolean attract = true;
    private double clock, accumulator, demoTime, shake, flash;
    private double goldTimer, messageTime;
    private String message = "";
    private Color messageColor = WHITE;

    public SnakePit() {}

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public int fps() {
        return 30;
    }

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        background = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        buildBackground();
        resetWorld(true);
        synchronizeSeats();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || seat.id() == null) return;
        if (attract) resetWorld(false);
        addPlayer(seat);
    }

    @Override
    public void left(Seat seat) {
        if (host == null || seat == null) return;
        Snake s = find(seat);
        if (s != null) snakes.remove(s);
        if (!attract && snakes.isEmpty()) resetWorld(true);
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (host == null || key == null) return;
        Snake s = find(seat);
        if (s == null) return;

        if (!down) {
            s.held.remove(key);
            return;
        }
        if (!s.held.add(key)) return;

        if (!s.alive) {
            if (key == Key.JUMP || key == Key.SWAP_HANDS) restart(s);
            return;
        }

        int d = switch (key) {
            case UP -> 0;
            case RIGHT -> 1;
            case DOWN -> 2;
            case LEFT -> 3;
            default -> -1;
        };
        if (d < 0) return;
        int basis = s.turns.isEmpty() ? s.direction : s.turns.peekLast();
        if (d != basis && d != ((basis + 2) & 3) && s.turns.size() < 2)
            s.turns.addLast(d);
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (host == null) return;
        Snake s = find(seat);
        if (s != null && !s.alive) restart(s);
    }

    private Snake find(Seat seat) {
        if (seat == null || seat.id() == null) return null;
        for (Snake s : snakes)
            if (s.seat != null && seat.id().equals(s.seat.id())) return s;
        return null;
    }

    private static boolean contains(List<Seat> list, UUID id) {
        for (Seat seat : list)
            if (seat != null && id.equals(seat.id())) return true;
        return false;
    }

    private void synchronizeSeats() {
        List<Seat> seats = host.seats();
        if (seats.isEmpty()) {
            if (!attract) resetWorld(true);
            return;
        }
        if (attract) resetWorld(false);
        snakes.removeIf(s -> s.seat == null || !contains(seats, s.seat.id()));
        for (Seat seat : seats) addPlayer(seat);
    }

    private void resetWorld(boolean demo) {
        attract = demo;
        snakes.clear();
        particles.clear();
        popups.clear();
        rings.clear();
        Arrays.fill(apples, null);
        accumulator = demoTime = shake = flash = messageTime = 0;
        goldTimer = 4;
        message = "";
        if (demo) {
            for (int i = 0; i < 4; i++) {
                Snake s = new Snake();
                s.bot = true;
                s.color = i;
                s.name = BOT_NAMES[i];
                snakes.add(s);
                spawn(s);
            }
        }
        replenish();
    }

    private void addPlayer(Seat seat) {
        if (seat == null || seat.id() == null || snakes.size() >= 4
                || find(seat) != null) return;

        boolean[] used = new boolean[4];
        for (Snake s : snakes) used[s.color] = true;
        int color = Math.floorMod(seat.index(), 4);
        if (used[color]) {
            for (int i = 0; i < 4; i++) {
                if (!used[i]) {
                    color = i;
                    break;
                }
            }
        }
        Snake s = new Snake();
        s.seat = seat;
        s.name = seat.name() == null || seat.name().isBlank()
                ? "PLAYER" : seat.name();
        s.color = color;
        snakes.add(s);
        spawn(s);
        announce("WASD to steer • Apples grow your snake • Avoid every body",
                WHITE, 3.5);
        sound("block.note_block.chime", 0.5f, 1.2f);
    }

    private void restart(Snake s) {
        if (s.alive || s.waiting || s.deadTime < 1.1) return;
        s.score = s.rank = 0;
        spawn(s);
        sound("block.note_block.pling", 0.5f, 1.3f);
    }

    private void spawn(Snake s) {
        s.alive = false;
        s.waiting = true;
        s.moving = false;
        s.body.clear();
        s.turns.clear();
        s.held.clear();
        s.progress = s.boost = s.deadTime = s.retry = s.bite = 0;
        s.grow = 0;
        s.ready = s.bot ? 0.3 : 1.05;
        buildOccupancy();

        int[] px = {7, 24, 7, 24};
        int[] py = {3, 11, 11, 3};
        int direction = (s.color & 1) == 0 ? 1 : 3;
        if (trySpawn(s, px[s.color] + py[s.color] * BW, direction)) return;

        int offset = rng.nextInt(SIZE);
        for (int i = 0; i < SIZE; i++) {
            int p = (offset + i) % SIZE;
            for (int d = 0; d < 4; d++)
                if (trySpawn(s, p, d)) return;
        }
    }

    private boolean trySpawn(Snake s, int head, int d) {
        int x = head % BW, y = head / BW;
        for (int i = -5; i <= 5; i++) {
            int nx = x + dx(d) * i, ny = y + dy(d) * i;
            if (!inside(nx, ny) || occupancy[nx + ny * BW] >= 0) return false;
            for (Snake other : snakes) {
                if (!other.alive || other.body.isEmpty()) continue;
                int h = other.body.get(0);
                if (i >= -1 && Math.abs(nx - h % BW) + Math.abs(ny - h / BW) < 4)
                    return false;
            }
        }
        s.direction = d;
        for (int i = 0; i < 6; i++) {
            int p = x - dx(d) * i + (y - dy(d) * i) * BW;
            s.body.add(p);
            apples[p] = null;
        }
        s.alive = true;
        s.waiting = false;
        int food = x + dx(d) * 3 + (y + dy(d) * 3) * BW;
        if (apples[food] == null) apples[food] = new Apple(false, 0);
        addRing(cx(head), cy(head), COLORS[s.color], 0.65, 22);
        return true;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt)) return;
        dt = Math.max(0, Math.min(0.1, dt));
        synchronizeSeats();
        clock += dt;
        demoTime += dt;
        shake = Math.max(0, shake - dt * 13);
        flash = Math.max(0, flash - dt * 2);
        messageTime = Math.max(0, messageTime - dt);
        goldTimer -= dt;

        for (Snake s : snakes) {
            s.bite = Math.max(0, s.bite - dt);
            if (!s.alive) {
                s.deadTime += dt;
                s.retry += dt;
                if (s.waiting && s.retry >= 1) spawn(s);
                else if (s.bot && !s.waiting && s.deadTime >= 1.6) {
                    s.score = s.rank = 0;
                    spawn(s);
                }
            }
        }

        accumulator += dt;
        while (accumulator + 1e-9 >= STEP) {
            accumulator -= STEP;
            physics();
        }

        for (int i = 0; i < SIZE; i++) {
            Apple a = apples[i];
            if (a == null) continue;
            a.age += dt;
            if (a.life > 0) {
                a.life -= dt;
                if (a.life <= 0) apples[i] = null;
            }
        }
        replenish();

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) particles.remove(i);
            else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 75 * dt;
            }
        }
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup p = popups.get(i);
            p.life -= dt;
            p.y -= dt * 17;
            if (p.life <= 0) popups.remove(i);
        }
        for (int i = rings.size() - 1; i >= 0; i--)
            if ((rings.get(i).life -= dt) <= 0) rings.remove(i);

        if (attract && demoTime >= 90) resetWorld(true);
    }

    /**
     * A snake travels FROM its committed grid cell TO its next one.
     * Turns are selected at departure; eating and collision happen on arrival.
     * This avoids a rendered head trailing collision state by a full cell.
     */
    private void physics() {
        Arrays.fill(due, false);
        Arrays.fill(doomed, false);
        Arrays.fill(vacates, false);
        Arrays.fill(next, -1);
        buildOccupancy();
        boolean any = false;

        for (int i = 0; i < snakes.size(); i++) {
            Snake s = snakes.get(i);
            if (!s.alive) continue;
            s.boost = Math.max(0, s.boost - STEP);
            if (s.ready > 0) {
                s.ready = Math.max(0, s.ready - STEP);
                continue;
            }

            if (!s.moving) {
                if (s.bot) s.direction = chooseDirection(s);
                else if (!s.turns.isEmpty()) s.direction = s.turns.removeFirst();
                s.moving = true;
            }
            s.progress += STEP / (s.boost > 0 ? FAST : NORMAL);
            if (s.progress < 1 - 1e-9) continue;

            s.progress = Math.max(0, s.progress - 1);
            due[i] = any = true;
            next[i] = neighbor(s.body.get(0), s.direction);
            vacates[i] = s.grow == 0 && (next[i] < 0 || apples[next[i]] == null);
        }
        if (!any) return;

        for (int i = 0; i < snakes.size(); i++) {
            if (due[i] && vacates[i]) {
                Snake s = snakes.get(i);
                occupancy[s.body.get(s.body.size() - 1)] = -1;
            }
        }
        for (int i = 0; i < snakes.size(); i++) {
            if (due[i] && (next[i] < 0 || occupancy[next[i]] >= 0))
                doomed[i] = true;
        }
        for (int i = 0; i < snakes.size(); i++) {
            if (!due[i]) continue;
            for (int j = i + 1; j < snakes.size(); j++) {
                if (!due[j]) continue;
                if ((next[i] >= 0 && next[i] == next[j])
                        || (next[i] == snakes.get(j).body.get(0)
                        && next[j] == snakes.get(i).body.get(0))) {
                    doomed[i] = doomed[j] = true;
                }
            }
        }

        // Commit surviving movements before turning dead bodies into apples.
        for (int i = 0; i < snakes.size(); i++) {
            if (!due[i] || doomed[i]) continue;
            Snake s = snakes.get(i);
            s.body.add(0, next[i]);
            Apple food = apples[next[i]];
            if (food != null) {
                apples[next[i]] = null;
                eat(s, next[i], food.golden);
            }
            if (s.grow > 0) s.grow--;
            else s.body.remove(s.body.size() - 1);
            s.moving = false;
        }
        for (int i = 0; i < snakes.size(); i++)
            if (due[i] && doomed[i]) snakes.get(i).alive = false;

        buildOccupancy();
        for (int i = 0; i < snakes.size(); i++)
            if (due[i] && doomed[i]) kill(snakes.get(i));
    }

    private void buildOccupancy() {
        Arrays.fill(occupancy, -1);
        for (int i = 0; i < snakes.size(); i++) {
            Snake s = snakes.get(i);
            if (s.alive)
                for (int p : s.body) occupancy[p] = i;
        }
    }

    private void eat(Snake s, int p, boolean golden) {
        s.score += golden ? 5 : 1;
        s.grow += golden ? 5 : 2;
        s.bite = 0.24;
        if (golden) {
            s.boost = 4.5;
            shake = Math.max(shake, 1.8);
            flash = 0.17;
            announce(s.name + " • GOLD RUSH!  +5 and 4.5 seconds of speed",
                    GOLD, 2.7);
        }
        Color c = golden ? GOLD : COLORS[s.color];
        burst(cx(p), cy(p), c, golden ? 32 : 13);
        addRing(cx(p), cy(p), c, golden ? 0.65 : 0.35, golden ? 28 : 15);
        popup(cx(p), cy(p) - 8, golden ? "+5 SPEED!" : "+1", c);
        sound(golden ? "entity.player.levelup" : "entity.experience_orb.pickup",
                golden ? 0.65f : 0.35f,
                golden ? 1.4f : Math.min(2f, 0.85f + (s.score % 16) * 0.065f));
    }

    private void kill(Snake s) {
        int h = s.body.get(0);
        s.deathX = clamp(cx(h) + dx(s.direction) * CELL, BX + 3, BX + BW * CELL - 3);
        s.deathY = clamp(cy(h) + dy(s.direction) * CELL, BY + 3, BY + BH * CELL - 3);
        s.deadTime = 0;
        s.waiting = false;
        s.moving = false;
        s.boost = 0;
        s.turns.clear();
        burst(s.deathX, s.deathY, COLORS[s.color], 42);
        addRing(s.deathX, s.deathY, COLORS[s.color], 0.7, 38);

        for (int p : s.body) {
            if (occupancy[p] < 0 && apples[p] == null)
                apples[p] = new Apple(false, 20);
        }
        s.body.clear();
        shake = Math.max(shake, s.bot ? 1 : 3.5);
        if (!s.bot && s.seat != null) {
            s.rank = host.score(s.seat, s.score);
            popup(s.deathX, s.deathY - 8, "COMPOSTED!", COLORS[s.color]);
            announce(s.name + " • " + s.score + " points • SPACE / CLICK to replant",
                    COLORS[s.color], 3.2);
            sound("entity.generic.explode", 0.45f, 1.5f);
        }
    }

    private int chooseDirection(Snake s) {
        int head = s.body.get(0), best = s.direction;
        double bestValue = -1e9;
        for (int d = 0; d < 4; d++) {
            if (d == ((s.direction + 2) & 3)) continue;
            int p = neighbor(head, d);
            if (p < 0 || occupancy[p] >= 0) continue;
            int room = floodRoom(p, 100);
            double nearest = 100;
            for (int i = 0; i < SIZE; i++) {
                if (apples[i] != null) {
                    double distance = Math.abs(p % BW - i % BW)
                            + Math.abs(p / BW - i / BW);
                    nearest = Math.min(nearest, distance - (apples[i].golden ? 2 : 0));
                }
            }
            double value = -nearest * 2.2 + Math.min(room, 65) * 0.2;
            if (room < s.body.size() + 8) value -= 90;
            if (d == s.direction) value += 0.25;
            value += rng.nextDouble() * 0.3;
            for (Snake other : snakes) {
                if (other == s || !other.alive) continue;
                int h = other.body.get(0);
                int distance = Math.abs(p % BW - h % BW) + Math.abs(p / BW - h / BW);
                if (distance <= 2) value -= (3 - distance) * 16;
                if (neighbor(h, other.direction) == p) value -= 70;
            }
            if (value > bestValue) {
                bestValue = value;
                best = d;
            }
        }
        return best;
    }

    private int floodRoom(int start, int cap) {
        if (++visitStamp == Integer.MAX_VALUE) {
            Arrays.fill(visited, 0);
            visitStamp = 1;
        }
        int read = 0, write = 1;
        floodQueue[0] = start;
        visited[start] = visitStamp;
        while (read < write && write < cap) {
            int p = floodQueue[read++];
            for (int d = 0; d < 4 && write < cap; d++) {
                int n = neighbor(p, d);
                if (n < 0 || occupancy[n] >= 0 || visited[n] == visitStamp) continue;
                visited[n] = visitStamp;
                floodQueue[write++] = n;
            }
        }
        return write;
    }

    private void replenish() {
        int fresh = 0;
        boolean hasGold = false;
        for (Apple a : apples) {
            if (a != null) {
                if (a.life == 0) fresh++;
                hasGold |= a.golden;
            }
        }
        int target = 12 + snakes.size();
        if (fresh >= target && (hasGold || goldTimer > 0)) return;
        buildOccupancy();

        // Body drops do not suppress fresh food elsewhere in the arena.
        while (fresh < target) {
            int p = freeCell();
            if (p < 0) break;
            apples[p] = new Apple(false, 0);
            fresh++;
        }
        if (!hasGold && goldTimer <= 0) {
            int p = freeCell();
            if (p >= 0) {
                apples[p] = new Apple(true, 16);
                addRing(cx(p), cy(p), GOLD, 0.7, 19);
                goldTimer = 15 + rng.nextDouble() * 6;
                if (!attract) {
                    announce("GOLDEN APPLE!  Worth +5 • Gives a short speed burst", GOLD, 2.2);
                    sound("block.note_block.bell", 0.3f, 1.6f);
                }
            }
        }
    }

    private int freeCell() {
        int start = rng.nextInt(SIZE);
        for (int i = 0; i < SIZE; i++) {
            int p = (start + i) % SIZE;
            if (occupancy[p] < 0 && apples[p] == null) return p;
        }
        return -1;
    }

    private void announce(String text, Color color, double seconds) {
        message = text;
        messageColor = color;
        messageTime = seconds;
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null)
            host.sound(id, attract ? volume * 0.16f : volume,
                    (float) clamp(pitch, 0.5, 2));
    }

    private void burst(double x, double y, Color c, int amount) {
        for (int i = 0; i < amount && particles.size() < 300; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double a = rng.nextDouble() * Math.PI * 2;
            double v = 18 + rng.nextDouble() * 68;
            p.vx = Math.cos(a) * v;
            p.vy = Math.sin(a) * v - 15;
            p.life = p.max = 0.3 + rng.nextDouble() * 0.5;
            p.color = c;
            p.size = 1 + rng.nextInt(3);
            particles.add(p);
        }
    }

    private void popup(double x, double y, String text, Color c) {
        if (popups.size() >= 24) popups.remove(0);
        Popup p = new Popup();
        p.x = clamp(x, BX + 35, BX + BW * CELL - 35);
        p.y = Math.max(BY + 15, y);
        p.text = text;
        p.color = c;
        popups.add(p);
    }

    private void addRing(double x, double y, Color color, double life, double radius) {
        if (rings.size() >= 24) rings.remove(0);
        Ring r = new Ring();
        r.x = x;
        r.y = y;
        r.color = color;
        r.life = r.max = life;
        r.radius = radius;
        rings.add(r);
    }

    private static int dx(int d) {
        return d == 1 ? 1 : d == 3 ? -1 : 0;
    }

    private static int dy(int d) {
        return d == 2 ? 1 : d == 0 ? -1 : 0;
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && x < BW && y >= 0 && y < BH;
    }

    private static int neighbor(int p, int d) {
        int x = p % BW + dx(d), y = p / BW + dy(d);
        return inside(x, y) ? x + y * BW : -1;
    }

    private static double cx(int p) {
        return BX + (p % BW + 0.5) * CELL;
    }

    private static double cy(int p) {
        return BY + (p / BW + 0.5) * CELL;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private void buildBackground() {
        Graphics2D g = background.createGraphics();
        g.setColor(INK);
        g.fillRect(0, 0, W, H);
        Color stoneA = new Color(0x203238), stoneB = new Color(0x192B30);
        for (int y = 48; y < 272; y += 14) {
            for (int x = 0; x < W; x += 16) {
                g.setColor(((x / 16 + y / 14) & 1) == 0 ? stoneA : stoneB);
                g.fillRect(x + 1, y + 1, 14, 12);
            }
        }
        g.setColor(new Color(0x071710));
        g.fillRect(BX - 7, BY - 7, BW * CELL + 14, BH * CELL + 15);
        g.setColor(new Color(0x715036));
        g.fillRect(BX - 4, BY - 4, BW * CELL + 8, BH * CELL + 8);
        for (int x = BX - 4; x < BX + BW * CELL; x += 14) {
            g.setColor(new Color(0xA07A50));
            g.fillRect(x + 1, BY - 4, 7, 2);
            g.setColor(new Color(0x402E28));
            g.fillRect(x + 3, BY + BH * CELL + 1, 7, 2);
        }

        Color a = new Color(0x385F3D), b = new Color(0x3C6440);
        Color light = new Color(0x4B7547), dark = new Color(0x305638);
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BW; x++) {
                int xx = BX + x * CELL, yy = BY + y * CELL;
                g.setColor(((x + y) & 1) == 0 ? a : b);
                g.fillRect(xx, yy, CELL, CELL);
                int hash = (x * 7349) ^ (y * 9151);
                if ((hash & 3) == 0) {
                    g.setColor(light);
                    g.fillRect(xx + 3, yy + 4, 1, 2);
                    g.fillRect(xx + 5, yy + 3, 1, 3);
                }
                if ((hash & 7) == 3) {
                    g.setColor(dark);
                    g.fillRect(xx + 9, yy + 10, 2, 1);
                }
            }
        }
        g.setColor(new Color(0x24492F));
        g.fillRect(BX, BY, BW * CELL, 2);
        g.fillRect(BX, BY, 2, BH * CELL);
        g.setColor(new Color(0x789B53));
        g.fillRect(BX, BY + BH * CELL - 1, BW * CELL, 1);
        g.dispose();
    }

    @Override
    public void draw(Graphics2D output) {
        if (host == null || frame == null) {
            output.setColor(INK);
            output.fillRect(0, 0, cols() * 128, rows() * 128);
            return;
        }
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(background, 0, 0, null);
        Graphics2D world = (Graphics2D) g.create();
        world.clipRect(BX - 3, BY - 3, BW * CELL + 6, BH * CELL + 6);
        world.translate((int) Math.round(Math.sin(clock * 113) * shake),
                (int) Math.round(Math.cos(clock * 137) * shake * 0.7));

        for (int i = 0; i < SIZE; i++)
            if (apples[i] != null) paintApple(world, i, apples[i]);
        for (Ring r : rings) {
            double f = 1 - r.life / r.max;
            int radius = (int) (4 + r.radius * f);
            world.setColor(alpha(r.color, (int) (160 * (1 - f))));
            world.drawRect((int) r.x - radius, (int) r.y - radius, radius * 2, radius * 2);
        }
        for (Snake s : snakes) {
            if (s.alive) paintSnake(world, s);
            else if (!s.waiting && s.deadTime < 1.2) paintDeath(world, s);
        }
        for (Particle p : particles) {
            world.setColor(alpha(p.color, (int) (255 * p.life / p.max)));
            world.fillRect((int) p.x, (int) p.y, p.size, p.size);
        }
        if (flash > 0) {
            world.setColor(alpha(GOLD, (int) (flash * 150)));
            world.fillRect(BX, BY, BW * CELL, BH * CELL);
        }
        world.dispose();
        g.dispose();

        Graphics2D out = (Graphics2D) output.create();
        out.setColor(INK);
        out.fillRect(0, 0, host.width(), host.height());
        out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        out.drawImage(frame, 0, 0, host.width(), host.height(), null);
        out.scale(host.width() / (double) W, host.height() / (double) H);
        out.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        out.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        paintHud(out);
        for (Popup p : popups)
            text(out, p.text, 11, p.x, p.y,
                    alpha(p.color, (int) (255 * Math.min(1, p.life * 3))), 0);

        if (!attract) {
            for (Snake s : snakes) {
                if (!s.alive || s.ready <= 0) continue;
                double x = cx(s.body.get(0)), y = cy(s.body.get(0));
                String label = "P" + (s.color + 1) + " READY";
                panel(out, x - 29, Math.max(BY + 2, y - 28), 58, 16, COLORS[s.color]);
                text(out, label, 9, x, Math.max(BY + 14, y - 16), WHITE, 0);
            }
            if (allOut()) paintGameOver(out);
        }
        paintFooter(out);
        out.dispose();
    }

    private void paintApple(Graphics2D g, int p, Apple a) {
        if (a.life > 0 && a.life < 2 && ((int) (clock * 9) & 1) == 0) return;
        int x = (int) cx(p), y = (int) cy(p);
        g.setColor(new Color(0x29472F));
        g.fillRect(x - 5, y + 4, 10, 3);

        if (a.golden) {
            y += (int) Math.round(Math.sin(clock * 5 + p));
            g.setColor(new Color(0x817338));
            g.drawRect(x - 7, y - 6, 14, 13);
            int phase = ((int) (clock * 7) + p) & 3;
            int sx = x + (phase < 2 ? 9 : -9), sy = y - 7 + phase * 4;
            g.setColor(GOLD);
            g.fillRect(sx - 1, sy, 3, 1);
            g.fillRect(sx, sy - 1, 1, 3);
        }
        int lift = a.age < 0.3 ? (int) ((0.3 - a.age) * 8) : 0;
        y -= lift;
        g.setColor(a.golden ? new Color(0x765020) : new Color(0x5D292E));
        g.fillRect(x - 5, y - 3, 10, 9);
        g.fillRect(x - 4, y - 4, 8, 11);
        g.setColor(a.golden ? GOLD : new Color(0xFA6258));
        g.fillRect(x - 4, y - 3, 8, 8);
        g.fillRect(x - 3, y + 5, 6, 1);
        g.setColor(a.golden ? new Color(0xFFF4B2) : new Color(0xFFC1A0));
        g.fillRect(x - 3, y - 2, 2, 3);
        g.setColor(new Color(0x302425));
        g.fillRect(x, y - 7, 2, 4);
        g.setColor(new Color(0xB2E776));
        g.fillRect(x + 2, y - 6, 3, 2);
    }

    private void paintSnake(Graphics2D g, Snake s) {
        int n = s.body.size();
        if (n == 0) return;
        double t = s.moving ? clamp(s.progress, 0, 1) : 0;
        int head = s.body.get(0);
        int destination = neighbor(head, s.direction);
        boolean extending = s.grow > 0
                || (destination >= 0 && apples[destination] != null);
        int drawn = n + (extending && s.moving ? 1 : 0);

        for (int i = 0; i < n; i++) {
            int p = s.body.get(i);
            double tx = i == 0 ? cx(p) + dx(s.direction) * CELL : cx(s.body.get(i - 1));
            double ty = i == 0 ? cy(p) + dy(s.direction) * CELL : cy(s.body.get(i - 1));
            s.rx[i] = cx(p) + (tx - cx(p)) * t;
            s.ry[i] = cy(p) + (ty - cy(p)) * t;
        }
        if (drawn > n) {
            s.rx[n] = cx(s.body.get(n - 1));
            s.ry[n] = cy(s.body.get(n - 1));
        }

        bodyLayer(g, s, drawn, 13, 2, new Color(0x243B2A));
        bodyLayer(g, s, drawn, s.boost > 0 ? 14 : 12, 0,
                s.boost > 0 ? GOLD : new Color(0x142829));
        bodyLayer(g, s, drawn, 10, 0, DARKS[s.color]);
        bodyLayer(g, s, drawn, 8, -1, COLORS[s.color]);

        for (int i = drawn - 1; i > 0; i--) {
            int x = (int) Math.round(s.rx[i]), y = (int) Math.round(s.ry[i]);
            g.setColor((i & 1) == 0 ? WHITE : DARKS[s.color]);
            g.fillRect(x - 2, y - 3, 3, 2);
            if (s.boost > 0 && ((i + (int) (clock * 12)) & 3) == 0) {
                g.setColor(GOLD);
                g.fillRect(x - 1, y + 6, 2, 2);
            }
        }

        int hx = (int) Math.round(s.rx[0]), hy = (int) Math.round(s.ry[0]);
        int size = s.bite > 0.12 ? 14 : 12;
        g.setColor(new Color(0x13262A));
        square(g, hx, hy, size + 2);
        g.setColor(s.boost > 0 ? GOLD : COLORS[s.color]);
        square(g, hx, hy, size);
        g.setColor(WHITE);
        g.fillRect(hx - 3, hy - size / 2 + 1, 5, 1);

        int fx = dx(s.direction), fy = dy(s.direction);
        int ax = -fy * 3, ay = fx * 3;
        for (int sign = -1; sign <= 1; sign += 2) {
            int ex = hx + fx * 3 + ax * sign;
            int ey = hy + fy * 3 + ay * sign;
            g.setColor(WHITE);
            g.fillRect(ex - 1, ey - 1, 3, 3);
            g.setColor(INK);
            g.fillRect(ex + fx, ey + fy, 1, 1);
        }
        if (((int) (clock * 6) + s.color) % 7 == 0) {
            g.setColor(RED);
            g.fillRect(hx + fx * 8 - (fy != 0 ? 1 : 0),
                    hy + fy * 8 - (fx != 0 ? 1 : 0),
                    fx != 0 ? 3 : 2, fy != 0 ? 3 : 2);
        }
    }

    private void bodyLayer(Graphics2D g, Snake s, int n, int width, int offset, Color c) {
        g.setColor(c);
        for (int i = n - 1; i >= 0; i--) {
            square(g, s.rx[i], s.ry[i] + offset, width);
            if (i + 1 < n) {
                // Current grid cell i is the bend between interpolated segments.
                int knot = s.body.get(Math.min(i, s.body.size() - 1));
                double kx = cx(knot), ky = cy(knot) + offset;
                segment(g, s.rx[i], s.ry[i] + offset, kx, ky, width);
                segment(g, kx, ky, s.rx[i + 1], s.ry[i + 1] + offset, width);
            }
        }
    }

    private static void square(Graphics2D g, double x, double y, int size) {
        g.fillRect((int) Math.round(x) - size / 2,
                (int) Math.round(y) - size / 2, size, size);
    }

    private static void segment(Graphics2D g, double x, double y,
                                double xx, double yy, int width) {
        g.fillRect((int) Math.round(Math.min(x, xx)) - width / 2,
                (int) Math.round(Math.min(y, yy)) - width / 2,
                (int) Math.round(Math.abs(xx - x)) + width,
                (int) Math.round(Math.abs(yy - y)) + width);
    }

    private void paintDeath(Graphics2D g, Snake s) {
        int x = (int) s.deathX, y = (int) (s.deathY - s.deadTime * 11);
        g.setColor(alpha(COLORS[s.color], (int) (220 * (1 - s.deadTime / 1.2))));
        g.fillRect(x - 6, y - 6, 12, 10);
        g.fillRect(x - 4, y + 4, 8, 3);
        g.setColor(INK);
        g.fillRect(x - 4, y - 3, 3, 3);
        g.fillRect(x + 1, y - 3, 3, 3);
        g.fillRect(x - 1, y + 2, 2, 2);
    }

    private void paintHud(Graphics2D g) {
        g.setColor(INK);
        g.fillRect(0, 0, W, 47);
        if (attract) {
            text(g, "SNAKE PIT", 29, 16, 30, COLORS[0], -1);
            text(g, "BITE APPLES. NOT SNAKES.", 10, 18, 43, MUTED, -1);
            List<Host.Score> top = host.scores();
            if (clock % 13 >= 7 && !top.isEmpty()) {
                text(g, "PIT LEGENDS", 10, 281, 14, GOLD, -1);
                for (int i = 0; i < Math.min(2, top.size()); i++) {
                    Host.Score score = top.get(i);
                    int y = 28 + i * 13;
                    text(g, (i + 1) + ". " + fit(g, safeName(score.name()), 10, 145),
                            10, 281, y, i == 0 ? GOLD : WHITE, -1);
                    text(g, Integer.toString(score.score()), 11, 493, y, WHITE, 1);
                }
            } else {
                text(g, "LIVE DEMO • 1–4 PLAYERS", 11, 494, 16, GOLD, 1);
                text(g, "SIT DOWN TO PLAY", 17, 494, 35,
                        clock % 1.3 < 0.95 ? WHITE : COLORS[1], 1);
                text(g, "WASD STEER • GOLD = +5 & SPEED", 9, 494, 46, MUTED, 1);
            }
            return;
        }

        for (int slot = 0; slot < 4; slot++) {
            int x = slot * 128;
            Snake s = snakeForColor(slot);
            g.setColor(new Color(0x1A3035));
            g.fillRoundRect(x + 3, 3, 122, 41, 5, 5);
            g.setColor(s == null ? new Color(0x405D53) : COLORS[slot]);
            g.fillRect(x + 4, 42, 120, 2);
            if (s == null) {
                text(g, "P" + (slot + 1) + " • OPEN", 11, x + 10, 19, MUTED, -1);
                text(g, "SIT TO JOIN", 10, x + 10, 35, MUTED, -1);
                continue;
            }
            text(g, "P" + (slot + 1), 10, x + 9, 17, COLORS[slot], -1);
            text(g, fit(g, safeName(s.name), 11, 87), 11, x + 30, 17, WHITE, -1);
            text(g, fit(g, Integer.toString(s.score), 18, 43), 18,
                    x + 10, 37, s.boost > 0 ? GOLD : COLORS[slot], -1);
            String status;
            Color c = MUTED;
            if (s.waiting) status = "WAITING";
            else if (!s.alive) {
                status = s.deadTime < 1.1 ? "OUT!" : "SPACE / CLICK";
                c = RED;
            } else if (s.ready > 0) {
                status = "GET READY";
                c = WHITE;
            } else if (s.boost > 0) {
                status = "FAST " + (int) Math.ceil(s.boost) + "s";
                c = GOLD;
            } else {
                status = "WASD";
            }
            text(g, status, 9, x + 119, 34, c, 1);
            if (s.boost > 0) {
                g.setColor(GOLD);
                g.fillRect(x + 58, 38, (int) (60 * s.boost / 4.5), 2);
            } else if (s.alive && !s.turns.isEmpty()) {
                StringBuilder turns = new StringBuilder();
                for (int d : s.turns) turns.append("URDL".charAt(d)).append(' ');
                text(g, turns.toString().trim(), 7, x + 119, 41, COLORS[slot], 1);
            }
        }
    }

    private Snake snakeForColor(int color) {
        for (Snake s : snakes) if (s.color == color) return s;
        return null;
    }

    private void paintFooter(Graphics2D g) {
        g.setColor(INK);
        g.fillRect(0, 272, W, 16);
        if (attract) {
            for (Snake s : snakes) {
                String label = s.name + "  " + s.score + (s.alive ? "" : "  OUT");
                text(g, label, 10, 64 + s.color * 128, 284, COLORS[s.color], 0);
            }
        } else {
            String line = messageTime > 0 ? message
                    : "WASD STEER  •  APPLE +1  •  GOLD +5 & SPEED  •  AVOID WALLS & BODIES";
            text(g, fit(g, line, 10, 490), 10, 256, 284,
                    messageTime > 0 ? messageColor : MUTED, 0);
        }
    }

    private boolean allOut() {
        if (snakes.isEmpty()) return false;
        for (Snake s : snakes)
            if (s.alive || s.waiting || s.deadTime < 0.65) return false;
        return true;
    }

    private void paintGameOver(Graphics2D g) {
        int height = 91 + snakes.size() * 22;
        int y = 157 - height / 2;
        panel(g, 108, y, 296, height, RED);
        text(g, "COMPOSTED!", 28, 256, y + 33, RED, 0);
        text(g, "Your body feeds the next run.", 11, 256, y + 52, MUTED, 0);
        for (int i = 0; i < snakes.size(); i++) {
            Snake s = snakes.get(i);
            int row = y + 76 + i * 22;
            text(g, fit(g, s.name, 13, 158), 13, 123, row, COLORS[s.color], -1);
            text(g, Integer.toString(s.score), 14, 332, row, WHITE, 1);
            if (s.rank > 0) text(g, "#" + s.rank, 11, 388, row, GOLD, 1);
        }
        text(g, "SPACE / CLICK TO REPLANT", 12, 256, y + height - 11,
                clock % 1.2 < 0.9 ? WHITE : COLORS[1], 0);
    }

    private static void panel(Graphics2D g, double x, double y,
                              double width, double height, Color rim) {
        g.setColor(new Color(6, 17, 22, 242));
        g.fillRoundRect((int) x, (int) y, (int) width, (int) height, 7, 7);
        g.setStroke(new BasicStroke(1.3f));
        g.setColor(rim);
        g.drawRoundRect((int) x, (int) y, (int) width, (int) height, 7, 7);
    }

    private static String safeName(String name) {
        return name == null ? "PLAYER" : name.toUpperCase(Locale.ROOT);
    }

    private static String fit(Graphics2D g, String value, int size, int width) {
        String text = value == null ? "" : value;
        g.setFont(FONTS[size]);
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > width) {
            int end = text.offsetByCodePoints(text.length(), -1);
            text = text.substring(0, end);
        }
        return text + "…";
    }

    private static void text(Graphics2D g, String value, int size,
                             double x, double y, Color c, int align) {
        String text = value == null ? "" : value;
        g.setFont(FONTS[Math.max(1, Math.min(48, size))]);
        int width = g.getFontMetrics().stringWidth(text);
        double left = align < 0 ? x : align > 0 ? x - width : x - width / 2.0;
        g.setColor(new Color(0, 0, 0, Math.min(170, c.getAlpha())));
        g.drawString(text, (float) left, (float) y + 1);
        g.setColor(c);
        g.drawString(text, (float) left, (float) y);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, a)));
    }

    private static final class Snake {
        Seat seat;
        String name = "SNAKE";
        int color, score, rank, direction = 1, grow;
        boolean bot, alive, waiting, moving;
        double progress, ready, boost, deadTime, retry, bite, deathX, deathY;
        final List<Integer> body = new ArrayList<>();
        final ArrayDeque<Integer> turns = new ArrayDeque<>(2);
        final EnumSet<Key> held = EnumSet.noneOf(Key.class);
        final double[] rx = new double[SIZE + 2];
        final double[] ry = new double[SIZE + 2];
    }

    private static final class Apple {
        final boolean golden;
        double life, age;

        Apple(boolean golden, double life) {
            this.golden = golden;
            this.life = life;
        }
    }

    private static final class Particle {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
    }

    private static final class Popup {
        double x, y, life = 0.85;
        String text;
        Color color;
    }

    private static final class Ring {
        double x, y, life, max, radius;
        Color color;
    }
}

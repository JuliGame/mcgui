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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/*
 * Review of the supplied frames and report:
 *
 * - Performance is already healthy; no exception is reported.
 * - Both humans die early, then spend most of the sample watching bots.
 * - The closing perimeter overwhelms the artwork, produces repeated bass notes,
 *   and ends this round in a draw. It is also unnecessary for the original brief.
 * - Incremental opaque trail strokes overwrite their own highlights, making
 *   continuous light walls look like disconnected, dashed conveyor belts.
 * - Bots mostly draw isolated, parallel paths instead of contesting the arena.
 * - Scores are absent during play, secondary text is small, and player-death
 *   feedback is easy to miss once its short popup disappears.
 * - Joining a running match has confusing bot/player ownership; departing
 *   players also erase their colour's round wins.
 *
 * Improvements:
 * Continuous cell-connected walls, more confrontational space-aware bots,
 * slower opening speed, a larger arena, visible scores, persistent OUT states,
 * brief automatic bot resolution plus an explicit skip, no crushing perimeter,
 * clearer late-join ownership, and once-only score submission per participation.
 *
 * Controls: absolute W/A/S/D. Space or click skips a bot-only showdown and
 * restarts a completed match. Mouse motion deliberately does not steer.
 */
public class LightCycles implements Scene {
    private static final int W = 1024, H = 576;
    private static final int CELL = 12, COLS = 80, ROWS = 32;
    private static final int BX = 32, BY = 126;
    private static final int AW = COLS * CELL, AH = ROWS * CELL;
    private static final int TARGET = 3;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    private static final Color BG = new Color(0x060B16);
    private static final Color FLOOR = new Color(0x0B1525);
    private static final Color PANEL = new Color(0x111F33);
    private static final Color GRID = new Color(0x142339);
    private static final Color MAJOR_GRID = new Color(0x1D3047);
    private static final Color WHITE = new Color(0xF0FAFF);
    private static final Color MUTED = new Color(0xA3B8CE);
    private static final Color GOLD = new Color(0xFFDE8A);
    private static final Color RED = new Color(0xFF6685);
    private static final Color[] COLORS = {
            new Color(0x45E9FF), new Color(0xFFAB4D),
            new Color(0xFF58D6), new Color(0xB5FF62)
    };
    private static final Color[][] WALL = new Color[4][4];
    private static final Font[] FONTS = new Font[97];

    static {
        for (int i = 0; i < FONTS.length; i++) {
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(1, i));
        }
        for (int i = 0; i < 4; i++) {
            WALL[i][0] = mix(FLOOR, COLORS[i], 0.18);
            WALL[i][1] = mix(FLOOR, COLORS[i], 0.48);
            WALL[i][2] = COLORS[i];
            WALL[i][3] = mix(COLORS[i], WHITE, 0.72);
        }
    }

    private enum Phase { COUNTDOWN, RIDE, RESULT, MATCH }

    private static final class Rider {
        Seat seat;
        int x, y, px, py, direction, pending = -1;
        int wins, points, rank;
        boolean alive, waiting, submitted;
        double flash, crashX, crashY;
        final EnumSet<Key> held = EnumSet.noneOf(Key.class);
    }

    private static final class Particle {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
    }

    private static final class Popup {
        double x, y, life = 1.4;
        String text;
        Color color;
    }

    private final Rider[] riders = new Rider[4];
    private final int[] board = new int[COLS * ROWS];
    private final int[] links = new int[COLS * ROWS];
    private final int[] marks = new int[COLS * ROWS];
    private final int[] flood = new int[COLS * ROWS];
    private final int[] nx = new int[4], ny = new int[4];
    private final boolean[] dead = new boolean[4];
    private final List<Particle> particles = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();
    private final Random aiRandom = new Random(0x11C7C1E5L);
    private final Random fxRandom = new Random(0xB10CFACE);

    private Host host;
    private BufferedImage frame, arena;
    private List<Host.Score> best = List.of();
    private Phase phase = Phase.COUNTDOWN;
    private double clock, phaseTime, roundTime, accumulator;
    private double countdownLength, eliminatedTime, shake, bannerTime;
    private double lastCrashSound = -100;
    private int round, winner = -1, champion = -1, stamp, lastCount;
    private boolean warming, skipShowdown;
    private String banner = "";

    public LightCycles() {
        for (int i = 0; i < riders.length; i++) riders[i] = new Rider();
        Arrays.fill(board, -1);
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        this.host = host;
        if (host == null) return;
        frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        arena = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_RGB);

        for (Rider rider : riders) {
            rider.seat = null;
            rider.held.clear();
        }
        List<Seat> seats = host.seats();
        if (seats != null) {
            for (Seat seat : seats) {
                if (valid(seat)) riders[seat.index()].seat = seat;
            }
        }
        refreshScores();
        newMatch();
        if (humans() == 0) warmDemo();
    }

    private static boolean valid(Seat seat) {
        return seat != null && seat.id() != null
                && seat.index() >= 0 && seat.index() < 4;
    }

    private Rider find(Seat seat) {
        if (host == null || !valid(seat)) return null;
        for (Rider rider : riders) {
            if (rider.seat != null && rider.seat.id().equals(seat.id())) return rider;
        }
        return null;
    }

    private int humans() {
        int count = 0;
        for (Rider rider : riders) if (rider.seat != null) count++;
        return count;
    }

    private boolean humanRiding() {
        for (Rider rider : riders) {
            if (rider.seat != null && !rider.waiting && rider.alive) return true;
        }
        return false;
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || frame == null || !valid(seat) || find(seat) != null) return;
        Rider rider = riders[seat.index()];
        if (rider.seat != null) return;
        boolean wasDemo = humans() == 0;

        rider.seat = seat;
        rider.points = rider.rank = 0;
        rider.submitted = false;
        rider.pending = -1;
        rider.held.clear();
        rider.waiting = phase != Phase.COUNTDOWN;

        if (wasDemo) {
            newMatch();
        } else {
            banner = name(seat.index()) + (rider.waiting
                    ? " — TAKES OVER P" + (seat.index() + 1) + " NEXT ROUND"
                    : " — READY ON P" + (seat.index() + 1));
            bannerTime = 2.0;
        }
        sound("block.note_block.chime", 0.45f, 1.3f);
    }

    @Override
    public void left(Seat seat) {
        Rider rider = find(seat);
        if (rider == null) return;
        submit(rider);
        rider.seat = null;
        rider.waiting = false;
        rider.pending = -1;
        rider.points = rider.rank = 0;
        rider.held.clear();
        // Wins belong to the colour for this match; its AI inherits the bike.
        if (humans() == 0) {
            refreshScores();
            newMatch();
            warmDemo();
        }
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        Rider rider = find(seat);
        if (rider == null || key == null) return;
        if (!down) {
            rider.held.remove(key);
            return;
        }
        boolean edge = rider.held.add(key);
        if (key == Key.JUMP && edge) {
            action();
            return;
        }

        int direction = switch (key) {
            case UP -> 0;
            case RIGHT -> 1;
            case DOWN -> 2;
            case LEFT -> 3;
            default -> -1;
        };
        if (direction < 0 || !rider.alive || rider.waiting) return;
        if (phase == Phase.COUNTDOWN) {
            rider.direction = direction;
            rider.pending = -1;
        } else if (phase == Phase.RIDE
                && direction != ((rider.direction + 2) & 3)) {
            rider.pending = direction;
        }
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (find(seat) != null) action();
    }

    private void action() {
        if (phase == Phase.MATCH && phaseTime >= 1.0) {
            newMatch();
        } else if (phase == Phase.RIDE && !humanRiding()) {
            skipShowdown = true;
        }
    }

    private void newMatch() {
        for (Rider rider : riders) {
            rider.wins = rider.points = rider.rank = 0;
            rider.waiting = rider.submitted = false;
            rider.pending = -1;
            // Keep physical held-key state: a held SPACE must not restart twice.
        }
        champion = -1;
        round = 0;
        bannerTime = 0;
        newRound();
    }

    private void newRound() {
        round++;
        phase = Phase.COUNTDOWN;
        countdownLength = humans() == 0 ? 0.75 : round == 1 ? 2.1 : 1.5;
        phaseTime = roundTime = accumulator = eliminatedTime = shake = 0;
        skipShowdown = false;
        winner = -1;
        lastCount = 3;
        Arrays.fill(board, -1);
        Arrays.fill(links, 0);
        particles.clear();
        popups.clear();

        int[][] starts = {
                {15, 9, 1}, {64, 22, 3}, {64, 9, 3}, {15, 22, 1}
        };
        for (int i = 0; i < 4; i++) {
            Rider rider = riders[i];
            rider.x = rider.px = starts[i][0];
            rider.y = rider.py = starts[i][1];
            rider.direction = starts[i][2];
            rider.pending = -1;
            rider.alive = true;
            rider.waiting = false;
            rider.flash = 0;
            board[rider.y * COLS + rider.x] = i;
        }
        rebuildArena();
        sound("block.note_block.hat", 0.35f, 0.8f);
    }

    private void warmDemo() {
        warming = true;
        phase = Phase.RIDE;
        phaseTime = 2;
        for (int i = 0; i < 43 && phase == Phase.RIDE; i++) {
            roundTime += interval();
            moveAll();
        }
        particles.clear();
        popups.clear();
        shake = 0;
        warming = false;
    }

    private double interval() {
        // Beginners get time to read the board; occupied space supplies pressure.
        return 1.0 / Math.min(17.5, 8.5 + roundTime * 0.22);
    }

    @Override
    public void tick(double dt) {
        if (host == null || frame == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(0.1, dt);
        clock += dt;
        phaseTime += dt;
        bannerTime = Math.max(0, bannerTime - dt);
        shake = Math.max(0, shake - 22 * dt);
        updateEffects(dt);

        switch (phase) {
            case COUNTDOWN -> {
                int count = countdownNumber();
                if (count != lastCount) {
                    lastCount = count;
                    sound("block.note_block.hat", 0.4f, count == 2 ? 1.0f : 1.3f);
                }
                if (phaseTime >= countdownLength) {
                    phase = Phase.RIDE;
                    phaseTime = accumulator = 0;
                    sound("block.note_block.bit", 0.55f, 1.7f);
                }
            }
            case RIDE -> {
                boolean spectators = humans() > 0 && !humanRiding();
                eliminatedTime = spectators ? eliminatedTime + dt : 0;
                boolean resolve = spectators && (skipShowdown || eliminatedTime > 2.0);

                if (resolve) {
                    // Bounded work, real simulation, no arbitrary awarded winner.
                    // Permanent trails guarantee that the remaining bots finish.
                    for (int i = 0; i < 24 && phase == Phase.RIDE; i++) {
                        roundTime += interval();
                        moveAll();
                    }
                    accumulator = 0;
                } else {
                    accumulator += dt * (spectators ? 2.5 : 1.0);
                    int steps = 0;
                    while (phase == Phase.RIDE && accumulator >= interval() && steps++ < 8) {
                        double step = interval();
                        accumulator -= step;
                        roundTime += step;
                        moveAll();
                    }
                }
            }
            case RESULT -> {
                if (phaseTime >= (humans() == 0 ? 1.0 : 1.35)) {
                    if (winner >= 0 && riders[winner].wins >= TARGET) finish(winner);
                    else newRound();
                }
            }
            case MATCH -> {
                if (humans() == 0 && phaseTime > 2.5) {
                    newMatch();
                    warmDemo();
                }
            }
        }
    }

    private int countdownNumber() {
        return Math.max(1, 3 - (int) (phaseTime / (countdownLength / 3.0)));
    }

    private void updateEffects(double dt) {
        for (Rider rider : riders) rider.flash = Math.max(0, rider.flash - dt);
        double drag = Math.exp(-2.8 * dt);
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
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup p = popups.get(i);
            p.life -= dt;
            p.y -= 17 * dt;
            if (p.life <= 0) popups.remove(i);
        }
    }

    private void moveAll() {
        if (arena == null) return;
        Arrays.fill(dead, false);

        for (int i = 0; i < 4; i++) {
            Rider rider = riders[i];
            if (!rider.alive) continue;
            if (rider.seat == null || rider.waiting) {
                rider.direction = botDirection(i);
            } else if (rider.pending >= 0) {
                if (rider.pending != ((rider.direction + 2) & 3)) {
                    rider.direction = rider.pending;
                }
                rider.pending = -1;
            }
            nx[i] = rider.x + DX[rider.direction];
            ny[i] = rider.y + DY[rider.direction];
            dead[i] = !empty(nx[i], ny[i]);
        }

        // Every intention is tested against the same board, before any commits.
        for (int i = 0; i < 4; i++) {
            if (!riders[i].alive) continue;
            for (int j = i + 1; j < 4; j++) {
                if (riders[j].alive && nx[i] == nx[j] && ny[i] == ny[j]) {
                    dead[i] = dead[j] = true;
                }
            }
        }

        Graphics2D g = arena.createGraphics();
        boolean crashed = false;
        for (int i = 0; i < 4; i++) {
            Rider rider = riders[i];
            if (!rider.alive) continue;
            if (dead[i]) {
                rider.alive = false;
                rider.pending = -1;
                rider.flash = 1.0;
                rider.crashX = BX + (rider.x + 0.5 + DX[rider.direction] * 0.45) * CELL;
                rider.crashY = BY + (rider.y + 0.5 + DY[rider.direction] * 0.45) * CELL;
                burst(rider.crashX, rider.crashY, COLORS[i], 36);
                popup(rider.crashX, rider.crashY - 24, "P" + (i + 1) + "  OUT", COLORS[i]);
                crashed = true;
            } else {
                int from = rider.y * COLS + rider.x;
                rider.px = rider.x;
                rider.py = rider.y;
                rider.x = nx[i];
                rider.y = ny[i];
                int to = rider.y * COLS + rider.x;
                board[to] = i;
                links[from] |= 1 << rider.direction;
                links[to] |= 1 << ((rider.direction + 2) & 3);
                paintCell(g, rider.px, rider.py);
                paintCell(g, rider.x, rider.y);
                if (!rider.waiting) rider.points++;
            }
        }
        g.dispose();

        if (crashed) {
            shake = Math.max(shake, 4.5);
            if (clock - lastCrashSound > 0.12) {
                sound("entity.generic.explode", 0.35f, 1.5f);
                lastCrashSound = clock;
            }
        }

        int survivors = 0, survivor = -1;
        for (int i = 0; i < 4; i++) {
            if (riders[i].alive) {
                survivors++;
                survivor = i;
            }
        }
        if (survivors <= 1) {
            phase = Phase.RESULT;
            phaseTime = accumulator = 0;
            winner = survivor;
            if (winner >= 0) {
                Rider rider = riders[winner];
                rider.wins++;
                if (!rider.waiting) rider.points += 500;
                burst(BX + rider.x * CELL + 6, BY + rider.y * CELL + 6,
                        COLORS[winner], 48);
                sound("entity.player.levelup", 0.5f, 1.4f);
            }
        }
    }

    private void finish(int index) {
        champion = index;
        phase = Phase.MATCH;
        phaseTime = 0;
        for (Rider rider : riders) submit(rider);
        refreshScores();
        sound("ui.toast.challenge_complete", 0.55f, 1.1f);
    }

    private void submit(Rider rider) {
        if (host != null && rider.seat != null && !rider.submitted) {
            rider.submitted = true;
            rider.rank = host.score(rider.seat, rider.points);
        }
    }

    private boolean empty(int x, int y) {
        return x >= 0 && y >= 0 && x < COLS && y < ROWS
                && board[y * COLS + x] == -1;
    }

    private int botDirection(int index) {
        Rider rider = riders[index];
        int targetX = COLS / 2, targetY = ROWS / 2, closest = Integer.MAX_VALUE;
        for (int j = 0; j < 4; j++) {
            Rider other = riders[j];
            if (j == index || !other.alive) continue;
            int distance = Math.abs(other.x - rider.x) + Math.abs(other.y - rider.y);
            if (distance < closest) {
                closest = distance;
                targetX = clamp(other.x + DX[other.direction] * 5, 3, COLS - 4);
                targetY = clamp(other.y + DY[other.direction] * 5, 3, ROWS - 4);
            }
        }

        int chosen = rider.direction;
        double bestValue = -Double.MAX_VALUE;
        for (int turn = -1; turn <= 1; turn++) {
            int direction = (rider.direction + turn + 4) & 3;
            int x = rider.x + DX[direction], y = rider.y + DY[direction];
            if (!empty(x, y)) continue;

            int runway = 0;
            while (runway < 9
                    && empty(x + DX[direction] * runway, y + DY[direction] * runway)) {
                runway++;
            }
            double value = space(x, y) * 2.5 + runway * 0.55
                    - (Math.abs(x - targetX) + Math.abs(y - targetY)) * 0.7
                    + (turn == 0 ? 1.5 : 0) + aiRandom.nextDouble() * 1.2;
            for (int j = 0; j < 4; j++) {
                Rider other = riders[j];
                if (j == index || !other.alive) continue;
                int distance = Math.abs(other.x - x) + Math.abs(other.y - y);
                if (distance == 1) value -= 250;
                if (x == other.x + DX[other.direction]
                        && y == other.y + DY[other.direction]) value -= 150;
            }
            if (value > bestValue) {
                bestValue = value;
                chosen = direction;
            }
        }
        return chosen;
    }

    private int space(int x, int y) {
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(marks, 0);
            stamp = 1;
        }
        int read = 0, write = 1;
        flood[0] = y * COLS + x;
        marks[flood[0]] = stamp;
        final int limit = 120;
        while (read < write && write < limit) {
            int cell = flood[read++];
            int xx = cell % COLS, yy = cell / COLS;
            for (int d = 0; d < 4 && write < limit; d++) {
                int ax = xx + DX[d], ay = yy + DY[d];
                if (!empty(ax, ay)) continue;
                int next = ay * COLS + ax;
                if (marks[next] == stamp) continue;
                marks[next] = stamp;
                flood[write++] = next;
            }
        }
        return write;
    }

    private void rebuildArena() {
        if (arena == null) return;
        Graphics2D g = arena.createGraphics();
        g.setColor(FLOOR);
        g.fillRect(0, 0, AW, AH);
        for (int x = 0; x < COLS; x++) {
            g.setColor(x % 8 == 0 ? MAJOR_GRID : GRID);
            g.drawLine(x * CELL, 0, x * CELL, AH);
        }
        for (int y = 0; y < ROWS; y++) {
            g.setColor(y % 8 == 0 ? MAJOR_GRID : GRID);
            g.drawLine(0, y * CELL, AW, y * CELL);
        }
        for (Rider rider : riders) paintCell(g, rider.x, rider.y);
        g.dispose();
    }

    /*
     * Each tile owns its pixels. Connections extend exactly to the tile edge,
     * so repainting a corner cannot erase the neighbouring tile's bright core.
     */
    private void paintCell(Graphics2D g, int x, int y) {
        int cell = y * COLS + x;
        int owner = board[cell];
        if (owner < 0) return;
        int sx = x * CELL, sy = y * CELL, mask = links[cell];

        g.setColor(FLOOR);
        g.fillRect(sx, sy, CELL, CELL);
        g.setColor(x % 8 == 0 ? MAJOR_GRID : GRID);
        g.fillRect(sx, sy, 1, CELL);
        g.setColor(y % 8 == 0 ? MAJOR_GRID : GRID);
        g.fillRect(sx, sy, CELL, 1);

        for (int layer = 0; layer < 4; layer++) {
            int half = 5 - layer;
            if (layer == 3) half = 1;
            int left = (mask & 8) != 0 ? 0 : 6 - half;
            int right = (mask & 2) != 0 ? CELL : 6 + half;
            int top = (mask & 1) != 0 ? 0 : 6 - half;
            int bottom = (mask & 4) != 0 ? CELL : 6 + half;
            g.setColor(WALL[owner][layer]);
            g.fillRect(sx + left, sy + 6 - half, right - left, half * 2);
            g.fillRect(sx + 6 - half, sy + top, half * 2, bottom - top);
        }
    }

    private void burst(double x, double y, Color color, int count) {
        if (warming) return;
        for (int i = 0; i < count && particles.size() < 240; i++) {
            Particle p = new Particle();
            double angle = fxRandom.nextDouble() * Math.PI * 2;
            double speed = 45 + fxRandom.nextDouble() * 220;
            p.x = x;
            p.y = y;
            p.vx = Math.cos(angle) * speed;
            p.vy = Math.sin(angle) * speed;
            p.max = p.life = 0.35 + fxRandom.nextDouble() * 0.7;
            p.size = 2 + fxRandom.nextInt(4);
            p.color = i % 5 == 0 ? WHITE : color;
            particles.add(p);
        }
    }

    private void popup(double x, double y, String message, Color color) {
        if (warming) return;
        Popup popup = new Popup();
        popup.x = Math.max(BX + 85, Math.min(BX + AW - 85, x));
        popup.y = Math.max(BY + 36, y);
        popup.text = message;
        popup.color = color;
        popups.add(popup);
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && !warming && humans() > 0) host.sound(id, volume, pitch);
    }

    private void refreshScores() {
        if (host == null) return;
        List<Host.Score> scores = host.scores();
        best = scores == null ? List.of() : new ArrayList<>(scores);
    }

    @Override
    public void draw(Graphics2D graphics) {
        if (graphics == null) return;
        if (host == null || frame == null) {
            graphics.setColor(BG);
            graphics.fillRect(0, 0, 2048, 1152);
            return;
        }

        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setColor(PANEL);
        g.fillRect(0, 0, W, 49);
        g.setColor(alpha(COLORS[0], 80));
        g.fillRect(32, 47, 960, 1);

        text(g, "LIGHT CYCLES", 33, 32, 37, WHITE);
        text(g, humans() == 0 ? "LIVE DEMO" : "ROUND " + round,
                18, 345, 35, COLORS[0]);
        right(g, "LAST RIDER WINS  /  FIRST TO " + TARGET, 18, 992, 35, MUTED);

        for (int i = 0; i < 4; i++) card(g, i);

        Graphics2D world = (Graphics2D) g.create();
        world.clipRect(BX - 6, BY - 6, AW + 12, AH + 12);
        world.translate(Math.sin(clock * 83) * shake, Math.cos(clock * 61) * shake * 0.6);
        world.drawImage(arena, BX, BY, null);
        world.setStroke(new BasicStroke(2));
        world.setColor(new Color(0x41627E));
        world.drawRect(BX - 2, BY - 2, AW + 4, AH + 4);

        for (int corner = 0; corner < 4; corner++) {
            int x = (corner & 1) == 0 ? BX - 3 : BX + AW + 3;
            int y = corner < 2 ? BY - 3 : BY + AH + 3;
            int sx = (corner & 1) == 0 ? 1 : -1;
            int sy = corner < 2 ? 1 : -1;
            world.setColor(COLORS[corner]);
            world.drawLine(x, y, x + sx * 28, y);
            world.drawLine(x, y, x, y + sy * 28);
        }

        for (int i = 0; i < 4; i++) {
            if (riders[i].alive) cycle(world, i);
            else wreck(world, i);
        }
        for (Particle p : particles) {
            world.setColor(alpha(p.color, (int) (255 * p.life / p.max)));
            world.fillRect((int) p.x, (int) p.y, p.size, p.size);
        }
        for (Popup popup : popups) {
            int opacity = (int) (255 * Math.min(1, popup.life * 2));
            world.setColor(alpha(BG, opacity));
            world.fillRoundRect((int) popup.x - 72, (int) popup.y - 24,
                    144, 34, 8, 8);
            center(world, popup.text, 22, popup.x, popup.y,
                    alpha(popup.color, opacity));
        }
        world.dispose();

        if (phase == Phase.COUNTDOWN) countdown(g);
        else if (phase == Phase.RESULT) result(g);
        else if (phase == Phase.MATCH) match(g);
        else if (phaseTime < 0.5) {
            center(g, "RIDE!", 68, W / 2.0, 328,
                    alpha(WHITE, (int) (255 * (1 - phaseTime / 0.5))));
        }

        if (bannerTime > 0 && phase != Phase.MATCH && phase != Phase.RESULT) {
            box(g, 205, 468, 614, 35, COLORS[0]);
            fitCenter(g, banner, 18, 570, 512, 493, WHITE);
        }
        footer(g);
        g.dispose();

        Pixels.drawSmooth(graphics, frame, 0, 0, Math.max(1, host.width()), Math.max(1, host.height()));
    }

    private void card(Graphics2D g, int index) {
        Rider rider = riders[index];
        Color color = COLORS[index];
        int x = 32 + index * 244;
        g.setColor(rider.flash > 0 ? mix(PANEL, color, rider.flash * 0.3) : PANEL);
        g.fillRoundRect(x, 57, 228, 59, 8, 8);
        g.setColor(color);
        g.fillRect(x, 63, 4, 46);
        text(g, "P" + (index + 1), 15, x + 11, 78, color);
        fitText(g, name(index), 21, 176, x + 43, 80,
                rider.alive || rider.waiting ? WHITE : MUTED);

        for (int n = 0; n < TARGET; n++) {
            g.setColor(n < rider.wins ? color : new Color(0x344258));
            g.fillRoundRect(x + 12 + n * 15, 96, 10, 10, 2, 2);
        }
        text(g, Integer.toString(rider.points), 16, x + 63, 107, WHITE);
        String state = rider.waiting ? "NEXT ROUND"
                : phase == Phase.MATCH ? (index == champion ? "CHAMPION" : "FINISHED")
                : !rider.alive ? "OUT"
                : phase == Phase.RESULT ? "WINNER"
                : phase == Phase.COUNTDOWN ? "READY"
                : rider.seat == null ? "AI" : "RIDING";
        right(g, state, 14, x + 216, 107, !rider.alive ? MUTED : color);
    }

    private void cycle(Graphics2D g, int index) {
        Rider rider = riders[index];
        boolean accelerated = humans() > 0 && !humanRiding();
        double fraction = phase == Phase.RIDE && !accelerated
                ? Math.max(0, Math.min(1, accumulator / interval())) : 1;
        double cx = BX + (rider.px + (rider.x - rider.px) * fraction + 0.5) * CELL;
        double cy = BY + (rider.py + (rider.y - rider.py) * fraction + 0.5) * CELL;
        Color color = COLORS[index];

        Graphics2D c = (Graphics2D) g.create();
        c.translate(cx, cy);
        c.rotate(rider.direction * Math.PI / 2);
        c.setColor(alpha(color, 22));
        c.fillOval(-23, -23, 46, 46);
        c.setColor(alpha(color, 40));
        c.fillOval(-15, -15, 30, 30);
        c.setColor(BG);
        c.fillRoundRect(-9, -12, 18, 25, 5, 5);
        c.setColor(color);
        c.fillRect(-9, -4, 4, 15);
        c.fillRect(5, -4, 4, 15);
        c.fillPolygon(new int[]{0, 5, 4, -4, -5},
                new int[]{-13, -5, 9, 9, -5}, 5);
        c.setColor(WHITE);
        c.fillRect(-2, -9, 4, 9);
        c.setColor(color.darker());
        c.fillRect(-2, 2, 4, 5);
        if (phase == Phase.RIDE) {
            int exhaust = 5 + (int) (3 * (1 + Math.sin(clock * 36 + index)));
            c.setColor(alpha(color, 150));
            c.fillRect(-2, 11, 4, exhaust);
            c.setColor(WHITE);
            c.fillRect(-1, 11, 2, 4);
        }
        c.dispose();

        if (phase == Phase.COUNTDOWN || (rider.seat != null && !rider.waiting)) {
            double labelY = cy < BY + 42 ? cy + 38 : cy - 23;
            g.setColor(alpha(BG, 235));
            g.fillRoundRect((int) cx - 20, (int) labelY - 19, 40, 25, 6, 6);
            center(g, "P" + (index + 1), 17, cx, labelY, color);
        }
        if (phase == Phase.COUNTDOWN) {
            int dx = DX[rider.direction], dy = DY[rider.direction];
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            g.drawLine((int) cx + dx * 23, (int) cy + dy * 23,
                    (int) cx + dx * 49, (int) cy + dy * 49);
            g.fillOval((int) cx + dx * 49 - 3, (int) cy + dy * 49 - 3, 6, 6);
        }
    }

    private void wreck(Graphics2D g, int index) {
        Rider rider = riders[index];
        int x = BX + rider.x * CELL + 6;
        int y = BY + rider.y * CELL + 6;
        g.setColor(BG);
        g.fillRect(x - 7, y - 7, 14, 14);
        g.setStroke(new BasicStroke(2));
        g.setColor(COLORS[index]);
        g.drawLine(x - 5, y - 5, x + 5, y + 5);
        g.drawLine(x - 5, y + 5, x + 5, y - 5);
        if (rider.flash > 0) {
            int radius = (int) (12 + (1 - rider.flash) * 58);
            g.setColor(alpha(COLORS[index], (int) (rider.flash * 170)));
            g.drawOval((int) rider.crashX - radius, (int) rider.crashY - radius,
                    radius * 2, radius * 2);
        }
    }

    private void countdown(Graphics2D g) {
        box(g, 350, 230, 324, 159, COLORS[0]);
        center(g, "CHOOSE YOUR HEADING", 20, 512, 259, COLORS[0]);
        double beat = countdownLength / 3;
        int size = 70 + (int) (10 * Math.exp(-10 * (phaseTime % beat)));
        center(g, Integer.toString(countdownNumber()), size, 512, 335, WHITE);
        center(g, "W / A / S / D  •  NO REVERSE", 18, 512, 370, WHITE);
    }

    private void result(Graphics2D g) {
        Color color = winner < 0 ? RED : COLORS[winner];
        box(g, 242, 245, 540, 133, color);
        fitCenter(g, winner < 0 ? "DOUBLE K.O." : riderLabel(winner) + " WINS",
                38, 495, 512, 294, color);
        center(g, winner < 0 ? "Trails stay deadly. Run it back!"
                : riders[winner].waiting ? "AUTOPILOT WIN FOR P" + (winner + 1)
                : "+500 POINTS  /  " + riders[winner].wins + " OF " + TARGET + " WINS",
                21, 512, 331, WHITE);
        center(g, winner >= 0 && riders[winner].wins >= TARGET
                ? "MATCH COMPLETE" : "NEXT ROUND COMING UP", 17, 512, 359, MUTED);
    }

    private void match(Graphics2D g) {
        if (champion < 0) return;
        Color color = COLORS[champion];
        box(g, 224, 179, 576, 290, color);
        center(g, "GRID CHAMPION", 21, 512, 213, MUTED);
        fitCenter(g, riderLabel(champion), 43, 515, 512, 262, color);
        for (int i = 0; i < 4; i++) {
            Rider rider = riders[i];
            int y = 301 + i * 30;
            fitText(g, "P" + (i + 1) + "  " + name(i), 19, 240, 256, y, COLORS[i]);
            right(g, rider.wins + " W  /  " + rider.points
                    + (rider.rank > 0 ? "  #" + rider.rank : ""),
                    19, 765, y, WHITE);
        }
        if (phaseTime >= 1) {
            center(g, humans() == 0 ? "SIT TO CHALLENGE THE GRID"
                    : "SPACE OR CLICK  •  NEW MATCH", 22, 512, 446,
                    clock % 1.2 < 0.9 ? WHITE : MUTED);
        }
    }

    private void footer(Graphics2D g) {
        if (humans() == 0) {
            center(g, "SIT TO RIDE  •  1–4 PLAYERS  •  W A S D TO STEER",
                    22, 512, 542, WHITE);
            if (!best.isEmpty() && clock % 12 >= 4) {
                int index = ((int) (clock / 3)) % Math.min(3, best.size());
                Host.Score score = best.get(index);
                fitCenter(g, "CABINET #" + (index + 1) + "  "
                        + shorten(score.name(), 22) + "  /  " + score.score(),
                        18, 920, 512, 568, GOLD);
            } else {
                center(g, "Leave a wall. Cut them off. Be the last light standing.",
                        18, 512, 568, MUTED);
            }
            return;
        }

        text(g, "W ↑   A ←   S ↓   D →", 21, 32, 542, WHITE);
        right(g, "NO REVERSE  /  +500 PER WIN", 19, 992, 542, COLORS[0]);

        String message;
        Color color = MUTED;
        if (phase == Phase.RIDE && !humanRiding()) {
            message = skipShowdown || eliminatedTime > 2
                    ? "RESOLVING BOT DUEL  •  YOU RIDE NEXT ROUND"
                    : "PLAYERS OUT  •  SPACE OR CLICK TO SKIP THE BOT DUEL";
            color = GOLD;
        } else if (phase == Phase.RIDE) {
            message = "EVERY WALL IS DEADLY  •  "
                    + (int) Math.round(1.0 / interval()) + " CELLS / SEC"
                    + "  •  SPEED BUILDS AS YOU SURVIVE";
        } else if (phase == Phase.COUNTDOWN) {
            message = "PICK A DIRECTION NOW  •  EMPTY COLOURS ARE AI RIDERS";
        } else {
            message = "THREE WINS TAKES THE MATCH  •  YOUR COLOUR'S PIPS TRACK ITS WINS";
        }
        fitCenter(g, message, 18, 960, 512, 568, color);
    }

    private String name(int index) {
        Rider rider = riders[index];
        return rider.seat == null ? "BOT " + (index + 1) : shorten(rider.seat.name(), 16);
    }

    private String riderLabel(int index) {
        return riders[index].waiting ? "BOT " + (index + 1) : name(index);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String shorten(String value, int limit) {
        if (value == null || value.isEmpty()) return "RIDER";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static Color alpha(Color color, int opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                clamp(opacity, 0, 255));
    }

    private static Color mix(Color a, Color b, double amount) {
        amount = Math.max(0, Math.min(1, amount));
        return new Color(
                (int) (a.getRed() * (1 - amount) + b.getRed() * amount),
                (int) (a.getGreen() * (1 - amount) + b.getGreen() * amount),
                (int) (a.getBlue() * (1 - amount) + b.getBlue() * amount));
    }

    private static void box(Graphics2D g, int x, int y, int w, int h, Color color) {
        g.setColor(new Color(4, 10, 22, 245));
        g.fillRoundRect(x, y, w, h, 14, 14);
        g.setStroke(new BasicStroke(6));
        g.setColor(alpha(color, 35));
        g.drawRoundRect(x, y, w, h, 14, 14);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(color);
        g.drawRoundRect(x, y, w, h, 14, 14);
    }

    private static int width(Graphics2D g, String s, int size) {
        g.setFont(FONTS[clamp(size, 1, 96)]);
        return g.getFontMetrics().stringWidth(s);
    }

    private static void text(Graphics2D g, String s, int size,
                             double x, double y, Color color) {
        g.setFont(FONTS[clamp(size, 1, 96)]);
        g.setColor(color);
        g.drawString(s, (float) x, (float) y);
    }

    private static void center(Graphics2D g, String s, int size,
                               double x, double y, Color color) {
        text(g, s, size, x - width(g, s, size) / 2.0, y, color);
    }

    private static void right(Graphics2D g, String s, int size,
                              double x, double y, Color color) {
        text(g, s, size, x - width(g, s, size), y, color);
    }

    private static void fitText(Graphics2D g, String s, int size, int maxWidth,
                                double x, double y, Color color) {
        while (size > 13 && width(g, s, size) > maxWidth) size--;
        while (s.length() > 2 && width(g, s, size) > maxWidth) {
            s = s.substring(0, s.length() - 2) + "…";
        }
        text(g, s, size, x, y, color);
    }

    private static void fitCenter(Graphics2D g, String s, int size, int maxWidth,
                                  double x, double y, Color color) {
        while (size > 14 && width(g, s, size) > maxWidth) size--;
        while (s.length() > 2 && width(g, s, size) > maxWidth) {
            s = s.substring(0, s.length() - 2) + "…";
        }
        center(g, s, size, x, y, color);
    }
}

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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Creeper Sweeper: Minesweeper with creepers, for one player.
 *
 * Left click digs a block, or sweeps round a number whose neighbours are all
 * marked; right click plants or removes a banner on a suspect. WASD moves the
 * selection, space digs it and F plants a banner. Digging a creeper sets it off. With
 * nobody seated, the board solves itself.
 */
public class CreeperSweeper implements Scene {
    private static final int W = 1200, H = 700;
    private static final int COLS = 16, ROWS = 10, CELL = 48;
    private static final int COUNT = COLS * ROWS, MINES = 28, SAFE = COUNT - MINES;
    private static final int BX = 34, BY = 148, BW = COLS * CELL, BH = ROWS * CELL;
    private static final int SX = 832, SW = 336;

    private static final Color INK = new Color(0x102D29);
    private static final Color DARK = new Color(0x09221F);
    private static final Color WHITE = new Color(0xFFF3D7);
    private static final Color MINT = new Color(0xADE6B2);
    private static final Color GOLD = new Color(0xFFE095);
    private static final Color RED = new Color(0xFF9D87);
    private static final Color EDGE = new Color(0x446D59);
    private static final Color MUTED = new Color(0x85AD94);
    private static final Color[] NUM = {
            INK, new Color(0x194CC5), new Color(0x19632B),
            new Color(0xB32328), new Color(0x332279), new Color(0x80252B),
            new Color(0x006776), new Color(0x202625), new Color(0x63584A)
    };

    private static final Font TITLE = font(47);
    private static final Font LARGE = font(36);
    private static final Font HEADING = font(25);
    private static final Font BODY = font(22);
    private static final Font SMALL = font(19);
    private static final Font LABEL = font(17);
    private static final Font NUMBER = new Font(Font.MONOSPACED, Font.BOLD, 34);
    private static final Font DIGITS = new Font(Font.MONOSPACED, Font.BOLD, 36);

    private enum Mode { READY, PLAY, FUSE, LOST, WON }

    private Host host;
    private Seat player;
    private Mode mode = Mode.READY;

    private final Random boards = new Random(0xC4EE9EEL);
    private final Random effects = new Random(71931);
    private final Random demoRandom = new Random(590);
    private final boolean[] mines = new boolean[COUNT];
    private final boolean[] open = new boolean[COUNT];
    private final boolean[] flags = new boolean[COUNT];
    private final boolean[] dirty = new boolean[COUNT];
    private final int[] adjacent = new int[COUNT];
    private final int[] queue = new int[COUNT];
    private final int[] variant = new int[COUNT];
    private final double[] age = new double[COUNT];
    private final double[] flagAge = new double[COUNT];
    private final EnumSet<Key> held = EnumSet.noneOf(Key.class);
    private final List<Particle> particles = new ArrayList<>();
    private List<Host.Score> leaders = List.of();

    private BufferedImage background, boardImage;
    private final BufferedImage[] grass = new BufferedImage[8];
    private final BufferedImage[][] dirt = new BufferedImage[2][9];

    private boolean generated, submitted, keyboardSelection;
    private int dug, flagCount, hover = -1, detonated = -1;
    private int rank, finalScore, feedbackCell = -1, demoTarget = -1;
    private double clock, elapsed, modeTime, shake, flash;
    private double keyWait, soundWait, scoreWait, feedbackTime, confettiWait;
    private double demoWait, demoX, demoY, demoTap;
    private double mouseX = -1000, mouseY = -1000;
    private double actionX, actionY, actionAge = 10;
    private String feedback = "";
    private Color feedbackColor = MINT;

    public CreeperSweeper() {}

    @Override public int cols() { return 12; }
    @Override public int rows() { return 7; }
    @Override public int fps() { return 30; }
    @Override public int maxPlayers() { return 1; }
    @Override public boolean showPointer() { return true; }

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        makeArt();
        player = host.seats().isEmpty() ? null : host.seats().get(0);
        refreshScores();
        reset();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null) return;
        if (player != null) {
            if (player.id().equals(seat.id())) player = seat;
            return;
        }
        player = seat;
        reset();
        sound("block.note_block.chime", 0.55f, 1.2f);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        player = null;
        reset();
        refreshScores();
    }

    private boolean owns(Seat seat) {
        return host != null && seat != null && player != null
                && player.id().equals(seat.id());
    }

    private boolean finished() {
        return mode == Mode.LOST || mode == Mode.WON;
    }

    private void reset() {
        Arrays.fill(mines, false);
        Arrays.fill(open, false);
        Arrays.fill(flags, false);
        Arrays.fill(dirty, true);
        Arrays.fill(adjacent, 0);
        Arrays.fill(age, 10);
        Arrays.fill(flagAge, 10);
        held.clear();
        particles.clear();
        generated = submitted = keyboardSelection = false;
        mode = Mode.READY;
        elapsed = modeTime = shake = flash = demoTap = 0;
        keyWait = soundWait = confettiWait = 0;
        dug = flagCount = rank = finalScore = 0;
        hover = detonated = demoTarget = feedbackCell = -1;
        feedbackTime = 0;
        feedback = "";
        demoWait = 0.3;
        demoX = BX + BW * 0.5;
        demoY = BY + BH * 0.5;
        actionAge = 10;
        mouseX = mouseY = -1000;
    }

    private double logicalX(int x) {
        return host == null || host.width() <= 0 ? -1 : x * (double) W / host.width();
    }

    private double logicalY(int y) {
        return host == null || host.height() <= 0 ? -1 : y * (double) H / host.height();
    }

    private int cellAt(double x, double y) {
        if (x < BX || y < BY || x >= BX + BW || y >= BY + BH) return -1;
        return (int) ((y - BY) / CELL) * COLS + (int) ((x - BX) / CELL);
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        if (!owns(seat)) return;
        double px = logicalX(x), py = logicalY(y);
        // A real mouse movement takes ownership. Stationary pointer reports
        // do not cancel keyboard selection.
        if (!keyboardSelection || Math.hypot(px - mouseX, py - mouseY) >= 3) {
            keyboardSelection = false;
            hover = cellAt(px, py);
            mouseX = px;
            mouseY = py;
        }
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (!owns(seat)) return;
        mouseX = logicalX(x);
        mouseY = logicalY(y);
        keyboardSelection = false;
        hover = cellAt(mouseX, mouseY);

        if (finished()) {
            if (!right && modeTime > 1
                    && mouseX >= SX + 20 && mouseX < SX + SW - 20
                    && mouseY >= 548 && mouseY < 607) reset();
            return;
        }
        if (mode == Mode.FUSE || hover < 0) return;
        if (right) toggleFlag(hover);
        else dig(hover, true);
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (!owns(seat) || key == null) return;
        if (!down) {
            held.remove(key);
            return;
        }
        if (!held.add(key)) return;

        if (key == Key.JUMP) {
            if (finished()) {
                if (modeTime > 1) {
                    reset();
                    held.add(Key.JUMP);
                }
            } else {
                if (hover < 0) hover = 4 * COLS + 7;
                dig(hover, true);
            }
        } else if (key == Key.SWAP_HANDS) {
            if (hover >= 0) toggleFlag(hover);
        } else if (direction(key)) {
            keyboardSelection = true;
            moveSelection(key);
            keyWait = 0.3;
        }
    }

    private static boolean direction(Key key) {
        return key == Key.UP || key == Key.DOWN || key == Key.LEFT || key == Key.RIGHT;
    }

    private void moveSelection(Key key) {
        if (finished() || mode == Mode.FUSE) return;
        if (hover < 0) {
            hover = 4 * COLS + 7;
            return;
        }
        int c = hover % COLS, r = hover / COLS;
        switch (key) {
            case UP -> r = Math.max(0, r - 1);
            case DOWN -> r = Math.min(ROWS - 1, r + 1);
            case LEFT -> c = Math.max(0, c - 1);
            case RIGHT -> c = Math.min(COLS - 1, c + 1);
            default -> {}
        }
        hover = r * COLS + c;
    }

    private void generate(int first) {
        int[] candidates = new int[COUNT];
        int n = 0;
        for (int i = 0; i < COUNT; i++) {
            if (Math.abs(i % COLS - first % COLS) > 1
                    || Math.abs(i / COLS - first / COLS) > 1) {
                candidates[n++] = i;
            }
        }
        // The entire first-click neighborhood is safe, guaranteeing a zero.
        for (int i = 0; i < MINES; i++) {
            int j = i + boards.nextInt(n - i);
            int temp = candidates[i];
            candidates[i] = candidates[j];
            candidates[j] = temp;
            mines[candidates[i]] = true;
        }
        for (int i = 0; i < COUNT; i++) {
            int c = i % COLS, r = i / COLS;
            for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
                for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                    if (mines[y * COLS + x]) adjacent[i]++;
                }
            }
        }
        generated = true;
        mode = Mode.PLAY;
        modeTime = 0;
    }

    private void toggleFlag(int i) {
        if (i < 0 || i >= COUNT || open[i] || finished() || mode == Mode.FUSE) return;
        if (!flags[i] && flagCount >= MINES) {
            notify("All 28 banners are placed. Move one to mark here.", GOLD, i);
            sound("block.note_block.bass", 0.3f, 0.7f);
            return;
        }
        flags[i] = !flags[i];
        flagCount += flags[i] ? 1 : -1;
        flagAge[i] = 0;
        markAction(i);
        burst(cx(i), cy(i), 7, flags[i] ? RED : MINT, false);
        notify(flags[i] ? "Suspect marked. A banner is a guess, not a guarantee."
                        : "Banner removed. This block can be dug again.",
                flags[i] ? GOLD : MINT, i);
        sound("block.note_block.hat", 0.45f, flags[i] ? 1.5f : 0.8f);
    }

    private void dig(int i, boolean chordAllowed) {
        if (i < 0 || i >= COUNT || (mode != Mode.READY && mode != Mode.PLAY)) return;
        if (flags[i]) {
            if (chordAllowed) {
                notify("Right-click to remove this banner before digging.", GOLD, i);
                sound("block.note_block.bass", 0.25f, 0.8f);
            }
            return;
        }
        if (!generated) generate(i);
        if (open[i]) {
            if (chordAllowed && age[i] >= 0) {
                if (adjacent[i] > 0) chord(i);
                else notify("An empty patch: all touching blocks are safe.", MINT, i);
            }
            return;
        }

        markAction(i);
        if (mines[i]) {
            detonated = i;
            open[i] = true;
            age[i] = 0;
            dirty[i] = true;
            mode = Mode.FUSE;
            modeTime = 0;
            feedbackTime = 0;
            sound("entity.creeper.primed", 0.7f, 1.05f);
            return;
        }

        int before = dug, head = 0, tail = 0;
        open[i] = true;
        queue[tail++] = i;
        while (head < tail) {
            int at = queue[head++];
            dug++;
            int distance = Math.abs(at % COLS - i % COLS)
                    + Math.abs(at / COLS - i / COLS);
            age[at] = -Math.min(0.65, distance * 0.045);
            dirty[at] = true;
            if (adjacent[at] != 0) continue;

            int c = at % COLS, r = at / COLS;
            for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
                for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                    int near = y * COLS + x;
                    if (!open[near] && !flags[near] && !mines[near]) {
                        open[near] = true;
                        queue[tail++] = near;
                    }
                }
            }
        }
        burst(cx(i), cy(i), Math.min(22, 5 + dug - before), MINT, false);
        if (soundWait <= 0) {
            sound("block.gravel.break", 0.4f, 1.05f + effects.nextFloat() * 0.2f);
            soundWait = 0.09;
        }

        int cleared = dug - before;
        if (cleared >= 5) {
            notify("CLEAR PATCH  +" + cleared + "  /  Follow the numbered edges.", MINT, -1);
        } else if (chordAllowed) {
            notify(adjacent[i] + " creepers touch this block. Diagonals count too.", MINT, i);
        }
        if (dug == SAFE) win();
    }

    private int nearbyFlags(int i) {
        int result = 0, c = i % COLS, r = i / COLS;
        for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
            for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                int j = y * COLS + x;
                if (j != i && flags[j]) result++;
            }
        }
        return result;
    }

    private void chord(int i) {
        int c = i % COLS, r = i / COLS;
        int placed = nearbyFlags(i), hidden = 0;
        for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
            for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                int j = y * COLS + x;
                if (!open[j] && !flags[j]) hidden++;
            }
        }
        if (hidden == 0) {
            notify("This numbered patch is already swept.", MINT, i);
            return;
        }
        if (placed != adjacent[i]) {
            notify("Sweep needs " + adjacent[i] + " touching banners; "
                    + placed + " placed.", GOLD, i);
            sound("block.note_block.bass", 0.3f, 0.65f);
            return;
        }

        int before = dug;
        for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
            for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                int j = y * COLS + x;
                if (!open[j] && !flags[j]) dig(j, false);
                if (mode != Mode.PLAY) return;
            }
        }
        markAction(i);
        notify("NEIGHBOR SWEEP  +" + (dug - before), MINT, i);
        sound("block.note_block.chime", 0.35f, 1.35f);
    }

    private void win() {
        mode = Mode.WON;
        modeTime = 0;
        feedbackTime = 0;
        for (int i = 0; i < COUNT; i++) {
            if (mines[i]) {
                flags[i] = true;
                flagAge[i] = 0;
            }
        }
        flagCount = MINES;
        finalScore = 10000 + (int) Math.max(0, 90000 - elapsed * 100);
        submit();
        sound("ui.toast.challenge_complete", 0.65f, 1.1f);
        burst(BX + 170, BY + 240, 50, GOLD, true);
        burst(BX + BW - 170, BY + 240, 50, MINT, true);
    }

    private void explode() {
        mode = Mode.LOST;
        modeTime = 0;
        shake = 11;
        flash = 0.7;
        for (int i = 0; i < COUNT; i++) {
            if (mines[i]) {
                open[i] = true;
                age[i] = 0;
                burst(cx(i), cy(i), i == detonated ? 40 : 4,
                        i == detonated ? GOLD : MINT, false);
            } else if (open[i]) {
                // Do not leave a previously queued flood visually hidden.
                age[i] = Math.max(0, age[i]);
            }
            dirty[i] = true;
        }
        finalScore = dug * 10;
        submit();
        sound("entity.generic.explode", 0.7f, 0.85f);
    }

    private void submit() {
        if (submitted) return;
        submitted = true;
        if (player != null && host != null) {
            rank = host.score(player, finalScore);
            refreshScores();
        }
    }

    private void notify(String text, Color color, int cell) {
        feedback = text;
        feedbackColor = color;
        feedbackCell = cell;
        feedbackTime = 3;
    }

    private void markAction(int i) {
        actionX = cx(i);
        actionY = cy(i);
        actionAge = 0;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        modeTime += dt;
        if (mode == Mode.PLAY) elapsed += dt;
        shake = Math.max(0, shake - dt * 15);
        flash = Math.max(0, flash - dt * 2.4);
        feedbackTime = Math.max(0, feedbackTime - dt);
        demoTap = Math.max(0, demoTap - dt);
        actionAge = Math.min(10, actionAge + dt);
        soundWait -= dt;

        int revealSparks = 0;
        for (int i = 0; i < COUNT; i++) {
            double previous = age[i];
            age[i] = Math.min(10, age[i] + dt);
            flagAge[i] = Math.min(10, flagAge[i] + dt);
            if (previous < 0 && age[i] >= 0) {
                dirty[i] = true;
                if (revealSparks++ < 4) {
                    burst(cx(i), cy(i), 2, new Color(0xC3D887), false);
                }
            }
        }

        double drag = Math.exp(-dt * 1.5);
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vx *= drag;
                p.vy += (p.confetti ? 140 : 440) * dt;
            }
        }

        if (mode == Mode.FUSE && modeTime >= 0.9) explode();
        if (mode == Mode.WON && modeTime < 3.5) {
            confettiWait -= dt;
            if (confettiWait <= 0) {
                confettiWait = 0.25;
                burst(BX + 60 + effects.nextInt(BW - 120), BY + 80,
                        10, effects.nextBoolean() ? GOLD : MINT, true);
            }
        }

        if (player == null) {
            demo(dt);
        } else if (keyboardSelection && !finished() && mode != Mode.FUSE) {
            keyWait -= dt;
            if (keyWait <= 0) {
                for (Key key : held) {
                    if (direction(key)) {
                        moveSelection(key);
                        keyWait = 0.13;
                        break;
                    }
                }
            }
        }

        scoreWait -= dt;
        if (scoreWait <= 0) {
            refreshScores();
            scoreWait = 5;
        }
    }

    private void demo(double dt) {
        if (finished()) {
            if (modeTime > 4.5) reset();
            return;
        }
        if (mode == Mode.FUSE) return;
        if (demoTarget < 0) {
            demoWait -= dt;
            if (demoWait > 0) return;
            demoTarget = chooseDemoCell();
            if (demoTarget < 0) {
                reset();
                return;
            }
            demoWait = 0.64;
        }
        demoX += (cx(demoTarget) - demoX) * Math.min(1, dt * 10);
        demoY += (cy(demoTarget) - demoY) * Math.min(1, dt * 10);
        hover = demoTarget;
        demoWait -= dt;
        if (demoWait <= 0) {
            int target = demoTarget;
            demoTarget = -1;
            demoTap = 0.35;
            if (generated && mines[target]) toggleFlag(target);
            else dig(target, true);
            demoWait = 0.28;
        }
    }

    private int chooseDemoCell() {
        if (!generated) return 4 * COLS + 7;

        // Prefer moves deducible by a single numbered tile.
        for (int i = 0; i < COUNT; i++) {
            if (!open[i] || adjacent[i] == 0) continue;
            int c = i % COLS, r = i / COLS;
            int hidden = 0, candidate = -1;
            for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
                for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                    int j = y * COLS + x;
                    if (!open[j] && !flags[j]) {
                        hidden++;
                        candidate = j;
                    }
                }
            }
            if (hidden == 0) continue;
            int placed = nearbyFlags(i);
            if (placed == adjacent[i]) return i; // Demonstrate a chord.
            if (placed + hidden == adjacent[i]) return candidate;
        }

        // An omniscient fallback keeps the demonstration moving through guesses.
        // Player controls never consult this method.
        int best = -1, priority = Integer.MIN_VALUE;
        for (int i = 0; i < COUNT; i++) {
            if (open[i] || flags[i] || mines[i]) continue;
            int seen = 0, c = i % COLS, r = i / COLS;
            for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
                for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                    if (open[y * COLS + x]) seen++;
                }
            }
            int p = seen * 8 + demoRandom.nextInt(8);
            if (p > priority) {
                priority = p;
                best = i;
            }
        }
        return best;
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && player != null) {
            host.sound(id, volume, Math.max(0.5f, Math.min(2f, pitch)));
        }
    }

    private void refreshScores() {
        if (host != null) leaders = new ArrayList<>(host.scores());
    }

    private static double cx(int i) { return BX + (i % COLS + 0.5) * CELL; }
    private static double cy(int i) { return BY + (i / COLS + 0.5) * CELL; }

    private void burst(double x, double y, int count, Color color, boolean confetti) {
        for (int i = 0; i < count && particles.size() < 260; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double a = effects.nextDouble() * Math.PI * 2;
            double speed = 35 + effects.nextDouble() * 180;
            p.vx = Math.cos(a) * speed;
            p.vy = Math.sin(a) * speed - (confetti ? 145 : 60);
            p.life = 0.4 + effects.nextDouble() * (confetti ? 1.9 : 0.65);
            p.size = 3 + effects.nextInt(5);
            p.color = color;
            p.confetti = confetti;
            particles.add(p);
        }
    }

    private void makeArt() {
        background = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        boardImage = new BufferedImage(BW, BH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x254D41), 0, H, INK));
        g.fillRect(0, 0, W, H);
        Random art = new Random(37);
        for (int i = 0; i < 130; i++) {
            g.setColor(i % 3 == 0 ? new Color(0x365F45) : new Color(0x294F3D));
            g.fillRect(art.nextInt(W), art.nextInt(H), 3 + art.nextInt(5), 3);
        }
        g.setColor(DARK);
        g.fillRect(0, 646, W, 54);
        for (int x = 0; x < W; x += 25) {
            int y = 640 - art.nextInt(9);
            g.setColor(new Color(0x416B48));
            g.fillRect(x, y, 3, 646 - y);
            g.fillRect(x - 3, y + 3, 3, 3);
        }
        panel(g, BX - 9, BY - 9, BW + 18, BH + 22);
        g.setColor(new Color(0xBDA46B));
        g.drawRect(BX - 3, BY - 3, BW + 5, BH + 5);
        panel(g, SX, BY - 9, SW, BH + 22);
        panel(g, SX, 22, 153, 98);
        panel(g, SX + 168, 22, 168, 98);
        creeper(g, 34, 29, 52, false);
        g.dispose();

        for (int i = 0; i < COUNT; i++) variant[i] = art.nextInt(grass.length);
        for (int v = 0; v < grass.length; v++) {
            grass[v] = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB);
            g = grass[v].createGraphics();
            int light = v % 3;
            g.setColor(new Color(0x355B30));
            g.fillRect(0, 0, CELL, CELL);
            g.setColor(new Color(119 + light * 4, 163 + light * 4, 72 + light * 3));
            g.fillRect(2, 2, 44, 40);
            g.setColor(new Color(154 + light * 3, 194 + light * 3, 101));
            g.fillRect(2, 2, 44, 4);
            g.setColor(new Color(0x548039));
            g.fillRect(3, 40, 42, 4);
            g.fillRect(43, 7, 3, 34);

            Random texture = new Random(100 + v);
            for (int n = 0; n < 4; n++) {
                int x = 7 + texture.nextInt(29), y = 10 + texture.nextInt(25);
                g.setColor(n % 2 == 0 ? new Color(0x689741) : new Color(0x98BB67));
                g.fillRect(x, y, 3 + texture.nextInt(4), 2);
            }
            int tx = 9 + v * 7 % 26, ty = 18 + v * 5 % 15;
            g.setColor(new Color(0x618F3C));
            g.fillRect(tx, ty, 9, 2);
            g.fillRect(tx + 4, ty - 4, 2, 5);
            if (v == 6) {
                g.setColor(new Color(0xB9CF8A));
                g.fillRect(33, 14, 2, 2);
            }
            g.dispose();
        }

        for (int parity = 0; parity < 2; parity++) {
            for (int n = 0; n <= 8; n++) {
                dirt[parity][n] = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB);
                g = dirt[parity][n].createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(0x9D8762));
                g.fillRect(0, 0, CELL, CELL);
                g.setColor(new Color(parity == 0 ? 0xE4D1AC : 0xDEC9A1));
                g.fillRect(1, 1, 46, 46);
                g.setColor(new Color(0xC5AE88));
                g.fillRect(7, 37, 4, 2);
                g.fillRect(38, 8, 2, 3);
                if (n > 0) center(g, Integer.toString(n), NUMBER, 24, 36, NUM[n]);
                g.dispose();
            }
        }
    }

    private void updateBoardCache() {
        Graphics2D g = boardImage.createGraphics();
        for (int i = 0; i < COUNT; i++) {
            if (!dirty[i]) continue;
            dirty[i] = false;
            int x = i % COLS * CELL, y = i / COLS * CELL;
            int parity = (i % COLS + i / COLS) & 1;
            boolean visible = open[i] && age[i] >= 0;
            g.drawImage(visible ? dirt[parity][mines[i] ? 0 : adjacent[i]]
                    : grass[variant[i]], x, y, null);
            if (visible && mines[i]) {
                g.setColor(i == detonated ? new Color(0xBD6A4F) : new Color(0xA2AC76));
                g.fillRect(x + 2, y + 2, 44, 44);
                creeper(g, x + 8, y + 7, 32, false);
                if (flags[i]) {
                    g.setColor(GOLD);
                    g.fillRect(x + 35, y + 3, 8, 8);
                }
            }
        }
        g.dispose();
    }

    @Override
    public void draw(Graphics2D output) {
        if (host == null || background == null) {
            output.setColor(INK);
            output.fillRect(0, 0, cols() * 128, rows() * 128);
            return;
        }
        updateBoardCache();

        Graphics2D g = (Graphics2D) output.create();
        g.scale(Math.max(1, host.width()) / (double) W,
                Math.max(1, host.height()) / (double) H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(background, 0, 0, null);

        int dx = (int) (Math.sin(clock * 83) * shake);
        int dy = (int) (Math.cos(clock * 67) * shake * 0.65);
        g.drawImage(boardImage, BX + dx, BY + dy, null);
        drawBoardEffects(g, dx, dy);
        drawHeader(g);
        drawSidebar(g);

        Graphics2D sparks = (Graphics2D) g.create();
        sparks.clipRect(BX - 8, BY - 8, BW + 16, BH + 24);
        for (Particle p : particles) {
            sparks.setColor(alpha(p.color, (int) (255 * Math.min(1, p.life / 0.3))));
            sparks.fillRect((int) p.x, (int) p.y, p.size, p.confetti ? 3 : p.size);
        }
        sparks.dispose();

        if (player == null && !finished()) drawDemoTool(g);
        if (player == null) {
            center(g, "LIVE DEMO  /  TAKE A SEAT TO SWEEP", HEADING, W / 2, 682,
                    Math.sin(clock * 2.2) > -0.75 ? GOLD : WHITE);
        } else {
            center(g, "LEFT CLICK  Dig / sweep     RIGHT CLICK  Banner"
                    + "     WASD  Select     SPACE  Dig     F  Banner",
                    SMALL, W / 2, 680, WHITE);
        }
        g.dispose();
    }

    private void drawHeader(Graphics2D g) {
        text(g, "CREEPER SWEEPER", TITLE, 102, 70, WHITE);
        text(g, player == null ? "A quiet meadow. A very explosive secret."
                : "SWEEPER / " + clipped(player.name(), 28), BODY, 104, 105, MINT);

        text(g, "CREEPERS", LABEL, SX + 14, 48, MINT);
        text(g, Integer.toString(MINES - flagCount), DIGITS, SX + 14, 94, GOLD);
        banner(g, SX + 111, 61, 0);
        text(g, "TIME", LABEL, SX + 182, 48, MINT);
        text(g, time(elapsed), DIGITS, SX + 182, 94, WHITE);

        String status;
        Color color = MINT;
        if (mode == Mode.FUSE) {
            status = "SSSSSSSS...  TOO CLOSE!";
            color = RED;
        } else if (mode == Mode.LOST) {
            status = "BOOM!  ALL 28 CREEPERS REVEALED";
            color = RED;
        } else if (mode == Mode.WON) {
            status = "MEADOW SECURED  /  EVERY SAFE BLOCK CLEARED";
            color = GOLD;
        } else if (feedbackTime > 0) {
            status = feedback;
            color = feedbackColor;
        } else if (mode == Mode.READY) {
            status = "FIRST DIG IS SAFE  /  CHOOSE ANY GRASS BLOCK";
        } else {
            status = "CLEAR THE GRASS. TRUST THE NUMBERS.";
        }
        fitCentered(g, status, SMALL, BX + BW / 2, 134, BW - 10, color);
    }

    private void drawSidebar(Graphics2D g) {
        if (finished()) {
            drawResult(g);
            return;
        }

        int x = SX + 20;
        text(g, player == null ? "WATCH THE SWEEPER" : "SECURE THE MEADOW",
                BODY, x, 180, GOLD);
        text(g, dug + " / " + SAFE, LARGE, x, 227, WHITE);
        text(g, "safe blocks cleared", BODY, x, 258, MINT);
        g.setColor(DARK);
        g.fillRect(x, 273, SW - 40, 10);
        g.setColor(MINT);
        g.fillRect(x, 273, (int) ((SW - 40.0) * dug / SAFE), 10);

        if (player == null && clock % 16 >= 10 && !leaders.isEmpty()) {
            text(g, "BEST SWEEPERS", HEADING, x, 329, GOLD);
            for (int i = 0; i < Math.min(3, leaders.size()); i++) {
                Host.Score s = leaders.get(i);
                int y = 372 + i * 66;
                text(g, (i + 1) + ". " + clipped(s.name(), 18), BODY, x, y, WHITE);
                text(g, Integer.toString(s.score()), BODY, x + 29, y + 28, MINT);
            }
            text(g, "Win fast. Score higher.", BODY, x, 592, GOLD);
            return;
        }

        boolean number = hover >= 0 && open[hover] && !mines[hover]
                && age[hover] >= 0 && adjacent[hover] > 0;
        int focus = number ? hover : -1;
        text(g, number ? "THIS NUMBER" : "READ THE DIRT", HEADING, x, 329, GOLD);
        drawNeighborhood(g, x, 346, focus);

        int value = number ? adjacent[hover] : 2;
        text(g, Integer.toString(value), LARGE, x + 141, 380, WHITE);
        text(g, "touching", BODY, x + 141, 410, WHITE);
        text(g, value == 1 ? "creeper" : "creepers", BODY, x + 141, 439, WHITE);
        text(g, "Diagonals count.", SMALL, x, 476, MINT);

        if (number) {
            int placed = nearbyFlags(hover);
            text(g, placed + " / " + value + " banners nearby", BODY, x, 516, GOLD);
            if (placed == value) {
                text(g, "Click number to sweep.", BODY, x, 550, WHITE);
                text(g, "Wrong banners can explode.", SMALL, x, 580, RED);
            } else {
                text(g, "Mark suspects, then click", BODY, x, 550, WHITE);
                text(g, "the number to sweep.", BODY, x, 580, WHITE);
            }
        } else {
            text(g, "LEFT CLICK   Dig a block", BODY, x, 516, WHITE);
            text(g, "RIGHT CLICK  Mark suspect", BODY, x, 551, WHITE);
            text(g, "Numbers can sweep neighbors.", SMALL, x, 583, GOLD);
        }
        text(g, "Counter = 28 minus your banners", LABEL, x, 615, MUTED);
    }

    private void drawNeighborhood(Graphics2D g, int x, int y, int focus) {
        final int s = 35;
        for (int r = -1; r <= 1; r++) {
            for (int c = -1; c <= 1; c++) {
                int px = x + (c + 1) * (s + 4), py = y + (r + 1) * (s + 4);
                int i = -1;
                if (focus >= 0) {
                    int col = focus % COLS + c, row = focus / COLS + r;
                    if (col < 0 || col >= COLS || row < 0 || row >= ROWS) continue;
                    i = row * COLS + col;
                }
                boolean center = c == 0 && r == 0;
                boolean visible = i >= 0 && open[i] && age[i] >= 0;
                g.setColor(center || visible ? new Color(0xDEC9A1) : new Color(0x6F984B));
                g.fillRect(px, py, s, s);
                g.setColor(center ? GOLD : EDGE);
                g.setStroke(new BasicStroke(center ? 2 : 1));
                g.drawRect(px, py, s, s);
                int n = center ? (focus >= 0 ? adjacent[focus] : 2)
                        : visible ? adjacent[i] : 0;
                if (n > 0) center(g, Integer.toString(n), HEADING, px + s / 2, py + 27, NUM[n]);

                boolean marked = i >= 0 ? flags[i] : (r == -1 && c == -1) || (r == 1 && c == 1);
                if (marked && !center) {
                    g.setColor(WHITE);
                    g.fillRect(px + 10, py + 6, 3, 25);
                    g.setColor(RED);
                    g.fillRect(px + 13, py + 6, 16, 13);
                }
            }
        }
    }

    private void drawResult(Graphics2D g) {
        boolean won = mode == Mode.WON;
        int x = SX + 20;
        center(g, won ? "MEADOW" : "ONE HISS", LARGE, SX + SW / 2, 197, won ? GOLD : RED);
        center(g, won ? "SECURED!" : "TOO MANY!", LARGE, SX + SW / 2, 241, won ? GOLD : RED);

        text(g, won ? "CLEAR TIME" : "SURVIVED", SMALL, x, 290, MINT);
        text(g, time(elapsed), DIGITS, x, 331, WHITE);
        text(g, won ? "SCORE + SPEED BONUS" : "EXPLORATION SCORE", LABEL, x, 373, MINT);
        text(g, Integer.toString(finalScore), LARGE, x, 416, GOLD);
        text(g, won ? "Every safe block cleared." : dug + " / " + SAFE + " safe blocks dug.",
                BODY, x, 455, WHITE);

        String detail = rank > 0 ? "HIGH SCORE TABLE  #" + rank
                : won ? "A beautifully quiet meadow." : "Read the edges. Try again.";
        fitCentered(g, detail, SMALL, SX + SW / 2, 490, SW - 40, MINT);

        if (player != null) {
            g.setColor(modeTime > 1 ? MINT : EDGE);
            g.fillRoundRect(x, 548, SW - 40, 59, 10, 10);
            center(g, "PLAY AGAIN / SPACE", BODY, SX + SW / 2, 585, INK);
        } else {
            center(g, "NEXT MEADOW SOON", BODY, SX + SW / 2, 579, GOLD);
        }
    }

    private void drawBoardEffects(Graphics2D g, int dx, int dy) {
        Graphics2D b = (Graphics2D) g.create();
        b.clipRect(BX - 8, BY - 8, BW + 16, BH + 20);
        b.translate(dx, dy);

        for (int i = 0; i < COUNT; i++) {
            int x = BX + i % COLS * CELL, y = BY + i / COLS * CELL;
            if (flags[i] && !(open[i] && mines[i] && age[i] >= 0)) {
                double t = Math.min(1, flagAge[i] / 0.28);
                double scale = t < 1 ? Math.max(0.1, 1 + Math.sin(t * Math.PI) * 0.18) : 1;
                Graphics2D flag = (Graphics2D) b.create();
                flag.translate(x + 24, y + 41);
                flag.scale(scale, scale);
                banner(flag, -13, -33, Math.sin(clock * 3 + i * 0.7) > 0 ? 1 : 0);
                flag.dispose();

                if (mode == Mode.LOST && !mines[i]) {
                    b.setColor(new Color(0xAE2330));
                    b.setStroke(new BasicStroke(4));
                    b.drawLine(x + 7, y + 8, x + 40, y + 40);
                    b.drawLine(x + 40, y + 8, x + 7, y + 40);
                }
            }
            if (open[i] && !mines[i] && age[i] >= 0 && age[i] < 0.4) {
                double f = 1 - age[i] / 0.4;
                b.setColor(alpha(WHITE, (int) (125 * f)));
                b.fillRect(x + 2, y + 2, 44, 44);
                b.setColor(alpha(GOLD, (int) (180 * f)));
                b.drawRect(x + 2, y + 2, 43, 43);
            }
        }

        if (hover >= 0 && !finished() && mode != Mode.FUSE) {
            if (open[hover] && age[hover] >= 0 && adjacent[hover] > 0) {
                int c = hover % COLS, r = hover / COLS;
                b.setColor(alpha(GOLD, 175));
                b.setStroke(new BasicStroke(2));
                for (int y = Math.max(0, r - 1); y <= Math.min(ROWS - 1, r + 1); y++) {
                    for (int x = Math.max(0, c - 1); x <= Math.min(COLS - 1, c + 1); x++) {
                        if (!open[y * COLS + x]) {
                            b.drawRect(BX + x * CELL + 4, BY + y * CELL + 4, 39, 39);
                        }
                    }
                }
            }
            int x = BX + hover % COLS * CELL, y = BY + hover / COLS * CELL;
            b.setColor(alpha(GOLD, 25));
            b.fillRect(x + 2, y + 2, 44, 44);
            b.setColor(GOLD);
            b.setStroke(new BasicStroke(3));
            b.drawRect(x + 2, y + 2, 43, 43);
            if (keyboardSelection) {
                b.setColor(WHITE);
                b.fillRect(x + 19, y - 4, 10, 5);
            }
        }

        if (feedbackTime > 0 && feedbackCell >= 0 && !finished() && mode != Mode.FUSE) {
            int i = feedbackCell;
            b.setColor(alpha(feedbackColor,
                    (int) (65 + 105 * Math.abs(Math.sin(clock * 6)))));
            b.setStroke(new BasicStroke(2));
            b.drawRect(BX + i % COLS * CELL + 5, BY + i / COLS * CELL + 5, 37, 37);
        }
        if (actionAge < 0.65 && mode != Mode.FUSE && !finished()) {
            int radius = 10 + (int) (actionAge * 50);
            b.setColor(alpha(WHITE, (int) (160 * (1 - actionAge / 0.65))));
            b.setStroke(new BasicStroke(2));
            b.drawOval((int) actionX - radius, (int) actionY - radius, radius * 2, radius * 2);
        }

        if (mode == Mode.FUSE && detonated >= 0) {
            int x = BX + detonated % COLS * CELL, y = BY + detonated / COLS * CELL;
            boolean white = ((int) (modeTime * (7 + modeTime * 18)) & 1) == 0;
            b.setColor(white ? WHITE : RED);
            b.fillRect(x + 2, y + 2, 44, 44);
            creeper(b, x + 5, y + 4, 38, white);
            int radius = 28 + (int) (8 * Math.sin(modeTime * 20));
            b.setStroke(new BasicStroke(3));
            b.setColor(RED);
            b.drawOval(x + 24 - radius, y + 24 - radius, radius * 2, radius * 2);
        }

        if (mode == Mode.LOST && modeTime < 0.9 && detonated >= 0) {
            int radius = (int) (modeTime * 750);
            b.setColor(alpha(GOLD, (int) (220 * (1 - modeTime / 0.9))));
            b.setStroke(new BasicStroke(6));
            b.drawOval((int) cx(detonated) - radius, (int) cy(detonated) - radius,
                    radius * 2, radius * 2);
        }
        if (flash > 0) {
            b.setColor(alpha(WHITE, (int) (flash * 210)));
            b.fillRect(BX, BY, BW, BH);
        }
        b.dispose();
    }

    private void drawDemoTool(Graphics2D g) {
        Graphics2D tool = (Graphics2D) g.create();
        tool.translate(demoX + 12, demoY + 6);
        tool.rotate(-0.45 + (demoTap > 0
                ? Math.sin(demoTap / 0.35 * Math.PI) * 0.8 : 0));
        tool.setColor(new Color(0, 0, 0, 65));
        tool.fillRect(-5, -8, 15, 40);
        tool.setColor(new Color(0x73512E));
        tool.fillRect(-3, -12, 7, 39);
        tool.setColor(new Color(0xD6B078));
        tool.fillRect(-3, -12, 3, 37);
        tool.setColor(new Color(0x305D59));
        tool.fillRect(-20, -17, 39, 12);
        tool.setColor(new Color(0x80E2D8));
        tool.fillRect(-20, -19, 39, 8);
        tool.fillRect(12, -16, 7, 18);
        tool.setColor(WHITE);
        tool.fillRect(-18, -19, 34, 3);
        tool.dispose();
    }

    private static void creeper(Graphics2D g, int x, int y, int size, boolean white) {
        g.setColor(new Color(0x294C27));
        g.fillRect(x, y + 3, size, size);
        g.setColor(white ? WHITE : new Color(0x7ACA54));
        g.fillRect(x, y, size, size - 3);
        g.setColor(white ? Color.WHITE : new Color(0xB3E679));
        g.fillRect(x + 2, y + 2, size - 4, Math.max(2, size / 8));
        int u = Math.max(2, size / 8);
        int ox = x + (size - u * 8) / 2, oy = y + size / 5;
        g.setColor(white ? new Color(0x677565) : new Color(0x183829));
        g.fillRect(ox + u, oy + u, 2 * u, 2 * u);
        g.fillRect(ox + 5 * u, oy + u, 2 * u, 2 * u);
        g.fillRect(ox + 3 * u, oy + 3 * u, 2 * u, u);
        g.fillRect(ox + 2 * u, oy + 4 * u, 4 * u, 2 * u);
        g.setColor(white ? WHITE : new Color(0x7ACA54));
        g.fillRect(ox + 3 * u, oy + 5 * u, 2 * u, u);
    }

    private static void banner(Graphics2D g, int x, int y, int wave) {
        g.setColor(new Color(0x35512B));
        g.fillOval(x - 3, y + 27, 31, 8);
        g.setColor(new Color(0x75502B));
        g.fillRect(x + 2, y, 5, 33);
        g.setColor(new Color(0xECD096));
        g.fillRect(x + 2, y, 2, 31);
        g.setColor(new Color(0x982D35));
        g.fillRect(x + 7, y + 2, 23, 23);
        g.setColor(new Color(0xF45459));
        g.fillRect(x + 7, y + 2, 11, 19);
        g.fillRect(x + 18, y + 2 + wave, 10, 19);
        g.fillRect(x + 7, y + 20, 6, 5);
        g.fillRect(x + 22, y + 20 + wave, 6, 4);
        g.setColor(new Color(0xFFABA0));
        g.fillRect(x + 8, y + 3, 3, 17);
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(DARK);
        g.fillRoundRect(x, y + 4, w, h, 12, 12);
        g.setColor(new Color(0x102F29));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, w, h, 12, 12);
    }

    private static Font font(int size) {
        return new Font(Font.SANS_SERIF, Font.BOLD, size);
    }

    private static void text(Graphics2D g, String s, Font font, int x, int y, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(s, x, y);
    }

    private static void center(Graphics2D g, String s, Font font, int x, int y, Color color) {
        g.setFont(font);
        text(g, s, font, x - g.getFontMetrics().stringWidth(s) / 2, y, color);
    }

    private static void fitCentered(Graphics2D g, String s, Font font,
                                    int x, int y, int width, Color color) {
        g.setFont(font);
        int measured = g.getFontMetrics().stringWidth(s);
        if (measured > width) {
            font = font.deriveFont(Math.max(14f, font.getSize2D() * width / measured));
        }
        center(g, s, font, x, y, color);
    }

    private static String clipped(String s, int length) {
        if (s == null) return "Player";
        return s.length() <= length ? s : s.substring(0, length - 1) + "…";
    }

    private static String time(double seconds) {
        int s = (int) Math.max(0, Math.min(5999, seconds));
        return twoDigits(s / 60) + ":" + twoDigits(s % 60);
    }

    private static String twoDigits(int n) {
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, a)));
    }

    private static final class Particle {
        double x, y, vx, vy, life;
        int size;
        Color color;
        boolean confetti;
    }
}

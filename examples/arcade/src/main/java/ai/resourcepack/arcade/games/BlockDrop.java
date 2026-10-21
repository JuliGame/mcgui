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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Block Drop: seven-bag falling blocks, clockwise SRS, hold, ghost,
 * bounded lock delay, buffered entry actions, and a live placement AI.
 *
 * All coordinates below are in a 960 x 540 logical canvas.
 */
public final class BlockDrop implements Scene {
    private static final int W = 960, H = 540;
    private static final int COLS = 10, ROWS = 20, CELL = 24;
    private static final int BX = 360, BY = 40;
    private static final double CLEAR_DURATION = 0.42;
    private static final double LOCK_DELAY = 0.48;

    private static final Color INK = new Color(0x09131C);
    private static final Color WHITE = new Color(0xF4FAEF);
    private static final Color MUTED = new Color(0xA6BDC6);
    private static final Color CYAN = new Color(0x77F3ED);
    private static final Color GOLD = new Color(0xFFDA79);
    private static final Color RED = new Color(0xFF7971);

    // I, J, L, O, S, T, Z.
    private static final Color[] COLORS = {
            new Color(0x92E9FF), new Color(0xBECADC),
            new Color(0xEABD7A), new Color(0xFFD45E),
            new Color(0x47EBC6), new Color(0xC58C65),
            new Color(0xFF7066)
    };
    private static final String[] NAMES = {
            "GLASS", "STONE", "OAK PLANKS", "GOLD ORE",
            "DIAMOND ORE", "DIRT", "TNT"
    };
    private static final int[][][][] SHAPES = makeShapes();
    private static final int[][][] KICKS = {
            {{0, 0}, {-1, 0}, {-1, -1}, {0, 2}, {-1, 2}},
            {{0, 0}, {1, 0}, {1, 1}, {0, -2}, {1, -2}},
            {{0, 0}, {1, 0}, {1, -1}, {0, 2}, {1, 2}},
            {{0, 0}, {-1, 0}, {-1, 1}, {0, -2}, {-1, -2}}
    };
    private static final int[][][] I_KICKS = {
            {{0, 0}, {-2, 0}, {1, 0}, {-2, 1}, {1, -2}},
            {{0, 0}, {-1, 0}, {2, 0}, {-1, -2}, {2, 1}},
            {{0, 0}, {2, 0}, {-1, 0}, {2, -1}, {-1, 2}},
            {{0, 0}, {1, 0}, {-2, 0}, {1, 2}, {-2, -1}}
    };
    private static final Font[] FONTS = new Font[73];
    private static final Font[] MONO = new Font[73];

    static {
        for (int i = 1; i < FONTS.length; i++) {
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
            MONO[i] = new Font(Font.MONOSPACED, Font.BOLD, i);
        }
    }

    private Host host;
    private Seat player;
    private final Random bags = new Random(0xB10CD09L);
    private final Random effects = new Random(431);
    private final int[][] board = new int[ROWS][COLS];
    private final boolean[] clearing = new boolean[ROWS];
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final EnumSet<Key> keys = EnumSet.noneOf(Key.class);
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final BufferedImage[] tiles = new BufferedImage[7];
    private BufferedImage background, wellImage;
    private boolean wellDirty = true;
    private List<Host.Score> highScores = List.of();

    private int type, rotation, px, py, hold = -1;
    private int score, lines, level = 1, rank, combo = -1, pending;
    private int resets, repeatDirection, bufferedRotations;
    private int aiX, aiRotation;
    private int dropX, dropTop, dropBottom, dropType, dropRotation;
    private int lockedType, lockedRotation, lockedX, lockedY;
    private int lastReward, piecesPlaced;
    private boolean active, over, holdUsed, backToBack, touchedGround;
    private boolean bufferedHold;

    private double clock, roundTime, overTime, gravityTime, softTime;
    private double lockTime, repeatTime, clearTime, shake, impact;
    private double noticeTime, aiTime, aiStep, aiFall, dropTime;
    private double pollTime, scorePulse, rotatePulse, lockFlash;
    private String notice = "", detail = "";

    public BlockDrop() {}

    @Override
    public boolean showPointer() {
        return false;
    }

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        makeArt();
        List<Seat> seats = host.seats();
        player = seats == null || seats.isEmpty() ? null : seats.get(0);
        refreshScores();
        reset();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || player != null) return;
        player = seat;
        reset();
        sound("block.note_block.chime", 1.2f);
    }

    @Override
    public void left(Seat seat) {
        if (!owns(seat)) return;
        player = null;
        reset();
    }

    private boolean owns(Seat seat) {
        return host != null && player != null && seat != null
                && player.id().equals(seat.id());
    }

    private void refreshScores() {
        if (host == null) return;
        List<Host.Score> result = host.scores();
        highScores = result == null ? List.of() : List.copyOf(result);
    }

    private void reset() {
        for (int[] row : board) Arrays.fill(row, 0);
        Arrays.fill(clearing, false);
        queue.clear();
        keys.clear();
        particles.clear();

        // Cabinet idle time must not change the player's opening bag.
        bags.setSeed(player == null ? 0xDE10B10CL : 0xB10CD09L);
        hold = -1;
        score = lines = rank = pending = piecesPlaced = 0;
        level = 1;
        combo = -1;
        active = over = holdUsed = backToBack = false;
        bufferedHold = false;
        bufferedRotations = repeatDirection = 0;
        roundTime = overTime = clearTime = shake = impact = 0;
        dropTime = scorePulse = rotatePulse = lockFlash = 0;
        repeatTime = gravityTime = softTime = lockTime = 0;
        notice = player == null ? "LIVE AUTOPILOT" : "LET'S BUILD!";
        detail = player == null ? "Real moves. Real line clears." : "Follow the landing outline.";
        noticeTime = player == null ? 1.2 : 2.4;
        lastReward = 0;
        wellDirty = true;

        if (player == null) {
            // A demonstration mine, never used in scored play. The opening I
            // clears four rows; two uneven rows remain to show ongoing play.
            for (int y = 16; y < ROWS; y++) {
                for (int x = 0; x < 9; x++) {
                    board[y][x] = 1 + Math.floorMod(x / 2 + y, 7);
                }
            }
            for (int x = 0; x < 5; x++) board[15][x] = 3;
            for (int x = 0; x < 3; x++) board[14][x] = 6;
            fillQueue();
            spawn(0);
        } else {
            nextPiece();
        }
    }

    private void fillQueue() {
        if (queue.size() >= 7) return;
        int[] bag = {0, 1, 2, 3, 4, 5, 6};
        for (int i = bag.length - 1; i > 0; i--) {
            int j = bags.nextInt(i + 1);
            int v = bag[i];
            bag[i] = bag[j];
            bag[j] = v;
        }
        for (int t : bag) queue.addLast(t);
    }

    private void nextPiece() {
        fillQueue();
        int next = queue.removeFirst();
        fillQueue();
        holdUsed = false;
        spawn(next);

        boolean held = bufferedHold;
        int rotations = bufferedRotations;
        bufferedHold = false;
        bufferedRotations = 0;
        if (active && held) useHold();
        for (int i = 0; i < rotations && active; i++) rotate();

        if (player != null && active) {
            int direction = heldDirection();
            if (direction != 0) move(direction, false);
            repeatDirection = direction;
            repeatTime = 0.15;
        }
    }

    private void spawn(int next) {
        type = next;
        rotation = 0;
        px = type == 3 ? 4 : 3;
        py = -1;
        gravityTime = softTime = lockTime = 0;
        touchedGround = false;
        resets = 0;
        active = true;
        aiTime = aiStep = aiFall = 0;

        if (!fits(type, rotation, px, py)) {
            gameOver();
            return;
        }
        if (fits(type, rotation, px, py + 1)) py++;
        if (player == null) planAI();
    }

    private boolean fits(int t, int r, int x, int y) {
        for (int[] c : SHAPES[t][r]) {
            int xx = x + c[0], yy = y + c[1];
            if (xx < 0 || xx >= COLS || yy < -4 || yy >= ROWS) return false;
            if (yy >= 0 && board[yy][xx] != 0) return false;
        }
        return true;
    }

    private int ghostY() {
        int y = py;
        while (fits(type, rotation, px, y + 1)) y++;
        return y;
    }

    private boolean grounded() {
        return !fits(type, rotation, px, py + 1);
    }

    private void manipulated(boolean wasGrounded) {
        if (wasGrounded) touchedGround = true;
        if (touchedGround && resets < 15) {
            resets++;
            lockTime = 0;
        }
    }

    private boolean move(int direction, boolean audible) {
        if (!active || over || direction == 0
                || !fits(type, rotation, px + direction, py)) return false;
        boolean ground = grounded();
        px += direction;
        manipulated(ground);
        if (audible) sound("block.note_block.hat", 0.8f);
        return true;
    }

    private void rotate() {
        if (!active || over || type == 3) return;
        int next = (rotation + 1) & 3;
        boolean ground = grounded();
        for (int[] kick : (type == 0 ? I_KICKS : KICKS)[rotation]) {
            if (!fits(type, next, px + kick[0], py + kick[1])) continue;
            px += kick[0];
            py += kick[1];
            rotation = next;
            manipulated(ground);
            rotatePulse = 0.18;
            sound("block.note_block.bit", 1.25f);
            return;
        }
    }

    private boolean descend(boolean soft) {
        if (!active || !fits(type, rotation, px, py + 1)) return false;
        py++;
        if (resets < 15) lockTime = 0;
        if (soft) addScore(1);
        return true;
    }

    private void useHold() {
        if (!active || over || holdUsed) return;
        int old = hold;
        hold = type;
        if (old < 0) {
            fillQueue();
            int next = queue.removeFirst();
            fillQueue();
            spawn(next);
        } else {
            spawn(old);
        }
        holdUsed = true;
        sound("block.note_block.flute", 1.4f);
    }

    private void hardDrop() {
        if (!active || over) return;
        int bottom = ghostY();
        dropX = px;
        dropTop = py;
        dropBottom = bottom;
        dropType = type;
        dropRotation = rotation;
        dropTime = 0.32;
        addScore((bottom - py) * 2);
        py = bottom;
        impact = 0.35;
        shake = Math.max(shake, 3.5);
        sound("block.note_block.basedrum", 0.75f);
        lock(true);
    }

    private void lock(boolean hard) {
        if (!active || over) return;
        for (int[] c : SHAPES[type][rotation]) {
            if (py + c[1] < 0) {
                gameOver();
                return;
            }
        }

        lockedType = type;
        lockedRotation = rotation;
        lockedX = px;
        lockedY = py;
        lockFlash = 0.25;
        piecesPlaced++;

        for (int[] c : SHAPES[type][rotation]) {
            int x = px + c[0], y = py + c[1];
            board[y][x] = type + 1;
            burst(BX + x * CELL + CELL / 2.0,
                    BY + (y + 1) * CELL - 2,
                    COLORS[type], hard ? 8 : 4, hard ? 105 : 55);
        }
        active = false;
        wellDirty = true;
        impact = Math.max(impact, 0.16);
        pending = 0;

        for (int y = 0; y < ROWS; y++) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) full &= board[y][x] != 0;
            clearing[y] = full;
            if (full) pending++;
        }

        if (pending == 0) {
            combo = -1;
            if (!hard) sound("block.note_block.basedrum", 0.6f);
            nextPiece();
            return;
        }

        clearTime = CLEAR_DURATION;
        shake = pending == 4 ? 7 : 4;
        notice = new String[]{"", "ROW MINED!", "DOUBLE!", "TRIPLE!", "BLOCK DROP!"}[pending];
        detail = pending == 4 ? "Four rows. One perfect strike." : "Keep the mine moving.";
        noticeTime = 2.8;
        lastReward = 0;
        sound(pending == 4 ? "entity.player.levelup" : "block.note_block.chime",
                pending == 4 ? 1.1f : 1.5f);
        for (int y = 0; y < ROWS; y++) {
            if (!clearing[y]) continue;
            for (int x = 0; x < COLS; x++) {
                burst(BX + x * CELL + CELL / 2.0,
                        BY + y * CELL + CELL / 2.0,
                        COLORS[board[y][x] - 1], 8, 155);
            }
        }
    }

    private void finishClear() {
        int n = pending;
        if (n <= 0) return;

        int reward = new int[]{0, 100, 300, 500, 800}[n] * level;
        boolean chained = n == 4 && backToBack;
        if (chained) reward = reward * 3 / 2;
        backToBack = n == 4;
        combo++;
        reward += Math.max(0, combo) * 50 * level;

        int dst = ROWS - 1;
        for (int src = ROWS - 1; src >= 0; src--) {
            if (clearing[src]) continue;
            if (dst != src) System.arraycopy(board[src], 0, board[dst], 0, COLS);
            dst--;
        }
        while (dst >= 0) Arrays.fill(board[dst--], 0);
        Arrays.fill(clearing, false);
        wellDirty = true;
        lockFlash = 0;

        boolean perfect = true;
        for (int[] row : board)
            for (int value : row) if (value != 0) perfect = false;
        if (perfect) reward += 2000 * level;

        addScore(reward);
        lastReward = reward;
        lines += n;
        int oldLevel = level;
        level = Math.min(99, 1 + lines / 10);

        if (perfect) notice = "PERFECT CLEAR!";
        else if (chained) notice = "BACK-TO-BACK!";
        detail = combo > 0 ? "COMBO x" + (combo + 1) : "More space. More possibilities.";
        if (level > oldLevel) {
            detail = "LEVEL " + level + " — FASTER FALL";
            sound("entity.experience_orb.pickup", 1.5f);
        }
        noticeTime = 2.8;
        impact = 0.24;
        shake = Math.max(shake, 2.5);
        pending = 0;
        clearTime = 0;
        nextPiece();
    }

    private void addScore(int amount) {
        score = (int) Math.min(999_999_999L, score + (long) Math.max(0, amount));
        if (amount >= 20) scorePulse = 0.35;
    }

    private void gameOver() {
        if (over) return;
        over = true;
        active = false;
        overTime = 0;
        keys.clear();
        repeatDirection = 0;
        bufferedRotations = 0;
        bufferedHold = false;
        shake = 6;
        if (player != null && host != null) {
            rank = host.score(player, score);
            refreshScores();
            sound("block.note_block.bass", 0.5f);
        }
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (!owns(seat) || key == null) return;
        if (!down) {
            keys.remove(key);
            if (key == Key.DOWN) softTime = 0;
            return;
        }
        if (!keys.add(key)) return;

        if (over) {
            if (key == Key.JUMP && overTime > 0.9) reset();
            return;
        }

        if (clearTime > 0) {
            if (key == Key.UP) bufferedRotations = (bufferedRotations + 1) & 3;
            if (key == Key.SWAP_HANDS) bufferedHold = true;
            // Never buffer an irreversible hard drop.
            return;
        }

        switch (key) {
            case LEFT, RIGHT -> {
                int direction = key == Key.LEFT ? -1 : 1;
                move(direction, true);
                repeatDirection = direction;
                repeatTime = 0.16;
            }
            case UP -> rotate();
            case DOWN -> softTime = 0.035;
            case JUMP -> hardDrop();
            case SWAP_HANDS -> useHold();
            default -> {}
        }
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        // Gameplay is deliberately keyboard-only; incidental clicks cannot
        // rotate or drop an unintended piece.
        if (owns(seat) && over && overTime > 0.9) reset();
    }

    private int heldDirection() {
        boolean left = keys.contains(Key.LEFT);
        boolean right = keys.contains(Key.RIGHT);
        return left == right ? 0 : left ? -1 : 1;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        roundTime += dt;
        noticeTime = Math.max(0, noticeTime - dt);
        dropTime = Math.max(0, dropTime - dt);
        impact = Math.max(0, impact - dt);
        scorePulse = Math.max(0, scorePulse - dt);
        rotatePulse = Math.max(0, rotatePulse - dt);
        lockFlash = Math.max(0, lockFlash - dt);
        shake *= Math.exp(-13 * dt);

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 250 * dt;
                p.vx *= Math.exp(-2 * dt);
            }
        }

        if ((pollTime -= dt) <= 0) {
            refreshScores();
            pollTime = 4;
        }
        if (over) {
            overTime += dt;
            if (player == null && overTime > 2) reset();
            return;
        }
        if (clearTime > 0) {
            clearTime -= dt;
            if (clearTime <= 0) finishClear();
            return;
        }
        if (!active) return;
        if (player == null) {
            tickAI(dt);
            return;
        }

        int direction = heldDirection();
        if (direction == 0) {
            repeatDirection = 0;
        } else if (direction != repeatDirection) {
            repeatDirection = direction;
            repeatTime = 0.16;
            move(direction, false);
        } else {
            repeatTime -= dt;
            while (repeatTime <= 0) {
                move(direction, false);
                repeatTime += 0.05;
            }
        }

        if (keys.contains(Key.DOWN)) {
            softTime += dt;
            while (softTime >= 0.035) {
                softTime -= 0.035;
                if (descend(true)) gravityTime = 0;
            }
        }

        double gravity = Math.max(0.05, 0.78 * Math.pow(0.79, level - 1));
        gravityTime += dt;
        while (gravityTime >= gravity) {
            gravityTime -= gravity;
            descend(false);
        }

        boolean ground = grounded();
        if (ground) touchedGround = true;
        if (touchedGround) {
            if (ground || resets >= 15) lockTime += dt;
            else lockTime = 0;
            if (ground && lockTime >= LOCK_DELAY) lock(false);
        }
    }

    private void planAI() {
        double best = -Double.MAX_VALUE;
        aiX = px;
        aiRotation = rotation;
        int[][] test = new int[ROWS][COLS];
        int[] heights = new int[COLS];

        for (int r = 0; r < (type == 3 ? 1 : 4); r++) {
            for (int x = -3; x < COLS; x++) {
                if (!fits(type, r, x, py)) continue;
                int y = py;
                while (fits(type, r, x, y + 1)) y++;

                boolean above = false;
                for (int[] c : SHAPES[type][r])
                    if (y + c[1] < 0) above = true;
                if (above) continue;

                for (int row = 0; row < ROWS; row++)
                    System.arraycopy(board[row], 0, test[row], 0, COLS);
                for (int[] c : SHAPES[type][r])
                    test[y + c[1]][x + c[0]] = type + 1;

                int cleared = 0, dst = ROWS - 1;
                for (int src = ROWS - 1; src >= 0; src--) {
                    boolean full = true;
                    for (int value : test[src]) full &= value != 0;
                    if (full) cleared++;
                    else {
                        if (dst != src)
                            System.arraycopy(test[src], 0, test[dst], 0, COLS);
                        dst--;
                    }
                }
                while (dst >= 0) Arrays.fill(test[dst--], 0);

                Arrays.fill(heights, 0);
                int holes = 0, aggregate = 0, bump = 0, highest = 0;
                int coveredHoles = 0;
                for (int col = 0; col < COLS; col++) {
                    boolean seen = false;
                    int cover = 0;
                    for (int row = 0; row < ROWS; row++) {
                        if (test[row][col] != 0) {
                            if (!seen) heights[col] = ROWS - row;
                            seen = true;
                            cover++;
                        } else if (seen) {
                            holes++;
                            coveredHoles += cover;
                        }
                    }
                    aggregate += heights[col];
                    highest = Math.max(highest, heights[col]);
                    if (col > 0) bump += Math.abs(heights[col] - heights[col - 1]);
                }
                int wells = 0;
                for (int col = 0; col < COLS; col++) {
                    int a = col == 0 ? ROWS : heights[col - 1];
                    int b = col == COLS - 1 ? ROWS : heights[col + 1];
                    wells += Math.max(0, Math.min(a, b) - heights[col] - 2);
                }
                double value = cleared * 11 - holes * 12 - coveredHoles * 0.35
                        - aggregate * 0.53 - bump * 0.4 - highest * 0.6
                        - wells * 0.35 - Math.abs(x - px) * 0.015;
                if (value > best) {
                    best = value;
                    aiX = x;
                    aiRotation = r;
                }
            }
        }
    }

    private void tickAI(double dt) {
        aiTime += dt;
        aiStep += dt;
        if (aiStep >= 0.07) {
            aiStep = 0;
            if (rotation != aiRotation) rotate();
            else if (px != aiX) move(Integer.compare(aiX, px), false);
        }

        boolean aligned = rotation == aiRotation && px == aiX;
        if (aligned && aiTime > 0.36) {
            aiFall += dt;
            // Animate most of the descent, then visibly slam the last cells.
            int landing = ghostY();
            while (aiFall >= 0.018 && py < landing - 4) {
                aiFall -= 0.018;
                descend(false);
            }
            if (aiTime > 0.82) hardDrop();
        } else if (aiTime > 1.7) {
            // A blocked movement path must never freeze the demo.
            hardDrop();
        }
    }

    private void sound(String id, float pitch) {
        if (host != null && player != null) {
            host.sound(id, 0.45f, Math.max(0.5f, Math.min(2, pitch)));
        }
    }

    private void burst(double x, double y, Color color, int count, double speed) {
        for (int i = 0; i < count && particles.size() < 420; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double angle = effects.nextDouble() * Math.PI * 2;
            double velocity = speed * (0.35 + effects.nextDouble());
            p.vx = Math.cos(angle) * velocity;
            p.vy = Math.sin(angle) * velocity - 55;
            p.maxLife = p.life = 0.4 + effects.nextDouble() * 0.5;
            p.size = 2 + effects.nextInt(4);
            p.color = color;
            particles.add(p);
        }
    }

    @Override
    public void draw(Graphics2D original) {
        int width = host == null ? 2048 : host.width();
        int height = host == null ? 1152 : host.height();
        original.setColor(INK);
        original.fillRect(0, 0, width, height);
        if (host == null || background == null) return;
        if (wellDirty) rebuildWell();

        Graphics2D g = (Graphics2D) original.create();
        g.scale(width / (double) W, height / (double) H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(background, 0, 0, null);

        for (int i = 0; i < 22; i++) {
            int x = (int) ((i * 127.7 + Math.sin(clock * 0.28 + i) * 12 + W) % W);
            int y = (int) (H - (clock * (5 + i % 4) + i * 41.7) % H);
            g.setColor(alpha(i % 3 == 0 ? GOLD : CYAN, 28 + i % 4 * 13));
            g.fillRect(x, y, 2, 2);
        }

        text(g, "BLOCK", 32, 24, 39, WHITE, -1);
        text(g, "DROP", 32, 139, 39, GOLD, -1);
        text(g, player == null ? "LIVE MINE" : "THE MINE", 17, 480, 26, CYAN, 0);
        fitText(g, player == null ? "AUTOPILOT / DEMO" : shortName(player.name(), 22),
                20, 294, 934, 35, CYAN, 1);

        drawLeft(g);
        drawRight(g);
        drawWell(g);

        for (Particle p : particles) {
            g.setColor(alpha(p.color, (int) (255 * p.life / p.maxLife)));
            g.fillRect((int) p.x, (int) p.y, p.size, p.size);
            if (p.size > 3) {
                g.setColor(alpha(WHITE, (int) (110 * p.life / p.maxLife)));
                g.fillRect((int) p.x, (int) p.y, p.size - 1, 1);
            }
        }

        if (over) drawGameOver(g);
        g.dispose();
    }

    private void rebuildWell() {
        Graphics2D g = wellImage.createGraphics();
        g.setColor(new Color(0x07131B));
        g.fillRect(0, 0, COLS * CELL, ROWS * CELL);
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int sx = x * CELL, sy = y * CELL;
                g.setColor((x + y) % 2 == 0
                        ? new Color(0x10232D) : new Color(0x122731));
                g.fillRect(sx + 1, sy + 1, CELL - 1, CELL - 1);
                g.setColor(new Color(0x203640));
                g.fillRect(sx + CELL / 2, sy + CELL / 2, 1, 1);
                if (board[y][x] != 0) {
                    tile(g, board[y][x] - 1, sx, sy, CELL);
                    // Settled cells are slightly quieter than the active piece.
                    g.setColor(new Color(3, 12, 18, 28));
                    g.fillRect(sx + 1, sy + 1, CELL - 2, CELL - 2);
                }
            }
        }
        g.dispose();
        wellDirty = false;
    }

    private boolean danger() {
        for (int y = 0; y < 5; y++)
            for (int value : board[y]) if (value != 0) return true;
        return false;
    }

    private void drawWell(Graphics2D parent) {
        Graphics2D g = (Graphics2D) parent.create();
        g.translate(Math.sin(clock * 77) * shake,
                Math.cos(clock * 91) * shake * 0.45);
        boolean dangerous = danger();
        Color rim = impact > 0 ? GOLD : dangerous ? RED : CYAN;

        g.setColor(alpha(rim, dangerous ? 50 : 22));
        g.fillRoundRect(BX - 11, BY - 9, COLS * CELL + 22,
                ROWS * CELL + 18, 12, 12);
        g.setColor(INK);
        g.fillRect(BX - 5, BY - 5, COLS * CELL + 10, ROWS * CELL + 10);
        g.setStroke(new BasicStroke(2));
        g.setColor(alpha(rim, 205));
        g.drawRect(BX - 3, BY - 3, COLS * CELL + 6, ROWS * CELL + 6);
        g.clipRect(BX, BY, COLS * CELL, ROWS * CELL);
        g.drawImage(wellImage, BX, BY, null);

        if (dangerous) {
            g.setPaint(new GradientPaint(0, BY,
                    alpha(RED, (int) (45 + 18 * Math.sin(clock * 6))),
                    0, BY + CELL * 5, alpha(RED, 0)));
            g.fillRect(BX, BY, COLS * CELL, CELL * 5);
        }

        if (dropTime > 0) {
            double f = dropTime / 0.32;
            g.setColor(alpha(COLORS[dropType], (int) (78 * f)));
            for (int[] c : SHAPES[dropType][dropRotation]) {
                int x = BX + (dropX + c[0]) * CELL;
                int y = BY + (dropTop + c[1]) * CELL;
                int bottom = BY + (dropBottom + c[1] + 1) * CELL;
                g.fillRect(x + 7, y, CELL - 14, Math.max(0, bottom - y));
            }
            int bottom = BY;
            for (int[] c : SHAPES[dropType][dropRotation])
                bottom = Math.max(bottom, BY + (dropBottom + c[1] + 1) * CELL);
            int spread = (int) ((1 - f) * 90);
            int center = BX + (dropX + 2) * CELL;
            g.setColor(alpha(GOLD, (int) (220 * f)));
            g.fillRect(center - spread - 18, bottom - 2, spread * 2 + 36, 3);
        }

        if (lockFlash > 0) {
            for (int[] c : SHAPES[lockedType][lockedRotation]) {
                int x = BX + (lockedX + c[0]) * CELL;
                int y = BY + (lockedY + c[1]) * CELL;
                g.setColor(alpha(WHITE, (int) (150 * lockFlash / 0.25)));
                g.fillRect(x + 1, y + 1, CELL - 2, CELL - 2);
            }
        }

        if (active) {
            int ghost = ghostY();
            boolean ground = grounded();
            if (ghost != py) {
                for (int[] c : SHAPES[type][rotation]) {
                    int x = BX + (px + c[0]) * CELL;
                    int y = BY + (ghost + c[1]) * CELL;
                    g.setColor(alpha(COLORS[type], 42));
                    g.fillRect(x + 2, y + 2, CELL - 4, CELL - 4);
                    g.setStroke(new BasicStroke(1));
                    g.setColor(alpha(COLORS[type], 205));
                    g.drawRect(x + 2, y + 2, CELL - 5, CELL - 5);
                    g.setColor(WHITE);
                    g.fillRect(x + 2, y + 2, 6, 2);
                    g.fillRect(x + 2, y + 2, 2, 6);
                    g.fillRect(x + CELL - 8, y + CELL - 4, 6, 2);
                    g.fillRect(x + CELL - 4, y + CELL - 8, 2, 6);
                }
            }

            for (int[] c : SHAPES[type][rotation]) {
                int x = BX + (px + c[0]) * CELL;
                int y = BY + (py + c[1]) * CELL;
                tile(g, type, x, y, CELL);
                if (rotatePulse > 0) {
                    g.setColor(alpha(WHITE, (int) (90 * rotatePulse / 0.18)));
                    g.fillRect(x + 1, y + 1, CELL - 2, CELL - 2);
                }
                // Outline only the exposed edges of the active tetromino.
                g.setColor(WHITE);
                g.setStroke(new BasicStroke(1.5f));
                if (!hasCell(type, rotation, c[0] - 1, c[1]))
                    g.drawLine(x + 1, y + 1, x + 1, y + CELL - 2);
                if (!hasCell(type, rotation, c[0] + 1, c[1]))
                    g.drawLine(x + CELL - 2, y + 1, x + CELL - 2, y + CELL - 2);
                if (!hasCell(type, rotation, c[0], c[1] - 1))
                    g.drawLine(x + 1, y + 1, x + CELL - 2, y + 1);
                if (!hasCell(type, rotation, c[0], c[1] + 1))
                    g.drawLine(x + 1, y + CELL - 2, x + CELL - 2, y + CELL - 2);
                if (ground) {
                    g.setColor(GOLD);
                    g.fillRect(x + 2, y + CELL - 4,
                            (int) ((CELL - 4) * Math.min(1, lockTime / LOCK_DELAY)), 2);
                }
            }
        }

        if (clearTime > 0) {
            double elapsed = CLEAR_DURATION - clearTime;
            for (int y = 0; y < ROWS; y++) {
                if (!clearing[y]) continue;
                g.setColor(alpha(WHITE, (int) (170 + 65 * Math.cos(elapsed * 24))));
                g.fillRect(BX, BY + y * CELL, COLS * CELL, CELL);
                int width = (int) (COLS * CELL * Math.min(1, elapsed / 0.25));
                g.setColor(GOLD);
                g.fillRect(BX + (COLS * CELL - width) / 2,
                        BY + y * CELL + CELL / 2 - 2, width, 4);
            }
            if (pending >= 2) {
                g.setColor(new Color(4, 13, 21, 225));
                g.fillRect(BX, BY + 180, COLS * CELL, 65);
                text(g, pending == 4 ? "BLOCK DROP!" : pending == 3 ? "TRIPLE!" : "DOUBLE!",
                        pending == 4 ? 27 : 35, BX + COLS * CELL / 2,
                        BY + 222, GOLD, 0);
            }
        }

        if (player != null && roundTime < 1.4 && !over) {
            int a = (int) (210 * Math.min(1, (1.4 - roundTime) / 0.4));
            g.setColor(new Color(5, 16, 24, Math.max(0, a)));
            g.fillRect(BX, BY + 185, COLS * CELL, 85);
            text(g, "LET'S BUILD", 29, 480, BY + 218, alpha(WHITE, a), 0);
            text(g, "W rotate  /  SPACE drop", 16, 480, BY + 247, alpha(CYAN, a), 0);
        }
        g.dispose();
    }

    private static boolean hasCell(int t, int r, int x, int y) {
        for (int[] c : SHAPES[t][r])
            if (c[0] == x && c[1] == y) return true;
        return false;
    }

    private void drawLeft(Graphics2D g) {
        panel(g, 24, 60, 304, 132, CYAN);
        text(g, player == null ? "DEMO SCORE" : "SCORE", 17, 42, 86, MUTED, -1);
        number(g, score, 38, 42, 128, scorePulse > 0 ? GOLD : WHITE);
        text(g, "LEVEL " + level, 22, 42, 162, GOLD, -1);
        text(g, lines + " ROWS", 22, 310, 162, CYAN, 1);
        g.setColor(new Color(0x29434D));
        g.fillRoundRect(43, 177, 266, 5, 4, 4);
        g.setColor(GOLD);
        g.fillRoundRect(43, 177, 266 * (lines % 10) / 10, 5, 4, 4);

        panel(g, 24, 207, 304, 104, holdUsed ? MUTED : CYAN);
        text(g, "HOLD", 19, 42, 235, holdUsed ? MUTED : CYAN, -1);
        text(g, holdUsed ? "NEXT TURN" : "[ F ]", 18, 310, 235, MUTED, 1);
        if (hold >= 0) {
            miniature(g, hold, 98, 274, 24, holdUsed);
            fitText(g, NAMES[hold], 18, 153, 155, 281, WHITE, -1);
        } else {
            g.setColor(new Color(0x203945));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(42, 251, 56, 43, 7, 7);
            text(g, "F", 25, 70, 281, CYAN, 0);
            text(g, "Save a piece", 21, 114, 272, WHITE, -1);
            text(g, "Swap once each turn", 16, 114, 296, MUTED, -1);
        }

        panel(g, 24, 326, 304, 194, GOLD);
        if (player == null) {
            text(g, "ONE MORE BLOCK.", 25, 176, 362, GOLD, 0);
            text(g, "Fill rows. Keep the mine clear.", 17, 176, 389, WHITE, 0);

            int a = (int) (42 + 17 * Math.sin(clock * 4));
            g.setColor(alpha(CYAN, a));
            g.fillRoundRect(39, 403, 274, 43, 8, 8);
            text(g, "SIT DOWN TO PLAY", 23, 176, 432, CYAN, 0);

            if (!highScores.isEmpty()) {
                Host.Score best = highScores.get(0);
                text(g, "TOP BUILDER", 14, 42, 469, GOLD, -1);
                fitText(g, shortName(best.name(), 17), 18, 161,
                        42, 498, WHITE, -1);
                fitText(g, Integer.toString(best.score()), 20, 103,
                        309, 498, CYAN, 1);
            } else {
                text(g, "A / D move     W rotate", 17, 176, 474, WHITE, 0);
                text(g, "SPACE drop     F hold", 17, 176, 502, CYAN, 0);
            }
        } else {
            text(g, "MAKE YOUR MOVE", 19, 42, 355, GOLD, -1);
            control(g, "A / D", "Move", 387);
            control(g, "W", "Rotate", 420);
            control(g, "S", "Soft drop", 453);
            control(g, "SPACE", "Drop & lock", 487);
            text(g, "Outline = landing position", 15, 176, 509, MUTED, 0);
        }
    }

    private void control(Graphics2D g, String key, String action, int y) {
        g.setColor(new Color(0x233D49));
        g.fillRoundRect(40, y - 22, 78, 28, 6, 6);
        text(g, key, 18, 79, y, CYAN, 0);
        text(g, action, 20, 133, y, WHITE, -1);
    }

    private void drawRight(Graphics2D g) {
        panel(g, 632, 60, 304, 265, CYAN);
        text(g, "NEXT", 21, 650, 89, CYAN, -1);
        text(g, "7-BLOCK BAG", 16, 918, 88, MUTED, 1);

        int i = 0;
        for (int t : queue) {
            if (i == 4) break;
            int y = 126 + i * 53;
            if (i == 0) {
                g.setColor(alpha(COLORS[t], 29));
                g.fillRoundRect(645, 102, 278, 49, 7, 7);
                g.setColor(COLORS[t]);
                g.fillRect(645, 111, 3, 30);
            }
            miniature(g, t, 704, y, i == 0 ? 24 : 21, false);
            fitText(g, NAMES[t], 18, 154, 760, y + 6,
                    i == 0 ? WHITE : MUTED, -1);
            i++;
        }

        panel(g, 632, 340, 304, 180, noticeTime > 0 ? GOLD : CYAN);
        if (noticeTime > 0) {
            double entrance = Math.min(1, (2.8 - noticeTime) * 7);
            int offset = lastReward > 0 ? (int) (5 * (1 - entrance)) : 0;
            fitText(g, notice, 27, 274, 784, 379 + offset, GOLD, 0);
            if (lastReward > 0) {
                text(g, "+" + lastReward, 44, 784, 435, WHITE, 0);
                fitText(g, detail, 17, 269, 784, 471, CYAN, 0);
                text(g, "KEEP DIGGING", 15, 784, 502, MUTED, 0);
            } else {
                fitText(g, detail, 18, 270, 784, 418, WHITE, 0);
                text(g, "10 rows = next level", 19, 784, 456, CYAN, 0);
                text(g, "Clear space. Build your score.", 16, 784, 494, MUTED, 0);
            }
        } else if (player == null && highScores.size() > 1) {
            text(g, "TOP BUILDERS", 23, 650, 375, GOLD, -1);
            for (int j = 0; j < Math.min(3, highScores.size()); j++) {
                Host.Score s = highScores.get(j);
                int y = 414 + j * 36;
                fitText(g, (j + 1) + ". " + shortName(s.name(), 15),
                        18, 173, 650, y, WHITE, -1);
                fitText(g, Integer.toString(s.score()), 18, 87,
                        918, y, CYAN, 1);
            }
        } else {
            text(g, danger() ? "DANGER: HIGH STACK" :
                    backToBack ? "CHAIN ANOTHER FOUR!" : "DIG FOR THE BIG ONE",
                    21, 784, 377, danger() ? RED : GOLD, 0);
            text(g, "1 ROW", 17, 651, 416, MUTED, -1);
            text(g, "100", 22, 746, 418, WHITE, 1);
            text(g, "2 ROWS", 17, 787, 416, MUTED, -1);
            text(g, "300", 22, 918, 418, WHITE, 1);
            text(g, "3 ROWS", 17, 651, 456, MUTED, -1);
            text(g, "500", 22, 746, 458, WHITE, 1);
            text(g, "4 ROWS", 17, 787, 456, GOLD, -1);
            text(g, "800", 22, 918, 458, GOLD, 1);
            text(g, backToBack ? "Next four-row clear: +50%" :
                    "All clear points × your level", 17, 784, 498, CYAN, 0);
        }
    }

    private void drawGameOver(Graphics2D g) {
        g.setColor(new Color(3, 9, 17, 195));
        g.fillRect(0, 0, W, H);
        panel(g, 218, 117, 524, 307, GOLD);
        text(g, "STACKED OUT!", 48, 480, 181, WHITE, 0);
        text(g, Integer.toString(score), 53, 480, 250, GOLD, 0);
        text(g, lines + " ROWS  /  LEVEL " + level, 23, 480, 291, CYAN, 0);
        text(g, player == null ? "AUTOPILOT REBUILDING..."
                        : rank > 0 ? "TOP BUILDER #" + rank : "THE NEXT MINE IS YOURS.",
                21, 480, 335, WHITE, 0);
        if (player != null && overTime > 0.9) {
            g.setColor(alpha(CYAN, 34 + (int) (12 * Math.sin(clock * 4))));
            g.fillRoundRect(256, 357, 448, 45, 8, 8);
            text(g, "SPACE OR CLICK TO PLAY AGAIN", 22, 480, 386, CYAN, 0);
        }
    }

    private void miniature(Graphics2D g, int t, int cx, int cy, int size, boolean dim) {
        int minX = 4, minY = 4, maxX = 0, maxY = 0;
        for (int[] c : SHAPES[t][0]) {
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxY = Math.max(maxY, c[1]);
        }
        int x0 = cx - (maxX - minX + 1) * size / 2;
        int y0 = cy - (maxY - minY + 1) * size / 2;
        for (int[] c : SHAPES[t][0]) {
            int x = x0 + (c[0] - minX) * size;
            int y = y0 + (c[1] - minY) * size;
            tile(g, t, x, y, size);
            if (dim) {
                g.setColor(new Color(8, 18, 28, 125));
                g.fillRect(x, y, size, size);
            }
        }
    }

    private void tile(Graphics2D g, int t, int x, int y, int size) {
        g.setColor(INK);
        g.fillRect(x, y, size, size);
        g.drawImage(tiles[t], x + 1, y + 1, size - 2, size - 2, null);
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, Color accent) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(x, y + 4, w, h, 12, 12);
        g.setColor(new Color(0x102731));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(new Color(0x35515A));
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setColor(accent);
        g.fillRect(x + 16, y, 44, 2);
    }

    private static void text(Graphics2D g, String s, int size, int x, int y,
                             Color color, int align) {
        g.setFont(FONTS[Math.clamp(size, 1, 72)]);
        int width = g.getFontMetrics().stringWidth(s);
        int xx = align < 0 ? x : align > 0 ? x - width : x - width / 2;
        g.setColor(new Color(0, 0, 0, Math.min(140, color.getAlpha())));
        g.drawString(s, xx, y + 1);
        g.setColor(color);
        g.drawString(s, xx, y);
    }

    private static void fitText(Graphics2D g, String s, int size, int maxWidth,
                                int x, int y, Color color, int align) {
        while (size > 12) {
            g.setFont(FONTS[Math.clamp(size, 1, 72)]);
            if (g.getFontMetrics().stringWidth(s) <= maxWidth) break;
            size--;
        }
        text(g, s, size, x, y, color, align);
    }

    private static void number(Graphics2D g, int value, int size, int x, int y, Color color) {
        String s = Integer.toString(value);
        s = "0".repeat(Math.max(0, 7 - s.length())) + s;
        while (size > 20) {
            g.setFont(MONO[size]);
            if (g.getFontMetrics().stringWidth(s) <= 268) break;
            size--;
        }
        g.setFont(MONO[size]);
        g.setColor(new Color(0, 0, 0, 140));
        g.drawString(s, x, y + 2);
        g.setColor(color);
        g.drawString(s, x, y);
    }

    private static String shortName(String name, int limit) {
        if (name == null) return "PLAYER";
        return name.length() <= limit ? name : name.substring(0, limit - 1) + "…";
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.clamp(a, 0, 255));
    }

    private static int[][][][] makeShapes() {
        String[][] bases = {
                {"....", "####", "....", "...."},
                {"#..", "###", "..."},
                {"..#", "###", "..."},
                {"##", "##"},
                {".##", "##.", "..."},
                {".#.", "###", "..."},
                {"##.", ".##", "..."}
        };
        int[][][][] result = new int[7][4][4][2];
        for (int t = 0; t < 7; t++) {
            int n = bases[t].length, at = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (bases[t][y].charAt(x) == '#') {
                        result[t][0][at][0] = x;
                        result[t][0][at++][1] = y;
                    }
                }
            }
            for (int r = 1; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    result[t][r][c][0] = n - 1 - result[t][r - 1][c][1];
                    result[t][r][c][1] = result[t][r - 1][c][0];
                }
            }
        }
        return result;
    }

    private void makeArt() {
        Random art = new Random(119);
        for (int t = 0; t < 7; t++) {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            Color base = switch (t) {
                case 0 -> new Color(0x346D86);
                case 1 -> new Color(0x929DB0);
                case 2 -> new Color(0xC08E50);
                case 3 -> new Color(0x777666);
                case 4 -> new Color(0x526F76);
                case 5 -> new Color(0x95603D);
                default -> new Color(0xDF4E42);
            };
            g.setColor(base);
            g.fillRect(0, 0, 16, 16);

            for (int i = 0; i < 25; i++) {
                int d = art.nextInt(39) - 19;
                g.setColor(new Color(
                        Math.clamp(base.getRed() + d, 0, 255),
                        Math.clamp(base.getGreen() + d, 0, 255),
                        Math.clamp(base.getBlue() + d, 0, 255)));
                g.fillRect(art.nextInt(16), art.nextInt(16), 2 + art.nextInt(3), 2);
            }

            switch (t) {
                case 0 -> {
                    g.setColor(new Color(0x95DFEF));
                    g.drawRect(1, 1, 13, 13);
                    g.setColor(new Color(0xE2FFFF));
                    g.fillRect(3, 3, 2, 5);
                    g.fillRect(5, 2, 3, 2);
                    g.fillRect(9, 10, 2, 3);
                    g.fillRect(11, 8, 2, 3);
                    g.setColor(new Color(0x5698AF));
                    g.fillRect(3, 12, 4, 1);
                    g.fillRect(7, 5, 2, 1);
                }
                case 1 -> {
                    g.setColor(new Color(0x566273));
                    g.fillRect(2, 5, 7, 1);
                    g.fillRect(8, 5, 1, 4);
                    g.fillRect(8, 9, 6, 1);
                    g.fillRect(3, 12, 1, 3);
                    g.setColor(new Color(0xD1D7DF));
                    g.fillRect(2, 4, 6, 1);
                    g.fillRect(4, 12, 6, 1);
                }
                case 2 -> {
                    g.setColor(new Color(0x76522C));
                    for (int y = 4; y < 16; y += 5) g.fillRect(0, y, 16, 1);
                    g.fillRect(5, 0, 1, 4);
                    g.fillRect(11, 5, 1, 4);
                    g.fillRect(4, 10, 1, 4);
                    g.setColor(new Color(0xF6CD89));
                    g.fillRect(1, 1, 11, 1);
                    g.fillRect(2, 6, 6, 1);
                    g.fillRect(6, 11, 8, 1);
                    g.setColor(new Color(0x8E6035));
                    g.fillRect(8, 2, 4, 1);
                    g.fillRect(2, 8, 5, 1);
                }
                case 3, 4 -> {
                    int[][] ore = {{3, 3}, {10, 2}, {7, 8}, {2, 11}, {12, 11}};
                    for (int[] p : ore) {
                        g.setColor(COLORS[t].darker().darker());
                        g.fillRect(p[0] - 1, p[1] - 1, 4, 4);
                        g.setColor(COLORS[t]);
                        g.fillRect(p[0], p[1], 3, 2);
                        g.fillRect(p[0], p[1] + 2, 2, 1);
                        g.setColor(t == 3 ? new Color(0xFFF2AA) : new Color(0xCCFFF2));
                        g.fillRect(p[0], p[1], 1, 1);
                    }
                }
                case 5 -> {
                    g.setColor(new Color(0xCB9465));
                    g.fillRect(2, 3, 3, 2);
                    g.fillRect(10, 7, 4, 2);
                    g.fillRect(4, 12, 3, 2);
                    g.setColor(new Color(0x613F2B));
                    g.fillRect(2, 8, 3, 2);
                    g.fillRect(9, 2, 4, 2);
                    g.setColor(new Color(0x82817B));
                    g.fillRect(7, 4, 2, 2);
                    g.fillRect(11, 12, 2, 2);
                }
                default -> {
                    g.setColor(new Color(0x812B2A));
                    for (int x = 3; x < 16; x += 4) g.fillRect(x, 1, 1, 14);
                    g.setColor(new Color(0xFFF1D2));
                    g.fillRect(0, 5, 16, 6);
                    g.setColor(new Color(0x302C2D));
                    for (int x : new int[]{1, 11}) {
                        g.fillRect(x, 6, 4, 1);
                        g.fillRect(x + 1, 7, 1, 3);
                    }
                    g.fillRect(6, 6, 1, 4);
                    g.fillRect(9, 6, 1, 4);
                    g.fillRect(7, 7, 1, 1);
                    g.fillRect(8, 8, 1, 1);
                }
            }

            // Crisp bevels give each texture a readable block silhouette.
            g.setColor(alpha(COLORS[t], 125));
            g.drawRect(0, 0, 15, 15);
            g.setColor(new Color(255, 255, 255, 105));
            g.fillRect(0, 0, 16, 1);
            g.fillRect(0, 0, 1, 16);
            g.setColor(new Color(0, 0, 0, 125));
            g.fillRect(0, 15, 16, 1);
            g.fillRect(15, 0, 1, 16);
            g.dispose();
            tiles[t] = image;
        }

        wellImage = new BufferedImage(COLS * CELL, ROWS * CELL, BufferedImage.TYPE_INT_RGB);
        background = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B3540),
                0, H, new Color(0x080F17)));
        g.fillRect(0, 0, W, H);

        for (int y = 0; y < H; y += 32) {
            for (int x = -32; x < W; x += 48) {
                int xx = x + ((y / 32) & 1) * 24;
                g.setColor(new Color(90, 139, 153, 9 + art.nextInt(12)));
                g.drawRect(xx, y, 46, 30);
                if (art.nextDouble() < 0.14) {
                    g.setColor(new Color(81, 200, 190, 35));
                    g.fillRect(xx + 11, y + 12, 5, 4);
                    g.fillRect(xx + 19, y + 19, 3, 3);
                }
            }
        }

        // Timber-and-iron shaft rails frame the action instead of the menus.
        for (int x : new int[]{BX - 20, BX + COLS * CELL + 10}) {
            g.setColor(new Color(0x433C32));
            g.fillRect(x, BY - 8, 10, ROWS * CELL + 16);
            g.setColor(new Color(0x76624A));
            g.fillRect(x + 1, BY - 8, 2, ROWS * CELL + 16);
            g.setColor(new Color(0x272B2D));
            g.fillRect(x + 7, BY - 8, 3, ROWS * CELL + 16);
            for (int y = BY; y < BY + ROWS * CELL; y += 48) {
                g.setColor(new Color(0x3C5057));
                g.fillRect(x - 1, y, 12, 8);
                g.setColor(new Color(0xA6B0A4));
                g.fillRect(x + 2, y + 2, 2, 2);
                g.fillRect(x + 7, y + 2, 2, 2);
            }
        }

        // Two small procedural lanterns, with cheap concentric light.
        for (int x : new int[]{342, 618}) {
            g.setColor(new Color(0x394C4D));
            g.fillRect(x - 1, 0, 2, 17);
            g.setColor(new Color(255, 194, 87, 13));
            g.fillOval(x - 19, 7, 38, 38);
            g.setColor(new Color(255, 194, 87, 25));
            g.fillOval(x - 12, 14, 24, 24);
            g.setColor(new Color(0x493E2D));
            g.fillRect(x - 5, 16, 10, 16);
            g.setColor(GOLD);
            g.fillRect(x - 3, 19, 6, 9);
            g.setColor(new Color(0xFFF3B1));
            g.fillRect(x - 1, 20, 2, 6);
        }
        g.dispose();
        wellDirty = true;
    }

    private static final class Particle {
        double x, y, vx, vy, life, maxLife;
        int size;
        Color color;
    }
}

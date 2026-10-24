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
 * Ore 2048: the sliding-number puzzle with ores, from coal up
 * to the 2048 tier.
 *
 * WASD slides every ore on the board (or click an arrow); two equal ores that
 * meet merge into the next. The panel beside the board lists every tier and
 * which have been found. When the board fills, click or space starts a new one.
 * With nobody seated, a simple AI plays.
 */
public final class Ore2048 implements Scene {
    private static final int W = 1024, H = 576;
    private static final int BX = 24, BY = 80;
    private static final int CELL = 108, GAP = 8, STEP = CELL + GAP;
    private static final int BOARD = CELL * 4 + GAP * 5;
    private static final double SLIDE = 0.14, TURN = 0.30;

    private static final Color BG = new Color(0x0C1920);
    private static final Color WHITE = new Color(0xF4F8EE);
    private static final Color MUTED = new Color(0xA8BEC2);
    private static final Color GOLD = new Color(0xFFDB7C);
    private static final Color MINT = new Color(0x80EDBE);
    private static final Color EDGE = new Color(0x425E65);
    private static final Color PANEL = new Color(0x142A32);
    private static final String[] NAMES = {
            "COAL", "COPPER", "IRON", "GOLD", "REDSTONE", "LAPIS",
            "EMERALD", "DIAMOND", "NETHERITE", "NETHER STAR", "AWAKENED STAR"
    };
    private static final Color[] ORE = {
            new Color(0xB4C6D5), new Color(0xF3AA77), new Color(0xEADFCC),
            new Color(0xFFDA50), new Color(0xFF6177), new Color(0x75AAFF),
            new Color(0x62EDA6), new Color(0x9AFFF1), new Color(0xC6ABD8),
            new Color(0xFFF0B2), new Color(0xFFE59B)
    };
    private static final int[] BUTTON_DIR = {0, 2, 3, 1};
    private static final String[] BUTTON_LABEL = {"A", "W", "S", "D"};
    private static final Font[] FONTS = new Font[81];

    static {
        for (int i = 1; i < FONTS.length; i++)
            FONTS[i] = new Font(Font.SANS_SERIF, Font.BOLD, i);
    }

    private final int[][] board = new int[4][4];
    private final boolean[][] merges = new boolean[4][4];
    private final ArrayList<Motion> motions = new ArrayList<>();
    private final ArrayList<Spark> sparks = new ArrayList<>();
    private final ArrayList<Reward> rewards = new ArrayList<>();
    private final EnumSet<Key> held = EnumSet.noneOf(Key.class);
    private final Random random = new Random(2048);
    private final Random effects = new Random(815);
    private final BufferedImage[] blocks = new BufferedImage[11];

    private Host host;
    private Seat player;
    private BufferedImage background;
    private List<Host.Score> leaders = List.of();
    private boolean moving, landed, over, won;
    private int score, best, rank, moves, run, discovered = 1;
    private int queued = -1, repeat = -1, lastDirection = -1;
    private int spawnR = -1, spawnC = -1, gain, mergeCount, mergeTier;
    private int mx = -1, my = -1;
    private double clock, age, overAge, demoWait, repeatWait;
    private double bannerTime, scoreTime, invalidTime, impact, poll, soundWait;
    private double discoveryFlash;
    private String banner = "";
    private int shownGain;

    public Ore2048() {}

    @Override
    public void start(Host host) {
        if (host == null) return;
        this.host = host;
        makeArt();
        refreshScores();
        List<Seat> seats = host.seats();
        player = seats.isEmpty() ? null : seats.get(0);
        reset();
    }

    @Override
    public void joined(Seat seat) {
        if (host == null || seat == null || player != null) return;
        player = seat;
        reset();
        sound("block.note_block.chime", 0.45f, 1.2f);
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

    private static int direction(Key key) {
        if (key == null) return -1;
        return switch (key) {
            case LEFT -> 0;
            case RIGHT -> 1;
            case UP -> 2;
            case DOWN -> 3;
            default -> -1;
        };
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        if (!owns(seat) || key == null) return;
        if (!down) {
            held.remove(key);
            if (direction(key) == repeat) {
                repeat = -1;
                for (Key other : held) {
                    int d = direction(other);
                    if (d >= 0) repeat = d;
                }
                repeatWait = 0.30;
            }
            return;
        }
        if (!held.add(key)) return;
        if (over) {
            if (key == Key.JUMP && overAge > 0.75) restart();
            return;
        }
        int d = direction(key);
        if (d >= 0) {
            repeat = d;
            repeatWait = 0.42;
            request(d);
        }
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        if (!owns(seat)) return;
        mx = (int) Math.floor(x * (double) W / Math.max(1, host.width()));
        my = (int) Math.floor(y * (double) H / Math.max(1, host.height()));
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        if (!owns(seat)) return;
        pointer(seat, x, y);
        if (right) return;
        if (over) {
            if (overAge > 0.75) restart();
        } else {
            int d = buttonAt(mx, my);
            if (d >= 0) request(d);
        }
    }

    private static int buttonAt(int x, int y) {
        if (y < 496 || y >= 545) return -1;
        for (int i = 0; i < 4; i++) {
            int left = 536 + 54 * i;
            if (x >= left && x < left + 46) return BUTTON_DIR[i];
        }
        return -1;
    }

    private void restart() {
        reset();
        sound("block.note_block.chime", 0.45f, 1.2f);
    }

    private void reset() {
        random.setSeed(2048L + run++ * 7919L);
        for (int r = 0; r < 4; r++) {
            Arrays.fill(board[r], 0);
            Arrays.fill(merges[r], false);
        }
        motions.clear();
        sparks.clear();
        rewards.clear();
        held.clear();
        score = rank = moves = shownGain = 0;
        queued = repeat = lastDirection = -1;
        spawnR = spawnC = -1;
        moving = landed = over = won = false;
        age = TURN;
        overAge = scoreTime = invalidTime = impact = discoveryFlash = 0;
        soundWait = 0;
        mx = my = -1;
        demoWait = 0.45;

        if (player == null) {
            int[][] showcase = {
                    {7, 6, 5, 4},
                    {3, 3, 2, 0},
                    {1, 2, 1, 0},
                    {0, 0, 0, 0}
            };
            for (int r = 0; r < 4; r++)
                System.arraycopy(showcase[r], 0, board[r], 0, 4);
            discovered = 7;
            banner = "LIVE DEMO  /  THE MINE PLAYS ITSELF";
            bannerTime = 3;
        } else {
            spawn();
            spawn();
            discovered = maximum(board);
            banner = "WASD TO SLIDE. MATCH EQUAL NUMBERS.";
            bannerTime = 5;
        }
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.1);
        clock += dt;
        bannerTime = Math.max(0, bannerTime - dt);
        scoreTime = Math.max(0, scoreTime - dt);
        invalidTime = Math.max(0, invalidTime - dt);
        impact = Math.max(0, impact - dt);
        discoveryFlash = Math.max(0, discoveryFlash - dt);
        soundWait = Math.max(0, soundWait - dt);
        poll -= dt;
        if (poll <= 0) {
            poll = 4;
            refreshScores();
        }

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark p = sparks.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                sparks.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 240 * dt;
            }
        }
        for (int i = rewards.size() - 1; i >= 0; i--) {
            Reward reward = rewards.get(i);
            reward.life -= dt;
            if (reward.life <= 0) rewards.remove(i);
        }

        if (over) {
            overAge += dt;
            if (player == null && overAge > 2.2) reset();
            return;
        }

        if (moving) {
            age += dt;
            if (age >= SLIDE && !landed) {
                landed = true;
                land();
            }
            if (age >= TURN) {
                moving = false;
                motions.clear();
                if (!hasMove(board)) finish();
                else if (queued >= 0 && player != null) {
                    int d = queued;
                    queued = -1;
                    slide(d);
                }
            }
        }
        if (over) return;

        if (player == null) {
            if (!moving) {
                demoWait -= dt;
                if (demoWait <= 0) {
                    int d = bestDirection();
                    if (d >= 0) slide(d);
                    else finish();
                    demoWait = 0.35;
                }
            }
        } else if (repeat >= 0) {
            repeatWait -= dt;
            if (repeatWait <= 0 && !moving && queued < 0) {
                slide(repeat);
                repeatWait = 0.31;
            }
        }
    }

    private void refreshScores() {
        if (host == null) return;
        leaders = List.copyOf(host.scores());
        if (!leaders.isEmpty()) best = Math.max(best, leaders.get(0).score());
    }

    private void sound(String id, float volume, float pitch) {
        if (host != null && player != null)
            host.sound(id, volume, Math.max(0.5f, Math.min(2f, pitch)));
    }

    private void request(int d) {
        if (over || d < 0 || d > 3) return;
        if (moving) queued = d;
        else slide(d);
    }

    private static int row(int d, int line, int position) {
        return d < 2 ? line : d == 2 ? position : 3 - position;
    }

    private static int col(int d, int line, int position) {
        return d >= 2 ? line : d == 0 ? position : 3 - position;
    }

    private void slide(int d) {
        if (moving || over || d < 0 || d > 3) return;
        int[][] result = new int[4][4];
        motions.clear();
        for (boolean[] line : merges) Arrays.fill(line, false);
        gain = mergeCount = mergeTier = 0;
        boolean changed = false;

        for (int line = 0; line < 4; line++) {
            int write = 0, previous = 0;
            boolean previousMerged = false;
            for (int p = 0; p < 4; p++) {
                int sr = row(d, line, p), sc = col(d, line, p);
                int tile = board[sr][sc];
                if (tile == 0) continue;
                boolean combine = write > 0 && tile == previous
                        && !previousMerged && tile < 30;
                int destination;
                if (combine) {
                    destination = write - 1;
                    int dr = row(d, line, destination);
                    int dc = col(d, line, destination);
                    result[dr][dc]++;
                    merges[dr][dc] = true;
                    previousMerged = true;
                    mergeCount++;
                    mergeTier = Math.max(mergeTier, tile + 1);
                    gain = add(gain, value(tile + 1));
                } else {
                    destination = write++;
                    result[row(d, line, destination)][col(d, line, destination)] = tile;
                    previous = tile;
                    previousMerged = false;
                }
                int dr = row(d, line, destination), dc = col(d, line, destination);
                motions.add(new Motion(sr, sc, dr, dc, tile));
                changed |= sr != dr || sc != dc || combine;
            }
        }
        lastDirection = d;

        if (!changed) {
            motions.clear();
            if (!hasMove(board)) {
                finish();
                return;
            }
            invalidTime = 0.24;
            if (soundWait <= 0) {
                sound("block.note_block.hat", 0.08f, 0.65f);
                soundWait = 0.65;
            }
            return;
        }

        // A reward belongs to the stationary tile it was created for.
        // Never leave a floating score behind when that tile starts sliding.
        rewards.clear();
        invalidTime = 0;
        for (int r = 0; r < 4; r++)
            System.arraycopy(result[r], 0, board[r], 0, 4);
        moving = true;
        landed = false;
        age = 0;
        moves++;
        spawn();
        if (mergeCount == 0) sound("block.note_block.hat", 0.10f, 1.25f);
    }

    private void spawn() {
        int empty = 0;
        for (int[] line : board)
            for (int v : line) if (v == 0) empty++;
        spawnR = spawnC = -1;
        if (empty == 0) return;
        int chosen = random.nextInt(empty);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (board[r][c] == 0 && chosen-- == 0) {
                    board[r][c] = random.nextDouble() < 0.9 ? 1 : 2;
                    spawnR = r;
                    spawnC = c;
                    return;
                }
            }
        }
    }

    private void land() {
        score = add(score, gain);
        if (player != null) best = Math.max(best, score);
        if (mergeCount > 0) {
            shownGain = gain;
            scoreTime = 0.8;
            impact = Math.min(0.20, 0.07 + mergeCount * 0.03);
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    if (!merges[r][c]) continue;
                    double x = cellX(c) + CELL / 2.0;
                    double y = cellY(r) + 29;
                    burst(x, y + 12, color(board[r][c]), 12 + Math.min(12, board[r][c]));
                    rewards.add(new Reward(x, y, "+" + value(board[r][c]),
                            color(board[r][c])));
                }
            }
            sound("block.note_block.bit", 0.35f,
                    (float) (0.75 + mergeTier * 0.10 + mergeCount * 0.04));
        }

        int highest = maximum(board);
        if (highest > discovered) {
            discovered = highest;
            banner = oreName(highest) + " DISCOVERED!";
            bannerTime = 2.7;
            discoveryFlash = 1;
            if (highest >= 4)
                sound("block.note_block.chime", 0.28f, 0.8f + highest * 0.09f);
        } else if (mergeCount >= 2 && bannerTime < 0.5) {
            banner = mergeCount + " MERGES IN ONE SLIDE!  +" + gain;
            bannerTime = 1.5;
        }
        if (!won && highest >= 11) {
            won = true;
            banner = "2048 FORGED! KEEP THE MINE GROWING.";
            bannerTime = 6;
            discoveryFlash = 2;
            burst(764, 130, GOLD, 70);
            sound("ui.toast.challenge_complete", 0.65f, 1f);
        }
    }

    private void finish() {
        if (over) return;
        over = true;
        moving = false;
        overAge = 0;
        queued = repeat = -1;
        held.clear();
        rewards.clear();
        if (player != null) {
            rank = host.score(player, score);
            refreshScores();
            sound("block.note_block.bass", 0.5f, 0.5f);
        }
    }

    private static int add(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    private static int value(int tier) {
        return 1 << Math.max(0, Math.min(30, tier));
    }

    private static int maximum(int[][] cells) {
        int max = 0;
        for (int[] line : cells)
            for (int v : line) max = Math.max(max, v);
        return max;
    }

    private static boolean hasMove(int[][] cells) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int v = cells[r][c];
                if (v == 0) return true;
                if (v < 30 && ((r < 3 && cells[r + 1][c] == v)
                        || (c < 3 && cells[r][c + 1] == v))) return true;
            }
        }
        return false;
    }

    private static int[][] simulated(int[][] source, int d) {
        int[][] out = new int[4][4];
        for (int line = 0; line < 4; line++) {
            int[] packed = new int[4];
            int n = 0;
            for (int p = 0; p < 4; p++) {
                int v = source[row(d, line, p)][col(d, line, p)];
                if (v != 0) packed[n++] = v;
            }
            int write = 0;
            for (int p = 0; p < n; p++) {
                int v = packed[p];
                if (p + 1 < n && packed[p + 1] == v && v < 30) {
                    v++;
                    p++;
                }
                out[row(d, line, write)][col(d, line, write)] = v;
                write++;
            }
        }
        return out;
    }

    private int bestDirection() {
        double bestUtility = -Double.MAX_VALUE;
        int choice = -1;
        for (int d = 0; d < 4; d++) {
            int[][] candidate = simulated(board, d);
            if (Arrays.deepEquals(candidate, board)) continue;
            double utility = evaluate(candidate), future = 0;
            int empty = 0;
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    if (candidate[r][c] != 0) continue;
                    candidate[r][c] = 1;
                    double reply = -Double.MAX_VALUE;
                    for (int next = 0; next < 4; next++)
                        reply = Math.max(reply, evaluate(simulated(candidate, next)));
                    future += reply;
                    empty++;
                    candidate[r][c] = 0;
                }
            }
            if (empty > 0) utility += 0.65 * future / empty;
            if (utility > bestUtility) {
                bestUtility = utility;
                choice = d;
            }
        }
        return choice;
    }

    private static double evaluate(int[][] cells) {
        double utility = 0;
        int max = maximum(cells);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int v = cells[r][c];
                if (v == 0) {
                    utility += 100;
                    continue;
                }
                int snake = r * 4 + (r % 2 == 0 ? c : 3 - c);
                utility += v * v * (16 - snake) * 0.5;
                if (r < 3 && cells[r + 1][c] != 0)
                    utility -= Math.abs(v - cells[r + 1][c]) * 6;
                if (c < 3 && cells[r][c + 1] != 0)
                    utility -= Math.abs(v - cells[r][c + 1]) * 6;
            }
        }
        if (cells[0][0] == max) utility += max * 35;
        if (!hasMove(cells)) utility -= 10000;
        return utility;
    }

    private void burst(double x, double y, Color color, int count) {
        for (int i = 0; i < count && sparks.size() < 200; i++) {
            double angle = effects.nextDouble() * Math.PI * 2;
            double speed = 45 + effects.nextDouble() * 135;
            Spark p = new Spark();
            p.x = x;
            p.y = y;
            p.vx = Math.cos(angle) * speed;
            p.vy = Math.sin(angle) * speed - 50;
            p.max = p.life = 0.3 + effects.nextDouble() * 0.4;
            p.size = 3 + effects.nextInt(4);
            p.color = color;
            sparks.add(p);
        }
    }

    private static int cellX(int c) { return BX + GAP + c * STEP; }
    private static int cellY(int r) { return BY + GAP + r * STEP; }
    private static Color color(int tier) { return ORE[Math.max(0, Math.min(10, tier - 1))]; }
    private static String oreName(int tier) {
        return tier > 11 ? "STAR +" + (tier - 11) : NAMES[Math.max(0, tier - 1)];
    }

    private void makeArt() {
        if (background != null) return;
        background = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x263F43), W, H, BG));
        g.fillRect(0, 0, W, H);
        Random art = new Random(813);
        for (int y = 0; y < H; y += 32) {
            for (int x = -32; x < W; x += 64) {
                int xx = x + ((y / 32) % 2) * 32;
                g.setColor(new Color(0, 0, 0, 16));
                g.drawRect(xx, y, 62, 30);
            }
        }
        for (int i = 0; i < 600; i++) {
            g.setColor(new Color(170, 210, 200, 8 + art.nextInt(7)));
            g.fillRect(art.nextInt(W / 4) * 4, art.nextInt(H / 4) * 4,
                    4 + art.nextInt(5) * 4, 4);
        }

        g.setColor(new Color(0x061017));
        g.fillRoundRect(BX - 5, BY + 1, BOARD + 10, BOARD + 7, 14, 14);
        g.setColor(new Color(0x71898B));
        g.fillRoundRect(BX - 3, BY - 3, BOARD + 6, BOARD + 6, 12, 12);
        g.setColor(new Color(0x40565A));
        g.fillRect(BX, BY, BOARD, BOARD);
        for (int i = 0; i < 950; i++) {
            g.setColor(art.nextBoolean() ? new Color(0x4A6265) : new Color(0x34494E));
            g.fillRect(BX + art.nextInt(BOARD / 4) * 4,
                    BY + art.nextInt(BOARD / 4) * 4, 4, 4);
        }
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int x = cellX(c), y = cellY(r);
                g.setColor(new Color(0x172A32));
                g.fillRect(x, y, CELL, CELL);
                g.setColor(new Color(0x0B1D25));
                g.fillRect(x, y, CELL, 4);
                g.fillRect(x, y, 4, CELL);
                g.setColor(new Color(0x587075));
                g.fillRect(x, y + CELL - 2, CELL, 2);
                g.setColor(new Color(0x21363C));
                for (int i = 0; i < 12; i++)
                    g.fillRect(x + 8 + art.nextInt(23) * 4,
                            y + 8 + art.nextInt(23) * 4, 4, 4);
                g.setColor(new Color(0x324950));
                g.fillRect(x + 52, y + 52, 4, 4);
            }
        }
        g.setColor(EDGE);
        g.fillRect(24, 70, 976, 1);
        g.dispose();
        for (int i = 0; i < blocks.length; i++) blocks[i] = makeBlock(i);
    }

    private BufferedImage makeBlock(int tier) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        Random art = new Random(470 + tier * 29L);
        Color stone = switch (tier) {
            case 0 -> new Color(0x8997A0);
            case 1 -> new Color(0x786F67);
            case 3 -> new Color(0x817861);
            case 4 -> new Color(0x6B4D60);
            case 5 -> new Color(0x4F6482);
            case 6 -> new Color(0x497769);
            case 7 -> new Color(0x51808A);
            case 8 -> new Color(0x463C53);
            case 9, 10 -> new Color(0x514568);
            default -> new Color(0x7D8586);
        };
        g.setColor(new Color(0x071019));
        g.fillRect(0, 1, 32, 31);
        g.setColor(stone);
        g.fillRect(1, 1, 30, 29);
        for (int i = 0; i < 20; i++) {
            g.setColor(i % 2 == 0 ? stone.brighter() : stone.darker());
            g.fillRect(2 + art.nextInt(14) * 2, 3 + art.nextInt(10) * 2,
                    2 + art.nextInt(4), 2);
        }

        Color ore = tier == 0 ? new Color(0x263242) : ORE[tier];
        if (tier <= 2) {
            int[][] veins = {{4, 5}, {21, 4}, {12, 10}, {5, 18}, {24, 17}, {16, 21}};
            for (int[] v : veins) {
                int x = v[0], y = v[1];
                g.setColor(ore.darker().darker());
                g.fillRect(x - 1, y, 7, 6);
                g.setColor(ore);
                g.fillRect(x, y - 1, tier == 2 ? 6 : 4, 4);
                g.fillRect(x + 1, y + 2, 4, 2);
                g.setColor(tier == 0 ? new Color(0x52657B) : ore.brighter());
                g.fillRect(x, y - 1, 4, 1);
                if (tier == 1) {
                    g.setColor(new Color(0x6FC7A4));
                    g.fillRect(x + 3, y + 3, 3, 2);
                }
            }
        } else if (tier == 3) {
            for (int[] p : new int[][]{{5, 5}, {20, 5}, {12, 15}}) {
                g.setColor(new Color(0x986621));
                g.fillRect(p[0] - 1, p[1] + 2, 9, 7);
                g.setColor(ore);
                g.fillRect(p[0], p[1], 7, 7);
                g.setColor(new Color(0xFFF3AB));
                g.fillRect(p[0], p[1], 6, 2);
                g.fillRect(p[0], p[1] + 2, 2, 3);
            }
        } else if (tier == 4) {
            g.setColor(new Color(0xAE294E));
            g.fillRect(5, 5, 4, 17);
            g.fillRect(8, 12, 16, 4);
            g.fillRect(20, 5, 4, 18);
            for (int[] p : new int[][]{{4, 4}, {19, 4}, {11, 11}, {4, 20}, {20, 19}}) {
                g.setColor(ore);
                g.fillRect(p[0], p[1], 6, 5);
                g.setColor(new Color(0xFFBCC4));
                g.fillRect(p[0] + 1, p[1], 3, 2);
            }
        } else if (tier <= 7) {
            for (int[] p : new int[][]{{5, 5}, {20, 4}, {12, 15}}) {
                int x = p[0], y = p[1];
                g.setColor(ore.darker().darker());
                g.fillRect(x - 1, y + 1, 10, 10);
                g.setColor(ore);
                if (tier == 5) {
                    g.fillRect(x, y + 2, 8, 6);
                    g.fillRect(x + 2, y, 5, 10);
                } else if (tier == 6) {
                    g.fillRect(x + 2, y, 5, 11);
                    g.fillRect(x, y + 3, 9, 5);
                } else {
                    g.fillPolygon(new int[]{x + 4, x + 9, x + 4, x - 1},
                            new int[]{y, y + 4, y + 11, y + 4}, 4);
                }
                g.setColor(tier == 7 ? Color.WHITE : ore.brighter());
                g.fillRect(x + 2, y + 1, 4, 2);
                g.fillRect(x + 1, y + 3, 2, 3);
                g.setColor(ore.darker());
                g.fillRect(x + 5, y + 5, 2, 4);
            }
        } else if (tier == 8) {
            g.setColor(new Color(0x251C2E));
            g.fillRect(4, 10, 24, 12);
            g.setColor(new Color(0xA68FAF));
            g.fillRect(7, 7, 18, 5);
            g.fillRect(4, 12, 24, 6);
            g.setColor(new Color(0xDBC5DC));
            g.fillRect(8, 7, 15, 2);
            g.fillRect(5, 12, 3, 4);
            g.setColor(new Color(0x6E587B));
            g.fillRect(7, 18, 18, 4);
            g.setColor(new Color(0xD5AD89));
            g.fillRect(10, 12, 2, 5);
            g.fillRect(21, 12, 2, 5);
        } else {
            if (tier == 10) {
                g.setColor(new Color(0xB990EF));
                g.drawRect(3, 3, 25, 23);
                g.fillRect(4, 4, 3, 3);
                g.fillRect(25, 4, 3, 3);
                g.fillRect(4, 21, 3, 3);
                g.fillRect(25, 21, 3, 3);
            }
            g.setColor(new Color(0xBD9359));
            g.fillRect(13, 2, 6, 24);
            g.fillRect(4, 11, 24, 6);
            g.setColor(new Color(0xFFD978));
            g.fillRect(14, 2, 4, 24);
            g.fillRect(4, 12, 24, 4);
            g.fillRect(9, 7, 14, 14);
            g.setColor(new Color(0xFFF3C4));
            g.fillRect(11, 8, 10, 12);
            g.fillRect(9, 11, 14, 6);
            g.setColor(Color.WHITE);
            g.fillRect(14, 5, 4, 18);
            g.fillRect(7, 12, 18, 3);
            g.fillRect(12, 10, 8, 8);
        }

        g.setColor(new Color(255, 255, 255, 95));
        g.fillRect(1, 1, 30, 1);
        g.fillRect(1, 2, 1, 27);
        g.setColor(new Color(0, 0, 0, 100));
        g.fillRect(29, 2, 2, 28);
        g.fillRect(1, 29, 30, 2);
        g.dispose();
        return image;
    }

    @Override
    public void draw(Graphics2D graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        int width = host == null ? cols() * 128 : Math.max(1, host.width());
        int height = host == null ? rows() * 128 : Math.max(1, host.height());
        g.setColor(BG);
        g.fillRect(0, 0, width, height);
        if (background == null || host == null) {
            g.dispose();
            return;
        }
        g.scale(width / (double) W, height / (double) H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(background, 0, 0, null);

        for (int i = 0; i < 12; i++) {
            int x = 518 + i * 43 % 478;
            int y = (int) (78 + (i * 41 + clock * (4 + i % 3)) % 466);
            g.setColor(alpha(MINT, 22));
            g.fillRect(x, y, 3, 3);
        }
        drawHeader(g);
        drawBoard(g);
        drawSidebar(g);

        for (Spark p : sparks) {
            g.setColor(alpha(p.color, (int) (230 * p.life / p.max)));
            g.fillRect((int) p.x, (int) p.y, p.size, p.size);
        }
        for (Reward p : rewards) {
            double y = p.y - (0.45 - p.life) * 24;
            int a = (int) (255 * Math.min(1, p.life / 0.15));
            g.setFont(FONTS[22]);
            int tw = g.getFontMetrics().stringWidth(p.text);
            g.setColor(alpha(BG, a * 9 / 10));
            g.fillRoundRect((int) p.x - tw / 2 - 8, (int) y - 21, tw + 16, 29, 7, 7);
            text(g, p.text, 22, p.x, y + 1, alpha(p.color, a), true);
        }

        String footer = player == null ? "LIVE DEMO / AI MINER"
                : invalidTime > 0 ? "BLOCKED THAT WAY — TRY ANOTHER DIRECTION"
                : moves + " MOVES / NO TIMER. PLAN YOUR NEXT SLIDE.";
        text(g, footer, 14, BX + BOARD / 2.0, 571,
                invalidTime > 0 ? GOLD : MUTED, true);

        if (over) drawGameOver(g);
        g.dispose();
    }

    private void drawHeader(Graphics2D g) {
        text(g, "ORE", 43, 24, 44, WHITE, false);
        text(g, "2048", 43, 127, 44, GOLD, false);
        text(g, player == null ? "DIG. SLIDE. DISCOVER."
                : "MINER / " + clipped(player.name(), 23), 16, 26, 64, MUTED, false);
        scoreBox(g, 536, 8, 218, player == null ? "DEMO SCORE" : "SCORE", score, WHITE);
        scoreBox(g, 766, 8, 234, "BEST", best, GOLD);
    }

    private void scoreBox(Graphics2D g, int x, int y, int width, String label,
                          int number, Color color) {
        panel(g, x, y, width, 54, EDGE);
        text(g, label, 13, x + 12, y + 18, MUTED, false);
        rightText(g, Integer.toString(number), number > 9999999 ? 23 : 30,
                x + width - 12, y + 43, color);
        if (x == 536 && scoreTime > 0 && shownGain > 0)
            text(g, "+" + shownGain, 16, x + 12, y + 43,
                    alpha(MINT, (int) (255 * Math.min(1, scoreTime / 0.3))), false);
    }

    private void drawBoard(Graphics2D g) {
        Graphics2D tiles = (Graphics2D) g.create();
        if (impact > 0)
            tiles.translate(0, Math.sin(impact * 90) * impact * 7);

        if (moving && age < SLIDE) {
            double t = Math.min(1, age / SLIDE);
            double ease = 1 - Math.pow(1 - t, 3);
            for (Motion m : motions) {
                double x = cellX(m.sc) + (m.dc - m.sc) * STEP * ease;
                double y = cellY(m.sr) + (m.dr - m.sr) * STEP * ease;
                drawTile(tiles, m.tier, x, y, 1, 0);
            }
        } else {
            double t = moving ? Math.max(0, Math.min(1, (age - SLIDE) / (TURN - SLIDE))) : 1;
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    int tier = board[r][c];
                    if (tier == 0) continue;
                    double scale = 1, flash = 0;
                    if (moving && merges[r][c]) {
                        scale += 0.085 * Math.sin(t * Math.PI);
                        flash = 1 - t;
                    }
                    if (moving && r == spawnR && c == spawnC)
                        scale = 0.48 + 0.52 * (1 - Math.pow(1 - t, 3));
                    drawTile(tiles, tier, cellX(c), cellY(r), scale, flash);
                }
            }
        }
        tiles.dispose();

        if (moving && age >= SLIDE) {
            double t = Math.min(1, (age - SLIDE) / (TURN - SLIDE));
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    if (!merges[r][c]) continue;
                    int expand = (int) (t * 7);
                    g.setColor(alpha(color(board[r][c]), (int) (200 * (1 - t))));
                    g.setStroke(new BasicStroke(2));
                    g.drawRect(cellX(c) - expand, cellY(r) - expand,
                            CELL + expand * 2, CELL + expand * 2);
                }
            }
        }

        if (invalidTime > 0 && lastDirection >= 0) {
            g.setColor(alpha(GOLD, (int) (180 * invalidTime / 0.24)));
            int d = lastDirection;
            if (d < 2)
                g.fillRect(d == 0 ? BX - 2 : BX + BOARD - 3, BY, 5, BOARD);
            else
                g.fillRect(BX, d == 2 ? BY - 2 : BY + BOARD - 3, BOARD, 5);
        }
    }

    private void drawTile(Graphics2D g, int tier, double x, double y, double scale,
                          double flash) {
        int size = Math.max(1, (int) Math.round(CELL * scale));
        int px = (int) Math.round(x + (CELL - size) / 2);
        int py = (int) Math.round(y + (CELL - size) / 2);
        g.drawImage(blocks[Math.min(10, tier - 1)], px, py, size, size, null);
        if (flash > 0) {
            g.setColor(alpha(WHITE, (int) (flash * 100)));
            g.fillRect(px + 2, py + 2, size - 4, size - 4);
        }
        int strip = Math.max(1, (int) (36 * scale));
        g.setColor(new Color(7, 16, 25, 242));
        g.fillRect(px + 3, py + size - strip - 3, Math.max(1, size - 6), strip);
        g.setColor(color(tier));
        g.fillRect(px + 3, py + size - strip - 3, Math.max(1, size - 6), 2);
        String number = Integer.toString(value(tier));
        int font = number.length() <= 4 ? 35 : number.length() <= 6 ? 25 : 15;
        text(g, number, Math.max(8, (int) (font * scale)),
                px + size / 2.0, py + size - 8 * scale,
                tier >= 10 ? GOLD : WHITE, true);
        if (tier >= 11) {
            g.setStroke(new BasicStroke(2));
            g.setColor(GOLD);
            g.drawRect(px + 1, py + 1, size - 3, size - 3);
        }
    }

    private void drawSidebar(Graphics2D g) {
        panel(g, 520, 80, 480, 113,
                discoveryFlash > 0 ? alpha(GOLD, 150 + (int) (80 * Math.min(1, discoveryFlash))) : EDGE);
        int tier = Math.max(1, discovered);
        g.drawImage(blocks[Math.min(10, tier - 1)], 536,
                98 + (player == null ? (int) (Math.sin(clock * 2) * 2) : 0), 78, 78, null);
        text(g, "DEEPEST DISCOVERY", 15, 630, 103, MINT, false);
        fitText(g, oreName(tier), 28, 18, 630, 137, 354, WHITE);
        text(g, Integer.toString(value(tier)), 23, 630, 166, color(tier), false);
        rightText(g, won ? "2048 FORGED!" : "GOAL: 2048", 17, 984, 166, GOLD);
        g.setColor(new Color(0x0A1B23));
        g.fillRect(630, 178, 354, 5);
        g.setColor(color(tier));
        g.fillRect(630, 178, (int) (354 * Math.min(1, tier / 11.0)), 5);

        if (bannerTime > 0) {
            fitText(g, banner, 18, 14, 522, 216, 476, GOLD);
        } else {
            text(g, "SAME NUMBER + SAME NUMBER = NEXT ORE", 16, 522, 216, MUTED, false);
        }

        boolean showScores = player == null && !leaders.isEmpty() && clock % 16 >= 10;
        if (showScores) {
            panel(g, 520, 230, 480, 228, EDGE);
            text(g, "HALL OF MINERS", 25, 538, 263, GOLD, false);
            for (int i = 0; i < Math.min(4, leaders.size()); i++) {
                Host.Score s = leaders.get(i);
                int y = 303 + i * 39;
                text(g, Integer.toString(i + 1), 22, 539, y, MINT, false);
                fitText(g, clipped(s.name(), 18), 22, 17, 570, y, 274, WHITE);
                rightText(g, Integer.toString(s.score()), 21, 982, y, GOLD);
            }
        } else {
            for (int i = 0; i < 10; i++) {
                int x = 520 + (i / 5) * 244;
                int y = 231 + (i % 5) * 36;
                boolean found = i < discovered;
                g.setColor(found ? new Color(0x25413F) : PANEL);
                g.fillRoundRect(x, y, 236, 33, 5, 5);
                g.drawImage(blocks[i], x + 3, y + 2, 29, 29, null);
                text(g, NAMES[i], 16, x + 41, y + 22, found ? WHITE : MUTED, false);
                rightText(g, Integer.toString(value(i + 1)), 17, x + 224, y + 22, ORE[i]);
                if (found) {
                    g.setColor(ORE[i]);
                    g.fillRect(x, y + 4, 2, 25);
                }
            }
            panel(g, 520, 413, 480, 45, discovered >= 11 ? GOLD : EDGE);
            g.drawImage(blocks[10], 528, 417, 36, 36, null);
            text(g, "AWAKENED STAR", 19, 578, 441, WHITE, false);
            rightText(g, "2048", 25, 984, 443, GOLD);
        }

        panel(g, 520, 469, 480, 83, player == null ? GOLD : EDGE);
        text(g, player == null ? "TAKE A SEAT TO PLAY" : "WASD / SLIDE THE MINE",
                19, 536, 491, player == null ? GOLD : WHITE, false);
        for (int i = 0; i < 4; i++) {
            int x = 536 + i * 54;
            int d = BUTTON_DIR[i];
            boolean active = lastDirection == d && (moving || invalidTime > 0);
            boolean hover = player != null && buttonAt(mx, my) == d;
            g.setColor(active ? new Color(0x477362)
                    : hover ? new Color(0x3B5660) : new Color(0x253D47));
            g.fillRoundRect(x, 496, 46, 49, 6, 6);
            g.setColor(active ? MINT : hover ? GOLD : EDGE);
            g.setStroke(new BasicStroke(1));
            g.drawRoundRect(x, 496, 46, 49, 6, 6);
            arrow(g, x + 23, 509, d, WHITE);
            text(g, BUTTON_LABEL[i], 18, x + 23, 537, WHITE, true);
        }
        text(g, "Tap or hold.", 18, 765, 515, WHITE, false);
        text(g, "Or click an arrow.", 16, 765, 538, MUTED, false);

        text(g, player == null ? "YOUR EXPEDITION STARTS WHEN YOU SIT"
                : "2 + 2 = 4   /   4 + 4 = 8   /   KEEP COMBINING",
                14, 760, 571, MUTED, true);
    }

    private void drawGameOver(Graphics2D g) {
        g.setColor(new Color(5, 13, 20, 205));
        g.fillRect(BX, BY, BOARD, BOARD);
        int cx = BX + BOARD / 2;
        int y = BY + 103;
        panel(g, BX + 16, y, BOARD - 32, 251, GOLD);
        text(g, "MINE EXHAUSTED", 34, cx, y + 44, WHITE, true);
        text(g, "SCORE " + score, score > 99999999 ? 25 : 32,
                cx, y + 91, GOLD, true);
        fitCentered(g, moves + " moves / " + oreName(discovered),
                19, cx, y + 123, BOARD - 56, MUTED);
        text(g, rank > 0 ? "HALL OF MINERS / #" + rank : "No empty cells. No matching neighbours.",
                rank > 0 ? 20 : 16, cx, y + 156, MINT, true);
        if (overAge > 0.75) {
            text(g, player == null ? "OPENING A NEW MINE..." : "CLICK TO MINE AGAIN",
                    23, cx, y + 204, WHITE, true);
            if (player != null)
                text(g, "or press SPACE", 17, cx, y + 231, MUTED, true);
        }
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, Color edge) {
        g.setColor(PANEL);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setStroke(new BasicStroke(1));
        g.setColor(edge);
        g.drawRoundRect(x, y, w, h, 10, 10);
    }

    private static void arrow(Graphics2D g, int x, int y, int d, Color color) {
        int dx = d == 0 ? -1 : d == 1 ? 1 : 0;
        int dy = d == 2 ? -1 : d == 3 ? 1 : 0;
        g.setColor(color);
        g.fillPolygon(new int[]{x + dx * 7, x - dx * 4 - dy * 5, x - dx * 4 + dy * 5},
                new int[]{y + dy * 7, y - dy * 4 + dx * 5, y - dy * 4 - dx * 5}, 3);
    }

    private static Color alpha(Color color, int a) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, a)));
    }

    private static String clipped(String s, int limit) {
        if (s == null) return "MINER";
        return s.length() <= limit ? s : s.substring(0, limit - 1) + "…";
    }

    private static void text(Graphics2D g, String s, int size, double x, double y,
                             Color color, boolean centered) {
        g.setFont(FONTS[Math.max(1, Math.min(80, size))]);
        double left = centered ? x - g.getFontMetrics().stringWidth(s) / 2.0 : x;
        g.setColor(new Color(0, 0, 0, color.getAlpha() / 2));
        g.drawString(s, (float) left, (float) (y + 1.5));
        g.setColor(color);
        g.drawString(s, (float) left, (float) y);
    }

    private static void rightText(Graphics2D g, String s, int size,
                                  double x, double y, Color color) {
        g.setFont(FONTS[Math.max(1, Math.min(80, size))]);
        text(g, s, size, x - g.getFontMetrics().stringWidth(s), y, color, false);
    }

    private static void fitText(Graphics2D g, String s, int size, int min,
                                double x, double y, int width, Color color) {
        while (size > min) {
            g.setFont(FONTS[size]);
            if (g.getFontMetrics().stringWidth(s) <= width) break;
            size--;
        }
        g.setFont(FONTS[size]);
        while (s.length() > 1 && g.getFontMetrics().stringWidth(s) > width)
            s = s.substring(0, s.length() - 2) + "…";
        text(g, s, size, x, y, color, false);
    }

    private static void fitCentered(Graphics2D g, String s, int size,
                                    double x, double y, int width, Color color) {
        while (size > 12) {
            g.setFont(FONTS[size]);
            if (g.getFontMetrics().stringWidth(s) <= width) break;
            size--;
        }
        text(g, s, size, x, y, color, true);
    }

    private record Motion(int sr, int sc, int dr, int dc, int tier) {}

    private static final class Spark {
        double x, y, vx, vy, life, max;
        int size;
        Color color;
    }

    private static final class Reward {
        final double x, y;
        final String text;
        final Color color;
        double life = 0.45;

        Reward(double x, double y, String text, Color color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
        }
    }
}

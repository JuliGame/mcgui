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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A shared, unscored note-block sequencer.
 *
 * Mouse: toggle notes. Right click: erase.
 * Instrument name: audition. Pitch arrows: lower / raise.
 * Wheel: tempo. Space: shared play / pause.
 * WASD: navigate, with key repeat. F: toggle selected note.
 *
 * Presets change on bar boundaries. Destructive replacements require
 * confirmation from the same player. Leaving the last seat restores a demo.
 */
public final class NoteBlockStudio implements Scene {
    private static final int W = 1280, H = 720;
    private static final int ROWS = 10, STEPS = 16;
    private static final int GX = 304, GY = 190;
    private static final int DX = 58, DY = 38, CW = 52, CH = 32;
    private static final int RIGHT = GX + DX * STEPS;
    private static final int BOTTOM = GY + ROWS * DY;

    private static final Color BG = new Color(0x101827);
    private static final Color PANEL = new Color(0x1B2A40);
    private static final Color DARK = new Color(0x0A1220);
    private static final Color WHITE = new Color(0xF2F8FF);
    private static final Color SOFT = new Color(0xB1C4DB);
    private static final Color DIM = new Color(0x536984);
    private static final Color MINT = new Color(0x77FFD1);
    private static final Color GOLD = new Color(0xFFD283);
    private static final Color RED = new Color(0xFF8B9D);

    private static final Color[] COLORS = {
            new Color(0xF7C477), new Color(0xFFAA78),
            new Color(0xFF7E8F), new Color(0xFF99CF),
            new Color(0xBCA0FF), new Color(0xFFE58C),
            new Color(0x88EBAC), new Color(0x7AE5EF),
            new Color(0x89B7FF), new Color(0xDDA1FF)
    };
    private static final Color[] INKS = {
            new Color(0x68E6FF), new Color(0xFF85BD),
            new Color(0xCAEF80), new Color(0xFFCC7B)
    };

    private static final String[] NAMES = {
            "HARP", "BASS", "BASS DRUM", "SNARE", "HAT",
            "BELL", "FLUTE", "CHIME", "XYLOPHONE", "PLING"
    };
    private static final String[] SOUNDS = {
            "block.note_block.harp", "block.note_block.bass",
            "block.note_block.basedrum", "block.note_block.snare",
            "block.note_block.hat", "block.note_block.bell",
            "block.note_block.flute", "block.note_block.chime",
            "block.note_block.xylophone", "block.note_block.pling"
    };

    // Pentatonic semitone positions in the complete 0.5 .. 2.0 pitch range.
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};
    private static final String[] PITCH_NAMES = {
            "F#1", "G#1", "A#1", "C#2", "D#2", "F#2",
            "G#2", "A#2", "C#3", "D#3", "F#3"
    };
    private static final int[] BASE_PITCH = {5, 0, 5, 5, 5, 7, 6, 8, 9, 10};
    private static final float[] PITCHES = new float[SCALE.length];

    private static final String[] PRESETS = {
            "OVERWORLD", "REDSTONE", "MOONRISE", "SKY ISLAND"
    };
    private static final int[] BPMS = {112, 140, 88, 124};
    private static final int[][] PATTERNS = {
            {0x0041, 0x1111, 0x0101, 0x1010, 0x5555,
                    0x0404, 0x0080, 0x4000, 0x0800, 0x8000},
            {0x0909, 0x4545, 0x4141, 0x1010, 0xAAAA,
                    0x0202, 0x2020, 0x0080, 0x8400, 0x8000},
            {0x0101, 0x0101, 0x0001, 0x1000, 0x4444,
                    0x0040, 0x0808, 0x4004, 0x0200, 0x8000},
            {0x0109, 0x1111, 0x0501, 0x1010, 0x5555,
                    0x2020, 0x0044, 0x0800, 0x8080, 0x4000}
    };

    private static final Font TITLE = font(42);
    private static final Font LARGE = new Font(Font.MONOSPACED, Font.BOLD, 34);
    private static final Font HEADING = font(24);
    private static final Font LABEL = font(21);
    private static final Font SMALL = font(18);
    private static final Font TINY = font(16);
    private static final Font PITCH_FONT =
            new Font(Font.MONOSPACED, Font.BOLD, 18);
    private static final BasicStroke LINE = new BasicStroke(1);
    private static final BasicStroke RING = new BasicStroke(2);
    private static final BasicStroke HEAVY = new BasicStroke(5);

    static {
        for (int i = 0; i < SCALE.length; i++) {
            PITCHES[i] = (float) Math.pow(2, (SCALE[i] - 12) / 12.0);
        }
    }

    private Host host;
    private final boolean[][] notes = new boolean[ROWS][STEPS];
    private final int[][] owner = new int[ROWS][STEPS];
    private final double[][] flashes = new double[ROWS][STEPS];
    private final double[] meters = new double[ROWS];
    private final int[] pitch = BASE_PITCH.clone();
    private final Map<UUID, Cursor> players = new LinkedHashMap<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Random random = new Random(0x4E425354L);

    private final BufferedImage[] blocks = new BufferedImage[ROWS];
    private BufferedImage background;

    private boolean playing = true;
    private boolean muted;
    private boolean edited;
    private int tempo = 112;
    private int preset;
    private int pending = -1;
    private int step = -1;
    private int count;
    private long bar;
    private double remaining;
    private double clock;
    private double beatPulse;
    private double transportLock;
    private double auditionLock;
    private double messageTime;
    private String message = "Sit down and join the groove.";

    private UUID confirmOwner;
    private int confirmAction = -1;
    private double confirmTime;

    public NoteBlockStudio() {
        loadPreset(0);
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
        players.clear();
        clock = 0;
        transportLock = 0;
        auditionLock = 0;
        loadPreset(0);
        rewind();
        playing = true;
        muted = false;
        ensureArt();
        if (host != null) {
            for (Seat seat : host.seats()) addPlayer(seat);
        }
        say(players.isEmpty() ? "Sit down and join the groove."
                : "You're live! Click an empty cell to add a note.");
    }

    @Override
    public void joined(Seat seat) {
        if (host == null) return;
        boolean first = players.isEmpty();
        if (!addPlayer(seat)) return;
        if (first) {
            playing = true;
            muted = false;
            pending = -1;
            cancelConfirmation();
            transportLock = 0;
        }
        say(shortName(seat.name(), 20) + " joined. Everyone edits the same loop.");
    }

    private boolean addPlayer(Seat seat) {
        if (seat == null || seat.id() == null || players.size() >= 4
                || players.containsKey(seat.id())) return false;
        boolean[] used = new boolean[4];
        for (Cursor c : players.values()) used[c.color] = true;
        int color = 0;
        while (color < 4 && used[color]) color++;
        if (color == 4) return false;

        Cursor c = new Cursor(seat, color);
        c.row = color * 2;
        c.col = color * 4;
        c.x = c.tx = cellX(c.col);
        c.y = c.ty = cellY(c.row);
        players.put(seat.id(), c);
        return true;
    }

    @Override
    public void left(Seat seat) {
        if (seat == null || seat.id() == null) return;
        if (players.remove(seat.id()) == null) return;
        if (seat.id().equals(confirmOwner)) cancelConfirmation();
        if (players.isEmpty()) {
            loadPreset(0);
            rewind();
            playing = true;
            muted = false;
            transportLock = 0;
            say("Sit down and join the groove.");
        } else {
            say(shortName(seat.name(), 20) + " left the studio.");
        }
    }

    private Cursor cursor(Seat seat) {
        return host == null || seat == null || seat.id() == null
                ? null : players.get(seat.id());
    }

    private double logicalX(int x) {
        return x * (double) W / Math.max(1, host.width());
    }

    private double logicalY(int y) {
        return y * (double) H / Math.max(1, host.height());
    }

    @Override
    public void pointer(Seat seat, int x, int y) {
        Cursor c = cursor(seat);
        if (c != null) locate(c, logicalX(x), logicalY(y));
    }

    private void locate(Cursor c, double x, double y) {
        c.tx = clamp(x, 5, W - 5);
        c.ty = clamp(y, 5, H - 5);
        int r = rowAt(y), s = colAt(x);
        if (r >= 0 && s >= 0) {
            c.row = r;
            c.col = s;
        }
    }

    // Gutters belong to the adjacent cell, rather than swallowing clicks.
    private static int rowAt(double y) {
        return y >= GY && y < BOTTOM ? (int) ((y - GY) / DY) : -1;
    }

    private static int colAt(double x) {
        return x >= GX && x < RIGHT ? (int) ((x - GX) / DX) : -1;
    }

    private static double cellX(int col) {
        return GX + col * DX + CW / 2.0;
    }

    private static double cellY(int row) {
        return GY + row * DY + CH / 2.0;
    }

    @Override
    public void click(Seat seat, int x, int y, boolean right) {
        Cursor c = cursor(seat);
        if (c == null) return;
        double px = logicalX(x), py = logicalY(y);
        locate(c, px, py);
        if (px < 0 || py < 0 || px >= W || py >= H) return;
        c.pulse = 1;
        int r = rowAt(py), s = colAt(px);

        if (r >= 0 && s >= 0) {
            editCell(c, r, s, right);
            return;
        }
        if (r >= 0 && px >= 36 && px < 294) {
            if (px >= 212) {
                changePitch(r, right || px < 238 ? -1 : 1);
            } else {
                audition(r);
                say(NAMES[r] + " audition  /  use the pitch arrows to tune this row.");
            }
            return;
        }
        if (right) return;

        if (py >= 106 && py < 146 && px >= GX && px < RIGHT) {
            requestPreset(c, (int) ((px - GX) / 232));
            return;
        }
        if (py >= 20 && py < 90) {
            if (inside(px, 1030, 44)) changeTempo(-2);
            else if (inside(px, 1194, 44)) changeTempo(2);
            return;
        }
        if (py >= 588 && py < 632) {
            if (inside(px, 32, 150)) togglePlayback();
            else if (inside(px, 194, 142)) {
                if (transportLock <= 0) {
                    rewind();
                    transportLock = 0.4;
                    say("Back to beat one.");
                }
            } else if (inside(px, 348, 116)) {
                if (confirmed(c, 4)) clear();
            } else if (inside(px, 476, 126) && transportLock <= 0) {
                muted = !muted;
                transportLock = 0.4;
                say(muted ? "Sound off. The visual loop keeps running."
                        : "Sound on. You're live.");
            }
        }
    }

    private void editCell(Cursor c, int r, int s, boolean erase) {
        boolean value = !erase && !notes[r][s];
        if (value == notes[r][s]) return;
        notes[r][s] = value;
        owner[r][s] = value ? c.color : -1;
        count += value ? 1 : -1;
        flashes[r][s] = value ? 0.7 : 0;
        c.pulse = 1;
        markEdited();
        burst(cellX(s), cellY(r), INKS[c.color], value ? 8 : 4);
        if (!playing && value) audition(r);
    }

    private void changePitch(int r, int direction) {
        int next = (int) clamp(pitch[r] + direction, 0, SCALE.length - 1);
        if (next != pitch[r]) {
            pitch[r] = next;
            markEdited();
        }
        audition(r);
        say(NAMES[r] + "  " + PITCH_NAMES[pitch[r]]
                + "  /  pentatonic tuning, low to high");
    }

    private void audition(int r) {
        meters[r] = 1;
        if (auditionLock <= 0) {
            playNote(r, 0.55f);
            auditionLock = 0.07;
        }
    }

    private void markEdited() {
        edited = true;
        if (pending >= 0) {
            pending = -1;
            say("Preset queue cancelled to keep your new edit.");
        }
        cancelConfirmation();
    }

    private void clear() {
        for (int r = 0; r < ROWS; r++) {
            Arrays.fill(notes[r], false);
            Arrays.fill(owner[r], -1);
            Arrays.fill(flashes[r], 0);
        }
        Arrays.fill(meters, 0);
        particles.clear();
        count = 0;
        markEdited();
        say("Blank canvas. Try a kick on 1 and 9, a snare on 5 and 13.");
    }

    @Override
    public void scroll(Seat seat, int notches) {
        if (cursor(seat) == null || notches == 0) return;
        changeTempo(-(int) clamp(notches, -25, 25) * 2);
    }

    private void changeTempo(int delta) {
        double old = duration();
        tempo = (int) clamp(tempo + delta, 50, 200);
        remaining *= duration() / old;
        say(tempo + " BPM  /  four steps per beat, four beats per loop");
    }

    @Override
    public void key(Seat seat, Key key, boolean down) {
        Cursor c = cursor(seat);
        if (c == null || key == null) return;
        if (!down) {
            c.held.remove(key);
            return;
        }
        if (!c.held.add(key)) return;
        switch (key) {
            case JUMP -> togglePlayback();
            case SWAP_HANDS -> editCell(c, c.row, c.col, false);
            case UP, DOWN, LEFT, RIGHT -> {
                navigate(c, key);
                c.repeat = 0.30;
            }
            case SPRINT -> {
                // Sneaking is reserved by the host; no hidden destructive keys.
            }
        }
    }

    private void navigate(Cursor c, Key key) {
        switch (key) {
            case UP -> c.row = Math.floorMod(c.row - 1, ROWS);
            case DOWN -> c.row = (c.row + 1) % ROWS;
            case LEFT -> c.col = Math.floorMod(c.col - 1, STEPS);
            case RIGHT -> c.col = (c.col + 1) % STEPS;
            default -> { return; }
        }
        c.tx = cellX(c.col);
        c.ty = cellY(c.row);
        c.pulse = 0.45;
    }

    private void togglePlayback() {
        if (transportLock > 0) return;
        transportLock = 0.4;
        playing = !playing;
        if (!playing && pending >= 0) {
            int requested = pending;
            loadPreset(requested);
            rewind();
        }
        say(playing ? "Live again. Your edits join the next pass."
                : "Paused. Click notes to audition. SPACE resumes the loop.");
    }

    private boolean confirmed(Cursor c, int action) {
        if (confirmTime > 0 && confirmAction == action
                && c.seat.id().equals(confirmOwner)) {
            cancelConfirmation();
            return true;
        }
        confirmOwner = c.seat.id();
        confirmAction = action;
        confirmTime = 4;
        say(shortName(c.seat.name(), 18)
                + (action == 4 ? ": click CLEAR again to erase the shared loop."
                : ": click that preset again to replace the edited loop."));
        return false;
    }

    private void cancelConfirmation() {
        confirmOwner = null;
        confirmAction = -1;
        confirmTime = 0;
    }

    private void requestPreset(Cursor c, int p) {
        if (p < 0 || p >= PRESETS.length) return;
        if (pending == p) {
            pending = -1;
            cancelConfirmation();
            say("Preset queue cancelled.");
            return;
        }
        if (edited && !confirmed(c, p)) return;
        cancelConfirmation();
        if (!playing || step < 0) {
            loadPreset(p);
            rewind();
            say(PRESETS[p] + " loaded.");
        } else {
            pending = p;
            say(PRESETS[p] + " starts on the next bar. Edit to cancel.");
        }
    }

    private void loadPreset(int p) {
        preset = Math.floorMod(p, PRESETS.length);
        tempo = BPMS[preset];
        count = 0;
        pending = -1;
        edited = false;
        for (int r = 0; r < ROWS; r++) {
            pitch[r] = BASE_PITCH[r];
            meters[r] = 0;
            for (int s = 0; s < STEPS; s++) {
                notes[r][s] = (PATTERNS[preset][r] & (1 << s)) != 0;
                owner[r][s] = -1;
                flashes[r][s] = 0;
                if (notes[r][s]) count++;
            }
        }
        particles.clear();
        cancelConfirmation();
    }

    private void rewind() {
        step = -1;
        bar = 0;
        remaining = 0;
        beatPulse = 0;
        for (double[] row : flashes) Arrays.fill(row, 0);
        Arrays.fill(meters, 0);
        particles.clear();
    }

    private double duration() {
        return 15.0 / tempo;
    }

    private void say(String text) {
        message = text;
        messageTime = 4;
    }

    @Override
    public void tick(double dt) {
        if (host == null || !Double.isFinite(dt) || dt <= 0) return;
        dt = Math.min(dt, 0.25);
        clock += dt;
        transportLock = Math.max(0, transportLock - dt);
        auditionLock = Math.max(0, auditionLock - dt);
        messageTime = Math.max(0, messageTime - dt);
        beatPulse = Math.max(0, beatPulse - dt * 3.8);

        if (confirmTime > 0) {
            confirmTime -= dt;
            if (confirmTime <= 0) {
                cancelConfirmation();
                say("Confirmation expired. Your loop is unchanged.");
            }
        }

        for (Cursor c : players.values()) {
            boolean moving = c.held.contains(Key.UP) || c.held.contains(Key.DOWN)
                    || c.held.contains(Key.LEFT) || c.held.contains(Key.RIGHT);
            if (moving) {
                c.repeat -= dt;
                if (c.repeat <= 0) {
                    if (c.held.contains(Key.UP) != c.held.contains(Key.DOWN)) {
                        navigate(c, c.held.contains(Key.UP) ? Key.UP : Key.DOWN);
                    }
                    if (c.held.contains(Key.LEFT) != c.held.contains(Key.RIGHT)) {
                        navigate(c, c.held.contains(Key.LEFT) ? Key.LEFT : Key.RIGHT);
                    }
                    c.repeat = 0.105;
                }
            }
            double ease = 1 - Math.exp(-38 * dt);
            c.x += (c.tx - c.x) * ease;
            c.y += (c.ty - c.y) * ease;
            c.pulse = Math.max(0, c.pulse - dt * 3.5);
        }

        for (int r = 0; r < ROWS; r++) {
            meters[r] = Math.max(0, meters[r] - dt * 2.8);
            for (int s = 0; s < STEPS; s++) {
                flashes[r][s] = Math.max(0, flashes[r][s] - dt * 3.3);
            }
        }

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
            } else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.vy += 48 * dt;
            }
        }

        if (!playing) return;
        remaining -= dt;
        int budget = 8;
        while (remaining <= 0 && budget-- > 0) {
            int next = (step + 1) % STEPS;
            if (next == 0) {
                if (pending >= 0) {
                    int requested = pending;
                    loadPreset(requested);
                    say(PRESETS[preset] + " is now live.");
                } else if (players.isEmpty() && bar > 0 && bar % 8 == 0) {
                    loadPreset((preset + 1) % PRESETS.length);
                }
                bar++;
            }
            step = next;
            if (step % 4 == 0) beatPulse = 1;

            for (int r = 0; r < ROWS; r++) {
                if (!notes[r][step]) continue;
                flashes[r][step] = 1;
                meters[r] = 1;
                float volume = r == 4 ? 0.24f : r == 1 ? 0.72f : 0.52f;
                if (players.isEmpty()) volume *= 0.65f;
                playNote(r, volume);
                burst(cellX(step), GY + r * DY + 10, COLORS[r], 4);
            }
            remaining += duration();
        }
        if (remaining <= 0) remaining = duration();
    }

    private void playNote(int r, float volume) {
        if (host != null && !muted) host.sound(SOUNDS[r], volume, PITCHES[pitch[r]]);
    }

    private void burst(double x, double y, Color color, int amount) {
        for (int i = 0; i < amount && particles.size() < 160; i++) {
            Particle p = new Particle();
            p.x = x + random.nextDouble() * 20 - 10;
            p.y = y;
            p.vx = random.nextDouble() * 65 - 32.5;
            p.vy = -40 - random.nextDouble() * 55;
            p.life = 0.4 + random.nextDouble() * 0.25;
            p.color = color;
            p.note = i == 0;
            particles.add(p);
        }
    }

    // Only the decorative background and tiny procedural sprites are rasterized.
    // Text and controls are rendered at the cabinet's actual resolution.
    private void ensureArt() {
        if (background != null) return;
        background = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = background.createGraphics();
        smooth(g);
        g.setPaint(new GradientPaint(0, 0, new Color(0x26374C), W, H, BG));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0x2A3B50));
        for (int x = 0; x < W; x += 40) g.drawLine(x, 0, x, H);
        for (int y = 0; y < H; y += 40) g.drawLine(0, y, W, y);

        g.setColor(new Color(0x9C7046));
        g.fillRect(0, 0, 7, H);
        g.fillRect(W - 7, 0, 7, H);
        g.setColor(GOLD);
        for (int x : new int[]{16, W - 17}) {
            for (int y : new int[]{15, H - 16}) g.fillRect(x - 2, y - 2, 4, 4);
        }

        g.setColor(DARK);
        g.fillRoundRect(20, 100, 1240, 477, 18, 18);
        g.fillRoundRect(20, 582, 1240, 56, 12, 12);

        for (int beat = 0; beat < 4; beat++) {
            g.setColor(beat % 2 == 0
                    ? new Color(0x23354A) : new Color(0x1B2A3F));
            g.fillRect(GX + beat * DX * 4 - 3, GY - 4,
                    DX * 4 - 2, ROWS * DY + 1);
        }
        for (int r = 0; r < ROWS; r++) {
            int y = GY + r * DY;
            g.setColor(PANEL);
            g.fillRoundRect(32, y, 264, CH, 5, 5);
            for (int s = 0; s < STEPS; s++) {
                int x = GX + s * DX;
                g.setColor(new Color(0x111F31));
                g.fillRoundRect(x, y, CW, CH, 4, 4);
                g.setColor(new Color(0x2D425C));
                g.drawRoundRect(x, y, CW, CH, 4, 4);
                g.setColor(s % 4 == 0 ? DIM : new Color(0x344961));
                g.fillRect(x + CW / 2 - 1, y + CH / 2 - 1, 3, 3);
            }
        }
        g.dispose();

        for (int r = 0; r < ROWS; r++) {
            BufferedImage sprite = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D b = sprite.createGraphics();
            Color c = COLORS[r];
            b.setColor(mix(new Color(0x523826), c, 0.23));
            b.fillRect(0, 0, CW, CH);
            b.setColor(mix(new Color(0xA87749), c, 0.45));
            b.fillRect(1, 1, CW - 2, CH - 5);
            b.setColor(c);
            b.fillRect(2, 1, CW - 4, 3);
            b.fillRect(2, CH - 5, CW - 4, 3);
            b.setColor(new Color(0x382B2B));
            b.fillRect(6, 7, CW - 12, 17);
            b.setColor(mix(c, new Color(0xAF8455), 0.55));
            for (int yy = 9; yy < 23; yy += 4) {
                for (int xx = 8; xx < CW - 7; xx += 5) {
                    b.fillRect(xx, yy, 3, 2);
                }
            }
            b.setColor(new Color(0x392D2C));
            b.fillRect(20, 6, 15, 20);
            b.setColor(c);
            musicNote(b, 22, 9, 1);
            b.setColor(new Color(0xFBE1B7));
            b.fillRect(3, 6, 2, 2);
            b.fillRect(CW - 5, 6, 2, 2);
            b.dispose();
            blocks[r] = sprite;
        }
    }

    @Override
    public void draw(Graphics2D graphics) {
        ensureArt();
        Graphics2D g = (Graphics2D) graphics.create();
        int width = host == null ? cols() * 128 : Math.max(1, host.width());
        int height = host == null ? rows() * 128 : Math.max(1, host.height());
        g.scale(width / (double) W, height / (double) H);
        smooth(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(background, 0, 0, null);

        drawHeader(g);
        drawPresets(g);
        drawGrid(g);
        drawTransport(g);
        drawFooter(g);

        for (Particle p : particles) {
            g.setColor(alpha(p.color, (int) (255 * Math.min(1, p.life / 0.2))));
            if (p.note) musicNote(g, (int) p.x, (int) p.y, 1);
            else g.fillRect((int) p.x, (int) p.y, 3, 3);
        }
        for (Cursor c : players.values()) drawCursor(g, c);
        g.dispose();
    }

    private void drawHeader(Graphics2D g) {
        g.drawImage(blocks[0], 32, 27, 48, 42, null);
        text(g, "NOTE BLOCK STUDIO", TITLE, 94, 56, WHITE);
        text(g, "BUILD A BEAT. SHARE THE GROOVE.", SMALL, 97, 82, GOLD);

        Color state = !playing ? GOLD : muted ? RED : MINT;
        panel(g, 672, 19, 334, 72, DARK);
        g.setColor(alpha(state, 30 + (int) (beatPulse * 65)));
        g.fillOval(682, 28, 28, 28);
        g.setColor(state);
        g.fillOval(690, 36, 12, 12);
        String label = !playing ? "PAUSED" : muted ? "SOUND OFF"
                : players.isEmpty() ? "LIVE DEMO" : "LIVE JAM";
        text(g, label, HEADING, 719, 49, state);
        text(g, players.isEmpty() ? "Sit to play / up to 4 musicians"
                : !playing ? "SPACE to resume the loop"
                : players.size() + " / 4 musicians connected",
                SMALL, 691, 76, SOFT);

        panel(g, 1020, 19, 228, 72, DARK);
        center(g, "-", LARGE, 1052, 58, GOLD);
        center(g, "+", LARGE, 1216, 58, GOLD);
        center(g, Integer.toString(tempo), LARGE, 1134, 53, WHITE);
        center(g, "BPM / SCROLL", TINY, 1134, 78, GOLD);
    }

    private void drawPresets(Graphics2D g) {
        text(g, "PATTERN LIBRARY", LABEL, 36, 132, GOLD);
        for (int p = 0; p < PRESETS.length; p++) {
            int x = GX + p * 232;
            boolean selected = p == preset;
            Color c = confirmAction == p ? RED : pending == p ? GOLD
                    : selected ? MINT : SOFT;
            panel(g, x, 106, 224, 40,
                    selected ? new Color(0x23463F) : PANEL);
            g.setStroke(LINE);
            g.setColor(alpha(c, selected || pending == p || confirmAction == p ? 220 : 55));
            g.drawRoundRect(x, 106, 224, 40, 7, 7);
            String label = confirmAction == p ? "REPLACE? CLICK AGAIN"
                    : PRESETS[p] + (pending == p ? " >" : selected && edited ? " *" : "");
            center(g, label, confirmAction == p ? SMALL : LABEL,
                    x + 112, 133, c);
        }

        text(g, "VOICE / AUDITION", TINY, 38, 177, SOFT);
        center(g, "PITCH", TINY, 254, 177, GOLD);
        for (int s = 0; s < STEPS; s++) {
            int x = GX + s * DX;
            boolean active = s == step;
            if (active) {
                g.setColor(playing ? MINT : GOLD);
                g.fillRoundRect(x + 8, 154, CW - 16, 29, 6, 6);
            }
            center(g, Integer.toString(s + 1), SMALL, x + CW / 2, 176,
                    active ? DARK : s % 4 == 0 ? WHITE : SOFT);
        }
    }

    private void drawGrid(Graphics2D g) {
        for (int r = 0; r < ROWS; r++) {
            int y = GY + r * DY;
            Color color = COLORS[r];
            if (meters[r] > 0) {
                g.setColor(alpha(color, (int) (meters[r] * 35)));
                g.fillRoundRect(32, y, 264, CH, 5, 5);
            }
            g.setColor(color);
            g.fillRect(33, y + 5, 4, CH - 10);
            text(g, NAMES[r], LABEL, 46, y + 23, color);
            g.setColor(alpha(color, 80));
            g.fillRect(46, y + CH - 2, 151, 2);
            g.setColor(color);
            g.fillRect(46, y + CH - 2, (int) (151 * meters[r]), 2);

            panel(g, 212, y + 1, 82, CH - 2, DARK);
            center(g, "-", SMALL, 224, y + 23, pitch[r] == 0 ? DIM : GOLD);
            center(g, PITCH_NAMES[pitch[r]], PITCH_FONT, 253, y + 23, WHITE);
            center(g, "+", SMALL, 284, y + 23,
                    pitch[r] == SCALE.length - 1 ? DIM : GOLD);

            for (int s = 0; s < STEPS; s++) {
                if (!notes[r][s]) continue;
                int x = GX + s * DX;
                double f = flashes[r][s];
                int lift = (int) (3 * f);
                if (f > 0) {
                    g.setColor(alpha(color, (int) (f * 75)));
                    g.fillRoundRect(x - 4, y - 4, CW + 8, CH + 8, 8, 8);
                }
                g.drawImage(blocks[r], x, y - lift, null);
                if (owner[r][s] >= 0) {
                    g.setColor(INKS[owner[r][s]]);
                    g.fillRect(x + 3, y + CH - 4 - lift, CW - 6, 3);
                }
                if (f > 0) {
                    g.setColor(alpha(WHITE, (int) (f * 125)));
                    g.fillRect(x + 1, y + 1 - lift, CW - 2, CH - 5);
                    g.setStroke(RING);
                    g.setColor(alpha(color, (int) (f * 255)));
                    g.drawRect(x - 1, y - 1 - lift, CW + 1, CH + 1);
                }
            }
        }

        if (step >= 0) {
            Color ink = playing ? MINT : GOLD;
            int sx = GX + step * DX;
            double progress = clamp(1 - remaining / duration(), 0, 1);
            int sweep = (int) (GX + (step + progress) * DX);
            g.setColor(alpha(ink, playing ? 24 : 15));
            g.fillRect(sx - 2, GY - 3, DX - 1, ROWS * DY + 1);
            g.setColor(alpha(ink, 45));
            g.fillRect(sweep - 4, GY - 5, 9, ROWS * DY + 3);
            g.setColor(ink);
            g.fillRect(sweep, GY - 5, 2, ROWS * DY + 3);
            g.fillPolygon(new int[]{sweep - 6, sweep + 8, sweep + 1},
                    new int[]{GY - 10, GY - 10, GY - 3}, 3);
            g.fillRect(sx, BOTTOM - 2, CW, 3);
        }

        if (count == 0) {
            panel(g, 530, 334, 472, 88, new Color(10, 18, 32, 235));
            center(g, "YOUR NEXT GREAT LOOP STARTS HERE", LABEL, 766, 367, GOLD);
            center(g, "Click a cell, or choose a pattern above.", SMALL, 766, 399, WHITE);
        }
    }

    private void drawTransport(Graphics2D g) {
        button(g, 32, 150, playing ? "II  PAUSE" : ">  PLAY",
                playing ? MINT : GOLD, true);
        button(g, 194, 142, "|< REWIND", SOFT, false);
        button(g, 348, 116, confirmAction == 4 ? "SURE?" : "CLEAR",
                confirmAction == 4 ? RED : SOFT, confirmAction == 4);
        button(g, 476, 126, muted ? "UNMUTE" : "MUTE",
                muted ? RED : SOFT, muted);

        text(g, count + " NOTES", LABEL, 620, 610, WHITE);
        text(g, "BAR " + Math.max(1, bar), TINY, 621, 631, SOFT);

        for (int beat = 0; beat < 4; beat++) {
            int x = 776 + beat * 49;
            boolean active = playing && step >= 0 && step / 4 == beat;
            Color color = active ? MINT : DIM;
            if (active) {
                g.setColor(alpha(MINT, (int) (45 * beatPulse)));
                g.fillRoundRect(x - 3, 587, 42, 46, 7, 7);
            }
            g.setColor(color);
            g.fillRoundRect(x, 591, 36, 11, 3, 3);
            center(g, Integer.toString(beat + 1), SMALL, x + 18, 626,
                    active ? WHITE : SOFT);
        }

        for (int r = 0; r < ROWS; r++) {
            int x = 1023 + r * 20;
            for (int segment = 0; segment < 5; segment++) {
                g.setColor(meters[r] > segment / 5.0
                        ? COLORS[r] : new Color(0x304158));
                g.fillRect(x, 616 - segment * 5, 13, 3);
            }
        }
        center(g, muted ? "MUTED MIX" : "PENTATONIC MIX", TINY,
                1120, 635, muted ? RED : GOLD);
    }

    private void drawFooter(Graphics2D g) {
        if (players.isEmpty()) {
            center(g, "SIT TO JAM  /  FOUR PLAYERS, ONE SHARED LOOP",
                    HEADING, W / 2, 666, GOLD);
        } else {
            int i = 0;
            for (Cursor c : players.values()) {
                int x = 36 + i++ * 310;
                g.setColor(INKS[c.color]);
                g.fillOval(x, 651, 10, 10);
                text(g, fit(g, shortName(c.seat.name(), 26), LABEL, 267),
                        LABEL, x + 18, 666, INKS[c.color]);
            }
        }

        String status;
        if (confirmTime > 0) status = message;
        else if (pending >= 0) {
            status = PRESETS[pending] + " queued for next bar / click its button to cancel";
        } else if (!playing) {
            status = "PAUSED / SPACE to resume / adding notes auditions them";
        } else if (messageTime > 0 && !players.isEmpty()) {
            status = message;
        } else if (players.isEmpty()) {
            status = "Every glowing block plays a real Minecraft note-block sound.";
        } else {
            status = "Click a voice to audition / pitch arrows tune the whole row";
        }
        center(g, fit(g, status, SMALL, W - 60), SMALL, W / 2, 689,
                confirmTime > 0 ? RED : pending >= 0 || !playing ? GOLD : SOFT);
        center(g,
                "CLICK: note   RIGHT: erase   WHEEL: tempo   SPACE: play/pause   WASD: move   F: note",
                SMALL, W / 2, 714, WHITE);
    }

    private void drawCursor(Graphics2D g, Cursor c) {
        Color color = INKS[c.color];
        int r = rowAt(c.ty), s = colAt(c.tx);
        if (r >= 0 && s >= 0) {
            int x = GX + s * DX, y = GY + r * DY;
            g.setColor(alpha(color, 36));
            g.fillRect(x, y, CW, CH);
            g.setStroke(RING);
            g.setColor(color);
            int len = 10;
            g.drawLine(x - 2, y - 2, x + len, y - 2);
            g.drawLine(x - 2, y - 2, x - 2, y + len);
            g.drawLine(x + CW + 2, y - 2, x + CW - len, y - 2);
            g.drawLine(x + CW + 2, y - 2, x + CW + 2, y + len);
            g.drawLine(x - 2, y + CH + 2, x + len, y + CH + 2);
            g.drawLine(x - 2, y + CH + 2, x - 2, y + CH - len);
            g.drawLine(x + CW + 2, y + CH + 2, x + CW - len, y + CH + 2);
            g.drawLine(x + CW + 2, y + CH + 2, x + CW + 2, y + CH - len);
        }

        int x = (int) c.x, y = (int) c.y;
        int radius = 9 + (int) (c.pulse * 7);
        g.setStroke(HEAVY);
        g.setColor(DARK);
        g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
        g.setStroke(RING);
        g.setColor(color);
        g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
        g.setColor(WHITE);
        g.fillRect(x - 1, y - 1, 3, 3);

        // Keep full names in the footer; compact translucent tags occlude less music.
        String name = shortName(c.seat.name(), 11);
        g.setFont(SMALL);
        int width = g.getFontMetrics().stringWidth(name) + 15;
        int lx = (int) clamp(x + 17, 10, W - width - 10);
        int ly = y - 35;
        if (ly < 5) ly = y + 18;
        panel(g, lx, ly, width, 25, new Color(10, 18, 32, 205));
        g.setColor(color);
        g.fillRect(lx, ly + 3, 3, 19);
        text(g, name, SMALL, lx + 8, ly + 19, color);
    }

    private static void button(Graphics2D g, int x, int width,
                               String label, Color color, boolean selected) {
        panel(g, x, 588, width, 44, selected ? mix(PANEL, color, 0.15) : PANEL);
        g.setStroke(LINE);
        g.setColor(alpha(color, selected ? 210 : 75));
        g.drawRoundRect(x, 588, width, 44, 7, 7);
        center(g, label, LABEL, x + width / 2, 617, color);
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, Color c) {
        g.setColor(c);
        g.fillRoundRect(x, y, w, h, 8, 8);
    }

    private static void musicNote(Graphics2D g, int x, int y, int scale) {
        g.fillRect(x, y + 10 * scale, 6 * scale, 4 * scale);
        g.fillRect(x + 4 * scale, y, 2 * scale, 12 * scale);
        g.fillRect(x + 6 * scale, y, 5 * scale, 3 * scale);
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static Font font(int size) {
        return new Font(Font.SANS_SERIF, Font.BOLD, size);
    }

    private static void text(Graphics2D g, String text, Font font,
                             int x, int y, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static void center(Graphics2D g, String text, Font font,
                               int x, int y, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, y);
    }

    private static String fit(Graphics2D g, String text, Font font, int width) {
        g.setFont(font);
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        int end = text.length();
        while (end > 0 && g.getFontMetrics().stringWidth(text.substring(0, end) + "…") > width) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static String shortName(String name, int limit) {
        if (name == null || name.isBlank()) return "Player";
        return name.length() > limit ? name.substring(0, limit - 1) + "…" : name;
    }

    private static boolean inside(double x, int left, int width) {
        return x >= left && x < left + width;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, a)));
    }

    private static Color mix(Color a, Color b, double t) {
        t = clamp(t, 0, 1);
        return new Color(
                (int) (a.getRed() * (1 - t) + b.getRed() * t),
                (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static final class Cursor {
        final Seat seat;
        final int color;
        final EnumSet<Key> held = EnumSet.noneOf(Key.class);
        int row, col;
        double x, y, tx, ty, pulse, repeat;

        Cursor(Seat seat, int color) {
            this.seat = seat;
            this.color = color;
        }
    }

    private static final class Particle {
        double x, y, vx, vy, life;
        Color color;
        boolean note;
    }
}

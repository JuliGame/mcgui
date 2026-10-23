package ai.resourcepack.ui.framework.api;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the pointer looks like in a seated view: an arrow, a hand over something that can be clicked, a text
 * caret, nothing, or a cursor of the server's own. Each is one glyph of the pack's pointer font, drawn so its
 * hot pixel ({@link #hotX}, {@link #hotY}) is where the pointer points.
 *
 * <p>The server's own cursors are the PNGs in {@code plugins/UIEngine/cursors/}, put in the pack when UI
 * Engine starts and named by their file name ({@code sword.png} is {@link #custom custom("sword")}). A page
 * asks for one with CSS: {@code cursor: url(sword.png) 4 0, pointer}, the hot pixel in the picture's own
 * pixels, and the keyword after it for a server without that cursor. A picture 16 pixels tall is the arrow's
 * size; one up to 32 is drawn bigger, and a taller one is scaled to 32.
 *
 * <p>The four built in are constants, so {@code ==} compares pointers; a custom cursor with a hot pixel is
 * one instance per (name, hot pixel) for the same reason.
 */
public final class Pointer {
    public static final Pointer ARROW = new Pointer("arrow", '', 0, 0, new String[]{
            "X", "XX", "XoX", "XooX", "XoooX", "XooooX", "XoooooX", "XooooooX", "XoooooooX",
            "XooooXXXXX", "XooXooX", "XoX XooX", "XX  XooX", "X    XooX", "     XooX", "      XX"});
    public static final Pointer HAND = new Pointer("hand", '', 4, 0, new String[]{
            "    XX", "   XooX", "   XooX", "   XooX", "   XooXXX", "   XooXooXXX", "XX XooXooXooXX",
            "XoXXooXooXooXoX", "XooXooooooooooX", " XoooooooooooX", "  XooooooooooX", "  XoooooooooX",
            "   XooooooooX", "   XooooooooX", "    XooooooX", "    XXXXXXXX"});
    public static final Pointer TEXT = new Pointer("text", '', 3, 8, new String[]{
            "XXX XXX", "XooXooX", "XXXoXXX", "  XoX", "  XoX", "  XoX", "  XoX", "  XoX", "  XoX",
            "  XoX", "  XoX", "  XoX", "  XoX", "XXXoXXX", "XooXooX", "XXX XXX"});
    public static final Pointer HIDDEN = new Pointer("none", '', 0, 0, new String[]{});

    private static final Pointer[] BUILT_IN = {ARROW, HAND, TEXT, HIDDEN};
    /** The first codepoint a custom cursor's glyph takes; the built-in ones are below it. */
    private static final char FIRST_CUSTOM = '';
    /** The most custom cursors the pack takes, and the tallest one is drawn (in glyph pixels; 16 is the arrow). */
    public static final int MAX_CUSTOM = 64, MAX_HEIGHT = 32;
    /** A glyph's ascent in the pointer font: a cursor's picture must be taller than this. */
    public static final int ASCENT = 7;

    private static final Map<String, Pointer> CUSTOM = new ConcurrentHashMap<>();
    private static final Map<String, Pointer> PLACED = new ConcurrentHashMap<>();

    /** Its name: arrow, hand, text, none, or a custom cursor's file name without .png. */
    public final String name;
    public final char glyph;
    /** The hot pixel, in glyph pixels from the glyph's top left. */
    public final int hotX, hotY;
    /** The built-in shapes' pictures: X black, o white, space clear. Empty for a custom cursor (see {@link #argb}). */
    public final String[] art;
    /** A custom cursor's picture as the pack carries it, or null. */
    private final BufferedImage image;
    /** The picture at glyph size, row by row (ARGB). */
    private final int[][] pixels;
    /** How many of the picture's pixels make one glyph pixel (1 but for a custom cursor taller than {@link #MAX_HEIGHT}). */
    private final double scale;

    private Pointer(String name, char glyph, int hotX, int hotY, String[] art) {
        this.name = name;
        this.glyph = glyph;
        this.hotX = hotX;
        this.hotY = hotY;
        this.art = art;
        this.image = null;
        this.scale = 1;
        this.pixels = new int[16][16];
        for (int y = 0; y < art.length; y++) {
            for (int x = 0; x < art[y].length(); x++) {
                char c = art[y].charAt(x);
                if (c == 'X') pixels[y][x] = 0xFF000000;
                else if (c == 'o') pixels[y][x] = 0xFFFFFFFF;
            }
        }
    }

    private Pointer(Pointer base, int hotX, int hotY) {
        this.name = base.name;
        this.glyph = base.glyph;
        this.hotX = hotX;
        this.hotY = hotY;
        this.art = base.art;
        this.image = base.image;
        this.scale = base.scale;
        this.pixels = base.pixels;
    }

    private Pointer(String name, char glyph, BufferedImage image) {
        this.name = name;
        this.glyph = glyph;
        this.hotX = 0;
        this.hotY = 0;
        this.art = new String[0];
        this.image = image;
        int height = Math.min(image.getHeight(), MAX_HEIGHT);
        this.scale = image.getHeight() / (double) height;
        int width = Math.max(1, (int) Math.round(image.getWidth() / scale));
        this.pixels = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y][x] = image.getRGB(Math.min(image.getWidth() - 1, (int) (x * scale)), Math.min(image.getHeight() - 1, (int) (y * scale)));
            }
        }
    }

    /** The four built-in shapes: arrow, hand, text, hidden. */
    public static Pointer[] values() {
        return BUILT_IN.clone();
    }

    /** The server's cursor called [name] (its file name in the cursors folder, without .png), or null if it has none. */
    public static Pointer custom(String name) {
        return name == null ? null : CUSTOM.get(name.toLowerCase(Locale.ROOT));
    }

    /** The server's cursors, in the order of their glyphs. */
    public static Collection<Pointer> customs() {
        List<Pointer> all = new ArrayList<>(CUSTOM.values());
        all.sort((a, b) -> Character.compare(a.glyph, b.glyph));
        return Collections.unmodifiableList(all);
    }

    /**
     * Adds a cursor called [name] drawn as [image]. UI Engine calls this for each picture in its cursors folder
     * as it builds its resource pack; one added later is not in the pack. Returns it, or null when the pack has
     * no room for another or the picture is too short for a glyph.
     */
    public static synchronized Pointer load(String name, BufferedImage image) {
        String key = name.toLowerCase(Locale.ROOT);
        if (CUSTOM.containsKey(key)) return CUSTOM.get(key);
        if (CUSTOM.size() >= MAX_CUSTOM || image.getHeight() <= ASCENT) return null;
        Pointer p = new Pointer(key, (char) (FIRST_CUSTOM + CUSTOM.size()), image);
        CUSTOM.put(key, p);
        return p;
    }

    /** This pointer with its hot pixel at ([x], [y]) of its picture (a custom cursor's own pixels; the glyph's for a built-in one). */
    public Pointer at(int x, int y) {
        int gx = Math.max(0, Math.min(width() - 1, (int) Math.round(x / scale)));
        int gy = Math.max(0, Math.min(height() - 1, (int) Math.round(y / scale)));
        if (gx == hotX && gy == hotY) return this;
        Pointer base = isCustom() ? CUSTOM.getOrDefault(name, this) : this;
        if (gx == base.hotX && gy == base.hotY) return base;
        return PLACED.computeIfAbsent(name + ' ' + gx + ' ' + gy, k -> new Pointer(base, gx, gy));
    }

    /** Whether this is one of the server's own cursors. */
    public boolean isCustom() {
        return image != null;
    }

    /** A custom cursor's picture as the pack carries it, or null for a built-in one. */
    public BufferedImage image() {
        return image;
    }

    /** Its size in glyph pixels (the arrow is 16 tall). */
    public int width() {
        return pixels.length == 0 ? 0 : pixels[0].length;
    }

    public int height() {
        return pixels.length;
    }

    /** The colour of glyph pixel ([x], [y]), ARGB; 0 where it is clear. */
    public int argb(int x, int y) {
        return y < 0 || y >= pixels.length || x < 0 || x >= pixels[y].length ? 0 : pixels[y][x];
    }

    /** The width the font gives this glyph: its rightmost drawn column, plus one, plus one of spacing. */
    public int advance() {
        int right = 0;
        for (int[] row : pixels) {
            for (int x = row.length - 1; x >= right; x--) {
                if ((row[x] >>> 24) != 0) {
                    right = x + 1;
                    break;
                }
            }
        }
        return right + 1;
    }

    @Override
    public String toString() {
        return name;
    }
}

package ai.resourcepack.screens.api;

/**
 * What the pointer looks like in a seated view: an arrow, or a hand over something that can be
 * clicked. Each is one 16×16 glyph of the pack's pointer font; (hotX, hotY) is the pixel that points.
 */
public enum Pointer {
    ARROW('', 0, 0, new String[]{
            "X", "XX", "XoX", "XooX", "XoooX", "XooooX", "XoooooX", "XooooooX", "XoooooooX",
            "XooooXXXXX", "XooXooX", "XoX XooX", "XX  XooX", "X    XooX", "     XooX", "      XX"}),
    HAND('', 4, 0, new String[]{
            "    XX", "   XooX", "   XooX", "   XooX", "   XooXXX", "   XooXooXXX", "XX XooXooXooXX",
            "XoXXooXooXooXoX", "XooXooooooooooX", " XoooooooooooX", "  XooooooooooX", "  XoooooooooX",
            "   XooooooooX", "   XooooooooX", "    XooooooX", "    XXXXXXXX"}),
    TEXT('', 3, 8, new String[]{
            "XXX XXX", "XooXooX", "XXXoXXX", "  XoX", "  XoX", "  XoX", "  XoX", "  XoX", "  XoX",
            "  XoX", "  XoX", "  XoX", "  XoX", "XXXoXXX", "XooXooX", "XXX XXX"}),
    HIDDEN('', 0, 0, new String[]{});

    public final char glyph;
    public final int hotX, hotY;
    /** X = black outline, o = white fill, space = clear. */
    public final String[] art;

    Pointer(char glyph, int hotX, int hotY, String[] art) {
        this.glyph = glyph;
        this.hotX = hotX;
        this.hotY = hotY;
        this.art = art;
    }

    /** The width the font gives this glyph: its rightmost drawn column, plus one, plus one of spacing. */
    public int advance() {
        int right = 0;
        for (String row : art) right = Math.max(right, row.stripTrailing().length());
        return right + 1;
    }
}

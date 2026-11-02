package ai.resourcepack.ui.framework.api;

/**
 * The parts of the game's HUD that plugins draw, which MC GUI can hide from a player while a view is open: the sidebar
 * scoreboard, boss bars and the action bar. Hidden by packets in that player's own connection and for them alone (what the
 * server sends meanwhile is kept and not passed on); when the view closes, however it closes, they are sent each sidebar and
 * boss bar as the server last had it. The tab list, the text under names, chat and titles are not touched.
 *
 * <p>The server's default is {@code views.hide-hud} in MC GUI's config.yml; a view says otherwise with
 * {@link ViewOptions#hud}.
 *
 * @param scoreboard the sidebar, plain and in each team colour.
 * @param bossBars   every boss bar (the server's, a command's, a plugin's).
 * @param actionBar  the action bar, except MC GUI's own messages (a page that cannot be found), which still show.
 */
public record HudParts(boolean scoreboard, boolean bossBars, boolean actionBar) {
    /** All three hidden. */
    public static final HudParts ALL = new HudParts(true, true, true);
    /** None hidden: whatever other plugins draw stays on screen over the view. */
    public static final HudParts NONE = new HudParts(false, false, false);

    /** Whether any part is hidden. */
    public boolean any() {
        return scoreboard || bossBars || actionBar;
    }

    /** These parts with the sidebar hidden or not. */
    public HudParts scoreboard(boolean scoreboard) {
        return new HudParts(scoreboard, bossBars, actionBar);
    }

    /** These parts with boss bars hidden or not. */
    public HudParts bossBars(boolean bossBars) {
        return new HudParts(scoreboard, bossBars, actionBar);
    }

    /** These parts with the action bar hidden or not. */
    public HudParts actionBar(boolean actionBar) {
        return new HudParts(scoreboard, bossBars, actionBar);
    }
}

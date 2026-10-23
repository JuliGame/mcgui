package ai.resourcepack.ui;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A page's sounds, written {@code <sound> [volume] [pitch]}: any sound the game knows
 * ({@code block.note_block.pling}, {@code minecraft:ui.button.click}) or one of UI Framework's own
 * ({@code uiframework:<file>}), or {@code none}. Heard by one player only.
 */
final class Sounds {
    /** The game's own button click, as loud as the game plays it. */
    static final String CLICK = "ui.button.click 0.25 1";

    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    /** The game's UI volume slider where the server has one, else the master slider. */
    private static final SoundCategory CATEGORY = category();

    private Sounds() {}

    /** Plays [spec] to [p]; nothing for none, a blank or a name the game would refuse. */
    static void play(Player p, String spec) {
        if (p == null || spec == null) return;
        String[] w = spec.trim().split("\\s+");
        String id = w[0].toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.equals("none") || !ID.matcher(id).matches()) return;
        float volume = w.length > 1 ? number(w[1], 1, 0, 10) : 1;
        float pitch = w.length > 2 ? number(w[2], 1, 0.5f, 2) : 1;
        p.playSound(p, id, CATEGORY, volume, pitch);
    }

    private static float number(String s, float otherwise, float min, float max) {
        try {
            float v = Float.parseFloat(s);
            return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : otherwise;
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static SoundCategory category() {
        try {
            return SoundCategory.valueOf("UI");
        } catch (IllegalArgumentException e) {
            return SoundCategory.MASTER;
        }
    }
}

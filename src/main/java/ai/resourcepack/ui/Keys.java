package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Key;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A page's names for the keys a seated player can press, for {@code data-key}: {@code w a s d}, {@code space},
 * {@code sprint}, {@code f}, {@code q} and {@code 1} to {@code 9}, or {@code up down left right jump swap drop}.
 */
final class Keys {
    private Keys() {}

    /** The page's name for [key]. */
    static String name(Key key) {
        return switch (key) {
            case UP -> "w";
            case DOWN -> "s";
            case LEFT -> "a";
            case RIGHT -> "d";
            case JUMP -> "space";
            case SPRINT -> "sprint";
            case SWAP_HANDS -> "f";
            case DROP -> "q";
            default -> String.valueOf(key.slot() + 1);
        };
    }

    /** The keys a {@code data-key} names, separated by spaces or commas, each as {@link #name} has it. */
    static Set<String> names(String attr) {
        Set<String> out = new TreeSet<>();
        for (String k : attr.toLowerCase(Locale.ROOT).split("[\s,]+")) {
            if (k.isEmpty()) continue;
            out.add(switch (k) {
                case "up" -> "w";
                case "down" -> "s";
                case "left" -> "a";
                case "right" -> "d";
                case "jump", " " -> "space";
                case "ctrl", "control" -> "sprint";
                case "swap", "offhand" -> "f";
                case "drop" -> "q";
                default -> k;
            });
        }
        return out;
    }
}

package ai.resourcepack.ui;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a page in for one player: {player}, {uuid}, {world}, {x} {y} {z}, {online}, {max}, {health},
 * {level}, and every PlaceholderAPI placeholder when that plugin is installed. Applied to a copy of
 * the page's document, in its text and its attributes, on the server thread.
 */
public final class Placeholders {
    private static Method papi;
    private static boolean probed;

    private Placeholders() {}

    private static Method papi() {
        if (!probed) {
            probed = true;
            try {
                if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                }
            } catch (ReflectiveOperationException e) {
                papi = null;
            }
        }
        return papi;
    }

    public static void reset() {
        probed = false;
        papi = null;
    }

    /** [doc] with its placeholders filled for [player] (null: the server's own values only). */
    public static Document fill(Document doc, Player player) {
        Document copy = doc.clone();
        walk(copy, player);
        return copy;
    }

    private static void walk(Node node, Player player) {
        if (node instanceof TextNode t) {
            String text = t.getWholeText();
            if (text.indexOf('{') >= 0 || text.indexOf('%') >= 0) t.text(apply(text, player));
            return;
        }
        if (node instanceof Element e) {
            // Scripts and styles are not text a player reads; leave CSS braces alone.
            String tag = e.normalName();
            if (tag.equals("style") || tag.equals("script")) return;
            for (Attribute a : e.attributes()) {
                String v = a.getValue();
                if (v.indexOf('{') >= 0 || v.indexOf('%') >= 0) {
                    if (a.getKey().equals("style")) continue;
                    a.setValue(apply(v, player));
                }
            }
        }
        for (Node child : node.childNodes()) walk(child, player);
    }

    public static String apply(String text, Player player) {
        String out = text;
        if (out.indexOf('{') >= 0) {
            out = out.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                    .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                    .replace("{server}", Bukkit.getServer().getName());
            if (player != null) {
                out = out.replace("{player}", player.getName())
                        .replace("{name}", player.getName())
                        .replace("{display_name}", plain(player))
                        .replace("{uuid}", player.getUniqueId().toString())
                        .replace("{world}", player.getWorld().getName())
                        .replace("{x}", String.valueOf(player.getLocation().getBlockX()))
                        .replace("{y}", String.valueOf(player.getLocation().getBlockY()))
                        .replace("{z}", String.valueOf(player.getLocation().getBlockZ()))
                        .replace("{health}", String.valueOf((int) Math.ceil(player.getHealth())))
                        .replace("{level}", String.valueOf(player.getLevel()))
                        .replace("{gamemode}", player.getGameMode().name().toLowerCase());
            }
        }
        Method m = papi();
        if (m != null && player != null && out.indexOf('%') >= 0) {
            try {
                out = (String) m.invoke(null, player, out);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // PlaceholderAPI failing leaves the text as it was.
            }
        }
        return out;
    }

    /** One placeholder: a built-in {name} or a PlaceholderAPI %name%. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+}|%[^%\\s]+%");

    /**
     * [command], for the console to run, with its placeholders filled for [player]; or null, with the
     * reason logged to [log], when a value put in has whitespace or a ';' in it. A display name, a
     * world's name or a PlaceholderAPI value can, and would then add arguments, or with some command
     * plugins a second command, to what runs with the console's rights.
     */
    public static String command(String command, Player player, Logger log) {
        Matcher m = PLACEHOLDER.matcher(command);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String value = apply(token, player);
            if (!value.equals(token) && value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == ';')) {
                log.warning("Not running console command '" + command + "': " + token + " is '" + value + "', which has a space or ';' in it");
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String plain(Player p) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(p.displayName());
    }
}

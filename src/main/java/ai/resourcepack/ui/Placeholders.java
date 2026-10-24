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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
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

    /**
     * A page's document as filled in for a player: the values, worked out (on the server thread, where they are
     * read) without copying the document. [values] are every value put in, in document order, and [key] all of
     * them in one string: two copies of the same page with the same key are the same document. [personal]: some
     * value was the player's own (their name, where they are, anything PlaceholderAPI filled), so the copy is theirs
     * alone; [personalAt] says which, by place in [values], and [template] is [key] with each of those left out (null
     * if none is): two personal copies with the same template differ only in their own values. {@link #copy()} makes
     * the filled copy, on any thread.
     */
    public record Filled(Document source, List<String> values, String key, boolean personal, BitSet personalAt, String template) {
        /** A copy of [source] with [values] put in: what the player sees. Any thread; [source] is only read. */
        public Document copy() {
            Document copy = source.clone();
            Iterator<String> next = values.iterator();
            walk(copy, (owner, attr, text) -> next.next(), true);
            return copy;
        }

        /**
         * Where [copy] (made by {@link #copy()}, and not changed since: the same nodes as [source]) holds this fill's personal values: the elements
         * whose own text holds one, and the elements with an attribute that holds one, with those attributes' names.
         */
        public Parts parts(Document copy) {
            Set<Element> text = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            Map<Element, List<String>> attrs = new IdentityHashMap<>();
            // The copy's values are filled in, so where they are is read off [source], node by node alongside it.
            if (personal) parts(source, copy, new int[]{0}, text, attrs);
            return new Parts(text, attrs);
        }

        private void parts(Node src, Node copy, int[] at, Set<Element> text, Map<Element, List<String>> attrs) {
            if (src instanceof TextNode t) {
                String v = t.getWholeText();
                if ((v.indexOf('{') >= 0 || v.indexOf('%') >= 0) && personalAt.get(at[0]++) && copy.parent() instanceof Element p) text.add(p);
                return;
            }
            if (src instanceof Element e) {
                String tag = e.normalName();
                if (tag.equals("style") || tag.equals("script")) return;
                if (e.attributesSize() > 0) {
                    for (Attribute a : e.attributes()) {
                        String v = a.getValue();
                        if ((v.indexOf('{') >= 0 || v.indexOf('%') >= 0) && !a.getKey().equals("style") && personalAt.get(at[0]++)) {
                            attrs.computeIfAbsent((Element) copy, k -> new ArrayList<>()).add(a.getKey());
                        }
                    }
                }
            }
            for (int i = 0, n = src.childNodeSize(); i < n; i++) parts(src.childNode(i), copy.childNode(i), at, text, attrs);
        }
    }

    /** Where a copy holds its personal values: in the own text of [text], and in the attributes [attrs] names. */
    public record Parts(Set<Element> text, Map<Element, List<String>> attrs) {}

    /** [doc] with its placeholders filled for [player] (null: the server's own values only). */
    public static Filled fill(Document doc, Player player) {
        return fill(doc, text -> apply(text, player), player == null ? null : text -> apply(text, null));
    }

    /**
     * [doc] with [fill] applied to every text and attribute that may hold a placeholder. Personal if [anyone]
     * (what the text is filled with for nobody in particular; null when [fill] is that already) gives another value.
     * [doc] is only read: it is the page's own, shared by every session and copied on render threads.
     */
    static Filled fill(Document doc, UnaryOperator<String> fill, UnaryOperator<String> anyone) {
        List<String> values = new ArrayList<>();
        StringBuilder key = new StringBuilder(), template = new StringBuilder();
        BitSet personal = new BitSet();
        walk(doc, (owner, attr, text) -> {
            String out = fill.apply(text);
            // Separated by a character no value has, so "ab" + "c" never reads as "a" + "bc".
            key.append(out).append('\0');
            if (anyone != null && !out.equals(anyone.apply(text))) {
                personal.set(values.size());
                // The template has a mark where a player's own value goes: no value has that character either.
                template.append('\u0001').append('\0');
            } else {
                template.append(out).append('\0');
            }
            values.add(out);
            return out;
        }, false);
        boolean any = !personal.isEmpty();
        return new Filled(doc, values, key.toString(), any, personal, any ? template.toString() : null);
    }

    /** Told each text (with its element, [attr] null) and attribute value that may hold a placeholder; answers its value. */
    private interface Visitor {
        String visit(Element owner, String attr, String text);
    }

    /**
     * Every text and attribute of [node] that may hold a placeholder, in document order, given to [value]; put back
     * as it answers if [write]. Read only otherwise: not even an empty attribute list is made (jsoup makes one on
     * {@code attributes()}).
     */
    private static void walk(Node node, Visitor value, boolean write) {
        if (node instanceof TextNode t) {
            String text = t.getWholeText();
            if (text.indexOf('{') >= 0 || text.indexOf('%') >= 0) {
                String out = value.visit(t.parent() instanceof Element p ? p : null, null, text);
                if (write) t.text(out);
            }
            return;
        }
        if (node instanceof Element e) {
            // Scripts and styles are not text a player reads; leave CSS braces alone.
            String tag = e.normalName();
            if (tag.equals("style") || tag.equals("script")) return;
            if (e.attributesSize() > 0) {
                for (Attribute a : e.attributes()) {
                    String v = a.getValue();
                    if (v.indexOf('{') >= 0 || v.indexOf('%') >= 0) {
                        if (a.getKey().equals("style")) continue;
                        String out = value.visit(e, a.getKey(), v);
                        if (write) a.setValue(out);
                    }
                }
            }
        }
        for (int i = 0, n = node.childNodeSize(); i < n; i++) walk(node.childNode(i), value, write);
    }

    public static String apply(String text, Player player) {
        String out = text;
        // Each value only if its token is there: a page's style sheet is full of braces, and working out the display
        // name's plain text alone allocated about 200 KB a call.
        if (out.indexOf('{') >= 0) {
            out = put(out, "{online}", () -> String.valueOf(Bukkit.getOnlinePlayers().size()));
            out = put(out, "{max}", () -> String.valueOf(Bukkit.getMaxPlayers()));
            out = put(out, "{server}", () -> Bukkit.getServer().getName());
            if (player != null) {
                out = put(out, "{player}", player::getName);
                out = put(out, "{name}", player::getName);
                out = put(out, "{display_name}", () -> plain(player));
                out = put(out, "{uuid}", () -> player.getUniqueId().toString());
                out = put(out, "{world}", () -> player.getWorld().getName());
                out = put(out, "{x}", () -> String.valueOf(player.getLocation().getBlockX()));
                out = put(out, "{y}", () -> String.valueOf(player.getLocation().getBlockY()));
                out = put(out, "{z}", () -> String.valueOf(player.getLocation().getBlockZ()));
                out = put(out, "{health}", () -> String.valueOf((int) Math.ceil(player.getHealth())));
                out = put(out, "{level}", () -> String.valueOf(player.getLevel()));
                out = put(out, "{gamemode}", () -> player.getGameMode().name().toLowerCase());
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

    /**
     * One placeholder: a built-in {name}, or a PlaceholderAPI %identifier_params%, whose params may
     * hold spaces (%math_{player_level} * 10%), as PlaceholderAPI's own parser allows.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+}|%[^%\\s_]+_[^%]*%|%[^%\\s]+%");

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

    private static String put(String text, String token, java.util.function.Supplier<String> value) {
        return text.contains(token) ? text.replace(token, value.get()) : text;
    }
}

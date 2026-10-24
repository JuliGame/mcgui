package ai.resourcepack.ui.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How MC GUI talks in chat, in the same shape as the other ResourcePack AI plugins: {@code [MCGUI]} in front,
 * the words grey, what a sentence is about (a page, a code) in the accent, a command white, a problem red and
 * a success green. Every colour and the word in the brackets come from {@code chat:} in config.yml.
 *
 * <p>A command named in a message is put in the chat box when clicked, never run: run from the chat screen, a
 * command that opens a page leaves the chat drawn over it.
 */
public final class Chat {
    /** The words used when config.yml says nothing, or something that is not a colour. */
    private static final String NAME = "MCGUI";
    private static final TextColor BRAND = TextColor.color(0x3670F8), DARK_GREY = TextColor.color(0x555555), GREY = TextColor.color(0xAAAAAA);
    private static final TextColor RED = TextColor.color(0xFF5555), GREEN = TextColor.color(0x55FF55), YELLOW = TextColor.color(0xFFFF55), WHITE = TextColor.color(0xFFFFFF);

    private record Style(String name, TextColor prefix, TextColor brackets, TextColor body, TextColor accent, TextColor command,
                         TextColor error, TextColor success, TextColor warning) {}

    private static volatile Style style = new Style(NAME, BRAND, DARK_GREY, GREY, BRAND, WHITE, RED, GREEN, YELLOW);

    /**
     * A command of this plugin in a message: its root, the subcommand, and then only the parts to fill in
     * ({@code <page>}, {@code [player]}, {@code wall|view}) or a page's name, so the prose after it is left out.
     */
    private static final Pattern COMMAND = Pattern.compile(
            "/(?:mcgui|gui|ui)\\b(?: [a-z]+)?(?: (?:<[^>\\s]+>|\\[[^\\]\\s]+\\]|[A-Za-z0-9_]+(?:\\|[A-Za-z0-9_]+)+))*");
    /** A name in single quotes: a page or a screen. */
    private static final Pattern QUOTED = Pattern.compile("'([^'\\s][^']*)'");

    private Chat() {}

    /** Reads {@code chat:} from config.yml: the word in the brackets and the colours. On start-up and on a reload. */
    public static void configure(ConfigurationSection chat) {
        if (chat == null) {
            style = new Style(NAME, BRAND, DARK_GREY, GREY, BRAND, WHITE, RED, GREEN, YELLOW);
            return;
        }
        String name = chat.getString("prefix", NAME).trim();
        ConfigurationSection c = chat.getConfigurationSection("colour");
        style = new Style(name.isEmpty() ? NAME : name,
                colour(c, "prefix", BRAND), colour(c, "brackets", DARK_GREY), colour(c, "body", GREY), colour(c, "accent", BRAND),
                colour(c, "command", WHITE), colour(c, "error", RED), colour(c, "success", GREEN), colour(c, "warning", YELLOW));
    }

    /** A colour from config.yml, #rrggbb with or without the hash; anything else is [fallback]. */
    private static TextColor colour(ConfigurationSection c, String key, TextColor fallback) {
        String hex = c == null ? null : c.getString(key);
        if (hex == null) return fallback;
        TextColor parsed = TextColor.fromHexString(hex.trim().startsWith("#") ? hex.trim() : "#" + hex.trim());
        return parsed == null ? fallback : parsed;
    }

    public static TextColor body() {
        return style.body;
    }

    public static TextColor accent() {
        return style.accent;
    }

    /** {@code [MCGUI] }, in front of every message. */
    public static Component prefix() {
        Style s = style;
        return Component.text()
                .append(Component.text("[", s.brackets))
                .append(Component.text(s.name, s.prefix))
                .append(Component.text("] ", s.brackets))
                .build();
    }

    /** A line under a message: indented, no prefix. */
    public static void detail(CommandSender to, String text) {
        to.sendMessage(Component.text().append(Component.text("  ")).append(words(text, style.body)).build());
    }

    /** A heading over a list: the accent, bold, and the rest of the line grey. */
    public static Component heading(String title, Component after) {
        Style s = style;
        return Component.text()
                .append(Component.text(title, s.accent, TextDecoration.BOLD))
                .append(after == null ? Component.empty() : Component.text("  ").append(after.colorIfAbsent(s.body)))
                .build();
    }

    /** One row of a list: a command, white, and what it does, grey. */
    public static Component row(String command, String what) {
        Style s = style;
        return Component.text()
                .append(Component.text("  "))
                .append(command(command))
                .append(Component.text(" - " + what, s.body))
                .build();
    }

    /** A message: red is a problem, green done, yellow a warning, anything else the ordinary grey. */
    public static boolean say(CommandSender to, String text, NamedTextColor kind) {
        Style s = style;
        TextColor color = kind == NamedTextColor.RED ? s.error : kind == NamedTextColor.GREEN ? s.success : kind == NamedTextColor.YELLOW ? s.warning : s.body;
        to.sendMessage(Component.text().append(prefix()).append(words(text, color)).build());
        return true;
    }

    /** A message built by the caller, after the prefix. */
    public static void send(CommandSender to, Component message) {
        to.sendMessage(Component.text().append(prefix()).append(message).build());
    }

    /** Text in the accent: a page, a code, a count. */
    public static Component accent(String text) {
        return Component.text(text, style.accent);
    }

    /** Text in the ordinary grey. */
    public static Component text(String text) {
        return Component.text(text, style.body);
    }

    /** A command, white, put in the chat box when clicked (up to the first part to fill in). */
    public static Component command(String command) {
        return Component.text(command, style.command)
                .clickEvent(ClickEvent.suggestCommand(fillable(command)))
                .hoverEvent(HoverEvent.showText(Component.text("Click to put it in the chat box", style.body)));
    }

    /** What goes in the chat box: the command up to its first part to fill in, or all of it. */
    private static String fillable(String command) {
        int cut = command.length();
        for (char c : new char[]{'<', '[', '|'}) {
            int i = command.indexOf(c);
            if (i >= 0) cut = Math.min(cut, i);
        }
        if (cut == command.length()) return command;
        String head = command.substring(0, cut);
        int space = head.lastIndexOf(' ');
        // "test wall|view": the word the choice is in goes too, so the box ends where a choice is made.
        if (command.charAt(cut) == '|' && space >= 0) head = head.substring(0, space + 1);
        return head.endsWith(" ") ? head : head + " ";
    }

    /** [text] in [color], each command in it a {@link #command} and each quoted name in the accent. */
    static Component words(String text, TextColor color) {
        TextComponent.Builder out = Component.text();
        Matcher m = COMMAND.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(quoted(text.substring(at, m.start()), color));
            out.append(command(m.group()));
            at = m.end();
        }
        out.append(quoted(text.substring(at), color));
        return out.build();
    }

    private static Component quoted(String text, TextColor color) {
        TextComponent.Builder out = Component.text();
        Matcher m = QUOTED.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(Component.text(text.substring(at, m.start()), color));
            out.append(Component.text(m.group(1), style.accent));
            at = m.end();
        }
        out.append(Component.text(text.substring(at), color));
        return out.build();
    }
}

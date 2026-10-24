package ai.resourcepack.ui.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How MC GUI talks in chat: every message starts with the plugin's name, the words are one colour for what kind
 * of message it is, and anything in it worth acting on stands out in the accent and can be clicked. A command
 * named in a message ({@code /mcgui import <code>}) is put in the chat box when it still needs filling in, and
 * run when it does not; a page named in quotes ({@code 'menu'}) is only coloured.
 */
public final class Chat {
    /** The plugin's name in front of each message, and the accent: commands, page names, anything to click. */
    public static final TextColor BRAND = TextColor.color(0x6E9BFF), ACCENT = TextColor.color(0x8FB8FF);
    public static final TextColor BODY = TextColor.color(0xC9CCD3), MUTED = TextColor.color(0x8A8E99), SEPARATOR = TextColor.color(0x50545E);
    public static final TextColor GOOD = TextColor.color(0x7BE495), WARN = TextColor.color(0xFFD27A), BAD = TextColor.color(0xFF7A7A);

    /**
     * A command of this plugin in a message: its root, the subcommand, and then only the parts to fill in
     * ({@code <page>}, {@code [player]}, {@code wall|view}), so the prose after it ("to match your settings") is left out.
     */
    private static final Pattern COMMAND = Pattern.compile(
            "/(?:mcgui|gui|ui)\\b(?: [a-z]+)?(?: (?:<[^>\\s]+>|\\[[^\\]\\s]+\\]|[A-Za-z0-9_]+(?:\\|[A-Za-z0-9_]+)+))*");
    /** A name in single quotes: a page or a screen. */
    private static final Pattern QUOTED = Pattern.compile("'([^'\\s][^']*)'");

    private Chat() {}

    /** "MC GUI ›", in front of every message. */
    public static Component prefix() {
        return Component.text("MC GUI", BRAND, TextDecoration.BOLD).append(Component.text(" › ", SEPARATOR));
    }

    /** A message, its words in [color], with the commands and names in it marked out. */
    public static void send(CommandSender to, String text, TextColor color) {
        to.sendMessage(prefix().append(words(text, color)));
    }

    /** A message after another, without the name in front: the lines of a list. */
    public static void detail(CommandSender to, String text) {
        to.sendMessage(Component.text("  ", BODY).append(words(text, MUTED)));
    }

    /** {@link #send} with the colour of the old one-colour messages: red is a problem, green done, yellow a warning, the rest plain. */
    public static boolean say(CommandSender to, String text, NamedTextColor old) {
        send(to, text, old == NamedTextColor.RED ? BAD : old == NamedTextColor.GREEN ? GOOD : old == NamedTextColor.YELLOW ? WARN : BODY);
        return true;
    }

    /**
     * A command to click: put in the chat box to finish when it has a part to fill in ({@code <page>},
     * {@code [player]}, {@code a|b}), else run.
     */
    public static Component command(String command) {
        boolean finish = command.contains("<") || command.contains("[") || command.contains("|");
        String hint = finish ? "Click to fill in" : "Click to run";
        return Component.text(command, ACCENT)
                .clickEvent(finish ? ClickEvent.suggestCommand(fillable(command)) : ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hint, MUTED)));
    }

    /** What goes in the chat box for a command with parts to fill in: up to the first of them. */
    private static String fillable(String command) {
        int cut = command.length();
        for (char c : new char[]{'<', '[', '|'}) {
            int i = command.indexOf(c);
            if (i >= 0) cut = Math.min(cut, i);
        }
        String head = command.substring(0, cut);
        int space = head.lastIndexOf(' ');
        // "test wall|view": the word the choice is in goes too, so the box ends where a choice is made.
        if (cut < command.length() && command.charAt(cut) == '|' && space >= 0) head = head.substring(0, space + 1);
        return head.endsWith(" ") ? head : head + " ";
    }

    /** [text] in [color], each command in it a {@link #command} and each quoted name in the accent. */
    static Component words(String text, TextColor color) {
        TextComponent.Builder out = Component.text();
        Matcher m = COMMAND.matcher(text);
        int at = 0;
        while (m.find()) {
            String found = m.group();
            // A full stop after a command is the sentence's.
            int end = m.end();
            while (found.endsWith(".")) {
                found = found.substring(0, found.length() - 1);
                end--;
            }
            out.append(quoted(text.substring(at, m.start()), color));
            out.append(command(found));
            at = end;
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
            out.append(Component.text(m.group(1), ACCENT));
            at = m.end();
        }
        out.append(Component.text(text.substring(at), color));
        return out.build();
    }
}

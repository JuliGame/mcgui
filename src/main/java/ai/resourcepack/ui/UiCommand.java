package ai.resourcepack.ui;

import ai.resourcepack.ui.chat.Chat;
import ai.resourcepack.ui.framework.FrameworkCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /mcgui open &lt;page&gt; [player] · close [player] · place &lt;page&gt; [cols] [rows] [name] [shared] ·
 * remove [name] · list · reload · import &lt;code&gt;, and the framework's own, which {@link FrameworkCommand} answers.
 *
 * <p>Each subcommand checks its own node, {@code mcgui.<subcommand>}, and one pointed at another
 * player also needs {@code mcgui.<subcommand>.others}. {@code mcgui.admin} is only a parent in
 * {@code plugin.yml} that grants them all; nothing here checks it. Closing your own page needs nothing.
 */
final class UiCommand implements TabExecutor {
    private static final Set<BlockFace> WALL = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final MCGUIPlugin plugin;
    /** The framework's own subcommands (test, fov, pointer, pack, stats, clear, debug), handed on as they are. */
    private final FrameworkCommand framework;
    private final PageImport importer;

    UiCommand(MCGUIPlugin plugin, FrameworkCommand framework) {
        this.plugin = plugin;
        this.framework = framework;
        this.importer = new PageImport(plugin);
    }

    /** The node a subcommand needs: {@code mcgui.<subcommand>}. */
    static String permissionFor(String sub) {
        return "mcgui." + sub;
    }

    /** What pointing a subcommand at somebody else needs on top: {@code mcgui.<subcommand>.others}. */
    static String othersPermissionFor(String sub) {
        return permissionFor(sub) + ".others";
    }

    /** Whether the sender holds {@code node}; tells them which one they lack when not. */
    private static boolean permitted(CommandSender sender, String node) {
        if (sender.hasPermission(node)) return true;
        say(sender, "You need " + node + " for that.", NamedTextColor.RED);
        return false;
    }

    /** Whether the sender may run {@code sub} at all, which is what tab completion offers. */
    private static boolean mayRun(CommandSender sender, String sub) {
        return sub.equals("close") || sender.hasPermission(permissionFor(sub));
    }

    private static boolean say(CommandSender to, String text, NamedTextColor color) {
        return Chat.say(to, text, color);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("stats")) {
            if (!permitted(sender, permissionFor(sub))) return true;
            if (args.length > 1 && args[1].equalsIgnoreCase("json")) sender.sendMessage(Stats.json(plugin));
            else {
                List<String> lines = Stats.lines(plugin);
                for (int i = 0; i < lines.size(); i++) {
                    if (i == 0) say(sender, lines.get(i), NamedTextColor.GRAY);
                    else Chat.detail(sender, lines.get(i).strip());
                }
            }
            return true;
        }
        if (FrameworkCommand.SUBCOMMANDS.contains(sub)) return framework.onCommand(sender, command, label, args);
        switch (sub) {
            case "open" -> open(sender, args);
            case "close" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) return say(sender, "Name a player.", NamedTextColor.RED);
                if (target != sender && !permitted(sender, othersPermissionFor("close"))) return true;
                if (!plugin.screens().close(target)) say(sender, target.getName() + " has no page open.", NamedTextColor.GRAY);
            }
            case "place" -> place(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> {
                if (!permitted(sender, permissionFor("list"))) return true;
                List<String> names = plugin.pages().names();
                if (names.isEmpty()) say(sender, "No pages yet: put .html files in " + plugin.pages().root(), NamedTextColor.GRAY);
                else sender.sendMessage(Chat.prefix().append(Component.text("Pages ", Chat.BODY)).append(pageLinks(names, label)));
                for (Screens.Wall w : plugin.screens().walls()) {
                    Chat.detail(sender, "Screen '" + w.name + "' shows '" + w.page + "', " + w.cols + "x" + w.rows + (w.shared ? " shared" : "") + " in "
                            + w.topLeft.getWorld().getName() + " at " + w.topLeft.getBlockX() + " " + w.topLeft.getBlockY() + " " + w.topLeft.getBlockZ());
                }
            }
            case "import" -> {
                if (!permitted(sender, permissionFor("import"))) return true;
                if (args.length < 2) return say(sender, "/mcgui import <code>: the code from Send to server on mcgui.dev", NamedTextColor.RED);
                importer.run(sender, args[1]);
            }
            case "reload" -> {
                if (!permitted(sender, permissionFor("reload"))) return true;
                plugin.reload();
                say(sender, "Reloaded the config and every page.", NamedTextColor.GREEN);
            }
            default -> help(sender, label);
        }
        return true;
    }

    /** Each subcommand: its words after the root, what it does, and the subcommand its permission is named after (null: anyone). */
    private record Help(String usage, String what, String sub) {}

    private static final List<Help> HELP = List.of(
            new Help("open <page> [player]", "Show a page fullscreen", "open"),
            new Help("close [player]", "Close the page that is open", null),
            new Help("place <page> [cols] [rows] [name] [shared]", "Put a page on the wall you look at", "place"),
            new Help("remove [name]", "Take a wall screen down", "remove"),
            new Help("list", "Every page and screen", "list"),
            new Help("import <code>", "Bring in pages made on mcgui.dev", "import"),
            new Help("reload", "Read the config and every page again", "reload"),
            new Help("test wall|view [cols] [rows]", "A test picture on a wall or fullscreen", "test"),
            new Help("fov <degrees>", "Fit views to your field of view", "fov"),
            new Help("pointer [speed]", "How fast the pointer moves", "pointer"),
            new Help("pack", "Send the resource pack again", "pack"),
            new Help("stats", "What the plugin costs the server", "stats"),
            new Help("clear", "Remove every test wall", "clear"));

    /** The commands the sender may run, one a line, each to click, under the plugin's name and version. */
    private void help(CommandSender sender, String label) {
        String root = "/" + label.toLowerCase(Locale.ROOT).replaceFirst("^mcgui:", "");
        sender.sendMessage(Chat.prefix()
                .append(Component.text(plugin.getPluginMeta().getVersion(), Chat.MUTED))
                .append(Component.text(" · ", Chat.SEPARATOR))
                .append(Component.text("mcgui.dev", Chat.ACCENT)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://mcgui.dev/"))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Make pages in your browser", Chat.MUTED)))));
        for (Help h : HELP) {
            if (h.sub() != null && !sender.hasPermission(permissionFor(h.sub()))) continue;
            sender.sendMessage(Component.text("  ")
                    .append(Chat.command(root + " " + h.usage()))
                    .append(Component.text("  " + h.what(), Chat.MUTED)));
        }
    }

    /** [pages] as names to click, each opening its page for the sender, with dots between. */
    static Component pageLinks(List<String> pages, String label) {
        String root = "/" + (label == null ? "mcgui" : label.toLowerCase(Locale.ROOT).replaceFirst("^mcgui:", ""));
        Component line = Component.empty();
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            if (i > 0) line = line.append(Component.text(" · ", Chat.SEPARATOR));
            line = line.append(Component.text(page, Chat.ACCENT)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(root + " open " + page))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Open " + page, Chat.MUTED))));
        }
        return line;
    }

    private void open(CommandSender sender, String[] args) {
        if (!permitted(sender, permissionFor("open"))) return;
        if (args.length < 2) {
            say(sender, "/mcgui open <page> [player]", NamedTextColor.RED);
            return;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) {
            say(sender, args.length > 2 ? "No player called " + args[2] + "." : "Name a player.", NamedTextColor.RED);
            return;
        }
        if (target != sender && !permitted(sender, othersPermissionFor("open"))) return;
        String page = args[1];
        if (!plugin.pages().exists(page)) {
            say(sender, "There is no page called '" + page + "' in " + plugin.pages().root().getFileName() + "/.", NamedTextColor.RED);
            return;
        }
        try {
            // A page not loaded yet opens when it has; what goes wrong then is told here too.
            if (!plugin.screens().open(target, page, problem -> say(sender, problem, NamedTextColor.RED))) {
                say(sender, "Could not open '" + page + "': see the console.", NamedTextColor.RED);
            }
        } catch (IllegalStateException e) {
            say(sender, e.getMessage(), NamedTextColor.RED);
        }
    }

    private void place(CommandSender sender, String[] args) {
        if (!permitted(sender, permissionFor("place"))) return;
        // From the console (or a script): /mcgui place <page> <cols> <rows> <name> <x> <y> <z> <facing> [world] [shared]
        if (args.length >= 9) {
            placeAt(sender, args);
            return;
        }
        if (!(sender instanceof Player p) || args.length < 2) {
            say(sender, "As a player, looking at a wall: /mcgui place <page> [cols] [rows] [name] [shared]"
                    + " (or anywhere: /mcgui place <page> <cols> <rows> <name> <x> <y> <z> <facing> [world] [shared])", NamedTextColor.RED);
            return;
        }
        String page = args[1];
        if (!plugin.pages().exists(page)) {
            say(sender, "There is no page called '" + page + "'.", NamedTextColor.RED);
            return;
        }
        int cols = number(args, 2, 4), rows = number(args, 3, 3);
        String name = args.length > 4 ? args[4] : plugin.pages().normalise(page).replace('/', '-').replace('.', '-') + "-" + (plugin.screens().walls().size() + 1);
        boolean shared = args.length > 5 && args[5].equalsIgnoreCase("shared");
        Block block = p.getTargetBlockExact(16);
        BlockFace face = p.getTargetBlockFace(16);
        if (block == null || face == null || !WALL.contains(face)) {
            say(sender, "Look at the side of a wall: the block you look at becomes the top-left corner.", NamedTextColor.RED);
            return;
        }
        try {
            plugin.screens().place(name, page, block.getRelative(face).getLocation(), face, cols, rows, shared, 0);
            say(sender, "Placed '" + name + "' (" + cols + "x" + rows + " maps) showing " + page + ". Look at it and click.", NamedTextColor.GREEN);
        } catch (RuntimeException e) {
            say(sender, "Could not place it: " + e.getMessage(), NamedTextColor.RED);
        }
    }

    private void placeAt(CommandSender sender, String[] args) {
        String page = args[1];
        if (!plugin.pages().exists(page)) {
            say(sender, "There is no page called '" + page + "'.", NamedTextColor.RED);
            return;
        }
        try {
            int cols = number(args, 2, 4), rows = number(args, 3, 3);
            String name = args[4];
            int x = Integer.parseInt(args[5]), y = Integer.parseInt(args[6]), z = Integer.parseInt(args[7]);
            BlockFace face = BlockFace.valueOf(args[8].toUpperCase(Locale.ROOT));
            if (!WALL.contains(face)) throw new IllegalArgumentException("facing is north, south, east or west");
            org.bukkit.World world = args.length > 9 && Bukkit.getWorld(args[9]) != null ? Bukkit.getWorld(args[9])
                    : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
            boolean shared = args[args.length - 1].equalsIgnoreCase("shared");
            plugin.screens().place(name, page, new org.bukkit.Location(world, x, y, z), face, cols, rows, shared, 0);
            say(sender, "Placed '" + name + "' at " + x + " " + y + " " + z + " facing " + face.name().toLowerCase(Locale.ROOT) + ".", NamedTextColor.GREEN);
        } catch (IllegalArgumentException e) {
            say(sender, "Could not place it: " + e.getMessage(), NamedTextColor.RED);
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (!permitted(sender, permissionFor("remove"))) return;
        String name = args.length > 1 ? args[1] : null;
        if (name == null && sender instanceof Player p) {
            Block block = p.getTargetBlockExact(16);
            BlockFace face = p.getTargetBlockFace(16);
            if (block != null && face != null) {
                Screens.Wall w = plugin.screens().wallAt(block.getRelative(face).getLocation());
                if (w == null) w = plugin.screens().wallAt(block.getLocation());
                if (w != null) name = w.name;
            }
        }
        if (name == null) {
            say(sender, "Name the screen, or look at it: /mcgui remove [name]", NamedTextColor.RED);
            return;
        }
        say(sender, plugin.screens().remove(name) ? "Removed '" + name + "'." : "No screen called '" + name + "'.", NamedTextColor.GRAY);
    }

    private static int number(String[] args, int i, int fallback) {
        try {
            return args.length > i ? Math.max(1, Math.min(16, Integer.parseInt(args[i]))) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        // Only what the sender may run is offered.
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String sub : List.of("open", "close", "place", "remove", "list", "reload", "import")) if (mayRun(sender, sub)) subs.add(sub);
            subs.addAll(framework.onTabComplete(sender, command, label, args));
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (FrameworkCommand.SUBCOMMANDS.contains(sub)) return framework.onTabComplete(sender, command, label, args);
        if (!mayRun(sender, sub)) return List.of();
        boolean playerArg = (args.length == 3 && sub.equals("open")) || (args.length == 2 && sub.equals("close"));
        if (playerArg && !sender.hasPermission(othersPermissionFor(sub))) {
            // Somebody who may act only on themselves is offered their own name, not the server's.
            return sender instanceof Player self ? filter(List.of(self.getName()), args[args.length - 1]) : List.of();
        }
        if (args.length == 2 && (sub.equals("open") || sub.equals("place"))) return filter(plugin.pages().names(), args[1]);
        if (args.length == 2 && sub.equals("remove")) {
            List<String> names = new ArrayList<>();
            for (Screens.Wall w : plugin.screens().walls()) names.add(w.name);
            return filter(names, args[1]);
        }
        if ((args.length == 3 && sub.equals("open")) || (args.length == 2 && sub.equals("close"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[args.length - 1]);
        }
        if (args.length == 6 && sub.equals("place")) return filter(List.of("shared"), args[5]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }
}

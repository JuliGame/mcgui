package ai.resourcepack.ui;

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
 * /ui open &lt;page&gt; [player] · close [player] · place &lt;page&gt; [cols] [rows] [name] [shared] ·
 * remove [name] · list · reload
 */
final class UiCommand implements TabExecutor {
    private static final Set<BlockFace> WALL = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final UIEnginePlugin plugin;

    UiCommand(UIEnginePlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean say(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> open(sender, args);
            case "close" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) return say(sender, "Name a player.", NamedTextColor.RED);
                if (target != sender && !sender.hasPermission("uiengine.open.others")) return say(sender, "You can't close other players' pages.", NamedTextColor.RED);
                if (!plugin.screens().close(target)) say(sender, target.getName() + " has no page open.", NamedTextColor.GRAY);
            }
            case "place" -> place(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> {
                if (!sender.hasPermission("uiengine.admin")) return say(sender, "You can't do that.", NamedTextColor.RED);
                List<String> names = plugin.pages().names();
                say(sender, names.isEmpty() ? "No pages yet: put .html files in " + plugin.pages().root() : "Pages: " + String.join(", ", names), NamedTextColor.GRAY);
                for (Screens.Wall w : plugin.screens().walls()) {
                    say(sender, "  screen " + w.name + ": " + w.page + ", " + w.cols + "x" + w.rows + (w.shared ? " shared" : "") + " in "
                            + w.topLeft.getWorld().getName() + " at " + w.topLeft.getBlockX() + " " + w.topLeft.getBlockY() + " " + w.topLeft.getBlockZ(), NamedTextColor.GRAY);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("uiengine.admin")) return say(sender, "You can't do that.", NamedTextColor.RED);
                plugin.reload();
                say(sender, "Reloaded the config and every page.", NamedTextColor.GREEN);
            }
            default -> say(sender, "/ui open <page> [player] · close [player] · place <page> [cols] [rows] [name] [shared] · remove [name] · list · reload",
                    NamedTextColor.GRAY);
        }
        return true;
    }

    private void open(CommandSender sender, String[] args) {
        if (args.length < 2) {
            say(sender, "/ui open <page> [player]", NamedTextColor.RED);
            return;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (target == null) {
            say(sender, args.length > 2 ? "No player called " + args[2] + "." : "Name a player.", NamedTextColor.RED);
            return;
        }
        if (target != sender && !sender.hasPermission("uiengine.open.others")) {
            say(sender, "You can't open pages for other players.", NamedTextColor.RED);
            return;
        }
        if (!sender.hasPermission("uiengine.open")) {
            say(sender, "You can't open pages.", NamedTextColor.RED);
            return;
        }
        String page = args[1];
        if (!plugin.pages().exists(page)) {
            say(sender, "There is no page called '" + page + "' in " + plugin.pages().root().getFileName() + "/.", NamedTextColor.RED);
            return;
        }
        try {
            if (!plugin.screens().open(target, page)) say(sender, "Could not open '" + page + "': see the console.", NamedTextColor.RED);
        } catch (IllegalStateException e) {
            say(sender, e.getMessage(), NamedTextColor.RED);
        }
    }

    private void place(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uiengine.admin")) {
            say(sender, "You can't do that.", NamedTextColor.RED);
            return;
        }
        // From the console (or a script): /ui place <page> <cols> <rows> <name> <x> <y> <z> <facing> [world] [shared]
        if (args.length >= 9) {
            placeAt(sender, args);
            return;
        }
        if (!(sender instanceof Player p) || args.length < 2) {
            say(sender, "As a player, looking at a wall: /ui place <page> [cols] [rows] [name] [shared]"
                    + " (or anywhere: /ui place <page> <cols> <rows> <name> <x> <y> <z> <facing> [world] [shared])", NamedTextColor.RED);
            return;
        }
        String page = args[1];
        if (!plugin.pages().exists(page)) {
            say(sender, "There is no page called '" + page + "'.", NamedTextColor.RED);
            return;
        }
        int cols = number(args, 2, 4), rows = number(args, 3, 3);
        String name = args.length > 4 ? args[4] : plugin.pages().normalise(page).replace('/', '-') + "-" + (plugin.screens().walls().size() + 1);
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
        if (!sender.hasPermission("uiengine.admin")) {
            say(sender, "You can't do that.", NamedTextColor.RED);
            return;
        }
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
            say(sender, "Name the screen, or look at it: /ui remove [name]", NamedTextColor.RED);
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
        if (args.length == 1) return filter(List.of("open", "close", "place", "remove", "list", "reload"), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
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

package ai.resourcepack.screens;

import ai.resourcepack.screens.api.Canvas;
import ai.resourcepack.screens.api.Click;
import ai.resourcepack.screens.api.Pointer;
import ai.resourcepack.screens.api.View;
import ai.resourcepack.screens.api.ViewContent;
import ai.resourcepack.screens.api.ViewOptions;
import ai.resourcepack.screens.api.WallContent;
import ai.resourcepack.screens.api.WallScreen;
import ai.resourcepack.screens.world.MapWall;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /screens test wall [cols] [rows] · test view [cols] [rows] · fov &lt;degrees&gt; · pack · stats · clear
 *
 * <p>The test picture is drawn with plain Java2D: a gradient, some text and one button that lights up
 * under the pointer and counts clicks. It is how to see the engine working without any other plugin.
 */
final class ScreensCommand implements TabExecutor {
    private final ScreenEnginePlugin plugin;
    private final List<WallScreen> testWalls = new ArrayList<>();

    ScreensCommand(ScreenEnginePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "test" -> test(sender, args);
            case "fov" -> {
                if (!(sender instanceof Player p)) return say(sender, "Only players have a field of view.", NamedTextColor.RED);
                if (args.length < 2) return say(sender, "Your FOV here is " + plugin.views().fovOf(p) + ". Set it with /screens fov <degrees> to match your video settings.", NamedTextColor.GRAY);
                try {
                    int fov = Integer.parseInt(args[1]);
                    if (fov < 30 || fov > 110) throw new NumberFormatException();
                    plugin.views().setFov(p, fov);
                    say(sender, "Views now fit a " + fov + "° field of view.", NamedTextColor.GREEN);
                } catch (NumberFormatException e) {
                    say(sender, "FOV is a whole number from 30 to 110.", NamedTextColor.RED);
                }
            }
            case "pack" -> {
                if (sender instanceof Player p && plugin.pack() != null) plugin.pack().send(p);
            }
            case "stats" -> say(sender, String.format(Locale.ROOT, "%d wall(s); %.1f MB of map data sent.",
                    plugin.walls().all().size(), plugin.bytesSent() / 1e6), NamedTextColor.GRAY);
            case "debug" -> debug(sender, args);
            case "clear" -> {
                testWalls.forEach(WallScreen::remove);
                say(sender, "Removed " + testWalls.size() + " test wall(s).", NamedTextColor.GREEN);
                testWalls.clear();
            }
            default -> say(sender, "/screens test wall|view [cols] [rows] · fov <degrees> · pack · stats · clear", NamedTextColor.GRAY);
        }
        return true;
    }

    private void test(CommandSender sender, String[] args) {
        // A fifth argument names the player, so the console (and a test harness) can run it.
        Player p = args.length > 4 ? org.bukkit.Bukkit.getPlayerExact(args[4]) : sender instanceof Player self ? self : null;
        if (p == null) {
            say(sender, "Run this as a player, or name one: /screens test wall|view <cols> <rows> <player>.", NamedTextColor.RED);
            return;
        }
        boolean view = args.length > 1 && args[1].equalsIgnoreCase("view");
        int cols = parse(args, 2, view ? 8 : 4), rows = parse(args, 3, view ? 5 : 3);
        Demo demo = new Demo(plugin.canvas(cols, rows));
        if (view) {
            try {
                plugin.openView(p, demo, ViewOptions.DEFAULT);
            } catch (IllegalStateException e) {
                say(sender, e.getMessage(), NamedTextColor.RED);
            }
            return;
        }
        Block block = p.getTargetBlockExact(12);
        BlockFace face = p.getTargetBlockFace(12);
        if (block == null || face == null || !MapWall.HORIZONTAL.contains(face)) {
            say(sender, "Look at the side of a wall: that block becomes the top-left corner.", NamedTextColor.RED);
            return;
        }
        testWalls.add(plugin.placeWall(block.getRelative(face).getLocation(), face, cols, rows, demo));
        say(sender, "Placed a " + cols + "x" + rows + " test screen. Look at it and click the button.", NamedTextColor.GREEN);
    }

    /**
     * /screens debug &lt;player&gt; point &lt;x&gt; &lt;y&gt; | click [right] | scroll &lt;notches&gt; | close: drive a
     * player's view from the console, for testing without anybody at the mouse.
     */
    private void debug(CommandSender sender, String[] args) {
        Player p = args.length > 1 ? org.bukkit.Bukkit.getPlayerExact(args[1]) : null;
        View view = p == null ? null : plugin.viewOf(p);
        if (view == null || args.length < 3) {
            say(sender, "/screens debug <player> point <x> <y> | click [right] | scroll <n> | close (the player needs an open view)", NamedTextColor.RED);
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "point" -> plugin.views().debugPoint(p, parse(args, 3, 0, 1 << 14), parse(args, 4, 0, 1 << 14));
            case "click" -> view.content().click(view, view.pointerX(), view.pointerY(), args.length > 3 ? Click.RIGHT : Click.LEFT);
            case "scroll" -> view.content().scroll(view, view.pointerX(), view.pointerY(), parse(args, 3, 1, 64));
            case "close" -> view.close();
            default -> say(sender, "point, click, scroll or close", NamedTextColor.RED);
        }
        say(sender, "pointer " + view.pointerX() + "," + view.pointerY() + " shown=" + view.shown(), NamedTextColor.GRAY);
    }

    private static int parse(String[] args, int i, int fallback, int max) {
        try {
            return args.length > i ? Math.max(-max, Math.min(max, Integer.parseInt(args[i]))) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parse(String[] args, int i, int fallback) {
        try {
            return args.length > i ? Math.max(1, Math.min(16, Integer.parseInt(args[i]))) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean say(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("test", "fov", "pack", "stats", "clear", "debug");
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) return List.of("wall", "view");
        return List.of();
    }

    /** The test picture: shared by every viewer, so one player's hover shows for everyone. */
    private static final class Demo implements WallContent, ViewContent {
        private final Canvas canvas;
        private final Rectangle button;
        private boolean hover;
        private int clicks;

        Demo(Canvas canvas) {
            this.canvas = canvas;
            int w = canvas.width(), h = canvas.height();
            button = new Rectangle(w / 2 - w / 6, h * 3 / 5, w / 3, h / 7);
            draw();
        }

        private void draw() {
            int w = canvas.width(), h = canvas.height();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x1E3A8A), w, h, new Color(0x9333EA)));
            g.fillRect(0, 0, w, h);
            // A strip of pure hues to judge the colour encoding by.
            for (int x = 0; x < w; x++) {
                g.setColor(Color.getHSBColor(x / (float) w, 1f, 1f));
                g.fillRect(x, h / 3, 1, h / 20);
            }
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h / 9));
            centre(g, "Screen Engine", w / 2, h / 4);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, h / 22));
            centre(g, canvas.cols() + " x " + canvas.rows() + " maps, " + w + " x " + h + " pixels", w / 2, h / 2);
            g.setColor(hover ? new Color(0x22C55E) : new Color(0x16A34A));
            g.fillRoundRect(button.x, button.y, button.width, button.height, h / 30, h / 30);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h / 20));
            centre(g, clicks == 0 ? "Click me" : "Clicked " + clicks + (clicks == 1 ? " time" : " times"), button.x + button.width / 2, button.y + button.height / 2 + h / 60);
            g.dispose();
            canvas.draw(img, 0, 0);
        }

        private static void centre(Graphics2D g, String text, int x, int y) {
            g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, y);
        }

        private void hover(boolean now) {
            if (now == hover) return;
            hover = now;
            draw();
        }

        @Override public Canvas canvas(Player viewer) { return canvas; }
        @Override public Canvas canvas() { return canvas; }
        @Override public void look(Player viewer, int x, int y) { hover(button.contains(x, y)); }
        @Override public void lookAway(Player viewer) { hover(false); }

        @Override
        public void click(Player viewer, int x, int y, Click click) {
            if (!button.contains(x, y)) return;
            clicks++;
            draw();
        }

        @Override
        public void pointer(View view, int x, int y) {
            boolean on = button.contains(x, y);
            view.pointer(on ? Pointer.HAND : Pointer.ARROW);
            hover(on);
        }

        @Override
        public void click(View view, int x, int y, Click click) {
            click(view.player(), x, y, click);
        }
    }
}

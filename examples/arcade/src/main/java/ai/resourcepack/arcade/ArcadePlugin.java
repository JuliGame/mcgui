package ai.resourcepack.arcade;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Click;
import ai.resourcepack.uiframework.api.CloseReason;
import ai.resourcepack.uiframework.api.Key;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.uiframework.api.UIFramework;
import ai.resourcepack.uiframework.api.View;
import ai.resourcepack.uiframework.api.ViewContent;
import ai.resourcepack.uiframework.api.ViewOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Neon Breakout: an example of a plugin built on UI Framework. {@code /arcade play} sits you at a game of
 * your own; {@code /arcade cabinet} puts one up on a wall for anybody to walk up to.
 */
public final class ArcadePlugin extends JavaPlugin {
    private static final TextColor PINK = TextColor.color(0xFF2E88), GREY = TextColor.color(0xB9B3C9);
    private static final List<String> SUBCOMMANDS = List.of("play", "cabinet", "remove", "scores");
    private static final Set<BlockFace> WALL = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private UIFramework ui;
    private Loop loop;
    private Scores scores;
    private final Map<String, Cabinet> cabinets = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        ui = UIFramework.get();
        scores = new Scores(new File(getDataFolder(), "scores.yml"));
        loop = new Loop(getLogger());
        loop.start();
        // Walls are not saved with the world: put the cabinets back once every world has loaded.
        Bukkit.getScheduler().runTask(this, this::loadCabinets);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            View v = ui.viewOf(p);
            if (v != null && v.content() instanceof Seat) v.close();
        }
        for (Cabinet c : cabinets.values()) if (c.screen != null) c.screen.remove();
        loop.stop();
    }

    // ---- Playing ----

    /** What a seated player drives: their pointer moves the paddle, a click (or jump) launches the ball. */
    private final class Seat implements ViewContent {
        final Game game;
        final Cabinet cabinet;

        Seat(Game game, Cabinet cabinet) {
            this.game = game;
            this.cabinet = cabinet;
        }

        @Override
        public Canvas canvas() {
            return game.canvas;
        }

        @Override
        public void pointer(View view, int x, int y) {
            game.aim = x / game.scale;
        }

        @Override
        public void click(View view, int x, int y, Click click) {
            game.press();
        }

        @Override
        public void key(View view, Key key, boolean down) {
            if (down && key == Key.JUMP) game.press();
        }

        @Override
        public void closed(View view, CloseReason reason) {
            if (cabinet == null) {
                loop.remove(game);
            } else if (view.player().getUniqueId().equals(cabinet.player)) {
                cabinet.player = null;
                game.unseat();
            }
        }
    }

    private void play(Player p) {
        Game game = new Game(ui.canvas(16, 9), host(p, null));
        loop.add(game);
        try {
            open(p, new Seat(game, null));
            game.seat(p.getName());
        } catch (IllegalStateException e) {
            loop.remove(game);
            say(p, e.getMessage(), NamedTextColor.RED);
        }
    }

    /** [p] clicked [cabinet]: sit them down at it, unless somebody already is. */
    void sit(Player p, Cabinet cabinet) {
        if (!p.hasPermission("arcade.play")) return;
        if (cabinet.player != null) {
            Player at = Bukkit.getPlayer(cabinet.player);
            if (at != null && at.isOnline() && ui.viewOf(at) != null) {
                if (!at.equals(p)) say(p, at.getName() + " is playing. Watch, and wait your turn.", GREY);
                return;
            }
        }
        try {
            open(p, new Seat(cabinet.game, cabinet));
            cabinet.player = p.getUniqueId();
            cabinet.game.seat(p.getName());
        } catch (IllegalStateException e) {
            say(p, e.getMessage(), NamedTextColor.RED);
        }
    }

    private void open(Player p, Seat seat) {
        View view = ui.openView(p, seat, ViewOptions.DEFAULT);
        // The paddle is the pointer.
        view.pointer(Pointer.HIDDEN);
    }

    /** Where a game's sounds go: to its player, and for a cabinet to everybody round it too. */
    private Game.Host host(Player solo, Cabinet[] cabinet) {
        return new Game.Host() {
            @Override
            public void sound(Game.Sfx sfx, float pitch) {
                Sound sound = switch (sfx) {
                    case START -> Sound.BLOCK_NOTE_BLOCK_CHIME;
                    case LAUNCH, PADDLE, BRICK -> Sound.BLOCK_NOTE_BLOCK_BIT;
                    case METAL -> Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE;
                    case LOSE -> Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
                    case CLEAR -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
                    case OVER -> Sound.BLOCK_BEACON_DEACTIVATE;
                    case POWER -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                };
                float volume = sfx == Game.Sfx.PADDLE ? 0.7f : 0.9f;
                ui.serverThread().execute(() -> {
                    if (cabinet != null && cabinet[0] != null) {
                        Cabinet c = cabinet[0];
                        Location at = c.centre();
                        at.getWorld().playSound(at, sound, SoundCategory.BLOCKS, volume * 1.5f, pitch);
                        Player p = c.player == null ? null : Bukkit.getPlayer(c.player);
                        // Seated far from the cabinet, they would not hear it from there.
                        if (p != null && (p.getWorld() != at.getWorld() || p.getLocation().distanceSquared(at) > 20 * 20)) {
                            p.playSound(p.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
                        }
                    } else if (solo != null && solo.isOnline()) {
                        solo.playSound(solo.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
                    }
                });
            }

            @Override
            public int finished(String player, int score) {
                int place = scores.submit(player, score);
                if (place > 0) {
                    ui.serverThread().execute(() -> {
                        try {
                            scores.save();
                        } catch (IOException e) {
                            getLogger().log(Level.WARNING, "Could not save scores.yml", e);
                        }
                        if (place == 1) {
                            Bukkit.broadcast(Component.text("★ ", PINK)
                                    .append(Component.text(player, NamedTextColor.WHITE))
                                    .append(Component.text(" set a new Neon Breakout high score: ", GREY))
                                    .append(Component.text(score, NamedTextColor.GOLD)));
                        }
                    });
                }
                return place;
            }

            @Override
            public List<Scores.Entry> top() {
                return scores.top();
            }
        };
    }

    // ---- Cabinets ----

    private Cabinet putUp(String id, Location topLeft, BlockFace facing, int cols, int rows) {
        Cabinet[] self = new Cabinet[1];
        Game game = new Game(ui.canvas(cols, rows), host(null, self));
        Cabinet c = new Cabinet(this, id, topLeft, facing, cols, rows, game);
        self[0] = c;
        c.screen = ui.placeWall(topLeft, facing, cols, rows, c);
        cabinets.put(id, c);
        loop.add(game);
        return c;
    }

    private void takeDown(Cabinet c) {
        if (c.player != null) {
            Player p = Bukkit.getPlayer(c.player);
            View v = p == null ? null : ui.viewOf(p);
            if (v != null && v.content() instanceof Seat s && s.cabinet == c) v.close();
        }
        c.screen.remove();
        loop.remove(c.game);
        cabinets.remove(c.id);
    }

    private File cabinetsFile() {
        return new File(getDataFolder(), "cabinets.yml");
    }

    private void loadCabinets() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cabinetsFile());
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) {
                getLogger().warning("Cabinet " + id + " is in a world that is not loaded: " + s.getString("world"));
                continue;
            }
            try {
                putUp(id, new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z")),
                        BlockFace.valueOf(s.getString("facing", "NORTH")), s.getInt("columns", 12), s.getInt("rows", 7));
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not put cabinet " + id + " back up", e);
            }
        }
    }

    private void saveCabinets() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Cabinet c : cabinets.values()) {
            ConfigurationSection s = yaml.createSection(c.id);
            s.set("world", c.topLeft.getWorld().getName());
            s.set("x", c.topLeft.getBlockX());
            s.set("y", c.topLeft.getBlockY());
            s.set("z", c.topLeft.getBlockZ());
            s.set("facing", c.facing.name());
            s.set("columns", c.cols);
            s.set("rows", c.rows);
        }
        try {
            yaml.save(cabinetsFile());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save cabinets.yml", e);
        }
    }

    // ---- Commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "play" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            say(sender, "/arcade play | cabinet [cols] [rows] | remove | scores", GREY);
            return true;
        }
        if (!sender.hasPermission("arcade." + sub)) {
            say(sender, "You can't do that.", NamedTextColor.RED);
            return true;
        }
        if (sub.equals("scores")) {
            List<Scores.Entry> top = scores.top();
            if (top.isEmpty()) say(sender, "No scores yet. Be the first!", GREY);
            for (int i = 0; i < top.size(); i++) {
                Scores.Entry e = top.get(i);
                sender.sendMessage(Component.text(String.format(" %2d. ", i + 1), PINK)
                        .append(Component.text(e.name() + " ", NamedTextColor.WHITE))
                        .append(Component.text(e.score(), NamedTextColor.GOLD)));
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            say(sender, "Only a player can do that.", NamedTextColor.RED);
            return true;
        }
        switch (sub) {
            case "play" -> play(p);
            case "cabinet" -> {
                int cols, rows;
                try {
                    cols = args.length > 1 ? Integer.parseInt(args[1]) : 12;
                    rows = args.length > 2 ? Integer.parseInt(args[2]) : 7;
                } catch (NumberFormatException e) {
                    say(p, "/arcade cabinet [cols] [rows]", GREY);
                    return true;
                }
                if (cols < 4 || rows < 3 || cols > 32 || rows > 18) {
                    say(p, "A cabinet is 4 to 32 maps wide and 3 to 18 tall.", NamedTextColor.RED);
                    return true;
                }
                Block block = p.getTargetBlockExact(16);
                BlockFace face = p.getTargetBlockFace(16);
                if (block == null || face == null || !WALL.contains(face)) {
                    say(p, "Look at a wall: the block you look at is the screen's top-left corner.", NamedTextColor.RED);
                    return true;
                }
                String id = "cabinet-" + UUID.randomUUID().toString().substring(0, 8);
                putUp(id, block.getRelative(face).getLocation(), face, cols, rows);
                saveCabinets();
                say(p, "Cabinet up. Click it to play; everybody near it can watch.", NamedTextColor.GREEN);
            }
            case "remove" -> {
                Block block = p.getTargetBlockExact(16);
                BlockFace face = p.getTargetBlockFace(16);
                Cabinet hit = null;
                if (block != null && face != null) {
                    Location l = block.getRelative(face).getLocation();
                    for (Cabinet c : cabinets.values()) if (c.covers(l) || c.covers(block.getLocation())) hit = c;
                }
                if (hit == null) {
                    say(p, "Look at a cabinet to take it down.", NamedTextColor.RED);
                    return true;
                }
                takeDown(hit);
                saveCabinets();
                say(p, "Cabinet taken down.", NamedTextColor.GREEN);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("arcade." + s)) out.add(s);
        }
        return out;
    }

    private static void say(CommandSender to, String text, TextColor colour) {
        to.sendMessage(Component.text("Arcade ", PINK).append(Component.text(text, colour)));
    }
}

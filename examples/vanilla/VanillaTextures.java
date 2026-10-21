import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies the game's own textures that the vanilla example pages use out of a Minecraft client jar
 * (which is Mojang's, so this repository cannot ship them) into [pages]/vanilla/.
 *
 * <pre>java VanillaTextures.java path/to/versions/26.3/26.3.jar plugins/UIEngine/pages</pre>
 *
 * Small textures are copied as they are: UI Engine draws pixel art 64 pixels or smaller scaled up without
 * smoothing. The few bigger ones (the book page, the experience bar) are scaled up here, pixel for pixel,
 * to the size the pages draw them at, so they stay just as sharp.
 */
public class VanillaTextures {
    private static final String T = "assets/minecraft/textures/";

    private static final List<String> COPY = List.of(
            "gui/header_separator.png", "gui/footer_separator.png",
            "gui/sprites/server_list/ping_1.png", "gui/sprites/server_list/ping_2.png", "gui/sprites/server_list/ping_3.png",
            "gui/sprites/server_list/ping_4.png", "gui/sprites/server_list/ping_5.png",
            "gui/sprites/world_list/join.png", "gui/sprites/world_list/join_highlighted.png",
            "gui/sprites/widget/checkbox.png", "gui/sprites/widget/checkbox_selected.png",
            "gui/sprites/widget/page_forward.png", "gui/sprites/widget/page_forward_highlighted.png",
            "gui/sprites/widget/page_backward.png", "gui/sprites/widget/page_backward_highlighted.png",
            "gui/sprites/icon/checkmark.png",
            "block/grass_block_side.png", "block/crafting_table_front.png", "block/tnt_side.png", "block/end_stone_bricks.png",
            "block/bookshelf.png", "block/diamond_ore.png", "block/oak_log.png",
            "item/diamond_sword.png", "item/diamond_pickaxe.png", "item/bow.png", "item/compass_00.png", "item/map.png",
            "item/writable_book.png", "item/emerald.png", "item/diamond.png", "item/iron_ingot.png", "item/gold_ingot.png",
            "item/experience_bottle.png", "item/golden_apple.png", "item/ender_pearl.png", "item/cooked_beef.png",
            "item/wheat.png", "item/totem_of_undying.png", "item/elytra.png", "item/name_tag.png", "item/cake.png",
            "item/nether_star.png", "item/spyglass.png", "item/firework_rocket.png", "item/oak_boat.png", "item/fishing_rod.png",
            "item/iron_pickaxe.png", "item/wooden_pickaxe.png", "item/bread.png", "item/clock_00.png");

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("java VanillaTextures.java <minecraft client jar> <UI Engine pages folder>");
            System.exit(2);
        }
        Path out = Path.of(args[1], "vanilla");
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String p : COPY) {
                Path to = out.resolve(p.substring(p.lastIndexOf('/') + 1));
                Files.createDirectories(to.getParent());
                try (InputStream in = open(jar, p)) {
                    Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            panorama(Path.of(args[0]), out.resolve("panorama_0.png"));
            // The book GUI's page, binding on the left: the right-hand page of a spread, and mirrored, the left.
            BufferedImage book = read(jar, "gui/book.png").getSubimage(20, 1, 146, 180);
            write(scale(book, 4, false), out.resolve("book_right.png"));
            write(scale(book, 4, true), out.resolve("book_left.png"));
            // The experience bar, shortened to the width of a book's line (both rounded ends kept).
            write(scale(shorten(read(jar, "gui/sprites/hud/experience_bar_background.png"), 114), 4, false), out.resolve("xp_background.png"));
            write(scale(shorten(read(jar, "gui/sprites/hud/experience_bar_progress.png"), 114), 4, false), out.resolve("xp_progress.png"));
            // And every item, block, GUI and effect texture, under its own path (vanilla/item/apple.png,
            // vanilla/gui/sprites/widget/button.png), for pages that want more than the list above.
            // Anything bigger than 64 pixels a side is stored 4x, pixel for pixel, so it stays sharp too:
            // a page always gives a texture's size itself (2 CSS pixels a texture pixel), never its file's.
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith(T) || !name.endsWith(".png")) continue;
                String rel = name.substring(T.length());
                if (!(rel.startsWith("item/") || rel.startsWith("block/") || rel.startsWith("gui/") || rel.startsWith("mob_effect/")
                        || rel.startsWith("map/"))) continue;
                Path to = out.resolve(rel);
                Files.createDirectories(to.getParent());
                BufferedImage img;
                try (InputStream in = jar.getInputStream(e)) {
                    img = ImageIO.read(in);
                }
                if (img == null) continue;
                if (img.getWidth() > 64 || img.getHeight() > 64) write(scale(img, 4, false), to);
                else try (InputStream in = jar.getInputStream(e)) {
                    Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        System.out.println("Wrote the vanilla textures to " + out);
    }

    /**
     * The title screen's panorama is not in the jar (the jar's copy is a 1-pixel stand-in): the launcher keeps
     * it with the game's other assets, by hash. Found from the jar's place: versions/X/X.jar, its X.json naming
     * the asset index, and assets/indexes/<index>.json the file.
     */
    private static void panorama(Path jar, Path to) throws IOException {
        Path version = jar.getParent(), home = version.getParent().getParent();
        Path json = version.resolve(version.getFileName() + ".json");
        var index = java.util.regex.Pattern.compile("\"assetIndex\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(Files.exists(json) ? Files.readString(json) : "");
        if (index.find()) {
            Path indexFile = home.resolve("assets/indexes/" + index.group(1) + ".json");
            var hash = java.util.regex.Pattern.compile("\"minecraft/textures/gui/title/background/panorama_0.png\"\\s*:\\s*\\{\\s*\"hash\"\\s*:\\s*\"([0-9a-f]{40})\"")
                    .matcher(Files.exists(indexFile) ? Files.readString(indexFile) : "");
            if (hash.find()) {
                Path object = home.resolve("assets/objects/" + hash.group(1).substring(0, 2) + "/" + hash.group(1));
                if (Files.exists(object)) {
                    Files.copy(object, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        System.out.println("No panorama found beside the jar (run the game once from this launcher folder): the menus will be plain dark grey.");
    }

    private static InputStream open(ZipFile jar, String path) throws IOException {
        ZipEntry e = jar.getEntry(T + path);
        if (e == null) throw new IOException("Not in this jar: " + T + path + " (is it a client jar, 26.1 or newer?)");
        return jar.getInputStream(e);
    }

    private static BufferedImage read(ZipFile jar, String path) throws IOException {
        try (InputStream in = open(jar, path)) {
            return ImageIO.read(in);
        }
    }

    /** [img] cut to [w] pixels wide: its left half and its right half, the middle left out. */
    private static BufferedImage shorten(BufferedImage img, int w) {
        BufferedImage out = new BufferedImage(w, img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img.getSubimage(0, 0, w / 2, img.getHeight()), 0, 0, null);
        g.drawImage(img.getSubimage(img.getWidth() - (w - w / 2), 0, w - w / 2, img.getHeight()), w / 2, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scale(BufferedImage img, int k, boolean mirror) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (mirror) g.drawImage(img, w * k, 0, -w * k, h * k, null);
        else g.drawImage(img, 0, 0, w * k, h * k, null);
        g.dispose();
        return out;
    }

    private static void write(BufferedImage img, Path to) throws IOException {
        ImageIO.write(img, "png", to.toFile());
    }
}

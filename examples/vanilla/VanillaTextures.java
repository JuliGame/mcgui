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
 * <pre>java VanillaTextures.java path/to/versions/26.3/26.3.jar plugins/MCGUI/pages</pre>
 *
 * Small textures are copied as they are: MC GUI draws pixel art 64 pixels or smaller scaled up without
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
            System.err.println("java VanillaTextures.java <minecraft client jar> <MC GUI pages folder>");
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
            GameFont.build(jar, out);
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

    /**
     * The game's own font as TrueType files MC GUI can load (vanilla/minecraft.ttf and
     * vanilla/minecraft_bold.ttf), built from the bitmap glyph sheets and the font definitions in the jar:
     * every glyph pixel for pixel, each one's width worked out as the game works it out (its rightmost
     * drawn column, plus one pixel of spacing), and bold done the game's way, the glyph drawn again one
     * pixel to the right. 8 game pixels to the em, so at font-size 16px one game pixel is 2 CSS pixels.
     */
    static final class GameFont {
        private static final int UNIT = 128, EM = 8 * UNIT;

        /** A glyph: its advance and its pixels as rectangles {x0, top, x1, bottom} in font units, y up from the baseline. */
        record Glyph(int advance, List<int[]> rects) {}

        static void build(ZipFile jar, Path out) throws IOException {
            java.util.TreeMap<Integer, Glyph> glyphs = new java.util.TreeMap<>();
            providers(jar, "minecraft:default", glyphs);
            Files.write(out.resolve("minecraft.ttf"), ttf(glyphs, false));
            java.util.TreeMap<Integer, Glyph> bold = new java.util.TreeMap<>();
            glyphs.forEach((cp, g) -> {
                List<int[]> rects = new java.util.ArrayList<>(g.rects());
                for (int[] r : g.rects()) rects.add(new int[]{r[0] + UNIT, r[1], r[2] + UNIT, r[3]});
                bold.put(cp, new Glyph(g.advance() + (g.rects().isEmpty() ? 0 : UNIT), rects));
            });
            Files.write(out.resolve("minecraft_bold.ttf"), ttf(bold, true));
        }

        /** Every glyph a font definition gives, in its order: the first provider to have a character wins, as in the game. */
        @SuppressWarnings("unchecked")
        private static void providers(ZipFile jar, String id, java.util.Map<Integer, Glyph> glyphs) throws IOException {
            String[] ns = id.split(":", 2);
            ZipEntry e = jar.getEntry("assets/" + ns[0] + "/font/" + ns[1] + ".json");
            if (e == null) return;
            java.util.Map<String, Object> def;
            try (InputStream in = jar.getInputStream(e)) {
                def = (java.util.Map<String, Object>) new Json(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).value();
            }
            for (Object o : (List<Object>) def.get("providers")) {
                java.util.Map<String, Object> p = (java.util.Map<String, Object>) o;
                switch ((String) p.get("type")) {
                    case "reference" -> providers(jar, (String) p.get("id"), glyphs);
                    case "space" -> ((java.util.Map<String, Object>) p.get("advances")).forEach((ch, adv) ->
                            glyphs.putIfAbsent(ch.codePointAt(0), new Glyph((int) Math.round(((Number) adv).doubleValue() * UNIT), List.of())));
                    case "bitmap" -> bitmap(jar, p, glyphs);
                    default -> {} // unihex (the fallback for every other script) is left to MC GUI's own fallbacks
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static void bitmap(ZipFile jar, java.util.Map<String, Object> p, java.util.Map<Integer, Glyph> glyphs) throws IOException {
            String[] file = ((String) p.get("file")).split(":", 2);
            ZipEntry e = jar.getEntry("assets/" + file[0] + "/textures/" + file[1]);
            if (e == null) return;
            BufferedImage img;
            try (InputStream in = jar.getInputStream(e)) {
                img = ImageIO.read(in);
            }
            List<String> rows = (List<String>) p.get("chars");
            int cols = rows.get(0).codePointCount(0, rows.get(0).length());
            int cw = img.getWidth() / cols, ch = img.getHeight() / rows.size();
            double height = p.containsKey("height") ? ((Number) p.get("height")).doubleValue() : 8;
            double ascent = ((Number) p.get("ascent")).doubleValue();
            double scale = height / ch;
            for (int r = 0; r < rows.size(); r++) {
                int[] cps = rows.get(r).codePoints().toArray();
                for (int c = 0; c < cps.length; c++) {
                    int cp = cps[c];
                    if (cp == 0 || cp == ' ' || glyphs.containsKey(cp)) continue;
                    int x0 = c * cw, y0 = r * ch;
                    int width = 0;
                    for (int x = cw - 1; x >= 0 && width == 0; x--)
                        for (int y = 0; y < ch; y++) if ((img.getRGB(x0 + x, y0 + y) >>> 24) != 0) { width = x + 1; break; }
                    // Pixel rows as runs, each run joined to the one above it when they line up.
                    List<int[]> runs = new java.util.ArrayList<>();
                    List<int[]> open = new java.util.ArrayList<>();
                    for (int y = 0; y < ch; y++) {
                        List<int[]> now = new java.util.ArrayList<>();
                        for (int x = 0; x < cw; ) {
                            if ((img.getRGB(x0 + x, y0 + y) >>> 24) == 0) { x++; continue; }
                            int start = x;
                            while (x < cw && (img.getRGB(x0 + x, y0 + y) >>> 24) != 0) x++;
                            int[] above = null;
                            for (int[] o : open) if (o[0] == start && o[2] == x) above = o;
                            if (above != null) { above[3] = y + 1; now.add(above); }
                            else { int[] run = {start, y, x, y + 1}; runs.add(run); now.add(run); }
                        }
                        open = now;
                    }
                    List<int[]> rects = new java.util.ArrayList<>();
                    for (int[] run : runs) {
                        rects.add(new int[]{(int) Math.round(run[0] * scale * UNIT), (int) Math.round((ascent - run[1] * scale) * UNIT),
                                (int) Math.round(run[2] * scale * UNIT), (int) Math.round((ascent - run[3] * scale) * UNIT)});
                    }
                    glyphs.put(cp, new Glyph(((int) (0.5 + width * scale) + 1) * UNIT, rects));
                }
            }
        }

        // ---- TrueType ----

        private static byte[] ttf(java.util.TreeMap<Integer, Glyph> glyphs, boolean bold) throws IOException {
            List<Integer> cps = new java.util.ArrayList<>();
            for (int cp : glyphs.keySet()) if (cp <= 0xFFFF) cps.add(cp);
            List<Glyph> order = new java.util.ArrayList<>();
            order.add(new Glyph(4 * UNIT, List.of())); // .notdef
            for (int cp : cps) order.add(glyphs.get(cp));
            int n = order.size();

            Bytes glyf = new Bytes(), loca = new Bytes(), hmtx = new Bytes();
            int xMin = 0, yMin = 0, xMax = 0, yMax = 0, maxPoints = 0, maxContours = 0, maxAdvance = 0;
            for (Glyph g : order) {
                loca.u32(glyf.size());
                int lsb = 0;
                if (!g.rects().isEmpty()) {
                    int gx0 = Integer.MAX_VALUE, gy0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE, gy1 = Integer.MIN_VALUE;
                    for (int[] r : g.rects()) {
                        gx0 = Math.min(gx0, r[0]); gx1 = Math.max(gx1, r[2]);
                        gy0 = Math.min(gy0, r[3]); gy1 = Math.max(gy1, r[1]);
                    }
                    lsb = gx0;
                    xMin = Math.min(xMin, gx0); yMin = Math.min(yMin, gy0); xMax = Math.max(xMax, gx1); yMax = Math.max(yMax, gy1);
                    int contours = g.rects().size();
                    maxContours = Math.max(maxContours, contours);
                    maxPoints = Math.max(maxPoints, contours * 4);
                    glyf.u16(contours).u16(gx0).u16(gy0).u16(gx1).u16(gy1);
                    for (int i = 0; i < contours; i++) glyf.u16(i * 4 + 3);
                    glyf.u16(0); // no instructions
                    for (int i = 0; i < contours * 4; i++) glyf.u8(1); // on the curve, x and y as 16-bit deltas
                    // Each rectangle clockwise: bottom left, top left, top right, bottom right.
                    int px = 0, py = 0;
                    for (int[] r : g.rects()) for (int x : new int[]{r[0], r[0], r[2], r[2]}) { glyf.u16(x - px); px = x; }
                    for (int[] r : g.rects()) for (int y : new int[]{r[3], r[1], r[1], r[3]}) { glyf.u16(y - py); py = y; }
                    while (glyf.size() % 4 != 0) glyf.u8(0);
                }
                hmtx.u16(g.advance()).u16(lsb);
                maxAdvance = Math.max(maxAdvance, g.advance());
            }
            loca.u32(glyf.size());

            Bytes cmap = new Bytes();
            List<int[]> segs = new java.util.ArrayList<>(); // start, end, glyph of start
            for (int i = 0; i < cps.size(); i++) {
                int cp = cps.get(i), gid = i + 1;
                int[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
                if (last != null && last[1] == cp - 1 && last[2] + (cp - last[0]) == gid) last[1] = cp;
                else segs.add(new int[]{cp, cp, gid});
            }
            segs.add(new int[]{0xFFFF, 0xFFFF, 0});
            int segX2 = segs.size() * 2, sr = 2 * Integer.highestOneBit(segs.size());
            cmap.u16(0).u16(1).u16(3).u16(1).u32(12);
            cmap.u16(4).u16(16 + segs.size() * 8).u16(0).u16(segX2).u16(sr).u16(Integer.numberOfTrailingZeros(sr / 2)).u16(segX2 - sr);
            for (int[] s : segs) cmap.u16(s[1]);
            cmap.u16(0);
            for (int[] s : segs) cmap.u16(s[0]);
            for (int[] s : segs) cmap.u16(s[0] == 0xFFFF ? 1 : (s[2] - s[0]) & 0xFFFF);
            for (int[] s : segs) cmap.u16(0);

            String style = bold ? "Bold" : "Regular";
            String[] names = {null, "Minecraft", style, "Minecraft " + style + " (from the game's own font)", "Minecraft " + style, "1.0", "Minecraft-" + style};
            Bytes name = new Bytes(), strings = new Bytes();
            int records = names.length - 1;
            name.u16(0).u16(records).u16(6 + records * 12);
            for (int id = 1; id < names.length; id++) {
                byte[] s = names[id].getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
                name.u16(3).u16(1).u16(0x409).u16(id).u16(s.length).u16(strings.size());
                strings.bytes(s);
            }
            name.bytes(strings.toArray());

            int ascender = 7 * UNIT, descender = -2 * UNIT;
            Bytes head = new Bytes().u32(0x00010000).u32(0x00010000).u32(0).u32(0x5F0F3CF5).u16(0x000B).u16(EM)
                    .u32(0).u32(0).u32(0).u32(0).u16(xMin).u16(yMin).u16(xMax).u16(yMax).u16(bold ? 1 : 0).u16(8).u16(2).u16(1).u16(0);
            Bytes hhea = new Bytes().u32(0x00010000).u16(ascender).u16(descender).u16(0).u16(maxAdvance).u16(0).u16(0).u16(xMax)
                    .u16(1).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(n);
            Bytes maxp = new Bytes().u32(0x00010000).u16(n).u16(maxPoints).u16(maxContours).u16(0).u16(0).u16(2)
                    .u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0);
            Bytes post = new Bytes().u32(0x00030000).u32(0).u16(-UNIT).u16(UNIT).u32(0).u32(0).u32(0).u32(0).u32(0);
            Bytes os2 = new Bytes().u16(4).u16(6 * UNIT).u16(bold ? 700 : 400).u16(5).u16(0)
                    .u16(5 * UNIT).u16(5 * UNIT).u16(0).u16(UNIT).u16(5 * UNIT).u16(5 * UNIT).u16(0).u16(4 * UNIT).u16(UNIT).u16(3 * UNIT)
                    .u16(0).bytes(new byte[10]).u32(1).u32(0).u32(0).u32(0).bytes("NONE".getBytes())
                    .u16(bold ? 0x20 : 0x40).u16(cps.isEmpty() ? 0 : cps.get(0)).u16(cps.isEmpty() ? 0 : cps.get(cps.size() - 1))
                    .u16(ascender).u16(descender).u16(0).u16(Math.max(ascender, yMax)).u16(Math.max(-descender, -yMin))
                    .u32(1).u32(0).u16(5 * UNIT).u16(7 * UNIT).u16(0).u16(' ').u16(1);

            java.util.TreeMap<String, byte[]> tables = new java.util.TreeMap<>();
            tables.put("OS/2", os2.toArray());
            tables.put("cmap", cmap.toArray());
            tables.put("glyf", glyf.toArray());
            tables.put("head", head.toArray());
            tables.put("hhea", hhea.toArray());
            tables.put("hmtx", hmtx.toArray());
            tables.put("loca", loca.toArray());
            tables.put("maxp", maxp.toArray());
            tables.put("name", name.toArray());
            tables.put("post", post.toArray());
            int count = tables.size(), range = 16 * Integer.highestOneBit(count);
            Bytes font = new Bytes().u32(0x00010000).u16(count).u16(range).u16(Integer.numberOfTrailingZeros(range / 16)).u16(count * 16 - range);
            int offset = 12 + count * 16, headAt = 0;
            for (var t : tables.entrySet()) {
                if (t.getKey().equals("head")) headAt = offset;
                font.bytes(t.getKey().getBytes()).u32((int) checksum(t.getValue())).u32(offset).u32(t.getValue().length);
                offset += (t.getValue().length + 3) & ~3;
            }
            for (byte[] t : tables.values()) {
                font.bytes(t);
                while (font.size() % 4 != 0) font.u8(0);
            }
            byte[] out = font.toArray();
            long adjust = (0xB1B0AFBAL - checksum(out)) & 0xFFFFFFFFL;
            java.nio.ByteBuffer.wrap(out).putInt(headAt + 8, (int) adjust);
            return out;
        }

        private static long checksum(byte[] b) {
            long sum = 0;
            for (int i = 0; i < b.length; i += 4) {
                long word = 0;
                for (int k = 0; k < 4; k++) word = word << 8 | (i + k < b.length ? b[i + k] & 0xFF : 0);
                sum = (sum + word) & 0xFFFFFFFFL;
            }
            return sum;
        }

        /** Big-endian bytes, written in order. */
        private static final class Bytes {
            private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            Bytes u8(int v) { out.write(v); return this; }
            Bytes u16(int v) { out.write(v >> 8); out.write(v); return this; }
            Bytes u32(int v) { u16(v >>> 16); return u16(v); }
            Bytes bytes(byte[] b) { out.writeBytes(b); return this; }
            int size() { return out.size(); }
            byte[] toArray() { return out.toByteArray(); }
        }

        /** Just enough JSON for the game's font definitions. */
        private static final class Json {
            private final String s;
            private int i;

            Json(String s) {
                this.s = s;
            }

            Object value() {
                skip();
                char c = s.charAt(i);
                if (c == '{') {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    i++;
                    for (skip(); s.charAt(i) != '}'; skip()) {
                        String k = string();
                        skip();
                        i++; // :
                        m.put(k, value());
                        skip();
                        if (s.charAt(i) == ',') i++;
                    }
                    i++;
                    return m;
                }
                if (c == '[') {
                    List<Object> l = new java.util.ArrayList<>();
                    i++;
                    for (skip(); s.charAt(i) != ']'; skip()) {
                        l.add(value());
                        skip();
                        if (s.charAt(i) == ',') i++;
                    }
                    i++;
                    return l;
                }
                if (c == '"') return string();
                int start = i;
                while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
                String w = s.substring(start, i);
                return switch (w) {
                    case "true" -> true;
                    case "false" -> false;
                    case "null" -> null;
                    default -> Double.parseDouble(w);
                };
            }

            private String string() {
                StringBuilder b = new StringBuilder();
                i++;
                while (s.charAt(i) != '"') {
                    char c = s.charAt(i++);
                    if (c != '\\') { b.append(c); continue; }
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        default -> b.append(e);
                    }
                }
                i++;
                return b.toString();
            }

            private void skip() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }
        }
    }
}

package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.Repaint;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.net.Wire;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * What a new session of a page costs before and after {@link FirstPictures}, in CPU time (every thread of the
 * process, so the painter and encoder pools count): drawing its first picture and converting it onto a blank
 * canvas, against taking a kept one. Also how a first render of a page splits into style and layout against
 * paint, and what repainting only a viewer's own text over another viewer's picture would cost (the option for
 * pages filled with each viewer's own values), and the map packets a new viewer of a wall is sent.
 *
 * <p>Arguments: page files (default the shipped pages and the long scroll test page); -Pruns=<n>.
 */
public final class FirstPictureBench {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private record Size(String name, int cols, int rows, double height) {}

    private static final Size[] SIZES = {new Size("wall 4x3", 4, 3, 384), new Size("fullscreen 16x9", 16, 9, 576)};

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 20);
        List<Path> files = new ArrayList<>();
        for (String a : args) if (!a.isBlank()) files.add(Path.of(a));
        if (files.isEmpty()) {
            for (String n : new String[]{"menu", "shop", "rules"}) files.add(Path.of("src/main/resources/pages/" + n + ".html"));
            files.add(Path.of("src/test/resources/scroll/long.html"));
        }
        Page.Loader loader = new Page.Loader(false, null);
        System.out.printf("%-10s %-16s %9s %9s %9s %9s %8s %9s %9s %7s %5s%n", "page", "size", "draw ms", "take ms", "fill ms",
                "lay ms", "lay %", "own ms", "own worst", "packets", "after");
        for (Path file : files) {
            Page page = Page.load(file.toAbsolutePath(), loader);
            for (Size size : SIZES) run(page, file.getFileName().toString().replace(".html", ""), size, runs);
        }
    }

    private static void run(Page page, String name, Size size, int runs) {
        int w = size.cols * 128, h = size.rows * 128;
        double scale = h / Screens.designHeight(page, size.height);
        Supplier<Placeholders.Filled> fill = () -> fill(page, "Alex");
        var none = HtmlRenderer.state(null, null);
        // Before: a blank canvas, the page filled, drawn and converted onto it.
        double draw = cpu(runs, () -> {
            Canvas canvas = new Canvas(PALETTE, size.cols, size.rows);
            Placeholders.Filled f = fill.get();
            canvas.draw(HtmlRenderer.render(page, f.doc(), w, h, scale, 0, none, null, null, 0).image(), 0, 0);
        });
        // After: the same blank canvas and fill, and the kept picture taken.
        Placeholders.Filled first = fill.get();
        HtmlRenderer.Frame frame = HtmlRenderer.render(page, first.doc(), w, h, scale, 0, none, null, null, 0);
        Canvas drawn = new Canvas(PALETTE, size.cols, size.rows);
        drawn.draw(frame.image(), 0, 0);
        FirstPictures.Key key = new FirstPictures.Key(name, page, size.cols, size.rows, scale, first.key());
        FirstPictures.Picture picture = FirstPictures.Picture.of(key, frame, drawn.copy());
        double take = cpu(runs, () -> {
            Canvas canvas = new Canvas(PALETTE, size.cols, size.rows);
            Placeholders.Filled f = fill.get();
            if (picture.frameFor(f.doc()) == null) throw new IllegalStateException("not shaped alike");
            canvas.show(picture.canvas);
        });
        double fillMs = cpu(runs, fill::get);
        // Style and layout alone: a render that paints one pixel.
        BufferedImage scratch = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        double lay = cpu(runs, () -> HtmlRenderer.render(page, fill.get().doc(), w, h, scale, 0, none, scratch, new Rectangle2D.Double(0, 0, 1 / scale, 1 / scale), 0));
        double render = cpu(runs, () -> HtmlRenderer.render(page, fill.get().doc(), w, h, scale, 0, none, null, null, 0));
        // A viewer's own values repainted over another's picture: what personal pages could share.
        Placeholders.Filled other = fill(page, "Steve_the_Builder");
        HtmlRenderer.Frame mine = picture.frameFor(other.doc());
        Set<Element> moved = differing(first.doc(), other.doc());
        String own = "-", ownWorst = "-";
        if (!moved.isEmpty() && mine != null) {
            double[] worst = {0};
            boolean[] partial = {true};
            double t = cpu(runs, () -> {
                HtmlRenderer.Frame base = FirstPictures.own(mine);
                Repaint.Result r = Repaint.partial(page, other.doc(), w, h, scale, 0, none, base, moved, 0);
                if (r.frame() == null) partial[0] = false;
            });
            HtmlRenderer.Frame base = FirstPictures.own(mine);
            Repaint.Result r = Repaint.partial(page, other.doc(), w, h, scale, 0, none, base, moved, 0);
            if (r.frame() != null) {
                worst[0] = worst(r.frame(), HtmlRenderer.render(page, other.doc(), w, h, scale, 0, none, null, null, 0));
                own = String.format("%.2f", t);
                ownWorst = String.valueOf((int) worst[0]);
            } else {
                own = "whole:" + r.why();
            }
        }
        Canvas blank = new Canvas(PALETTE, size.cols, size.rows), adopted = new Canvas(PALETTE, size.cols, size.rows);
        adopted.show(picture.canvas);
        int before = packets(blank, frame.image()), after = packets(adopted, null);
        System.out.printf("%-10s %-16s %9.2f %9.3f %9.3f %9.2f %7.0f%% %9s %9s %7d %5d%n", name, size.name, draw, take, fillMs, lay,
                100 * lay / render, own, ownWorst, before, after);
    }

    /** Fills as a server with 3 players would for [player]. */
    private static Placeholders.Filled fill(Page page, String player) {
        UnaryOperator<String> anyone = t -> t.replace("{online}", "3").replace("{max}", "20").replace("{server}", "Paper");
        return Placeholders.fill(page.document, t -> anyone.apply(t).replace("{player}", player).replace("{name}", player), anyone);
    }

    /** Elements whose own text or attributes differ between two copies of a page. */
    private static Set<Element> differing(Document a, Document b) {
        List<Element> x = a.getAllElements(), y = b.getAllElements();
        Set<Element> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < x.size(); i++) {
            Element p = x.get(i), q = y.get(i);
            boolean same = p.ownText().equals(q.ownText());
            for (Attribute at : p.attributes()) same &= at.getValue().equals(q.attr(at.getKey()));
            if (!same) out.add(q);
        }
        return out;
    }

    private static int worst(HtmlRenderer.Frame f, HtmlRenderer.Frame g) {
        int[] a = ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData(), b = ((DataBufferInt) g.image().getRaster().getDataBuffer()).getData();
        int worst = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] == b[i]) continue;
            for (int s = 0; s < 32; s += 8) worst = Math.max(worst, Math.abs((a[i] >> s & 255) - (b[i] >> s & 255)));
        }
        return worst;
    }

    /**
     * CPU ms of [work], every thread of the process: the median of five batches of [runs], each over a whole batch
     * (the process clock ticks in 10 ms here), after a batch unmeasured.
     */
    private static double cpu(int runs, Runnable work) {
        for (int i = 0; i < runs; i++) work.run();
        double[] t = new double[5];
        for (int b = 0; b < t.length; b++) {
            long c0 = OS.getProcessCpuTime();
            for (int i = 0; i < runs; i++) work.run();
            t[b] = (OS.getProcessCpuTime() - c0) / 1e6 / runs;
        }
        Arrays.sort(t);
        return t[t.length / 2];
    }

    // ---------------------------------------------------------------- packets

    /** Passes of the sender until it has sent everything (its byte budget refills with time). */
    private static void drain(MapSender sender, int[] patches) {
        int quiet = 0, last = -1;
        while (quiet < 5) {
            sender.pass();
            quiet = patches[0] == last ? quiet + 1 : 0;
            last = patches[0];
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Patches sent to a client who has nothing, shown [c] and then, if not null, [page] drawn on it. */
    private static int packets(Canvas c, BufferedImage page) {
        int cols = c.cols(), rows = c.rows();
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, cols, rows, 7000);
        int[] patches = {0};
        Wire wire = new Wire() {
            public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) { return mapId; }
            public Object repoint(MapWall wall, int tile, int mapId) { return null; }
            public Frames frames(Player player) { return null; }
            public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
                patches[0] += packets.size();
                done.accept(true);
                return true;
            }
        };
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == a[0];
            default -> null;
        });
        MapSender sender = new MapSender(wire, PALETTE, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(player, wall, c, Canvas.Encoding.HI, null)));
        drain(sender, patches);
        if (page != null) {
            c.draw(page, 0, 0);
            drain(sender, patches);
        }
        return patches[0];
    }
}

package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.nodes.Element;

import javax.imageio.ImageIO;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What styling costs on real pages, and whether a change to the cascade changed any result. For each
 * page (at a fullscreen view's 1024 x 576 CSS px, drawn at 2x), on fresh copies of its document each run:
 * the style pass alone, cold, then again on the same copy with the first element that has a :hover rule
 * hovered, and once more unhovered (in CPU time of the thread, which other work on the machine disturbs
 * least); and in wall time a whole render, cold, then on that same copy hovered and unhovered again (what
 * a session pays on a hover, and on a scroll or a hover ending). Each is the runs after one warm-up. Also
 * a digest of every computed style (elements and their pseudo-elements, in document order) cold, hovered
 * after unhovered, and unhovered again, so two builds can be compared exactly, and optionally the pictures.
 * Args: folders or .html files. Options: {@code -Druns=<n>} (3), {@code -Donly=<substring>},
 * {@code -Dout=<tsv>}, {@code -Ddump=<dir>} (a text dump of every style per page), {@code -Dpng=<dir>}.
 */
public final class StyleBench {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = 2;

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 3);
        String only = System.getProperty("only", "");
        String dump = System.getProperty("dump"), png = System.getProperty("png");
        PrintStream tsv = new PrintStream(Files.newOutputStream(Path.of(System.getProperty("out", "build/stylebench.tsv"))), true, StandardCharsets.UTF_8);
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        tsv.println("page\telements\trules\tstyle_cpu_ms\trestyle_hover_cpu_ms\trestyle_again_cpu_ms\tfull_ms\thover_ms\tagain_ms\tdigest");
        java.lang.management.ThreadMXBean cpu = java.lang.management.ManagementFactory.getThreadMXBean();
        Page.Loader loader = new Page.Loader(false, Path.of("build/torture/cache"));
        for (Path file : pages(args)) {
            String name = file.getFileName().toString().replace(".html", "");
            if (!only.isEmpty() && !name.contains(only)) continue;
            try {
                Page page = Page.load(file, loader);
                int targetAt = -1;
                var all = page.document.getAllElements();
                for (int k = 0; k < all.size(); k++) {
                    if (!page.styles.stateKey(all.get(k), false).isEmpty()) {
                        targetAt = k;
                        break;
                    }
                }
                long[] style = new long[runs], restyleHover = new long[runs], restyleAgain = new long[runs];
                long[] full = new long[runs], hover = new long[runs], again = new long[runs];
                String digest = "";
                for (int i = -1; i < runs; i++) {
                    org.jsoup.nodes.Document a = page.document.clone(), b = page.document.clone();
                    Element target = targetAt < 0 ? null : b.getAllElements().get(targetAt);
                    Selector.State hovered = HtmlRenderer.state(target, null);
                    long c0 = cpu.getCurrentThreadCpuTime();
                    Styler s = new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, Selector.State.NONE, page.uri, RenderBudget.NONE);
                    s.run(root(a));
                    long c1 = cpu.getCurrentThreadCpuTime();
                    new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, HtmlRenderer.state(targetAt < 0 ? null : a.getAllElements().get(targetAt), null), page.uri, RenderBudget.NONE).run(root(a));
                    long c2 = cpu.getCurrentThreadCpuTime();
                    new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, Selector.State.NONE, page.uri, RenderBudget.NONE).run(root(a));
                    long c3 = cpu.getCurrentThreadCpuTime();
                    long t1 = System.nanoTime();
                    HtmlRenderer.Frame f = HtmlRenderer.render(page, b, W, H, SCALE, 0, Selector.State.NONE);
                    long t2 = System.nanoTime();
                    if (target != null) HtmlRenderer.render(page, b, W, H, SCALE, 0, hovered);
                    long t3 = System.nanoTime();
                    HtmlRenderer.render(page, b, W, H, SCALE, 0, Selector.State.NONE);
                    long t4 = System.nanoTime();
                    if (i < 0) {
                        // Cold, then on one copy: unhovered, hovered, unhovered again. Streamed: a big page's text is hundreds of MB.
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        java.io.Writer w = null;
                        if (dump != null) {
                            Files.createDirectories(Path.of(dump));
                            w = Files.newBufferedWriter(Path.of(dump, name + ".txt"));
                        }
                        java.io.Writer text = w;
                        java.util.function.Consumer<String> sink = line -> {
                            md.update(line.getBytes(StandardCharsets.UTF_8));
                            try {
                                if (text != null) text.write(line);
                            } catch (java.io.IOException x) {
                                throw new java.io.UncheckedIOException(x);
                            }
                        };
                        dump(a, s, sink);
                        a = null;
                        s = null;
                        org.jsoup.nodes.Document c = page.document.clone();
                        Element ct = targetAt < 0 ? null : c.getAllElements().get(targetAt);
                        new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, Selector.State.NONE, page.uri, RenderBudget.NONE).run(root(c));
                        Styler h = new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, HtmlRenderer.state(ct, null), page.uri, RenderBudget.NONE);
                        h.run(root(c));
                        sink.accept("---- hovered\n");
                        dump(c, h, sink);
                        h = null;
                        Styler u = new Styler(Page.UA, page.styles, W / SCALE, H / SCALE, Selector.State.NONE, page.uri, RenderBudget.NONE);
                        u.run(root(c));
                        sink.accept("---- unhovered\n");
                        dump(c, u, sink);
                        if (w != null) w.close();
                        digest = HexFormat.of().formatHex(md.digest()).substring(0, 16);
                        if (png != null) {
                            Files.createDirectories(Path.of(png));
                            ImageIO.write(f.image(), "png", Path.of(png, name + ".png").toFile());
                            HtmlRenderer.Frame sc = HtmlRenderer.render(page, b, W, H, SCALE, f.maxScroll() / 2, Selector.State.NONE);
                            ImageIO.write(sc.image(), "png", Path.of(png, name + "-mid.png").toFile());
                        }
                        continue;
                    }
                    style[i] = c1 - c0;
                    restyleHover[i] = c2 - c1;
                    restyleAgain[i] = c3 - c2;
                    full[i] = t2 - t1;
                    hover[i] = t3 - t2;
                    again[i] = t4 - t3;
                }
                String line = String.join("\t", name, Integer.toString(page.document.getAllElements().size()), Integer.toString(page.styles.size()),
                        ms(style), targetAt < 0 ? "-" : ms(restyleHover), ms(restyleAgain), ms(full), targetAt < 0 ? "-" : ms(hover), ms(again), digest);
                tsv.println(line);
                out.println(line);
            } catch (Throwable t) {
                tsv.println(name + "\tERROR " + t);
                out.println(name + "\tERROR " + t);
            }
        }
        tsv.close();
        System.exit(0);
    }

    private static Element root(org.jsoup.nodes.Document d) {
        Element html = d.selectFirst("html");
        return html == null ? d : html;
    }

    /** The runs' times in ms, sorted, joined with '/'. */
    private static String ms(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        StringBuilder b = new StringBuilder();
        for (long v : s) b.append(b.isEmpty() ? "" : "/").append(Math.round(v / 1e6));
        return b.toString();
    }

    /** One line per styled element and pseudo-element, in document order, into [out]. */
    private static void dump(org.jsoup.nodes.Document doc, Styler s, java.util.function.Consumer<String> out) throws Exception {
        int i = 0;
        for (Element e : doc.getAllElements()) {
            i++;
            for (var kind : List.of(Map.entry("", s.styles), Map.entry("::before", s.before), Map.entry("::after", s.after),
                    Map.entry("::placeholder", s.placeholder), Map.entry("::marker", s.marker))) {
                Style st = kind.getValue().get(e);
                if (st == null) continue;
                StringBuilder b = new StringBuilder();
                b.append(i).append(' ').append(e.normalName()).append(kind.getKey()).append(' ');
                fields(st, b, 0);
                out.accept(b.append('\n').toString());
            }
        }
    }

    private static void fields(Object o, StringBuilder b, int depth) throws Exception {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                b.append(f.getName()).append('=');
                value(f.get(o), b, depth);
                b.append(';');
            }
        }
    }

    private static void value(Object v, StringBuilder b, int depth) throws Exception {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>) {
            b.append(v);
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            for (Object x : it) {
                value(x, b, depth);
                b.append(',');
            }
            b.append(']');
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            for (var en : m.entrySet()) {
                b.append(en.getKey()).append(':');
                value(en.getValue(), b, depth);
                b.append(',');
            }
            b.append('}');
        } else if (v.getClass().isArray()) {
            b.append('[');
            int n = java.lang.reflect.Array.getLength(v);
            for (int k = 0; k < n; k++) {
                value(java.lang.reflect.Array.get(v, k), b, depth);
                b.append(',');
            }
            b.append(']');
        } else if (v.getClass().getMethod("toString").getDeclaringClass() != Object.class) {
            b.append(v);
        } else if (depth < 6) {
            b.append(v.getClass().getSimpleName()).append('(');
            fields(v, b, depth + 1);
            b.append(')');
        } else {
            b.append('?');
        }
    }

    private static List<Path> pages(String[] args) throws Exception {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.toString().endsWith(".html")).sorted().forEach(out::add);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }
}

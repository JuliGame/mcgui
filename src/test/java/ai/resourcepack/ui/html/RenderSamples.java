package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Renders every .html in a folder to PNGs (1024×576 at 1x, and at 2x), with load problems and
 * timings printed: how the renderer is judged, by eye. Run with {@code ./gradlew renderSamples}.
 */
public final class RenderSamples {
    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        List<Path> pages;
        try (Stream<Path> s = Files.list(in)) {
            pages = s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
        Page.Loader loader = new Page.Loader(true, out.resolve("cache"));
        for (Path p : pages) {
            String name = p.getFileName().toString().replace(".html", "");
            if (args.length > 2 && !name.contains(args[2])) continue;
            long t0 = System.nanoTime();
            Page page = Page.load(p, loader);
            long t1 = System.nanoTime();
            HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document, 1024, 576, 1, 0, Selector.State.NONE, null, null, 0);
            long t2 = System.nanoTime();
            HtmlRenderer.Frame f2 = HtmlRenderer.render(page, page.document, 2048, 1152, 2, 0, Selector.State.NONE, null, null, 0);
            long t3 = System.nanoTime();
            ImageIO.write(f.image(), "png", out.resolve(name + ".png").toFile());
            ImageIO.write(f2.image(), "png", out.resolve(name + "@2x.png").toFile());
            if (f.maxScroll() > 0) {
                HtmlRenderer.Frame end = HtmlRenderer.render(page, page.document, 1024, 576, 1, f.maxScroll(), Selector.State.NONE, null, null, 0);
                ImageIO.write(end.image(), "png", out.resolve(name + "-end.png").toFile());
            }
            System.out.printf("%-24s load %4d ms  1x %4d ms  2x %4d ms  page height %.0f  %s%n", name, (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, f.pageHeight(), page.problems);
        }
    }
}

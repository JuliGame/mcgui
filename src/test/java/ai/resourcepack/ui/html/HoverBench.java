package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.nio.file.Path;

/**
 * How long a hover costs: a full render of a page at a fullscreen view's size, then a repaint of
 * only the region round one hovered element, as a session does. Args: page file, CSS selector.
 */
public final class HoverBench {
    public static void main(String[] args) throws Exception {
        Page page = Page.load(Path.of(args[0]), new Page.Loader(false, null));
        String selector = args.length > 1 ? args[1] : "a";
        int w = 2048, h = 1152;
        double scale = 2;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            HtmlRenderer.Frame full = HtmlRenderer.render(page, page.document, w, h, scale, 0, Selector.State.NONE);
            long t1 = System.nanoTime();
            Element target = page.document.selectFirst(selector);
            Rectangle2D area = null;
            for (var hit : full.hits()) {
                boolean inside = false;
                for (Element e = hit.element(); e != null && !inside; e = e.parent()) inside = e == target;
                if (!inside) continue;
                if (area == null) area = hit.shape().getBounds2D();
                else area.add(hit.shape().getBounds2D());
            }
            Rectangle2D dirty = new Rectangle2D.Double(area.getX() - 48, area.getY() - 48, area.getWidth() + 96, area.getHeight() + 96);
            long t2 = System.nanoTime();
            HtmlRenderer.render(page, page.document, w, h, scale, 0, HtmlRenderer.state(target, null), full.image(), dirty);
            long t3 = System.nanoTime();
            long t4 = System.nanoTime();
            HtmlRenderer.render(page, page.document, w, h, scale, 0, HtmlRenderer.state(target, null), full.image(), new Rectangle2D.Double(0, 0, 1, 1));
            long t5 = System.nanoTime();
            System.out.printf("full %d ms, hover repaint %d ms (%.0f x %.0f css px), style+layout+walk only %d ms%n", (t1 - t0) / 1_000_000,
                    (t3 - t2) / 1_000_000, dirty.getWidth(), dirty.getHeight(), (t5 - t4) / 1_000_000);
        }
    }
}

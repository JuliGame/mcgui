package ai.resourcepack.ui.html.res;

import java.awt.Graphics2D;

/** An image a page uses: a bitmap or an SVG, drawn scaled into any rectangle. */
public interface Picture {
    /** Natural size in CSS pixels; 0 for an SVG with no size of its own. */
    double width();

    double height();

    void draw(Graphics2D g, double x, double y, double w, double h);
}

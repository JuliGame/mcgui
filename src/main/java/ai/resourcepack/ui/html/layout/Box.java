package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.res.Picture;
import ai.resourcepack.ui.html.style.Style;
import java.awt.Font;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * A node of the box tree: an element, an anonymous wrapper, a piece of text, or a pseudo-element.
 *
 * <p><b>Coordinates.</b> A box's (x, y) is its border box's top-left relative to the border box of
 * the box that laid it out: its nearest ancestor that is not an inline box. Inline boxes (a span)
 * have no rectangle of their own; their pieces on each line ({@link #fragments}) are relative to the
 * block that owns the lines, and so are the boxes inside them. Painting and hit testing add offsets
 * only when they enter a box that is not inline.
 */
public final class Box {
    public enum Inner { FLOW, FLEX, GRID, TABLE, ROW_GROUP, ROW, CELL, REPLACED, TEXT, BREAK }

    public final Element element;       // null for anonymous boxes and text
    public final Style style;
    public Box parent;
    public final List<Box> children = new ArrayList<>();
    public boolean blockLevel;
    public Inner inner = Inner.FLOW;
    /** "before", "after", "marker", or null. */
    public String pseudo;
    /** The element the box belongs to for clicks and hover: its own, or its parent's for text and pseudo-elements. */
    public Element owner;

    // Text boxes
    public String text;

    // Replaced boxes
    public Picture picture;
    /** A form control, drawn by the painter: "text", "checkbox", "radio", "range", "select", "button", "progress". */
    public String control;
    public String controlText;
    public boolean controlPlaceholder;
    public double controlValue = Double.NaN;

    // Layout results
    public double x, y, w, h;
    public final double[] m = new double[4];
    public final double[] b = new double[4];
    public final double[] p = new double[4];
    /** Distance from the border box's top to the first line's baseline, for alignment; NaN if none. */
    public double baseline = Double.NaN;
    public double lastBaseline = Double.NaN;
    /** The lines of inline content of this block container, or null. */
    public List<Line> lines;
    /** For an inline box: its rectangle on each line it appears on (relative to the block owning the lines). */
    public List<Fragment> fragments;
    /** Where this box would have been in the flow, relative to its reference box: for absolute boxes with auto offsets. */
    public double staticX, staticY;
    /** For a list item: the marker text ("•", "3."). */
    public String marker;
    /** The ::marker style, when the page gives one. */
    public Style markerStyle;
    /** Set when a fixed-position box: its position is relative to the viewport, not the page. */
    public boolean fixed;
    /** The inputs of the last layout of this box: the same again needs no work. */
    double[] laidOutWith;

    /** The scrollable overflow below this box's content (the page height, for the root). */
    public double overflowBottom, overflowRight;

    public Box(Element element, Style style) {
        this.element = element;
        this.style = style;
        this.owner = element;
    }

    public boolean isInline() {
        return !blockLevel && inner == Inner.FLOW && !atomic;
    }

    /** An inline-level box laid out as one unbreakable piece: inline-block, inline-flex, an image, a form control. */
    public boolean atomic;

    public boolean isText() {
        return inner == Inner.TEXT;
    }

    public double contentX() {
        return b[3] + p[3];
    }

    public double contentY() {
        return b[0] + p[0];
    }

    public double contentW() {
        return Math.max(0, w - b[1] - b[3] - p[1] - p[3]);
    }

    public double contentH() {
        return Math.max(0, h - b[0] - b[2] - p[0] - p[2]);
    }

    public double outerW() {
        return w + m[1] + m[3];
    }

    public double outerH() {
        return h + m[0] + m[2];
    }

    public boolean isOutOfFlow() {
        return style.isOutOfFlow();
    }

    /** A line of inline content. */
    public static final class Line {
        public double x, y, w, h, baseline;
        public final List<Item> items = new ArrayList<>();
    }

    /** A piece of a line: some text, or an atomic box. */
    public static final class Item {
        public Box box;          // the text box, or the atomic box
        public String text;      // text items
        public Font font;
        public double x, w;      // relative to the line's block
        public double baselineY; // relative to the block
        public Style style;
        /** Extra space added to each space character (text-align: justify). */
        public double spacing;
    }

    /** One line's piece of an inline box. */
    public static final class Fragment {
        public double x, y, w, h;
        public boolean first, last;
    }

    @Override
    public String toString() {
        return (element != null ? element.normalName() : isText() ? "#text" : "anon") + (pseudo != null ? "::" + pseudo : "")
                + "[" + inner + (blockLevel ? " block" : " inline") + " " + (int) x + "," + (int) y + " " + (int) w + "x" + (int) h + "]";
    }
}

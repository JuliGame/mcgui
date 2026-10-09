package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flexbox: base sizes from flex-basis or content, lines when wrapping, the grow and shrink
 * distribution with min/max freezing, stretch, justify-content, align-items/self/content, gaps,
 * auto margins, order, and the reverse directions. A column container with an auto height is sized
 * by its items, then by its own min/max height, and only then flexes (a sticky footer works).
 */
final class FlexLayout {
    private final Layout L;

    FlexLayout(Layout layout) {
        this.L = layout;
    }

    private static final class Item {
        final Box box;
        double base, hyp, min, max, target;
        double marginMain, marginCross;
        boolean frozen;
        double cross;
        String align;

        Item(Box box) {
            this.box = box;
        }

        double outerTarget() {
            return target + marginMain;
        }
    }

    private static boolean isRow(Style s) {
        return s.flexDirection.startsWith("row");
    }

    private static double gap(Len g, double basis) {
        return g == null ? 0 : Math.max(0, g.of(basis));
    }

    double layout(Box box, double contentH) {
        Style s = box.style;
        boolean row = isRow(s);
        boolean reverse = s.flexDirection.endsWith("reverse");
        boolean wrap = !s.flexWrap.equals("nowrap");
        double cw = box.contentW();
        double mainSize = row ? cw : contentH;
        double crossSize = row ? contentH : cw;
        double gapMain = gap(row ? s.columnGap : s.rowGap, row ? cw : contentH);
        double gapCross = gap(row ? s.rowGap : s.columnGap, row ? contentH : cw);

        List<Item> items = new ArrayList<>();
        for (Box c : box.children) {
            if (c.isOutOfFlow()) {
                c.staticX = box.contentX();
                c.staticY = box.contentY();
                L.defer(c);
                continue;
            }
            items.add(new Item(c));
        }
        items.sort(Comparator.comparingInt(i -> i.box.style.order));
        for (Item it : items) baseSize(it, row, cw, contentH, mainSize);

        // A column with an auto height: its items decide it, then its own min and max height.
        if (Double.isNaN(mainSize)) {
            double sum = 0;
            for (Item it : items) sum += it.hyp + it.marginMain;
            sum += gapMain * Math.max(0, items.size() - 1);
            double clamped = L.clampHeight(box, sum + L.vEdges(box), Double.NaN) - L.vEdges(box);
            if (Math.abs(clamped - sum) > 0.01) mainSize = clamped;
        }

        // Lines.
        List<List<Item>> lines = new ArrayList<>();
        List<Item> cur = new ArrayList<>();
        double used = 0;
        for (Item it : items) {
            double outer = it.hyp + it.marginMain;
            if (wrap && !Double.isNaN(mainSize) && !cur.isEmpty() && used + gapMain + outer > mainSize + 0.01) {
                lines.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            used += (cur.isEmpty() ? 0 : gapMain) + outer;
            cur.add(it);
        }
        if (!cur.isEmpty()) lines.add(cur);

        // Main sizes, then each item laid out at its main size.
        for (List<Item> line : lines) {
            if (Double.isNaN(mainSize)) {
                for (Item it : line) it.target = it.hyp;
            } else {
                resolveFlexible(line, mainSize, gapMain);
            }
            for (Item it : line) {
                it.align = alignOf(it.box, s);
                layoutItem(it, row, cw, contentH, Double.NaN);
                it.cross = row ? it.box.h + it.marginCross : it.box.w + it.marginCross;
            }
        }

        // Cross sizes of the lines.
        double[] lineCross = new double[lines.size()];
        double[] lineBaseline = new double[lines.size()];
        for (int li = 0; li < lines.size(); li++) {
            double max = 0, maxBase = 0;
            for (Item it : lines.get(li)) {
                max = Math.max(max, it.cross);
                if (row && it.align.equals("baseline")) maxBase = Math.max(maxBase, itemBaseline(it));
            }
            for (Item it : lines.get(li)) {
                if (row && it.align.equals("baseline")) max = Math.max(max, maxBase - itemBaseline(it) + it.cross);
            }
            lineCross[li] = max;
            lineBaseline[li] = maxBase;
        }
        // A row with an auto height is as tall as its lines, then its own min/max height: a body with
        // min-height: 100vh centres its content in the screen.
        if (row && Double.isNaN(crossSize)) {
            double sum = gapCross * Math.max(0, lines.size() - 1);
            for (double c : lineCross) sum += c;
            double clamped = L.clampHeight(box, sum + L.vEdges(box), Double.NaN) - L.vEdges(box);
            if (Math.abs(clamped - sum) > 0.01) {
                crossSize = clamped;
                contentH = clamped;
            }
        }
        if (lines.size() == 1 && !Double.isNaN(crossSize)) lineCross[0] = crossSize;
        String alignContent = s.alignContent;
        double crossUsed = gapCross * Math.max(0, lines.size() - 1);
        for (double c : lineCross) crossUsed += c;
        if (lines.size() > 1 && !Double.isNaN(crossSize) && crossSize > crossUsed
                && (alignContent.equals("normal") || alignContent.equals("stretch"))) {
            double extra = (crossSize - crossUsed) / lines.size();
            for (int li = 0; li < lineCross.length; li++) lineCross[li] += extra;
            crossUsed = crossSize;
        }

        // Stretch: items with an auto cross size fill their line.
        for (int li = 0; li < lines.size(); li++) {
            for (Item it : lines.get(li)) {
                if (!it.align.equals("stretch")) continue;
                Style is = it.box.style;
                boolean autoCross = row ? is.height == null && is.heightKeyword == null : is.width == null && is.widthKeyword == null;
                boolean autoMargin = row ? is.margin[0] == null || is.margin[2] == null : is.margin[1] == null || is.margin[3] == null;
                if (!autoCross || autoMargin) continue;
                double target = lineCross[li] - it.marginCross;
                target = row ? L.clampHeight(it.box, target, contentH) : L.clampWidth(it.box, target, cw);
                double have = row ? it.box.h : it.box.w;
                if (Math.abs(have - target) > 0.01) layoutItem(it, row, cw, contentH, target);
                it.cross = target + it.marginCross;
            }
        }

        // Positions.
        double contentMain = Double.isNaN(mainSize) ? 0 : mainSize;
        if (Double.isNaN(mainSize)) {
            for (List<Item> line : lines) {
                double sum = gapMain * Math.max(0, line.size() - 1);
                for (Item it : line) sum += it.outerTarget();
                contentMain = Math.max(contentMain, sum);
            }
        }
        double crossFree = Double.isNaN(crossSize) ? 0 : crossSize - crossUsed;
        double lineOffset = 0, lineGapExtra = 0;
        if (lines.size() > 1 && crossFree > 0) {
            switch (alignContent) {
                case "flex-end" -> lineOffset = crossFree;
                case "center" -> lineOffset = crossFree / 2;
                case "space-between" -> lineGapExtra = crossFree / (lines.size() - 1);
                case "space-around" -> {
                    lineGapExtra = crossFree / lines.size();
                    lineOffset = lineGapExtra / 2;
                }
                case "space-evenly" -> {
                    lineGapExtra = crossFree / (lines.size() + 1);
                    lineOffset = lineGapExtra;
                }
                default -> {}
            }
        }
        double crossTotal = Double.isNaN(crossSize) ? crossUsed : crossSize;
        boolean wrapReverse = s.flexWrap.equals("wrap-reverse");
        double linePos = lineOffset;
        for (int li = 0; li < lines.size(); li++) {
            List<Item> line = lines.get(li);
            placeLine(box, line, row, reverse, contentMain, gapMain, s.justifyContent,
                    wrapReverse ? crossTotal - linePos - lineCross[li] : linePos, lineCross[li], lineBaseline[li]);
            linePos += lineCross[li] + gapCross + lineGapExtra;
        }

        // Baseline: the first item's.
        if (!items.isEmpty()) {
            Box first = lines.get(0).get(0).box;
            double b = Double.isNaN(first.baseline) ? first.h : first.baseline;
            box.baseline = first.y + b;
            box.lastBaseline = box.baseline;
        }
        if (row) return Double.isNaN(contentH) ? crossUsed : contentH;
        return Double.isNaN(contentH) ? contentMain : contentH;
    }

    private static String alignOf(Box item, Style container) {
        String a = item.style.alignSelf;
        if (a.equals("auto")) a = container.alignItems;
        if (a.equals("normal")) a = "stretch";
        // A replaced item with an aspect ratio does not stretch by default; everything else does.
        return a;
    }

    private double itemBaseline(Item it) {
        Box b = it.box;
        return b.m[0] + (Double.isNaN(b.baseline) ? b.h : b.baseline);
    }

    /** The flex base size, hypothetical size, and min/max of an item, all border-box main sizes. */
    private void baseSize(Item it, boolean row, double cw, double ch, double mainSize) {
        Box b = it.box;
        Style is = b.style;
        L.edges(b, cw);
        it.marginMain = row ? b.m[1] + b.m[3] : b.m[0] + b.m[2];
        it.marginCross = row ? b.m[0] + b.m[2] : b.m[1] + b.m[3];
        double edges = row ? L.hEdges(b) : L.vEdges(b);
        double base = Double.NaN;
        Len basis = is.flexBasis;
        if (basis != null && !(basis.relative() && Double.isNaN(mainSize))) {
            base = basis.of(mainSize) + (is.borderBox ? 0 : edges);
            base = Math.max(base, edges);
        } else if (!is.flexBasisContent) {
            base = row ? L.specifiedWidth(b, cw) : L.specifiedHeight(b, ch);
        }
        double content = Double.NaN;
        if (Double.isNaN(base)) {
            content = contentMain(it, row, cw);
            base = content;
        }
        it.base = base;
        // min: a specified min, else the content (unless the item scrolls or clips).
        Len minSpec = row ? is.minWidth : is.minHeight;
        double min;
        if (minSpec != null) {
            min = row ? L.clampWidth(b, 0, cw) : L.clampHeight(b, 0, ch);
        } else if (is.clips()) {
            min = edges;
        } else {
            double c = row ? L.minContent(b) : (Double.isNaN(content) ? contentMain(it, false, cw) : content);
            double spec = row ? L.specifiedWidth(b, cw) : L.specifiedHeight(b, ch);
            min = Double.isNaN(spec) ? c : Math.min(c, spec);
            // A max-size caps the automatic minimum too.
            Len maxSpec = row ? is.maxWidth : is.maxHeight;
            if (maxSpec != null && !(maxSpec.relative() && Double.isNaN(row ? cw : ch))) {
                min = Math.min(min, maxSpec.of(row ? cw : ch) + (is.borderBox ? 0 : edges));
            }
        }
        Len maxSpec = row ? is.maxWidth : is.maxHeight;
        double max = Double.MAX_VALUE;
        if (maxSpec != null && !(maxSpec.relative() && Double.isNaN(row ? cw : ch))) max = maxSpec.of(row ? cw : ch) + (is.borderBox ? 0 : edges);
        it.min = Math.max(edges, min);
        it.max = Math.max(it.min, max);
        it.hyp = Math.max(it.min, Math.min(it.max, base));
    }

    /** The item's content size along the main axis, border box. */
    private double contentMain(Item it, boolean row, double cw) {
        Box b = it.box;
        if (row) return L.maxContent(b);
        // A column item's height comes from laying it out at the width it will have.
        double w = crossWidthFor(it, cw, Double.NaN);
        L.layout(b, cw, Double.NaN, w, Double.NaN);
        return b.h;
    }

    /** A column item's width before stretching: its specified width, or what its content wants within the container. */
    private double crossWidthFor(Item it, double cw, double stretchTo) {
        Box b = it.box;
        if (!Double.isNaN(stretchTo)) return stretchTo;
        double spec = L.specifiedWidth(b, cw);
        if (!Double.isNaN(spec)) return L.clampWidth(b, spec, cw);
        Style container = b.parent == null ? b.style : b.parent.style;
        String align = alignOf(b, container);
        double avail = cw - b.m[1] - b.m[3];
        if (align.equals("stretch") && b.style.margin[1] != null && b.style.margin[3] != null) return L.clampWidth(b, avail, cw);
        return L.clampWidth(b, Math.max(L.minContent(b), Math.min(L.maxContent(b), avail)), cw);
    }

    /** Lays an item out at its main size; [cross], if not NaN, is its stretched cross size. */
    private void layoutItem(Item it, boolean row, double cw, double ch, double cross) {
        Box b = it.box;
        if (row) {
            L.layout(b, cw, ch, it.target, cross);
        } else {
            L.layout(b, cw, ch, crossWidthFor(it, cw, cross), it.target);
        }
        // layout() resets the edges; auto margins stay 0 until positioned.
    }

    /** CSS's "resolve the flexible lengths" for one line. */
    private void resolveFlexible(List<Item> line, double mainSize, double gap) {
        double outerHyp = gap * Math.max(0, line.size() - 1);
        for (Item it : line) outerHyp += it.hyp + it.marginMain;
        boolean grow = outerHyp < mainSize;
        for (Item it : line) {
            it.target = it.hyp;
            double factor = grow ? it.box.style.flexGrow : it.box.style.flexShrink;
            it.frozen = factor == 0 || (grow && it.base > it.hyp) || (!grow && it.base < it.hyp);
        }
        double initialFree = mainSize - outerSum(line, gap, true);
        for (int round = 0; round <= line.size(); round++) {
            boolean anyUnfrozen = false;
            for (Item it : line) if (!it.frozen) anyUnfrozen = true;
            if (!anyUnfrozen) break;
            double free = mainSize - outerSum(line, gap, false);
            double sumGrow = 0, sumShrink = 0;
            for (Item it : line) {
                if (it.frozen) continue;
                sumGrow += it.box.style.flexGrow;
                sumShrink += it.box.style.flexShrink;
            }
            // Factors that add up to less than 1 take only that share of the free space.
            double sum = grow ? sumGrow : sumShrink;
            if (sum < 1 && Math.abs(initialFree * sum) < Math.abs(free)) free = initialFree * sum;
            double scaledShrink = 0;
            for (Item it : line) if (!it.frozen) scaledShrink += it.box.style.flexShrink * it.base;
            double violation = 0;
            for (Item it : line) {
                if (it.frozen) continue;
                double t;
                if (grow) t = it.base + (sumGrow > 0 ? free * it.box.style.flexGrow / sumGrow : 0);
                else t = it.base + (scaledShrink > 0 ? free * it.box.style.flexShrink * it.base / scaledShrink : 0);
                double clamped = Math.max(it.min, Math.min(it.max, t));
                violation += clamped - t;
                it.target = clamped;
            }
            for (Item it : line) {
                if (it.frozen) continue;
                if (Math.abs(violation) < 0.001) it.frozen = true;
                else if (violation > 0 && it.target <= it.min + 0.001) it.frozen = true;
                else if (violation < 0 && it.target >= it.max - 0.001) it.frozen = true;
            }
        }
    }

    /** Outer sizes plus gaps: targets for frozen items, base sizes for the rest (or all hypothetical sizes). */
    private static double outerSum(List<Item> line, double gap, boolean hypothetical) {
        double s = gap * Math.max(0, line.size() - 1);
        for (Item it : line) {
            double size = hypothetical ? (it.frozen ? it.hyp : it.base) : (it.frozen ? it.target : it.base);
            s += size + it.marginMain;
        }
        return s;
    }

    private void placeLine(Box box, List<Item> line, boolean row, boolean reverse, double mainSize, double gap, String justify,
                           double linePos, double lineCross, double lineBaseline) {
        double used = gap * Math.max(0, line.size() - 1);
        int autoMargins = 0;
        for (Item it : line) {
            used += it.outerTarget();
            Style is = it.box.style;
            if (row) {
                if (is.margin[3] == null) autoMargins++;
                if (is.margin[1] == null) autoMargins++;
            } else {
                if (is.margin[0] == null) autoMargins++;
                if (is.margin[2] == null) autoMargins++;
            }
        }
        double free = mainSize - used;
        double offset = 0, between = gap;
        double perAuto = 0;
        if (autoMargins > 0 && free > 0) {
            perAuto = free / autoMargins;
            free = 0;
        }
        int n = line.size();
        switch (justify) {
            case "flex-end" -> offset = free;
            case "center" -> offset = free / 2;
            case "space-between" -> {
                if (n > 1 && free > 0) between += free / (n - 1);
            }
            case "space-around" -> {
                if (free > 0) {
                    between += free / n;
                    offset = free / n / 2;
                } else {
                    offset = free / 2;
                }
            }
            case "space-evenly" -> {
                if (free > 0) {
                    between += free / (n + 1);
                    offset = free / (n + 1);
                } else {
                    offset = free / 2;
                }
            }
            default -> {}
        }
        double pos = offset;
        for (Item it : line) {
            Box b = it.box;
            Style is = b.style;
            double before, after;
            if (row) {
                before = is.margin[3] == null ? perAuto : b.m[3];
                after = is.margin[1] == null ? perAuto : b.m[1];
            } else {
                before = is.margin[0] == null ? perAuto : b.m[0];
                after = is.margin[2] == null ? perAuto : b.m[2];
            }
            double size = row ? b.w : b.h;
            double mainPos = pos + before;
            if (reverse) mainPos = mainSize - mainPos - size;
            // Cross position within the line.
            double outerCross = it.cross;
            double crossOffset = switch (it.align) {
                case "flex-end" -> lineCross - outerCross;
                case "center" -> (lineCross - outerCross) / 2;
                case "baseline" -> row ? lineBaseline - itemBaseline(it) : 0;
                default -> 0;
            };
            boolean autoStart = row ? is.margin[0] == null : is.margin[3] == null;
            boolean autoEnd = row ? is.margin[2] == null : is.margin[1] == null;
            if (autoStart || autoEnd) {
                double room = lineCross - (row ? b.h : b.w) - (row ? (autoStart ? 0 : b.m[0]) + (autoEnd ? 0 : b.m[2]) : (autoStart ? 0 : b.m[3]) + (autoEnd ? 0 : b.m[1]));
                crossOffset = autoStart && autoEnd ? room / 2 : autoStart ? room : 0;
            }
            double crossMarginStart = row ? (autoStart ? 0 : b.m[0]) : (autoStart ? 0 : b.m[3]);
            if (row) {
                b.x = box.contentX() + mainPos;
                b.y = box.contentY() + linePos + crossOffset + crossMarginStart;
                b.m[3] = before;
                b.m[1] = after;
            } else {
                b.y = box.contentY() + mainPos;
                b.x = box.contentX() + linePos + crossOffset + crossMarginStart;
                b.m[0] = before;
                b.m[2] = after;
            }
            pos += before + size + after + between;
        }
    }

    /** Content-box {min, max} widths. */
    double[] intrinsic(Box box) {
        Style s = box.style;
        boolean row = isRow(s);
        boolean wrap = !s.flexWrap.equals("nowrap");
        double gap = gap(row ? s.columnGap : s.rowGap, Double.NaN);
        double min = 0, max = 0;
        int n = 0;
        for (Box c : box.children) {
            if (c.isOutOfFlow()) continue;
            double margins = L.fixedMargins(c);
            double cmin = L.minContent(c) + margins, cmax = L.maxContent(c) + margins;
            if (row) {
                max += cmax;
                min = wrap ? Math.max(min, cmin) : min + cmin;
            } else {
                max = Math.max(max, cmax);
                min = Math.max(min, cmin);
            }
            n++;
        }
        if (row && n > 1) {
            max += gap * (n - 1);
            if (!wrap) min += gap * (n - 1);
        }
        return new double[]{min, max};
    }
}

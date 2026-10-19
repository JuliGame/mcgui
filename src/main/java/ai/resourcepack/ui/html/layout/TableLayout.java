package ai.resourcepack.ui.html.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Tables, the automatic layout simplified: column widths from their cells' min- and max-content
 * widths (colspan honoured, rowspan treated as 1), rows as tall as their tallest cell, cell content
 * aligned by vertical-align, captions on top. Rows and row groups get real boxes, so their
 * backgrounds and hover states work.
 */
final class TableLayout {
    private final Layout L;

    TableLayout(Layout layout) {
        this.L = layout;
    }

    private record Row(Box box, Box group, List<Box> cells) {}

    private List<Row> rows(Box table, List<Box> captions) {
        List<Row> head = new ArrayList<>(), body = new ArrayList<>(), foot = new ArrayList<>();
        for (Box c : table.children) {
            if (c.isOutOfFlow()) {
                c.staticX = table.contentX();
                c.staticY = table.contentY();
                L.defer(c);
                continue;
            }
            if (c.inner == Box.Inner.ROW_GROUP) {
                String d = c.style.display;
                List<Row> into = d.equals("table-header-group") ? head : d.equals("table-footer-group") ? foot : body;
                for (Box r : c.children) if (!r.isOutOfFlow() && !BoxBuilder.isCollapsibleSpace(r)) into.add(new Row(r, c, cells(r)));
            } else if (c.inner == Box.Inner.ROW) {
                body.add(new Row(c, null, cells(c)));
            } else if (c.inner == Box.Inner.CELL) {
                // A cell straight in a table: a row of its own.
                body.add(new Row(null, null, List.of(c)));
            } else if (!BoxBuilder.isCollapsibleSpace(c)) {
                captions.add(c);
            }
        }
        List<Row> all = new ArrayList<>(head);
        all.addAll(body);
        all.addAll(foot);
        return all;
    }

    private static List<Box> cells(Box row) {
        List<Box> out = new ArrayList<>();
        for (Box c : row.children) if (!c.isOutOfFlow() && !BoxBuilder.isCollapsibleSpace(c)) out.add(c);
        return out;
    }

    private static int colspan(Box cell) {
        if (cell.element == null) return 1;
        // Most cells have none: no exception for those. One costs a stack trace, which inside 500 nested
        // tables is thousands of frames deep, for every cell on every layout pass.
        String v = cell.element.attr("colspan").trim();
        if (v.isEmpty()) return 1;
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private double spacing(Box table) {
        return table.style.borderCollapse.equals("collapse") ? 0 : table.style.borderSpacing.of(0);
    }

    /** {min[], max[]} per column. */
    private double[][] columns(List<Row> rows) {
        int n = 0;
        for (Row r : rows) {
            int c = 0;
            for (Box cell : r.cells) c += colspan(cell);
            n = Math.max(n, c);
        }
        double[] min = new double[n], max = new double[n];
        for (Row r : rows) {
            int col = 0;
            for (Box cell : r.cells) {
                int span = colspan(cell);
                double cmin = L.minContent(cell), cmax = L.maxContent(cell);
                if (span == 1) {
                    min[col] = Math.max(min[col], cmin);
                    max[col] = Math.max(max[col], cmax);
                }
                col += span;
            }
        }
        // Spanning cells: share any shortfall across their columns.
        for (Row r : rows) {
            int col = 0;
            for (Box cell : r.cells) {
                int span = colspan(cell);
                if (span > 1) {
                    double haveMin = 0, haveMax = 0;
                    for (int i = col; i < col + span && i < n; i++) {
                        haveMin += min[i];
                        haveMax += max[i];
                    }
                    double cmin = L.minContent(cell), cmax = L.maxContent(cell);
                    for (int i = col; i < col + span && i < n; i++) {
                        if (cmin > haveMin) min[i] += (cmin - haveMin) / span;
                        if (cmax > haveMax) max[i] += (cmax - haveMax) / span;
                    }
                }
                col += span;
            }
        }
        for (int i = 0; i < n; i++) max[i] = Math.max(max[i], min[i]);
        return new double[][]{min, max};
    }

    double layout(Box table, double contentH) {
        List<Box> captions = new ArrayList<>();
        List<Row> rows = rows(table, captions);
        double cw = table.contentW();
        double y = table.contentY();
        for (Box cap : captions) {
            L.layout(cap, cw, Double.NaN, Double.NaN, Double.NaN);
            cap.x = table.contentX() + cap.m[3];
            cap.y = y + cap.m[0];
            y = cap.y + cap.h + cap.m[2];
        }
        double[][] mm = columns(rows);
        double[] min = mm[0], max = mm[1];
        int n = min.length;
        double sp = spacing(table);
        double avail = cw - sp * (n + 1);
        double[] widths = new double[n];
        double sumMin = 0, sumMax = 0;
        for (int i = 0; i < n; i++) {
            sumMin += min[i];
            sumMax += max[i];
        }
        boolean fixedWidth = table.style.width != null;
        if (sumMax <= avail) {
            double extra = fixedWidth || table.blockLevel && table.style.width != null ? avail - sumMax : 0;
            for (int i = 0; i < n; i++) widths[i] = max[i] + (sumMax > 0 ? extra * max[i] / sumMax : extra / Math.max(1, n));
        } else if (sumMin >= avail) {
            System.arraycopy(min, 0, widths, 0, n);
        } else {
            double room = avail - sumMin, want = sumMax - sumMin;
            for (int i = 0; i < n; i++) widths[i] = min[i] + (want > 0 ? room * (max[i] - min[i]) / want : 0);
        }
        double[] colX = new double[n + 1];
        double x = sp;
        for (int i = 0; i < n; i++) {
            colX[i] = x;
            x += widths[i] + sp;
        }
        colX[n] = x;
        double tableW = x;
        y += sp;
        Box lastGroup = null;
        double groupTop = y;
        for (Row r : rows) {
            if (r.group != lastGroup) {
                if (lastGroup != null) finishGroup(lastGroup, table, groupTop, y - sp, tableW);
                lastGroup = r.group;
                groupTop = y;
            }
            double rowH = 0;
            int col = 0;
            for (Box cell : r.cells) {
                int span = colspan(cell);
                int end = Math.min(n, col + span);
                double w = colX[end] - colX[Math.min(col, n)] - sp;
                L.layout(cell, w, Double.NaN, w, Double.NaN);
                cell.x = colX[Math.min(col, n)];
                rowH = Math.max(rowH, cell.h);
                col += span;
            }
            if (r.box != null && r.box.style.height != null) rowH = Math.max(rowH, r.box.style.height.of(Double.NaN));
            for (Box cell : r.cells) {
                if (cell.h < rowH) {
                    double free = rowH - cell.h;
                    double shift = switch (cell.style.verticalAlign) {
                        case "middle" -> free / 2;
                        case "bottom" -> free;
                        default -> 0;
                    };
                    shiftContent(cell, shift);
                    cell.h = rowH;
                }
            }
            // Cells sit in their row's box; the row in its group's, or the table's.
            double rowX = table.contentX(), rowY = y;
            if (r.box != null) {
                r.box.w = tableW;
                r.box.h = rowH;
                r.box.x = r.group != null ? 0 : rowX;
                r.box.y = r.group != null ? y - groupTop : rowY;
                for (Box cell : r.cells) cell.y = 0;
            } else {
                for (Box cell : r.cells) {
                    cell.x += rowX;
                    cell.y = rowY;
                }
            }
            y += rowH + sp;
        }
        if (lastGroup != null) finishGroup(lastGroup, table, groupTop, y - sp, tableW);
        return y - table.contentY();
    }

    private void finishGroup(Box group, Box table, double top, double bottom, double width) {
        group.x = table.contentX();
        group.y = top;
        group.w = width;
        group.h = Math.max(0, bottom - top);
    }

    /** Moves everything inside a cell down by [dy] (vertical-align). */
    static void shiftContent(Box block, double dy) {
        if (dy == 0) return;
        if (block.lines != null) {
            for (Box.Line l : block.lines) {
                l.y += dy;
                for (Box.Item it : l.items) {
                    if (it.text != null) it.baselineY += dy;
                    else if (it.box != null) it.box.y += dy;
                }
            }
        }
        shiftChildren(block, dy, block.lines != null);
        if (!Double.isNaN(block.baseline)) block.baseline += dy;
        if (!Double.isNaN(block.lastBaseline)) block.lastBaseline += dy;
    }

    private static void shiftChildren(Box parent, double dy, boolean atomicsDone) {
        for (Box c : parent.children) {
            if (c.isText()) continue;
            if (c.isInline()) {
                if (c.fragments != null) for (Box.Fragment f : c.fragments) f.y += dy;
                shiftChildren(c, dy, atomicsDone);
            } else if (!(atomicsDone && c.atomic)) {
                c.y += dy;
            }
        }
    }

    double[] intrinsic(Box table) {
        List<Row> rows = rows(table, new ArrayList<>());
        double[][] mm = columns(rows);
        double sp = spacing(table) * (mm[0].length + 1);
        double min = sp, max = sp;
        for (double v : mm[0]) min += v;
        for (double v : mm[1]) max += v;
        return new double[]{min, max};
    }
}

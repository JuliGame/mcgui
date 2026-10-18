package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSS grid: track lists with px, %, fr, auto, min-content, max-content, minmax(), fit-content() and
 * repeat() (auto-fill and auto-fit too); template areas; line numbers, negative lines, spans and
 * names; row-major auto-placement (or column with grid-auto-flow); implicit tracks from
 * grid-auto-rows/columns; gaps; and self/content alignment on both axes. Track sizing is the
 * standard algorithm simplified: content tracks take their items' sizes, then grow to their limits,
 * then fr tracks share what is left.
 */
final class GridLayout {
    private final Layout L;

    GridLayout(Layout layout) {
        this.L = layout;
    }

    /** One track: its minimum and maximum sizing functions. */
    private static final class Track {
        // kinds: "fixed", "auto", "min", "max", "fr", "fit"
        String minKind = "auto", maxKind = "auto";
        Len minLen, maxLen;
        double fr;
        double base, limit, size;

        static Track of(String spec, Len.Context ctx) {
            Track t = new Track();
            String s = spec.trim().toLowerCase(Locale.ROOT);
            String inner = Values.fn(s, "minmax");
            if (inner != null) {
                List<String> parts = Values.splitCommas(inner);
                if (parts.size() == 2) {
                    Track a = of(parts.get(0), ctx), b = of(parts.get(1), ctx);
                    t.minKind = a.minKind.equals("fr") ? "auto" : a.minKind;
                    t.minLen = a.minLen;
                    t.maxKind = b.maxKind;
                    t.maxLen = b.maxLen;
                    t.fr = b.fr;
                    return t;
                }
            }
            inner = Values.fn(s, "fit-content");
            if (inner != null) {
                t.minKind = "auto";
                t.maxKind = "fit";
                t.maxLen = Len.parse(inner, ctx);
                return t;
            }
            if (s.endsWith("fr")) {
                Double fr = Values.number(s.substring(0, s.length() - 2));
                t.minKind = "auto";
                t.maxKind = "fr";
                t.fr = fr == null ? 1 : fr;
                return t;
            }
            switch (s) {
                case "auto" -> {
                    return t;
                }
                case "min-content" -> {
                    t.minKind = t.maxKind = "min";
                    return t;
                }
                case "max-content" -> {
                    t.minKind = t.maxKind = "max";
                    return t;
                }
                default -> {}
            }
            Len len = Len.parse(spec, ctx);
            if (len != null) {
                t.minKind = t.maxKind = "fixed";
                t.minLen = t.maxLen = len;
            }
            return t;
        }
    }

    private static final class Cell {
        final Box box;
        int col = -1, row = -1, colSpan = 1, rowSpan = 1;

        Cell(Box box) {
            this.box = box;
        }
    }

    /** A track list with repeat() expanded; auto repeats fill [space] with [gap] between. */
    private List<Track> tracks(String template, Len.Context ctx, double space, double gap, Map<String, Integer> lineNames) {
        List<Track> out = new ArrayList<>();
        if (template == null || template.trim().equalsIgnoreCase("none") || template.isBlank()) return out;
        for (String part : Values.splitSpaces(template.trim())) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.startsWith("[")) {
                for (String n : part.substring(1, part.length() - 1).trim().split("\\s+")) lineNames.putIfAbsent(n, out.size() + 1);
                continue;
            }
            String inner = Values.fn(lower, "repeat");
            if (inner == null) {
                out.add(Track.of(part, ctx));
                continue;
            }
            int comma = inner.indexOf(',');
            if (comma < 0) continue;
            String count = inner.substring(0, comma).trim();
            List<Track> unit = new ArrayList<>();
            for (String u : Values.splitSpaces(part.substring(part.indexOf(',') + 1, part.length() - 1).trim())) {
                if (u.startsWith("[")) continue;
                unit.add(Track.of(u, ctx));
            }
            if (unit.isEmpty()) continue;
            int n;
            if (count.equals("auto-fill") || count.equals("auto-fit")) {
                double one = 0;
                for (Track t : unit) {
                    double size = t.maxKind.equals("fixed") ? t.maxLen.of(space) : t.minKind.equals("fixed") ? t.minLen.of(space) : 0;
                    one += size;
                }
                one += gap * unit.size();
                n = Double.isNaN(space) || one <= gap * unit.size() ? 1 : Math.max(1, (int) Math.floor((space + gap) / one));
                if (count.equals("auto-fit")) n = -n; // marked: empty repeated tracks collapse
            } else {
                try {
                    n = Math.max(1, Integer.parseInt(count));
                } catch (NumberFormatException e) {
                    n = 1;
                }
            }
            boolean fit = n < 0;
            // A typo like repeat(100000, 1fr) must not cost minutes of track sizing.
            n = Math.min(Math.abs(n), Math.max(1, (MAX_TRACKS - out.size()) / unit.size()));
            for (int i = 0; i < n; i++) for (Track t : unit) out.add(copy(t));
            if (fit) autoFitStart = out.size() - n * unit.size();
            else autoFitStart = -1;
        }
        return out;
    }

    /** Where the auto-fit repetition starts in the track list just parsed, or -1. */
    private int autoFitStart = -1;

    /** repeat() adds no tracks past this many in one list. Browsers cap grids too; a screen needs far fewer. */
    private static final int MAX_TRACKS = 500;

    private static Track copy(Track t) {
        Track c = new Track();
        c.minKind = t.minKind;
        c.maxKind = t.maxKind;
        c.minLen = t.minLen;
        c.maxLen = t.maxLen;
        c.fr = t.fr;
        return c;
    }

    double layout(Box box, double contentH) {
        Style s = box.style;
        Len.Context ctx = s.context;
        double cw = box.contentW();
        double colGap = s.columnGap == null ? 0 : Math.max(0, s.columnGap.of(cw));
        double rowGap = s.rowGap == null ? 0 : Math.max(0, s.rowGap.of(contentH));

        List<Cell> cells = new ArrayList<>();
        for (Box c : box.children) {
            if (c.isOutOfFlow()) {
                c.staticX = box.contentX();
                c.staticY = box.contentY();
                L.defer(c);
                continue;
            }
            cells.add(new Cell(c));
        }
        cells.sort(Comparator.comparingInt(c -> c.box.style.order));

        Map<String, Integer> colNames = new HashMap<>(), rowNames = new HashMap<>();
        List<Track> cols = tracks(s.gridTemplateColumns, ctx, cw, colGap, colNames);
        int fitFrom = autoFitStart;
        List<Track> rows = tracks(s.gridTemplateRows, ctx, contentH, rowGap, rowNames);
        Map<String, int[]> areas = areas(s.gridTemplateAreas);
        for (int[] a : areas.values()) {
            while (rows.size() < a[1]) rows.add(Track.of(s.gridAutoRows, ctx));
            while (cols.size() < a[3]) cols.add(Track.of(s.gridAutoColumns, ctx));
        }
        place(cells, cols, rows, areas, colNames, rowNames, s, ctx);

        // auto-fit: repeated tracks no item landed in collapse to nothing.
        if (fitFrom >= 0) {
            boolean[] used = new boolean[cols.size()];
            for (Cell c : cells) for (int i = c.col; i < c.col + c.colSpan && i < used.length; i++) used[i] = true;
            for (int i = cols.size() - 1; i >= fitFrom; i--) if (!used[i]) cols.remove(i);
        }

        // Columns.
        sizeTracks(cols, cells, true, cw, colGap, s.justifyContent);
        double[] colX = positions(cols, colGap, cw, s.justifyContent);

        // Items at their column widths, then the rows.
        for (Cell c : cells) {
            double areaW = span(cols, c.col, c.colSpan, colGap);
            L.layout(c.box, areaW, Double.NaN, itemWidth(c.box, areaW, s), Double.NaN);
        }
        sizeTracks(rows, cells, false, contentH, rowGap, s.alignContent);
        double[] rowY = positions(rows, rowGap, contentH, s.alignContent);

        // Place each item in its area.
        for (Cell c : cells) {
            Box b = c.box;
            double areaW = span(cols, c.col, c.colSpan, colGap);
            double areaH = span(rows, c.row, c.rowSpan, rowGap);
            String alignSelf = b.style.alignSelf.equals("auto") ? s.alignItems : b.style.alignSelf;
            if (alignSelf.equals("normal")) alignSelf = "stretch";
            if (alignSelf.equals("stretch") && b.style.height == null && b.style.margin[0] != null && b.style.margin[2] != null) {
                double target = L.clampHeight(b, areaH - b.m[0] - b.m[2], areaH);
                if (Math.abs(target - b.h) > 0.01) L.layout(b, areaW, areaH, itemWidth(b, areaW, s), target);
            }
            String justifySelf = b.style.justifySelf.equals("auto") ? s.justifyItems : b.style.justifySelf;
            double freeX = areaW - b.w - b.m[1] - b.m[3];
            double freeY = areaH - b.h - b.m[0] - b.m[2];
            double dx = switch (justifySelf) {
                case "center" -> freeX / 2;
                case "flex-end" -> freeX;
                default -> 0;
            };
            if (b.style.margin[3] == null && b.style.margin[1] == null) dx = freeX / 2;
            else if (b.style.margin[3] == null) dx = freeX;
            double dy = switch (alignSelf) {
                case "center" -> freeY / 2;
                case "flex-end" -> freeY;
                default -> 0;
            };
            if (b.style.margin[0] == null && b.style.margin[2] == null) dy = freeY / 2;
            else if (b.style.margin[0] == null) dy = freeY;
            b.x = box.contentX() + colX[c.col] + b.m[3] + dx;
            b.y = box.contentY() + rowY[c.row] + b.m[0] + dy;
        }
        if (!cells.isEmpty()) {
            Box first = cells.get(0).box;
            box.baseline = first.y + (Double.isNaN(first.baseline) ? first.h : first.baseline);
            box.lastBaseline = box.baseline;
        }
        if (!Double.isNaN(contentH)) return contentH;
        double total = rowGap * Math.max(0, rows.size() - 1);
        for (Track t : rows) total += t.size;
        return total;
    }

    /** An item's border-box width in an area: stretched unless it has a width or aligns otherwise. */
    private double itemWidth(Box b, double areaW, Style grid) {
        double spec = L.specifiedWidth(b, areaW);
        if (!Double.isNaN(spec)) return L.clampWidth(b, spec, areaW);
        L.edges(b, areaW);
        String js = b.style.justifySelf.equals("auto") ? grid.justifyItems : b.style.justifySelf;
        double avail = areaW - b.m[1] - b.m[3];
        boolean autoMargins = b.style.margin[1] == null || b.style.margin[3] == null;
        if ((js.equals("normal") || js.equals("stretch")) && !autoMargins && b.inner != Box.Inner.REPLACED) return L.clampWidth(b, avail, areaW);
        return L.clampWidth(b, Math.max(L.minContent(b), Math.min(L.maxContent(b), avail)), areaW);
    }

    private static double span(List<Track> tracks, int start, int n, double gap) {
        double s = gap * Math.max(0, n - 1);
        for (int i = start; i < start + n && i < tracks.size(); i++) s += tracks.get(i).size;
        return s;
    }

    /** Track sizing along one axis. [space] may be NaN (an auto-height grid). */
    private void sizeTracks(List<Track> tracks, List<Cell> cells, boolean columns, double space, double gap, String distribute) {
        for (Track t : tracks) {
            t.base = t.minKind.equals("fixed") ? t.minLen.of(space) : 0;
            t.limit = switch (t.maxKind) {
                case "fixed" -> t.maxLen.of(space);
                case "fr" -> Double.MAX_VALUE;
                default -> 0;
            };
            if (Double.isNaN(t.base)) t.base = 0;
        }
        // Content contributions of items in one track.
        for (Cell c : cells) {
            int idx = columns ? c.col : c.row;
            int n = columns ? c.colSpan : c.rowSpan;
            if (n != 1 || idx >= tracks.size()) continue;
            Track t = tracks.get(idx);
            double min, max;
            if (columns) {
                double margins = L.fixedMargins(c.box);
                min = L.minContent(c.box) + margins;
                max = L.maxContent(c.box) + margins;
            } else {
                min = max = c.box.h + c.box.m[0] + c.box.m[2];
            }
            switch (t.minKind) {
                case "auto", "min" -> t.base = Math.max(t.base, min);
                case "max" -> t.base = Math.max(t.base, max);
                default -> {}
            }
            switch (t.maxKind) {
                case "auto", "max" -> t.limit = Math.max(t.limit, max);
                case "min" -> t.limit = Math.max(t.limit, min);
                case "fit" -> t.limit = Math.max(t.limit, Math.min(max, t.maxLen == null ? max : t.maxLen.of(space)));
                default -> {}
            }
        }
        // Items spanning several tracks: any shortfall goes to the non-fixed tracks they span.
        for (Cell c : cells) {
            int idx = columns ? c.col : c.row;
            int n = columns ? c.colSpan : c.rowSpan;
            if (n <= 1) continue;
            double need = columns ? L.minContent(c.box) + L.fixedMargins(c.box) : c.box.h + c.box.m[0] + c.box.m[2];
            double have = gap * (n - 1);
            List<Track> flexible = new ArrayList<>();
            for (int i = idx; i < idx + n && i < tracks.size(); i++) {
                have += tracks.get(i).base;
                if (!tracks.get(i).minKind.equals("fixed")) flexible.add(tracks.get(i));
            }
            if (need > have && !flexible.isEmpty()) for (Track t : flexible) t.base += (need - have) / flexible.size();
        }
        for (Track t : tracks) {
            if (t.limit != Double.MAX_VALUE && t.limit < t.base) t.limit = t.base;
            t.size = t.base;
        }
        double gaps = gap * Math.max(0, tracks.size() - 1);
        double used = gaps;
        for (Track t : tracks) used += t.base;
        if (!Double.isNaN(space)) {
            // Grow non-flexible tracks towards their limits, evenly.
            double free = space - used;
            for (int round = 0; round < 8 && free > 0.01; round++) {
                List<Track> growable = new ArrayList<>();
                for (Track t : tracks) if (t.limit != Double.MAX_VALUE && t.size < t.limit - 0.01) growable.add(t);
                if (growable.isEmpty()) break;
                double each = free / growable.size();
                for (Track t : growable) {
                    double add = Math.min(each, t.limit - t.size);
                    t.size += add;
                    free -= add;
                }
            }
        } else {
            for (Track t : tracks) if (t.limit != Double.MAX_VALUE) t.size = Math.max(t.base, t.limit);
        }
        // fr tracks share what is left (or, with no definite space, take their content size).
        double frSum = 0;
        for (Track t : tracks) if (t.maxKind.equals("fr")) frSum += t.fr;
        if (frSum > 0) {
            if (Double.isNaN(space)) {
                double unit = 0;
                for (Track t : tracks) if (t.maxKind.equals("fr") && t.fr > 0) unit = Math.max(unit, t.base / t.fr);
                for (Track t : tracks) if (t.maxKind.equals("fr")) t.size = Math.max(t.base, unit * t.fr);
            } else {
                // Which tracks still flex, by index: tracks frozen at their base size drop out.
                boolean[] flex = new boolean[tracks.size()];
                int flexing = 0;
                for (int i = 0; i < flex.length; i++) {
                    if (tracks.get(i).maxKind.equals("fr")) {
                        flex[i] = true;
                        flexing++;
                    }
                }
                for (int round = 0; round < flexing + 1; round++) {
                    double fixed = gaps, sum = 0;
                    for (int i = 0; i < flex.length; i++) {
                        if (flex[i]) sum += tracks.get(i).fr;
                        else fixed += tracks.get(i).size;
                    }
                    double unit = sum <= 0 ? 0 : Math.max(0, space - fixed) / Math.max(1, sum);
                    if (sum > 0 && sum < 1) unit = Math.max(0, space - fixed);
                    boolean changed = false;
                    for (int i = 0; i < flex.length; i++) {
                        Track t = tracks.get(i);
                        if (flex[i] && t.fr * unit < t.base) {
                            t.size = t.base;
                            flex[i] = false;
                            flexing--;
                            changed = true;
                        }
                    }
                    if (!changed) {
                        for (int i = 0; i < flex.length; i++) if (flex[i]) tracks.get(i).size = tracks.get(i).fr * unit;
                        break;
                    }
                }
            }
        } else if (!Double.isNaN(space) && (distribute.equals("normal") || distribute.equals("stretch"))) {
            // No fr tracks: auto tracks stretch to fill the container.
            double free = space - gaps;
            for (Track t : tracks) free -= t.size;
            List<Track> autos = new ArrayList<>();
            for (Track t : tracks) if (t.maxKind.equals("auto")) autos.add(t);
            if (free > 0 && !autos.isEmpty()) for (Track t : autos) t.size += free / autos.size();
        }
    }

    /** Each track's start, with justify-content / align-content spreading any space left. */
    private static double[] positions(List<Track> tracks, double gap, double space, String distribute) {
        double[] out = new double[tracks.size() + 1];
        double used = gap * Math.max(0, tracks.size() - 1);
        for (Track t : tracks) used += t.size;
        double free = Double.isNaN(space) ? 0 : space - used;
        double offset = 0, between = gap;
        int n = tracks.size();
        if (free > 0 && n > 0) {
            switch (distribute) {
                case "center" -> offset = free / 2;
                case "flex-end" -> offset = free;
                case "space-between" -> between += n > 1 ? free / (n - 1) : 0;
                case "space-around" -> {
                    between += free / n;
                    offset = free / n / 2;
                }
                case "space-evenly" -> {
                    between += free / (n + 1);
                    offset = free / (n + 1);
                }
                default -> {}
            }
        }
        double p = offset;
        for (int i = 0; i < n; i++) {
            out[i] = p;
            p += tracks.get(i).size + between;
        }
        out[n] = p;
        return out;
    }

    // ---------------------------------------------------------------- placement

    private static Map<String, int[]> areas(String template) {
        Map<String, int[]> out = new HashMap<>();
        if (template == null || template.trim().equalsIgnoreCase("none")) return out;
        List<String> rows = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = template.indexOf('"', i);
            char q = '"';
            int a2 = template.indexOf('\'', i);
            if (a < 0 || (a2 >= 0 && a2 < a)) {
                a = a2;
                q = '\'';
            }
            if (a < 0) break;
            int b = template.indexOf(q, a + 1);
            if (b < 0) break;
            rows.add(template.substring(a + 1, b));
            i = b + 1;
        }
        for (int r = 0; r < rows.size(); r++) {
            String[] names = rows.get(r).trim().split("\\s+");
            for (int c = 0; c < names.length; c++) {
                String n = names[c];
                if (n.startsWith(".")) continue;
                int[] area = out.computeIfAbsent(n, k -> new int[]{Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0});
                area[0] = Math.min(area[0], r);
                area[1] = Math.max(area[1], r + 1);
                area[2] = Math.min(area[2], c);
                area[3] = Math.max(area[3], c + 1);
            }
        }
        return out;
    }

    /** A placement property as a 0-based line, a span ({-n}), or auto (Integer.MIN_VALUE). */
    private static int line(String v, int count, Map<String, int[]> areas, Map<String, Integer> names, boolean start, boolean column) {
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("auto") || v.isEmpty()) return Integer.MIN_VALUE;
        if (v.startsWith("span")) {
            String n = v.substring(4).trim();
            try {
                return -Math.max(1, Integer.parseInt(n));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        try {
            int n = Integer.parseInt(v);
            if (n > 0) return n - 1;
            if (n < 0) return Math.max(0, count + 1 + n);
            return Integer.MIN_VALUE;
        } catch (NumberFormatException e) {
            String name = v.replace("-start", "").replace("-end", "");
            int[] area = areas.get(name);
            if (area != null) return column ? (start ? area[2] : area[3]) : (start ? area[0] : area[1]);
            Integer named = names.get(v);
            if (named != null) return named - 1;
            return Integer.MIN_VALUE;
        }
    }

    private void place(List<Cell> cells, List<Track> cols, List<Track> rows, Map<String, int[]> areas,
                       Map<String, Integer> colNames, Map<String, Integer> rowNames, Style grid, Len.Context ctx) {
        int colCount = Math.max(1, cols.size());
        boolean byColumn = grid.gridAutoFlow.contains("column");
        List<boolean[]> occupied = new ArrayList<>();
        List<Cell> auto = new ArrayList<>();
        for (Cell c : cells) {
            Style s = c.box.style;
            if (s.gridAreaName != null && areas.containsKey(s.gridAreaName.toLowerCase(Locale.ROOT)) || s.gridAreaName != null && areas.containsKey(s.gridAreaName)) {
                int[] a = areas.getOrDefault(s.gridAreaName, areas.get(s.gridAreaName.toLowerCase(Locale.ROOT)));
                c.row = a[0];
                c.rowSpan = a[1] - a[0];
                c.col = a[2];
                c.colSpan = a[3] - a[2];
                continue;
            }
            int cs = line(s.gridColumnStart, colCount, areas, colNames, true, true);
            int ce = line(s.gridColumnEnd, colCount, areas, colNames, false, true);
            int rs = line(s.gridRowStart, rows.size(), areas, rowNames, true, false);
            int re = line(s.gridRowEnd, rows.size(), areas, rowNames, false, false);
            int[] col = resolve(cs, ce);
            int[] row = resolve(rs, re);
            c.colSpan = col[1];
            c.rowSpan = row[1];
            c.col = col[0];
            c.row = row[0];
            if (c.col >= 0 && c.row >= 0) mark(occupied, c);
            else auto.add(c);
        }
        for (Cell c : cells) if (c.col >= 0 && c.row >= 0 && c.box.style.gridAreaName != null) mark(occupied, c);
        for (Cell c : cells) colCount = Math.max(colCount, Math.max(0, c.col) + c.colSpan);
        // Auto-placement: sparse, row by row (or column by column).
        int cursorR = 0, cursorC = 0;
        for (Cell c : auto) {
            if (c.col >= 0) {
                // A fixed column: the next row with room there.
                int r = 0;
                while (!free(occupied, r, c.col, c.rowSpan, c.colSpan)) r++;
                c.row = r;
                mark(occupied, c);
                continue;
            }
            if (c.row >= 0) {
                int col = 0;
                while (!free(occupied, c.row, col, c.rowSpan, c.colSpan)) col++;
                c.col = col;
                mark(occupied, c);
                continue;
            }
            int span = Math.min(c.colSpan, colCount);
            c.colSpan = span;
            if (byColumn) {
                int rowCount = Math.max(1, rows.size());
                while (!free(occupied, cursorR, cursorC, c.rowSpan, c.colSpan)) {
                    cursorR++;
                    if (cursorR + c.rowSpan > rowCount) {
                        cursorR = 0;
                        cursorC++;
                    }
                }
            } else {
                while (!free(occupied, cursorR, cursorC, c.rowSpan, c.colSpan) || cursorC + span > colCount) {
                    cursorC++;
                    if (cursorC + span > colCount) {
                        cursorC = 0;
                        cursorR++;
                    }
                }
            }
            c.row = cursorR;
            c.col = cursorC;
            mark(occupied, c);
        }
        // Implicit tracks.
        int needCols = colCount, needRows = rows.size();
        for (Cell c : cells) {
            needCols = Math.max(needCols, c.col + c.colSpan);
            needRows = Math.max(needRows, c.row + c.rowSpan);
        }
        List<String> autoCols = Values.splitSpaces(grid.gridAutoColumns);
        List<String> autoRows = Values.splitSpaces(grid.gridAutoRows);
        while (cols.size() < needCols) cols.add(Track.of(autoCols.get((cols.size()) % autoCols.size()), ctx));
        while (rows.size() < needRows) rows.add(Track.of(autoRows.get(rows.size() % autoRows.size()), ctx));
    }

    /** {start, span}: start is -1 for auto. */
    private static int[] resolve(int start, int end) {
        boolean sAuto = start == Integer.MIN_VALUE, eAuto = end == Integer.MIN_VALUE;
        if (!sAuto && start < 0 && start != Integer.MIN_VALUE) {
            // "span n / x"
            int span = -start;
            if (!eAuto && end >= 0) return new int[]{Math.max(0, end - span), span};
            return new int[]{-1, span};
        }
        if (sAuto) {
            if (!eAuto && end < 0) return new int[]{-1, -end};
            if (!eAuto && end > 0) return new int[]{Math.max(0, end - 1), 1};
            return new int[]{-1, 1};
        }
        if (eAuto) return new int[]{start, 1};
        if (end < 0) return new int[]{start, -end};
        if (end <= start) return new int[]{Math.min(start, end), Math.max(1, Math.abs(start - end))};
        return new int[]{start, end - start};
    }

    private static boolean free(List<boolean[]> occ, int r, int c, int rs, int cs) {
        for (int i = r; i < r + rs; i++) {
            if (i >= occ.size()) continue;
            boolean[] row = occ.get(i);
            for (int j = c; j < c + cs; j++) if (j < row.length && row[j]) return false;
        }
        return true;
    }

    private static void mark(List<boolean[]> occ, Cell c) {
        for (int i = c.row; i < c.row + c.rowSpan; i++) {
            while (occ.size() <= i) occ.add(new boolean[0]);
            boolean[] row = occ.get(i);
            if (row.length < c.col + c.colSpan) {
                boolean[] bigger = new boolean[c.col + c.colSpan];
                System.arraycopy(row, 0, bigger, 0, row.length);
                row = bigger;
                occ.set(i, row);
            }
            for (int j = c.col; j < c.col + c.colSpan; j++) row[j] = true;
        }
    }

    /** Content-box {min, max} widths. */
    double[] intrinsic(Box box) {
        Style s = box.style;
        double gap = s.columnGap == null || s.columnGap.relative() ? 0 : s.columnGap.of(0);
        List<Track> cols = tracks(s.gridTemplateColumns, s.context, Double.NaN, gap, new HashMap<>());
        int n = Math.max(1, cols.size());
        double fixed = 0;
        int content = 0;
        for (Track t : cols) {
            if (t.maxKind.equals("fixed")) fixed += t.maxLen.of(0);
            else content++;
        }
        double minItem = 0, maxItem = 0;
        int items = 0;
        for (Box c : box.children) {
            if (c.isOutOfFlow()) continue;
            double m = L.fixedMargins(c);
            minItem = Math.max(minItem, L.minContent(c) + m);
            maxItem = Math.max(maxItem, L.maxContent(c) + m);
            items++;
        }
        if (cols.isEmpty()) return new double[]{minItem, maxItem};
        int perRow = Math.min(n, Math.max(1, items));
        double gaps = gap * (perRow - 1);
        return new double[]{fixed + content * minItem + gaps, fixed + content * maxItem + gaps};
    }
}

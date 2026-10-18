package ai.resourcepack.ui.html.layout;

import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.text.Fonts;

import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inline formatting: a block's text, inline boxes and inline-blocks broken into lines. Whitespace
 * is collapsed per {@code white-space}; lines break at spaces (and after hyphens, and anywhere in a
 * word that cannot fit when {@code overflow-wrap} or {@code word-break} allow it); each line is as tall
 * as its tallest piece around a shared baseline, then aligned per {@code text-align}.
 */
final class InlineLayout {
    private final Layout L;
    private final Map<Style, Font> fonts = new IdentityHashMap<>();
    /** Inline boxes open at the end of the line just placed: they continue on the next. */
    private List<Box> carried = new ArrayList<>();

    InlineLayout(Layout layout) {
        this.L = layout;
    }

    private enum Kind { WORD, SPACE, START, END, ATOMIC, BREAK, ABS }

    private static final class Atom {
        Kind kind;
        String text;
        Box box;        // the text box, the inline box (START/END), the atomic or absolute box
        Style style;
        Font font;
        double w;
        double minW, maxW; // ATOMIC, when measuring
        boolean wrap;      // SPACE: a line may break here; WORD: a line may break right after it
        boolean collapsible;
    }

    Font font(Style s) {
        return fonts.computeIfAbsent(s, st -> L.shaper.withSpacing(L.shaper.fonts().font(st.fontFamilies, st.fontWeight, st.italic, st.fontSize), st.letterSpacing));
    }

    double textWidth(String text, Style s) {
        return L.shaper.width(text, font(s));
    }

    Fonts.Metrics metrics(Style s) {
        return L.shaper.fonts().metrics(font(s));
    }

    // ---------------------------------------------------------------- flattening

    /** The block's inline content as atoms. [measuring]: atomic boxes are measured, not laid out. */
    private List<Atom> flatten(Box block, double avail, boolean measuring) {
        List<Atom> out = new ArrayList<>();
        boolean[] lastSpace = {true};
        flatten(block, out, avail, measuring, lastSpace);
        return out;
    }

    private void flatten(Box parent, List<Atom> out, double avail, boolean measuring, boolean[] lastSpace) {
        for (Box c : parent.children) {
            if (c.isText()) {
                text(c, out, lastSpace);
            } else if (c.inner == Box.Inner.BREAK) {
                Atom a = new Atom();
                a.kind = Kind.BREAK;
                a.box = c;
                a.style = c.style;
                out.add(a);
                lastSpace[0] = true;
            } else if (c.isOutOfFlow()) {
                Atom a = new Atom();
                a.kind = Kind.ABS;
                a.box = c;
                out.add(a);
            } else if (c.isInline()) {
                L.edges(c, avail);
                Atom start = new Atom();
                start.kind = Kind.START;
                start.box = c;
                start.style = c.style;
                start.w = c.m[3] + c.b[3] + c.p[3];
                out.add(start);
                flatten(c, out, avail, measuring, lastSpace);
                Atom end = new Atom();
                end.kind = Kind.END;
                end.box = c;
                end.style = c.style;
                end.w = c.m[1] + c.b[1] + c.p[1];
                out.add(end);
                if (start.w > 0 || end.w > 0) lastSpace[0] = false;
            } else {
                Atom a = new Atom();
                a.kind = Kind.ATOMIC;
                a.box = c;
                a.style = c.style;
                if (measuring) {
                    double margins = L.fixedMargins(c);
                    a.minW = L.minContent(c) + margins;
                    a.maxW = L.maxContent(c) + margins;
                } else {
                    L.layout(c, avail, Double.NaN, Double.NaN, Double.NaN);
                    a.w = c.outerW();
                }
                out.add(a);
                lastSpace[0] = false;
            }
        }
    }

    private void text(Box t, List<Atom> out, boolean[] lastSpace) {
        Style s = t.style;
        String ws = s.whiteSpace;
        boolean collapse = ws.equals("normal") || ws.equals("nowrap") || ws.equals("pre-line");
        boolean newlines = !ws.equals("normal") && !ws.equals("nowrap");
        boolean wrap = !ws.equals("nowrap") && !ws.equals("pre");
        Font font = font(s);
        String text = t.text;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n' && newlines) {
                Atom a = new Atom();
                a.kind = Kind.BREAK;
                a.box = t;
                a.style = s;
                out.add(a);
                lastSpace[0] = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                int j = i;
                StringBuilder spaces = new StringBuilder();
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == '\n' && newlines) break;
                    if (d != ' ' && d != '\t' && d != '\n' && d != '\r' && d != '\f') break;
                    spaces.append(d == '\t' && !collapse ? "        " : " ");
                    j++;
                }
                i = j;
                if (collapse) {
                    if (lastSpace[0]) continue;
                    spaces.setLength(1);
                }
                Atom a = new Atom();
                a.kind = Kind.SPACE;
                a.text = spaces.toString();
                a.box = t;
                a.style = s;
                a.font = font;
                a.w = L.shaper.width(a.text, font) + s.wordSpacing * a.text.length();
                a.wrap = wrap;
                a.collapsible = collapse;
                out.add(a);
                lastSpace[0] = true;
                continue;
            }
            int j = i;
            while (j < n) {
                char d = text.charAt(j);
                if (d == ' ' || d == '\t' || d == '\n' || d == '\r' || d == '\f') break;
                j++;
            }
            words(text.substring(i, j), t, s, font, wrap, out);
            lastSpace[0] = false;
            i = j;
        }
    }

    /** A run of non-space text as words, split after hyphens and between CJK characters. */
    private void words(String run, Box t, Style s, Font font, boolean wrap, List<Atom> out) {
        int start = 0;
        for (int k = 0; k < run.length(); k++) {
            char ch = run.charAt(k);
            boolean cjk = isCjk(ch);
            boolean split = (ch == '-' || ch == '—' || ch == '/' && k > 0) && k + 1 < run.length() && Character.isLetterOrDigit(run.charAt(k + 1));
            if (cjk || split || s.wordBreak.equals("break-all")) {
                if (cjk && k > start) {
                    word(run.substring(start, k), t, s, font, wrap, out);
                    start = k;
                }
                word(run.substring(start, k + 1), t, s, font, wrap, out);
                start = k + 1;
            }
        }
        if (start < run.length()) {
            Atom last = word(run.substring(start), t, s, font, false, out);
            last.wrap = false;
        }
    }

    private Atom word(String text, Box t, Style s, Font font, boolean breakAfter, List<Atom> out) {
        Atom a = new Atom();
        a.kind = Kind.WORD;
        a.text = text;
        a.box = t;
        a.style = s;
        a.font = font;
        a.w = L.shaper.width(text, font);
        a.wrap = breakAfter;
        out.add(a);
        return a;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3040 && c <= 0x30FF) || (c >= 0x3400 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    // ---------------------------------------------------------------- intrinsic sizes

    /** Content-box {min, max} widths of a block's inline content. */
    double[] intrinsic(Box block) {
        List<Atom> atoms = flatten(block, Double.NaN, true);
        double min = 0, max = 0, line = 0, unbreakable = 0;
        boolean lineHasContent = false;
        double trailingSpace = 0;
        for (Atom a : atoms) {
            switch (a.kind) {
                case BREAK -> {
                    max = Math.max(max, line - trailingSpace);
                    line = 0;
                    trailingSpace = 0;
                    lineHasContent = false;
                    min = Math.max(min, unbreakable);
                    unbreakable = 0;
                }
                case SPACE -> {
                    if (!lineHasContent && a.collapsible) break;
                    line += a.w;
                    trailingSpace = a.collapsible ? a.w : 0;
                    if (a.wrap) {
                        min = Math.max(min, unbreakable);
                        unbreakable = 0;
                    } else {
                        unbreakable += a.w;
                    }
                }
                case WORD -> {
                    line += a.w;
                    trailingSpace = 0;
                    lineHasContent = true;
                    unbreakable += a.w;
                    if (a.wrap) {
                        min = Math.max(min, unbreakable);
                        unbreakable = 0;
                    }
                }
                case START, END -> {
                    line += a.w;
                    unbreakable += a.w;
                }
                case ATOMIC -> {
                    line += a.maxW;
                    trailingSpace = 0;
                    lineHasContent = true;
                    min = Math.max(min, unbreakable);
                    min = Math.max(min, a.minW);
                    unbreakable = 0;
                }
                default -> {}
            }
        }
        max = Math.max(max, line - trailingSpace);
        min = Math.max(min, unbreakable);
        double indent = block.style.textIndent.of(0);
        return new double[]{min + Math.max(0, indent), max + Math.max(0, indent)};
    }

    // ---------------------------------------------------------------- layout

    /** Breaks the block's inline content into lines; returns their total height. */
    double layout(Box block, double contentH) {
        double avail = block.contentW();
        List<Atom> atoms = flatten(block, avail, false);
        List<List<Atom>> lines = breakLines(atoms, avail, block.style.textIndent.of(avail));
        block.lines = new ArrayList<>();
        carried = new ArrayList<>();
        double y = block.contentY();
        boolean first = true;
        int clamp = block.style.lineClamp;
        for (int li = 0; li < lines.size(); li++) {
            List<Atom> atomsOfLine = lines.get(li);
            boolean last = li == lines.size() - 1 || endsWithBreak(atomsOfLine);
            Box.Line line = place(block, atomsOfLine, avail, y, first ? block.style.textIndent.of(avail) : 0, last);
            first = false;
            if (line == null) continue;
            block.lines.add(line);
            y += line.h;
            if (clamp > 0 && block.lines.size() == clamp && li < lines.size() - 1) {
                ellipsize(line, avail);
                break;
            }
        }
        if (block.style.textOverflow.equals("ellipsis") && block.style.clips()) {
            for (Box.Line line : block.lines) if (line.w > avail + 0.5) ellipsize(line, avail);
        }
        if (!block.lines.isEmpty()) {
            Box.Line f = block.lines.get(0), l = block.lines.get(block.lines.size() - 1);
            block.baseline = f.y + f.baseline;
            block.lastBaseline = l.y + l.baseline;
        }
        return y - block.contentY();
    }

    private static boolean endsWithBreak(List<Atom> line) {
        return !line.isEmpty() && line.get(line.size() - 1).kind == Kind.BREAK;
    }

    private List<List<Atom>> breakLines(List<Atom> atoms, double avail, double indent) {
        List<List<Atom>> lines = new ArrayList<>();
        List<Atom> cur = new ArrayList<>();
        double x = indent;
        boolean content = false;
        for (int i = 0; i < atoms.size(); i++) {
            Atom a = atoms.get(i);
            switch (a.kind) {
                case BREAK -> {
                    cur.add(a);
                    lines.add(cur);
                    cur = new ArrayList<>();
                    x = 0;
                    content = false;
                }
                case SPACE -> {
                    if (!content && a.collapsible) continue;
                    cur.add(a);
                    x += a.w;
                }
                case START, END, ABS -> {
                    cur.add(a);
                    x += a.w;
                }
                case WORD, ATOMIC -> {
                    if (content && x + a.w > avail + 0.01 && canBreakBefore(cur, a)) {
                        // Move any inline-box starts that belong with this word onto the new line.
                        List<Atom> carry = new ArrayList<>();
                        while (!cur.isEmpty() && cur.get(cur.size() - 1).kind == Kind.START) carry.add(0, cur.remove(cur.size() - 1));
                        lines.add(cur);
                        cur = new ArrayList<>(carry);
                        x = 0;
                        for (Atom c : carry) x += c.w;
                        content = false;
                    }
                    if (a.kind == Kind.WORD && x + a.w > avail + 0.01 && mayBreakWord(a) && a.text.length() > 1) {
                        // A word that cannot fit on any line is broken where it overflows.
                        for (Atom piece : split(a, avail - x, avail)) {
                            if (content && x + piece.w > avail + 0.01) {
                                lines.add(cur);
                                cur = new ArrayList<>();
                                x = 0;
                            }
                            cur.add(piece);
                            x += piece.w;
                            content = true;
                        }
                        continue;
                    }
                    cur.add(a);
                    x += a.w;
                    content = true;
                }
            }
        }
        if (!cur.isEmpty()) lines.add(cur);
        return lines;
    }

    /** Whether the line may break between what is on it so far and [next]. */
    private static boolean canBreakBefore(List<Atom> cur, Atom next) {
        for (int k = cur.size() - 1; k >= 0; k--) {
            Atom p = cur.get(k);
            switch (p.kind) {
                case START, END, ABS -> {
                    continue;
                }
                case SPACE -> {
                    return p.wrap;
                }
                case WORD -> {
                    return p.wrap || (next.kind == Kind.ATOMIC && !p.style.whiteSpace.equals("nowrap") && !p.style.whiteSpace.equals("pre"));
                }
                case ATOMIC -> {
                    String ws = next.style == null ? "normal" : next.style.whiteSpace;
                    return !ws.equals("nowrap") && !ws.equals("pre");
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean mayBreakWord(Atom a) {
        String ow = a.style.overflowWrap, wb = a.style.wordBreak;
        return ow.equals("anywhere") || ow.equals("break-word") || wb.equals("break-all") || wb.equals("break-word");
    }

    private List<Atom> split(Atom a, double first, double avail) {
        List<Atom> out = new ArrayList<>();
        String text = a.text;
        // Pieces are taken from [start] on, not cut off a shrinking copy: a 1 MB word was copied once per piece.
        int start = 0, n = text.length();
        double room = Math.max(first, 0);
        while (start < n) {
            int k = 1;
            while (k < n - start && L.shaper.width(text.substring(start, start + k + 1), a.font) <= room) k++;
            if (k == 1 && room < avail && out.isEmpty() && L.shaper.width(text.substring(start, start + 1), a.font) > room) {
                room = avail;
                continue;
            }
            Atom p = new Atom();
            p.kind = Kind.WORD;
            p.text = text.substring(start, start + k);
            p.box = a.box;
            p.style = a.style;
            p.font = a.font;
            p.w = L.shaper.width(p.text, a.font);
            p.wrap = true;
            out.add(p);
            start += k;
            room = avail;
        }
        return out;
    }

    /** Positions one line's atoms; null for a line with nothing on it worth a line box. */
    private Box.Line place(Box block, List<Atom> atoms, double avail, double y, double indent, boolean lastLine) {
        // Trailing collapsible spaces hang off the end and are not drawn.
        int end = atoms.size();
        while (end > 0) {
            Atom a = atoms.get(end - 1);
            if (a.kind == Kind.SPACE && a.collapsible) end--;
            else if (a.kind == Kind.BREAK) {
                end--;
                break;
            } else break;
        }
        List<Atom> line = new ArrayList<>(atoms.subList(0, end));
        while (end < atoms.size()) {
            Atom a = atoms.get(end++);
            if (a.kind == Kind.END || a.kind == Kind.ABS) line.add(a);
        }
        boolean hasContent = false;
        for (Atom a : line) {
            if (a.kind == Kind.WORD || a.kind == Kind.ATOMIC || (a.kind == Kind.SPACE && !a.collapsible)) hasContent = true;
            if ((a.kind == Kind.START || a.kind == Kind.END) && a.w > 0) hasContent = true;
        }
        boolean forcedBreak = endsWithBreak(atoms);
        if (!hasContent && !forcedBreak) {
            // Nothing but absolutes: they still get a static position here.
            for (Atom a : line) if (a.kind == Kind.ABS) {
                a.box.staticX = block.contentX() + indent;
                a.box.staticY = y;
                L.defer(a.box);
            }
            return null;
        }
        // Horizontal positions.
        double width = indent;
        int spaces = 0;
        for (Atom a : line) {
            width += a.w;
            if (a.kind == Kind.SPACE) spaces++;
        }
        String align = block.style.textAlign;
        double offset = switch (align) {
            case "right", "end", "-webkit-right" -> avail - width;
            case "center", "-webkit-center" -> (avail - width) / 2;
            default -> 0;
        };
        offset = Math.max(0, offset) + indent;
        double extra = align.equals("justify") && !lastLine && spaces > 0 && width < avail ? (avail - width) / spaces : 0;

        Style bs = block.style;
        Fonts.Metrics strutMetrics = metrics(bs);
        double strutL = bs.lineHeight(strutMetrics.normalLineHeight());
        double strutHalf = (strutL - strutMetrics.ascent() - strutMetrics.descent()) / 2;
        double ascent = strutMetrics.ascent() + strutHalf, descent = strutMetrics.descent() + strutHalf;
        boolean onlyAtomics = true;
        for (Atom a : line) if (a.kind == Kind.WORD || a.kind == Kind.SPACE) onlyAtomics = false;

        Box.Line out = new Box.Line();
        out.x = block.contentX();
        out.y = y;
        out.w = width + extra * spaces - indent;
        double x = block.contentX() + offset;
        // In the order the boxes opened, which is outermost first: open boxes are nested in one another.
        Map<Box, Box.Fragment> open = new java.util.LinkedHashMap<>();
        Map<Box, Double> openShift = new HashMap<>();
        List<Box.Fragment> fragments = new ArrayList<>();
        List<Box> fragmentBoxes = new ArrayList<>();
        List<Atom> atomics = new ArrayList<>();
        List<Double> atomicShift = new ArrayList<>();
        Box.Item item = null;
        // The text of [item] while words are added to it, set once it is complete: adding to a String
        // copies it, and a long unbreakable line of many words copied itself once per word.
        StringBuilder itemText = null;
        double shift = 0;
        // Inline boxes still open from the previous line carry on here, without their start edge.
        for (Box c : carried) {
            Box.Fragment f = new Box.Fragment();
            f.x = x;
            open.put(c, f);
            shift += baselineShift(c.style, ascent);
            openShift.put(c, shift);
        }
        for (Atom a : line) {
            switch (a.kind) {
                case START -> {
                    Box.Fragment f = new Box.Fragment();
                    f.first = true;
                    f.x = x + a.box.m[3];
                    open.put(a.box, f);
                    shift += baselineShift(a.style, ascent);
                    openShift.put(a.box, shift);
                    x += a.w;
                    if (itemText != null) item.text = itemText.toString();
                    itemText = null;
                    item = null;
                    // Each inline box's own font and line height count towards the line, even when empty.
                    Fonts.Metrics m = metrics(a.style);
                    double lh = a.style.lineHeight(m.normalLineHeight());
                    double half = (lh - m.ascent() - m.descent()) / 2;
                    ascent = Math.max(ascent, m.ascent() + half - shift);
                    descent = Math.max(descent, m.descent() + half + shift);
                }
                case END -> {
                    Box.Fragment f = open.remove(a.box);
                    if (f == null) {
                        f = new Box.Fragment();
                        f.x = block.contentX() + offset;
                    }
                    f.last = true;
                    x += a.w;
                    f.w = x - a.box.m[1] - f.x;
                    fragments.add(f);
                    fragmentBoxes.add(a.box);
                    Double s = openShift.remove(a.box);
                    shift -= baselineShift(a.style, ascent);
                    if (s != null) a.box.baseline = s;
                    if (itemText != null) item.text = itemText.toString();
                    itemText = null;
                    item = null;
                }
                case WORD, SPACE -> {
                    double w = a.w + (a.kind == Kind.SPACE ? extra * a.text.length() / Math.max(1, a.text.length()) : 0);
                    if (item != null && item.box == a.box && item.style == a.style && extra == 0) {
                        if (itemText == null) itemText = new StringBuilder(item.text);
                        itemText.append(a.text);
                        item.w += w;
                    } else {
                        if (itemText != null) item.text = itemText.toString();
                        itemText = null;
                        item = new Box.Item();
                        item.box = a.box;
                        item.text = a.text;
                        item.font = a.font;
                        item.style = a.style;
                        item.x = x;
                        item.w = w;
                        item.baselineY = shift;
                        out.items.add(item);
                    }
                    if (a.kind == Kind.WORD) {
                        Fonts.Metrics m = metrics(a.style);
                        double lh = a.style.lineHeight(m.normalLineHeight());
                        double half = (lh - m.ascent() - m.descent()) / 2;
                        ascent = Math.max(ascent, m.ascent() + half - shift);
                        descent = Math.max(descent, m.descent() + half + shift);
                    }
                    x += w;
                }
                case ATOMIC -> {
                    Box.Item it = new Box.Item();
                    it.box = a.box;
                    it.x = x;
                    it.w = a.w;
                    it.style = a.style;
                    out.items.add(it);
                    atomics.add(a);
                    atomicShift.add(shift);
                    double[] ad = atomicAscentDescent(a.box, block.style);
                    String va = a.box.style.verticalAlign;
                    if (!va.equals("top") && !va.equals("bottom")) {
                        ascent = Math.max(ascent, ad[0] - shift);
                        descent = Math.max(descent, ad[1] + shift);
                    }
                    a.box.x = x + a.box.m[3];
                    x += a.w;
                    if (itemText != null) item.text = itemText.toString();
                    itemText = null;
                    item = null;
                }
                case ABS -> {
                    a.box.staticX = x;
                    a.box.staticY = y;
                    L.defer(a.box);
                }
                default -> {}
            }
        }
        if (itemText != null) item.text = itemText.toString();
        // Boxes still open at the end of the line carry on to the next one, outermost first.
        List<Box> next = new ArrayList<>();
        for (Map.Entry<Box, Box.Fragment> e : open.entrySet()) {
            Box.Fragment f = e.getValue();
            f.w = x - f.x;
            fragments.add(f);
            fragmentBoxes.add(e.getKey());
            next.add(e.getKey());
        }
        carried = next;
        // A line with only atomic boxes (images in a row) needs no strut beyond them.
        if (onlyAtomics && !atomics.isEmpty() && strutL == 0) {
            ascent = 0;
            descent = 0;
        }
        double h = ascent + descent;
        for (Atom a : atomics) {
            String va = a.box.style.verticalAlign;
            if (va.equals("top") || va.equals("bottom")) h = Math.max(h, a.box.outerH());
        }
        out.h = h;
        out.baseline = ascent;
        double baselineY = y + ascent;
        for (Box.Item it : out.items) if (it.text != null) it.baselineY = baselineY + it.baselineY;
        for (int k = 0; k < atomics.size(); k++) {
            Box ab = atomics.get(k).box;
            String va = ab.style.verticalAlign;
            if (va.equals("top")) ab.y = y + ab.m[0];
            else if (va.equals("bottom")) ab.y = y + h - ab.outerH() + ab.m[0];
            else ab.y = baselineY + atomicShift.get(k) - atomicAscentDescent(ab, block.style)[0] + ab.m[0];
        }
        for (int k = 0; k < fragments.size(); k++) {
            Box fb = fragmentBoxes.get(k);
            Box.Fragment f = fragments.get(k);
            Fonts.Metrics m = metrics(fb.style);
            double s = Double.isNaN(fb.baseline) ? 0 : fb.baseline;
            f.y = baselineY + s - m.ascent() - fb.p[0] - fb.b[0];
            f.h = m.ascent() + m.descent() + fb.p[0] + fb.p[2] + fb.b[0] + fb.b[2];
            if (fb.fragments == null) fb.fragments = new ArrayList<>();
            fb.fragments.add(f);
            fb.baseline = Double.NaN;
        }
        return out;
    }

    /** How far (down, in pixels) vertical-align moves an inline box's baseline. */
    private double baselineShift(Style s, double ascent) {
        return switch (s.verticalAlign) {
            case "sub" -> s.fontSize * 0.25;
            case "super" -> -s.fontSize * 0.4;
            case "length" -> s.verticalAlignLength == null ? 0 : -s.verticalAlignLength.of(s.lineHeight(s.fontSize * 1.2));
            case "middle" -> 0;
            default -> 0;
        };
    }

    /** {above, below} the baseline for an atomic box's margin box. */
    private double[] atomicAscentDescent(Box b, Style parent) {
        double outer = b.outerH();
        String va = b.style.verticalAlign;
        if (va.equals("middle")) {
            double xh = metrics(parent).xHeight();
            return new double[]{outer / 2 + xh / 2, outer / 2 - xh / 2};
        }
        double base;
        boolean hasBaseline = !Double.isNaN(b.lastBaseline) && !b.style.clips() && b.inner != Box.Inner.REPLACED;
        if (hasBaseline) base = b.m[0] + (b.inner == Box.Inner.FLEX || b.inner == Box.Inner.GRID ? b.baseline : b.lastBaseline);
        else base = outer;
        if (Double.isNaN(base)) base = outer;
        if (va.equals("text-bottom")) {
            Fonts.Metrics m = metrics(parent);
            return new double[]{outer - m.descent(), m.descent()};
        }
        if (va.equals("text-top")) {
            Fonts.Metrics m = metrics(parent);
            return new double[]{m.ascent(), outer - m.ascent()};
        }
        return new double[]{base, outer - base};
    }

    /**
     * The longest start of [t] no wider than [room], in characters. Found by doubling and then halving
     * rather than by dropping one character at a time from the end, which measured a megabyte of
     * nowrap text a million times over; widths only grow as characters are added, so it is the same length.
     */
    private int fitting(String t, Font font, double room) {
        int lo = 0, hi = 1;
        while (hi <= t.length() && L.shaper.width(t.substring(0, hi), font) <= room) {
            lo = hi;
            hi *= 2;
        }
        hi = Math.min(hi, t.length() + 1);
        // lo fits (or is 0), hi does not (or is past the end).
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (L.shaper.width(t.substring(0, mid), font) <= room) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Cuts a line's text so it ends in "…" within [avail]. */
    private void ellipsize(Box.Line line, double avail) {
        Box.Item lastText = null;
        for (Box.Item it : line.items) if (it.text != null) lastText = it;
        if (lastText == null) return;
        Font f = lastText.font;
        double dots = L.shaper.width("…", f);
        double limit = line.x + avail - dots;
        List<Box.Item> keep = new ArrayList<>();
        for (Box.Item it : line.items) {
            if (it.x + it.w <= limit) {
                keep.add(it);
                continue;
            }
            if (it.text != null && it.x < limit) {
                String t = it.text.substring(0, fitting(it.text, it.font, limit - it.x));
                it.text = t.stripTrailing() + "…";
                it.w = L.shaper.width(it.text, it.font);
                keep.add(it);
            }
            break;
        }
        if (keep.isEmpty() || keep.get(keep.size() - 1).text == null || !keep.get(keep.size() - 1).text.endsWith("…")) {
            Box.Item dotsItem = new Box.Item();
            dotsItem.box = lastText.box;
            dotsItem.style = lastText.style;
            dotsItem.font = f;
            dotsItem.text = "…";
            dotsItem.w = dots;
            dotsItem.baselineY = lastText.baselineY;
            dotsItem.x = keep.isEmpty() ? line.x : keep.get(keep.size() - 1).x + keep.get(keep.size() - 1).w;
            keep.add(dotsItem);
        }
        line.items.clear();
        line.items.addAll(keep);
        line.w = Math.min(line.w, avail);
    }
}

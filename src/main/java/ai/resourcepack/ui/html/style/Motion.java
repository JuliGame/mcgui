package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.css.Stylesheet;
import ai.resourcepack.ui.html.css.Values;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * CSS animations ({@code @keyframes}, {@code animation}) and transitions ({@code transition}) for one copy of a
 * page as it is shown: the clocks they run on, and the transitions under way. Each time the page is styled, an
 * element that animates has its declared values replaced with the values at that moment, worked out from the
 * declared ones as text: numbers with the same units, colours and transform functions move smoothly, anything
 * else changes halfway. The cascade then computes the rest as ever, so any property can be animated, layout
 * included, and a picture is just a picture of that moment. Whoever shows the page draws it again while
 * {@link #active()}, repainting only what {@link #moved()}.
 *
 * <p>Two clocks, both set by whoever draws ({@link #moment}): the viewer's, from when the page was shown to them,
 * which transitions and animations that end run on; and the page's, the same for every viewer, which animations that
 * repeat for ever run on (their delay only shifts where in the loop they are). So while only those move
 * ({@link #personal()} is false), every viewer of the page in the same state sees the same picture at the same
 * moment, and one picture can serve them all.
 *
 * <p>Styling finds the motion of the picture being drawn through {@link #current()}, set around the drawing
 * by whoever draws it; with none (a page drawn once, for a test), nothing moves.
 */
public final class Motion {
    private static final ThreadLocal<Motion> CURRENT = new ThreadLocal<>();

    /** Properties {@code transition: all} moves; any other has to be named. */
    private static final Set<String> ALL = Set.of("color", "background-color", "opacity", "transform", "border-color",
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color", "width", "height",
            "top", "right", "bottom", "left", "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding-top", "padding-right", "padding-bottom", "padding-left", "box-shadow", "text-shadow", "filter",
            "letter-spacing", "font-size", "border-radius", "outline-color", "fill", "stroke");

    /**
     * What a keyframe that does not give a property starts or ends at when the element does not declare it either: the
     * property's initial value, for those whose initial value can be written down without the element's other values.
     */
    private static final Map<String, String> INITIAL = Map.ofEntries(Map.entry("opacity", "1"), Map.entry("transform", "none"),
            Map.entry("filter", "none"), Map.entry("background-color", "rgba(0, 0, 0, 0)"), Map.entry("box-shadow", "none"),
            Map.entry("text-shadow", "none"), Map.entry("letter-spacing", "0px"), Map.entry("border-radius", "0px"),
            Map.entry("margin-top", "0px"), Map.entry("margin-right", "0px"), Map.entry("margin-bottom", "0px"), Map.entry("margin-left", "0px"),
            Map.entry("padding-top", "0px"), Map.entry("padding-right", "0px"), Map.entry("padding-bottom", "0px"), Map.entry("padding-left", "0px"));

    /** A clock stopped at so many seconds, for a picture of one moment (a test); null runs on the one {@link #moment} sets. */
    private Double stopped;
    /** The moment the next pictures are of: seconds on the viewer's clock and on the page's (see {@link #moment}). */
    private double viewerAt, pageAt;
    /** The moment of this styling pass, on the viewer's clock and on the page's. */
    private double now, pageNow;
    /** Styling passes so far: an animation on the page from the first is as old as the page. */
    private int passes;
    private volatile boolean active, personal;
    private boolean moving, own;
    /** The elements that moved (or were waiting to) in this pass, and in the last whole one. */
    private Set<Element> movingNow = newSet(), moved = Set.of();
    /**
     * Per element (its place in document order): what each transitioned property was last declared as, transitions
     * under way, and when each animation that ends began (viewer's clock).
     */
    private final Map<Integer, Map<String, String>> last = new HashMap<>();
    private final Map<Integer, Map<String, Run>> running = new HashMap<>();
    private final Map<Integer, Map<String, Double>> began = new HashMap<>();

    private record Run(String from, String to, double start, double duration, Easing easing) {}

    public Motion() {
        this(null);
    }

    private Motion(Double stopped) {
        this.stopped = stopped;
    }

    /** A motion whose clocks stay at [seconds] after the page was shown: every picture is of that moment. */
    public static Motion at(double seconds) {
        return new Motion(seconds);
    }

    /** Moves a stopped clock (see {@link #at}) on to [seconds]. */
    public void stopAt(double seconds) {
        stopped = seconds;
    }

    /**
     * The next pictures are of [viewer] seconds after the page was shown to its viewer, and [page] seconds on the page's
     * own clock (shared by every viewer). Set by whoever draws, before drawing; a stopped clock ({@link #at}) ignores it.
     */
    public void moment(double viewer, double page) {
        viewerAt = viewer;
        pageAt = page;
    }

    /** The motion of the picture this thread is drawing, or null. */
    public static Motion current() {
        return CURRENT.get();
    }

    /** Sets the motion of the pictures this thread draws until {@link #clear()}. */
    public static void set(Motion m) {
        CURRENT.set(m);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Whether the last picture drawn had anything still moving (or waiting to): draw it again. */
    public boolean active() {
        return active;
    }

    /**
     * Whether anything moving in the last picture runs on its viewer's clock (a transition, or an animation that ends):
     * then its pictures are this viewer's own. While only animations that repeat for ever move, they are the page's.
     */
    public boolean personal() {
        return personal;
    }

    /** The elements that moved, or were waiting to, in the last whole styling pass: what the next picture repaints. */
    public Set<Element> moved() {
        return moved;
    }

    /**
     * Whether this motion has styled the page yet. One that has not knows nothing of what was shown before, so a hover
     * would start no transition: the next picture with it is drawn whole, which teaches it.
     */
    public boolean styled() {
        return passes > 0;
    }

    /** Whether this motion keeps anything for the element at [at]: it is styled afresh every time, so that it is kept up to date. */
    boolean tracks(int at) {
        return last.containsKey(at) || running.containsKey(at) || began.containsKey(at);
    }

    /**
     * What the last picture drawn with this motion was laid out as (HtmlRenderer's, opaque here), kept while something
     * moves: the next picture, if what moved changed only what painting reads, paints it again rather than laying the
     * page out afresh. Never copied ({@link #copy}): its boxes are one picture's at a time, as this motion is.
     */
    private Object layout;

    /** The layout kept (see above), handed over: it is the caller's until it {@link #keepLayout}s one again. Or null. */
    public Object takeLayout() {
        Object l = layout;
        layout = null;
        return l;
    }

    /** Keeps [l], the layout of the picture just styled with this motion, if something on it still moves. */
    public void keepLayout(Object l) {
        layout = active ? l : null;
    }

    /** A copy of this motion, which goes on from where this one is without changing it. */
    public Motion copy() {
        Motion m = new Motion(stopped);
        m.viewerAt = viewerAt;
        m.pageAt = pageAt;
        m.now = now;
        m.pageNow = pageNow;
        m.passes = passes;
        m.active = active;
        m.personal = personal;
        m.moved = moved;
        for (Map.Entry<Integer, Map<String, String>> e : last.entrySet()) m.last.put(e.getKey(), new HashMap<>(e.getValue()));
        for (Map.Entry<Integer, Map<String, Run>> e : running.entrySet()) m.running.put(e.getKey(), new HashMap<>(e.getValue()));
        for (Map.Entry<Integer, Map<String, Double>> e : began.entrySet()) m.began.put(e.getKey(), new HashMap<>(e.getValue()));
        return m;
    }

    /**
     * A copy of this motion as if its last styling pass had been at [viewer] seconds on its viewer's clock: everything
     * on that clock (transitions, animations that end) moved on or back by the difference. How a viewer takes on the
     * motion of a picture drawn for it at another moment (ahead of time, or by another viewer), which then starts when
     * it is shown.
     */
    public Motion copyAt(double viewer) {
        Motion m = copy();
        double by = viewer - now;
        if (by == 0) return m;
        m.now = viewer;
        for (Map<String, Run> runs : m.running.values()) {
            for (Map.Entry<String, Run> e : runs.entrySet()) {
                Run r = e.getValue();
                e.setValue(new Run(r.from, r.to, r.start + by, r.duration, r.easing));
            }
        }
        for (Map<String, Double> b : m.began.values()) b.replaceAll((k, v) -> v + by);
        return m;
    }

    /** A styling pass starts: the moment it is of. */
    void begin() {
        now = stopped != null ? stopped : viewerAt;
        pageNow = stopped != null ? stopped : pageAt;
        moving = false;
        own = false;
        movingNow = newSet();
    }

    /** A styling pass ended. */
    void end() {
        passes++;
        active = moving;
        personal = own;
        moved = Collections.unmodifiableSet(movingNow);
    }

    private static Set<Element> newSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Whether declared values [d] animate or transition anything. */
    static boolean moves(Map<String, String> d) {
        if (d == null) return false;
        String name = d.get("animation-name");
        if (name != null && !name.trim().equalsIgnoreCase("none")) return true;
        String duration = d.get("transition-duration");
        if (duration == null) return false;
        for (String t : split(duration)) if (time(t) > 0) return true;
        return false;
    }

    /**
     * Puts element [e]'s (at [at] in document order) animated and transitioning values into its declared values [all],
     * for this moment. Called for every element whose declared values move, and every one this motion {@link #tracks}.
     */
    void apply(int at, Element e, Map<String, String> all, Stylesheet sheet) {
        Map<String, String> base = new HashMap<>(all);
        boolean was = moving;
        moving = false;
        animate(at, all, base, sheet);
        transition(at, all, base);
        if (moving) movingNow.add(e);
        moving |= was;
    }

    // ---------------------------------------------------------------- animations

    private void animate(int at, Map<String, String> all, Map<String, String> base, Stylesheet sheet) {
        List<String> names = split(all.getOrDefault("animation-name", "none"));
        List<String> durations = split(all.getOrDefault("animation-duration", "0s")), delays = split(all.getOrDefault("animation-delay", "0s"));
        List<String> counts = split(all.getOrDefault("animation-iteration-count", "1")), directions = split(all.getOrDefault("animation-direction", "normal"));
        List<String> fills = split(all.getOrDefault("animation-fill-mode", "none")), easings = split(all.getOrDefault("animation-timing-function", "ease"));
        List<String> states = split(all.getOrDefault("animation-play-state", "running"));
        Map<String, Double> began = this.began.get(at), still = null;
        for (int i = 0; i < names.size(); i++) {
            String name = unquote(names.get(i));
            List<Stylesheet.Keyframe> frames = sheet == null ? null : sheet.keyframes.get(name);
            if (frames == null || frames.isEmpty()) continue;
            double duration = time(at(durations, i)), delay = time(at(delays, i));
            String count = at(counts, i).toLowerCase(Locale.ROOT);
            double iterations = count.equals("infinite") ? Double.POSITIVE_INFINITY : number(count, 1);
            boolean forever = Double.isInfinite(iterations);
            // One that ends began when it was first applied (as old as the page, from the first pass): on the viewer's clock.
            double start = 0;
            if (!forever) {
                Double b = began == null ? null : began.get(name);
                start = b != null ? b : passes == 0 ? 0 : now;
                if (still == null) still = new HashMap<>();
                still.put(name, start);
            }
            String direction = at(directions, i).toLowerCase(Locale.ROOT), fill = at(fills, i).toLowerCase(Locale.ROOT);
            Easing easing = Easing.parse(at(easings, i));
            // Paused stays where it starts: nothing here keeps time while paused.
            double t = at(states, i).equalsIgnoreCase("paused") ? 0 : (forever ? pageNow : now - start) - delay;
            double progress;
            if (duration <= 0 || iterations <= 0) {
                if (!fill.equals("forwards") && !fill.equals("both")) continue;
                progress = end(iterations, direction);
            } else if (t < 0) {
                moved(forever);
                if (!fill.equals("backwards") && !fill.equals("both")) continue;
                progress = directed(0, 0, direction);
            } else if (t / duration >= iterations) {
                if (!fill.equals("forwards") && !fill.equals("both")) continue;
                progress = end(iterations, direction);
            } else {
                moved(forever);
                double whole = t / duration;
                long iteration = (long) Math.floor(whole);
                progress = directed(whole - iteration, iteration, direction);
            }
            frame(frames, progress, easing, all, base);
        }
        // Those no longer applied are forgotten: applied again, they start again.
        if (still == null) this.began.remove(at);
        else this.began.put(at, still);
    }

    /** Something moved this pass: on the page's clock if [page], else on the viewer's. */
    private void moved(boolean page) {
        moving = true;
        if (!page) own = true;
    }

    /** Where an animation ends, by its direction: the end of its last iteration. */
    private static double end(double iterations, String direction) {
        if (Double.isInfinite(iterations)) iterations = 1;
        double whole = Math.max(iterations, 0);
        long last = (long) Math.ceil(whole) - 1;
        double frac = whole - Math.floor(whole);
        return directed(frac == 0 ? 1 : frac, Math.max(last, 0), direction);
    }

    private static double directed(double frac, long iteration, String direction) {
        boolean reverse = switch (direction) {
            case "reverse" -> true;
            case "alternate" -> iteration % 2 == 1;
            case "alternate-reverse" -> iteration % 2 == 0;
            default -> false;
        };
        return reverse ? 1 - frac : frac;
    }

    /**
     * The values at [progress] (0 to 1) through [frames], into [all]; an end no frame gives is the element's own value,
     * or the property's initial one.
     */
    private static void frame(List<Stylesheet.Keyframe> frames, double progress, Easing easing, Map<String, String> all, Map<String, String> base) {
        Set<String> props = new HashSet<>();
        for (Stylesheet.Keyframe k : frames) props.addAll(k.values().keySet());
        props.remove("animation-timing-function");
        for (String prop : props) {
            List<double[]> at = new ArrayList<>();
            List<String> values = new ArrayList<>();
            List<Easing> eases = new ArrayList<>();
            for (Stylesheet.Keyframe k : frames) {
                String v = k.values().get(prop);
                if (v == null) continue;
                String own = k.values().get("animation-timing-function");
                at.add(new double[]{k.offset()});
                values.add(v);
                eases.add(own != null ? Easing.parse(own) : easing);
            }
            String own = base.get(prop);
            if (own == null) own = INITIAL.get(prop);
            if (own != null && at.get(0)[0] > 0) {
                at.add(0, new double[]{0});
                values.add(0, own);
                eases.add(0, easing);
            }
            if (own != null && at.get(at.size() - 1)[0] < 1) {
                at.add(new double[]{1});
                values.add(own);
                eases.add(easing);
            }
            String value;
            if (progress <= at.get(0)[0]) {
                value = values.get(0);
            } else if (progress >= at.get(at.size() - 1)[0]) {
                value = values.get(values.size() - 1);
            } else {
                int i = 0;
                while (i + 1 < at.size() && at.get(i + 1)[0] < progress) i++;
                double from = at.get(i)[0], to = at.get(i + 1)[0];
                double t = to > from ? (progress - from) / (to - from) : 1;
                value = mix(prop, values.get(i), values.get(i + 1), eases.get(i).at(t));
            }
            all.put(prop, value);
        }
    }

    // ---------------------------------------------------------------- transitions

    private void transition(int at, Map<String, String> all, Map<String, String> base) {
        String duration = all.get("transition-duration");
        Map<String, String> before = last.get(at);
        Map<String, Run> runs = running.get(at);
        if (duration != null) {
            List<String> props = split(all.getOrDefault("transition-property", "all")), durations = split(duration);
            List<String> delays = split(all.getOrDefault("transition-delay", "0s")), easings = split(all.getOrDefault("transition-timing-function", "ease"));
            for (int i = 0; i < props.size(); i++) {
                String p = props.get(i).toLowerCase(Locale.ROOT);
                double d = time(at(durations, i));
                if (d <= 0 || p.equals("none")) continue;
                for (String prop : p.equals("all") ? ALL : Set.of(p)) {
                    String target = declared(base, prop);
                    if (target == null) continue;
                    if (before == null) last.put(at, before = new HashMap<>());
                    String was = before.put(prop, target);
                    if (was == null || was.equals(target)) continue;
                    // From where it is now, if it was already on its way somewhere.
                    Run run = runs == null ? null : runs.get(prop);
                    String from = run != null ? value(prop, run) : was;
                    if (runs == null) running.put(at, runs = new HashMap<>());
                    runs.put(prop, new Run(from, target, now + time(at(delays, i)), d, Easing.parse(at(easings, i))));
                }
            }
        }
        // What it no longer transitions is still followed, so a transition declared again later starts from where it is.
        if (before != null) {
            for (Map.Entry<String, String> e : before.entrySet()) {
                String target = declared(base, e.getKey());
                if (target != null) e.setValue(target);
            }
        }
        if (runs == null) return;
        runs.entrySet().removeIf(e -> now >= e.getValue().start + e.getValue().duration);
        if (runs.isEmpty()) {
            running.remove(at);
            return;
        }
        for (Map.Entry<String, Run> e : runs.entrySet()) {
            all.put(e.getKey(), value(e.getKey(), e.getValue()));
            moved(false);
        }
    }

    /** [prop] as declared in [d], or its initial value if it is not and that can be written down; else null. */
    private static String declared(Map<String, String> d, String prop) {
        String v = d.get(prop);
        return v != null ? v : INITIAL.get(prop);
    }

    private String value(String prop, Run r) {
        double t = (now - r.start) / r.duration;
        return mix(prop, r.from, r.to, r.easing.at(Math.max(0, Math.min(1, t))));
    }

    // ---------------------------------------------------------------- values

    /** [a] and [b], [t] of the way from one to the other: numbers, colours and transforms smoothly, anything else halfway. */
    static String mix(String prop, String a, String b, double t) {
        if (t <= 0 || a.equals(b)) return a;
        if (t >= 1) return b;
        if (prop.equals("transform")) {
            if (a.trim().equalsIgnoreCase("none")) a = identity(b);
            if (b.trim().equalsIgnoreCase("none")) b = identity(a);
        }
        List<Token> x = tokens(a), y = tokens(b);
        if (x.size() != y.size()) return t < 0.5 ? a : b;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < x.size(); i++) {
            Token p = x.get(i), q = y.get(i);
            if (p instanceof Num m && q instanceof Num n) {
                String unit = m.unit.isEmpty() ? n.unit : n.unit.isEmpty() ? m.unit : m.unit;
                if (!m.unit.isEmpty() && !n.unit.isEmpty() && !m.unit.equalsIgnoreCase(n.unit)) return t < 0.5 ? a : b;
                out.append(format(m.value + (n.value - m.value) * t)).append(unit);
            } else if (p instanceof Col m && q instanceof Col n) {
                int ca = m.argb, cb = n.argb;
                double alpha = ((ca >>> 24) + ((cb >>> 24) - (ca >>> 24)) * t) / 255.0;
                out.append("rgba(").append(channel(ca, cb, 16, t)).append(", ").append(channel(ca, cb, 8, t)).append(", ")
                        .append(channel(ca, cb, 0, t)).append(", ").append(format(alpha)).append(')');
            } else if (p instanceof Text m && q instanceof Text n && m.text.equalsIgnoreCase(n.text)) {
                out.append(m.text);
            } else {
                return t < 0.5 ? a : b;
            }
        }
        return out.toString();
    }

    private static long channel(int a, int b, int shift, double t) {
        int x = (a >> shift) & 0xFF, y = (b >> shift) & 0xFF;
        return Math.round(x + (y - x) * t);
    }

    /** A transform that does nothing, in [like]'s functions: 1 for scales, 0 for everything else. */
    private static String identity(String like) {
        StringBuilder out = new StringBuilder();
        String fn = "";
        for (Token tk : tokens(like)) {
            if (tk instanceof Text tx) {
                out.append(tx.text);
                int open = tx.text.lastIndexOf('(');
                if (open >= 0) fn = tx.text.substring(0, open).trim().toLowerCase(Locale.ROOT);
                if (tx.text.contains(")")) fn = "";
            } else if (tk instanceof Num n) {
                out.append(fn.startsWith("scale") ? "1" : "0").append(fn.startsWith("scale") ? "" : n.unit);
            } else {
                out.append("rgba(0, 0, 0, 0)");
            }
        }
        return out.toString();
    }

    private sealed interface Token permits Num, Col, Text {}
    private record Num(double value, String unit) implements Token {}
    private record Col(int argb) implements Token {}
    private record Text(String text) implements Token {}

    /** [s] as numbers (with their units), colours and the text between, runs of text joined. */
    private static List<Token> tokens(String s) {
        List<Token> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0, n = s.length();
        BiConsumer<List<Token>, StringBuilder> flush = (list, sb) -> {
            if (!sb.isEmpty()) list.add(new Text(sb.toString().replaceAll("\\s+", " ")));
            sb.setLength(0);
        };
        while (i < n) {
            char c = s.charAt(i);
            if (c == '#') {
                int j = i + 1;
                while (j < n && Character.digit(s.charAt(j), 16) >= 0) j++;
                Integer col = Values.color(s.substring(i, j), 0xFF000000);
                if (col != null) {
                    flush.accept(out, text);
                    out.add(new Col(col));
                    i = j;
                    continue;
                }
            }
            if (Character.isLetter(c) || c == '-' && i + 1 < n && Character.isLetter(s.charAt(i + 1))) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '-' || s.charAt(j) == '_')) j++;
                String word = s.substring(i, j);
                if (j < n && s.charAt(j) == '(') {
                    String lower = word.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("rgb") || lower.startsWith("hsl")) {
                        int close = s.indexOf(')', j);
                        if (close > 0) {
                            Integer col = Values.color(s.substring(i, close + 1), 0xFF000000);
                            if (col != null) {
                                flush.accept(out, text);
                                out.add(new Col(col));
                                i = close + 1;
                                continue;
                            }
                        }
                    }
                    text.append(word).append('(');
                    i = j + 1;
                    continue;
                }
                Integer col = word.equalsIgnoreCase("currentcolor") ? null : Values.color(word, 0xFF000000);
                if (col != null) {
                    flush.accept(out, text);
                    out.add(new Col(col));
                } else {
                    text.append(word);
                }
                i = j;
                continue;
            }
            if (Character.isDigit(c) || (c == '-' || c == '+' || c == '.') && i + 1 < n && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '.')) {
                int j = i + 1;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j + 1 < n && (Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '-')) {
                    j += 2;
                    while (j < n && Character.isDigit(s.charAt(j))) j++;
                }
                int u = j;
                while (u < n && (Character.isLetter(s.charAt(u)) || s.charAt(u) == '%')) u++;
                try {
                    double v = Double.parseDouble(s.substring(i, j));
                    flush.accept(out, text);
                    out.add(new Num(v, s.substring(j, u)));
                    i = u;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not a number after all: text.
                }
            }
            text.append(c);
            i++;
        }
        flush.accept(out, text);
        return out;
    }

    private static String format(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ---------------------------------------------------------------- shorthands

    /** {@code animation} as its longhands, each a comma list with one entry per animation. */
    public static void expandAnimation(String value, BiConsumer<String, String> out) {
        List<String> name = new ArrayList<>(), duration = new ArrayList<>(), easing = new ArrayList<>(), delay = new ArrayList<>(),
                count = new ArrayList<>(), direction = new ArrayList<>(), fill = new ArrayList<>(), state = new ArrayList<>();
        for (String one : split(value)) {
            String n = "none", d = null, e = "ease", de = null, c = "1", di = "normal", f = "none", s = "running";
            for (String part : words(one)) {
                String l = part.toLowerCase(Locale.ROOT);
                if (isTime(l)) {
                    if (d == null) d = l;
                    else de = l;
                } else if (Easing.isEasing(l)) e = part;
                else if (l.equals("infinite") || l.matches("[0-9.]+")) c = l;
                else if (Set.of("normal", "reverse", "alternate", "alternate-reverse").contains(l)) di = l;
                else if (Set.of("forwards", "backwards", "both").contains(l)) f = l;
                else if (l.equals("running") || l.equals("paused")) s = l;
                else n = part;
            }
            name.add(n);
            duration.add(d == null ? "0s" : d);
            easing.add(e);
            delay.add(de == null ? "0s" : de);
            count.add(c);
            direction.add(di);
            fill.add(f);
            state.add(s);
        }
        out.accept("animation-name", String.join(", ", name));
        out.accept("animation-duration", String.join(", ", duration));
        out.accept("animation-timing-function", String.join(", ", easing));
        out.accept("animation-delay", String.join(", ", delay));
        out.accept("animation-iteration-count", String.join(", ", count));
        out.accept("animation-direction", String.join(", ", direction));
        out.accept("animation-fill-mode", String.join(", ", fill));
        out.accept("animation-play-state", String.join(", ", state));
    }

    /** {@code transition} as its longhands, each a comma list with one entry per transition. */
    public static void expandTransition(String value, BiConsumer<String, String> out) {
        List<String> prop = new ArrayList<>(), duration = new ArrayList<>(), easing = new ArrayList<>(), delay = new ArrayList<>();
        for (String one : split(value)) {
            String p = "all", d = null, e = "ease", de = null;
            for (String part : words(one)) {
                String l = part.toLowerCase(Locale.ROOT);
                if (isTime(l)) {
                    if (d == null) d = l;
                    else de = l;
                } else if (Easing.isEasing(l)) e = part;
                else p = l;
            }
            prop.add(p);
            duration.add(d == null ? "0s" : d);
            easing.add(e);
            delay.add(de == null ? "0s" : de);
        }
        out.accept("transition-property", String.join(", ", prop));
        out.accept("transition-duration", String.join(", ", duration));
        out.accept("transition-timing-function", String.join(", ", easing));
        out.accept("transition-delay", String.join(", ", delay));
    }

    // ---------------------------------------------------------------- reading

    /** A comma list, commas inside brackets left alone. */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(from, i).trim());
                from = i + 1;
            }
        }
        out.add(s.substring(from).trim());
        return out;
    }

    /** Space-separated words, spaces inside brackets left alone. */
    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder w = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (!w.isEmpty()) out.add(w.toString());
                w.setLength(0);
            } else {
                w.append(c);
            }
        }
        if (!w.isEmpty()) out.add(w.toString());
        return out;
    }

    /** The [i]th of a list, the list repeated if it is shorter, as CSS reads one. */
    private static String at(List<String> list, int i) {
        return list.get(i % list.size());
    }

    private static boolean isTime(String s) {
        return s.matches("[+-]?[0-9]*\\.?[0-9]+m?s");
    }

    /** A CSS time in seconds; 0 for anything else. */
    static double time(String s) {
        String l = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (l.endsWith("ms")) return Double.parseDouble(l.substring(0, l.length() - 2)) / 1000;
            if (l.endsWith("s")) return Double.parseDouble(l.substring(0, l.length() - 1));
        } catch (NumberFormatException ignored) {
            // Not a time.
        }
        return 0;
    }

    private static double number(String s, double otherwise) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') ? s.substring(1, s.length() - 1) : s;
    }

    /** A timing function: how far along a value is at each moment of its run. */
    record Easing(double x1, double y1, double x2, double y2, int steps, boolean jumpStart) {
        static final Easing LINEAR = new Easing(0, 0, 1, 1, 0, false), EASE = new Easing(0.25, 0.1, 0.25, 1, 0, false);

        static boolean isEasing(String s) {
            return Set.of("linear", "ease", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end").contains(s)
                    || s.startsWith("cubic-bezier(") || s.startsWith("steps(");
        }

        static Easing parse(String s) {
            String l = s.trim().toLowerCase(Locale.ROOT);
            switch (l) {
                case "linear": return LINEAR;
                case "ease": return EASE;
                case "ease-in": return new Easing(0.42, 0, 1, 1, 0, false);
                case "ease-out": return new Easing(0, 0, 0.58, 1, 0, false);
                case "ease-in-out": return new Easing(0.42, 0, 0.58, 1, 0, false);
                case "step-start": return new Easing(0, 0, 0, 0, 1, true);
                case "step-end": return new Easing(0, 0, 0, 0, 1, false);
                default: break;
            }
            int open = l.indexOf('('), close = l.lastIndexOf(')');
            if (open < 0 || close < open) return EASE;
            String[] args = l.substring(open + 1, close).split(",");
            try {
                if (l.startsWith("cubic-bezier") && args.length == 4) {
                    return new Easing(Math.max(0, Math.min(1, Double.parseDouble(args[0].trim()))), Double.parseDouble(args[1].trim()),
                            Math.max(0, Math.min(1, Double.parseDouble(args[2].trim()))), Double.parseDouble(args[3].trim()), 0, false);
                }
                if (l.startsWith("steps")) {
                    int n = Math.max(1, Integer.parseInt(args[0].trim()));
                    boolean start = args.length > 1 && (args[1].trim().equals("start") || args[1].trim().equals("jump-start"));
                    return new Easing(0, 0, 0, 0, n, start);
                }
            } catch (NumberFormatException ignored) {
                // A broken one eases.
            }
            return EASE;
        }

        /** The output at input [t] (0 to 1). */
        double at(double t) {
            if (steps > 0) {
                double s = jumpStart ? Math.ceil(t * steps) : Math.floor(t * steps);
                return Math.max(0, Math.min(1, s / steps));
            }
            if (x1 == y1 && x2 == y2) return t;
            // Solve x(u) = t for u by bisection (x is increasing), then y(u).
            double lo = 0, hi = 1, u = t;
            for (int i = 0; i < 40; i++) {
                u = (lo + hi) / 2;
                double x = bezier(u, x1, x2);
                if (Math.abs(x - t) < 1e-6) break;
                if (x < t) lo = u;
                else hi = u;
            }
            return bezier(u, y1, y2);
        }

        private static double bezier(double u, double p1, double p2) {
            double v = 1 - u;
            return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
        }
    }
}

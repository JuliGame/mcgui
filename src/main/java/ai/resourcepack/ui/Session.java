package ai.resourcepack.ui;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.ScrollCache;
import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * One player's page on one screen: which page, how far it is scrolled, what the pointer is over, and
 * the history the back button walks. Drawing happens off the server thread, at most one picture at a
 * time per session, and a change while one is being drawn just asks for another when it is done.
 * Pages load off the server thread too, and show once loaded. Everything else runs on the server thread.
 */
public final class Session {
    private final UIEnginePlugin plugin;
    private final ExecutorService renderers;
    /**
     * Back to the server thread as soon as it is free, which between ticks is at once: the Bukkit
     * scheduler would hold each hop (the next picture, what is under the pointer now) for the next tick.
     */
    private final Executor serverThread;
    public final Canvas canvas;
    /** The player this session belongs to, or null for a screen everyone shares. */
    public final UUID viewer;
    /** CSS pixels the canvas is tall: the screen's own until the first page shown says otherwise. */
    private double designHeight;
    private boolean sized;

    private String pageName;
    private Page page;
    /** This session's copy of the page's document; [hover] and [pressed] are always elements of it. */
    private Document doc;
    private double scrollY;
    private Element hover, pressed;
    private final Deque<Entry> back = new ArrayDeque<>();
    private record Entry(String page, double scroll) {}
    /** Counts the pages asked for, so a page that finishes loading after another was asked for is not shown. */
    private int navigation;

    private volatile HtmlRenderer.Frame frame;
    /**
     * Several views' worth of the page round the view, painted when the view first moves, so the next notches of
     * the wheel are cut from it rather than painted (ScrollCache): set by the render thread, dropped by the server
     * thread once nobody has scrolled for a while, since it is several times a picture's memory.
     */
    private volatile ScrollCache cache;
    /** How long a tall picture is kept with nothing cut from it. */
    private static final long CACHE_IDLE_NANOS = 15_000_000_000L;
    /** No tall picture is painted of a document a refresh replaces within this many ticks. */
    private static final int CACHE_MIN_TICKS = 100;
    /** The name of the page in [frame]; written before it. */
    private volatile String drawnName;
    /** Counts clicks, so the end of one press does not end a later one. */
    private int presses;
    private boolean rendering, dirty;
    private boolean closed;
    /** Told the pointer shape for what is under the pointer (views only). */
    public Consumer<Pointer> pointerShape = p -> {};
    /** Closes the view or screen this session is on. */
    public Runnable close = () -> {};
    /** Re-fill placeholders every this many ticks (0: only when the page opens). */
    private int refreshTicks;
    private long nextRefresh;

    /** [designHeight]: how many CSS pixels tall the canvas is, unless the first page shown sets its own ui:height. */
    Session(UIEnginePlugin plugin, ExecutorService renderers, Canvas canvas, UUID viewer, double designHeight) {
        this.plugin = plugin;
        this.renderers = renderers;
        this.serverThread = plugin.serverThread();
        this.canvas = canvas;
        this.viewer = viewer;
        this.designHeight = designHeight;
    }

    public String pageName() {
        return pageName;
    }

    public double scale() {
        return canvas.height() / designHeight;
    }

    private Player player() {
        return viewer == null ? null : Bukkit.getPlayer(viewer);
    }

    // ---------------------------------------------------------------- pages

    /**
     * Shows [name] (a page in the folder), scrolled to its top or to [fragment]: now if it is loaded,
     * else once it has loaded. False if there is no such page.
     */
    public boolean open(String name, String fragment, boolean remember) {
        return open(name, fragment, remember, null);
    }

    /** As {@link #open(String, String, boolean)}; [tell] hears if the page then fails to load. */
    private boolean open(String name, String fragment, boolean remember, Player tell) {
        String n = plugin.pages().normalise(name);
        if (n == null || !plugin.pages().exists(n)) return false;
        whenLoaded(n, tell, p -> {
            if (remember && pageName != null) back.push(new Entry(pageName, scrollY));
            if (back.size() > 50) back.removeLast();
            show(n, p, 0, fragment);
        });
        return true;
    }

    /**
     * Runs [then] with page [n] on the server thread: at once if it is loaded, else when it is, unless
     * another page was asked for meanwhile. [tell] hears if it cannot be loaded.
     */
    private void whenLoaded(String n, Player tell, Consumer<Page> then) {
        int nav = ++navigation;
        Page now = plugin.pages().loaded(n);
        if (now != null) {
            then.accept(now);
            return;
        }
        plugin.pages().load(n).thenAcceptAsync(p -> {
            if (closed || navigation != nav) return;
            if (p != null) then.accept(p);
            else if (tell != null && tell.isOnline()) tell.sendActionBar(Component.text("There is no page called " + n, NamedTextColor.RED));
        }, serverThread);
    }

    /** Puts up page [p], named [n], scrolled to [scroll] or, once drawn, to [fragment]. */
    private void show(String n, Page p, double scroll, String fragment) {
        if (!sized) {
            designHeight = Screens.designHeight(p, designHeight);
            sized = true;
        }
        pageName = n;
        page = p;
        doc = Placeholders.fill(p.document, player());
        scrollY = scroll;
        hover = null;
        pressed = null;
        refreshTicks = refreshSetting(p);
        nextRefresh = Bukkit.getCurrentTick() + refreshTicks;
        pendingFragment = fragment;
        render();
    }

    private String pendingFragment;

    private static int refreshSetting(Page p) {
        String v = p.meta("ui:refresh");
        if (v == null) return 0;
        try {
            double seconds = Double.parseDouble(v.trim());
            // 0 or less is no refresh. Each refresh draws the whole page again, so not more than twice a second.
            return seconds > 0 ? (int) Math.max(10, Math.min(Integer.MAX_VALUE, Math.round(seconds * 20))) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Back to the page before; false if there is none. */
    public boolean goBack() {
        return goBack(player());
    }

    private boolean goBack(Player tell) {
        Entry e = back.poll();
        if (e == null) return false;
        whenLoaded(e.page, tell, p -> show(e.page, p, e.scroll, null));
        return true;
    }

    /** The page file changed: show the new one where the player was. */
    void reloaded(String name) {
        if (!name.equals(pageName) || closed) return;
        int nav = navigation;
        plugin.pages().load(name).thenAcceptAsync(p -> {
            if (closed || navigation != nav || p == null || p == page || !name.equals(pageName)) return;
            page = p;
            doc = Placeholders.fill(p.document, player());
            // The new file can be shaped differently: nothing carries over by position.
            hover = null;
            pressed = null;
            refreshTicks = refreshSetting(p);
            render();
        }, serverThread);
    }

    /**
     * Page [p], shown for navigation [nav], could not be drawn, and the screen still has the last picture
     * of another page: unless the player has moved on, that page comes back, as it was drawn.
     */
    private void undrawable(Page p, int nav) {
        HtmlRenderer.Frame f = frame;
        String name = drawnName;
        if (closed || navigation != nav || page != p || f == null || f.page() == p || name == null) return;
        navigation++;
        String failed = pageName;
        // Reached from the page in the picture: that page is on top of the back stack, and going back to it would go nowhere.
        Entry top = back.peek();
        if (top != null && top.page().equals(name) && top.scroll() == f.scrollY()) back.pop();
        pageName = name;
        page = f.page();
        // The picture's document was this session's, so it was never changed after being drawn.
        doc = f.document();
        scrollY = f.scrollY();
        hover = null;
        pressed = null;
        pendingFragment = null;
        refreshTicks = refreshSetting(page);
        nextRefresh = Bukkit.getCurrentTick() + refreshTicks;
        Player pl = player();
        if (pl != null) pl.sendActionBar(Component.text("Could not show " + failed + ": see the console", NamedTextColor.RED));
        render();
    }

    /** Every tick: re-fill placeholders on pages that ask for it, and let go of a tall picture nobody scrolls. */
    void tick(long now) {
        ScrollCache c = cache;
        if (c != null && c.idleNanos() > CACHE_IDLE_NANOS) cache = null;
        if (refreshTicks > 0 && page != null && now >= nextRefresh) {
            nextRefresh = now + refreshTicks;
            Document fresh = Placeholders.fill(page.document, player());
            // What clicks changed (an open <details>, a ticked box) and what the pointer is on carry over.
            keepFormState(doc, fresh);
            hover = counterpart(hover, fresh);
            pressed = counterpart(pressed, fresh);
            doc = fresh;
            render();
        }
    }

    /** Copies what a click can change, ticks and open details, from [from] to [to], copies of the same page. */
    private static void keepFormState(Document from, Document to) {
        Elements a = from.getAllElements(), b = to.getAllElements();
        if (a.size() != b.size()) return;
        for (int i = 0; i < a.size(); i++) {
            String attr = switch (a.get(i).normalName()) {
                case "input" -> "checked";
                case "details" -> "open";
                default -> null;
            };
            if (attr == null) continue;
            if (a.get(i).hasAttr(attr)) b.get(i).attr(attr, "");
            else b.get(i).removeAttr(attr);
        }
    }

    /**
     * [e]'s counterpart in [to]. Both documents must be copies of the same page's: filling in
     * placeholders and clicking change attributes and text, never the shape, so an element's place in
     * document order finds it.
     */
    private static Element counterpart(Element e, Document to) {
        if (e == null) return null;
        Document from = e.ownerDocument();
        if (from == to) return e;
        if (from == null) return null;
        int i = from.getAllElements().indexOf(e);
        Elements all = to.getAllElements();
        return i >= 0 && i < all.size() ? all.get(i) : null;
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the current state, now or as soon as the picture being drawn is done. */
    public synchronized void render() {
        if (closed || page == null) return;
        if (rendering) {
            dirty = true;
            return;
        }
        rendering = true;
        submit();
    }

    /** The hover and press keys of the picture last drawn (render thread only). */
    private List<Element> drawnHover = List.of(), drawnPress = List.of();

    private List<Element> key(Element e, boolean active) {
        return page == null ? List.of() : page.styles.stateKey(e, active);
    }

    private void submit() {
        Page p = page;
        Document d = doc;
        double s = scrollY;
        int nav = navigation;
        var state = HtmlRenderer.state(hover, pressed);
        List<Element> hoverKey = key(hover, false), pressKey = key(pressed, true);
        String name = pageName;
        String fragment = pendingFragment;
        pendingFragment = null;
        double scale = scale();
        // A tall picture is worth painting only if its document lasts: not if a refresh replaces it within seconds.
        boolean lasting = refreshTicks == 0 || nextRefresh - Bukkit.getCurrentTick() > CACHE_MIN_TICKS;
        boolean shift = plugin.tileShift();
        renderers.execute(() -> {
            try {
                long t0 = System.nanoTime();
                HtmlRenderer.Frame before = frame;
                HtmlRenderer.Frame f = null;
                Rectangle2D changed = null;
                boolean partial = false;
                // This picture's: put back once it is drawn, so a failure part way through cannot leave a half-patched one.
                ScrollCache c = cache;
                cache = null;
                boolean same = fragment == null && before != null && before.document() == d && before.page() == p && before.scale() == scale;
                double at = same ? Math.max(0, Math.min(s, before.maxScroll())) : s;
                // A tall picture of another document or size, or one the view has left, is of no more use.
                if (c != null && !(same && c.of(p, d, canvas.width(), canvas.height(), scale) && c.covers(at))) c = null;
                // The canvas shows the last view as it was cut from this tall picture, if there is one.
                ScrollCache kept = c;
                boolean still = same && Math.abs(before.scrollY() - at) < 0.01;
                Set<Element> moved = same ? changedState(hoverKey, pressKey) : Set.of();
                if (still && moved.isEmpty()) {
                    drawnHover = hoverKey;
                    drawnPress = pressKey;
                    cache = c;
                    return;
                }
                boolean redraw = false;
                if (c != null && !moved.isEmpty()) {
                    // The hover or press state moved: repaint what it touches in the whole tall picture, not only
                    // in view, since the rest of it will be scrolled into view.
                    HtmlRenderer.Frame strip = c.strip();
                    Repainted r = repaint(p, d, scale, state, strip, moved);
                    if (r == null) {
                        // Not enough, and it may be half done: neither it nor the view in it will do.
                        c = null;
                        redraw = true;
                    } else {
                        c.patched(r.frame());
                        Rectangle2D g = r.region();
                        changed = new Rectangle2D.Double(g.getX(), g.getY() + strip.scrollY() - at, g.getWidth(), g.getHeight());
                    }
                }
                if (c != null) {
                    f = c.frame(at);
                    partial = still;
                } else if (still && !redraw) {
                    // Only the hover or press state moved: repaint just what it touches.
                    Repainted r = repaint(p, d, scale, state, before, moved);
                    if (r != null) {
                        f = r.frame();
                        changed = r.region();
                        partial = true;
                    }
                } else if (same && !still && lasting && ScrollCache.possible(before)) {
                    // Only the view moved (and perhaps the pointer's state with it). Painting is nearly all a picture
                    // costs, and the next notches will want the rows round this view: paint a few views' worth of
                    // the page at once, and cut this view and the next ones from that.
                    c = ScrollCache.paint(p, d, canvas.width(), canvas.height(), scale, at, before.scrollY(), state, ScrollCache.VIEWS);
                    if (c != null) f = c.frame(at);
                }
                if (f == null) {
                    f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, s, state);
                    if (fragment != null && !fragment.isEmpty()) {
                        Element target = d.getElementById(fragment);
                        double top = target == null ? Double.NaN : f.topOf(target);
                        if (!Double.isNaN(top)) {
                            f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, ScrollCache.snap(top, scale), state);
                            double scrolled = f.scrollY();
                            // Unless the player has moved on (another page, or scrolled) while this was drawn.
                            serverThread.execute(() -> {
                                if (navigation == nav && scrollY == s) scrollY = scrolled;
                            });
                        }
                    }
                }
                if (partial) {
                    // Only the changed rectangle goes to the canvas, so only its tiles are re-encoded and re-sent.
                    int x0 = Math.max(0, (int) Math.floor(changed.getX() * scale)), y0 = Math.max(0, (int) Math.floor(changed.getY() * scale));
                    int x1 = Math.min(canvas.width(), (int) Math.ceil(changed.getMaxX() * scale));
                    int y1 = Math.min(canvas.height(), (int) Math.ceil(changed.getMaxY() * scale));
                    if (x1 > x0 && y1 > y0) canvas.draw(f.image().getSubimage(x0, y0, x1 - x0, y1 - y0), x0, y0);
                } else {
                    // Moved by whole map rows on the same page: the canvas moves too, so its viewers' frames are pointed
                    // at the maps their clients already have and only the rows that came in are sent.
                    int rows = same && shift ? ScrollCache.tileRows(before.scrollY(), f.scrollY(), scale, canvas.rows()) : 0;
                    if (rows != 0 && c != null && c == kept && changed == null) {
                        // Both views cut from the same picture: what stayed in view is the same pixels, moved, so only
                        // the rows that came in are drawn (converting the whole view is most of a notch's work).
                        int band = Math.abs(rows) * ScrollCache.TILE, y0 = rows > 0 ? canvas.height() - band : 0;
                        canvas.scroll(rows, f.image().getSubimage(0, y0, canvas.width(), band), 0, y0);
                    } else {
                        // Anything else may differ elsewhere too: the whole view, and only tiles that then differ are encoded.
                        canvas.scroll(rows, f.image(), 0, 0);
                    }
                }
                drawnHover = hoverKey;
                drawnPress = pressKey;
                drawnName = name;
                frame = f;
                cache = c;
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("Drew %s for %s in %d ms%s%s", pageName, viewer, (System.nanoTime() - t0) / 1_000_000,
                            partial ? String.format(" (only %.0f x %.0f)", changed.getWidth(), changed.getHeight()) : "",
                            c != null ? String.format(" (from a picture %d px tall)", c.strip().image().getHeight()) : ""));
                }
                // A new page under a still pointer: what it is over has changed.
                if (!partial) serverThread.execute(this::repoint);
            } catch (RuntimeException | Error e) {
                plugin.getLogger().warning("Could not draw page " + name + ": " + e);
                if (plugin.debug()) e.printStackTrace();
                // A page that cannot be drawn at all leaves the last picture, of another page, on the screen,
                // where clicks would find nothing: go back to that page, so the screen still works.
                HtmlRenderer.Frame shown = frame;
                if (shown != null && shown.page() != p) serverThread.execute(() -> undrawable(p, nav));
            } finally {
                synchronized (Session.this) {
                    if (closed) cache = null;
                    if (dirty && !closed) {
                        dirty = false;
                        // The state moved on while drawing: draw again, reading it on the server thread.
                        serverThread.execute(() -> {
                            synchronized (Session.this) {
                                if (!closed) submit();
                                else rendering = false;
                            }
                        });
                    } else {
                        rendering = false;
                    }
                }
            }
        });
    }

    /** A picture with part of it painted again, and that part (CSS pixels of the picture). */
    private record Repainted(HtmlRenderer.Frame frame, Rectangle2D region) {}

    /**
     * [base] (the last picture, or a ScrollCache's tall one) with what [moved] touches painted again, into its own
     * image, in [state]. Null when that is not enough (then [base]'s image may be half repainted): draw it all.
     */
    private Repainted repaint(Page p, Document d, double scale, Selector.State state, HtmlRenderer.Frame base, Set<Element> moved) {
        // A page where hovering one element restyles its siblings ('+', '~') is drawn whole.
        Rectangle2D changed = p.siblingStates() ? null : dirtyRegion(base, moved);
        if (changed == null || !small(changed, base)) return null;
        HtmlRenderer.Frame f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, base.scrollY(), state, base.image(), changed);
        // The new state may reach further than the old one did (a shadow only hovering adds): then
        // paint both. If it cannot be told, or it is too much, draw it all.
        Rectangle2D after = dirtyRegion(f, moved);
        if (after == null) return null;
        if (!changed.contains(after)) {
            changed = changed.createUnion(after);
            if (!small(changed, base)) return null;
            f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, base.scrollY(), state, base.image(), changed);
        }
        return sameLayout(base, f, changed) ? new Repainted(f, changed) : null;
    }

    /** Room round a changed element for what it may draw outside itself on hover beyond its shadows and blur: a lift, a glow. */
    private static final double DIRTY_MARGIN = 48;

    /** The elements whose hover or press state differs between the picture drawn and [hoverKey] and [pressKey]. */
    private Set<Element> changedState(List<Element> hoverKey, List<Element> pressKey) {
        Set<Element> changed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element e : drawnHover) if (!hoverKey.contains(e)) changed.add(e);
        for (Element e : hoverKey) if (!drawnHover.contains(e)) changed.add(e);
        for (Element e : drawnPress) if (!pressKey.contains(e)) changed.add(e);
        for (Element e : pressKey) if (!drawnPress.contains(e)) changed.add(e);
        return changed;
    }

    /**
     * The part of [f]'s picture (CSS pixels from its top: the view, or a ScrollCache's taller picture) that
     * [changed] and everything inside them cover, grown by the furthest their styles there paint outside
     * themselves, and over every blur it meets. Null when it cannot be told, or when a blur it meets is cut by
     * the picture's edge (draw it all).
     */
    private static Rectangle2D dirtyRegion(HtmlRenderer.Frame f, Set<Element> changed) {
        Rectangle2D area = null;
        double reach = DIRTY_MARGIN;
        for (Painter.Hit h : f.hits()) {
            boolean inside = false;
            for (Element e = h.element(); e != null && !inside; e = e.parent()) inside = changed.contains(e);
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
            reach = Math.max(reach, paintsOutside(f.style(h.element())));
        }
        if (area == null) return null;
        Rectangle2D view = new Rectangle2D.Double(0, 0, f.viewportWidth(), f.image().getHeight() / f.scale());
        Rectangle2D padded = new Rectangle2D.Double(area.getX() - reach, area.getY() - f.scrollY() - reach,
                area.getWidth() + 2 * reach, area.getHeight() + 2 * reach);
        Rectangle2D out = padded.createIntersection(view);
        if (out.isEmpty()) return null;
        // Blurs read round themselves. A filter blur blurs a layer of its own, which a partial repaint paints only
        // inside the region, so a layer the region cuts would blur with a cut edge; a backdrop filter blurs what is
        // painted under and round its box, which outside the region is the last picture, already blurred and with
        // text on it. So the region takes in all of each it meets, as far as its blur reads. One the picture's edge
        // cuts is drawn whole by a full picture, with the page past the edge under it (HtmlRenderer.paintBands),
        // and cannot be repainted in part at all.
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Painter.Hit h : f.hits()) {
                Style s = f.style(h.element());
                if (s == null) continue;
                double blur = blurReach(s.filter, s);
                boolean backdrop = !s.backdropFilter.equals("none");
                if (blur <= 0 && !backdrop) continue;
                Rectangle2D b = blur > 0 ? extent(f, h.element()) : h.shape().getBounds2D();
                if (backdrop) blur = Math.max(blur, blurReach(s.backdropFilter, s));
                Rectangle2D layer = new Rectangle2D.Double(b.getX() - blur, b.getY() - f.scrollY() - blur, b.getWidth() + 2 * blur, b.getHeight() + 2 * blur);
                if (!out.intersects(layer) || out.contains(layer)) continue;
                if (!view.contains(layer)) return null;
                out = out.createUnion(layer);
                grew = true;
            }
        }
        // Out to whole pixels of the picture: a clip edge part way through a pixel leaves that pixel half repainted.
        double sc = f.scale(), x0 = Math.floor(out.getX() * sc) / sc, y0 = Math.floor(out.getY() * sc) / sc;
        return new Rectangle2D.Double(x0, y0, Math.ceil(out.getMaxX() * sc) / sc - x0, Math.ceil(out.getMaxY() * sc) / sc - y0).createIntersection(view);
    }

    /** Where [e] and everything inside it are drawn in [f], in page CSS pixels. */
    private static Rectangle2D extent(HtmlRenderer.Frame f, Element e) {
        Rectangle2D area = null;
        for (Painter.Hit h : f.hits()) {
            boolean inside = false;
            for (Element p = h.element(); p != null && !inside; p = p.parent()) inside = p == e;
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
        }
        return area;
    }

    /**
     * Whether repainting [region] of [f]'s picture is worth it over painting all of it again: the view, or a
     * ScrollCache's taller picture, whose whole repaint would cost several views.
     */
    private static boolean small(Rectangle2D region, HtmlRenderer.Frame f) {
        return region.getWidth() * region.getHeight() < 0.6 * f.viewportWidth() * f.image().getHeight() / f.scale();
    }

    /** How far past its box (CSS pixels) an element styled [s] can paint: its outer shadows, its text's shadows, its blur. */
    private static double paintsOutside(Style s) {
        if (s == null) return 0;
        return Math.max(Math.max(shadowReach(s.boxShadow, s), shadowReach(s.textShadow, s)), blurReach(s.filter, s));
    }

    private static double shadowReach(String value, Style s) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return 0;
        double reach = 0;
        for (String shadow : Values.splitCommas(value)) {
            double[] lens = new double[4];
            int n = 0;
            boolean inset = false;
            for (String tok : Values.splitSpaces(shadow)) {
                if (tok.equalsIgnoreCase("inset")) {
                    inset = true;
                    continue;
                }
                Len l = Len.parse(tok, s.context);
                if (l != null && n < 4) lens[n++] = Math.abs(l.of(0));
            }
            if (inset || n < 2) continue;
            // Offset and spread, and the blur: a Gaussian of sigma blur/2, drawn out to three sigma.
            reach = Math.max(reach, Math.max(lens[0], lens[1]) + lens[3] + 1.5 * lens[2] + 2);
        }
        return reach;
    }

    private static double blurReach(String filter, Style s) {
        if (filter == null || filter.equals("none")) return 0;
        double reach = 0;
        for (String fn : Values.splitSpaces(filter)) {
            if (!fn.toLowerCase(Locale.ROOT).startsWith("blur(") || !fn.endsWith(")")) continue;
            String arg = fn.substring(5, fn.length() - 1).trim();
            Len l = Len.parse(arg.isEmpty() ? "0" : arg, s.context);
            // blur(r) is a Gaussian of sigma r, drawn out to three sigma.
            if (l != null) reach = Math.max(reach, 3 * Math.abs(l.of(0)) + 2);
        }
        return reach;
    }

    /** Whether nothing outside [region] moved between two frames: if something did, a partial repaint is not enough. */
    private static boolean sameLayout(HtmlRenderer.Frame a, HtmlRenderer.Frame b, Rectangle2D region) {
        if (a.hits().size() != b.hits().size()) return false;
        for (int i = 0; i < a.hits().size(); i++) {
            Rectangle2D ra = a.hits().get(i).shape().getBounds2D(), rb = b.hits().get(i).shape().getBounds2D();
            if (Math.abs(ra.getX() - rb.getX()) < 0.5 && Math.abs(ra.getY() - rb.getY()) < 0.5
                    && Math.abs(ra.getWidth() - rb.getWidth()) < 0.5 && Math.abs(ra.getHeight() - rb.getHeight()) < 0.5) continue;
            Rectangle2D va = new Rectangle2D.Double(ra.getX(), ra.getY() - a.scrollY(), ra.getWidth(), ra.getHeight());
            Rectangle2D vb = new Rectangle2D.Double(rb.getX(), rb.getY() - b.scrollY(), rb.getWidth(), rb.getHeight());
            if (!region.contains(va) || !region.contains(vb)) return false;
        }
        return true;
    }

    public boolean ready() {
        return frame != null;
    }

    public void dispose() {
        synchronized (this) {
            closed = true;
            cache = null;
        }
    }

    // ---------------------------------------------------------------- input

    private double[] css(int x, int y) {
        double s = scale();
        return new double[]{x / s, y / s};
    }

    /**
     * What is at (x, y) in the last picture, an element of that picture's document; null if the picture
     * is of another page than the one shown now (the new page's first picture has not landed yet).
     */
    private Element at(int x, int y) {
        HtmlRenderer.Frame f = frame;
        if (f == null || f.page() != page) return null;
        double[] c = css(x, y);
        return f.elementAt(c[0], c[1]);
    }

    /** Where the pointer last was, so a new picture can be checked under it; -1 for nowhere. */
    private int pointerX = -1, pointerY = -1;

    /** The pointer is over pixel (x, y) of the canvas. */
    public void pointer(int x, int y) {
        pointerX = x;
        pointerY = y;
        Element seen = at(x, y);
        pointerShape.accept(cursorFor(seen));
        Element e = counterpart(seen, doc);
        if (e == hover) return;
        // Only a move that changes what :hover matches is worth a picture.
        boolean differs = !key(hover, false).equals(key(e, false));
        hover = e;
        if (differs) render();
    }

    public void pointerGone() {
        pointerX = pointerY = -1;
        if (hover != null) {
            boolean differs = !key(hover, false).isEmpty();
            hover = null;
            if (differs) render();
        }
    }

    /** The pointer for [e], an element of the last picture's document (whose styles it reads). */
    private Pointer cursorFor(Element e) {
        HtmlRenderer.Frame f = frame;
        for (Element p = e; p != null; p = p.parent()) {
            Style s = f == null ? null : f.style(p);
            if (s != null && !s.cursor.equals("auto")) {
                return switch (s.cursor) {
                    case "pointer" -> Pointer.HAND;
                    case "text" -> Pointer.TEXT;
                    case "none" -> Pointer.HIDDEN;
                    default -> Pointer.ARROW;
                };
            }
            if (actionable(p)) return Pointer.HAND;
        }
        return Pointer.ARROW;
    }

    private static boolean actionable(Element e) {
        if (e.hasAttr("data-command") || e.hasAttr("data-console") || e.hasAttr("data-action") || e.hasAttr("data-message")) return true;
        return switch (e.normalName()) {
            case "a" -> e.hasAttr("href");
            case "button", "summary" -> true;
            default -> false;
        };
    }

    private void repoint() {
        if (closed || pointerX < 0) return;
        Element seen = at(pointerX, pointerY);
        pointerShape.accept(cursorFor(seen));
        Element e = counterpart(seen, doc);
        boolean differs = !key(e, false).equals(drawnHover);
        hover = e;
        if (differs) render();
    }

    public void scroll(int notches) {
        HtmlRenderer.Frame f = frame;
        if (f == null) return;
        // Whole pixels of the canvas, so the view can be cut from a tall picture (ScrollCache) and matches one drawn on its own.
        double to = ScrollCache.snap(Math.max(0, Math.min(f.maxScroll(), scrollY + notches * scrollStep())), scale());
        if (to == scrollY) return;
        scrollY = to;
        render();
    }

    /**
     * CSS pixels a notch of the wheel scrolls: the page's {@code <meta name="ui:scroll-step">}, else the config's.
     * "tile" is one map row, so every notch but the one that reaches an end moves the canvas by whole maps
     * ({@link Canvas#scroll}) and its viewers are sent only the row that comes in.
     */
    private double scrollStep() {
        String v = page == null ? null : page.meta("ui:scroll-step");
        if (v == null) v = plugin.scrollStep();
        v = v.trim();
        if (v.equalsIgnoreCase("tile")) return ScrollCache.TILE / scale();
        try {
            double step = Double.parseDouble(v);
            return step > 0 && Double.isFinite(step) ? step : 80;
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    /** A click at (x, y) by the player this session belongs to. */
    public void click(int x, int y) {
        click(x, y, null);
    }

    /**
     * A click at (x, y): follows the link or runs the command under it. [clicker] is who clicked: on a
     * shared screen, the one whose commands run and whose placeholders fill them; null for the viewer.
     */
    public void click(int x, int y, Player clicker) {
        // The picture can be behind the session: the element clicked is found in the session's own copy.
        Element e = counterpart(at(x, y), doc);
        if (e == null) return;
        Player p = clicker != null ? clicker : player();
        Page on = page;
        boolean acted = activate(e, p);
        if (acted && p != null && plugin.clickSound()) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        if (page == on && page.styles.dynamic) {
            // :active for a moment, so a press shows. A toggle has given the session a new copy by now.
            Element target = counterpart(e, doc);
            if (target == null) return;
            int press = ++presses;
            pressed = target;
            render();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // By identity it could miss: a refresh in between moves pressed to a new copy of the document.
                if (presses == press && pressed != null) {
                    pressed = null;
                    render();
                }
            }, 3);
        }
    }

    /** [target] (an element of [doc]) or the nearest ancestor that does something, clicked by [p]. */
    private boolean activate(Element target, Player p) {
        for (Element e = target; e != null; e = e.parent()) {
            if (e.hasAttr("data-command") || e.hasAttr("data-console") || e.hasAttr("data-message")) {
                if (e.hasAttr("data-message") && p != null) {
                    p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(Placeholders.apply(original(e, "data-message"), p)));
                }
                if (e.hasAttr("data-command") && p != null) runAs(p, Placeholders.apply(original(e, "data-command"), p));
                if (e.hasAttr("data-console")) console(original(e, "data-console"), p);
                if (e.hasAttr("data-close")) close.run();
                return true;
            }
            if (e.hasAttr("data-action")) {
                action(e.attr("data-action"), p);
                return true;
            }
            String tag = e.normalName();
            if (tag.equals("a") && e.hasAttr("href")) {
                link(original(e, "href"), p);
                return true;
            }
            if (tag.equals("summary") && e.parent() != null && e.parent().normalName().equals("details")) {
                Element details = editable(e.parent());
                if (details == null) return true;
                if (details.hasAttr("open")) details.removeAttr("open");
                else details.attr("open", "");
                render();
                return true;
            }
            if (tag.equals("input") && (e.attr("type").equalsIgnoreCase("checkbox") || e.attr("type").equalsIgnoreCase("radio"))) {
                toggle(e);
                return true;
            }
            if (tag.equals("label")) {
                Element input = e.hasAttr("for") ? doc.getElementById(e.attr("for")) : e.selectFirst("input");
                if (input != null) {
                    toggle(input);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * [e]'s [attr] as the page file has it, placeholders not filled in: filled at the click, for whoever
     * clicked (a shared screen's copy was filled for nobody), and checked when it goes to the console.
     */
    private String original(Element e, String attr) {
        Element o = counterpart(e, page.document);
        return o != null ? o.attr(attr) : e.attr(attr);
    }

    /** Runs [template] as the console, its placeholders filled for [p]; not if a value would change the command's shape. */
    private void console(String template, Player p) {
        String command = Placeholders.command(strip(template), p, plugin.getLogger());
        if (command != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * [e] (an element of [doc]) in a fresh copy of the document, which becomes the session's: a picture
     * being drawn keeps reading the old one, so nothing changes under it. Null if it is not there.
     */
    private Element editable(Element e) {
        Document copy = doc.clone();
        Element mine = counterpart(e, copy);
        hover = counterpart(hover, copy);
        pressed = counterpart(pressed, copy);
        doc = copy;
        return mine;
    }

    private void toggle(Element original) {
        Element input = editable(original);
        if (input == null) return;
        boolean radio = input.attr("type").equalsIgnoreCase("radio");
        if (radio) {
            String name = input.attr("name");
            for (Element other : doc.select("input[type=radio]")) if (other.attr("name").equals(name)) other.removeAttr("checked");
            input.attr("checked", "");
        } else if (input.hasAttr("checked")) {
            input.removeAttr("checked");
        } else {
            input.attr("checked", "");
        }
        render();
    }

    private void action(String raw, Player p) {
        String a = raw.trim();
        String l = a.toLowerCase(Locale.ROOT);
        if (l.equals("close")) close.run();
        else if (l.equals("back")) goBack(p);
        else if (l.equals("reload")) {
            if (pageName != null) open(pageName, null, false, p);
        } else if (l.equals("top")) {
            scrollY = 0;
            render();
        } else if (l.startsWith("page:")) navigate(a.substring(5).trim(), p);
    }

    /** Follows [href], as the page file has it (placeholders not filled in), for [p]. */
    private void link(String href, Player p) {
        String h = href.trim();
        String l = h.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.equals("#")) return;
        if (l.startsWith("command:") || l.startsWith("cmd:")) {
            if (p != null) runAs(p, Placeholders.apply(h.substring(h.indexOf(':') + 1), p));
        } else if (l.startsWith("console:")) {
            console(h.substring(8), p);
        } else if (l.startsWith("close:")) {
            close.run();
        } else if (l.startsWith("back:")) {
            goBack(p);
        } else if (l.startsWith("http://") || l.startsWith("https://")) {
            // A page cannot be browsed to: the link goes to chat, where it can be clicked.
            String url = Placeholders.apply(h, p);
            if (p != null) p.sendMessage(Component.text(url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
        } else if (h.startsWith("#")) {
            scrollTo(h.substring(1));
        } else if (!l.contains(":")) {
            navigate(Placeholders.apply(h, p), p);
        }
    }

    private void navigate(String href, Player p) {
        String target = plugin.pages().resolveLink(pageName == null ? "" : pageName, href);
        int hash = href.indexOf('#');
        String fragment = hash >= 0 ? href.substring(hash + 1) : null;
        if (target == null) return;
        if (target.equals(pageName) && fragment != null) {
            scrollTo(fragment);
            return;
        }
        if (!open(target, fragment, true, p) && p != null) {
            p.sendActionBar(Component.text("There is no page called " + target, NamedTextColor.RED));
        }
    }

    /** Scrolls to the element with id [id], where the last picture drew it. */
    private void scrollTo(String id) {
        HtmlRenderer.Frame f = frame;
        if (f == null || f.page() != page) return;
        // Looked up in the picture's own document: its positions are of its elements.
        Element target = f.document().getElementById(id);
        if (target == null) return;
        double top = f.topOf(target);
        if (Double.isNaN(top)) return;
        scrollY = ScrollCache.snap(Math.max(0, Math.min(f.maxScroll(), top)), scale());
        render();
    }

    private static String strip(String command) {
        String c = command.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private static void runAs(Player p, String command) {
        p.performCommand(strip(command));
    }
}

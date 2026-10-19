package ai.resourcepack.ui;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Repaint;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.RenderAbandoned;
import ai.resourcepack.ui.html.ScrollCache;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
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
    /**
     * What [doc] was filled from, and whether it is still only that (nothing clicked has changed it): only such a
     * document's first picture is shared with other sessions ({@link FirstPictures}).
     */
    private Placeholders.Filled filled;
    private boolean pristine;
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
    /**
     * The image of [frame] when it is shared with a {@link FirstPictures} picture (taken from one, or kept in one):
     * copied before a repaint paints into it. Written when no picture is being drawn, read by the render thread.
     */
    private volatile java.awt.image.BufferedImage borrowed;
    /** A picture another session drew for this one's state, handed over when it landed (server thread). */
    private FirstPictures.Picture offered;
    /** The next picture is drawn here, not waited for: the one waited for could not be drawn. */
    private boolean drawOwn;
    /** Told once, on the server thread, when the first picture is on the canvas or could not be drawn. */
    private Runnable firstPicture;
    /** The tick this session was made. */
    private final int born;
    /**
     * Ticks a wall waits for a session's first picture before showing its canvas, blank, all the same. A frame whose
     * map the client has no colours for draws the map item, so a wall shows nothing new until its page is drawn,
     * and then the page, one packet a map; a blank first only if drawing takes longer than this.
     */
    static final int BLANK_AFTER_TICKS = 10;

    /** [designHeight]: how many CSS pixels tall the canvas is, unless the first page shown sets its own ui:height. */
    Session(UIEnginePlugin plugin, ExecutorService renderers, Canvas canvas, UUID viewer, double designHeight) {
        this.plugin = plugin;
        this.renderers = renderers;
        this.serverThread = plugin.serverThread();
        this.canvas = canvas;
        this.viewer = viewer;
        this.designHeight = designHeight;
        this.born = Bukkit.getCurrentTick();
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
        filled = Placeholders.fill(p.document, player());
        doc = filled.doc();
        pristine = true;
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
            filled = Placeholders.fill(p.document, player());
            doc = filled.doc();
            pristine = true;
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
            Placeholders.Filled fresh = Placeholders.fill(page.document, player());
            // What clicks changed (an open <details>, a ticked box) and what the pointer is on carry over.
            boolean untouched = pristine && doc == filled.doc();
            keepFormState(doc, fresh.doc());
            hover = counterpart(hover, fresh.doc());
            pressed = counterpart(pressed, fresh.doc());
            filled = fresh;
            doc = fresh.doc();
            pristine = untouched;
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
        long timeout = plugin.renderTimeout();
        // A tall picture is worth painting only if its document lasts: not if a refresh replaces it within seconds.
        boolean lasting = refreshTicks == 0 || nextRefresh - Bukkit.getCurrentTick() > CACHE_MIN_TICKS;
        boolean shift = plugin.tileShift();
        // The first picture of this copy of the page, as every session's is: taken from FirstPictures if it is kept
        // there, waited for if another session is drawing it, else drawn here and kept for the next.
        FirstPictures.Key first = drawOwn ? null : firstKey(p, d, s, fragment, scale);
        FirstPictures.Picture take = offered;
        offered = null;
        drawOwn = false;
        if (first != null) {
            if (take == null || !take.key.equals(first)) {
                FirstPictures.Claim claim = plugin.firstPictures().claim(first, landed -> serverThread.execute(() -> landed(landed)));
                if (claim.picture() == null && !claim.mine()) return; // waiting for it: still drawing, as far as render() goes
                take = claim.picture();
                if (!claim.mine()) first = null;
            } else {
                first = null;
            }
            if (take != null && adopt(take, d, name, hoverKey, pressKey)) {
                rendering = false;
                return;
            }
        }
        FirstPictures.Key publish = first;
        FirstPictures pictures = plugin.firstPictures();
        renderers.execute(() -> {
            boolean published = false;
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
                    Repaint.Result r = Repaint.partial(p, d, canvas.width(), canvas.height(), scale, strip.scrollY(), state, strip, moved, timeout);
                    if (r.frame() == null) {
                        // Not enough, and it may be half done: neither it nor the view in it will do.
                        c = null;
                        redraw = true;
                    } else {
                        c.patched(r.frame());
                        Rectangle2D g = r.changed();
                        changed = new Rectangle2D.Double(g.getX(), g.getY() + strip.scrollY() - at, g.getWidth(), g.getHeight());
                    }
                }
                if (c != null) {
                    f = c.frame(at);
                    partial = still;
                } else if (still && !redraw) {
                    // Only the hover or press state moved: repaint just what it touches. A picture shared with other
                    // sessions (FirstPictures) is copied first, as the repaint paints into it.
                    HtmlRenderer.Frame base = before.image() == borrowed ? FirstPictures.own(before) : before;
                    Repaint.Result r = Repaint.partial(p, d, canvas.width(), canvas.height(), scale, before.scrollY(), state, base, moved, timeout);
                    if (r.frame() != null) {
                        f = r.frame();
                        changed = r.changed();
                        partial = true;
                    }
                } else if (same && !still && lasting && ScrollCache.possible(before) && !NO_STRIP.contains(p)) {
                    // Only the view moved (and perhaps the pointer's state with it). Painting is nearly all a picture
                    // costs, and the next notches will want the rows round this view: paint a few views' worth of
                    // the page at once, and cut this view and the next ones from that.
                    try {
                        c = ScrollCache.paint(p, d, canvas.width(), canvas.height(), scale, at, before.scrollY(), state, ScrollCache.VIEWS, timeout);
                    } catch (RenderAbandoned e) {
                        // Several views take several times one view's time: a page that fits render-timeout only a view
                        // at a time is still drawn, a view at a time, from now on.
                        NO_STRIP.add(p);
                    }
                    if (c != null) f = c.frame(at);
                }
                if (f == null) {
                    f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, s, state, null, null, timeout);
                    if (fragment != null && !fragment.isEmpty()) {
                        Element target = d.getElementById(fragment);
                        double top = target == null ? Double.NaN : f.topOf(target);
                        if (!Double.isNaN(top)) {
                            f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, ScrollCache.snap(top, scale), state, null, null, timeout);
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
                if (publish != null && !partial && c == null && f.document() == d && f.scrollY() == 0) {
                    // This session's first picture of the page, drawn as any session's would be: kept for the next to
                    // show it here, with the canvas as it is now. Its image is shared from now on.
                    FirstPictures.Picture picture = FirstPictures.Picture.of(publish, f, canvas.copy());
                    if (picture != null) borrowed = f.image();
                    pictures.publish(publish, picture);
                    published = true;
                }
                if (before == null) serverThread.execute(this::firstPictureSettled);
                AtomicInteger failed = FAILURES.remove(p);
                if (failed != null && failed.get() > 1) {
                    plugin.getLogger().info("Drew page " + name + " after " + failed.get() + " attempts that could not be drawn");
                }
                cache = c;
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("Drew %s for %s in %d ms%s%s", pageName, viewer, (System.nanoTime() - t0) / 1_000_000,
                            partial ? String.format(" (only %.0f x %.0f)", changed.getWidth(), changed.getHeight()) : "",
                            c != null ? String.format(" (from a picture %d px tall)", c.strip().image().getHeight()) : ""));
                }
                // A new page under a still pointer: what it is over has changed.
                if (!partial) serverThread.execute(this::repoint);
            } catch (RuntimeException | Error e) {
                // Once per page revision: a page too heavy to draw fails on every hover and scroll, for every viewer.
                int failures = FAILURES.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
                if (failures == 1) plugin.getLogger().warning("Could not draw page " + name + ": " + e + " (repeats are counted, not logged)");
                if (plugin.debug()) e.printStackTrace();
                // A page that cannot be drawn at all leaves the last picture, of another page, on the screen,
                // where clicks would find nothing: go back to that page, so the screen still works.
                HtmlRenderer.Frame shown = frame;
                if (shown != null && shown.page() != p) serverThread.execute(() -> undrawable(p, nav));
                if (shown == null) serverThread.execute(this::firstPictureSettled);
            } finally {
                if (publish != null && !published) pictures.abandon(publish);
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

    /** Pages (one revision each: a reload makes a new Page) that failed to draw, and how many times since they last drew. */
    private static final Map<Page, AtomicInteger> FAILURES = Collections.synchronizedMap(new WeakHashMap<>());

    /** Pages (one revision each) whose tall picture for the scroll cache took longer than render-timeout: scrolled a view at a time. */
    private static final Set<Page> NO_STRIP = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

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
     * The key of [d]'s first picture if the picture to be drawn is one ({@link FirstPictures}): the first of this copy
     * of the page, at its top, nothing hovered or pressed, nothing clicked changed, no #fragment, and filled with
     * nobody's own values. Else null. [s], [fragment] and [scale] are what is to be drawn.
     */
    private FirstPictures.Key firstKey(Page p, Document d, double s, String fragment, double scale) {
        HtmlRenderer.Frame f = frame;
        if (!plugin.firstPictures().enabled() || fragment != null || s != 0 || hover != null || pressed != null) return null;
        if (!pristine || filled == null || filled.doc() != d || filled.personal() || f != null && f.document() == d) return null;
        return new FirstPictures.Key(pageName, p, canvas.cols(), canvas.rows(), scale, filled.key());
    }

    /**
     * Takes [picture] as this session's picture of [d], as if it had drawn it: the canvas shows it (its tiles, nothing
     * drawn), and the frame is it, on [d]'s elements. False, with nothing changed, if [d] is not shaped like it. On the
     * server thread, in place of drawing ([rendering] is set).
     */
    private boolean adopt(FirstPictures.Picture picture, Document d, String name, List<Element> hoverKey, List<Element> pressKey) {
        HtmlRenderer.Frame f = picture.frameFor(d);
        if (f == null) return false;
        long t0 = System.nanoTime();
        boolean first = frame == null;
        canvas.show(picture.canvas);
        borrowed = f.image();
        drawnHover = hoverKey;
        drawnPress = pressKey;
        drawnName = name;
        cache = null;
        frame = f;
        FAILURES.remove(f.page());
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("Took the first picture of %s for %s in %.2f ms (%s)", name, viewer, (System.nanoTime() - t0) / 1e6,
                    plugin.firstPictures().stats()));
        }
        // As after drawing a new page: what the pointer is over now.
        serverThread.execute(this::repoint);
        if (first) serverThread.execute(this::firstPictureSettled);
        return true;
    }

    /** The first picture this session waited for was drawn by another session ([picture]), or could not be (null). */
    private void landed(FirstPictures.Picture picture) {
        synchronized (this) {
            dirty = false;
            if (closed) {
                rendering = false;
                return;
            }
            // Taken if the state is still the one it is of; else what is wanted now is drawn, here if that one was not.
            offered = picture;
            drawOwn = picture == null;
            submit();
        }
    }

    /** Runs [then] on the server thread once the first picture is on the canvas or could not be drawn. */
    void whenFirstPicture(Runnable then) {
        if (frame != null) then.run();
        else firstPicture = then;
    }

    private void firstPictureSettled() {
        Runnable then = firstPicture;
        firstPicture = null;
        if (then != null) then.run();
    }

    /**
     * Whether a wall shows this session's canvas yet: once the first picture is on it, or, if that takes longer
     * than {@link #BLANK_AFTER_TICKS}, blank until it is.
     */
    boolean showing() {
        return frame != null || Bukkit.getCurrentTick() - born >= BLANK_AFTER_TICKS;
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

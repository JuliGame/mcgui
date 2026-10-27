package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.OpenTrace;
import ai.resourcepack.ui.framework.platform.Platform;
import ai.resourcepack.ui.framework.platform.Threads;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.Pointer;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Repaint;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.Personal;
import ai.resourcepack.ui.html.PooledPicture;
import ai.resourcepack.ui.html.RenderAbandoned;
import ai.resourcepack.ui.html.ScrollCache;
import ai.resourcepack.ui.html.SharedRepaints;
import ai.resourcepack.ui.event.PageClickEvent;
import ai.resourcepack.ui.event.PageOpenEvent;
import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Motion;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.script.Script;
import ai.resourcepack.ui.script.ScriptEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One player's page on one screen: which page, how far it is scrolled, what the pointer is over, and
 * the history the back button walks. Drawing happens off the server thread, at most one picture at a
 * time per session, and a change while one is being drawn just asks for another when it is done.
 * Pages load off the server thread too, and show once loaded. Everything else runs on the server thread.
 */
public final class Session {
    private final MCGUIPlugin plugin;
    private final Renders renderers;
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
    /**
     * This session's copy of the page's document; [hover] and [pressed] are always elements of it. Null from the
     * moment a page is shown until its first picture is drawn or taken: a render thread makes the copy then, off the
     * server thread (see {@link Placeholders.Filled#copy}), and [hover] and [pressed] are null meanwhile. A taken first
     * picture's document is shared with that picture and every session that took it ({@link FirstPictures}): drawing
     * never writes to a document, and a click that changes it gives this session a copy of its own first
     * ({@link #editable}). Written under the session's lock; the render thread only ever sets it from null.
     */
    private volatile Document doc;
    /**
     * What [doc] is filled from (written under the session's lock), and whether the document is still only that
     * (nothing clicked has changed it): only such a document's first picture is shared with other sessions
     * ({@link FirstPictures}).
     */
    private Placeholders.Filled filled;
    private boolean pristine;
    private double scrollY;
    private Element hover, pressed;
    /** Where the pointer last entered an element with a hover sound (its selector), so it plays once per entry. */
    private String hoverSoundAt;
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
    /** A picture the page draws has arrived since the last one was drawn (a face): the next is drawn whole. */
    private boolean arrived;
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
    /**
     * Where whole pictures are painted, reused once no picture of this session shows them (never a {@link #borrowed}
     * image: one kept in {@link FirstPictures} is shared for good). Render thread only, one render at a time.
     */
    private final PooledPicture pooled = new PooledPicture();
    /** A picture another session drew for this one's state, handed over when it landed (server thread). */
    private FirstPictures.Picture offered;
    /** A picture of a page that moves that another session drew for this one's state, handed over when it was published (server thread). */
    private AnimFrames.Shared offeredFrame;
    /** A hover repaint another session drew for this one's state (or failed to: null), handed over when it landed (server thread). */
    private RepaintLanded offeredRepaint;

    /** The hover repaint [key] another session was drawing, as it landed: [prepared], or null if it is not partial or was not drawn. */
    private record RepaintLanded(Object key, Repaint.Prepared prepared) {}
    /**
     * The last picture of a page that moves this session drew and shared (AnimFrames), until it next draws or shows
     * another, when it settles whether anyone took it ({@link #retireShared}). Set by the render thread, read by the
     * server thread between pictures.
     */
    private volatile AnimFrames.Shared sharedFrame;
    /** The next picture is drawn here, not waited for: the one waited for could not be drawn. */
    private boolean drawOwn;
    /** The tick this session was made. */
    private final int born;
    /**
     * Hover repaints drawn before the pointer gets there (HoverAhead), while it rests and the render threads are idle;
     * [ahead] is the drawing under way, which holds this session's turn to draw ([rendering]) as a picture would.
     */
    private final HoverAhead hoverAhead;
    /**
     * Pictures this session drew whole, drew as a repaint of part of the last (or a view cut from a tall picture), and
     * took from another session (AnimFrames): for tests and benchmarks.
     */
    final AtomicLong drewWhole = new AtomicLong(), drewPart = new AtomicLong(), tookFrames = new AtomicLong();
    /** First pictures drawn over another player's copy of a personal page, their values repainted (tests). */
    final AtomicLong fromTheirs = new AtomicLong();
    private HoverAhead.Job ahead;
    /** Looks ahead again, on the server thread: when another session's drawing ahead is over. */
    private final Runnable lookAgain;
    /** The hovers the client shows by itself, without a picture (a view with hover layers); null without. */
    private volatile ClientHovers hovers;
    /** The shown page's JavaScript, or null: nearly every page has none, and then nothing here changes how it is drawn. */
    private volatile Script script;
    /** Held by every drawing into [canvas] while a page with a script is shown, which draws into it from its own thread too. */
    private final java.util.concurrent.locks.ReentrantLock paintLock = new java.util.concurrent.locks.ReentrantLock();
    /** How long the pointer rests on a hover the client shows by itself before the picture catches up with it ({@link #catchUp}). */
    private static final long CATCH_UP_NANOS = 100_000_000L;
    /** When the pointer last moved (nanoTime), and how fast, canvas pixels a second. */
    private long pointerAt;
    private double pointerVx, pointerVy;
    /**
     * Ticks a wall or view waits for a session's first picture before showing its canvas, blank, all the same. A frame
     * whose map the client has no colours for draws the map item, so a screen shows nothing new until its page is
     * drawn, and then the page, one packet a map; a blank first only if drawing takes longer than this. (A view is
     * not switched to before its picture has arrived, so there the wait is unseen.)
     */
    static final int BLANK_AFTER_TICKS = 10;

    /** [designHeight]: how many CSS pixels tall the canvas is, unless the first page shown sets its own ui:height. */
    Session(MCGUIPlugin plugin, Renders renderers, Canvas canvas, UUID viewer, double designHeight) {
        this.plugin = plugin;
        this.renderers = renderers;
        // On Folia, a player's session goes back to their own thread, and a shared one to the global region's.
        this.serverThread = Threads.folia() && viewer != null ? task -> {
            Player p = Bukkit.getPlayer(viewer);
            (p != null ? Threads.player(plugin, p, plugin.serverThread()) : plugin.serverThread()).execute(task);
        } : plugin.serverThread();
        this.canvas = canvas;
        // Players' pictures of one page are alike but where their own values are, and where they hover: one tile for each
        // place they are alike in, whoever drew it.
        canvas.shareAlike(true);
        this.viewer = viewer;
        this.designHeight = designHeight;
        this.born = Platform.tick();
        this.hoverAhead = new HoverAhead(plugin.hoverAhead(), plugin.repaints());
        this.lookAgain = () -> serverThread.execute(this::lookAhead);
    }

    /** The page shown, or null before the first has loaded. */
    Page page() {
        return page;
    }

    /**
     * A picture the page draws has arrived (a player's face, until now its stand-in): drawn again whole. A plain
     * {@link #render} would find the same document, scroll and state as the picture on the screen and draw nothing.
     */
    synchronized void pictureArrived() {
        arrived = true;
        render();
    }

    /** Whether the page shown draws pictures that can arrive after it is drawn (see {@link Page#schemed}). */
    boolean schemed() {
        Page p = page;
        return p != null && p.schemed();
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

    /**
     * Hands the hovers drawn ahead to the client as hover layers ({@link ClientHovers}), so it shows each the frame its
     * pointer gets there. A view's, once it is open; null for none.
     */
    void layers(ai.resourcepack.ui.framework.api.HoverLayers layers) {
        hovers = layers == null ? null : new ClientHovers(layers);
    }

    /**
     * Arms layers for what [job] drew ahead, and what other sessions drew of this picture, which it found kept: on the
     * render thread, once the drawing ahead is over, when nothing draws into the picture. The least urgent work there is:
     * it stops the moment this session asks for a real picture (the job is stopped then) or any other work waits for a
     * render thread, and a drawing ahead from where it stopped then arms the rest. Its CPU comes out of drawing ahead's.
     */
    private void armLayers(HoverAhead.Job job) {
        ClientHovers h = hovers;
        HtmlRenderer.Frame f = frame;
        // While something on the page moves its picture changes many times a second, and every change takes the layers off.
        // A script's canvas changes without a picture, so a hover drawn ahead of it would show an old frame of it.
        if (h == null || f == null || !still() || job.stopped() || script != null) return;
        java.util.function.BooleanSupplier stop = () -> job.stopped() || renderers.queued() || !HoverAhead.quiet();
        long c0 = CPU.getCurrentThreadCpuTime();
        List<Element> drawn = drawnHover;
        var ready = hoverAhead.ready(f, drawn, drawnPress, drawnPress, k -> !k.equals(drawn) && h.wants(f, k));
        boolean done = h.arm(f, drawn, ready, pointerX, pointerY, stop);
        HoverAhead.charge(CPU.getCurrentThreadCpuTime() - c0);
        if (!done) hoverAhead.again();
    }

    /**
     * With hover layers the client shows a hover by itself over a picture drawn in another state, and nothing is drawn
     * ({@link #pointer}). Once the pointer has rested there for {@link #CATCH_UP_NANOS} and the render threads have
     * nothing else to do, the picture catches up: the hover was drawn ahead (its layer is made of it), so this only
     * applies it, and the player sees no change. Else the next hover the client cannot show by itself is drawn from the
     * old state, undoing that too: two changes as far apart as the two targets, which on the market is the whole picture
     * (off the close button, then onto the trade button), and what is drawn ahead meanwhile is drawn over the old state.
     * Server thread, every tick.
     */
    private synchronized void catchUp() {
        ClientHovers h = hovers;
        if (h == null || closed || rendering || page == null || pointerX < 0 || System.nanoTime() - pointerAt < CATCH_UP_NANOS) return;
        HtmlRenderer.Frame f = frame;
        if (f == null || f.image() == null || !idle()) return;
        // Render-thread fields, settled while nothing is drawn.
        List<Element> hoverKey = key(hover, false);
        if (hoverKey.equals(drawnHover) || !key(pressed, true).equals(drawnPress) || !h.armed(f, hoverKey)) return;
        render();
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
            else if (tell != null && tell.isOnline()) Platform.actionBar(tell, Component.text("There is no page called " + n, NamedTextColor.RED));
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
        Placeholders.Filled f = Placeholders.fill(p.document, player());
        synchronized (this) {
            filled = f;
            doc = null;
        }
        pristine = true;
        scrollY = scroll;
        hover = null;
        pressed = null;
        refreshTicks = refreshSetting(p);
        nextRefresh = Platform.tick() + refreshTicks;
        pendingFragment = fragment;
        hoverSoundAt = null;
        motion = new Motion();
        shownAt = Platform.tick();
        runScript(p);
        Player viewerNow = player();
        Sounds.play(viewerNow, p.meta("ui:open-sound"));
        if (viewerNow != null) Bukkit.getPluginManager().callEvent(new PageOpenEvent(viewerNow, n, screen));
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
            Placeholders.Filled f = Placeholders.fill(p.document, player());
            synchronized (this) {
                filled = f;
                doc = null;
            }
            pristine = true;
            // The new file can be shaped differently: nothing carries over by position.
            hover = null;
            pressed = null;
            motion = new Motion();
            shownAt = Platform.tick();
            refreshTicks = refreshSetting(p);
            runScript(p);
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
        // The picture's document was this session's (or shared with a kept picture), so it was never changed after being drawn.
        synchronized (this) {
            doc = f.document();
        }
        // Not what [filled] describes: never kept or taken as a first picture.
        pristine = false;
        scrollY = f.scrollY();
        hover = null;
        pressed = null;
        pendingFragment = null;
        // Its motion was of the other page's elements.
        motion = new Motion();
        shownAt = Platform.tick();
        refreshTicks = refreshSetting(page);
        nextRefresh = Platform.tick() + refreshTicks;
        runScript(page);
        Player pl = player();
        if (pl != null) Platform.actionBar(pl, Component.text("Could not show " + failed + ": see the console", NamedTextColor.RED));
        render();
    }

    /** Every tick: re-fill placeholders on pages that ask for it, and let go of a tall picture nobody scrolls, and of a picture nobody draws over. */
    void tick(long now) {
        // Something on the page is moving: the next picture of it, so many times a second.
        Page shown = page;
        if (shown != null && shown.moves() && motion.active() && now % plugin.animationTicks() == 0) render();
        catchUp();
        ScrollCache c = cache;
        if (c != null && c.idleNanos() > CACHE_IDLE_NANOS) cache = null;
        if (now % 20 == 0) park();
        if (refreshTicks > 0 && page != null && now >= nextRefresh) {
            nextRefresh = now + refreshTicks;
            Placeholders.Filled fresh = Placeholders.fill(page.document, player());
            // Every value the same: the copy would be the same document, and its picture the same picture.
            if (filled != null && fresh.source() == filled.source() && fresh.key().equals(filled.key())) return;
            Document d = doc;
            if (pristine && hover == null && pressed == null) {
                // Nothing to carry over: copied when it is drawn (or the first picture taken), off the server thread.
                synchronized (this) {
                    filled = fresh;
                    doc = null;
                }
            } else {
                // What clicks changed (an open <details>, a ticked box) and what the pointer is on carry over.
                Document copy = fresh.copy();
                if (d != null) keepFormState(d, copy);
                hover = counterpart(hover, copy);
                pressed = counterpart(pressed, copy);
                synchronized (this) {
                    filled = fresh;
                    doc = copy;
                }
            }
            render();
        }
    }

    /** Copies what a click can change, ticks, values, chosen options and open details, from [from] to [to], copies of the same page. */
    private static void keepFormState(Document from, Document to) {
        Elements a = from.getAllElements(), b = to.getAllElements();
        if (a.size() != b.size()) return;
        for (int i = 0; i < a.size(); i++) {
            String attr = switch (a.get(i).normalName()) {
                case "input" -> "checked";
                case "option" -> "selected";
                case "details" -> "open";
                default -> null;
            };
            if (attr == null) continue;
            // A slider's or number field's value is the click's too.
            String type = a.get(i).attr("type").toLowerCase(Locale.ROOT);
            if (type.equals("range") || type.equals("number")) b.get(i).attr("value", a.get(i).attr("value"));
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
        if (e == null || to == null) return null;
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
            // A drawing ahead gives way at once, unless it is drawing just this.
            if (ahead != null) ahead.wanted(ahead.of(page, doc, scrollY), key(hover, false), key(pressed, true));
            return;
        }
        rendering = true;
        submit();
    }

    /**
     * Draws ahead the hovers the pointer may reach next (HoverAhead), if the render threads have nothing else to do and
     * the picture on the screen is of the state now. Server thread; the drawing itself is on a render thread.
     */
    private synchronized void lookAhead() {
        if (closed || rendering || !hoverAhead.on() || page == null || doc == null || pointerX < 0 || cache != null || pendingFragment != null) return;
        // On a page that moves, only while nothing does: a hover drawn ahead then shows the first moment of the transitions
        // it starts (Repaint.prepare styles it with a copy of the motion, taken on with it).
        if (!page.styles.hovers() || page.siblingStates() || !still() || page.moves() && !motion.styled() || !idle()) return;
        HtmlRenderer.Frame f = frame;
        double scale = scale();
        // A picture put away is drawn ahead over once it is back, when it is next drawn over.
        if (f == null || f.image() == null || f.page() != page || f.document() != doc || f.scale() != scale) return;
        if (Math.abs(f.scrollY() - Math.max(0, Math.min(scrollY, f.maxScroll()))) >= 0.01) return;
        double x = pointerX / scale, y = pointerY / scale;
        if (hoverAhead.done(f, x, y)) return;
        // The picture shows the state now (no picture is being drawn, so the render thread's fields are settled). Not while
        // the client shows a hover by itself over a picture of another state (ClientHovers): all drawn ahead from there
        // would undo that state too, and the layers armed already serve the pointer's next move. Once it rests the
        // picture catches up (catchUp), and drawing ahead goes on from what the player sees.
        List<Element> hoverKey = key(hover, false), pressKey = key(pressed, true);
        if (!hoverKey.equals(drawnHover) || !pressKey.equals(drawnPress)) return;
        if (!HoverAhead.claim(lookAgain)) return;
        hoverAhead.personal(personalCopy());
        // From here every way out releases the turn: one kept by mistake would stop drawing ahead for every session.
        try {
            boolean resting = System.nanoTime() - pointerAt > 150_000_000L;
            HoverAhead.Job job = hoverAhead.job(page, doc, canvas.width(), canvas.height(), scale, f, drawnHover, drawnPress,
                    hoverKey, pressed, pressKey, x, y, resting ? 0 : pointerVx / scale, resting ? 0 : pointerVy / scale, plugin.renderTimeout());
            ahead = job;
            rendering = true;
            Motion m = page.moves() ? motion : null;
            double[] at = moment();
            renderers.execute(Renders.Kind.AHEAD, () -> {
                try {
                    if (m != null) {
                        m.moment(at[0], at[1]);
                        Motion.set(m);
                    }
                    hoverAhead.run(job);
                    armLayers(job);
                } catch (RuntimeException | Error e) {
                    // It may have left nothing usable; real pictures draw as ever.
                    hoverAhead.drop();
                    if (plugin.debug()) e.printStackTrace();
                } finally {
                    Motion.clear();
                    HoverAhead.release();
                    done();
                }
            });
        } catch (RuntimeException e) {
            // Shutting down (the pool refused the job), or the job could not be set up: nothing runs, so nothing else releases it.
            HoverAhead.release();
            ahead = null;
            rendering = false;
            if (!(e instanceof java.util.concurrent.RejectedExecutionException) && plugin.debug()) e.printStackTrace();
        }
    }

    /** Whether a thread of the {@link Renders} pool is free and nothing waits for one, and no whole picture is being painted. */
    private boolean idle() {
        return renderers.idle() && HoverAhead.quiet();
    }

    /**
     * The picture on the screen, put away (ParkedPicture) while nobody draws over it: the frame keeps its hit regions,
     * styles and document, and no image. Set and cleared by a render thread while this session holds its turn to draw
     * ([rendering]); {@link #submit} puts it back before anything else.
     */
    private volatile ai.resourcepack.ui.html.ParkedPicture parked;
    /** When this session last finished a turn to draw (nanoTime). */
    private volatile long drawnAt = System.nanoTime();

    /**
     * Puts the picture away if nothing has been drawn and the pointer has not moved for {@code park-pictures-after}
     * (config.yml): its pixels, in blocks shared with every parked picture holding the same ones, instead of a whole
     * picture per view. Not a picture shared with other sessions (it costs this one nothing), one of a page that moves
     * or whose pictures can arrive late (what is kept for those is keyed by the picture itself), nor one a tall
     * picture for scrolling is kept with. Server thread; the parking itself is on a render thread, holding the turn.
     */
    private synchronized void park() {
        long after = plugin.parkAfterNanos();
        if (after <= 0 || closed || rendering || dirty || parked != null || cache != null || sharedFrame != null) return;
        Page p = page;
        HtmlRenderer.Frame f = frame;
        if (p == null || f == null || p.moves() || p.schemed() || f.image() == null || f.image() == borrowed) return;
        if (!ai.resourcepack.ui.html.ParkedPicture.parks(f.image())) return;
        long now = System.nanoTime();
        if (now - drawnAt < after || now - pointerAt < after) return;
        rendering = true;
        try {
            renderers.execute(Renders.Kind.PARK, () -> {
                try {
                    HtmlRenderer.Frame shown = frame;
                    if (shown == null || shown.image() == null || shown.image() == borrowed || !ai.resourcepack.ui.html.ParkedPicture.parks(shown.image())) return;
                    ai.resourcepack.ui.html.ParkedPicture pp = ai.resourcepack.ui.html.ParkedPicture.of(shown.image());
                    HtmlRenderer.Frame bare = new HtmlRenderer.Frame(null, shown.scale(), shown.viewportWidth(), shown.viewportHeight(), shown.pageHeight(),
                            shown.scrollY(), shown.hits(), shown.styles(), shown.document(), shown.page());
                    // Nothing keeps the image from now on: drawing ahead forgets the pictures it worked from, hover
                    // layers come off (they are armed again over the picture put back), and the image goes back to the pool.
                    hoverAhead.letGo();
                    ClientHovers layers = hovers;
                    if (layers != null) layers.reset();
                    parked = pp;
                    frame = bare;
                    pooled.start(bare);
                    parkedPictures.incrementAndGet();
                } catch (RuntimeException | Error e) {
                    if (plugin.debug()) e.printStackTrace();
                } finally {
                    done();
                }
            });
        } catch (RuntimeException e) {
            // Shutting down: the pool refused the job.
            rendering = false;
        }
    }

    /** Pictures this session put away (tests). */
    final AtomicLong parkedPictures = new AtomicLong();

    /** Puts the parked picture back as the frame's image, in a pooled picture, exactly as it was. Render thread, holding the turn. */
    private void unpark() {
        ai.resourcepack.ui.html.ParkedPicture pp = parked;
        HtmlRenderer.Frame f = frame;
        if (pp == null) return;
        try {
            if (f != null && f.image() == null) {
                pooled.start(f);
                java.awt.image.BufferedImage img = pp.unpark(pooled.take(pp.width(), pp.height()));
                HtmlRenderer.Frame back = new HtmlRenderer.Frame(img, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(),
                        f.hits(), f.styles(), f.document(), f.page());
                frame = back;
                pooled.shown(back);
            }
        } finally {
            parked = null;
        }
    }

    /** This session's turn to draw is over (render thread): another picture if the state moved on meanwhile, else perhaps a drawing ahead. */
    private void done() {
        drawnAt = System.nanoTime();
        synchronized (this) {
            ahead = null;
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
                return;
            }
            rendering = false;
        }
        if (hoverAhead.on() && pointerX >= 0) serverThread.execute(this::lookAhead);
    }

    /** The hover and press keys of the picture last drawn (render thread only). */
    private List<Element> drawnHover = List.of(), drawnPress = List.of();
    /** The elements that moved (or were waiting to) in the picture last drawn: the next picture repaints them (render thread only). */
    private Set<Element> drawnMoved = Set.of();

    private List<Element> key(Element e, boolean active) {
        return page == null ? List.of() : page.styles.stateKey(e, active);
    }

    private void submit() {
        if (parked != null) {
            // The picture was put away: back first, on a render thread, then this is asked again, still holding the turn.
            try {
                renderers.execute(Renders.Kind.PICTURE, () -> {
                    try {
                        unpark();
                    } catch (RuntimeException | Error e) {
                        // Not put back: the next picture is drawn whole.
                        frame = null;
                        if (plugin.debug()) e.printStackTrace();
                    }
                    serverThread.execute(() -> {
                        synchronized (Session.this) {
                            if (!closed) submit();
                            else rendering = false;
                        }
                    });
                });
            } catch (RuntimeException e) {
                rendering = false;
            }
            return;
        }
        // The last picture this session shared is settled before anything is drawn or shown over it.
        retireShared();
        if (arrived) {
            // Everything drawn so far has the stand-in in it: the picture, its tall copy and the hovers drawn ahead.
            arrived = false;
            frame = null;
            cache = null;
            hoverAhead.drop();
        }
        Page p = page;
        Document held = doc;
        Placeholders.Filled fill = filled;
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
        boolean lasting = refreshTicks == 0 || nextRefresh - Platform.tick() > CACHE_MIN_TICKS;
        boolean shift = plugin.tileShift();
        // The first picture of this copy of the page, as every session's is: taken from FirstPictures if it is kept
        // there, waited for if another session is drawing it, else drawn here and kept for the next.
        FirstPictures.Key first = drawOwn ? null : firstKey(p, held, s, fragment, scale);
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
            if (take != null && !take.key.personal()) {
                adopt(take, name);
                rendering = false;
                return;
            }
        }
        // Another player's first picture of this personal page: their values in it are repainted with this player's
        // own before anything is shown (Personal), on the render thread.
        FirstPictures.Picture theirs = take;
        FirstPictures.Key publish = first;
        FirstPictures pictures = plugin.firstPictures();
        // A page that moves is drawn at the moment each picture is of. While something on it moves, a picture differs
        // from the last by time alone: what moved is repainted (or all of it, if that is too much), and nothing is cut
        // from a tall picture of another moment. While nothing does, it is drawn as a page that never moves.
        Motion moving = p.moves() ? motion : null;
        boolean animating = moving != null && moving.active();
        double[] moment = moment();
        // While only animations that repeat for ever move, every viewer of this document in this state wants the same
        // picture at each tick: one draws it, the others take it (AnimFrames).
        AnimFrames.Shared share = null, landedFrame = offeredFrame;
        offeredFrame = null;
        AnimFrames.Key frameKey = animating && !moving.personal() && !p.scripted() ? frameKey(p, held, s, fragment, scale, hoverKey, pressKey) : null;
        if (frameKey != null) {
            AnimFrames frames = plugin.animFrames();
            if (landedFrame != null) {
                // The one waited for: taken if it is still of this state, else this session draws its own.
                if (landedFrame.key.equals(frameKey) && frames.take(landedFrame, canvas)) {
                    tookFrame(landedFrame);
                    return;
                }
            } else {
                AnimFrames.Claim claim = frames.claim(frameKey, Platform.tick(), canvas, sh -> serverThread.execute(() -> frameLanded(sh)));
                if (claim.waits()) return; // still drawing, as far as render() goes
                if (claim.taken() != null) {
                    tookFrame(claim.taken());
                    return;
                }
                share = claim.mine();
            }
        }
        AnimFrames.Shared sharing = share;
        // The hover repaint this picture is, if any session in this state may have drawn it (SharedRepaints): taken if
        // it is kept; waited for, without holding a render thread, if another session is drawing it; else claimed, so
        // whoever wants it meanwhile waits for this one. Claimed before drawings ahead give way, so a drawing ahead of
        // it that this session waits for is finished, not stopped.
        Object shareKey = shareable(p, held, s, fragment, scale, hoverKey, pressKey);
        // This player's own copy of a personal page whose repaints are shared with other players' copies, or null.
        Personal.Copy shareCopy = shareKey instanceof Personal.Key ? personalCopy() : null;
        RepaintLanded landedRepaint = offeredRepaint;
        offeredRepaint = null;
        SharedRepaints.Claim claim = null;
        if (shareKey != null) {
            if (landedRepaint != null && landedRepaint.key().equals(shareKey) && landedRepaint.prepared() != null) {
                claim = SharedRepaints.Claim.kept(landedRepaint.prepared());
            } else {
                claim = hoverAhead.claim(shareKey, frame, hoverKey, landedRepaint == null);
                if (claim.pending() != null) {
                    Object k = shareKey;
                    claim.pending().whenComplete((r, e) -> serverThread.execute(() -> repaintLanded(new RepaintLanded(k, r))));
                    return; // still drawing, as far as render() goes
                }
            }
        }
        SharedRepaints.Claim claimed = claim;
        // Real work first: drawings ahead, of every session, give way.
        HoverAhead.makeRoom();
        // The picture's urgency, for the queue it waits in: a page not yet shown first, then what a player's own action
        // asks for, and an animation's next moment (which differs from the last by time alone) after the rest.
        Renders.Kind kind = frame == null || frame.page() != p ? Renders.Kind.SHOW
                : animating && sameView(p, held, s, fragment, scale, hoverKey, pressKey) ? Renders.Kind.MOTION : Renders.Kind.PICTURE;
        OpenTrace.Open trace = OpenTrace.of(viewer);
        if (trace != null) trace.mark(OpenTrace.Open.QUEUED);
        renderers.execute(kind, () -> {
            OpenTrace.Open traced = OpenTrace.of(viewer);
            if (traced != null) traced.mark(OpenTrace.Open.STARTED);
            boolean published = false, drawnAhead = false, shareSettled = false, tookTheirs = false;
            Motion m = moving;
            if (m != null) {
                m.moment(moment[0], moment[1]);
                Motion.set(m);
            }
            // The document to draw: the session's, or, if it has none yet (a page just shown), a copy made here.
            Document d = held;
            try {
                if (d == null) {
                    d = fill.copy();
                    // Worked out here, off the server thread, while nothing else reads the copy.
                    Personal.Copy made = personalShared(fill, p) ? copyOf(d, fill, p) : null;
                    // The session's from now on, unless another page or fill was shown meanwhile.
                    synchronized (Session.this) {
                        if (doc == null && filled == fill) {
                            doc = d;
                            if (made != null) ownCopy = made;
                        }
                    }
                }
                long t0 = System.nanoTime();
                HtmlRenderer.Frame before = frame;
                pooled.start(before);
                HtmlRenderer.Frame f = null;
                Rectangle2D changed = null;
                boolean partial = false;
                // This picture's: put back once it is drawn, so a failure part way through cannot leave a half-patched one.
                ScrollCache c = cache;
                cache = null;
                // A motion that has styled nothing yet (the page shown again over its own last picture) learns from a whole one.
                boolean same = fragment == null && before != null && before.document() == d && before.page() == p && before.scale() == scale
                        && (m == null || m.styled());
                double at = same ? Math.max(0, Math.min(s, before.maxScroll())) : s;
                // A tall picture of another document or size, or one the view has left, is of no more use: nothing reads it
                // from now on, and a new one may be painted over it.
                ScrollCache dropped = null;
                if (c != null && (animating || !(same && c.of(p, d, canvas.width(), canvas.height(), scale) && c.covers(at)))) {
                    dropped = c;
                    c = null;
                }
                // The canvas shows the last view as it was cut from this tall picture, if there is one.
                ScrollCache kept = c;
                boolean still = same && Math.abs(before.scrollY() - at) < 0.01;
                Set<Element> moved = same ? changedState(hoverKey, pressKey) : Set.of();
                // A picture that differs from the last by time alone repaints just round where what moves was and is.
                double margin = moved.isEmpty() ? Repaint.MOTION_MARGIN : Repaint.DIRTY_MARGIN;
                // What moved in the picture on the screen may have moved again.
                if (same && animating && !drawnMoved.isEmpty()) moved = union(moved, drawnMoved);
                if (still && moved.isEmpty() && !animating) {
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
                Repaint.Result over = theirs == null ? null : overTheirs(theirs, p, d, fill, scale, state, timeout);
                if (over != null) {
                    // Their picture, the canvas showing it with this player's own values already in: only where those
                    // are was drawn, and the rest is shown as it is (its tiles shared).
                    f = over.frame();
                    changed = over.changed();
                    partial = true;
                    tookTheirs = true;
                } else if (c != null) {
                    f = c.frame(at);
                    partial = still;
                } else if (still && !redraw) {
                    // Only the hover or press state moved, or what moves on the page: repaint just what it touches, unless
                    // that was drawn already. On a page that does not move, by any session in this state, ahead or when
                    // its own hover came (SharedRepaints: the same pixels, worked out over a picture equal to this one);
                    // on one that moves, ahead by this session (HoverAhead, with the motion it was styled with, taken on
                    // here), and never while something moves. A picture shared with other sessions (FirstPictures) is
                    // copied first, as the repaint (or the pixels drawn ahead) go into it.
                    Repaint.Prepared ready = null;
                    boolean whole = false;
                    if (claimed != null) {
                        ready = claimed.prepared();
                        // Known not to be partial: drawn whole below.
                        whole = ready == null && claimed.kept() && !claimed.own();
                        if (ready != null && shareCopy != null) {
                            // Another player's copy drew it (or this one, ahead): this player's only if nothing of their
                            // own is near what it painted, with this copy's own frame.
                            Personal.Copy from = Personal.copy(ready.frame().document());
                            ready = from == null || !Personal.clear(shareCopy, before, ready) ? null : Personal.translate(ready, from, shareCopy, before);
                        }
                        // One this copy drew ahead for itself alone, as it paints the player's own values.
                        if (ready == null && !whole && shareCopy != null) ready = hoverAhead.take(before, drawnHover, drawnPress, hoverKey, pressKey);
                    } else if (!animating) {
                        ready = hoverAhead.take(before, drawnHover, drawnPress, hoverKey, pressKey);
                    }
                    drawnAhead = ready != null;
                    if (drawnAhead && ready.motion() != null) m = took(moving, ready.motion(), moment[0]);
                    Repaint.Result r = null;
                    if (!whole) {
                        HtmlRenderer.Frame base = before.image() == borrowed ? FirstPictures.own(before, pooled.take(canvas.width(), canvas.height())) : before;
                        if (drawnAhead) {
                            r = ready.apply(base);
                        } else {
                            long c0 = CPU.getCurrentThreadCpuTime();
                            Repaint.Prepared mineToShare = null;
                            if (claimed != null && claimed.mine() && shareCopy != null) {
                                // Worked out apart and then applied, as a drawing ahead is: what it keeps is then only
                                // the pixels it changes, which other players' copies are compared round (Personal).
                                mineToShare = Repaint.prepare(p, d, canvas.width(), canvas.height(), scale, before.scrollY(), state, base, moved,
                                        ai.resourcepack.ui.html.RenderBudget.of(timeout));
                                // Not partial: drawn whole below, as partial() would have said.
                                r = mineToShare == null ? new Repaint.Result(null, null, Repaint.Why.NO_REGION, null) : mineToShare.apply(base);
                            } else {
                                r = Repaint.partial(p, d, canvas.width(), canvas.height(), scale, before.scrollY(), state, base, moved, margin, timeout);
                            }
                            if (claimed != null && claimed.mine()) {
                                long cpu = CPU.getCurrentThreadCpuTime() - c0;
                                SharedRepaints shared = hoverAhead.shared();
                                if (r.frame() == null) shared.put(shareKey, null, cpu, false);
                                else if (shareCopy == null) shared.put(shareKey, Repaint.Prepared.of(r), cpu, false);
                                // It paints this player's own values: every other copy draws its own.
                                else if (mineToShare == null || !Personal.clear(shareCopy, before, mineToShare)) shared.putOwn(shareKey, false);
                                else shared.put(shareKey, Personal.share(mineToShare, before), cpu, false);
                                shareSettled = true;
                            }
                        }
                    }
                    if (r != null && r.frame() != null) {
                        f = r.frame();
                        changed = r.changed();
                        partial = true;
                    }
                } else if (same && !still && lasting && !animating && ScrollCache.possible(before) && !NO_STRIP.contains(p)) {
                    // Only the view moved (and perhaps the pointer's state with it). Painting is nearly all a picture
                    // costs, and the next notches will want the rows round this view: paint a few views' worth of
                    // the page at once, and cut this view and the next ones from that.
                    try {
                        c = ScrollCache.paint(p, d, canvas.width(), canvas.height(), scale, at, before.scrollY(), state, ScrollCache.VIEWS, timeout,
                                dropped, pooled.take(canvas.width(), canvas.height()));
                    } catch (RenderAbandoned e) {
                        // Several views take several times one view's time: a page that fits render-timeout only a view
                        // at a time is still drawn, a view at a time, from now on.
                        NO_STRIP.add(p);
                    }
                    if (c != null) f = c.frame(at);
                }
                if (f == null) {
                    f = HtmlRenderer.render(p, d, pooled.take(canvas.width(), canvas.height()), scale, s, state, timeout);
                    if (fragment != null && !fragment.isEmpty()) {
                        Element target = d.getElementById(fragment);
                        double top = target == null ? Double.NaN : f.topOf(target);
                        if (!Double.isNaN(top)) {
                            // The first was only for finding the target: drawn again over it, scrolled there.
                            f = HtmlRenderer.render(p, d, pooled.take(canvas.width(), canvas.height()), scale, ScrollCache.snap(top, scale), state, timeout);
                            double scrolled = f.scrollY();
                            // Unless the player has moved on (another page, or scrolled) while this was drawn.
                            serverThread.execute(() -> {
                                if (navigation == nav && scrollY == s) scrollY = scrolled;
                            });
                        }
                    }
                }
                // An overlay page is transparent where it paints nothing, and its view tints the world there: both
                // go out with this drawing.
                canvas.transparency(f.page().overlay());
                canvas.builderMark(plugin.builderMark(f.page()));
                canvas.tint(f.page().overlayTint());
                // A script's canvases are laid over every picture of its page before it reaches the screen (Script.over),
                // under the lock its own frames are drawn under. Null, and none of this, for any page without a script.
                Script js = script;
                if (js != null && js.page() != f.page()) js = null;
                if (js != null) {
                    paintLock.lock();
                    js.placed(f);
                }
                try {
                if (partial) {
                    // Only the changed rectangle goes to the canvas, so only its tiles are re-encoded and re-sent.
                    int x0 = Math.max(0, (int) Math.floor(changed.getX() * scale)), y0 = Math.max(0, (int) Math.floor(changed.getY() * scale));
                    int x1 = Math.min(canvas.width(), (int) Math.ceil(changed.getMaxX() * scale));
                    int y1 = Math.min(canvas.height(), (int) Math.ceil(changed.getMaxY() * scale));
                    // Over another player's picture: shown with this player's values drawn in, as one change, so their
                    // values are never on this canvas, not even for a moment.
                    if (tookTheirs) canvas.show(theirs.canvas, x1 > x0 && y1 > y0 ? f.image().getSubimage(x0, y0, x1 - x0, y1 - y0) : null, x0, y0);
                    else if (x1 > x0 && y1 > y0) {
                        java.awt.image.BufferedImage part = f.image().getSubimage(x0, y0, x1 - x0, y1 - y0);
                        canvas.draw(js == null ? part : js.over(part, x0, y0), x0, y0);
                    }
                } else {
                    // Moved by whole map rows on the same page: the canvas moves too, so its viewers' frames are pointed
                    // at the maps their clients already have and only the rows that came in are sent.
                    int rows = same && shift ? ScrollCache.tileRows(before.scrollY(), f.scrollY(), scale, canvas.rows()) : 0;
                    if (rows != 0 && c != null && c == kept && changed == null) {
                        // Both views cut from the same picture: what stayed in view is the same pixels, moved, so only
                        // the rows that came in are drawn (converting the whole view is most of a notch's work).
                        int band = Math.abs(rows) * ScrollCache.TILE, y0 = rows > 0 ? canvas.height() - band : 0;
                        java.awt.image.BufferedImage in = f.image().getSubimage(0, y0, canvas.width(), band);
                        canvas.scroll(rows, js == null ? in : js.over(in, 0, y0), 0, y0);
                    } else {
                        // Anything else may differ elsewhere too: the whole view, and only tiles that then differ are encoded.
                        canvas.scroll(rows, js == null ? f.image() : js.over(f.image(), 0, 0), 0, 0);
                    }
                }
                } finally {
                    if (js != null) paintLock.unlock();
                }
                drawnHover = hoverKey;
                if (traced != null) traced.mark(OpenTrace.Open.DRAWN);
                drawnPress = pressKey;
                drawnMoved = m != null ? m.moved() : Set.of();
                drawnName = name;
                frame = f;
                (partial ? drewPart : drewWhole).incrementAndGet();
                if (sharing != null) {
                    // Still a picture every viewer in this state wants: unless what moves now runs on this viewer's clock.
                    if (f.document() == d && m != null && m.active() && !m.personal()) {
                        plugin.animFrames().publish(sharing, f, canvas, drawnMoved);
                        sharedFrame = sharing;
                    } else {
                        plugin.animFrames().abandon(sharing);
                    }
                }
                hoverAhead.drop();
                // The layers were of the picture before: the new one's are drawn ahead from it.
                resetLayers();
                if (publish != null && !partial && c == null && f.document() == d && f.scrollY() == 0) {
                    // This session's first picture of the page, drawn as any session's would be: kept for the next to
                    // show it here, with the canvas as it is now. Its image (and document) are shared from now on, so the
                    // image is never handed back. Picture.of cannot return null (CanvasShare).
                    FirstPictures.Picture picture = FirstPictures.Picture.of(publish, f, canvas.copy(), m == null ? null : m.copy());
                    borrowed = f.image();
                    pooled.share(borrowed);
                    pictures.publish(publish, picture);
                    published = true;
                }
                pooled.shown(f);
                AtomicInteger failed = FAILURES.remove(p);
                if (failed != null && failed.get() > 1) {
                    plugin.getLogger().info("Drew page " + name + " after " + failed.get() + " attempts that could not be drawn");
                }
                cache = c;
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("Drew %s for %s in %d ms%s%s%s", pageName, viewer, (System.nanoTime() - t0) / 1_000_000,
                            partial ? String.format(" (only %.0f x %.0f)", changed.getWidth(), changed.getHeight()) : "",
                            c != null ? String.format(" (from a picture %d px tall)", c.strip().image().getHeight()) : "",
                            drawnAhead ? " (drawn ahead)" : ""));
                }
                // A new page under a still pointer: what it is over has changed.
                if (!partial || tookTheirs) serverThread.execute(this::repoint);
            } catch (RuntimeException | Error e) {
                // Once per page revision: a page too heavy to draw fails on every hover and scroll, for every viewer.
                int failures = FAILURES.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
                if (failures == 1) plugin.getLogger().warning("Could not draw page " + name + ": " + e + " (repeats are counted, not logged)");
                if (plugin.debug()) e.printStackTrace();
                // Its pixels may be half painted: nothing drawn ahead over them will do.
                hoverAhead.drop();
                resetLayers();
                // A page that cannot be drawn at all leaves the last picture, of another page, on the screen,
                // where clicks would find nothing: go back to that page, so the screen still works.
                HtmlRenderer.Frame shown = frame;
                pooled.failed(shown);
                if (shown != null && shown.page() != p) serverThread.execute(() -> undrawable(p, nav));
            } finally {
                Motion.clear();
                if (publish != null && !published) pictures.abandon(publish);
                // Not drawn: whoever waits for it draws their own (nothing if it was published).
                if (sharing != null) plugin.animFrames().abandon(sharing);
                // Claimed but not drawn (it failed, or was not the picture drawn after all): whoever waits draws it.
                if (claimed != null && claimed.mine() && !shareSettled) hoverAhead.shared().abandon(shareKey);
                done();
            }
        });
    }

    private static final com.sun.management.ThreadMXBean CPU = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    /**
     * What the picture about to be drawn is kept under as a hover repaint ({@link SharedRepaints}), if it is one: the
     * picture on the screen is of the same document [d], page [p] and [scale], at the scroll [s] is to be drawn at, and
     * only the hover or press state moves, to [hoverKey] and [pressKey]. Else null. Of this player's own copy of a
     * personal page, a key other players' copies find too ({@link Personal#key}). Server thread, before submitting:
     * the picture and its drawn state are settled while this session is not drawing.
     */
    private Object shareable(Page p, Document d, double s, String fragment, double scale, List<Element> hoverKey, List<Element> pressKey) {
        HtmlRenderer.Frame f = frame;
        if (d == null || f == null || fragment != null || cache != null || p.moves()) return null;
        if (f.document() != d || f.page() != p || f.scale() != scale || Math.abs(f.scrollY() - Math.max(0, Math.min(s, f.maxScroll()))) >= 0.01) return null;
        if (changedState(hoverKey, pressKey).isEmpty()) return null;
        Personal.Copy c = personalCopy();
        hoverAhead.personal(c);
        return HoverAhead.shareKey(c, f, drawnHover, drawnPress, hoverKey, pressKey);
    }

    /** Whether pictures of [p] filled as [f] are shared with other players' copies though some values are the player's own (Personal). */
    private boolean personalShared(Placeholders.Filled f, Page p) {
        return f != null && f.personal() && plugin.sharePersonal() && f.source() == p.document && !p.moves() && !p.schemed();
    }

    /**
     * This session's document as a {@link Personal.Copy}, if it is a personal page's copy whose pictures are shared
     * with other players' copies, as it was filled (nothing clicked changed it). Else null. Server thread.
     */
    private Personal.Copy personalCopy() {
        Document d = doc;
        Placeholders.Filled f = filled;
        Page p = page;
        if (d == null || p == null || !pristine || !personalShared(f, p)) return null;
        Personal.Copy c = ownCopy;
        if (c != null && c.doc == d) return c;
        return ownCopy = copyOf(d, f, p);
    }

    /**
     * The {@link Personal.Copy} of this session's document, held here for as long as it shows it: Personal finds copies
     * by their document only while something holds them.
     */
    private volatile Personal.Copy ownCopy;

    /** [d], a copy of [p] made from [f] and not changed since, as a {@link Personal.Copy}: worked out the first time. */
    private static Personal.Copy copyOf(Document d, Placeholders.Filled f, Page p) {
        Personal.Copy c = Personal.copy(d);
        if (c != null) return c;
        Placeholders.Parts parts = f.parts(d);
        return Personal.of(d, p, f.template(), parts.text(), parts.attrs());
    }

    /**
     * This player's first picture of [p], drawn over [theirs], another player's first picture of the same personal page
     * at this size: their picture retargeted to this player's copy [d] (made from [fill]), and only the elements
     * holding this player's own values repainted ({@link Personal}). Null if that cannot be done (the copies differ, or
     * the repaint would move something else): then the picture is drawn whole. Render thread.
     */
    private Repaint.Result overTheirs(FirstPictures.Picture theirs, Page p, Document d, Placeholders.Filled fill, double scale,
                                      Selector.State state, long timeout) {
        Personal.Copy from = theirs.copy;
        if (from == null || !personalShared(fill, p) || theirs.frame.page() != p) return null;
        Personal.Copy mine = copyOf(d, fill, p);
        if (!mine.template.equals(from.template)) return null;
        HtmlRenderer.Frame base = Personal.retarget(theirs.frame, from, mine);
        if (base == null || base.scale() != scale || base.scrollY() != 0) return null;
        base = FirstPictures.own(base, pooled.take(canvas.width(), canvas.height()));
        if (mine.personal().isEmpty()) {
            // The player's own values are only where nothing is drawn from them (a command a click runs): the picture is theirs as it is.
            fromTheirs.incrementAndGet();
            return new Repaint.Result(base, new Rectangle2D.Double(), Repaint.Why.PARTIAL, new Rectangle2D.Double());
        }
        Repaint.Result r = Repaint.retext(p, d, canvas.width(), canvas.height(), scale, 0, state, base, mine.personal(), timeout);
        // Not partial: drawn whole, into the same pooled picture.
        if (r.frame() == null) return null;
        fromTheirs.incrementAndGet();
        return r;
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

    private static Set<Element> union(Set<Element> a, Set<Element> b) {
        if (a.isEmpty()) return b;
        Set<Element> out = Collections.newSetFromMap(new IdentityHashMap<>());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /**
     * Takes on [from], the motion a picture drawn elsewhere (ahead, or by another session) was styled with, in place of
     * [was], the one this picture began with: a copy of its own, as if styled at [viewer] seconds, and current on this
     * (render) thread. Unless the page was shown again meanwhile, which gave the session a new motion: then [was] stays
     * this picture's. Returns the one this picture is drawn with.
     */
    private Motion took(Motion was, Motion from, double viewer) {
        Motion m = from.copyAt(viewer);
        if (!MOTION.compareAndSet(this, was, m)) return was;
        Motion.set(m);
        return m;
    }

    private static final java.lang.invoke.VarHandle MOTION;

    static {
        try {
            MOTION = java.lang.invoke.MethodHandles.lookup().findVarHandle(Session.class, "motion", Motion.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The key of the picture about to be drawn if other viewers may want it too (AnimFrames): of the document shown, in the
     * state, at the place and size its last picture shows. Else null.
     */
    private AnimFrames.Key frameKey(Page p, Document d, double s, String fragment, double scale, List<Element> hoverKey, List<Element> pressKey) {
        return sameView(p, d, s, fragment, scale, hoverKey, pressKey)
                ? new AnimFrames.Key(d, p, canvas.width(), canvas.height(), scale, frame.scrollY(), hoverKey, pressKey) : null;
    }

    /** Whether the next picture is of the same document, scale, place and hover and press state as the last one drawn: it differs, if at all, by time. */
    private boolean sameView(Page p, Document d, double s, String fragment, double scale, List<Element> hoverKey, List<Element> pressKey) {
        HtmlRenderer.Frame f = frame;
        if (d == null || f == null || fragment != null || f.document() != d || f.page() != p || f.scale() != scale) return false;
        if (Math.abs(f.scrollY() - Math.max(0, Math.min(s, f.maxScroll()))) >= 0.01) return false;
        // The render thread's, settled: no picture is being drawn.
        return hoverKey.equals(drawnHover) && pressKey.equals(drawnPress);
    }

    /**
     * Shows [sh], a picture another session drew for this one's state, which this session's canvas already shows (taken
     * by AnimFrames): its frame is this session's from now on, shared, as a first picture taken is. Server thread, in
     * place of drawing.
     */
    private void tookFrame(AnimFrames.Shared sh) {
        AnimFrames frames = plugin.animFrames();
        HtmlRenderer.Frame f = frames.frame(sh);
        frame = f;
        borrowed = f.image();
        drawnMoved = frames.moved(sh);
        cache = null;
        hoverAhead.drop();
        tookFrames.incrementAndGet();
        rendering = false;
    }

    /** The picture this session waited for ([sh]) was published by another session, with a frame or none. */
    private void frameLanded(AnimFrames.Shared sh) {
        synchronized (this) {
            dirty = false;
            if (closed) {
                rendering = false;
                return;
            }
            // Taken if the state is still the one it is of; else what is wanted now is drawn here.
            offeredFrame = sh;
            submit();
        }
    }

    /** The hover repaint this session waited for was drawn by another session, or could not be ([l]). */
    private void repaintLanded(RepaintLanded l) {
        synchronized (this) {
            dirty = false;
            if (closed) {
                rendering = false;
                return;
            }
            // Taken if the state is still the one it is of; else what is wanted now is drawn (or claimed) here.
            offeredRepaint = l;
            submit();
        }
    }

    /**
     * Settles the last picture this session shared ({@link #sharedFrame}), before it draws or shows another: nobody takes it
     * from now on, and if anybody did, it is shared for good, so it is copied before it is painted over and never handed
     * back. Server thread, between pictures.
     */
    private void retireShared() {
        AnimFrames.Shared sh = sharedFrame;
        if (sh == null) return;
        sharedFrame = null;
        java.awt.image.BufferedImage img = plugin.animFrames().retire(sh);
        if (img == null) return;
        pooled.share(img);
        HtmlRenderer.Frame f = frame;
        if (f != null && f.image() == img) borrowed = img;
    }

    /**
     * The key of the first picture of the page as filled in [filled] if the picture to be drawn is one
     * ({@link FirstPictures}): the first of this copy of the page ([d], or none yet), at its top, nothing hovered or
     * pressed, nothing clicked changed, no #fragment, and filled with nobody's own values. Else null. [s],
     * [fragment] and [scale] are what is to be drawn.
     */
    private FirstPictures.Key firstKey(Page p, Document d, double s, String fragment, double scale) {
        HtmlRenderer.Frame f = frame;
        if (!plugin.firstPictures().enabled() || fragment != null || s != 0 || hover != null || pressed != null) return null;
        // What a script draws is this session's alone.
        if (p.scripted()) return null;
        if (!pristine || filled == null || filled.source() != p.document || d != null && f != null && f.document() == d) return null;
        // A face that has not arrived yet would be kept as its stand-in. A page that moves is kept with the motion it was
        // drawn with (its transitions' starting points, when its animations that end began), which the taker takes on.
        if (p.schemed()) return null;
        // A copy filled with the player's own values: kept by its template, and taken only with the taker's own values
        // drawn in over it (Personal), never shown as it is.
        if (filled.personal()) return personalShared(filled, p) ? new FirstPictures.Key(pageName, p, canvas.cols(), canvas.rows(), scale, FirstPictures.Key.PERSONAL + filled.template()) : null;
        return new FirstPictures.Key(pageName, p, canvas.cols(), canvas.rows(), scale, filled.key());
    }

    /**
     * Takes [picture] as this session's picture, as if it had drawn it: the canvas shows it (its tiles and colours,
     * nothing drawn or copied), the frame is its frame, and the document its document, shared with it and every
     * other session that took it (see {@link #doc}). On the server thread, under the session's lock, in place of
     * drawing ([rendering] is set); nothing here is in proportion to the picture's size.
     */
    private void adopt(FirstPictures.Picture picture, String name) {
        long t0 = System.nanoTime();
        HtmlRenderer.Frame f = picture.frame;
        canvas.show(picture.canvas);
        OpenTrace.Open trace = OpenTrace.of(viewer);
        if (trace != null) trace.mark(OpenTrace.Open.KEPT);
        // A copy of the same page filled with the same values, with nothing hovered, pressed or clicked: the very same document.
        doc = picture.document();
        borrowed = f.image();
        drawnHover = List.of();
        drawnPress = List.of();
        // Its motion as the picture was drawn, moved on to now on this viewer's clock: a transition its next hover
        // starts, starts from what the picture shows, and what moved in it is repainted next.
        if (picture.motion != null) motion = picture.motion.copyAt((Platform.tick() - shownAt) / 20.0);
        drawnMoved = picture.motion != null ? motion.moved() : Set.of();
        drawnName = name;
        cache = null;
        frame = f;
        resetLayers();
        FAILURES.remove(f.page());
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("Took the first picture of %s for %s in %.3f ms (%s)", name, viewer, (System.nanoTime() - t0) / 1e6,
                    plugin.firstPictures().stats()));
        }
        // As after drawing a new page: what the pointer is over now.
        serverThread.execute(this::repoint);
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

    /**
     * Whether its screen shows this session's canvas yet: once the first picture is on it, or, if that takes longer
     * than {@link #BLANK_AFTER_TICKS}, blank until it is.
     */
    boolean showing() {
        return frame != null || Platform.tick() - born >= BLANK_AFTER_TICKS;
    }

    public boolean ready() {
        return frame != null;
    }

    public void dispose() {
        Script js = script;
        script = null;
        if (js != null) js.stop();
        synchronized (this) {
            closed = true;
            cache = null;
            if (ahead != null) ahead.cancel();
        }
        hoverAhead.drop();
        resetLayers();
    }

    /** Starts [p]'s scripts for this session, stopping the last page's; nothing for a page without any. */
    private void runScript(Page p) {
        Script was = script;
        if (was != null && was.page() == p) return;
        if (was != null) was.stop();
        script = null;
        if (p == null || !p.scripted() || closed) return;
        resetLayers();
        script = ScriptEngine.start(p, canvas, paintLock, scale(), plugin.getLogger(), pageName, new Script.Host() {
            @Override
            public void command(String command) {
                serverThread.execute(() -> {
                    Player pl = Session.this.player();
                    if (pl != null && !closed) Bukkit.dispatchCommand(pl, command);
                });
            }

            @Override
            public void sound(String sound) {
                serverThread.execute(() -> {
                    Player pl = Session.this.player();
                    if (pl != null && !closed) Sounds.play(pl, sound);
                });
            }

            @Override
            public String player() {
                Player pl = Session.this.player();
                return pl == null ? null : pl.getName();
            }

            @Override
            public void redraw() {
                serverThread.execute(() -> {
                    if (!closed) pictureArrived();
                });
            }
        });
    }

    /**
     * A key went [down] or up on a page with a script: handed to it, and true if the script has it to itself. False on
     * any other page, which then handles the key as it always has.
     */
    boolean scriptKey(ai.resourcepack.ui.framework.api.Key key, boolean down) {
        Script js = script;
        if (js == null) return false;
        js.key(Keys.jsKey(key), Keys.jsCode(key), down);
        // A page's own data-key still clicks. The movement keys are the script's rather than the scroll's; F still goes back.
        if (down && key != ai.resourcepack.ui.framework.api.Key.SWAP_HANDS) key(Keys.name(key));
        return key != ai.resourcepack.ui.framework.api.Key.SWAP_HANDS;
    }

    private void resetLayers() {
        ClientHovers h = hovers;
        if (h != null) h.reset();
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
        long now = System.nanoTime();
        if (pointerX >= 0 && now - pointerAt < 250_000_000L && now > pointerAt) {
            double dt = (now - pointerAt) / 1e9;
            pointerVx = (x - pointerX) / dt;
            pointerVy = (y - pointerY) / dt;
        } else {
            pointerVx = pointerVy = 0;
        }
        pointerAt = now;
        pointerX = x;
        pointerY = y;
        Script js = script;
        if (js != null) js.pointer(x / scale(), y / scale());
        Element seen = at(x, y);
        pointerShape.accept(cursorFor(seen));
        Element e = counterpart(seen, doc);
        // Only a move that changes what :hover matches is worth a picture.
        boolean differs = e != hover && !key(hover, false).equals(key(e, false));
        hover = e;
        hoverSound(e);
        ClientHovers h = hovers;
        if (h == null) {
            if (differs) render();
            else lookAhead();
            return;
        }
        // With hover layers the client may already show this hover over the picture by itself: then nothing is drawn,
        // unless a picture is on its way, which the layers are not of.
        boolean shown = h.shows(frame, x, y, key(e, false), drawnHover);
        if (shown && differs && !drawing()) ClientHovers.SHOWN.incrementAndGet();
        if (shown && !drawing()) lookAhead();
        else if (differs || !shown) render();
        else lookAhead();
    }

    /** Whether a picture (not a drawing ahead) is being drawn or waited for. */
    private synchronized boolean drawing() {
        return rendering && ahead == null;
    }

    /** The pointer is over [e]: the hover sound of the element with one it is in, if it has just come into it. */
    private void hoverSound(Element e) {
        while (e != null && !e.hasAttr("data-hover-sound")) e = e.parent();
        // By selector, not identity: a toggle gives the session a new copy of the same element.
        String at = e == null ? null : e.cssSelector();
        if (java.util.Objects.equals(at, hoverSoundAt)) return;
        hoverSoundAt = at;
        if (e != null) Sounds.play(player(), e.attr("data-hover-sound"));
    }

    public void pointerGone() {
        pointerX = pointerY = -1;
        hoverSoundAt = null;
        synchronized (this) {
            if (ahead != null) ahead.cancel();
        }
        hoverAhead.drop();
        resetLayers();
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
            if (s != null && s.cursorImage != null) {
                String[] image = s.cursorImage.split(" ");
                Pointer custom = Pointer.custom(image[0]);
                if (custom != null) return custom.at(Integer.parseInt(image[1]), Integer.parseInt(image[2]));
            }
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
        if (e.hasAttr("data-command") || e.hasAttr("data-console") || e.hasAttr("data-action") || e.hasAttr("data-message") || e.hasAttr("data-event")) return true;
        return switch (e.normalName()) {
            case "a" -> e.hasAttr("href");
            case "button", "summary", "select", "label" -> true;
            case "input" -> switch (e.attr("type").toLowerCase(Locale.ROOT)) {
                case "checkbox", "radio", "range", "number", "submit", "reset", "image", "button" -> true;
                default -> false;
            };
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
        Script js = script;
        if (js != null) js.click(x / scale(), y / scale(), false);
        // The picture can be behind the session: the element clicked is found in the session's own copy.
        Element e = counterpart(at(x, y), doc);
        if (e == null) return;
        double[] c = css(x, y);
        press(e, new double[]{c[0], c[1] + scrollY}, clicker != null ? clicker : player());
    }

    /**
     * The key [name] was pressed (a letter, a digit, {@code space}...): clicks the page's element whose
     * {@code data-key} names it, if there is one. False if none does.
     */
    public boolean key(String name) {
        Document d = doc;
        if (d == null || page == null || frame == null || frame.page() != page) return false;
        for (Element e : d.select("[data-key]")) {
            if (!Keys.names(e.attr("data-key")).contains(name)) continue;
            java.awt.geom.Rectangle2D at = boundsInPicture(e);
            press(e, at == null ? new double[]{0, 0} : new double[]{at.getCenterX(), at.getCenterY()}, player());
            return true;
        }
        return false;
    }

    /** Every hotbar slot (0 to 8) whose number key the page shown listens for. */
    java.util.Set<Integer> numberKeys() {
        Document d = doc;
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        if (d == null) return out;
        for (Element e : d.select("[data-key]")) {
            for (String k : Keys.names(e.attr("data-key"))) if (k.length() == 1 && k.charAt(0) >= '1' && k.charAt(0) <= '9') out.add(k.charAt(0) - '1');
        }
        return out;
    }

    /** [e] (an element of [doc]) clicked at [at] (CSS pixels of the page) by [p], with its sound and its press. */
    private void press(Element e, double[] at, Player p) {
        Page on = page;
        clickAt = at;
        boolean acted = activate(e, p);
        clickSound(e, acted, p);
        if (page == on && page.styles.dynamic) {
            // :active for a moment, so a press shows. A toggle has given the session a new copy by now.
            Element target = counterpart(e, doc);
            if (target == null) return;
            int press = ++presses;
            pressed = target;
            render();
            Threads.globalLater(plugin, () -> {
                // By identity it could miss: a refresh in between moves pressed to a new copy of the document.
                if (presses == press && pressed != null) {
                    pressed = null;
                    render();
                }
            }, 3);
        }
    }

    /**
     * The sound of a click on [e] that [acted] or not, for [p]: the {@code data-sound} of [e] or its nearest
     * ancestor with one, which plays even where nothing else happens; else, if something did, the page's
     * {@code ui:click-sound}, else the config's.
     */
    private void clickSound(Element e, boolean acted, Player p) {
        for (Element a = e; a != null; a = a.parent()) {
            if (a.hasAttr("data-sound")) {
                Sounds.play(p, a.attr("data-sound"));
                return;
            }
        }
        if (!acted) return;
        String own = page == null ? null : page.meta("ui:click-sound");
        Sounds.play(p, own != null ? own : plugin.clickSound());
    }

    /** The clocks and the transitions under way of the page shown, for its animations: new with each page shown. */
    private volatile Motion motion = new Motion();
    /** The tick the page shown was shown at: where its viewer's clock starts (see {@link #moment}). */
    private int shownAt;

    /**
     * The moment a picture asked for now is of, on the viewer's clock and on the page's ({@link Motion#moment}): in
     * whole ticks, so viewers asking in the same tick are at the same moment.
     */
    private double[] moment() {
        int t = Platform.tick();
        return new double[]{(t - shownAt) / 20.0, t / 20.0};
    }

    /**
     * Whether nothing on the page shown is moving or waiting to ({@link Motion#active}): its next picture then differs
     * from the last only by what the viewer does, so it can be drawn ahead, repainted in part, or shared.
     */
    boolean still() {
        return !motion.active();
    }

    /** The wall screen this session is on, by name, for plugins' events; null for a fullscreen page. */
    String screen;

    /** Where the click being handled landed, in CSS pixels of the page (not the viewport). */
    private double[] clickAt;

    /**
     * [target] (an element of [doc]) or the nearest ancestor that does something, clicked by [p]: false if
     * nothing does, or a plugin cancelled the click ({@link PageClickEvent}).
     */
    private boolean activate(Element target, Player p) {
        for (Element e = target; e != null; e = e.parent()) {
            Element form = Forms.submitted(e);
            if (form == null && !acts(e)) continue;
            if (!allowed(form != null && !Forms.isReset(e) ? form : e, form != null ? Forms.values(form, e) : Forms.values(e, null), p)) return false;
            act(e, form, p);
            return true;
        }
        return false;
    }

    /** Whether a click on [e] does something of its own (a form's submit button aside: see {@link Forms#submitted}). */
    private boolean acts(Element e) {
        String tag = e.normalName();
        if (commands(e) && !tag.equals("form")) return true;
        if (Forms.isSelect(e) || Forms.isSlider(e) || e.hasAttr("data-action")) return true;
        if (tag.equals("a")) return e.hasAttr("href");
        if (tag.equals("summary")) return e.parent() != null && e.parent().normalName().equals("details");
        if (tag.equals("input")) return e.attr("type").equalsIgnoreCase("checkbox") || e.attr("type").equalsIgnoreCase("radio");
        if (tag.equals("label")) return labelled(e) != null;
        return false;
    }

    /** Whether [e] has something to run when clicked, or a {@code data-event} for plugins. */
    private static boolean commands(Element e) {
        return e.hasAttr("data-command") || e.hasAttr("data-console") || e.hasAttr("data-message") || e.hasAttr("data-event");
    }

    private Element labelled(Element label) {
        return label.hasAttr("for") ? doc.getElementById(label.attr("for")) : label.selectFirst("input");
    }

    /** Asks plugins whether [p]'s click on [e], with the fields [values], may go ahead. */
    private boolean allowed(Element e, java.util.Map<String, String> values, Player p) {
        if (p == null) return true;
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        for (org.jsoup.nodes.Attribute a : e.attributes()) attributes.put(a.getKey(), a.getValue());
        PageClickEvent event = new PageClickEvent(p, pageName, screen, e.normalName(), attributes, e.text(), values);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** Does what a click on [e] does; [form] is the form it sends, if it is a submit or reset button. */
    private void act(Element e, Element form, Player p) {
        String tag = e.normalName();
        if (commands(e) && !tag.equals("form")) {
            run(e, Forms.values(e, null), p);
        } else if (form != null) {
            if (Forms.isReset(e)) reset(form);
            else run(form, Forms.values(form, e), p);
        } else if (Forms.isSelect(e)) {
            Element select = editable(e);
            if (select != null) Forms.next(select);
            render();
        } else if (Forms.isSlider(e)) {
            java.awt.geom.Rectangle2D at = boundsInPicture(e);
            Element input = at == null ? null : editable(e);
            if (input != null) Forms.slide(input, (clickAt[0] - at.getX()) / at.getWidth());
            render();
        } else if (e.hasAttr("data-action")) {
            action(e.attr("data-action"), p);
        } else if (tag.equals("a")) {
            link(original(e, "href"), p);
        } else if (tag.equals("summary")) {
            Element details = editable(e.parent());
            if (details == null) return;
            if (details.hasAttr("open")) details.removeAttr("open");
            else details.attr("open", "");
            render();
        } else if (tag.equals("input")) {
            toggle(e);
        } else if (tag.equals("label")) {
            toggle(labelled(e));
        }
    }

    /**
     * Does what [e] says when clicked, for [p]: its data-message, data-command and data-console, with
     * {@code {form.<name>}} filled from [form] and placeholders for [p]; then data-close.
     */
    private void run(Element e, java.util.Map<String, String> form, Player p) {
        if (e.hasAttr("data-message") && p != null) {
            Platform.send(p, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(Placeholders.apply(Forms.fill(original(e, "data-message"), form), p)));
        }
        if (e.hasAttr("data-command") && p != null) runAs(p, Placeholders.apply(Forms.fill(original(e, "data-command"), form), p));
        if (e.hasAttr("data-console")) console(Forms.fill(original(e, "data-console"), form), p);
        if (e.hasAttr("data-close")) close.run();
    }

    /** Where [e] (an element of [doc]) was drawn in the last picture, in CSS pixels of the page; null if it was not. */
    private java.awt.geom.Rectangle2D boundsInPicture(Element e) {
        HtmlRenderer.Frame f = frame;
        Element drawn = f == null || f.page() != page ? null : counterpart(e, f.document());
        return drawn == null ? null : f.boundsOf(drawn);
    }

    /** Puts every field of [form] back as the page has it. */
    private void reset(Element form) {
        Element mine = editable(form);
        if (mine == null) return;
        Element original = counterpart(mine, page.document);
        if (original != null) Forms.reset(mine, original);
        render();
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
        if (command == null) return;
        // On Folia, the console's commands run on the global region.
        if (Threads.folia()) Threads.globalNow(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * [e] (an element of [doc]) in a fresh copy of the document, which becomes the session's: a picture
     * being drawn keeps reading the old one, so nothing changes under it, and a document shared with a kept first
     * picture (and the sessions that took it) is never written to. Null if it is not there.
     */
    private Element editable(Element e) {
        Document copy = doc.clone();
        Element mine = counterpart(e, copy);
        hover = counterpart(hover, copy);
        pressed = counterpart(pressed, copy);
        synchronized (this) {
            doc = copy;
        }
        // Changed by a click from what it was filled as: its pictures are this session's alone.
        pristine = false;
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
            if (p != null) Platform.send(p, Component.text(url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
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
            Platform.actionBar(p, Component.text("There is no page called " + target, NamedTextColor.RED));
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

    private void runAs(Player p, String command) {
        String c = strip(command);
        // On Folia, from [p]'s own thread, which a wall's click may not be on.
        if (Threads.folia()) Threads.now(plugin, p, () -> p.performCommand(c));
        else p.performCommand(c);
    }
}

package ai.resourcepack.ui.script;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.res.Picture;
import ai.resourcepack.ui.html.res.Pictures;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A page's JavaScript, running for one session: the page's {@code <script>}s, with the browser's canvas, timers,
 * {@code requestAnimationFrame} and keyboard and mouse events, so a page can be a game.
 *
 * <p>It never runs on the server thread. Every script runs on a small pool of its own, a frame at a time (up to
 * {@code ui:fps} a second, 20 by default), and a frame that runs longer than {@link #FRAME_LIMIT_NANOS} stops the
 * script for good, so a loop that never ends costs a server one frame's worth of a pool thread, once. Scripts see
 * nothing of Java or the server: only what {@code prelude.js} builds out of the few natives here.
 *
 * <p>What a script draws reaches the screen two ways, both under the session's [lock], which every drawing into the
 * session's canvas holds: at the end of a frame that drew, each canvas is laid over the page's own picture of the place
 * it is in ({@link #compose}); and a picture of the page drawn meanwhile has the canvases laid over it before it is
 * handed to the screen ({@link #over}), so a hover or a scroll never shows the page without them, not for a moment.
 */
public final class PageScript {
    /** What a script may ask of the server: each runs on the server thread, as the viewer. */
    public interface Host {
        void command(String command);

        void sound(String sound);

        /** The viewer's name, or null on a screen everyone shares. */
        String player();
    }

    private static final long FRAME_LIMIT_NANOS = 250_000_000L, START_LIMIT_NANOS = 2_000_000_000L;
    private static final int DEFAULT_FPS = 20, MAX_FPS = 30;
    private static final Object DEADLINE = new Object();
    private static final String PRELUDE = prelude();
    private static final Factory FACTORY = new Factory();
    private static volatile ScheduledThreadPoolExecutor pool;

    private final Page page;
    private final Canvas target;
    private final java.util.concurrent.locks.ReentrantLock lock;
    private final Logger log;
    private final Host host;
    private final String name;
    private final double scale;
    private final long periodNanos;
    /** The page's canvases first, in document order, then any the script made. Script thread only (but [onPage]). */
    private final List<ScriptCanvas> canvases = new ArrayList<>();
    private final int onPage;
    /** The page's canvases: read by the render thread too, so never a list the script adds to. */
    private final ScriptCanvas[] pageCanvases;
    private final long born = System.nanoTime();
    private final ConcurrentLinkedQueue<Object[]> input = new ConcurrentLinkedQueue<>();
    private volatile boolean stopped;

    /** Where each canvas on the page is, as last drawn: [lock]. */
    private Placed[] placed;
    /** Viewport CSS pixels of each canvas on the page (x, y, w, h), or null where it is not drawn; read by the script. */
    private volatile double[][] cssRects;
    private final Map<String, ScriptCanvas.Handle> images = new HashMap<>();

    private Scriptable scope;
    private Function onFrame, onKey, onMouse;
    private int logged;
    private long logWindow;

    /** A canvas on the screen: [rect] in canvas pixels (may reach past the edges), the part of it in view, and the page there. */
    private record Placed(Rectangle rect, Rectangle visible, BufferedImage under) {}

    private PageScript(Page page, Canvas target, java.util.concurrent.locks.ReentrantLock lock, double scale, Logger log, String name, Host host) {
        this.page = page;
        this.target = target;
        this.lock = lock;
        this.scale = scale;
        this.log = log;
        this.name = name;
        this.host = host;
        int fps = DEFAULT_FPS;
        String meta = page.meta("ui:fps");
        if (meta != null) {
            try {
                fps = (int) Math.round(Double.parseDouble(meta.trim()));
            } catch (NumberFormatException ignored) {
                // The default.
            }
        }
        this.periodNanos = 1_000_000_000L / Math.max(1, Math.min(MAX_FPS, fps));
        Elements els = page.document.select("canvas");
        for (Element e : els) canvases.add(new ScriptCanvas(canvases.size(), true, dim(e, "width", 300), dim(e, "height", 150), scale));
        this.onPage = canvases.size();
        this.pageCanvases = canvases.toArray(new ScriptCanvas[0]);
        this.placed = new Placed[onPage];
        this.cssRects = new double[onPage][];
    }

    /**
     * Starts [page]'s scripts for a session drawing into [target] at [scale] canvas pixels a CSS pixel, under [lock];
     * null if the page has none. [name] is the page's, for what goes to the console.
     */
    public static PageScript start(Page page, Canvas target, java.util.concurrent.locks.ReentrantLock lock, double scale, Logger log, String name, Host host) {
        if (!page.scripted()) return null;
        PageScript s = new PageScript(page, target, lock, scale, log, name, host);
        pool().execute(s::boot);
        return s;
    }

    private static ScheduledThreadPoolExecutor pool() {
        ScheduledThreadPoolExecutor p = pool;
        if (p == null) {
            synchronized (PageScript.class) {
                p = pool;
                if (p == null) {
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
                    p = new ScheduledThreadPoolExecutor(threads, r -> {
                        Thread t = new Thread(r, "MCGUI script");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    });
                    p.setRemoveOnCancelPolicy(true);
                    pool = p;
                }
            }
        }
        return p;
    }

    /** Stops every script (the plugin is disabled). */
    public static void shutdown() {
        ScheduledThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
    }

    /** The page this runs the scripts of. */
    public Page page() {
        return page;
    }

    /** Stops this one: no more frames, events or drawing. Any thread. */
    public void stop() {
        stopped = true;
        input.clear();
    }

    // ---------------------------------------------------------------- input (any thread)

    /** The pointer moved to (x, y), CSS pixels of the viewport. */
    public void pointer(double x, double y) {
        if (stopped) return;
        // Only the latest place matters: moves still waiting are dropped for it.
        input.removeIf(e -> "mousemove".equals(e[0]));
        input.add(new Object[]{"mousemove", x, y, 0});
    }

    /** A click at (x, y), CSS pixels of the viewport: [right] for the use key. */
    public void click(double x, double y, boolean right) {
        if (stopped) return;
        int button = right ? 2 : 0;
        input.add(new Object[]{"mousedown", x, y, button});
        input.add(new Object[]{"mouseup", x, y, button});
        input.add(new Object[]{right ? "contextmenu" : "click", x, y, button});
    }

    /** A key went down or up; [key] and [code] as a browser names them ({@code "w"}, {@code "KeyW"}). */
    public void key(String key, String code, boolean down) {
        if (stopped) return;
        input.add(new Object[]{down ? "keydown" : "keyup", key, code});
    }

    // ---------------------------------------------------------------- the page's picture (render thread, under [lock])

    /**
     * A picture of the page, [frame], is about to go on the screen: where each canvas now is, and the page's own pixels
     * under it, which a canvas's frame is drawn over. Under [lock].
     */
    public void placed(HtmlRenderer.Frame frame) {
        if (stopped) return;
        BufferedImage img = frame.image();
        Elements els = frame.document().select("canvas");
        double sc = frame.scale();
        double[][] css = new double[onPage][];
        for (int i = 0; i < onPage; i++) {
            Rectangle2D b = i < els.size() ? frame.boundsOf(els.get(i)) : null;
            if (b == null || b.isEmpty()) {
                placed[i] = null;
                continue;
            }
            double top = b.getY() - frame.scrollY();
            css[i] = new double[]{b.getX(), top, b.getWidth(), b.getHeight()};
            Rectangle r = new Rectangle((int) Math.round(b.getX() * sc), (int) Math.round(top * sc),
                    Math.max(1, (int) Math.round(b.getWidth() * sc)), Math.max(1, (int) Math.round(b.getHeight() * sc)));
            Rectangle visible = r.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
            if (visible.isEmpty()) {
                placed[i] = null;
                continue;
            }
            Placed was = placed[i];
            BufferedImage under = was != null && was.under.getWidth() == visible.width && was.under.getHeight() == visible.height
                    && was.under.getType() == typeOf(img) ? was.under : new BufferedImage(visible.width, visible.height, typeOf(img));
            Graphics2D g = under.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(img, -visible.x, -visible.y, null);
            g.dispose();
            placed[i] = new Placed(r, visible, under);
        }
        cssRects = css;
    }

    /**
     * [img], the part of the page's picture whose top-left is at (x, y) on the screen, with the canvases drawn over it:
     * [img] itself if none is in it, else a copy ([img] may be shared with other pictures). Under [lock], after
     * {@link #placed}.
     */
    public BufferedImage over(BufferedImage img, int x, int y) {
        if (stopped) return img;
        BufferedImage out = img;
        Graphics2D g = null;
        Rectangle area = new Rectangle(x, y, img.getWidth(), img.getHeight());
        for (int i = 0; i < onPage; i++) {
            Placed p = placed[i];
            if (p == null || !p.visible.intersects(area)) continue;
            if (g == null) {
                out = new BufferedImage(img.getWidth(), img.getHeight(), typeOf(img));
                g = out.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(img, 0, 0, null);
                g.setComposite(AlphaComposite.SrcOver);
            }
            draw(g, pageCanvases[i].front, p.rect, -x, -y);
        }
        if (g != null) g.dispose();
        return out;
    }

    private static void draw(Graphics2D g, BufferedImage front, Rectangle r, int dx, int dy) {
        if (front.getWidth() == r.width && front.getHeight() == r.height) {
            g.drawImage(front, r.x + dx, r.y + dy, null);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(front, r.x + dx, r.y + dy, r.width, r.height, null);
        }
    }

    private static int typeOf(BufferedImage img) {
        int t = img.getType();
        return t == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB_PRE : t;
    }

    /** The canvases that changed this frame, laid over the page and handed to the screen. Script thread. */
    private void compose() {
        lock.lock();
        try {
            if (stopped) return;
            for (int i = 0; i < onPage; i++) {
                ScriptCanvas c = pageCanvases[i];
                if (!c.publish()) continue;
                Placed p = placed[i];
                if (p == null) continue;
                BufferedImage out = new BufferedImage(p.under.getWidth(), p.under.getHeight(), p.under.getType());
                Graphics2D g = out.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(p.under, 0, 0, null);
                g.setComposite(AlphaComposite.SrcOver);
                draw(g, c.front, p.rect, -p.visible.x, -p.visible.y);
                g.dispose();
                target.draw(out, p.visible.x, p.visible.y);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- running

    private void boot() {
        if (stopped) return;
        Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal(DEADLINE, System.nanoTime() + START_LIMIT_NANOS);
            scope = cx.initSafeStandardObjects();
            Object init = cx.evaluateString(scope, PRELUDE, "mcgui-prelude.js", 1, null);
            Scriptable api = (Scriptable) ((Function) init).call(cx, scope, scope, new Object[]{natives(cx)});
            onFrame = (Function) ScriptableObject.getProperty(api, "frame");
            onKey = (Function) ScriptableObject.getProperty(api, "key");
            onMouse = (Function) ScriptableObject.getProperty(api, "mouse");
            Function onStart = (Function) ScriptableObject.getProperty(api, "start");
            for (Page.Source s : page.scripts) {
                if (stopped) return;
                try {
                    cx.evaluateString(scope, s.code(), s.name(), 1, null);
                } catch (RhinoException e) {
                    report(e);
                }
            }
            onStart.call(cx, scope, scope, new Object[0]);
        } catch (Overrun e) {
            overran("starting");
            return;
        } catch (RhinoException e) {
            report(e);
        } catch (RuntimeException | StackOverflowError e) {
            log.log(Level.WARNING, "Script on " + name + " failed to start", e);
            stop();
            return;
        } finally {
            Context.exit();
        }
        compose();
        schedule(0);
    }

    private void schedule(long elapsed) {
        if (stopped) return;
        try {
            pool().schedule(this::frame, Math.max(1_000_000L, periodNanos - elapsed), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            stopped = true;
        }
    }

    private void frame() {
        if (stopped) return;
        long t0 = System.nanoTime();
        Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal(DEADLINE, t0 + FRAME_LIMIT_NANOS);
            Object[] e;
            while ((e = input.poll()) != null) {
                try {
                    if (e.length == 3) onKey.call(cx, scope, scope, e);
                    else onMouse.call(cx, scope, scope, e);
                } catch (RhinoException ex) {
                    report(ex);
                }
            }
            try {
                onFrame.call(cx, scope, scope, new Object[]{now()});
            } catch (RhinoException ex) {
                report(ex);
            }
        } catch (Overrun ex) {
            overran("one frame");
            return;
        } catch (RuntimeException | StackOverflowError ex) {
            log.log(Level.WARNING, "Script on " + name + " stopped", ex);
            stop();
            return;
        } finally {
            Context.exit();
        }
        compose();
        schedule(System.nanoTime() - t0);
    }

    private double now() {
        return (System.nanoTime() - born) / 1e6;
    }

    private void overran(String what) {
        stop();
        log.warning("Stopped the script on " + name + ": " + what + " ran longer than "
                + (what.equals("starting") ? START_LIMIT_NANOS : FRAME_LIMIT_NANOS) / 1_000_000 + " ms (a loop that never ends?)");
    }

    private void report(RhinoException e) {
        String where = e.sourceName() == null ? "" : " (" + e.sourceName() + (e.lineNumber() > 0 ? ":" + e.lineNumber() : "") + ")";
        say(Level.WARNING, e.details() + where);
    }

    /** Writes a line from the script to the console, at most 40 every ten seconds. */
    private void say(Level level, String line) {
        long now = System.nanoTime();
        if (now - logWindow > 10_000_000_000L) {
            if (logged > 40) log.info("[" + name + "] ... and " + (logged - 40) + " more lines from its script");
            logWindow = now;
            logged = 0;
        }
        if (++logged > 40) return;
        log.log(level, "[" + name + "] " + line);
    }

    // ---------------------------------------------------------------- what the prelude builds on

    private Scriptable natives(Context cx) {
        Scriptable n = cx.newObject(scope);
        fn(n, "canvases", (c, a) -> (double) onPage);
        fn(n, "canvas", (c, a) -> {
            int i = (int) Context.toNumber(a[0]);
            if (i < 0 || i >= canvases.size()) return null;
            ScriptCanvas sc = canvases.get(i);
            String id = i < onPage ? page.document.select("canvas").get(i).id() : "";
            return c.newArray(scope, new Object[]{new ScriptCanvas.Handle(sc, null), id, (double) sc.width, (double) sc.height});
        });
        fn(n, "create", (c, a) -> {
            if (canvases.size() >= 256) throw Context.reportRuntimeError("Too many canvases");
            ScriptCanvas sc = new ScriptCanvas(canvases.size(), false, (int) Context.toNumber(a[0]), (int) Context.toNumber(a[1]), scale);
            canvases.add(sc);
            return c.newArray(scope, new Object[]{new ScriptCanvas.Handle(sc, null), "", (double) sc.width, (double) sc.height});
        });
        fn(n, "resize", (c, a) -> {
            ScriptCanvas sc = handle(a[0]);
            // A canvas on the page is read by the render thread: its pictures change under the lock.
            if (sc != null && sc.onPage) {
                lock.lock();
                try {
                    sc.resize((int) Context.toNumber(a[1]), (int) Context.toNumber(a[2]));
                } finally {
                    lock.unlock();
                }
            } else if (sc != null) {
                sc.resize((int) Context.toNumber(a[1]), (int) Context.toNumber(a[2]));
            }
            return null;
        });
        fn(n, "context", (c, a) -> {
            ScriptCanvas sc = handle(a[0]);
            if (sc == null) return null;
            if (sc.context == null) sc.context = new Canvas2D(scope, sc, page.fonts, (Scriptable) a[1]);
            return sc.context;
        });
        fn(n, "rect", (c, a) -> {
            int i = (int) Context.toNumber(a[0]);
            double[][] r = cssRects;
            if (i < 0 || i >= r.length || r[i] == null) return null;
            return c.newArray(scope, new Object[]{r[i][0], r[i][1], r[i][2], r[i][3]});
        });
        fn(n, "view", (c, a) -> c.newArray(scope, new Object[]{target.width() / scale, target.height() / scale}));
        fn(n, "image", (c, a) -> image(Context.toString(a[0])));
        fn(n, "imageSize", (c, a) -> {
            if (!(a[0] instanceof ScriptCanvas.Handle h) || h.picture == null) return c.newArray(scope, new Object[]{0.0, 0.0});
            BufferedImage px = h.pixels();
            return c.newArray(scope, new Object[]{(double) px.getWidth(), (double) px.getHeight()});
        });
        fn(n, "imageSrc", (c, a) -> {
            Element e = page.document.getElementById(Context.toString(a[0]));
            return e != null && e.normalName().equals("img") ? e.attr("src") : null;
        });
        fn(n, "log", (c, a) -> {
            String level = Context.toString(a[0]);
            say(level.equals("error") || level.equals("warn") ? Level.WARNING : Level.INFO, Context.toString(a[1]));
            return null;
        });
        fn(n, "now", (c, a) -> now());
        fn(n, "command", (c, a) -> {
            String cmd = Context.toString(a[0]).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) host.command(cmd);
            return null;
        });
        fn(n, "sound", (c, a) -> {
            host.sound(Context.toString(a[0]));
            return null;
        });
        fn(n, "player", (c, a) -> host.player());
        return n;
    }

    private interface Native {
        Object run(Context cx, Object[] args);
    }

    private void fn(Scriptable on, String name, Native f) {
        ScriptableObject.putProperty(on, name, new LambdaFunction(scope, name, 0, (cx, sc, self, args) -> {
            Object[] a = args.length >= 4 ? args : java.util.Arrays.copyOf(args, 4);
            for (int i = args.length; i < a.length; i++) a[i] = Undefined.instance;
            Object r = f.run(cx, a);
            return r == null ? null : r;
        }));
    }

    private static ScriptCanvas handle(Object o) {
        return o instanceof ScriptCanvas.Handle h ? h.canvas : null;
    }

    /** A picture from the page's own folder, by a path relative to the page; null if there is none. */
    private Object image(String src) {
        ScriptCanvas.Handle h = images.get(src);
        if (h != null || images.containsKey(src)) return h;
        Picture pic = null;
        try {
            URI u = page.uri.resolve(src.replace(" ", "%20"));
            pic = page.image(u.toString());
            if (pic == null && "file".equals(u.getScheme())) {
                Path folder = page.file.toAbsolutePath().getParent().normalize();
                Path p = Path.of(u).toAbsolutePath().normalize();
                // A script reads pictures from its page's folder and those under it, nothing else on the server.
                if (p.startsWith(folder) && Files.isRegularFile(p) && Files.size(p) < 8 * 1024 * 1024) pic = Pictures.decode(Files.readAllBytes(p), p.toString());
            }
        } catch (IllegalArgumentException | IOException e) {
            pic = null;
        }
        h = pic == null ? null : new ScriptCanvas.Handle(null, pic);
        if (images.size() < 512) images.put(src, h);
        return h;
    }

    private static int dim(Element e, String attr, int fallback) {
        try {
            int v = Integer.parseInt(e.attr(attr).trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String prelude() {
        try (InputStream in = PageScript.class.getResourceAsStream("/script/prelude.js")) {
            if (in == null) throw new IllegalStateException("script/prelude.js is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A frame ran too long. An Error, so the script's own try/catch cannot swallow it. */
    private static final class Overrun extends Error {
        Overrun() {
            super(null, null, false, false);
        }
    }

    /** Interpreted (so the instruction count is watched), modern JavaScript, and no Java. */
    private static final class Factory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setInterpretedMode(true);
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            cx.setInstructionObserverThreshold(10_000);
            cx.setClassShutter(SHUT);
            // Rhino looks its regular expressions up as a service, and relocating it into the jar loses that file.
            org.mozilla.javascript.ScriptRuntime.setRegExpProxy(cx, new org.mozilla.javascript.regexp.RegExpImpl());
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object d = cx.getThreadLocal(DEADLINE);
            if (d instanceof Long deadline && System.nanoTime() > deadline) throw new Overrun();
        }
    }

    private static final ClassShutter SHUT = className -> false;
}

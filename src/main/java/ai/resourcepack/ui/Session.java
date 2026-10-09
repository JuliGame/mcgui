package ai.resourcepack.ui;

import ai.resourcepack.screens.api.Canvas;
import ai.resourcepack.screens.api.Pointer;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.style.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * One player's page on one screen: which page, how far it is scrolled, what the pointer is over, and
 * the history the back button walks. Drawing happens off the server thread, at most one picture at a
 * time per session, and a change while one is being drawn just asks for another when it is done.
 * Everything else runs on the server thread.
 */
public final class Session {
    private final UIEnginePlugin plugin;
    private final ExecutorService renderers;
    public final Canvas canvas;
    /** The player this session belongs to, or null for a screen everyone shares. */
    public final UUID viewer;
    private final double designHeight;

    private String pageName;
    private Page page;
    private Document doc;
    private double scrollY;
    private Element hover, pressed;
    private final Deque<Entry> back = new ArrayDeque<>();
    private record Entry(String page, double scroll) {}

    private volatile HtmlRenderer.Frame frame;
    private boolean rendering, dirty;
    private boolean closed;
    /** Told the pointer shape for what is under the pointer (views only). */
    public Consumer<Pointer> pointerShape = p -> {};
    /** Closes the view or screen this session is on. */
    public Runnable close = () -> {};
    /** Re-fill placeholders every this many ticks (0: only when the page opens). */
    private int refreshTicks;
    private long nextRefresh;

    Session(UIEnginePlugin plugin, ExecutorService renderers, Canvas canvas, UUID viewer, double designHeight) {
        this.plugin = plugin;
        this.renderers = renderers;
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

    /** Shows [name] (a page in the folder), scrolled to its top or to [fragment]. False if there is no such page. */
    public boolean open(String name, String fragment, boolean remember) {
        Page p = plugin.pages().get(name);
        if (p == null) return false;
        if (remember && pageName != null) back.push(new Entry(pageName, scrollY));
        if (back.size() > 50) back.removeLast();
        pageName = plugin.pages().normalise(name);
        page = p;
        doc = Placeholders.fill(p.document, player());
        scrollY = 0;
        hover = null;
        pressed = null;
        refreshTicks = refreshSetting(p);
        nextRefresh = Bukkit.getCurrentTick() + refreshTicks;
        pendingFragment = fragment;
        render();
        return true;
    }

    private String pendingFragment;

    private static int refreshSetting(Page p) {
        String v = p.meta("ui:refresh");
        if (v == null) return 0;
        try {
            return Math.max(1, (int) Math.round(Double.parseDouble(v.trim()) * 20));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean goBack() {
        Entry e = back.poll();
        if (e == null) return false;
        Page p = plugin.pages().get(e.page);
        if (p == null) return false;
        pageName = e.page;
        page = p;
        doc = Placeholders.fill(p.document, player());
        scrollY = e.scroll;
        hover = null;
        refreshTicks = refreshSetting(p);
        render();
        return true;
    }

    /** The page file changed: show the new one where the player was. */
    void reloaded(String name) {
        if (!name.equals(pageName) || closed) return;
        Page p = plugin.pages().get(name);
        if (p == null) return;
        page = p;
        doc = Placeholders.fill(p.document, player());
        hover = null;
        render();
    }

    /** Every tick: re-fill placeholders on pages that ask for it. */
    void tick(long now) {
        if (refreshTicks > 0 && page != null && now >= nextRefresh) {
            nextRefresh = now + refreshTicks;
            doc = Placeholders.fill(page.document, player());
            render();
        }
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

    private void submit() {
        Page p = page;
        Document d = doc;
        double s = scrollY;
        var state = HtmlRenderer.state(hover, pressed);
        String fragment = pendingFragment;
        pendingFragment = null;
        double scale = scale();
        renderers.execute(() -> {
            try {
                HtmlRenderer.Frame f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, s, state);
                if (fragment != null && !fragment.isEmpty()) {
                    Element target = d.getElementById(fragment);
                    double top = target == null ? Double.NaN : f.topOf(target);
                    if (!Double.isNaN(top)) {
                        f = HtmlRenderer.render(p, d, canvas.width(), canvas.height(), scale, top, state);
                        double scrolled = f.scrollY();
                        Bukkit.getScheduler().runTask(plugin, () -> scrollY = scrolled);
                    }
                }
                canvas.draw(f.image(), 0, 0);
                HtmlRenderer.Frame previous = frame;
                frame = f;
                // A new page under a still pointer: what it is over has changed.
                if (previous == null || previous.hits() != f.hits()) Bukkit.getScheduler().runTask(plugin, this::repoint);
            } catch (RuntimeException | Error e) {
                plugin.getLogger().warning("Could not draw page '" + pageName + "': " + e);
                if (plugin.debug()) e.printStackTrace();
            } finally {
                synchronized (Session.this) {
                    if (dirty && !closed) {
                        dirty = false;
                        // The state moved on while drawing: draw again, reading it on the server thread.
                        Bukkit.getScheduler().runTask(plugin, () -> {
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

    public boolean ready() {
        return frame != null;
    }

    public void dispose() {
        synchronized (this) {
            closed = true;
        }
    }

    // ---------------------------------------------------------------- input

    private double[] css(int x, int y) {
        double s = scale();
        return new double[]{x / s, y / s};
    }

    private Element at(int x, int y) {
        HtmlRenderer.Frame f = frame;
        if (f == null) return null;
        double[] c = css(x, y);
        return f.elementAt(c[0], c[1]);
    }

    /** Where the pointer last was, so a new picture can be checked under it; -1 for nowhere. */
    private int pointerX = -1, pointerY = -1;

    /** The pointer is over pixel (x, y) of the canvas. */
    public void pointer(int x, int y) {
        pointerX = x;
        pointerY = y;
        Element e = at(x, y);
        pointerShape.accept(cursorFor(e));
        if (e != hover) {
            hover = e;
            if (page != null && page.styles.dynamic) render();
        }
    }

    public void pointerGone() {
        pointerX = pointerY = -1;
        if (hover != null) {
            hover = null;
            if (page != null && page.styles.dynamic) render();
        }
    }

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
        Element e = at(pointerX, pointerY);
        pointerShape.accept(cursorFor(e));
        // Only a different element needs drawing again; the same one is already drawn hovered.
        if (e != hover && (hover == null || e == null || !sameElement(e, hover))) {
            hover = e;
            if (page != null && page.styles.dynamic) render();
        } else {
            hover = e;
        }
    }

    /** The same element in two copies of the page: same tag and position in the tree. */
    private static boolean sameElement(Element a, Element b) {
        return a.normalName().equals(b.normalName()) && a.cssSelector().equals(b.cssSelector());
    }

    public void scroll(int notches) {
        HtmlRenderer.Frame f = frame;
        if (f == null) return;
        double to = Math.max(0, Math.min(f.maxScroll(), scrollY + notches * plugin.scrollStep()));
        if (to == scrollY) return;
        scrollY = to;
        render();
    }

    /** A click at (x, y): follows the link or runs the command under it. */
    public void click(int x, int y) {
        Element e = at(x, y);
        if (e == null) return;
        Player p = player();
        boolean acted = activate(e, p);
        if (acted && p != null && plugin.clickSound()) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
        if (page != null && page.styles.dynamic) {
            // :active for a moment, so a press shows.
            Element target = e;
            pressed = target;
            render();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pressed == target) {
                    pressed = null;
                    render();
                }
            }, 3);
        }
    }

    private boolean activate(Element target, Player p) {
        for (Element e = target; e != null; e = e.parent()) {
            if (e.hasAttr("data-command") || e.hasAttr("data-console") || e.hasAttr("data-message")) {
                if (e.hasAttr("data-message") && p != null) p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(e.attr("data-message")));
                if (e.hasAttr("data-command") && p != null) runAs(p, e.attr("data-command"));
                if (e.hasAttr("data-console")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(e.attr("data-console")));
                if (e.hasAttr("data-close")) close.run();
                return true;
            }
            if (e.hasAttr("data-action")) {
                action(e.attr("data-action"));
                return true;
            }
            String tag = e.normalName();
            if (tag.equals("a") && e.hasAttr("href")) {
                link(e.attr("href"), p);
                return true;
            }
            if (tag.equals("summary") && e.parent() != null && e.parent().normalName().equals("details")) {
                Element details = editable(e.parent());
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
     * [e]'s counterpart in a fresh copy of the document, which becomes the session's: a picture
     * being drawn keeps reading the old one, so nothing changes under it.
     */
    private Element editable(Element e) {
        int index = doc.getAllElements().indexOf(e);
        Document copy = doc.clone();
        doc = copy;
        hover = null;
        pressed = null;
        return index < 0 ? e : copy.getAllElements().get(index);
    }

    private void toggle(Element original) {
        Element input = editable(original);
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

    private void action(String raw) {
        String a = raw.trim();
        String l = a.toLowerCase(Locale.ROOT);
        if (l.equals("close")) close.run();
        else if (l.equals("back")) goBack();
        else if (l.equals("reload")) {
            if (pageName != null) open(pageName, null, false);
        } else if (l.equals("top")) {
            scrollY = 0;
            render();
        } else if (l.startsWith("page:")) navigate(a.substring(5).trim());
    }

    private void link(String href, Player p) {
        String h = href.trim();
        String l = h.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.equals("#")) return;
        if (l.startsWith("command:") || l.startsWith("cmd:")) {
            if (p != null) runAs(p, h.substring(h.indexOf(':') + 1));
        } else if (l.startsWith("console:")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(h.substring(8)));
        } else if (l.startsWith("close:")) {
            close.run();
        } else if (l.startsWith("back:")) {
            goBack();
        } else if (l.startsWith("http://") || l.startsWith("https://")) {
            // A page cannot be browsed to: the link goes to chat, where it can be clicked.
            if (p != null) p.sendMessage(Component.text(h, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(h)));
        } else if (h.startsWith("#")) {
            scrollTo(h.substring(1));
        } else if (!l.contains(":")) {
            navigate(h);
        }
    }

    private void navigate(String href) {
        String target = plugin.pages().resolveLink(pageName == null ? "" : pageName, href);
        int hash = href.indexOf('#');
        String fragment = hash >= 0 ? href.substring(hash + 1) : null;
        if (target == null) return;
        if (target.equals(pageName) && fragment != null) {
            scrollTo(fragment);
            return;
        }
        Player p = player();
        if (!open(target, fragment, true) && p != null) {
            p.sendActionBar(Component.text("There is no page called " + target, NamedTextColor.RED));
        }
    }

    private void scrollTo(String id) {
        HtmlRenderer.Frame f = frame;
        Element target = doc == null ? null : doc.getElementById(id);
        if (f == null || target == null) return;
        double top = f.topOf(target);
        if (Double.isNaN(top)) return;
        scrollY = Math.max(0, Math.min(f.maxScroll(), top));
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

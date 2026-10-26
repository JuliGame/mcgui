package ai.resourcepack.ui.script;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;

import java.awt.image.BufferedImage;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A page's JavaScript, running for one session. What runs it (Rhino, Babel and the canvas) is not in the plugin's jar:
 * it is the script engine, fetched the first time a page with a script opens ({@link ScriptEngine}). This is all the
 * plugin knows of it.
 */
public interface Script {
    /** The page this runs the scripts of. */
    Page page();

    /** No more frames, events or drawing. Any thread. */
    void stop();

    /** The pointer moved to (x, y), CSS pixels of the viewport. */
    void pointer(double x, double y);

    /** A click at (x, y), CSS pixels of the viewport: [right] for the use key. */
    void click(double x, double y, boolean right);

    /** A key went down or up; [key] and [code] as a browser names them. */
    void key(String key, String code, boolean down);

    /** A picture of the page is about to go on the screen: where each canvas now is. Under the session's lock. */
    void placed(HtmlRenderer.Frame frame);

    /** [img], the part of the page's picture whose top-left is at (x, y), with the canvases drawn over it. Under the lock. */
    BufferedImage over(BufferedImage img, int x, int y);

    /** What a script may ask of the server: each runs on the server thread, as the viewer. */
    interface Host {
        void command(String command);

        void sound(String sound);

        /** The viewer's name, or null on a screen everyone shares. */
        String player();

        /** Draw the page again: the engine arrived after it was drawn, and its canvases go on the next picture. */
        void redraw();
    }

    /** The script engine's way in: one, made by {@link ScriptEngine} from the engine's jar. */
    interface Factory {
        /** Starts [page]'s scripts; null if it has none. See {@link ScriptEngine#start}. */
        Script start(Page page, Canvas target, ReentrantLock lock, double scale, Logger log, String name, Host host);

        /** Stops every script and lets go of what the engine holds (the plugin is disabled). */
        void shutdown();
    }
}

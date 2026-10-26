package ai.resourcepack.ui.script.engine;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.script.Script;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/** The script engine's way in, made by name by the plugin's ScriptEngine once the engine's jar is loaded. */
public final class Engine implements Script.Factory {
    @Override
    public Script start(Page page, Canvas target, ReentrantLock lock, double scale, Logger log, String name, Script.Host host) {
        return PageScript.start(page, target, lock, scale, log, name, host);
    }

    @Override
    public void shutdown() {
        PageScript.shutdown();
    }
}

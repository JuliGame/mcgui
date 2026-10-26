package ai.resourcepack.ui.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Turns the JavaScript Rhino cannot read (classes, {@code ...} rest and spread, {@code for (const x of ...)},
 * {@code async}/{@code await}, class fields, {@code ??=}) into JavaScript it can, with Babel, which runs in Rhino
 * itself. A script Rhino reads as it is never goes near Babel, so Babel is loaded only by a page that needs it, and let
 * go of once nothing has needed it for {@link #IDLE_NANOS}: it is a few tens of megabytes while loaded. What it makes
 * is kept by the script's text, so a page opened again, or by another player, is not converted again.
 *
 * <p>Generators are converted too (regenerator), and with them what async becomes: Rhino's own generator functions
 * have no prototype, so no {@code apply}, which converted async code calls on them.
 *
 * <p>Babel is told to keep line numbers, so an error in a converted script names the line the author wrote.
 */
final class Transpiler {
    private static final long IDLE_NANOS = 120_000_000_000L;
    /** Converting can take a while the first time (Babel itself is loaded then), but never for ever. */
    private static final long LIMIT_NANOS = 30_000_000_000L;
    private static final String OPTIONS = "{presets: [], sourceType: 'script', retainLines: true, compact: false, comments: false,"
            + " plugins: ['transform-class-properties', 'transform-private-methods', 'transform-private-property-in-object',"
            + " 'transform-class-static-block', 'transform-classes', 'transform-parameters', 'transform-spread',"
            + " 'transform-destructuring', 'transform-for-of', 'transform-block-scoping', 'transform-async-to-generator', 'transform-regenerator',"
            + " 'transform-object-rest-spread', 'transform-logical-assignment-operators', 'transform-numeric-separator',"
            + " 'transform-optional-catch-binding']}";

    private static final Map<String, String> CONVERTED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 128;
        }
    };

    private static Scriptable babel;
    private static Function transform;
    private static long lastUsed;
    private static boolean releaseScheduled;

    private Transpiler() {}

    /** A script that could not be converted: a syntax error, as Babel words it. */
    static final class Failed extends Exception {
        Failed(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * [code] as Rhino can run it: itself when Rhino reads it as it is, else Babel's conversion. On a script thread with
     * no Rhino context entered. Throws Failed for a script that is not JavaScript at all.
     */
    static String prepare(String code, String name) throws Failed {
        Context cx = PageScript.FACTORY.enterContext();
        try {
            cx.putThreadLocal(PageScript.DEADLINE, System.nanoTime() + LIMIT_NANOS);
            try {
                cx.compileString(code, name, 1, null);
                return code;
            } catch (EvaluatorException e) {
                // Rhino cannot read it: Babel may.
            }
            String key = key(code);
            synchronized (Transpiler.class) {
                String done = CONVERTED.get(key);
                if (done != null) return done;
                try {
                    Function t = transform(cx);
                    Object out = t.call(cx, babel, babel, new Object[]{code, name});
                    String converted = Context.toString(out);
                    CONVERTED.put(key, converted);
                    return converted;
                } catch (RhinoException e) {
                    String m = e.details();
                    // Babel's message carries a picture of the code after a line break: the first line says it all.
                    int nl = m.indexOf('\n');
                    throw new Failed((nl > 0 ? m.substring(0, nl) : m) + " (" + name + ")");
                } finally {
                    lastUsed = System.nanoTime();
                    scheduleRelease();
                }
            }
        } finally {
            Context.exit();
        }
    }

    /** Babel's transform, loading Babel first if it is not loaded. Under the class's lock. */
    private static Function transform(Context cx) {
        if (transform != null) return transform;
        Scriptable scope = cx.initSafeStandardObjects();
        // Babel's browser build looks for a global object and a console.
        cx.evaluateString(scope, "var window = this, self = this;"
                + " var console = {log: function () {}, info: function () {}, warn: function () {}, error: function () {}, debug: function () {}};",
                "babel-env", 1, null);
        cx.evaluateString(scope, resource("/script/babel.min.js"), "babel.min.js", 1, null);
        Object fn = cx.evaluateString(scope, "(function (code, name) { return Babel.transform(code, Object.assign({filename: name}, "
                + OPTIONS + ")).code; })", "babel-call", 1, null);
        babel = scope;
        transform = (Function) fn;
        return transform;
    }

    private static void scheduleRelease() {
        if (releaseScheduled) return;
        releaseScheduled = true;
        try {
            PageScript.pool().schedule(Transpiler::releaseIfIdle, IDLE_NANOS, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            releaseScheduled = false;
        }
    }

    private static void releaseIfIdle() {
        synchronized (Transpiler.class) {
            releaseScheduled = false;
            if (babel == null) return;
            if (System.nanoTime() - lastUsed >= IDLE_NANOS) {
                babel = null;
                transform = null;
            } else {
                scheduleRelease();
            }
        }
    }

    private static String key(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = Transpiler.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lets go of Babel and everything converted (the plugin is disabled). */
    static synchronized void clear() {
        babel = null;
        transform = null;
        CONVERTED.clear();
    }
}

package ai.resourcepack.ui.script;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.text.Fonts;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A canvas's {@code getContext('2d')}: the browser's drawing calls, drawn with Java2D into the canvas's picture. Only
 * the script's thread uses it. Paths are kept in the picture's own pixels, transformed as each point is added, as the
 * canvas specification has them; a stroke is made in the space it was drawn in, so a scaled line is scaled.
 *
 * <p>What is here is what games draw with: rectangles, paths, arcs, text, pictures, gradients, transforms, clipping
 * and alpha. Not here (yet): pixel access ({@code getImageData}), patterns, shadows and filters.
 */
final class Canvas2D extends ScriptableObject {
    private static final Pattern FONT_SIZE = Pattern.compile("(?i)^(\\d*\\.?\\d+)(px|pt|em|rem)(?:/\\S+)?$");

    private final ScriptCanvas canvas;
    private final Fonts fonts;
    /** The canvas element the script got this from, for {@code ctx.canvas}. */
    final Scriptable element;
    private State s = new State();
    private final Deque<State> saved = new ArrayDeque<>();
    /** The current path, in the picture's pixels. */
    private Path2D.Double path = new Path2D.Double();
    private final Map<String, Object> methods = new HashMap<>();

    /** Everything save() keeps. */
    private static final class State {
        Object fillValue = "#000000", strokeValue = "#000000";
        Object fill = Color.BLACK, stroke = Color.BLACK;
        double lineWidth = 1, miterLimit = 10, dashOffset;
        String lineCap = "butt", lineJoin = "miter";
        float[] dash;
        String font = "10px sans-serif";
        Font awtFont;
        String textAlign = "start", textBaseline = "alphabetic";
        double alpha = 1;
        String composite = "source-over";
        boolean smoothing = true;
        /** User space to canvas units; the picture's pixels are a fixed scale of those ({@link ScriptCanvas#scale}). */
        AffineTransform tx = new AffineTransform();
        /** In the picture's pixels; null for none. */
        Area clip;
        final Map<String, Object> other = new HashMap<>();

        State copy() {
            State c = new State();
            c.fillValue = fillValue;
            c.strokeValue = strokeValue;
            c.fill = fill;
            c.stroke = stroke;
            c.lineWidth = lineWidth;
            c.miterLimit = miterLimit;
            c.dashOffset = dashOffset;
            c.lineCap = lineCap;
            c.lineJoin = lineJoin;
            c.dash = dash;
            c.font = font;
            c.awtFont = awtFont;
            c.textAlign = textAlign;
            c.textBaseline = textBaseline;
            c.alpha = alpha;
            c.composite = composite;
            c.smoothing = smoothing;
            c.tx = new AffineTransform(tx);
            c.clip = clip == null ? null : new Area(clip);
            c.other.putAll(other);
            return c;
        }
    }

    Canvas2D(Scriptable scope, ScriptCanvas canvas, Fonts fonts, Scriptable element) {
        this.canvas = canvas;
        this.fonts = fonts;
        this.element = element;
        setParentScope(scope);
        setPrototype(ScriptableObject.getObjectPrototype(scope));
        define(scope);
    }

    @Override
    public String getClassName() {
        return "CanvasRenderingContext2D";
    }

    /** The canvas was resized: the browser resets its context's state then. */
    void reset() {
        s = new State();
        saved.clear();
        path = new Path2D.Double();
    }

    // ---------------------------------------------------------------- properties

    @Override
    public Object get(String name, Scriptable start) {
        Object m = methods.get(name);
        if (m != null) return m;
        return switch (name) {
            case "canvas" -> element;
            case "fillStyle" -> s.fillValue;
            case "strokeStyle" -> s.strokeValue;
            case "lineWidth" -> s.lineWidth;
            case "lineCap" -> s.lineCap;
            case "lineJoin" -> s.lineJoin;
            case "miterLimit" -> s.miterLimit;
            case "lineDashOffset" -> s.dashOffset;
            case "font" -> s.font;
            case "textAlign" -> s.textAlign;
            case "textBaseline" -> s.textBaseline;
            case "globalAlpha" -> s.alpha;
            case "globalCompositeOperation" -> s.composite;
            case "imageSmoothingEnabled" -> s.smoothing;
            default -> s.other.containsKey(name) ? s.other.get(name) : super.get(name, start);
        };
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return methods.containsKey(name) || super.has(name, start) || switch (name) {
            case "canvas", "fillStyle", "strokeStyle", "lineWidth", "lineCap", "lineJoin", "miterLimit", "lineDashOffset", "font",
                 "textAlign", "textBaseline", "globalAlpha", "globalCompositeOperation", "imageSmoothingEnabled" -> true;
            default -> s.other.containsKey(name);
        };
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        // Each setting ignores a value it cannot use, as the browser's do.
        switch (name) {
            case "fillStyle" -> {
                Object p = paint(value);
                if (p != null) {
                    s.fill = p;
                    s.fillValue = value instanceof CharSequence ? value.toString() : value;
                }
            }
            case "strokeStyle" -> {
                Object p = paint(value);
                if (p != null) {
                    s.stroke = p;
                    s.strokeValue = value instanceof CharSequence ? value.toString() : value;
                }
            }
            case "lineWidth" -> {
                double v = Context.toNumber(value);
                if (v > 0 && Double.isFinite(v)) s.lineWidth = v;
            }
            case "miterLimit" -> {
                double v = Context.toNumber(value);
                if (v > 0 && Double.isFinite(v)) s.miterLimit = v;
            }
            case "lineDashOffset" -> {
                double v = Context.toNumber(value);
                if (Double.isFinite(v)) s.dashOffset = v;
            }
            case "lineCap" -> {
                String v = Context.toString(value);
                if (v.equals("butt") || v.equals("round") || v.equals("square")) s.lineCap = v;
            }
            case "lineJoin" -> {
                String v = Context.toString(value);
                if (v.equals("miter") || v.equals("round") || v.equals("bevel")) s.lineJoin = v;
            }
            case "font" -> {
                String v = Context.toString(value);
                Font f = parseFont(v);
                if (f != null) {
                    s.font = v;
                    s.awtFont = f;
                }
            }
            case "textAlign" -> s.textAlign = Context.toString(value);
            case "textBaseline" -> s.textBaseline = Context.toString(value);
            case "globalAlpha" -> {
                double v = Context.toNumber(value);
                if (v >= 0 && v <= 1) s.alpha = v;
            }
            case "globalCompositeOperation" -> {
                String v = Context.toString(value);
                if (composite(v, 1) != null) s.composite = v;
            }
            case "imageSmoothingEnabled" -> s.smoothing = Context.toBoolean(value);
            // Shadows and filters are not drawn, but read back what was set, as a script may expect.
            default -> {
                if (methods.containsKey(name)) return;
                s.other.put(name, value);
            }
        }
    }

    // ---------------------------------------------------------------- methods

    private interface Op {
        Object run(Object[] a);
    }

    private void method(Scriptable scope, String name, int arity, Op op) {
        methods.put(name, new LambdaFunction(scope, name, arity, (cx, sc, self, args) -> {
            Object r = op.run(args);
            return r == null ? Undefined.instance : r;
        }));
    }

    private void define(Scriptable scope) {
        method(scope, "save", 0, a -> {
            if (saved.size() < 512) saved.push(s.copy());
            return null;
        });
        method(scope, "restore", 0, a -> {
            if (!saved.isEmpty()) s = saved.pop();
            return null;
        });
        method(scope, "translate", 2, a -> {
            if (finite(a, 2)) s.tx.translate(n(a, 0), n(a, 1));
            return null;
        });
        method(scope, "scale", 2, a -> {
            if (finite(a, 2)) s.tx.scale(n(a, 0), n(a, 1));
            return null;
        });
        method(scope, "rotate", 1, a -> {
            if (finite(a, 1)) s.tx.rotate(n(a, 0));
            return null;
        });
        method(scope, "transform", 6, a -> {
            if (finite(a, 6)) s.tx.concatenate(new AffineTransform(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4), n(a, 5)));
            return null;
        });
        method(scope, "setTransform", 6, a -> {
            if (finite(a, 6)) s.tx = new AffineTransform(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4), n(a, 5));
            else if (a.length == 0) s.tx = new AffineTransform();
            return null;
        });
        method(scope, "resetTransform", 0, a -> {
            s.tx = new AffineTransform();
            return null;
        });
        method(scope, "setLineDash", 1, a -> {
            if (a.length > 0 && a[0] instanceof NativeArray arr) {
                float[] d = new float[(int) arr.getLength()];
                for (int i = 0; i < d.length; i++) {
                    double v = Context.toNumber(arr.get(i));
                    if (!(v >= 0) || !Double.isFinite(v)) return null;
                    d[i] = (float) v;
                }
                boolean any = false;
                for (float v : d) any |= v > 0;
                if (d.length % 2 == 1) {
                    float[] twice = Arrays.copyOf(d, d.length * 2);
                    System.arraycopy(d, 0, twice, d.length, d.length);
                    d = twice;
                }
                s.dash = any ? d : null;
            }
            return null;
        });
        method(scope, "getLineDash", 0, a -> {
            Context cx = Context.getCurrentContext();
            float[] d = s.dash == null ? new float[0] : s.dash;
            Object[] out = new Object[d.length];
            for (int i = 0; i < d.length; i++) out[i] = (double) d[i];
            return cx.newArray(getParentScope(), out);
        });

        method(scope, "clearRect", 4, a -> {
            if (!finite(a, 4)) return null;
            Graphics2D g = graphics();
            g.setComposite(AlphaComposite.Clear);
            g.fill(device().createTransformedShape(rect(a)));
            canvas.changed();
            return null;
        });
        method(scope, "fillRect", 4, a -> {
            if (finite(a, 4)) fillShape(device().createTransformedShape(rect(a)), s.fill);
            return null;
        });
        method(scope, "strokeRect", 4, a -> {
            if (finite(a, 4)) strokeShape(device().createTransformedShape(rect(a)), s.stroke);
            return null;
        });

        method(scope, "beginPath", 0, a -> {
            path = new Path2D.Double();
            return null;
        });
        method(scope, "closePath", 0, a -> {
            if (path.getCurrentPoint() != null) path.closePath();
            return null;
        });
        method(scope, "moveTo", 2, a -> {
            if (finite(a, 2)) {
                Point2D p = pt(n(a, 0), n(a, 1));
                path.moveTo(p.getX(), p.getY());
            }
            return null;
        });
        method(scope, "lineTo", 2, a -> {
            if (finite(a, 2)) lineTo(pt(n(a, 0), n(a, 1)));
            return null;
        });
        method(scope, "quadraticCurveTo", 4, a -> {
            if (!finite(a, 4)) return null;
            Point2D c = pt(n(a, 0), n(a, 1)), p = pt(n(a, 2), n(a, 3));
            if (path.getCurrentPoint() == null) path.moveTo(c.getX(), c.getY());
            path.quadTo(c.getX(), c.getY(), p.getX(), p.getY());
            return null;
        });
        method(scope, "bezierCurveTo", 6, a -> {
            if (!finite(a, 6)) return null;
            Point2D c1 = pt(n(a, 0), n(a, 1)), c2 = pt(n(a, 2), n(a, 3)), p = pt(n(a, 4), n(a, 5));
            if (path.getCurrentPoint() == null) path.moveTo(c1.getX(), c1.getY());
            path.curveTo(c1.getX(), c1.getY(), c2.getX(), c2.getY(), p.getX(), p.getY());
            return null;
        });
        method(scope, "rect", 4, a -> {
            if (!finite(a, 4)) return null;
            double x = n(a, 0), y = n(a, 1), w = n(a, 2), h = n(a, 3);
            Point2D p0 = pt(x, y);
            path.moveTo(p0.getX(), p0.getY());
            lineTo(pt(x + w, y));
            lineTo(pt(x + w, y + h));
            lineTo(pt(x, y + h));
            path.closePath();
            path.moveTo(p0.getX(), p0.getY());
            return null;
        });
        method(scope, "roundRect", 5, a -> {
            if (!finite(a, 4)) return null;
            double x = n(a, 0), y = n(a, 1), w = n(a, 2), h = n(a, 3);
            double r = 0;
            if (a.length > 4) r = a[4] instanceof NativeArray arr ? (arr.getLength() > 0 ? Context.toNumber(arr.get(0)) : 0) : Context.toNumber(a[4]);
            if (!Double.isFinite(r) || r < 0) r = 0;
            Rectangle2D box = rect(new Object[]{x, y, w, h});
            r = Math.min(r, Math.min(box.getWidth(), box.getHeight()) / 2);
            path.append(device().createTransformedShape(new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight(), r * 2, r * 2)), false);
            return null;
        });
        method(scope, "arc", 6, a -> {
            if (!finite(a, 5)) return null;
            double r = n(a, 2);
            if (r < 0) throw Context.reportRuntimeError("IndexSizeError: The radius provided (" + r + ") is negative.");
            arc(n(a, 0), n(a, 1), r, r, 0, n(a, 3), n(a, 4), a.length > 5 && Context.toBoolean(a[5]));
            return null;
        });
        method(scope, "ellipse", 8, a -> {
            if (!finite(a, 7)) return null;
            if (n(a, 2) < 0 || n(a, 3) < 0) throw Context.reportRuntimeError("IndexSizeError: The radius provided is negative.");
            arc(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4), n(a, 5), n(a, 6), a.length > 7 && Context.toBoolean(a[7]));
            return null;
        });
        method(scope, "arcTo", 5, a -> {
            if (finite(a, 5)) arcTo(n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4));
            return null;
        });
        method(scope, "fill", 1, a -> {
            Path2D.Double p = (Path2D.Double) path.clone();
            p.setWindingRule(a.length > 0 && "evenodd".equals(Context.toString(a[a.length - 1])) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            fillShape(p, s.fill);
            return null;
        });
        method(scope, "stroke", 0, a -> {
            strokeShape(path, s.stroke);
            return null;
        });
        method(scope, "clip", 1, a -> {
            Path2D.Double p = (Path2D.Double) path.clone();
            p.setWindingRule(a.length > 0 && "evenodd".equals(Context.toString(a[a.length - 1])) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            Area area = new Area(p);
            if (s.clip != null) area.intersect(s.clip);
            s.clip = area;
            return null;
        });
        method(scope, "isPointInPath", 2, a -> finite(a, 2) && path.contains(pt(n(a, 0), n(a, 1))));

        method(scope, "fillText", 4, a -> {
            if (a.length >= 3 && finite(new Object[]{a[1], a[2]}, 2)) fillShape(text(Context.toString(a[0]), n(a, 1), n(a, 2), maxWidth(a)), s.fill);
            return null;
        });
        method(scope, "strokeText", 4, a -> {
            if (a.length >= 3 && finite(new Object[]{a[1], a[2]}, 2)) strokeShape(text(Context.toString(a[0]), n(a, 1), n(a, 2), maxWidth(a)), s.stroke);
            return null;
        });
        method(scope, "measureText", 1, a -> {
            String t = a.length > 0 ? Context.toString(a[0]) : "";
            Font f = font();
            Rectangle2D b = f.getStringBounds(t, Fonts.FRC);
            LineMetrics lm = f.getLineMetrics(t, Fonts.FRC);
            Scriptable m = Context.getCurrentContext().newObject(getParentScope());
            ScriptableObject.putProperty(m, "width", b.getWidth());
            ScriptableObject.putProperty(m, "actualBoundingBoxLeft", 0.0);
            ScriptableObject.putProperty(m, "actualBoundingBoxRight", b.getWidth());
            ScriptableObject.putProperty(m, "actualBoundingBoxAscent", (double) lm.getAscent());
            ScriptableObject.putProperty(m, "actualBoundingBoxDescent", (double) lm.getDescent());
            ScriptableObject.putProperty(m, "fontBoundingBoxAscent", (double) lm.getAscent());
            ScriptableObject.putProperty(m, "fontBoundingBoxDescent", (double) lm.getDescent());
            return m;
        });

        method(scope, "drawImage", 9, a -> {
            drawImage(a);
            return null;
        });
        method(scope, "createLinearGradient", 4, a -> finite(a, 4) ? new Gradient(getParentScope(), false, new double[]{n(a, 0), n(a, 1), n(a, 2), n(a, 3)}) : null);
        method(scope, "createRadialGradient", 6, a -> finite(a, 6) ? new Gradient(getParentScope(), true, new double[]{n(a, 0), n(a, 1), n(a, 2), n(a, 3), n(a, 4), n(a, 5)}) : null);
    }

    // ---------------------------------------------------------------- drawing

    /** User space to the picture's pixels. */
    private AffineTransform device() {
        AffineTransform d = AffineTransform.getScaleInstance(canvas.scale, canvas.scale);
        d.concatenate(s.tx);
        return d;
    }

    private Point2D pt(double x, double y) {
        return device().transform(new Point2D.Double(x, y), null);
    }

    private void lineTo(Point2D p) {
        if (path.getCurrentPoint() == null) path.moveTo(p.getX(), p.getY());
        else path.lineTo(p.getX(), p.getY());
    }

    private void arc(double x, double y, double rx, double ry, double rotation, double start, double end, boolean ccw) {
        double sweep;
        double full = Math.PI * 2;
        if (!ccw && end - start >= full) sweep = full;
        else if (ccw && start - end >= full) sweep = -full;
        else if (!ccw) sweep = mod(end - start, full);
        else sweep = -mod(start - end, full);
        // Java2D measures angles anticlockwise on the screen, the canvas clockwise.
        Arc2D.Double arc = new Arc2D.Double(-rx, -ry, rx * 2, ry * 2, -Math.toDegrees(start), -Math.toDegrees(sweep), Arc2D.OPEN);
        AffineTransform t = device();
        t.translate(x, y);
        t.rotate(rotation);
        path.append(t.createTransformedShape(arc), true);
    }

    private static double mod(double a, double m) {
        double r = a % m;
        return r < 0 ? r + m : r;
    }

    /** The canvas's arcTo: a straight line towards (x1, y1), turning into (x2, y2) along a circle of radius [r]. */
    private void arcTo(double x1, double y1, double x2, double y2, double r) {
        Point2D cur = path.getCurrentPoint();
        if (cur == null) {
            Point2D p = pt(x1, y1);
            path.moveTo(p.getX(), p.getY());
            return;
        }
        Point2D p0;
        try {
            p0 = device().inverseTransform(cur, null);
        } catch (NoninvertibleTransformException e) {
            return;
        }
        double x0 = p0.getX(), y0 = p0.getY();
        double ax = x0 - x1, ay = y0 - y1, bx = x2 - x1, by = y2 - y1;
        double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
        double cross = ax * by - ay * bx;
        if (r == 0 || la == 0 || lb == 0 || Math.abs(cross) < 1e-9) {
            lineTo(pt(x1, y1));
            return;
        }
        double angle = Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / (la * lb))));
        double d = r / Math.tan(angle / 2);
        double tx0 = x1 + ax / la * d, ty0 = y1 + ay / la * d;
        double tx1 = x1 + bx / lb * d, ty1 = y1 + by / lb * d;
        // The centre is r along the bisector's normal from the first tangent point, on the inside of the turn.
        double nx = -ay / la, ny = ax / la;
        if (cross > 0) {
            nx = -nx;
            ny = -ny;
        }
        double cx = tx0 + nx * r, cy = ty0 + ny * r;
        double a0 = Math.atan2(ty0 - cy, tx0 - cx), a1 = Math.atan2(ty1 - cy, tx1 - cx);
        lineTo(pt(tx0, ty0));
        arc(cx, cy, r, r, 0, a0, a1, cross > 0);
    }

    private static Rectangle2D rect(Object[] a) {
        double x = n(a, 0), y = n(a, 1), w = n(a, 2), h = n(a, 3);
        if (w < 0) {
            x += w;
            w = -w;
        }
        if (h < 0) {
            y += h;
            h = -h;
        }
        return new Rectangle2D.Double(x, y, w, h);
    }

    private Graphics2D graphics() {
        Graphics2D g = canvas.graphics();
        g.setTransform(new AffineTransform());
        g.setClip(s.clip);
        g.setComposite(composite(s.composite, (float) s.alpha));
        return g;
    }

    private void fillShape(Shape device, Object style) {
        Graphics2D g = graphics();
        g.setPaint(paintFor(style));
        g.fill(device);
        canvas.changed();
    }

    /** Strokes [device] (in the picture's pixels) as a line drawn in the current user space. */
    private void strokeShape(Shape device, Object style) {
        AffineTransform d = device();
        Shape user;
        try {
            user = d.createInverse().createTransformedShape(device);
        } catch (NoninvertibleTransformException e) {
            return;
        }
        int cap = switch (s.lineCap) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
        int join = switch (s.lineJoin) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
        BasicStroke stroke = new BasicStroke((float) s.lineWidth, cap, join, (float) Math.max(1, s.miterLimit), s.dash, (float) s.dashOffset);
        fillShape(d.createTransformedShape(stroke.createStrokedShape(user)), style);
    }

    private Font font() {
        if (s.awtFont == null) s.awtFont = parseFont(s.font);
        if (s.awtFont == null) s.awtFont = fonts.font(List.of("sans-serif"), 400, false, 10);
        return s.awtFont;
    }

    /** The CSS font shorthand: [style] [weight] size[/line-height] family, family... */
    private Font parseFont(String v) {
        List<String> words = new ArrayList<>(Arrays.asList(v.trim().split("\\s+")));
        boolean italic = false;
        int weight = 400;
        double size = -1;
        int i = 0;
        for (; i < words.size(); i++) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            Matcher m = FONT_SIZE.matcher(w);
            if (m.matches()) {
                size = Double.parseDouble(m.group(1));
                switch (m.group(2)) {
                    case "pt" -> size *= 4.0 / 3;
                    case "em", "rem" -> size *= 16;
                    default -> {}
                }
                i++;
                break;
            }
            switch (w) {
                case "italic", "oblique" -> italic = true;
                case "bold", "bolder" -> weight = 700;
                case "lighter" -> weight = 300;
                case "normal", "small-caps" -> {}
                default -> {
                    if (w.matches("[1-9]00")) weight = Integer.parseInt(w);
                    else return null;
                }
            }
        }
        if (size <= 0 || i >= words.size()) return null;
        String rest = String.join(" ", words.subList(i, words.size()));
        List<String> families = new ArrayList<>();
        for (String f : Values.splitCommas(rest)) {
            f = f.trim();
            if (f.length() > 1 && (f.startsWith("\"") && f.endsWith("\"") || f.startsWith("'") && f.endsWith("'"))) f = f.substring(1, f.length() - 1);
            if (!f.isEmpty()) families.add(f);
        }
        if (families.isEmpty()) return null;
        return fonts.font(families, weight, italic, size);
    }

    private static double maxWidth(Object[] a) {
        return a.length > 3 && a[3] != Undefined.instance ? Context.toNumber(a[3]) : Double.NaN;
    }

    /** [t]'s outline at (x, y) in user space, aligned as set, in the picture's pixels. */
    private Shape text(String t, double x, double y, double maxWidth) {
        Font f = font();
        GlyphVector gv = f.createGlyphVector(Fonts.FRC, t);
        double w = gv.getLogicalBounds().getWidth();
        LineMetrics lm = f.getLineMetrics(t, Fonts.FRC);
        double squeeze = maxWidth > 0 && w > maxWidth ? maxWidth / w : 1;
        double dx = switch (s.textAlign) {
            case "center" -> -w * squeeze / 2;
            case "right", "end" -> -w * squeeze;
            default -> 0;
        };
        double dy = switch (s.textBaseline) {
            case "top" -> lm.getAscent();
            case "hanging" -> lm.getAscent() * 0.8;
            case "middle" -> (lm.getAscent() - lm.getDescent()) / 2;
            case "bottom", "ideographic" -> -lm.getDescent();
            default -> 0;
        };
        AffineTransform t2 = device();
        t2.translate(x + dx, y + dy);
        if (squeeze != 1) t2.scale(squeeze, 1);
        return t2.createTransformedShape(gv.getOutline());
    }

    private void drawImage(Object[] a) {
        if (a.length < 3) return;
        BufferedImage img = ScriptCanvas.imageOf(a[0]);
        if (img == null) return;
        double sx = 0, sy = 0, sw = img.getWidth(), sh = img.getHeight(), dx, dy, dw, dh;
        // A canvas drawn into another is in its own picture's pixels; its size to the script is in canvas units.
        double unit = ScriptCanvas.unitsOf(a[0]);
        if (a.length >= 9) {
            sx = n(a, 1) * unit;
            sy = n(a, 2) * unit;
            sw = n(a, 3) * unit;
            sh = n(a, 4) * unit;
            dx = n(a, 5);
            dy = n(a, 6);
            dw = n(a, 7);
            dh = n(a, 8);
        } else if (a.length >= 5) {
            dx = n(a, 1);
            dy = n(a, 2);
            dw = n(a, 3);
            dh = n(a, 4);
        } else {
            dx = n(a, 1);
            dy = n(a, 2);
            dw = img.getWidth() / unit;
            dh = img.getHeight() / unit;
        }
        if (!Double.isFinite(sx + sy + sw + sh + dx + dy + dw + dh) || sw == 0 || sh == 0 || dw == 0 || dh == 0) return;
        Graphics2D g = graphics();
        g.setTransform(device());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s.smoothing
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        AffineTransform place = new AffineTransform();
        place.translate(dx, dy);
        place.scale(dw / sw, dh / sh);
        place.translate(-sx, -sy);
        Shape oldClip = g.getClip();
        g.clip(new Rectangle2D.Double(dx, dy, dw, dh));
        g.drawImage(img, place, null);
        g.setClip(oldClip);
        g.setTransform(new AffineTransform());
        canvas.changed();
    }

    // ---------------------------------------------------------------- styles

    /** A colour or a gradient, or null for a value the canvas would ignore. */
    private static Object paint(Object value) {
        if (value instanceof Gradient gr) return gr;
        if (value instanceof CharSequence || value instanceof String) {
            Integer argb = Values.color(value.toString(), 0xFF000000);
            return argb == null ? null : new Color(argb, true);
        }
        return null;
    }

    /** [style] as a Java2D paint in the picture's pixels, gradients placed by the transform in force now. */
    private Paint paintFor(Object style) {
        if (style instanceof Gradient gr) return gr.paint(device());
        return (Paint) style;
    }

    private static Composite composite(String op, float alpha) {
        AlphaComposite c = switch (op) {
            case "source-over" -> AlphaComposite.SrcOver;
            case "copy" -> AlphaComposite.Src;
            case "source-in" -> AlphaComposite.SrcIn;
            case "source-out" -> AlphaComposite.SrcOut;
            case "source-atop" -> AlphaComposite.SrcAtop;
            case "destination-over" -> AlphaComposite.DstOver;
            case "destination-in" -> AlphaComposite.DstIn;
            case "destination-out" -> AlphaComposite.DstOut;
            case "destination-atop" -> AlphaComposite.DstAtop;
            case "xor" -> AlphaComposite.Xor;
            // No additive blend in Java2D: drawn over, which keeps a glow visible if not as bright.
            case "lighter" -> AlphaComposite.SrcOver;
            default -> null;
        };
        return c == null ? null : c.derive(alpha);
    }

    private static boolean finite(Object[] a, int count) {
        if (a.length < count) return false;
        for (int i = 0; i < count; i++) if (!Double.isFinite(Context.toNumber(a[i]))) return false;
        return true;
    }

    private static double n(Object[] a, int i) {
        return i < a.length ? Context.toNumber(a[i]) : Double.NaN;
    }

    /** A {@code CanvasGradient}: its geometry in the user space it is used in, and its stops. */
    static final class Gradient extends ScriptableObject {
        private final boolean radial;
        private final double[] geometry;
        private final List<float[]> stops = new ArrayList<>();
        private final List<Color> colours = new ArrayList<>();

        Gradient(Scriptable scope, boolean radial, double[] geometry) {
            this.radial = radial;
            this.geometry = geometry;
            setParentScope(scope);
            setPrototype(ScriptableObject.getObjectPrototype(scope));
            defineProperty("addColorStop", new LambdaFunction(scope, "addColorStop", 2, (cx, sc, self, args) -> {
                double at = args.length > 0 ? Context.toNumber(args[0]) : Double.NaN;
                Integer argb = args.length > 1 ? Values.color(Context.toString(args[1]), 0xFF000000) : null;
                if (!(at >= 0 && at <= 1)) throw Context.reportRuntimeError("IndexSizeError: The provided value (" + at + ") is outside the range (0.0, 1.0).");
                if (argb == null) throw Context.reportRuntimeError("SyntaxError: The value provided could not be parsed as a color.");
                int i = 0;
                while (i < stops.size() && stops.get(i)[0] <= at) i++;
                stops.add(i, new float[]{(float) at});
                colours.add(i, new Color(argb, true));
                return Undefined.instance;
            }), READONLY);
        }

        @Override
        public String getClassName() {
            return "CanvasGradient";
        }

        Paint paint(AffineTransform device) {
            if (stops.isEmpty()) return new Color(0, true);
            if (stops.size() == 1) return colours.get(0);
            // Java wants strictly increasing stops: equal ones are nudged apart.
            float[] f = new float[stops.size()];
            float last = -1;
            for (int i = 0; i < f.length; i++) {
                float v = stops.get(i)[0];
                if (v <= last) v = Math.min(1, last + 1e-5f);
                f[i] = last = v;
            }
            if (f[f.length - 1] > 1) return colours.get(colours.size() - 1);
            Color[] c = colours.toArray(new Color[0]);
            try {
                if (!radial) {
                    Point2D a = new Point2D.Double(geometry[0], geometry[1]), b = new Point2D.Double(geometry[2], geometry[3]);
                    if (a.equals(b)) return new Color(0, true);
                    return new java.awt.LinearGradientPaint(a, b, f, c, MultipleGradientPaint.CycleMethod.NO_CYCLE,
                            MultipleGradientPaint.ColorSpaceType.SRGB, device);
                }
                double r = geometry[5];
                if (r <= 0) return new Color(0, true);
                // Java's radial gradient has one circle and a focus: the end circle, focused on the start circle's centre.
                return new java.awt.RadialGradientPaint(new Point2D.Double(geometry[3], geometry[4]), (float) r,
                        new Point2D.Double(geometry[0], geometry[1]), f, c, MultipleGradientPaint.CycleMethod.NO_CYCLE,
                        MultipleGradientPaint.ColorSpaceType.SRGB, device);
            } catch (IllegalArgumentException e) {
                return colours.get(colours.size() - 1);
            }
        }
    }
}

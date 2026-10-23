package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.style.Style;
import org.junit.jupiter.api.Test;

import java.awt.PaintContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A conic gradient's angle comes from a polynomial where one colour holds either way, from atan2 elsewhere: never a different pixel. */
class ConicGradientTest {
    private static final Style STYLE = Style.compute(Map.of(), null, 16, 1024, 576);

    @Test
    void everyPixelIsTheColourAtan2Gives() throws Exception {
        String[] gradients = {
                // The market's rays: hard stops every few degrees.
                "conic-gradient(from 0deg at 50% 55%, rgba(255,214,140,.16) 0deg 9deg, transparent 9deg 30deg, rgba(255,214,140,.16) 30deg 39deg,"
                        + " transparent 39deg 60deg, rgba(255,214,140,.16) 60deg 69deg, transparent 69deg 90deg, rgba(255,214,140,.16) 90deg 99deg,"
                        + " transparent 99deg 120deg, rgba(255,214,140,.16) 120deg 129deg, transparent 129deg 360deg)",
                // A checkerboard about a corner, its centre on a pixel's centre at 2x.
                "conic-gradient(from 45deg at 10.25px 10.25px, #000 0 25%, #fff 0 50%, #000 0 75%, #fff 0)",
                // Soft stops only: worked out from atan2 everywhere but in the stretches of one colour.
                "conic-gradient(red, blue 30%, blue 60%, red)",
                "conic-gradient(from -33deg at 0 100%, #123 0deg 1deg, #456 1deg 2deg, #123 2deg 3deg, #456 3deg 90deg)",
        };
        AffineTransform[] views = {
                AffineTransform.getScaleInstance(2, 2),
                new AffineTransform(2, 0, 0, 2, -37, 211),
                rotated(),
        };
        int checked = 0;
        for (String layer : gradients) {
            for (AffineTransform view : views) {
                Rectangle2D box = new Rectangle2D.Double(0, 0, 300, 200);
                var paint = assertInstanceOf(Backgrounds.ConicPaint.class, Backgrounds.gradientPaint(layer, STYLE, box));
                java.awt.Rectangle device = view.createTransformedShape(box).getBounds();
                PaintContext ctx = paint.createContext(null, device, box, view, null);
                AffineTransform toUser = view.createInverse();
                // In tiles, as Java2D asks for them.
                for (int ty = device.y; ty < device.y + device.height; ty += 32) {
                    for (int tx = device.x; tx < device.x + device.width; tx += 32) {
                        Raster r = ctx.getRaster(tx, ty, 32, 32);
                        int[] data = ((DataBufferInt) r.getDataBuffer()).getData();
                        int stride = ((SinglePixelPackedSampleModel) r.getSampleModel()).getScanlineStride();
                        for (int j = 0; j < 32; j++) {
                            for (int i = 0; i < 32; i++) {
                                double[] p = {tx + i + 0.5, ty + j + 0.5};
                                toUser.transform(p, 0, p, 0, 1);
                                assertEquals(paint.exactAt(p[0], p[1]), data[j * stride + i], layer + " at device (" + (tx + i) + ", " + (ty + j) + ")");
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(true, checked > 1_000_000);
    }

    private static AffineTransform rotated() {
        AffineTransform t = AffineTransform.getScaleInstance(2, 2);
        t.rotate(Math.toRadians(17), 150, 100);
        return t;
    }
}

package ai.resourcepack.arcade.scene;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelsTest {
    /** A picture with smooth gradients, hard edges and lone bright pixels, like a glowing vector game's. */
    private static BufferedImage picture(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x102040), w, h, new Color(0xF0A020)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.CYAN);
        g.fillRect(w / 4, h / 3, w / 3, 3);
        Random r = new Random(5);
        for (int i = 0; i < 200; i++) image.setRGB(r.nextInt(w), r.nextInt(h), r.nextInt(0xFFFFFF));
        g.dispose();
        return image;
    }

    /** The largest difference in any channel between Java2D's bilinear stretch and {@link Pixels#smooth}. */
    private static int worst(int sw, int sh, int dw, int dh) {
        BufferedImage from = picture(sw, sh);
        BufferedImage java2d = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = java2d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(from, 0, 0, dw, dh, null);
        g.dispose();
        BufferedImage ours = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        Pixels.smooth(from, ours);
        int worst = 0;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int a = java2d.getRGB(x, y), b = ours.getRGB(x, y);
                for (int s = 0; s < 24; s += 8) worst = Math.max(worst, Math.abs((a >> s & 255) - (b >> s & 255)));
            }
        }
        return worst;
    }

    @Test
    void stretchesAsJava2dDoes() {
        // The three the games use: Pong 4x, Light Cycles 2x, Phantom Field 2.13x, all to a 16x9 canvas.
        for (int[] s : new int[][]{{512, 288}, {1024, 576}, {960, 540}}) {
            int w = worst(s[0], s[1], 2048, 1152);
            assertTrue(w <= 2, s[0] + "x" + s[1] + ": a channel off by " + w);
        }
        assertTrue(worst(37, 23, 300, 170) <= 2);
    }
}

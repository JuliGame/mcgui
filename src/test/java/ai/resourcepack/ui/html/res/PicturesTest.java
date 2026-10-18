package ai.resourcepack.ui.html.res;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicturesTest {
    private static byte[] png(int w, int h) throws IOException {
        // One bit per pixel, so a big picture stays small to make.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return out.toByteArray();
    }

    @Test
    void bigBitmapsAreHeldSubsampledAtTheirStatedSize() throws IOException {
        Picture p = Pictures.decode(png(6000, 4000), "big.png");
        // Laid out and cropped at the size the file states...
        assertEquals(6000, p.width());
        assertEquals(4000, p.height());
        // ...but held at fewer pixels.
        BufferedImage held = ((Pictures.Raster) p).img();
        assertTrue((long) held.getWidth() * held.getHeight() <= Pictures.MAX_PIXELS, held.getWidth() + " x " + held.getHeight());
        assertEquals(1.5, (double) held.getWidth() / held.getHeight(), 0.01);
    }

    @Test
    void ordinaryBitmapsAreReadWhole() throws IOException {
        Picture p = Pictures.decode(png(300, 200), "small.png");
        assertEquals(300, p.width());
        assertEquals(200, p.height());
    }
}

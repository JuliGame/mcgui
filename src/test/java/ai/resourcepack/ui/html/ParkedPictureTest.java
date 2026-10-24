package ai.resourcepack.ui.html;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A parked picture puts back every pixel as it was, and shares a block with any other parked picture holding the same pixels there. */
class ParkedPictureTest {
    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static BufferedImage noise(Random r, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] px = pixels(img);
        for (int i = 0; i < px.length; i++) px[i] = r.nextInt();
        return img;
    }

    @Test
    void everyPixelComesBackOverWhateverThePictureHeld() {
        Random r = new Random(5);
        // Not a whole number of blocks either way: the edge blocks are smaller.
        BufferedImage img = noise(r, 300, 200);
        int[] was = pixels(img).clone();
        ParkedPicture p = ParkedPicture.of(img);
        BufferedImage into = noise(r, 300, 200);
        p.unpark(into);
        assertArrayEquals(was, pixels(into));
    }

    @Test
    void picturesAlikeButInOnePlaceShareEveryOtherBlock() {
        Random r = new Random(9);
        BufferedImage a = noise(r, 512, 384), b = new BufferedImage(512, 384, BufferedImage.TYPE_INT_ARGB_PRE);
        System.arraycopy(pixels(a), 0, pixels(b), 0, pixels(a).length);
        // One pixel in block (2, 1) differs.
        pixels(b)[(128 + 5) * 512 + 256 + 7] ^= 1;
        ParkedPicture pa = ParkedPicture.of(a), pb = ParkedPicture.of(b);
        assertEquals(4 * 3 - 1, pa.sameBlocks(pb));
        BufferedImage back = new BufferedImage(512, 384, BufferedImage.TYPE_INT_ARGB_PRE);
        pb.unpark(back);
        assertArrayEquals(pixels(b), pixels(back));
    }

    @Test
    void onlyAPictureOfItsOwnPixelsParks() {
        BufferedImage whole = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB_PRE);
        assertTrue(ParkedPicture.parks(whole));
        assertFalse(ParkedPicture.parks(whole.getSubimage(0, 0, 128, 128)), "part of a bigger picture");
        assertFalse(ParkedPicture.parks(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)), "not premultiplied");
    }
}

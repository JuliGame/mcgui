package ai.resourcepack.ui.html.res;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnimatedPictureTest {
    private static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF;

    /** One frame to write: its picture, where it goes, how long it shows and how it is cleared. */
    record Frame(BufferedImage image, int left, int top, int hundredths, String disposal) {}

    static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(argb));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** A GIF of [frames], looping [loop] (null: no looping extension at all, 0: for ever). */
    static byte[] gif(Integer loop, Frame... frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            String format = "javax_imageio_gif_image_1.0";
            for (int i = 0; i < frames.length; i++) {
                Frame f = frames[i];
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(f.image), null);
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", f.disposal);
                control.setAttribute("delayTime", Integer.toString(f.hundredths));
                IIOMetadataNode place = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
                place.setAttribute("imageLeftPosition", Integer.toString(f.left));
                place.setAttribute("imageTopPosition", Integer.toString(f.top));
                if (i == 0 && loop != null) {
                    IIOMetadataNode list = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, (byte) (loop & 0xFF), (byte) (loop >> 8 & 0xFF)});
                    list.appendChild(app);
                    root.appendChild(list);
                }
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(f.image, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Solid frames of [colours], [hundredths] each, looping for ever. */
    public static byte[] loop(int w, int h, int hundredths, int... colours) throws IOException {
        Frame[] frames = new Frame[colours.length];
        for (int i = 0; i < frames.length; i++) frames[i] = new Frame(solid(w, h, colours[i]), 0, 0, hundredths, "none");
        return gif(0, frames);
    }

    private static AnimatedPicture read(byte[] data) {
        return assertInstanceOf(AnimatedPicture.class, Pictures.decode(data, "a.gif"));
    }

    private static int colour(AnimatedPicture p, double seconds, int x, int y) {
        return p.image(seconds).getRGB(x, y);
    }

    @Test
    void eachFrameShowsForItsOwnDelayAndTheLoopComesRound() throws IOException {
        AnimatedPicture p = read(gif(0, new Frame(solid(8, 8, RED), 0, 0, 10, "none"), new Frame(solid(8, 8, GREEN), 0, 0, 30, "none")));
        assertTrue(p.forever());
        assertEquals(8, p.width());
        assertEquals(RED, colour(p, 0, 4, 4));
        assertEquals(RED, colour(p, 0.09, 4, 4));
        assertEquals(GREEN, colour(p, 0.1, 4, 4), "a frame's end is the next one's start");
        assertEquals(GREEN, colour(p, 0.39, 4, 4));
        assertEquals(RED, colour(p, 0.4, 4, 4), "round again");
        assertEquals(GREEN, colour(p, 4000.15, 4, 4), "and still in step much later");
    }

    @Test
    void aGifWithNoLoopPlaysOnceAndStaysOnItsLastFrame() throws IOException {
        AnimatedPicture p = read(gif(null, new Frame(solid(8, 8, RED), 0, 0, 10, "none"), new Frame(solid(8, 8, GREEN), 0, 0, 10, "none")));
        assertFalse(p.forever());
        assertTrue(p.playing(0.19));
        assertFalse(p.playing(0.2));
        assertEquals(GREEN, colour(p, 60, 4, 4));
    }

    @Test
    void aLoopCountIsRepeatsAfterTheFirstPlay() throws IOException {
        AnimatedPicture p = read(gif(1, new Frame(solid(8, 8, RED), 0, 0, 10, "none"), new Frame(solid(8, 8, GREEN), 0, 0, 10, "none")));
        assertTrue(p.playing(0.39), "two plays of 0.2 s");
        assertFalse(p.playing(0.4));
    }

    @Test
    void aDelayOfNothingIsATenthOfASecond() throws IOException {
        AnimatedPicture p = read(gif(0, new Frame(solid(8, 8, RED), 0, 0, 0, "none"), new Frame(solid(8, 8, GREEN), 0, 0, 1, "none")));
        assertEquals(RED, colour(p, 0.09, 4, 4));
        assertEquals(GREEN, colour(p, 0.1, 4, 4));
    }

    @Test
    void aPartFrameIsDrawnOverWhatIsThere() throws IOException {
        AnimatedPicture p = read(gif(0, new Frame(solid(8, 8, RED), 0, 0, 10, "none"), new Frame(solid(2, 2, GREEN), 3, 3, 10, "none")));
        assertEquals(GREEN, colour(p, 0.1, 3, 3));
        assertEquals(RED, colour(p, 0.1, 0, 0), "the first frame stays round it");
    }

    @Test
    void aFrameRestoredToBackgroundIsGoneFromTheNext() throws IOException {
        AnimatedPicture p = read(gif(0, new Frame(solid(8, 8, RED), 0, 0, 10, "restoreToBackgroundColor"), new Frame(solid(2, 2, GREEN), 3, 3, 10, "none")));
        assertEquals(RED, colour(p, 0, 0, 0));
        assertEquals(0, colour(p, 0.1, 0, 0) >>> 24, "cleared: nothing shows through where the second frame does not reach");
        assertEquals(GREEN, colour(p, 0.1, 3, 3));
    }

    @Test
    void aFrameRestoredToPreviousIsGoneFromTheNext() throws IOException {
        AnimatedPicture p = read(gif(0, new Frame(solid(8, 8, RED), 0, 0, 10, "none"), new Frame(solid(2, 2, GREEN), 3, 3, 10, "restoreToPrevious"),
                new Frame(solid(2, 2, BLUE), 0, 0, 10, "none")));
        assertEquals(GREEN, colour(p, 0.1, 3, 3));
        assertEquals(RED, colour(p, 0.2, 3, 3), "the green frame was only for its own turn");
        assertEquals(BLUE, colour(p, 0.2, 0, 0));
    }

    @Test
    void oneFrameIsAnOrdinaryPicture() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(solid(8, 8, RED), "gif", out);
        assertInstanceOf(Pictures.Raster.class, Pictures.decode(out.toByteArray(), "still.gif"));
        assertEquals(0, Pictures.animatedBytes(out.toByteArray()));
    }

    @Test
    void stillAsksForTheFirstFrameOnly() throws IOException {
        assertInstanceOf(Pictures.Raster.class, Pictures.still(loop(8, 8, 10, RED, GREEN), "a.gif"));
    }

    @Test
    void framesPastTheMemoryLimitAreDroppedAndTheTimeKept() throws IOException {
        int side = 2000, frames = 8;
        long oneFrame = 4L * side * side;
        long fits = AnimatedPicture.MAX_BYTES / oneFrame;
        assertTrue(fits < frames, "the test is of more frames than fit");
        int[] colours = new int[frames];
        // Red, red, green, green...: every second frame is dropped, and each kept one lasts as long as both.
        for (int i = 0; i < frames; i++) colours[i] = i / 2 % 2 == 0 ? RED : GREEN;
        byte[] data = loop(side, side, 10, colours);
        assertEquals(fits * oneFrame, Pictures.animatedBytes(data), "weighed as what is held");
        AnimatedPicture p = read(data);
        assertEquals(RED, colour(p, 0.19, 0, 0));
        assertEquals(GREEN, colour(p, 0.2, 0, 0));
        assertEquals(RED, colour(p, 0.4, 0, 0));
        assertEquals(RED, colour(p, 0.8, 0, 0), "a play is still eight tenths of a second");
    }

    @Test
    void aGifTooBigToHoldTwoFramesOfIsAStill() throws IOException {
        // One frame of this is 36 MB: only one fits under the limit.
        assertInstanceOf(Pictures.Raster.class, Pictures.decode(loop(3000, 3000, 10, RED, GREEN), "big.gif"));
    }
}

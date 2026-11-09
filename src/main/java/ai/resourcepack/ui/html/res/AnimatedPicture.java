package ai.resourcepack.ui.html.res;

import ai.resourcepack.ui.html.style.Motion;
import org.w3c.dom.Node;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.Arrays;

/**
 * A GIF with more than one frame, every frame held whole. Which one is drawn depends only on the time, so nothing is
 * stored per viewer: drawing asks the motion of the picture being drawn for the moment ({@link Motion#pictureAt}), and
 * a script's {@code drawImage} asks {@link #image} with its own clock. Drawn with no motion (a test, a sample), it is
 * its first frame.
 *
 * <p>A GIF that loops for ever runs on the page's clock, so every viewer of a page sees the same frame and one picture
 * can serve them all; one that ends runs on its viewer's, and then stays on its last frame, as in a browser.
 */
public final class AnimatedPicture implements Picture {
    /** A GIF holds at most this many bytes decoded; past it, frames are dropped evenly and the rest shown for longer. */
    static final long MAX_BYTES = 64L << 20;
    static final int MAX_FRAMES = 1024;
    /** A delay of this many hundredths of a second or less means 10, as every browser reads it. */
    private static final int SHORTEST_DELAY = 1, DEFAULT_DELAY = 10;

    private final Pictures.Raster[] frames;
    /** Seconds into one play at which each frame ends. */
    private final double[] endsAt;
    private final double length;
    /** Times it plays; 0 for ever. */
    private final int plays;
    private final int width, height;

    private AnimatedPicture(Pictures.Raster[] frames, double[] endsAt, int plays, int width, int height) {
        this.frames = frames;
        this.endsAt = endsAt;
        this.length = endsAt[endsAt.length - 1];
        this.plays = plays;
        this.width = width;
        this.height = height;
    }

    @Override
    public double width() {
        return width;
    }

    @Override
    public double height() {
        return height;
    }

    /** Whether it loops for ever, and so runs on the page's clock rather than its viewer's. */
    public boolean forever() {
        return plays == 0;
    }

    /** Whether it is still changing [seconds] after it began. */
    public boolean playing(double seconds) {
        return forever() || seconds < plays * length;
    }

    /** The frame [seconds] after it began. */
    public BufferedImage image(double seconds) {
        return frame(seconds).img();
    }

    @Override
    public void draw(Graphics2D g, double x, double y, double w, double h) {
        Motion m = Motion.current();
        frame(m == null ? 0 : m.pictureAt(forever())).draw(g, x, y, w, h);
    }

    private Pictures.Raster frame(double seconds) {
        if (seconds <= 0) return frames[0];
        if (!playing(seconds)) return frames[frames.length - 1];
        double into = seconds % length;
        int at = Arrays.binarySearch(endsAt, into);
        // An exact end is the start of the next frame; else the search says which frame's end comes next.
        at = at >= 0 ? at + 1 : -at - 1;
        return frames[Math.min(at, frames.length - 1)];
    }

    // ---------------------------------------------------------------- reading

    static boolean isGif(byte[] data) {
        return data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8';
    }

    /** How much of a GIF is kept: its size, its frames in the file, and how many of them can be held. */
    private record Plan(int width, int height, int frames, int kept) {
        long bytes() {
            return kept * (long) width * height * 4;
        }
    }

    /** What [reader] holds, or null for a GIF that is better as a still: one frame, or one too big to hold two of. */
    private static Plan plan(ImageReader reader) throws IOException {
        int n = reader.getNumImages(true);
        if (n < 2) return null;
        Node screen = child(reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
        int w = number(screen, "logicalScreenWidth", 0), h = number(screen, "logicalScreenHeight", 0);
        if (w <= 0 || h <= 0) {
            w = reader.getWidth(0);
            h = reader.getHeight(0);
        }
        if (w <= 0 || h <= 0 || (long) w * h > Pictures.MAX_PIXELS) return null;
        long fits = MAX_BYTES / ((long) w * h * 4);
        int kept = (int) Math.min(Math.min(n, MAX_FRAMES), fits);
        return kept < 2 ? null : new Plan(w, h, n, kept);
    }

    private static ImageReader reader(ImageInputStream in) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        reader.setInput(in, false, false);
        return reader;
    }

    /** About how many bytes {@link #read} holds for [data], from its headers alone; 0 for a GIF that is a still. */
    static long weigh(byte[] data) {
        try (ImageInputStream in = Pictures.memory(data)) {
            ImageReader reader = reader(in);
            try {
                Plan plan = plan(reader);
                return plan == null ? 0 : plan.bytes();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** The GIF in [data], or null for one to read as a still (see {@link #plan}, or a file Java cannot read frames of). */
    static AnimatedPicture read(byte[] data) {
        try (ImageInputStream in = Pictures.memory(data)) {
            ImageReader reader = reader(in);
            try {
                Plan plan = plan(reader);
                return plan == null ? null : frames(reader, plan);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static AnimatedPicture frames(ImageReader reader, Plan plan) throws IOException {
        // Opaque or nothing, so these are the same pixels premultiplied, which Java2D draws fastest.
        BufferedImage screen = new BufferedImage(plan.width, plan.height, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();
        Pictures.Raster[] kept = new Pictures.Raster[plan.kept];
        double[] seconds = new double[plan.kept];
        int plays = 1, filled = 0;
        Graphics2D g = screen.createGraphics();
        try {
            for (int i = 0; i < plan.frames; i++) {
                Node meta;
                BufferedImage part;
                try {
                    meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                    part = reader.read(i);
                } catch (IIOException | RuntimeException e) {
                    // A damaged frame ends it, as in a browser: what came before it still plays.
                    break;
                }
                if (i == 0) plays = plays(meta);
                Node control = child(meta, "GraphicControlExtension");
                Node place = child(meta, "ImageDescriptor");
                int delay = number(control, "delayTime", 0);
                String disposal = control == null ? "none" : attribute(control, "disposalMethod");
                int left = number(place, "imageLeftPosition", 0), top = number(place, "imageTopPosition", 0);

                int[] before = "restoreToPrevious".equals(disposal) ? pixels.clone() : null;
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(part, left, top, null);

                // Frames past what can be held are dropped evenly: each one kept also lasts as long as those it stands for.
                int slot = (int) ((long) i * plan.kept / plan.frames);
                if (slot == filled) {
                    kept[slot] = new Pictures.Raster(copy(screen), plan.width, plan.height);
                    filled++;
                }
                seconds[slot] += (delay <= SHORTEST_DELAY ? DEFAULT_DELAY : delay) / 100.0;

                if (before != null) {
                    System.arraycopy(before, 0, pixels, 0, pixels.length);
                } else if ("restoreToBackgroundColor".equals(disposal)) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(left, top, part.getWidth(), part.getHeight());
                }
            }
        } finally {
            g.dispose();
        }
        if (filled < 2) return null;
        kept = Arrays.copyOf(kept, filled);
        double[] endsAt = new double[filled];
        double sum = 0;
        for (int i = 0; i < filled; i++) endsAt[i] = sum += seconds[i];
        return new AnimatedPicture(kept, endsAt, plays, plan.width, plan.height);
    }

    private static BufferedImage copy(BufferedImage from) {
        BufferedImage to = new BufferedImage(from.getWidth(), from.getHeight(), from.getType());
        int[] in = ((DataBufferInt) from.getRaster().getDataBuffer()).getData();
        System.arraycopy(in, 0, ((DataBufferInt) to.getRaster().getDataBuffer()).getData(), 0, in.length);
        return to;
    }

    /** Times a GIF plays, from its first frame's looping extension: 0 for ever; once if it has none. */
    private static int plays(Node first) {
        Node list = child(first, "ApplicationExtensions");
        if (list == null) return 1;
        for (Node n = list.getFirstChild(); n != null; n = n.getNextSibling()) {
            String id = attribute(n, "applicationID");
            if (!"NETSCAPE".equals(id) && !"ANIMEXTS".equals(id)) continue;
            if (n instanceof IIOMetadataNode node && node.getUserObject() instanceof byte[] b && b.length >= 3 && b[0] == 1) {
                int count = (b[1] & 0xFF) | (b[2] & 0xFF) << 8;
                // The number of times it REPEATS: a browser plays it once more than that.
                return count == 0 ? 0 : count + 1;
            }
        }
        return 1;
    }

    private static Node child(Node parent, String name) {
        for (Node n = parent == null ? null : parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) return n;
        }
        return null;
    }

    private static String attribute(Node node, String name) {
        Node a = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static int number(Node node, String name, int otherwise) {
        try {
            String v = attribute(node, name);
            return v == null ? otherwise : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}

package ai.resourcepack.ui.framework.api;

import java.util.function.BooleanSupplier;

/**
 * Pictures a view's client switches to by itself, the frame its pointer goes over part of the view, without waiting for
 * the server to hear where the pointer is (the client reports it 20 times a second, so that wait is 0 to 50 ms, and then
 * the new picture has to be drawn and sent). Each layer is pixels of part of the view and a rectangle of the view: while
 * the pointer, which the client works out every frame from where its camera looks, is inside the rectangle (and outside
 * a second one, if given), the layer shows in place of the view's own picture under it.
 *
 * <p>A view has them only for a player with the resource pack whose client places the pointer, and only with
 * {@code views.hover-layers} above 0 ({@link View#layers()}). Each layer takes one hidden map for every 128 × 126 part
 * of the canvas where its pixels are opaque ({@link #mapsFor}). A map a layer holds costs the client a draw call every
 * frame, shown or not (about 9 µs on a desktop GPU); a free one costs nothing, as it has no frame on the client.
 *
 * <p>A layer is worked out once ({@link #prepare}, the heavy part: its maps' pictures, encoded) and armed in any view of
 * the same size, by as many views as show the same picture: arming one only marks its maps with where it shows. Layers
 * are of the picture the view shows when they are armed: whoever draws the view disarms them (or clears them all) when
 * that picture changes where they cover it, in the same moment, or a stale layer would show. Every method is safe from
 * any thread.
 */
public interface HoverLayers {
    /** Priorities run from 0 to one less than this. */
    int PRIORITIES = ai.resourcepack.ui.framework.render.Palette.LAYER_PRIORITIES;

    /** A layer worked out by {@link #prepare}: its maps' pictures, for any view of the size it was worked out for. */
    interface Layer {
        /** The maps it takes when armed. */
        int maps();
    }

    /** Maps the layers can take altogether. */
    int capacity();

    /**
     * Maps a layer of [argb] ([w] × [h], its first pixel at canvas (x, y)) would take, as {@link #prepare} lays it out:
     * cheap, nothing is encoded. One that needs more than half of {@link #capacity} is never armed: it would leave room
     * for no other.
     */
    int mapsFor(int[] argb, int x, int y, int w, int h);

    /**
     * The layer of [argb], [w] × [h] pixels, row by row, whose first is canvas pixel (x, y), ready to {@link #arm} in this
     * view or any other of its size. Where its pixels are less than half opaque, and outside them, the view's own show:
     * leave transparent what the layer does not change, and it needs fewer maps. Give x, y, w and h even: the picture is
     * coloured in 2 × 2 blocks, and a block half outside the pixels takes its colour from the half inside. Most of the
     * work of a layer, a millisecond or two a map: off the server thread. [stop] is asked between maps; null once it
     * says to stop. A layer whose pixels were worked out before (by any view) is found again, not encoded twice.
     */
    Layer prepare(int[] argb, int x, int y, int w, int h, BooleanSupplier stop);

    /** Maps the layers take now. */
    int used();

    /**
     * Arms [layer] as the layer [key] (in place of any layer of that key): it shows in place of the view's own while the
     * client's pointer is inside [include] and outside [exclude] (each {left, top, right, bottom} in canvas pixels, right
     * and bottom not included; [exclude] null for none). Where two layers show at once, the one of higher [priority] (0
     * to {@link #PRIORITIES} - 1) is in front. Cheap: its maps are marked, not encoded, and a map the client still holds
     * from the same layer is sent only its marks. False, and nothing armed, if the layer needs more maps than are free or
     * than half of {@link #capacity}, or has nothing opaque; then the layer of that key, if any, is disarmed.
     */
    boolean arm(Object key, int[] include, int[] exclude, int priority, Layer layer);

    /** Whether the layer [key] is armed. */
    boolean armed(Object key);

    /** The layer [key] no longer shows, and its maps are free. Nothing if it is not armed. */
    void disarm(Object key);

    /** Disarms every layer. */
    void clear();
}

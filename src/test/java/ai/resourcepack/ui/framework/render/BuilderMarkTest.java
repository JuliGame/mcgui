package ai.resourcepack.ui.framework.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderMarkTest {
    /**
     * The first six lit blocks of four rows, as mcgui.dev's reader computes them (export-mark.ts there): the hash is
     * a contract with it, and a block lit here and not there is a vote against the tag.
     */
    @Test
    void litBlocksMatchTheReader() {
        String expected = "50,1 63,1 65,1 69,1 82,1 103,1 11,7 24,7 33,7 38,7 51,7 68,7 9,300 13,300 16,300 39,300 84,300 106,300 "
                + "52,575 58,575 88,575 111,575 130,575 135,575";
        List<String> lit = new ArrayList<>();
        for (int by : new int[]{1, 7, 300, 575}) {
            for (int bx = 1, n = 0; bx < 1023 && n < 6; bx++) {
                if (BuilderMark.lit(bx, by)) {
                    lit.add(bx + "," + by);
                    n++;
                }
            }
        }
        assertEquals(expected, String.join(" ", lit));
    }

    /** On a flat picture every lit block goes up exactly one Cb level, nothing else changes, and its tile is flagged. */
    @Test
    void raisesLitBlocksOneLevel() {
        Palette palette = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
        int w = 256, h = 256;
        byte[] ycc = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) Palette.rgbToYcc(0x2b3a55, ycc, i * 3);
        byte[] before = ycc.clone();
        boolean[] dirty = new boolean[4];
        BuilderMark.apply(palette, ycc, w, h, 2, 0, 0, w, h, dirty);
        int raised = 0;
        for (int by = 0; by < h / 2; by++) {
            for (int bx = 0; bx < w / 2; bx++) {
                int sumBefore = 0, sumAfter = 0;
                for (int i = 0; i < 4; i++) {
                    int p = ((2 * by + (i >> 1)) * w + 2 * bx + (i & 1)) * 3;
                    assertEquals(before[p], ycc[p]);
                    assertEquals(before[p + 2], ycc[p + 2]);
                    sumBefore += before[p + 1] & 255;
                    sumAfter += ycc[p + 1] & 255;
                }
                int step = palette.chromaLevelOf(sumAfter) - palette.chromaLevelOf(sumBefore);
                boolean edge = bx == 0 || by == 0 || bx >= w / 2 - 1 || by >= h / 2 - 1 || (bx & 63) == 0 && (by & 63) == 0;
                assertEquals(BuilderMark.lit(bx, by) && !edge ? 1 : 0, step, "block " + bx + "," + by);
                raised += step;
            }
        }
        assertTrue(raised > 400, "raised " + raised);
        for (boolean d : dirty) assertTrue(d);
    }
}

package ai.resourcepack.uiframework.net;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The rectangle the sender sends is exactly the one covering every byte that changed. */
class MapSenderDiffTest {
    /** Byte by byte, as plainly as it can be said. */
    private static int[] covering(byte[] a, byte[] b) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                if (a[y * 128 + x] == b[y * 128 + x]) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    @Test
    void coversExactlyWhatChanged() {
        Random r = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            byte[] a = new byte[128 * 128];
            r.nextBytes(a);
            byte[] b = a.clone();
            int changes = switch (i % 4) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 1 + r.nextInt(5);
                default -> r.nextInt(2000);
            };
            for (int c = 0; c < changes; c++) {
                // Often at the edges and in the last bytes of a row, where a scan from either end turns.
                int x = r.nextInt(4) == 0 ? (r.nextBoolean() ? 0 : 127) : r.nextInt(128);
                int y = r.nextInt(4) == 0 ? (r.nextBoolean() ? 0 : 127) : r.nextInt(128);
                b[y * 128 + x] ^= (byte) (1 + r.nextInt(255));
            }
            assertArrayEquals(covering(a, b), MapSender.diff(a, b), "case " + i);
        }
    }
}

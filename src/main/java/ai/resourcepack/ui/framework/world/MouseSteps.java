package ai.resourcepack.ui.framework.world;

import java.util.Arrays;

/**
 * How far one count of a player's mouse turns their head, learned from how their pitch changes.
 *
 * <p>Minecraft never tells the server a player's mouse sensitivity, and a mouse sends counts, never
 * distances, so nothing on the server can learn their DPI. But the client turns the head by a whole number
 * of counts times one step, which depends only on the sensitivity ({@code MouseHandler.turnPlayer} and
 * {@code Entity.turn}): every change of pitch it reports is a whole multiple of that step, to float
 * rounding. The largest step that nearly every recent change is a multiple of is the player's. With it a
 * view moves its pointer the same distance per count whatever their sensitivity, as a desktop pointer does.
 *
 * <p>No step is found while the turns are not whole counts (smooth camera, a trackpad sending fractions, a
 * client that is not vanilla); the last one found then stands. A turn the server makes is not whole counts
 * either, and is left out ({@link #hold}): a few of them would not change what is found, but the same turn
 * over and over (a plugin turning a player whose mouse is still) would look like a step. Server thread only.
 */
final class MouseSteps {
    /** The step at sensitivity [s] (the option's 0 to 1, which the game shows as 0 to 200%), in degrees per count. */
    static double step(double s) {
        // As the client works it out: in double, from the float constants it uses.
        double ss = s * 0.6F + 0.2F;
        return ss * ss * ss * 8.0 * 0.15F;
    }

    /** The sensitivity (0 to 1) whose step is [step]. */
    static double sensitivity(double step) {
        // max before min, not clamp: a step a hair under the slider's least would give -0.0.
        return Math.min(1.0, Math.max(0.0, (Math.cbrt(step / (8.0 * 0.15F)) - 0.2F) / 0.6F));
    }

    /** The smallest and largest step the game's slider can give, and the one at its default sensitivity. */
    static final double MIN = step(0), MAX = step(1), DEFAULT = step(0.5);

    /** Changes kept, the fewest a step is found from, and how many new ones between attempts. */
    private static final int KEEP = 64, ENOUGH = 40, EVERY = 8;
    /**
     * How far a change may be from a whole number of steps and still count as one, in degrees. The client
     * adds each frame's turn to a float pitch (under 90°, so it rounds by 4e-6° at most a frame): this is
     * more than a tick of hundreds of frames can drift, and far less than the smallest step, so a change
     * that is not whole counts (a teleport, a turn cut short at ±90°) seldom fits a step by chance.
     */
    private static final double TOLERANCE = 5e-4;
    /** The share of the kept changes the best candidate must fit before the changes are taken for whole counts at all. */
    private static final double FIT = 0.65;
    /** How far outside the slider's range a step may be found, as a share of the range's ends. */
    private static final double MARGIN = 0.1;
    /** Largest change kept, in degrees: a bigger one is a teleport more often than a flick. */
    private static final double LARGEST = 45;
    /** Candidate steps: each of this many smallest changes, divided by 1 up to this many counts. */
    private static final int SEEDS = 8, COUNTS = 16;
    /**
     * Samples left out after a {@link #hold}: until the client has taken a turn the server sent, the server
     * still applies the rotations it sends from before it, and its first rotation after can arrive a tick
     * or two after the server learns that it has.
     */
    private static final int SETTLE = 4;

    private final double[] changes = new double[KEEP];
    private int kept, next, sinceFit, settle;
    private float last = Float.NaN;
    /** Degrees per count; NaN until known. */
    private double step;

    /** [known]: the step found earlier (remembered on the player), or NaN. */
    MouseSteps(double known) {
        this.step = plausible(known) ? known : Double.NaN;
    }

    /** Degrees per count, or NaN while unknown. */
    double step() {
        return step;
    }

    /**
     * The server turned them (a teleport, a respawn, a turn a view sends), or is waiting for their client to
     * take one: the client's rotations go either side of it for a while, so the next few changes of pitch
     * are left out. Called again each tick for as long as the turn lasts.
     */
    void hold() {
        settle = SETTLE;
        last = Float.NaN;
    }

    /** Their pitch now: true when that showed a new step. */
    boolean sample(float pitch) {
        if (settle > 0) {
            settle--;
            return false;
        }
        float from = last;
        last = pitch;
        // At the client's ±90° limit the turn is cut short, not a whole number of counts.
        if (Float.isNaN(from) || Math.abs(from) >= 89.9f || Math.abs(pitch) >= 89.9f) return false;
        double change = Math.abs((double) pitch - from);
        if (change < MIN * (1 - MARGIN) || change > LARGEST) return false;
        changes[next] = change;
        next = (next + 1) % KEEP;
        if (kept < KEEP) kept++;
        if (kept < ENOUGH || ++sinceFit < EVERY) return false;
        sinceFit = 0;
        double found = fit();
        if (Double.isNaN(found) || (!Double.isNaN(step) && Math.abs(found / step - 1) < 0.01)) return false;
        step = found;
        return true;
    }

    /**
     * The player's step from the kept changes, or NaN. Each candidate (one of the smallest changes over a few
     * counts) is pinned down by least squares over the changes of only a few of its steps first, then over
     * larger and larger ones, so a large change is judged against a step already pinned down by the small
     * ones.
     *
     * <p>Every whole fraction of the true step fits every change it does, and some of the rest: by chance, or
     * because they are alike (a turn cut short at -90° after one at +90° is 180° off whole counts). So the
     * step is the largest candidate that fits nearly as many as the best fitting one, four in five of them:
     * more than a fraction gains from the few changes that are not whole counts, and far more than a multiple
     * of the step (fitting about half of them) manages. The best must fit {@link #FIT} of the changes, or
     * they are not whole counts at all.
     */
    private double fit() {
        double[] sorted = Arrays.copyOf(changes, kept);
        Arrays.sort(sorted);
        double[] steps = new double[SEEDS * COUNTS];
        int[] fitted = new int[SEEDS * COUNTS];
        int found = 0, most = 0;
        for (int i = 0; i < Math.min(SEEDS, kept); i++) {
            for (int n = 1; n <= COUNTS; n++) {
                double k = sorted[i] / n;
                if (k < MIN * (1 - MARGIN)) break;
                if (k > MAX * (1 + MARGIN)) continue;
                for (double counts : PASSES) {
                    double sum = 0, squares = 0;
                    for (double c : sorted) {
                        double m = Math.rint(c / k);
                        if (m > counts) break;
                        if (!fits(c, k)) continue;
                        sum += c * m;
                        squares += m * m;
                    }
                    if (squares == 0) break;
                    k = sum / squares;
                }
                if (!plausible(k)) continue;
                steps[found] = k;
                fitted[found] = fitting(sorted, k);
                most = Math.max(most, fitted[found]);
                found++;
            }
        }
        if (most < FIT * kept) return Double.NaN;
        double least = 0.8 * most;
        double best = Double.NaN;
        for (int i = 0; i < found; i++) {
            if (fitted[i] >= least && !(steps[i] <= best)) best = steps[i];
        }
        return best;
    }

    /** How many of [changes] are a whole number of [step]s. */
    private static int fitting(double[] changes, double step) {
        int n = 0;
        for (double c : changes) if (fits(c, step)) n++;
        return n;
    }

    /** The most counts a change may be in each pass of {@link #fit} that pins a candidate down. */
    private static final double[] PASSES = {12, 100, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};

    /** Whether [change] is a whole number of [step]s, near enough. */
    private static boolean fits(double change, double step) {
        double m = Math.rint(change / step);
        return m >= 1 && Math.abs(change - m * step) <= TOLERANCE;
    }

    private static boolean plausible(double step) {
        return step >= MIN * (1 - MARGIN) && step <= MAX * (1 + MARGIN);
    }
}

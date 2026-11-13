package ai.resourcepack.ui.html;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Compares two folders of PNGs (the output of {@code renderSamples}, taken from two builds) pixel for pixel, the
 * gate in CI that a change to the renderer, a stylesheet or a page shows up as a picture. Run with
 * {@code sh gradlew renderDiff -Pbefore=<dir> -Pafter=<dir> -Pout=<dir>}.
 *
 * <p>Writes {@code <out>/diff/<name>.png} for each picture that changed (changed pixels red, the rest the after
 * picture faded to grey) and {@code <out>/summary.md}, a markdown table of what changed, and prints it. Exit code 1
 * when any picture exists on both sides and is not identical (a different size counts); 0 when everything is
 * identical. A picture on one side only is reported and does not fail.
 */
public final class RenderDiff {
    private RenderDiff() {
    }

    /** One picture's outcome. */
    private record Row(String name, String status, long changed, long total, int maxDelta, String box, boolean differs) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RenderDiff <before-dir> <after-dir> <out-dir> [title]");
            System.exit(2);
        }
        Path before = Path.of(args[0]), after = Path.of(args[1]), out = Path.of(args[2]);
        String title = args.length > 3 ? args[3] : "Render diff";
        Files.createDirectories(out);
        Path diffDir = out.resolve("diff");
        deleteTree(diffDir);

        TreeSet<String> names = new TreeSet<>();
        names.addAll(pngs(before));
        names.addAll(pngs(after));

        List<Row> rows = new ArrayList<>();
        int same = 0;
        for (String name : names) {
            Path b = before.resolve(name), a = after.resolve(name);
            boolean hasB = Files.isRegularFile(b), hasA = Files.isRegularFile(a);
            if (!hasB || !hasA) {
                rows.add(new Row(name, hasA ? "only after" : "only before", 0, 0, 0, "", false));
                continue;
            }
            Row r = compare(name, b, a, diffDir);
            if (r.differs) rows.add(r);
            else same++;
        }

        boolean fail = rows.stream().anyMatch(Row::differs);
        StringBuilder md = new StringBuilder();
        md.append("### ").append(title).append("\n\n");
        int differing = (int) rows.stream().filter(Row::differs).count();
        int oneSided = rows.size() - differing;
        md.append(names.size()).append(" pictures: ").append(same).append(" identical, ")
                .append(differing).append(" changed, ").append(oneSided).append(" on one side only.\n\n");
        if (!rows.isEmpty()) {
            md.append("| picture | status | changed pixels | share | largest channel difference | where |\n");
            md.append("|---|---|---:|---:|---:|---|\n");
            for (Row r : rows) {
                md.append("| `").append(r.name).append("` | ").append(r.status).append(" | ")
                        .append(r.differs && r.total > 0 ? Long.toString(r.changed) : "")
                        .append(" | ").append(r.differs && r.total > 0 ? String.format(Locale.ROOT, "%.3f%%", 100.0 * r.changed / r.total) : "")
                        .append(" | ").append(r.differs && r.maxDelta >= 0 ? Integer.toString(r.maxDelta) : "")
                        .append(" | ").append(r.box).append(" |\n");
            }
            md.append('\n');
        }
        Files.writeString(out.resolve("summary.md"), md.toString());
        System.out.print(md);
        System.out.println(fail ? "RESULT: pictures differ" : "RESULT: identical");
        System.exit(fail ? 1 : 0);
    }

    private static Row compare(String name, Path b, Path a, Path diffDir) throws IOException {
        BufferedImage ib = ImageIO.read(b.toFile()), ia = ImageIO.read(a.toFile());
        if (ib == null || ia == null) {
            return new Row(name, "unreadable PNG (" + (ib == null ? "before" : "after") + ")", 0, 0, -1, "", true);
        }
        if (ib.getWidth() != ia.getWidth() || ib.getHeight() != ia.getHeight()) {
            return new Row(name, "size " + ib.getWidth() + "x" + ib.getHeight() + " to " + ia.getWidth() + "x" + ia.getHeight(), 0, 0, -1, "", true);
        }
        int w = ia.getWidth(), h = ia.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        long changed = 0;
        int max = 0, x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] rb = new int[w], ra = new int[w], outRow = new int[w];
        for (int y = 0; y < h; y++) {
            ib.getRGB(0, y, w, 1, rb, 0, w);
            ia.getRGB(0, y, w, 1, ra, 0, w);
            boolean rowChanged = false;
            for (int x = 0; x < w; x++) {
                int pb = rb[x], pa = ra[x];
                // Fully transparent pixels all look alike, whatever colour they carry.
                boolean equal = pb == pa || ((pb >>> 24) == 0 && (pa >>> 24) == 0);
                if (equal) {
                    int g = (((pa >> 16) & 255) * 30 + ((pa >> 8) & 255) * 59 + (pa & 255) * 11) / 100;
                    g = 255 - (255 - g) / 4;
                    outRow[x] = 0xFF000000 | g << 16 | g << 8 | g;
                    continue;
                }
                changed++;
                rowChanged = true;
                int d = Math.max(Math.max(Math.abs((pb >>> 24) - (pa >>> 24)), Math.abs(((pb >> 16) & 255) - ((pa >> 16) & 255))),
                        Math.max(Math.abs(((pb >> 8) & 255) - ((pa >> 8) & 255)), Math.abs((pb & 255) - (pa & 255))));
                if (d > max) max = d;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                outRow[x] = 0xFFFF0000;
            }
            if (rowChanged) {
                if (y < y0) y0 = y;
                y1 = y;
            }
            diff.setRGB(0, y, w, 1, outRow, 0, w);
        }
        if (changed == 0) return new Row(name, "identical", 0, (long) w * h, 0, "", false);
        Path png = diffDir.resolve(name);
        Files.createDirectories(png.getParent());
        ImageIO.write(diff, "png", png.toFile());
        return new Row(name, "changed", changed, (long) w * h, max, "x " + x0 + "–" + x1 + ", y " + y0 + "–" + y1, true);
    }

    private static List<String> pngs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".png"))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}

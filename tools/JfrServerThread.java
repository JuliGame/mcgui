import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** java JfrServerThread.java <file.jfr> <fromSecond> <toSecond> [thread]: what the server thread did, by MC GUI's frames. */
public class JfrServerThread {
    public static void main(String[] a) throws Exception {
        String thread = a.length > 3 ? a[3] : "Server thread";
        List<RecordedEvent> all = RecordingFile.readAllEvents(Path.of(a[0]));
        Instant t0 = all.stream().map(RecordedEvent::getStartTime).min(Comparator.naturalOrder()).get();
        double from = Double.parseDouble(a[1]), to = Double.parseDouble(a[2]);
        Map<String, Integer> cpu = new HashMap<>(), cpuTop = new HashMap<>();
        Map<String, Long> alloc = new HashMap<>();
        int samples = 0, ours = 0; long allocAll = 0, allocOurs = 0;
        for (RecordedEvent e : all) {
            double s = Duration.between(t0, e.getStartTime()).toMillis() / 1000.0;
            if (s < from || s > to) continue;
            RecordedThread th = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.hasField("eventThread") ? e.getThread("eventThread") : null;
            if (th == null || !thread.equals(th.getJavaName())) continue;
            RecordedStackTrace st = e.getStackTrace();
            if (st == null) continue;
            String key = null, top = null;
            List<RecordedFrame> fr = st.getFrames();
            for (int i = 0; i < fr.size(); i++) {
                RecordedMethod m = fr.get(i).getMethod();
                String n = m.getType().getName() + "." + m.getName();
                if (top == null) top = n + ":" + fr.get(i).getLineNumber();
                if (n.startsWith("ai.resourcepack")) { key = n + ":" + fr.get(i).getLineNumber(); break; }
            }
            String name = e.getEventType().getName();
            if (name.equals("jdk.ExecutionSample")) {
                samples++;
                if (key != null) { ours++; cpu.merge(key, 1, Integer::sum); cpuTop.merge(key + " <- " + top, 1, Integer::sum); }
            } else if (name.equals("jdk.ObjectAllocationSample")) {
                long w = e.getLong("weight");
                allocAll += w;
                if (key != null) { allocOurs += w; alloc.merge(key + " <- " + top + " [" + e.getClass("objectClass").getName() + "]", w, Long::sum); }
            }
        }
        System.out.printf("%s %.0f-%.0f s: %d samples, %d in ours; %.1f MB sampled allocation, %.1f MB in ours%n", thread, from, to, samples, ours, allocAll / 1e6, allocOurs / 1e6);
        System.out.println("-- cpu by our innermost frame");
        cpu.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25).forEach(x -> System.out.println(x.getValue() + "  " + x.getKey()));
        System.out.println("-- cpu by our frame <- top frame");
        cpuTop.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25).forEach(x -> System.out.println(x.getValue() + "  " + x.getKey()));
        System.out.println("-- allocation by our frame <- top frame [class]");
        alloc.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(30).forEach(x -> System.out.printf("%.2f MB  %s%n", x.getValue() / 1e6, x.getKey()));
    }
}

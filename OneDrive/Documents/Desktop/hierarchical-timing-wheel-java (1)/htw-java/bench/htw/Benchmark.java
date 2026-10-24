package htw;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Fair comparison: hierarchical timing wheel vs. binary min-heap (PriorityQueue).
 * Both store id + callback and both support cancel (heap uses lazy deletion).
 * Usage: java -cp out htw.Benchmark [N] [cancelPercent]
 * The workload runs twice; the second run (after JIT warm-up) is the one to quote.
 */
public class Benchmark {
    static final long HORIZON = 1_000_000;

    static final class HeapEntry {
        final long expiry, id;
        final TimerCallback cb;
        HeapEntry(long e, long i, TimerCallback c) { expiry = e; id = i; cb = c; }
    }

    static long[] fired = {0};

    static void runWheel(long[] delays, boolean[] cancel) {
        fired[0] = 0;
        int n = delays.length;
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        long t0 = System.nanoTime();
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) ids[i] = w.schedule(delays[i], id -> fired[0]++);
        for (int i = 0; i < n; i++) if (cancel[i]) w.cancel(ids[i]);
        long t1 = System.nanoTime();
        w.advance(HORIZON);
        long t2 = System.nanoTime();
        System.out.printf("Timing wheel : schedule+cancel %.0f ms, run %.0f ms, total %.0f ms, fired %d%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t0) / 1e6, fired[0]);
    }

    static void runHeap(long[] delays, boolean[] cancel) {
        fired[0] = 0;
        int n = delays.length;
        PriorityQueue<HeapEntry> pq = new PriorityQueue<>((a, b) -> Long.compare(a.expiry, b.expiry));
        Set<Long> cancelled = new HashSet<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) pq.add(new HeapEntry(delays[i], i + 1, id -> fired[0]++));
        for (int i = 0; i < n; i++) if (cancel[i]) cancelled.add((long) (i + 1));
        long t1 = System.nanoTime();
        for (long now = 1; now <= HORIZON; now++) {
            while (!pq.isEmpty() && pq.peek().expiry <= now) {
                HeapEntry e = pq.poll();
                if (!cancelled.contains(e.id)) e.cb.fire(e.id);
            }
        }
        long t2 = System.nanoTime();
        System.out.printf("Min-heap     : schedule+cancel %.0f ms, run %.0f ms, total %.0f ms, fired %d%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t0) / 1e6, fired[0]);
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        int cancelPct = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        Random rng = new Random(1);
        long[] delays = new long[n];
        boolean[] cancel = new boolean[n];
        for (int i = 0; i < n; i++) {
            delays[i] = 1 + (long) (rng.nextDouble() * HORIZON);
            cancel[i] = rng.nextInt(100) < cancelPct;
        }
        System.out.println("N=" + n + " timers, delays in [1," + HORIZON + "], " + cancelPct + "% cancelled");
        for (int round = 1; round <= 2; round++) {
            System.out.println("-- run " + round + (round == 1 ? " (warm-up)" : ""));
            runWheel(delays, cancel);
            runHeap(delays, cancel);
        }
    }
}

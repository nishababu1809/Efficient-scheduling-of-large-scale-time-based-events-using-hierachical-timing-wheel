package htw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Interactive demo: add, repeat, cancel, reschedule, remaining, next, stats, clear, tick, status, demo, help, quit */
public class Main {

    private static void demo(HierarchicalTimingWheel w) {
        System.out.println("Scheduling timers with delays 5, 70, 5000, 300000 (spans 4 levels)...");
        for (long d : new long[]{5, 70, 5000, 300000}) {
            long id = w.schedule(d, i -> System.out.println("  [t=" + w.now() + "] timer #" + i + " FIRED"));
            System.out.println("  timer #" + id + " -> expires at t=" + (w.now() + d));
        }
        w.advance(300000);
        System.out.println("Pending: " + w.pending());
    }

    public static void main(String[] args) throws IOException {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        System.out.println("Hierarchical Timing Wheel (" + HierarchicalTimingWheel.LEVELS + " levels x "
                + HierarchicalTimingWheel.SLOTS + " slots, range " + HierarchicalTimingWheel.RANGE
                + " ticks). Type 'help'.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) break;
            String[] p = line.trim().split("\\s+");
            String cmd = p[0];
            try {
                switch (cmd) {
                    case "add": {
                        if (p.length < 2) { System.out.println("  usage: add <delay >= 0>"); break; }
                        long d = Long.parseLong(p[1]);
                        if (d < 0) { System.out.println("  usage: add <delay >= 0>"); break; }
                        long id = w.schedule(d, i -> System.out.println("  [t=" + w.now() + "] timer #" + i + " FIRED"));
                        System.out.println("  timer #" + id + " scheduled, fires at t=" + (w.now() + (d == 0 ? 1 : d)));
                        break;
                    }
                    case "cancel": {
                        if (p.length < 2) { System.out.println("  usage: cancel <id>"); break; }
                        System.out.println(w.cancel(Long.parseLong(p[1])) ? "  cancelled" : "  no such pending timer");
                        break;
                    }
                    case "tick": {
                        long n = p.length > 1 ? Long.parseLong(p[1]) : 1;
                        if (n < 0) { System.out.println("  n must be >= 0"); break; }
                        w.advance(n);
                        System.out.println("  now t=" + w.now() + ", pending=" + w.pending());
                        break;
                    }
                    case "repeat": {
                        if (p.length < 2) { System.out.println("  usage: repeat <interval >= 1>"); break; }
                        long iv = Long.parseLong(p[1]);
                        long id = w.scheduleRepeating(iv, i -> System.out.println("  [t=" + w.now() + "] repeating timer #" + i + " FIRED"));
                        System.out.println("  repeating timer #" + id + " every " + Math.max(iv, 1) + " ticks");
                        break;
                    }
                    case "reschedule": {
                        if (p.length < 3) { System.out.println("  usage: reschedule <id> <newDelay>"); break; }
                        System.out.println(w.reschedule(Long.parseLong(p[1]), Long.parseLong(p[2]))
                                ? "  rescheduled" : "  no such pending timer");
                        break;
                    }
                    case "remaining": {
                        if (p.length < 2) { System.out.println("  usage: remaining <id>"); break; }
                        long r = w.remaining(Long.parseLong(p[1]));
                        System.out.println(r < 0 ? "  not pending" : "  fires in " + r + " ticks");
                        break;
                    }
                    case "next": {
                        long e = w.nextExpiry();
                        System.out.println(e < 0 ? "  no pending timers" : "  next event at t=" + e + " (in " + (e - w.now()) + " ticks)");
                        break;
                    }
                    case "stats": {
                        int[] c = w.countsPerLevel();
                        for (int l = 0; l < HierarchicalTimingWheel.LEVELS; l++)
                            System.out.println("  level " + l + " (res " + (1L << (HierarchicalTimingWheel.BITS * l)) + " ticks/slot): " + c[l]);
                        System.out.println("  overflow: " + c[HierarchicalTimingWheel.LEVELS]);
                        break;
                    }
                    case "clear":
                        w.clear();
                        System.out.println("  all timers cancelled");
                        break;
                    case "status":
                        System.out.println("  now t=" + w.now() + ", pending=" + w.pending());
                        break;
                    case "demo":
                        demo(w);
                        break;
                    case "help":
                        System.out.println("  add <delay> | repeat <interval> | cancel <id> | reschedule <id> <delay>\n  remaining <id> | next | stats | clear | tick [n] | status | demo | quit");
                        break;
                    case "quit":
                    case "exit":
                        return;
                    case "":
                        break;
                    default:
                        System.out.println("  unknown command (try 'help')");
                }
            } catch (NumberFormatException e) {
                System.out.println("  please enter a valid whole number");
            }
        }
    }
}

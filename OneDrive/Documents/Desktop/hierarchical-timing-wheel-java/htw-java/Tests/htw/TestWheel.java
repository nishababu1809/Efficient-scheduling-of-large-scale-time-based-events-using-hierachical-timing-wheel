package htw;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/** Self-checking tests (no external libraries): run with `make check`. */
public class TestWheel {
    private static int passed = 0;

    private static void check(boolean c, String msg) {
        if (!c) {
            System.err.println("FAIL: " + msg);
            System.exit(1);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("[ok] " + name);
    }

    static void exactTicks() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        Map<Long, Long> fired = new HashMap<>(), expect = new HashMap<>();
        long[] delays = {1, 2, 63, 64, 65, 100, 4095, 4096, 4097, 262143, 262144, 300000,
                (1L << 24) - 1, 1L << 24, (1L << 24) + 1};
        for (long d : delays) {
            long id = w.schedule(d, i -> fired.put(i, w.now()));
            expect.put(id, d);
        }
        w.advance((1L << 24) + 5);
        check(fired.equals(expect), "fire ticks differ from expected");
        check(w.pending() == 0, "pending should be 0");
        pass("fires at exact tick across all levels + overflow");
    }

    static void zeroDelay() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        int[] n = {0};
        w.schedule(0, i -> n[0]++);
        check(n[0] == 0, "must not fire immediately");
        w.tick();
        check(n[0] == 1, "zero delay should fire on next tick");
        pass("zero delay fires on next tick");
    }

    static void cancel() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        int[] n = {0};
        long a = w.schedule(10, i -> n[0]++);
        long b = w.schedule(10, i -> n[0]++);
        long c = w.schedule(5000, i -> n[0]++);
        check(w.cancel(a), "cancel a");
        check(!w.cancel(a), "double cancel");
        check(w.cancel(c), "cancel c");
        check(!w.cancel(9999), "unknown id");
        w.advance(6000);
        check(n[0] == 1, "only b should fire");
        check(!w.cancel(b), "cancel after fire");
        check(w.pending() == 0, "pending 0");
        pass("cancel (double cancel, unknown id, after fire, other levels)");
    }

    static void cancelInsideCallback() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        int[] n = {0};
        long[] b = {0};
        w.schedule(5, i -> { w.cancel(b[0]); n[0]++; });
        b[0] = w.schedule(5, i -> n[0]++);
        w.advance(5);
        check(n[0] >= 1 && n[0] <= 2, "callback cancel should be safe");
        check(w.pending() == 0, "pending 0");
        pass("cancel of same-tick timer from inside a callback is safe");
    }

    static void rescheduleInCallback() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        int[] n = {0};
        TimerCallback[] rec = new TimerCallback[1];
        rec[0] = i -> { if (++n[0] < 5) w.schedule(100, rec[0]); };
        w.schedule(100, rec[0]);
        w.advance(1000);
        check(n[0] == 5, "should fire 5 times");
        pass("periodic / re-scheduling from callback");
    }

    static void manySameSlot() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        int[] n = {0};
        for (int i = 0; i < 100000; i++) w.schedule(777, id -> n[0]++);
        w.advance(777);
        check(n[0] == 100000, "all 100k should fire");
        pass("100k timers in one slot");
    }

    static void invalidInput() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        boolean threw = false;
        try { w.schedule(5, null); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "null callback must be rejected");
        int[] n = {0};
        w.schedule(-10, i -> n[0]++);   // negative delay treated as 1
        w.tick();
        check(n[0] == 1, "negative delay -> next tick");
        pass("invalid input: null callback rejected, negative delay clamped");
    }


    static void repeating() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        java.util.List<Long> at = new java.util.ArrayList<>();
        long id = w.scheduleRepeating(10, i -> at.add(w.now()));
        w.advance(35);
        check(at.equals(java.util.Arrays.asList(10L, 20L, 30L)), "repeating fire ticks " + at);
        check(w.isPending(id), "repeating timer stays pending");
        check(w.cancel(id), "cancel repeating");
        w.advance(50);
        check(at.size() == 3 && w.pending() == 0, "no firing after cancel");
        // self-cancel from own callback after 3 runs
        int[] n = {0};
        long[] self = {0};
        self[0] = w.scheduleRepeating(5, i -> { if (++n[0] == 3) w.cancel(self[0]); });
        w.advance(100);
        check(n[0] == 3 && w.pending() == 0, "self-cancel inside callback");
        // repeating across a cascade boundary (interval 100 spans level 1)
        HierarchicalTimingWheel w2 = new HierarchicalTimingWheel();
        int[] m = {0};
        w2.scheduleRepeating(100, i -> m[0]++);
        w2.advance(10_000);
        check(m[0] == 100, "100 firings, got " + m[0]);
        pass("repeating timers (same id, cancel, self-cancel, cross-level)");
    }

    static void rescheduleOp() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        long[] firedAt = {-1};
        long id = w.schedule(50, i -> firedAt[0] = w.now());
        w.advance(30);
        check(w.remaining(id) == 20, "remaining 20");
        check(w.reschedule(id, 100), "reschedule ok");   // extend timeout: fires at 130
        check(w.remaining(id) == 100, "remaining 100");
        w.advance(99);
        check(firedAt[0] == -1, "must not fire early");
        w.advance(1);
        check(firedAt[0] == 130, "fired at 130, got " + firedAt[0]);
        check(!w.reschedule(id, 5), "reschedule after fire fails");
        check(!w.reschedule(424242, 5), "reschedule unknown id fails");
        check(w.remaining(id) == -1 && !w.isPending(id), "not pending anymore");
        // move across levels both ways
        long far = w.schedule(5, i -> { });
        check(w.reschedule(far, 3_000_000), "to far level");
        check(w.reschedule(far, 2), "back to level 0");
        w.advance(2);
        check(w.pending() == 0, "fired after move back");
        pass("reschedule / remaining / isPending");
    }

    static void nextExpiryAndStats() {
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        check(w.nextExpiry() == -1, "empty -> -1");
        w.schedule(5000, i -> { });
        check(w.nextExpiry() == 5000, "single far timer");
        w.schedule(70, i -> { });
        w.schedule(20_000_000, i -> { });
        check(w.nextExpiry() == 70, "earliest is 70");
        int[] c = w.countsPerLevel();
        check(c[0] == 0 && c[1] == 1 && c[2] == 1 && c[HierarchicalTimingWheel.LEVELS] == 1,
                "level counts " + java.util.Arrays.toString(c));
        w.clear();
        check(w.pending() == 0 && w.nextExpiry() == -1, "clear empties everything");
        // brute-force cross-check of nextExpiry while time advances (stale-level timers included)
        Random rng = new Random(7);
        HierarchicalTimingWheel x = new HierarchicalTimingWheel();
        Map<Long, Long> exp = new HashMap<>();
        for (int round = 0; round < 3000; round++) {
            for (int k = rng.nextInt(4); k > 0; k--) {
                long d = 1 + (long) (Math.pow(rng.nextDouble(), 3) * 30_000_000L);
                long id = x.schedule(d, i -> exp.remove(i));
                exp.put(id, x.now() + d);
            }
            long min = -1;
            for (long e : exp.values()) if (min < 0 || e < min) min = e;
            check(x.nextExpiry() == min, "nextExpiry " + x.nextExpiry() + " != brute " + min + " at t=" + x.now());
            x.advance(1 + rng.nextInt(rng.nextInt(10) == 0 ? 20000 : 50));
        }
        pass("nextExpiry matches brute force over 3000 random states; stats; clear");
    }

    static void randomized() {
        Random rng = new Random(12345);
        HierarchicalTimingWheel w = new HierarchicalTimingWheel();
        Map<Long, Long> expect = new HashMap<>(), fired = new HashMap<>();
        for (int i = 0; i < 20000; i++) {
            long d = 1 + (long) (rng.nextDouble() * 40_000_000L);
            long id = w.schedule(d, k -> fired.put(k, w.now()));
            expect.put(id, d);
        }
        for (Iterator<Map.Entry<Long, Long>> it = expect.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (rng.nextInt(10) == 0) {
                check(w.cancel(e.getKey()), "cancel pending");
                it.remove();
            }
        }
        w.advance(40_000_001L);
        check(fired.equals(expect), "randomized fire ticks differ");
        check(w.pending() == 0, "pending 0");
        pass("randomized: 20k timers up to 40M ticks, 10% cancelled");
    }

    public static void main(String[] args) {
        exactTicks();
        zeroDelay();
        cancel();
        cancelInsideCallback();
        rescheduleInCallback();
        manySameSlot();
        invalidInput();
        repeating();
        rescheduleOp();
        nextExpiryAndStats();
        randomized();
        System.out.println(passed + " test groups passed");
    }
}

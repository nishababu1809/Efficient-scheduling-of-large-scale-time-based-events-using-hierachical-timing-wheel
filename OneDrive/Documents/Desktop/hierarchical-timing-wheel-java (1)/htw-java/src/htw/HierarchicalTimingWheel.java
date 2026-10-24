package htw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Hierarchical Timing Wheel - efficient scheduling of large-scale time-based events.
 *
 * Layout: LEVELS wheels, each with SLOTS = 2^BITS buckets.
 *   Level l has a resolution of 2^(BITS*l) ticks per slot.
 *   Total direct range = 2^(BITS*LEVELS) ticks (2^24 = 16,777,216 with defaults).
 *   Timers beyond that range wait in an "overflow" list and are re-inserted
 *   each time the top wheel wraps around.
 *
 * Each bucket is a hand-written doubly linked list, so schedule and cancel are O(1).
 * Not thread-safe; callbacks may schedule()/cancel()/reschedule() but must not call tick().
 */
public class HierarchicalTimingWheel {

    public static final int BITS = 6;                    // 64 slots per level
    public static final int SLOTS = 1 << BITS;
    public static final int LEVELS = 4;
    public static final int RANGE_BITS = BITS * LEVELS;
    public static final long RANGE = 1L << RANGE_BITS;

    private static final int NOT_LINKED = -1;
    private static final int OVERFLOW = LEVELS;          // pseudo-level for the overflow list

    private static final class Timer {
        final long id;
        long expiry;
        long period;              // 0 = one-shot, >0 = repeating every 'period' ticks
        final TimerCallback cb;
        Timer prev, next;
        int level = NOT_LINKED;   // which list this timer is in (NOT_LINKED = none)
        int slot;
        boolean cancelled;

        Timer(long id, long expiry, TimerCallback cb) {
            this.id = id;
            this.expiry = expiry;
            this.cb = cb;
        }
    }

    private final Timer[][] wheel = new Timer[LEVELS][SLOTS];
    private Timer overflow;
    private long now;
    private long nextId = 1;
    private final Map<Long, Timer> index = new HashMap<>();
    private final ArrayList<Timer> due = new ArrayList<>();
    private boolean ticking;

    public long now() { return now; }

    public int pending() { return index.size(); }

    /** Schedule cb to fire {@code delay} ticks from now (delay <= 0 is treated as 1). O(1). */
    public long schedule(long delay, TimerCallback cb) {
        if (cb == null) throw new IllegalArgumentException("callback must not be null");
        if (delay <= 0) delay = 1;
        Timer t = new Timer(nextId++, now + delay, cb);
        index.put(t.id, t);
        insert(t);
        return t.id;
    }

    /** Cancel a pending timer. O(1) expected (hash lookup + list unlink). */
    public boolean cancel(long id) {
        Timer t = index.remove(id);
        if (t == null) return false;
        if (t.level != NOT_LINKED) {
            unlink(t);                 // sitting in a bucket
        } else {
            t.cancelled = true;        // already collected for firing this tick
        }
        return true;
    }

    /**
     * Schedule a repeating timer: first fires after {@code interval} ticks, then every
     * {@code interval} ticks until cancelled. The id stays the same across firings. O(1).
     */
    public long scheduleRepeating(long interval, TimerCallback cb) {
        if (cb == null) throw new IllegalArgumentException("callback must not be null");
        if (interval <= 0) interval = 1;
        Timer t = new Timer(nextId++, now + interval, cb);
        t.period = interval;
        index.put(t.id, t);
        insert(t);
        return t.id;
    }

    /**
     * Move a pending timer to fire {@code newDelay} ticks from now (e.g. extend a session
     * timeout). Returns false if the id is unknown or the timer is firing right now. O(1).
     */
    public boolean reschedule(long id, long newDelay) {
        Timer t = index.get(id);
        if (t == null || t.level == NOT_LINKED) return false;
        if (newDelay <= 0) newDelay = 1;
        unlink(t);
        t.expiry = now + newDelay;
        insert(t);
        return true;
    }

    /** True if the id refers to a timer that has not yet fired (or is repeating) and was not cancelled. O(1). */
    public boolean isPending(long id) {
        return index.containsKey(id);
    }

    /** Ticks remaining until the timer fires, or -1 if it is not pending. O(1). */
    public long remaining(long id) {
        Timer t = index.get(id);
        return t == null ? -1 : t.expiry - now;
    }

    /**
     * Absolute tick of the earliest pending timer, or -1 if none. Lets a driver sleep until
     * the next event instead of waking every tick. O(L*S + m): scans at most L*S slot heads plus
     * the lists of the first non-empty slot per level (m = timers in those slots).
     */
    public long nextExpiry() {
        long best = -1;
        for (int l = 0; l < LEVELS; l++) {
            int c = (int) ((now >>> (BITS * l)) & (SLOTS - 1));
            // Timers at level l always sit in a block strictly ahead of 'now' (1..64 blocks),
            // so the first non-empty slot in cyclic order holds this level's earliest timer.
            for (int off = 1; off <= SLOTS; off++) {
                Timer t = wheel[l][(c + off) & (SLOTS - 1)];
                if (t != null) {
                    best = minExpiry(t, best);
                    break;
                }
            }
        }
        return minExpiry(overflow, best);
    }

    private static long minExpiry(Timer head, long best) {
        for (Timer t = head; t != null; t = t.next)
            if (best < 0 || t.expiry < best) best = t.expiry;
        return best;
    }

    /** Cancel every pending timer. O(n). */
    public void clear() {
        for (Timer[] lvl : wheel) java.util.Arrays.fill(lvl, null);
        overflow = null;
        for (Timer t : index.values()) {
            t.cancelled = true;
            detachFields(t);
        }
        index.clear();
    }

    /** Number of pending timers per level; the last entry is the overflow list. O(n). */
    public int[] countsPerLevel() {
        int[] counts = new int[LEVELS + 1];
        for (int l = 0; l < LEVELS; l++)
            for (int s = 0; s < SLOTS; s++)
                for (Timer t = wheel[l][s]; t != null; t = t.next) counts[l]++;
        for (Timer t = overflow; t != null; t = t.next) counts[LEVELS]++;
        return counts;
    }

    /**
     * Advance time by one tick and fire everything that is due.
     * Amortised O(1) per tick plus O(1) per fired timer.
     */
    public void tick() {
        if (ticking) throw new IllegalStateException("tick() must not be called from a callback");
        ticking = true;
        try {
            now++;

            // Cascade: when the low (BITS*l) bits of now are zero, level l's current
            // slot has come into range of the finer wheels below it.
            for (int l = 1; l < LEVELS; l++) {
                if ((now & ((1L << (BITS * l)) - 1)) != 0) break;
                cascade(l, (int) ((now >>> (BITS * l)) & (SLOTS - 1)));
            }
            // Top wheel wrapped: bring overflow timers that are now in range.
            if ((now & (RANGE - 1)) == 0) {
                Timer t = overflow;
                overflow = null;
                while (t != null) {
                    Timer nx = t.next;
                    detachFields(t);
                    place(t);
                    t = nx;
                }
            }
            // Collect level-0 slot for this tick.
            int s0 = (int) (now & (SLOTS - 1));
            Timer t = wheel[0][s0];
            wheel[0][s0] = null;
            while (t != null) {
                Timer nx = t.next;
                detachFields(t);
                due.add(t);
                t = nx;
            }
            // Fire after the structure is consistent so callbacks may schedule/cancel.
            // Index loop: callbacks only add to buckets, never to 'due'.
            try {
                for (int i = 0; i < due.size(); i++) {
                    Timer d = due.get(i);
                    if (d.cancelled) continue;
                    if (d.period == 0) {
                        index.remove(d.id);
                        d.cb.fire(d.id);
                    } else {                       // repeating: fire, then re-arm unless cancelled
                        d.cb.fire(d.id);
                        if (!d.cancelled) {
                            d.expiry = now + d.period;
                            insert(d);
                        }
                    }
                }
            } finally {
                due.clear();
            }
        } finally {
            ticking = false;
        }
    }

    public void advance(long n) {
        for (long i = 0; i < n; i++) tick();
    }

    // ---------------------------------------------------------------- internals

    private void link(int level, int slot, Timer t) {   // push-front into doubly linked list
        Timer head = (level == OVERFLOW) ? overflow : wheel[level][slot];
        t.prev = null;
        t.next = head;
        if (head != null) head.prev = t;
        setHead(level, slot, t);
        t.level = level;
        t.slot = slot;
    }

    private void unlink(Timer t) {
        if (t.prev != null) t.prev.next = t.next;
        else setHead(t.level, t.slot, t.next);
        if (t.next != null) t.next.prev = t.prev;
        detachFields(t);
    }

    private void setHead(int level, int slot, Timer t) {
        if (level == OVERFLOW) overflow = t;
        else wheel[level][slot] = t;
    }

    private static void detachFields(Timer t) {
        t.prev = null;
        t.next = null;
        t.level = NOT_LINKED;
    }

    /** Choose level/slot from the remaining delay and link the timer in. Needs expiry > now. */
    private void insert(Timer t) {
        long delta = t.expiry - now;
        for (int l = 0; l < LEVELS; l++) {
            if (delta < (1L << (BITS * (l + 1)))) {
                link(l, (int) ((t.expiry >>> (BITS * l)) & (SLOTS - 1)), t);
                return;
            }
        }
        link(OVERFLOW, 0, t);
    }

    /** Used during cascades: already-due timers go straight to the fire list. */
    private void place(Timer t) {
        if (t.expiry <= now) due.add(t);
        else insert(t);
    }

    private void cascade(int level, int slotIdx) {
        Timer t = wheel[level][slotIdx];
        wheel[level][slotIdx] = null;
        while (t != null) {
            Timer nx = t.next;
            detachFields(t);
            place(t);
            t = nx;
        }
    }
}

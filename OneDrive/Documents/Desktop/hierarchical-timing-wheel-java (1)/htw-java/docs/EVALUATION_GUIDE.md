# Evaluation Guide (maps to the 5 rubric components)

## 1. Data Structure Justification
**Problem:** millions of pending timers; most are cancelled before firing (timeouts);
the clock moves in discrete ticks.

| Alternative | Why it loses here |
|-------------|-------------------|
| Unsorted list / array | schedule O(1) but tick must scan all n: O(n) per tick |
| Sorted linked list | tick O(1) but schedule O(n) |
| Binary min-heap (`PriorityQueue`) | schedule, cancel O(log n); `remove(Object)` is O(n), so real systems use lazy deletion, which leaves dead entries in the heap |
| Balanced BST (`TreeMap`) | O(log n) everything, heavy nodes, poor cache behaviour |
| **Single (flat) timing wheel** | O(1) but range limited to the slot count; huge delays need huge memory |
| **Hierarchical timing wheel** | O(1) schedule/cancel, amortised O(1) tick, range 64^4 with only 256 slots of memory |

Key insight: time is *bounded-precision and monotonic*. We never need a full sort of events,
only "which bucket is due now", so bucketing by time (like a clock: seconds, minutes, hours)
beats comparison-based structures.

## 2. Implementation Strategy (trade-offs)
- **Slot = hand-written doubly linked list, not an `ArrayList`/`LinkedList` per slot.**
  + O(1) unlink on cancel (an ArrayList would need O(bucket) search and shift)
  + cascading just re-links existing nodes; no copying
  + no wrapper node objects (Java's `LinkedList` allocates a node per element and cannot
    remove a known element in O(1) without an iterator)
  - two extra references per timer, worse cache locality than an array
- **Fixed `Timer[][] wheel = new Timer[4][64]` array of list heads.**
  + index computed by shifts and masks, no allocation for the wheel itself
  - range/precision fixed at compile time (`BITS`, `LEVELS` constants)
- **64 slots (power of two)** so `x % 64` and `x / 64^l` become `& 63` and `>>> (6*l)`.
- **`HashMap<Long, Timer>`** gives O(1) cancel by id. Cost: O(n) memory and `Long` boxing
  (this is why scheduling is slower than a heap's `add` in Java; see benchmark).
- **`level` and `slot` stored inside each `Timer`** (Java has no pointer-to-head like C++),
  so a node can unlink itself, and `level == NOT_LINKED` doubles as "already collected".
- **Collect-then-fire:** due timers are gathered into a list first and callbacks run
  afterwards, so callbacks can safely `schedule` or `cancel` (tested). A `cancelled` flag
  handles a same-tick cancel of an already-collected timer. Calling `tick()` from a
  callback is rejected with `IllegalStateException`.
- **Overflow list** instead of a 5th level: constant memory; rarely used.
- Trade-off of the hierarchy: a timer in an upper level is touched again when cascaded
  (at most LEVELS-1 = 3 extra moves, a constant).

## 3. Live demo checklist
Run `make run`, then show:
1. `demo`: timers at 5, 70, 5000, 300000 fire at exactly those ticks.
2. `add 0` then `tick`: zero delay fires on next tick.
3. `add 100`, `add 100`, `cancel 1`, `tick 100`: only one fires.
4. `cancel 999`: "no such pending timer", no crash.
5. `add 20000000`, `tick 20000000`: overflow-list timer fires.
6. `add abc`, `add -5`, `tick 0`, `foo`: input edge cases handled without crashing.
7. `repeat 40`, `tick 85`: fires at 40 and 80 with the same id; `cancel` stops it.
8. `add 100`, `reschedule 1 10`, `next`, `remaining 1`: timeout refresh and earliest-event lookup.
9. `stats`: shows how many timers sit in each level (good for explaining cascading visually).
10. `make check`: 11 test groups pass, including 20,000 randomized timers and a brute-force check of `nextExpiry`.

### Extra operations: how they work
- **scheduleRepeating:** the same `Timer` node is re-armed after each firing (`expiry = now + period`,
  then `insert`), so the id never changes and cancel keeps working. Cancelling from inside its own
  callback is detected by the `cancelled` flag, so it is not re-armed.
- **reschedule:** unlink the node (O(1) via `level`/`slot`) and `insert` it again with the new
  expiry, possibly into a different level. Same callback and id.
- **nextExpiry:** a timer at level l always sits in a block strictly ahead of the clock, so in each
  level the first non-empty slot (cyclic from the current slot) holds that level's earliest timer.
  The answer is the minimum over the 4 levels plus the overflow list. We must check *every* level,
  because a timer left in an upper level (waiting for its cascade) can expire before a timer that
  was inserted later into level 0. A test compares it with brute force.

## 4. Time Complexity Analysis
Let n = pending timers, L = 4 levels, S = 64 slots, k = timers firing this tick.

- **schedule:** compute delta, loop over at most L levels to pick one (constant), compute the
  slot with shifts/masks, push-front into a list, put into the hash map
  = O(L) + O(1) expected = **O(1)**.
- **cancel:** hash lookup O(1) expected, unlink O(1) = **O(1) expected**.
- **tick:** increment clock, fire level-0 slot = O(1 + k). Cascades are the subtle part.
  Level l is cascaded once every 64^l ticks, and each cascade moves one slot's timers to a
  lower level. **A timer is cascaded at most L-1 times in its lifetime** (each cascade
  strictly lowers its level). So over T ticks with N timers total:
  `work <= T + N*(L-1) + N` = O(T + N), i.e. **amortised O(1) per tick and per timer**.
  Worst single tick: cascading a slot holding m timers costs O(m) (a latency spike; the
  total is still amortised O(1) per timer).
- **Overflow:** re-examined once per 2^24 ticks; each overflow timer is re-examined at most
  `ceil(delay / 2^24)` times, a small constant for realistic delays.
- **reschedule / remaining / isPending:** O(1) expected (hash lookup + unlink/insert).
- **nextExpiry:** scans at most L*S = 256 slot heads, then the list of the first non-empty slot per level: O(L*S + m).
- **Space:** O(n) timers + O(L*S) = O(n + 256) slot heads.

Heap comparison: heap add/poll = O(log n) each, so N timers cost O(N log N) vs O(N)
amortised for the wheel.

### Measured (example run, Java 21; will vary on your machine, quote the 2nd run)
| Workload | Wheel total | PriorityQueue total |
|----------|-------------|---------------------|
| 1M timers, 50% cancelled | ~0.85 s | ~1.0 s |
| 3M timers, 90% cancelled | ~2.4 s | ~4.3 s |

Honest notes to say if asked:
- The wheel's **tick/run phase is much faster** (0.23 s vs 3.8 s at 3M) because it avoids
  O(log n) pops and dead entries.
- Its **schedule phase is slower in Java** (2.2 s vs 0.5 s at 3M) due to `Long` boxing in the
  `HashMap` and one object per timer. Replacing the map with a primitive-keyed array indexed
  by id would remove most of that cost.
- For small n the two are close; the wheel wins as n and the cancel rate grow.

## 5. Individual contribution & GitHub proof
- Each member uses **their own** GitHub account and commits from it (`git config user.name`
  and `user.email` must match that account).
- Suggested split (adjust to your team size):
  - Member A: `HierarchicalTimingWheel.java`: `Timer`, link/unlink helpers, `schedule`, `cancel`
  - Member B: `tick`, `cascade`, overflow logic + complexity write-up
  - Member C: `tests/`, `bench/`, `Main.java`, README
- Make several small, meaningful commits each (e.g. "add Timer node and link/unlink",
  "implement cascade", "add overflow test"), not one big upload.
- Add teammates as collaborators; everyone clones and pushes their own work. Before the
  evaluation run `git log --oneline --author="<name>"` to check your commits.
- Every member should be able to explain **every** file, especially `tick()` and `cascade()`.

## Likely viva questions
1. *Why 64 slots and 4 levels?* Powers of two give bit operations; 6 bits x 4 levels = 24 bits
   of range with only 256 buckets. A flat wheel for 2^24 ticks would need 16M slots.
2. *What happens to a timer of 70 ticks?* delta=70 >= 64 so level 1, slot `(expiry>>>6)&63`.
   When the clock reaches the matching multiple of 64 it cascades to level 0 and fires at
   its exact tick.
3. *Why doubly linked?* O(1) removal on cancel without scanning the bucket.
4. *Why not Java's `LinkedList` or `ArrayList` for slots?* `LinkedList.remove(Object)` is O(n);
   `ArrayList` removal is O(n) too. Our own nodes let a timer unlink itself in O(1).
5. *Worst case for tick?* Cascading a crowded slot, O(m), but amortised O(1) per timer.
6. *Why not a `PriorityQueue`?* O(log n) per op, `remove(Object)` is O(n), lazy deletion bloats it.
7. *What if delay exceeds the range?* Overflow list, re-inserted when the top wheel wraps.
8. *Downsides?* Fixed tick granularity, memory per timer, cascade latency spikes, no
   guaranteed order between timers in the same tick.
9. *Is HashMap O(1) worst case?* No, expected O(1); a primitive array indexed by id would give
   a true O(1) lookup.
10. *Thread safety?* Not thread-safe by design; wrap in a lock or run on a single scheduler thread.

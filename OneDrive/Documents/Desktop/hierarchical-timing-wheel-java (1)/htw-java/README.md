# Hierarchical Timing Wheel (Java)
### Efficient Scheduling of Large-Scale Time-Based Events

DSA Lab Evaluation 3 project. A hierarchical timing wheel manages huge numbers of timers
(timeouts, retries, session expiry, cache TTLs, game/IoT events) with **O(1) schedule,
O(1) cancel and amortised O(1) per tick**, where a binary heap needs O(log n) per operation.

## Team
| Name | Roll No. | GitHub |
|------|----------|--------|
| _member 1_ | | @username |
| _member 2_ | | @username |
| _member 3_ | | @username |

## Problem statement
Given a stream of events that must each fire after a given delay, support:
1. `schedule(delay, callback)`: register an event
2. `cancel(id)`: remove a pending event
3. `tick()`: advance the clock and fire every event that is due

for hundreds of thousands to millions of simultaneous pending events.

## How it works
- 4 wheels x 64 slots. Level `l` has resolution `64^l` ticks/slot, so the wheels cover
  `64^4 = 2^24 = 16,777,216` ticks directly (about 4.6 hours at 1 ms/tick).
- A timer goes into the **lowest level whose range covers its remaining delay**.
- Every slot is a hand-written **doubly linked list** (O(1) insert / unlink).
- When the clock crosses a level boundary, that level's current slot is **cascaded**: its
  timers are re-inserted into finer levels (or fired if already due).
- Timers beyond 2^24 ticks wait in an **overflow list**, re-examined whenever the top wheel wraps.
- A `HashMap<Long, Timer>` gives O(1) cancel by id.

## Build & run (JDK 11+)
```bash
make check          # compile + run all tests
make run            # interactive demo (type: demo)
make bench          # wheel vs PriorityQueue
```
Without `make` (e.g. Windows):
```bash
mkdir out
javac -d out src/htw/*.java tests/htw/*.java bench/htw/*.java
java -cp out htw.TestWheel
java -cp out htw.Main
java -Xmx3g -cp out htw.Benchmark 1000000 50
```

## Operations
| Operation | Meaning | Complexity |
|-----------|---------|------------|
| `schedule(delay, cb)` | one-shot timer | O(1) |
| `scheduleRepeating(interval, cb)` | recurring timer, same id every period | O(1) per arm |
| `cancel(id)` | remove a pending timer | O(1) expected |
| `reschedule(id, newDelay)` | extend / shorten a pending timer (session timeout refresh) | O(1) expected |
| `remaining(id)` / `isPending(id)` | query a timer | O(1) expected |
| `tick()` / `advance(n)` | move the clock and fire due timers | amortised O(1) per tick |
| `nextExpiry()` | absolute tick of the earliest pending timer | O(L*S + m), at most 256 slot heads + one slot list per level |
| `countsPerLevel()` | how many timers sit in each level / overflow | O(n) |
| `clear()` | cancel everything | O(n) |

### Interactive commands
`add <delay>` | `repeat <interval>` | `cancel <id>` | `reschedule <id> <delay>` | `remaining <id>` |
`next` | `stats` | `clear` | `tick [n]` | `status` | `demo` | `help` | `quit`

## Project layout
```
src/htw/HierarchicalTimingWheel.java   the data structure
src/htw/TimerCallback.java             functional interface for callbacks
src/htw/Main.java                      interactive CLI for the live demo
tests/htw/TestWheel.java               11 test groups: correctness, edge cases, randomized, brute-force cross-checks
bench/htw/Benchmark.java               wheel vs PriorityQueue
docs/EVALUATION_GUIDE.md               justification, complexity proof, viva prep
```

## Complexity summary
| Operation | Hierarchical wheel | Binary heap |
|-----------|--------------------|-------------|
| schedule  | O(1) | O(log n) |
| cancel    | O(1) expected | O(log n) / lazy deletion |
| tick      | amortised O(1) + O(k) for k fired | O(k log n) |
| space     | O(n + L*S) | O(n) |

See `docs/EVALUATION_GUIDE.md` for the full proof.

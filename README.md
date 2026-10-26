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

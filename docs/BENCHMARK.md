# Benchmark Plan

## 1. Research question

Does client-assisted terrain computation reduce server world-generation cost enough to justify:

- serialization
- network transfer
- synchronization
- result application
- failures/timeouts?

Measure before optimizing.

---

## 2. Primary metrics

### Server cost

```text
server CPU time / chunk
server wall time / chunk
worldgen worker saturation
tick health during exploration
```

### Remote path

```text
job preparation ms
server encode ms
request bytes
network RTT
client queue wait ms
client compute ms
client encode/compress ms
response bytes
server decode/decompress ms
validation ms
apply ms
total wall latency
```

### Reliability

```text
success rate
timeout rate
fallback rate
invalid result rate
```

---

## 3. Important distinction

A remote path can be useful even when:

```text
remote wall latency >= local wall latency
```

if it substantially reduces:

```text
server CPU contention
```

Therefore report both latency and server CPU savings.

---

## 4. Benchmark modes

At minimum:

```text
VANILLA
LOCAL_BACKEND
REMOTE_BACKEND
```

Later:

```text
REMOTE_UNCOMPRESSED
REMOTE_COMPRESSED
REMOTE_SPECULATIVE_CACHE_HIT
REMOTE_SPECULATIVE_CACHE_MISS
```

---

## 5. Workloads

### Single chunk

Useful for debugging overhead.

### Sequential exploration

Generate a line/ring of new chunks.

### Multi-direction exploration

Better resembles players moving independently.

### Burst

Request many new chunks quickly.

### Multi-player

Only after single-player remote path is stable.

---

## 6. Warm-up

Java/JIT makes early iterations unrepresentative.

Benchmark procedure should include:

```text
warm-up chunks
measured chunks
repeated runs
```

Do not report only the first run.

Disable deterministic stage digesting during performance runs. The
`WORLDGEN_ASSIST_NOISE_DIGEST` environment variable and
`worldgen_assist.noise_digest` JVM property must both be unset/false because
digest mode intentionally holds the NOISE future open while hashing.

---

## 7. CSV schema

Suggested:

```csv
timestamp,mode,run_id,chunk_x,chunk_z,server_prepare_ms,server_local_compute_ms,client_queue_ms,client_compute_ms,encode_ms,request_bytes,response_bytes,rtt_ms,decode_ms,validate_ms,apply_ms,total_ms,fallback,result
```

Avoid storing raw world seed in public benchmark data unless intentional.

---

## 8. Summary statistics

Report at minimum:

```text
count
mean
median
p95
p99
standard deviation
timeouts
```

For throughput:

```text
chunks/sec
```

For server load:

```text
server CPU time saved/chunk
```

---

## 9. Compression decision

Measure:

```text
compression ratio
compression CPU
decompression CPU
saved transfer time
```

LAN may favor low/no compression.

Internet connections may favor compression.

Make it data-driven.

---

## 10. Offload decision model

Later the scheduler can estimate:

```text
expected_remote_cost
  = queue
  + client_compute
  + network
  + decode
  + apply
  + expected_fallback_penalty
```

And compare with server pressure / local estimate.

Do not use only ping.

---

## 11. Benchmark reproducibility

Record:

- Minecraft version
- mod commit
- Java version
- Fabric Loader/API/Loom versions
- CPU model
- server/client machine relationship
- network type
- view distance
- simulation distance
- worldgen datapacks
- JVM flags
- test world configuration

---

## 12. Phase gates

### Gate 1

Instrument vanilla and identify meaningful worldgen cost.

### Gate 2

The backend-selection seam adds negligible architectural overhead and stays
deterministic.

Gate 2 passed on 2026-09-02 for the `delegate` baseline described in Section
21. It crosses the project abstraction while returning the exact captured
vanilla executor. Canonical digests matched at all 331 observed coordinates,
and three fresh 648-task pairs did not distinguish its overhead from run noise.
This result validates the seam only; it is not evidence that moving work to a
different executor is free.

The dedicated `local` pool remains rejected. Its deterministic checks passed,
but the FIFO, fixed-window ForkJoin, repeated tick/CPU, and worker-proportional
admission measurements in Sections 15 through 20 all found material latency or
fallback costs. Phase 2 may proceed with immutable context and protocol
scaffolding, while any future scheduling policy must earn its own benchmark
result.

### Gate 3

Remote backend demonstrates server CPU savings on LAN.

### Gate 4

Phase 3 lossless compression and bounded cache infrastructure are implemented.
The runtime sample in Section 22 establishes their wire-size and CPU-cost
baseline; a controlled LAN comparison and useful cache-hit rate remain required
before claiming a performance win.

### Gate 5

Speculative generation evaluated separately from direct on-demand offload.

## 13. Current Phase 1 backend modes

Run the zero-scheduling-change delegation baseline with:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BACKEND = "delegate"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
.\gradlew.bat runServer --args nogui
```

This mode selects the project backend abstraction, then returns the exact
captured vanilla `wgen_fill_noise` executor object. It adds no wrapper executor,
worker, queue, or lifecycle resource.

Run the experimental dedicated-pool topology with:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BACKEND = "local"
$env:WORLDGEN_ASSIST_LOCAL_WORKERS = "1"
$env:WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER = "1"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
.\gradlew.bat runServer --args nogui
```

Record the worker count and queue-per-worker ratio with every result. Workers
accept `1..64`; queued tasks per worker accept `0..16` and default to `1`.
Queue capacity is `workers * queue-per-worker`, and total local admission is
`workers * (1 + queue-per-worker)`. This mode currently moves the exact vanilla
calculation task to a project-owned server-process thread pool; it does not
serialize work or reduce total server-machine CPU use. It remains a research
mode because its measured policies failed Gate 2.

Unset the backend variable or select `vanilla` for the unchanged default path.

## 14. Phase 1 executor benchmark instrumentation

Enable queue/compute/CPU timing in both comparison modes:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BENCHMARK = "true"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
```

The equivalent JVM property is
`worldgen_assist.noise_benchmark=true`. The wrapper records:

```text
scheduled_nanos
started_nanos
completed_nanos
requested_backend = vanilla | delegate | local
execution = vanilla | vanilla_delegate | local_pool | vanilla_fallback
queue_ms = started - scheduled
compute_ms = completed - started
cpu_ms = current worker-thread CPU consumed during supplier execution
```

`cpu_ms` uses the JVM `ThreadMXBean` current-thread counter. If that counter is
unsupported or disabled, the log records `-1` and the summarizer excludes the
value from CPU statistics. This metric does not include work performed by
other threads or server tick CPU outside the supplier.

Insert a unique server log marker after warm-up and before the measured
workload. The current fixture uses `CAWG_BENCHMARK_MEASURED_BEGIN`. Summarize a
matched pair with:

```powershell
.\scripts\Summarize-NoiseBenchmark.ps1 `
  -VanillaLog run\benchmarks\my-run\vanilla.log `
  -LocalLog run\benchmarks\my-run\local.log `
  -OutputDirectory run\benchmarks\my-run\results `
  -RunId my-run
```

The script emits raw joined records, coordinate-paired comparisons, mode
summaries, and `noise-stage-route-summary.csv`. It validates routed records and
counts vanilla-delegate, local-pool, and vanilla-fallback tasks independently,
so a fallback-heavy mixed run cannot be reported as pure local execution. It
rejects digest-enabled runs and incomplete or mismatched logs. Legacy logs
without `execution` or `cpu_ms` remain readable; missing execution routes are
marked as legacy and missing CPU samples are excluded.
Its p95/p99 values use nearest rank; standard deviation is population standard
deviation. `workload_span_ms` is the interval from the earliest measured task
submission to the latest completion, and throughput is measured task count
divided by that interval.

The fixed eight-position fixture can be run safely with:

```powershell
.\scripts\Run-NoiseBenchmarkFixture.ps1 `
  -Mode delegate `
  -WorldName cawg-delegate-example `
  -OutputLog run\benchmarks\example\delegate.log
```

The runner requires a fresh world, seed `8675309`, JDK 25.0.4, and an output
under `run/benchmarks`. It enables task/tick timing, waits for exactly 648
measured records, stops the dedicated server normally, and restores
`run/server.properties` in `finally`. Existing worlds and evidence files are
never overwritten.

## 15. Initial FIFO executor result — 2026-09-01

### Environment and workload

```text
Minecraft: 26.2
mod: 0.1.0 working tree
Fabric Loader: 0.19.3
Fabric API: 0.156.0+26.2
Fabric Loom: 1.17.20
Gradle: 9.5.1
Java: 25.0.4
OS: Windows NT 10.0.26200.0
CPU: Intel 12th Gen Core i7-12700K, 20 logical processors
vanilla workers observed: 19
local workers configured: 19
view distance: 10
simulation distance: 10
dimension: minecraft:overworld
seed: 8675309
datapacks: vanilla/default Fabric development runtime
JVM memory: Gradle/Loom development defaults
```

Each mode used a fresh world. Spawn generation supplied 25 warm-up NOISE tasks.
After the marker, eight widely separated forced chunks produced the same 648
measured dependency coordinates in each mode. Run order was vanilla/local,
local/vanilla, then vanilla/local. Digesting was disabled.

### Per-run summaries

```text
run  mode    stage mean  queue mean  compute mean  span/648   throughput
 1   vanilla    23.52 ms     2.98 ms      20.38 ms  5054.93 ms  128.19/s
 1   local      43.38 ms    22.12 ms      21.10 ms  4921.53 ms  131.67/s
 2   vanilla    21.25 ms     2.82 ms      18.29 ms  4865.50 ms  133.18/s
 2   local      85.13 ms    62.30 ms      22.49 ms  4958.50 ms  130.68/s
 3   vanilla    24.92 ms     3.44 ms      21.32 ms  5270.64 ms  122.95/s
 3   local      61.98 ms    38.22 ms      23.55 ms  5030.56 ms  128.81/s
```

Every mode/run contained 648 successful stage records and 648 matching task
records. Stage failures, task failures, and local fallback submissions were all
zero.

### Interpretation

The mean 648-task workload span was 5063.69 ms for vanilla and 4970.20 ms for
local; mean throughput was 128.11 and 130.39 tasks/s respectively. Those burst
metrics are effectively comparable for this small three-run development-host
sample and do not establish a local speedup.

Per-task latency was not comparable. The mean of stage means increased from
23.23 ms to 63.50 ms. Mean supplier compute time increased modestly from 20.00
ms to 22.38 ms, while mean queue wait increased from 3.08 ms to 40.88 ms. The
queue difference explains most of the stage-latency difference.

Generated Minecraft source shows that vanilla submits to a shared 19-worker,
asynchronous-mode `ForkJoinPool`; this initial prototype submitted to a dedicated
19-worker `ThreadPoolExecutor` with one bounded FIFO queue. The measurement is
evidence that matching worker counts and aggregate throughput is insufficient:
that scheduler changed individual completion latency materially.

Detailed disposable evidence is retained under
`run/benchmarks/phase1-executor-detail-20260901/`, which is excluded from Git.

## 16. Historical fixed-window async ForkJoin result — 2026-09-01

The local executor was revised to a dedicated `ForkJoinPool` with
`asyncMode=true`. At the time of this historical measurement, a non-blocking
semaphore bounded admission to the configured worker count plus 1024 waiting
slots; a full admission window fell back to the captured vanilla executor
before the command began. Section 20 replaces that disproportionate fixed
window. Benchmark logs identify the schedulers as
`minecraft_shared_fork_join_async` for vanilla and `fork_join_async` for local
mode.

The environment and 648-task workload matched Section 15. Three fresh pairs
were run in vanilla/local, local/vanilla, then vanilla/local order, with
digesting disabled.

```text
run  mode    stage mean  queue mean  compute mean  span/648   throughput
 1   vanilla    20.78 ms     3.06 ms      17.48 ms  4531.10 ms  143.01/s
 1   local      36.13 ms    17.16 ms      18.56 ms  4946.53 ms  131.00/s
 2   vanilla    20.84 ms     2.90 ms      17.80 ms  4874.91 ms  132.93/s
 2   local      46.46 ms    24.88 ms      21.40 ms  5361.25 ms  120.87/s
 3   vanilla    24.46 ms     3.18 ms      21.13 ms  4983.20 ms  130.04/s
 3   local      50.17 ms    27.20 ms      22.76 ms  5180.79 ms  125.08/s
```

Every mode/run contained 648 successful matched stage/task records. Stage
failures, task failures, and local fallback submissions were zero. The mean of
run means was:

```text
metric                              VANILLA   LOCAL_BACKEND
stage elapsed (ms/task)               22.03           44.26
supplier queue wait (ms/task)          3.05           23.08
supplier compute (ms/task)            18.80           20.91
workload span (ms/648 tasks)        4796.40         5162.86
throughput (tasks/s)                  135.32          125.65
```

A one-pair screening sweep used fresh local worlds at 8, 19, and 32 workers.
Their throughputs were 124.22, 131.00, and 106.65 tasks/s respectively; 19 was
therefore retained for the repeated fixture. Eight workers accumulated queue
wait, while 32 workers showed over-parallelization on this 20-logical-processor
host.

Compared directionally with the separate historical FIFO series, the revised
local scheduler reduced mean queue wait from 40.88 ms to 23.08 ms and mean
stage elapsed from 63.50 ms to 44.26 ms. This is not a paired comparison across
the two implementations. Within the current paired series, local stage latency
remained about twice vanilla's and throughput was about 7.2% lower. A dedicated
pool still competes with vanilla's shared executor for the same CPUs while
upstream generation work continues. Determinism passes, but the performance
part of Gate 2 remained open at this point; Section 19 later records that the
current policy fails it. Phase 2 should not treat this as a
performance-equivalent local baseline.

Detailed evidence is retained under
`run/benchmarks/phase1-forkjoin-20260901/`; worker-screening evidence is under
`run/benchmarks/phase1-forkjoin-screen-20260901/` (both excluded from Git).

## 17. Worker CPU-time instrumentation smoke — 2026-09-01

The `cpu_ms` extension was runtime-checked with an explicit JDK 25.0.4 pair.
After the spawn warm-up marker, forcing block position `(2048,2048)` produced
81 matched NOISE-stage/task coordinates in each fresh world. Both modes had
zero stage/task failures, the local backend had zero fallbacks, and all 81
records per mode contained supported current-thread CPU readings.

```text
one-pair smoke metric                 VANILLA   LOCAL_BACKEND
stage elapsed mean (ms/task)            146.09          406.78
queue wait mean (ms/task)                 26.70          205.42
compute wall mean (ms/task)              113.76          190.82
worker CPU mean (ms/task)                 18.33           14.08
workload span (ms/81 tasks)             2015.85          918.66
throughput (tasks/s)                       40.18           88.17
```

These numbers are not a performance result. On this Windows host the CPU
counter was quantized to `0`, `15.625`, `31.25`, `46.875`, and `62.5` ms;
vanilla had 21 zero samples and local had 29. The pair was not repeated, and
the concurrent burst makes per-task wall latency and workload span describe
different aspects of the run. Both modes emitted one Minecraft
`Can't keep up!` warning (3155 ms vanilla, 3618 ms local), which is too coarse
for a tick-health conclusion. The smoke establishes log/CSV plumbing and the
counter's host-specific resolution limit. Repeated aggregate CPU and per-tick
duration/saturation data were still outstanding at this point; Sections 18 and
19 add and exercise that instrumentation.

Evidence is retained under
`run/benchmarks/phase1-cpu-smoke-20260901/` (excluded from Git).

## 18. Server-tick and saturation instrumentation — 2026-09-01

Enable the independent tick logger for a disposable comparison run:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BENCHMARK = "true"
$env:WORLDGEN_ASSIST_TICK_BENCHMARK = "true"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
```

The equivalent JVM property is `worldgen_assist.tick_benchmark=true`. The
logger uses Fabric `START_SERVER_TICK` and `END_SERVER_TICK` events and is off
by default. Inspection of the local Fabric API 0.156.0+26.2 source found that
its `MinecraftServerMixin` fires START immediately before
`MinecraftServer.tickChildren(BooleanSupplier)` and END at the tail of
`tickServer`. Therefore `elapsed_ms` is that Fabric event interval, not the
entire `tickServer` invocation. No additional Worldgen Assist Mixin is used.

Each `[CAWG] tick.complete` record includes a 50 ms budget classification,
NOISE completions/failures since the previous tick end, NOISE active counts at
the current start/end, and local-pool point samples. Completion deltas use the
previous END snapshot as their baseline so completions between END and the next
START are assigned to the next record. Local mode additionally resets and
reports per-interval peaks for exact active tasks and admitted tasks; this
captures a burst that starts and finishes before the end sample.

Summarize a matched log pair with:

```powershell
.\scripts\Summarize-TickBenchmark.ps1 `
  -VanillaLog run\benchmarks\my-run\vanilla.log `
  -LocalLog run\benchmarks\my-run\local.log `
  -OutputDirectory run\benchmarks\my-run\tick-results `
  -RunId my-run
```

The script rejects digest-enabled, unmarked, wrong-backend, or unequal
NOISE-count pairs. It writes all post-marker records and summarizes ticks with
NOISE completion, failure, or active work. Percentiles use nearest rank and
standard deviation is population standard deviation.

An explicit JDK 25.0.4 one-pair smoke used fresh worlds, seed `8675309`, and a
post-warm-up forced chunk at block position `(2048,2048)`. Both modes reported
81 NOISE completions and zero failures; local used 19 workers and had zero
fallbacks.

```text
one-pair smoke metric                 VANILLA   LOCAL_BACKEND
NOISE-active tick count                     8               1
ticks over 50 ms                             1               1
over-budget share                        12.5%          100.0%
active-tick mean (ms)                   418.20         3540.23
active-tick p95 (ms)                   3342.04         3540.23
measured active window (ms)            4163.15         3540.23
maximum NOISE active point sample           20               0
local interval peak active tasks             -              19
local interval peak admitted tasks           -              61
```

The zero local end-point samples alongside interval peaks of 19 active and 61
admitted tasks verify why interval-peak instrumentation is required. This
single forced-chunk pair is an instrumentation smoke, not a performance result:
the modes exposed different active-tick counts, were not repeated, and the
command concentrated local completions into one long tick. Gate 2 remained
unresolved at this smoke stage; the repeated, order-balanced workload in
Section 19 supplies the next decision evidence. Evidence is retained under
`run/benchmarks/phase1-tick-smoke-20260901/` (excluded from Git).

## 19. Repeated tick/CPU Gate 2 fixture — 2026-09-02

The tick logger was then exercised with the same 648-task fixture used for the
revised executor result. After spawn warm-up and the marker, these eight block
positions were force-loaded in order:

```text
(1024,1024)   (-1024,1024)   (1024,-1024)   (-1024,-1024)
(3072,512)    (-3072,512)    (512,3072)     (512,-3072)
```

Their center chunks are `(±64,±64)`, `(±192,32)`, and `(32,±192)`; each
position produced 81 distinct measured NOISE coordinates. Three fresh-world
pairs used seed `8675309`, 19 local workers, JDK 25.0.4, and run order
vanilla/local, local/vanilla, then vanilla/local. Digesting was disabled. Each
mode/run contained 648 matching stage/task records with supported CPU samples,
zero failures, and zero local fallbacks.

The pair summaries were combined with:

```powershell
.\scripts\Summarize-BenchmarkSeries.ps1 `
  -RunDirectories run\benchmarks\my-series\run-1, `
                  run\benchmarks\my-series\run-2, `
                  run\benchmarks\my-series\run-3 `
  -OutputDirectory run\benchmarks\my-series\series-results `
  -SeriesId my-series
```

The series script validates run IDs, both modes, matched NOISE/tick completion
counts, and active-tick row counts. It emits per-run, pooled active-tick,
mode-summary, and comparison CSVs.

```text
mean of run means / pooled ticks       VANILLA   LOCAL_BACKEND
NOISE tasks                               1,944           1,944
stage elapsed (ms/task)                    23.69           50.83
supplier queue wait (ms/task)               3.11           28.72
supplier compute wall (ms/task)            20.45           21.94
worker CPU (ms/task)                       16.81           16.25
workload span (ms/648 tasks)             5026.59         4887.95
throughput (tasks/s)                      129.16          132.60
NOISE-active ticks                            54              40
ticks over 50 ms                               3               4
over-budget share                           5.56%          10.00%
pooled active-tick mean (ms)               226.70          325.69
pooled active-tick p95 (ms)               3895.05         4178.07
maximum local active/admitted peak              -          19/137
```

The 27.14 ms stage-mean increase is dominated by a 25.60 ms queue-wait
increase. Local stage latency was 2.15 times vanilla. Local throughput was
2.7% higher in this series, whereas the prior three-pair series was 7.2% lower;
there is no stable throughput advantage. The worker CPU difference was only
`-0.55 ms/task` and the Windows counter remains quantized to 15.625 ms. It also
excludes upstream work and other server threads, so it is not evidence of
server CPU savings.

Tick summaries are conditional on ticks carrying measured NOISE work; their
sample counts differ by mode and should not be read as a general TPS
distribution. Even with that limitation, local produced four over-budget ticks
versus three, a 43.7% higher pooled active-tick mean, and a 7.3% higher pooled
p95. The current dedicated-pool backend therefore fails the performance/tick
part of Gate 2. Determinism remains passed, but Phase 2 must not advance using
this backend as a negligible-overhead baseline. Evidence is retained under
`run/benchmarks/phase1-tick-cpu-20260902/` (excluded from Git).

## 20. Worker-proportional admission redesign — 2026-09-02

The fixed 1024-slot wait window was disproportionate to small worker counts and
allowed the 19-worker fixture to reach an interval peak of 137 admitted tasks.
The backend now derives queue capacity from the worker count:

```text
queue capacity     = workers * queued tasks per worker
admission capacity = workers + queue capacity
default ratio      = 1 queued task per worker
accepted range     = 0..16
```

Admission remains non-blocking. A command that cannot acquire capacity, or is
rejected before it starts, is sent unchanged to the captured vanilla executor.
Fallback warnings are emitted for the first fallback and every 64th fallback;
exact counts come from the task `execution` route and tick/shutdown counters.

A fresh-world one-pair screen held the seed, 19 workers, eight-position
648-task workload, JDK 25.0.4, and benchmark flags constant while varying only
the waiting ratio. These are noisy screening runs rather than repeated
performance estimates:

```text
queued/worker  capacity (queue/total)  fallbacks       stage mean  queue mean
0                            0 / 19    159 / 648  24.54%     25.35 ms     2.88 ms
1                           19 / 38     60 / 648   9.26%     33.59 ms    10.10 ms
2                           38 / 57     76 / 648  11.73%     45.13 ms    18.97 ms
4                           76 / 95      3 / 648   0.46%     55.12 ms    30.21 ms
```

The ratio-0 result is not a local speedup: almost one quarter of its tasks ran
on vanilla. Conversely, the ratio-4 run nearly eliminated fallback but restored
substantial queue latency. The non-monotonic ratio-1/ratio-2 fallback counts
also show why a single screening run must not be over-interpreted. The sweep
exposes a latency/fallback frontier; none of these mixed results passes Gate 2.

After adding exact route attribution and rate-limiting fallback warnings, the
default ratio `1` was rerun against a routed vanilla log:

```text
final routed metric                      VANILLA   LOCAL_BACKEND
NOISE tasks                                  648             648
actual local-pool route                        0             586
actual vanilla-fallback route                  0              62 (9.57%)
stage elapsed mean (ms/task)               22.93           40.66
supplier queue wait (ms/task)               3.17           11.62
supplier compute wall (ms/task)            19.62           28.81
worker CPU (ms/task)                       17.17           17.12
workload span (ms/648 tasks)             4903.45         5012.06
throughput (tasks/s)                      132.15          129.29
NOISE-active ticks                            19              16
ticks over 50 ms                               1               2
over-budget share                           5.26%          12.50%
active-tick mean/p95 (ms)          216.93/4104.42  268.25/4226.37
maximum local active/admitted peak              -           19/38
```

Within the mixed local-mode run, the 586 local-pool tasks averaged 37.25 ms per
stage and the 62 vanilla-fallback tasks averaged 72.97 ms. Fallback therefore
did not hide a successful local result; contention made that route particularly
slow. Aggregate local-mode stage mean was 17.74 ms worse than vanilla and
throughput was 2.2% lower. The proportional bound is a material operational
improvement over the fixed window, but the current same-machine dedicated-pool
shape still fails Gate 2. Section 21 supplies the different, exact-vanilla-
executor baseline used to validate the abstraction itself. Further dedicated-
pool tuning must not rely on vanilla fallback for apparent latency gains.

Evidence is retained under
`run/benchmarks/phase1-admission-screen-20260902/` (excluded from Git).

## 21. Vanilla-delegating Gate 2 baseline — 2026-09-02

### Design and deterministic check

The `delegate` backend traverses configuration and backend selection, validates
the same arguments as other backends, and returns the exact executor captured
from vanilla's `CompletableFuture.supplyAsync` call. The original supplier and
executor object then enter the original call unchanged. There is no executor
wrapper, task resubmission, extra future, queue, worker, or lifecycle resource.
Runtime logs identify this route as `execution=vanilla_delegate` and its
scheduler as `minecraft_shared_fork_join_async`.

A fresh seed-`8675309` deterministic world used the established four target
chunks. It produced 331 successful NOISE-stage digests. All 331 coordinates and
all canonical format-1 SHA-256 digests matched the independent vanilla
baseline; there were no failures or mode-only coordinates. Evidence is retained
in `run/test-results/determinism-delegate.log` (excluded from Git).

### Repeated fixture

Three fresh 648-task pairs used the Section 15 host/configuration and alternated
order: vanilla/delegate, delegate/vanilla, vanilla/delegate. Digesting was off;
task and tick timing were on. Every run contained 648 matched stage/task
records with zero failures. All 1,944 delegate tasks used
`execution=vanilla_delegate`; no dedicated-pool or fallback route appeared.

```text
run  mode      stage mean  queue mean  compute mean  span/648   throughput
 1   vanilla      21.55 ms     2.92 ms      18.35 ms  4918.75 ms  131.74/s
 1   delegate     26.09 ms     3.14 ms      22.75 ms  5254.90 ms  123.31/s
 2   vanilla      23.51 ms     3.43 ms      19.91 ms  5913.08 ms  109.59/s
 2   delegate     25.90 ms     3.13 ms      22.61 ms  5274.30 ms  122.86/s
 3   vanilla      27.10 ms     3.37 ms      23.51 ms  5191.86 ms  124.81/s
 3   delegate     23.86 ms     2.89 ms      20.76 ms  5271.99 ms  122.91/s
```

The mean of run means was:

```text
metric                                      VANILLA       DELEGATE      delta
stage elapsed (ms/task)                       24.054         25.283     +1.229
supplier queue wait (ms/task)                  3.237          3.053     -0.183
supplier compute wall (ms/task)               20.588         22.042     +1.455
worker CPU (ms/task)                          15.770         17.425     +1.656
workload span (ms/648 tasks)                5341.231       5267.064    -74.168
throughput (tasks/s)                         122.046        123.029     +0.983
measurement window (ms)                    5715.798       5616.919    -98.879
NOISE-active ticks                                50             50          0
ticks over 50 ms                                   3              3          0
over-budget share                                6.0%           6.0%         0
pooled active-tick mean (ms)                  260.121        265.753     +5.633
pooled active-tick p95 (ms)                  4066.876       4238.654   +171.779
maximum NOISE active                               22             27         +5
```

Delegate-minus-vanilla stage-mean deltas were `+4.538`, `+2.390`, and
`-3.242` ms/task. Their sign reversal and 4.018 ms sample standard deviation,
together with the unchanged queue, span, throughput, and active/over-budget
tick counts, mean this fixture cannot distinguish seam overhead from run noise.
The observed compute/CPU differences cannot be attributed to different task
scheduling because both modes used the identical supplier mechanics and exact
same executor object.

Gate 2 therefore passes for the backend-selection seam. The result does not
rescue the dedicated `local` pool or predict remote performance. At the end of
Phase 1 it permitted Phase 2's immutable protocol work to begin while
preserving vanilla fallback; remote execution, validation, and application
still required their own later evidence.

Detailed evidence is retained under
`run/benchmarks/phase1-delegate-20260902/` (excluded from Git).

## 22. Phase 3 compressed-transport runtime sample — 2026-09-03

This is an implementation/runtime validation sample, not a controlled paired
performance benchmark. A real Minecraft 26.2 server/client run received 21
standard-height results through protocol version 2. Every density envelope chose
DEFLATE over RAW:

```text
metric                                  min       mean        max
encoded response density bytes      307,123    321,450    333,518
compression ratio                     0.3905     0.4087     0.4241
client density compute (ms)             4.72       8.25      30.11
client encode/compress (ms)              8.10       9.69      16.91
server decode/decompress (ms)             1.88       2.49      10.11
response RTT (ms)                        39.71      94.92     519.23
```

Each raw density field was 786,432 bytes, so observed response density bytes
fell by about 57.6%–60.9%. `estimated_transfer_ms` is the residual of RTT after
client compute, client encode, and server decode; it includes scheduling and
packet transit and must not be interpreted as pure network latency.

Twenty jobs reached remote application. Seven had coordinates present in the
independent local digest baseline and matched exactly (`7/7`, zero mismatches).
The cache stored results and lifecycle invalidation was observed, but the
direct-demand fixture did not produce a meaningful cache-hit latency sample;
that comparison belongs with Phase 4 speculative scheduling.

Evidence is retained in ignored files under `run/test-results/`, principally
`phase3-compressed-determinism-matched-20260903.log` and
`phase3-compression-cache-runtime-20260903.log`.

## 23. Phase 4 prediction/cache-hit runtime sample — 2026-09-03

This is a functional runtime sample, not a controlled performance benchmark.
With one accepted client, one in-flight slot, a 16-entry cache, a 20-tick
prediction interval, and an eight-chunk lead, three completed predictions were
later requested by the real NOISE pipeline:

```text
chunk      cache-path completion (ms)    canonical local match
0,17                            16.03    yes
-1,18                           15.93    yes
0,18                            31.70    yes
```

All three completed as `source=cache`, and all three complete format-1 NOISE
digests matched the independent local-only seed-`8675309` world (`3/3`). This
confirms a non-zero useful-hit sample and deterministic reuse. The fixture did
not match cache-enabled prediction against identical local/remote trajectories,
warmup, CPU samples, or server tick distributions, so these three timings alone
must not be used to claim a net performance benefit. Section 24 records the
later matched directional screen used to begin the opt-in Phase 5 experiment.

Evidence is retained in ignored files:

```text
run/test-results/phase4-prediction-cache-hit-20260903.log
run/test-results/phase4-prediction-local-baseline-20260903.log
```

## 24. Phase 4 matched prediction screen — 2026-09-03

This directional screen held seed `8675309`, one worker, the eight measured
target chunks (`36,48,60,72,84,96,108,120` on Z=0), and two warm-up chunks
constant. Prediction used an eight-chunk lead, one in-flight slot, a 64-entry
cache, and ten-tick observations. All eight measured predicted results were
complete cache hits before demand; the local run generated the same targets
without remote mode.

```text
per-target mean                         prediction/cache      local       delta
complete NOISE stage (ms)                    13.2232         14.2917     -7.48%
supplier queue wait (ms)                     1.1624          1.2084     -3.81%
supplier compute wall (ms)                  10.2109         12.9642    -21.24%
worker CPU (ms)                              7.8125         13.6719    -42.86%
```

The Windows thread-CPU counter is quantized at approximately 15.625 ms and the
sample is only eight chunks, so especially the CPU delta is not a production
estimate. This matched screen provided enough directional evidence to begin
the separately opt-in Phase 5 validation experiment; it does not establish
general throughput, TPS, multi-player benefit, or a release performance claim.

Evidence is retained under
`run/benchmarks/phase4-prediction-screen-20260903/`, including
`matched-targets.csv`, `prediction-run2.log`, and `local-run3-warmed.log`.

## 25. Phase 5 validation runtime cost sample — 2026-09-03

With eight of 768 standard Overworld cells selected, each job recomputed 1,024
of 98,304 density values. Four honest-client jobs passed bit-exact validation:

```text
invocation                    1        2        3        4
validation time (ms)       18.9473   6.4522   6.2044   6.2220
```

The first invocation includes cold initialization. The three following values
span `6.2044..6.4522 ms` with a `6.2929 ms` mean. This small runtime sample
measures the new validation boundary but is not paired enough to select an
operator default; validation therefore remains disabled by default.

The adversarial companion run changed every density by one representable step.
It was rejected at the first sampled mismatch, then used local fallback. Its
full fallback latency is not comparable to the honest path and is excluded from
the overhead mean. Evidence is retained under
`run/benchmarks/phase5-validation-20260903/`.

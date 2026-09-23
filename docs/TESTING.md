# Testing Strategy

This is a historical testing strategy. For current selected runs and results,
use `VALIDATION_MATRIX.md` and `TEST_RESULTS_LATEST.md`.

The 2026-09-05 checkpoint `test-artifacts/20260905-133450-551/` passed
Phase A-D on JDK 25.0.4 with **224 tests / 48 suites**, zero failures/errors/skips.
This verifies the inactive seeded-leaf orchestration and vanilla continuation,
the canonical pre-record geometry guard, expanded lifecycle assertions, and
three-public-seed standard-height RAW/DEFLATE complete chains. Full-height bit
equality uses a separate full authoritative traversal; executor validation
samples 64/768 cells. No new Fabric payload/Mixin/live runtime caller was enabled,
and no remote PC was used. Exact evidence and remaining gaps are in
`TEST_RESULTS_LATEST.md`; repeatable commands are automated by
`scripts/Run-SeededLeafVerification.ps1`; current selection rules are in
`VALIDATION_MATRIX.md`.
Historical checkpoints below retain the counts and limitations of their dates.

## 1. Testing layers

Use multiple layers.

```text
Pure unit tests
      |
Protocol/codec tests
      |
Worldgen deterministic tests
      |
GameTest / runtime tests
      |
Dedicated server + client integration
      |
Performance benchmarks
```

---

## 2. Pure unit tests

Good candidates:

- job state machine
- ID allocation
- timeout policy
- cache keys
- compression helpers
- palette codec
- bounds checking
- EWMA worker metrics
- scheduler decisions

These should not require launching a full game where avoidable.

Fabric provides Fabric Loader JUnit support for mod-aware unit testing.

---

## 3. Protocol tests

For each payload:

- encode/decode round trip
- min values
- max legal values
- too-large lengths
- truncated data
- unsupported version
- unknown result format
- duplicate job result
- stale context ID

Add explicit tests for decompression output limits.

---

## 4. Deterministic worldgen tests

Core requirement.

For fixed world configuration:

```text
Vanilla generation
        |
 canonicalize
        |
      digest A

Assisted generation
        |
 canonicalize
        |
      digest B
```

Require:

```text
A == B
```

Test coordinates should include:

```text
0,0
positive coordinates
negative coordinates
large coordinates
ocean
mountainous terrain
caves
structure-influenced area
```

The exact seed/coordinates can be committed as test fixtures if seed secrecy is not relevant to the test repository.

---

## 5. Stage-level versus final equivalence

Test both when possible.

### Stage-level

Compare immediately after the offloaded stage.

Useful for debugging.

### Final chunk

Continue remaining vanilla stages and compare the final generated result.

This catches downstream assumptions that a stage-level comparison may miss.

---

## 6. Canonical chunk digest

Avoid depending on unstable Java object serialization.

Create a deterministic digest from relevant state.

Potential inputs:

```text
chunk position
section index
ordered block state IDs / canonical names
worldgen heightmaps
required post-processing metadata
```

Document exactly what the digest includes.

---

## 7. Timeout tests

Simulate:

```text
client never responds
client responds after timeout
client disconnects mid-job
client returns invalid data
```

Expected result:

```text
server falls back
chunk still generates
late response cannot mutate chunk
```

---

## 8. Concurrency tests

Exercise:

- several different chunk jobs
- repeated same chunk request
- cancellation
- server shutdown with pending work
- client disconnect
- max in-flight limit

Watch for:

- double completion
- deadlock
- concurrent modification
- chunk mutation from wrong thread

---

## 9. Runtime tests / GameTest

Fabric 26.2 documentation supports both Fabric Loader JUnit and Minecraft GameTest-based testing.

Use GameTest/runtime tests when behavior requires actual Minecraft bootstrap/world state.

Potential tests:

- command registration
- payload registration
- server-side lifecycle
- chunk generation hook reached
- deterministic chunk output

---

## 10. Manual integration test

Early remote PoC:

1. Start dev server.
2. Start dev client.
3. Join server.
4. Confirm `WorkerHello`.
5. Request/generate a known chunk.
6. Confirm job dispatch.
7. Confirm result receive.
8. Confirm validation.
9. Confirm final chunk generation.
10. Repeat with remote disabled.
11. Force timeout and confirm fallback.

Collect:

```text
server latest.log
client latest.log
benchmark CSV
```

---

## 11. Regression suite

Before merging a worldgen change:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

And when Mixins/worldgen runtime behavior changed:

- launch dev server
- launch dev client if required
- generate deterministic fixture chunks
- inspect logs
- compare digests

---

## 12. Test-world policy

Use disposable worlds.

Never test initial worldgen Mixins on a valuable survival world.

Recommended:

```text
run/
  test-worlds/
```

or a script that recreates a known world directory.

---

## 13. Fault-injection flags

Useful development config:

```text
forceRemoteTimeout
WORLDGEN_ASSIST_CLIENT_TEST_CORRUPT_DENSITY=true
artificialClientDelayMs
dropEveryNthResult
disableCompression
forceLocalBackend
forceRemoteBackend
```

These make fallback and failure handling testable.

The implemented corruption flag is honored only in Fabric's development
environment and changes every returned density by one representable IEEE-754
step. It is disabled by default. Do not leave dangerous debug defaults enabled
in production builds.

---

## 14. Definition of PoC correctness

The remote PoC is correct only when:

- [x] remote path generates chunks
- [x] local fallback generates chunks
- [x] deterministic comparison passes
- [x] timeout does not corrupt/stall world
- [x] disconnect does not corrupt/stall world
- [x] result is applied before the original server-side fill
- [x] downstream stages complete normally
- [x] world reload succeeds

These boxes describe the narrow PoC only. Phase 5 now adds a measured
probabilistic adversarial check, but it still does not imply public-server
readiness.

---

## 15. Current Phase 0 through Phase 5 coverage

The current implementation includes Fabric Loader JUnit tests for the noise
stage metrics observer:

- successful future completion
- exceptional future completion
- synchronous failure before a future is returned
- preservation of the original vanilla future identity
- counters and elapsed-time accumulation
- ordered, awaited success observation for exact-boundary snapshots
- canonical registry-name and sorted-property block-state encoding
- vanilla/delegate/local mode, worker-count, and queued-tasks-per-worker configuration parsing
- vanilla-delegating backend identity, scheduler ID, and exact executor identity preservation
- project-owned worker execution
- local-executor rejection before mutation and vanilla fallback
- non-blocking fallback when bounded local admission is full
- worker-proportional queue/admission sizing for ratios 0, 1, 2, and 4
- actual `vanilla_delegate` / `local_pool` / `vanilla_fallback` benchmark-route attribution and context restoration
- fallback warning cadence at the first and every 64th fallback
- local worker-pool recreation for a later server lifecycle in the same JVM
- explicit asynchronous ForkJoin scheduler identity
- benchmark wrapper identity preservation while timing is disabled
- deterministic supplier queue/compute boundaries with an injected monotonic clock
- deterministic worker CPU-time boundaries with an injected CPU clock
- benchmark failure observation and rethrow of the original exception
- exact NOISE active-operation and peak counters across completion paths
- exact local active/admitted point samples and interval-peak reset behavior
- tick timing, 50 ms budget classification, and lifecycle reset behavior
- NOISE completion attribution across the gap between adjacent tick events
- protocol-version range and explicit current-version rejection
- SHA-256 context-fingerprint length, hex parsing, equality, and defensive copies
- canonical context JSON/object and registry ordering, array-order sensitivity,
  input-change sensitivity, duplicate-registry rejection, and a format-1 golden
  vector
- job UUID sentinel, dimension-byte, and generated-source `ChunkPos.isValid` bounds
- bounded pending-job admission and server-issued UUID collision handling
- atomic owner/identity/replay/timeout/cancellation response-state transitions
- disconnect/server-shutdown cancellation and bounded terminal-record retention
- bounded density job/result constructors and exact cell-order remapping
- bounded codecs for handshake, accepted, request, result, failure, and cancel
- invalid enums, strings, identifiers, counts, truncation, and oversized result
  rejection
- exact big-endian density round trips through RAW and DEFLATE envelopes,
  incompressible fallback, defensive copies, and encoded-size limits
- malformed, truncated, trailing, dictionary/overflow, and unknown-encoding
  rejection with exact decompressed output bounds
- worker handshake/version/capacity admission and sole-worker leasing
- result/failure completion, send failure, re-handshake, reload, disconnect,
  shutdown, tick timeout, and off-thread watchdog timeout state transitions
- single-winner response claims before asynchronous decode, duplicate claim
  rejection, decode-failure fallback, timeout-during-decode, and lease release
- result-cache copy isolation, exact context/geometry keying, zero-capacity mode,
  LRU eviction, lifecycle generation, and stale-generation exclusion

An additional Fabric test-runtime check bootstraps Minecraft and loads both
`net.minecraft.world.level.chunk.status.ChunkStatusTasks` and
`net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`. Because the
mod's Mixin configuration is active in that runtime, this fails if either
verified target or operation descriptor cannot be applied.

The development dedicated server reaches Fabric/Mixin initialization and the
`[CAWG] initialized` log without errors. The local and delegate modes both emit
their expected `[CAWG] backend.enabled` records; digest runs also emit
`[CAWG] stage.digest_enabled ... format=1`. Opt-in context runs emit one
`[CAWG] context.fingerprint ... format=1` record per noise-based dimension.

For that runtime check, set `WORLDGEN_ASSIST_NOISE_DIGEST=true`, generate a
fixed coordinate set twice from separately recreated worlds with the same
seed/configuration, and compare only log entries with the same digest format
version and chunk coordinates. Digest mode must be disabled for timing
benchmarks because it intentionally holds the stage boundary while hashing.

### 15.1 Deterministic fixture run — 2026-09-01

The checked runtime fixture used:

```text
Minecraft: 26.2
dimension: minecraft:overworld
level seed: 8675309
target chunk coordinates: 0,0; 8,12; -7,5; 128,-96
digest: canonical format 1, SHA-256
```

Widely separated forced chunks caused 331 dependency chunks to reach the
`NOISE` stage in each fresh world.

```text
vanilla world A versus vanilla world B:
  common coordinates: 331
  mismatches: 0

vanilla world A versus Phase 1 local backend:
  common coordinates: 331
  mismatches: 0
  local completion threads: 331/331 CAWG-LocalWorldgen-1
  stage failures: 0
  vanilla executor fallbacks: 0
  saved local world reload: passed (100 persistent chunks loaded)

vanilla world A versus revised async ForkJoin local backend (19 workers):
  common coordinates: 331
  mismatches: 0
  local completion threads: 331/331 CAWG-LocalWorldgen-*
  stage failures: 0
  vanilla executor fallbacks: 0
```

The disposable evidence logs are retained under `run/test-results/`, which is
excluded from version control with the rest of `run/`. The revised-backend log
is `run/test-results/determinism-local-forkjoin.log`.

### 15.2 Initial FIFO executor benchmark — 2026-09-01

The opt-in benchmark wrapper and CSV summarizer were runtime-tested with three
fresh vanilla/local world pairs. Each mode/run had 25 spawn warm-up noise
tasks followed by 648 measured dependency tasks, with identical measured
coordinate sets. All 3,888 measured stages and their 3,888 matched task records
completed; there were zero stage failures and zero local fallback submissions.

The summarizer rejects digest-enabled logs, missing markers, duplicate
coordinates, unexpected backend IDs, and unequal stage/task or vanilla/local
coordinate sets. Detailed logs and CSVs are under
`run/benchmarks/phase1-executor-detail-20260901/` (ignored by Git).

### 15.3 Revised async ForkJoin executor benchmark — 2026-09-01

The same 648-task, three-pair fixture was repeated after replacing the fixed
FIFO executor with a dedicated asynchronous-mode `ForkJoinPool` and bounded
admission. The summarizer recorded scheduler IDs
`minecraft_shared_fork_join_async` and `fork_join_async`. Every mode/run had
648 matched coordinates, with zero stage failures, task failures, or local
fallbacks.

```text
mean of run means                 VANILLA   LOCAL_BACKEND
stage elapsed (ms/task)             22.03           44.26
supplier queue wait (ms/task)        3.05           23.08
supplier compute (ms/task)          18.80           20.91
workload span (ms/648 tasks)      4796.40         5162.86
throughput (tasks/s)                135.32          125.65
```

A fresh-world screening sweep also compared 8, 19, and 32 local workers. The
19-worker configuration had the best local throughput of those three (131.00,
124.22, and 106.65 tasks/s respectively), so it was used for the repeated
fixture. The current paired result still fails the performance part of Gate 2.
Detailed evidence is retained under
`run/benchmarks/phase1-forkjoin-20260901/`; the screening evidence is under
`run/benchmarks/phase1-forkjoin-screen-20260901/` (both ignored by Git).

### 15.4 Worker CPU-time instrumentation smoke — 2026-09-01

The extended task wrapper and summarizer were checked in an explicit JDK
25.0.4 vanilla/local pair. After spawn warm-up, one forced chunk at block
position `(2048,2048)` produced 81 matched measured NOISE coordinates per
mode. All 162 records had supported `cpu_ms` samples, no task/stage failures,
and no local fallbacks.

The Windows `ThreadMXBean` readings were quantized in 15.625 ms increments.
Vanilla had 21 zero-valued samples and local had 29, so this one smoke pair is
evidence that the measurement and backward-compatible CSV path work, not a
reliable CPU performance comparison. Both modes also emitted one coarse
Minecraft `Can't keep up!` warning. Repeated aggregate CPU measurement and
the explicit tick-duration instrumentation described in Section 15.5 both
still needed repeated fixtures at this point. Section 15.6 supplies that
follow-up evidence. The CPU smoke evidence is retained under
`run/benchmarks/phase1-cpu-smoke-20260901/` (ignored by Git).

### 15.5 Server-tick instrumentation smoke — 2026-09-01

The opt-in Fabric tick-event logger and tick CSV summarizer were checked in an
explicit JDK 25.0.4 vanilla/local pair. Fresh worlds used seed `8675309`; after
the warm-up marker, forcing block position `(2048,2048)` produced 81 measured
NOISE completions per mode. Both modes had zero stage failures, and local used
19 workers with zero fallbacks.

Vanilla attributed activity to eight ticks, one over the 50 ms budget, with an
active-tick mean of 418.20 ms and p95 of 3342.04 ms. Local attributed all 81
completions to one 3540.23 ms tick. Its point samples were idle at tick end,
while the new interval counters captured peaks of 19 exact active tasks and 61
admitted tasks. This validates completion carry-over and interval saturation
capture; the one-pair figures are not a performance comparison. Repeated,
order-balanced tick/CPU runs were still required here and are reported in
Section 15.6. Evidence is under
`run/benchmarks/phase1-tick-smoke-20260901/` (ignored by Git).

### 15.6 Repeated tick/CPU Gate 2 fixture — 2026-09-02

Three fresh-world pairs repeated the established eight-position, 648-task
fixture with both NOISE task CPU timing and tick/saturation logging enabled.
The order was vanilla/local, local/vanilla, then vanilla/local. Every mode/run
had 648 matching stage/task records and supported CPU samples; all 3,888 tasks
completed with zero failures and zero local fallbacks.

The new series summarizer validated per-run NOISE/tick counts before pooling
results. Mean stage elapsed was 23.69 ms/task for vanilla and 50.83 ms/task for
local; queue wait accounted for 25.60 ms of the 27.14 ms difference. Worker CPU
means were 16.81 and 16.25 ms/task, a small difference on a Windows counter
quantized to 15.625 ms. Local throughput was 132.60 tasks/s versus 129.16 in
this series, but the earlier repeated series had the opposite result.

Across NOISE-active ticks, vanilla had 3/54 and local 4/40 over the 50 ms
budget. Pooled active-tick mean/p95 were 226.70/3895.05 ms for vanilla and
325.69/4178.07 ms for local. Local interval peaks reached 19 active and 137
admitted tasks. Because active-tick counts differ and the fixture concentrates
work through console commands, these are conditional diagnostics, not general
TPS distributions. They nevertheless provide no tick-health improvement and
confirm non-negligible queue overhead. The current local backend fails the
performance/tick portion of Gate 2. Evidence is under
`run/benchmarks/phase1-tick-cpu-20260902/` (ignored by Git).

### 15.7 Worker-proportional admission and routed screen — 2026-09-02

The historical fixed 1024-slot waiting window was replaced with a configurable
`workers * queued-tasks-per-worker` queue. The default ratio is one, making the
19-worker local admission ceiling 38 rather than 1043. A one-pair fresh-world
screen exercised ratios 0, 1, 2, and 4 with the established seed and 648-task
fixture. All four runs completed 648 stages with zero failures. Their fallback
counts were 159, 60, 76, and 3 respectively, demonstrating the tradeoff between
short local queues and executing a material share on vanilla.

Task records now include the actual execution route. The summarizer emits a
route summary and remains backward-compatible with older unrouted logs. A final
default-ratio run, after rate-limiting fallback warnings, completed 648/648
tasks with zero failures: 586 ran on `local_pool` and 62 ran on
`vanilla_fallback` (9.57%). Only the first fallback produced a warning because
the count remained below 64; the exact count was preserved in route and tick
counters.

The matched vanilla/local aggregate stage means were 22.93/40.66 ms, queue
means were 3.17/11.62 ms, and throughput was 132.15/129.29 tasks/s. Conditional
NOISE-active ticks had 1/19 versus 2/16 over the 50 ms budget, with mean/p95 of
216.93/4104.42 ms versus 268.25/4226.37 ms. Local interval peaks stayed within
19 active and 38 admitted tasks. The new bound works as designed, but the
routed result still fails Gate 2 and prevents fallback-heavy runs from being
misclassified as local performance. Evidence is under
`run/benchmarks/phase1-admission-screen-20260902/` (ignored by Git).

### 15.8 Vanilla-delegating Gate 2 baseline — 2026-09-02

The `delegate` backend was checked at three levels. Its unit test requires that
the backend return the exact captured executor object and advertise the vanilla
shared asynchronous ForkJoin scheduler. A fresh deterministic server world
then produced 331 successful canonical NOISE digests; all 331 coordinates and
digests matched the established vanilla baseline with no failures or mode-only
coordinates. The runtime route was `vanilla_delegate`.

Finally, three fresh 648-task vanilla/delegate pairs alternated run order. All
3,888 tasks completed without stage/task failures, and every one of the 1,944
delegate tasks used `vanilla_delegate`. Mean-of-run-means results were:

```text
metric                              VANILLA      DELEGATE       delta
stage elapsed (ms/task)               24.054        25.283      +1.229
queue wait (ms/task)                   3.237         3.053      -0.183
compute wall (ms/task)                20.588        22.042      +1.455
workload span (ms/648 tasks)        5341.231      5267.064     -74.168
throughput (tasks/s)                 122.046       123.029      +0.983
NOISE-active / over-budget ticks        50/3          50/3         0/0
```

Per-run stage deltas changed sign (`+4.538`, `+2.390`, `-3.242` ms/task),
with a 4.018 ms sample standard deviation. Since the two modes use the same
supplier mechanics and exact executor object, and queue/span/throughput/tick
counts were equivalent at this fixture's resolution, no seam overhead was
distinguishable from run noise. Gate 2 passes for backend selection. It remains
failed for the dedicated local-pool policy documented in Sections 15.2 through
15.7.

The fixed fixture is automated by
`scripts/Run-NoiseBenchmarkFixture.ps1`; it refuses reused worlds and existing
evidence files, constrains output to `run/benchmarks`, waits for exactly 648
measured tasks, and restores `server.properties`. Evidence is under
`run/benchmarks/phase1-delegate-20260902/`, with the deterministic log at
`run/test-results/determinism-delegate.log` (all ignored by Git).

### 15.9 Phase 2 immutable identity scaffold — 2026-09-02

Eleven focused tests cover the first common protocol metadata types. Protocol
versions accept the future representable range but require exact version `1`
for a job. Context fingerprints require exactly 32 bytes/64 hexadecimal
characters and defensively copy both inputs and outputs. Job identities reject
the all-zero UUID, unsupported protocol versions, dimension IDs above 256 UTF-8
bytes, and coordinates for which Minecraft 26.2 `ChunkPos.isValid` is false;
the inclusive generated-source boundary is accepted.

Twelve additional tests cover the server pending-job registry: global and
per-owner admission, exact response acceptance/replay rejection, owner and
identity mismatch, deadline-edge expiry, idempotent cancellation, disconnect
and shutdown cancellation, bounded/expiring terminal tombstones, zero/colliding
UUID retries, constructor bounds, and eight concurrent responses with exactly
one winner.

The registry's accepted response status remains an identity claim only;
`RemoteDensityField` and cached `NoiseChunk` geometry perform the subsequent
result/application checks.

### 15.10 Phase 2 context fingerprint — 2026-09-02

Five focused tests lock down format `1`: JSON object fields and registry entries
canonicalize independently of iteration order, arrays remain order-sensitive,
seed/dimension/settings/density/noise changes alter the digest, duplicate
registry IDs fail closed, and a fixed fixture matches the committed SHA-256
golden vector.

The factory was also exercised in a real Minecraft 26.2 dedicated server using
seed `8675309`. Two starts of the same disposable world each produced exactly
one fingerprint for Overworld, Nether, and End. All three dimension/digest pairs
matched exactly across starts (`3/3`, format `1`, SHA-256). The evidence is
retained as `run/test-results/context-fingerprint-a.log` and
`context-fingerprint-b.log`; the world itself was removed and
`server.properties` restored. The second test process required explicit
termination after capture because its launch session exposed no writable stdin;
the fingerprint comparison had already passed.

### 15.11 Phase 2 remote density PoC — 2026-09-02

Fabric JUnit now round-trips every payload codec and verifies legal maxima plus
the first illegal bounds. Coordinator tests cover worker admission, exact owner
and identity matching, replay, explicit client failure, send failure,
re-handshake, reload, disconnect, shutdown, ordinary timeout with cancellation,
and watchdog timeout without unsafe off-thread networking. Runtime Mixin loading
also asserts the `ChunkAccess` accessor, private
`NoiseBasedChunkGenerator.createNoiseChunk` invoker, `NoiseChunk` application
Mixin, and inner `CacheAllInCell` accessor.

The real runtime fixture used a Minecraft 26.2 dedicated server, a quick-play
client, seed `8675309`, vanilla Overworld settings, and one worker. Logs confirm:

- accepted handshake and one in-flight job;
- client work on `CAWG-RemoteWorldgen-1`, not the render thread;
- 98,304 finite density samples per standard-height job;
- server `job.result_received`, density application, `job.complete`, and the
  original NOISE-stage completion;
- clean disconnect, save, and subsequent world reopen.

Seven chunks whose remote lifecycle reached `job.complete` were generated at
the same coordinates in an independent local-only world with the same seed and
configuration. The canonical format-1 digest includes all 98,304 block states,
generation heightmaps, and post-processing positions. All seven matched
exactly (`7/7`, zero mismatches). Evidence is retained in ignored files:

```text
run/test-results/remote-watchdog-and-density.log
run/test-results/local-density-baseline-20260902.log
run/test-results/local-density-baseline-completed-jobs-20260902.log
```

A second assertion used a 500 ms timeout while `forceload` synchronously waited
on the server main thread. `CAWG-RemoteTimeout` expired the pending attempt,
local generation unblocked the command, the queued result was rejected as
`EXPIRED`, `list` still reported the client connected, and a later remote job
completed. This specifically verifies the non-tick watchdog added after the
initial tick-only timeout took about 30 seconds to recover through disconnect.

### 15.12 Phase 3 compression and result cache — 2026-09-03

The suite now contains 88 tests. New focused tests cover lossless RAW/DEFLATE
selection, exact IEEE-754 preservation, all envelope bounds and malformed-input
paths, pre-decode response claims, bounded asynchronous decode failure/timeout
behavior, and LRU cache capacity, copy isolation, key separation, disabled mode,
and generation changes across invalidation.

A real Minecraft 26.2 dedicated server/client run used seed `8675309`, vanilla
Overworld settings, protocol version 2, one remote worker, and standard
98,304-density jobs. Twenty-one received envelopes all selected DEFLATE:

```text
raw density bytes per result       786,432
encoded bytes range                307,123 .. 333,518
mean compression ratio             0.4087
mean client encode time (ms)       9.69
mean server decode time (ms)       2.49
remote application completions     20
```

Seven remotely completed coordinates also existed in the independent Phase 2
local-only baseline. Their complete canonical format-1 NOISE digests matched
exactly (`7/7`, zero mismatches), demonstrating that transport compression did
not alter block states, heightmaps, or post-processing positions. The same run
stored 21 bounded cache entries over time and cleared 16 resident entries at
shutdown. A separate lifecycle run logged datapack-reload invalidation and
post-reload stores under the new cache generation.

Ignored runtime evidence is retained at:

```text
run/test-results/phase3-compression-cache-runtime-20260903.log
run/test-results/phase3-compressed-determinism-20260903.log
run/test-results/phase3-compressed-determinism-matched-20260903.log
```

The middle log also records a 10-second watchdog fallback caused by a
synchronous `forceload` command while compressed transport was enabled. The
matched log drove chunk demand asynchronously through a connected client and is
the source of the `7/7` compressed-versus-local digest result.

### 15.13 Phase 4 player-owned prediction — 2026-09-03

The suite now contains 96 tests across 22 suites, with zero failures, errors, or
skips. New tests cover movement-direction prediction, stationary retry,
dimension and teleport reset, coordinate boundaries, effective-view clamping,
bounded forward scanning, observation clearing, exact-owner worker admission,
prediction configuration bounds, and cache presence checks.

A real Minecraft 26.2 server/client run used seed `8675309`, protocol version 2,
one accepted client, one in-flight slot, a 16-entry cache, a 20-tick interval,
and an eight-chunk lead. Logs established `prediction.sent`,
`prediction.complete`, later `cache.hit`, and `job.complete source=cache` for
three real-demand chunks:

```text
chunk      cache-path completion     remote digest == local digest
0,17                 16.03 ms        yes
-1,18                15.93 ms        yes
0,18                 31.70 ms        yes
```

The canonical format-1 digest covers the complete NOISE-stage block states,
generation heightmaps, and post-processing positions. All three matched the
independent local-only world (`3/3`, zero mismatches). One unrelated direct job
also exercised the 10-second watchdog/local-fallback route while the synchronous
teleport command occupied the server thread; its late result was rejected.

Ignored evidence is retained at:

```text
run/test-results/phase4-prediction-cache-hit-20260903.log
run/test-results/phase4-prediction-local-baseline-20260903.log
```

This fixture proves prediction ownership, cache consumption, fallback, and
deterministic application. It is not a controlled throughput or tick-health
comparison, so it does not satisfy the Phase 5 performance-benefit prerequisite.

### 15.14 Phase 5 sampled validation and adversarial client — 2026-09-03

The suite now contains 104 tests across 23 suites, with zero failures or
errors. Focused coverage includes unique bounded cell selection, zero-sample
behavior, bit-exact comparison (including signed zero), validation metric
bounds, explicit cache eviction, connection quarantine/re-handshake rejection,
and 2,000 deterministic bounded random result-payload decode attempts. The
timeout circuit-breaker test proves that a success resets its counter, three
subsequent consecutive timeouts quarantine the connection, and a disconnect
permits a fresh registration.

The honest-client runtime used seed `8675309`, vanilla Overworld settings, one
worker, one in-flight slot, and eight validation cells. Four direct remote jobs
each checked 1,024 density values and completed normally:

```text
validation time (ms)    18.9473, 6.4522, 6.2044, 6.2220
post-first range (ms)   6.2044 .. 6.4522
validation failures     0
remote completions      4
```

A second disposable world enabled the development-only
`WORLDGEN_ASSIST_CLIENT_TEST_CORRUPT_DENSITY=true` fixture. It changed every
density by one representable IEEE-754 step. The first remote result failed at a
sampled value, produced `worker.quarantined` and `job.apply_rejected`, produced
no `cache.store` or remote completion, and the original target chunk reached
ordinary NOISE-stage completion through local fallback. No later remote job was
sent during that connection.

Ignored evidence is retained at:

```text
run/benchmarks/phase5-validation-20260903/server.log
run/benchmarks/phase5-validation-20260903/adversarial-server.log
```

### 15.15 Seed-disclosure guard and dependency inventory — 2026-09-03

The full suite now contains 109 tests across 24 suites, with zero failures,
errors, or skips.

The configuration suite now proves that remote enable alone retains
`SeedDisclosureMode.DENY`, while only the separately parsed `trusted_raw` value
enables the existing seed-bearing protocol. An unknown mode fails startup
configuration. The coordinator-level test additionally proves that `DENY`
returns `REMOTE_DISABLED`, creates no pending submission, and sends no job.

`SeedDependencyAnalyzerTest` traverses both unwired and seed-wired vanilla
Overworld `finalDensity`. The wired 26.2 fixture fixes 25 unique keyed noise
IDs, 1 `BlendedNoise`, and 1,116 expanded noise-holder references, with no
unkeyed or unwired holder. The unwired graph is explicitly not replay-ready.
The analyzer exposes identifiers and Java type names only; it never extracts a
seed, random factory, noise object, offset, or permutation table.

### 15.16 Server-local seeded-leaf recorder/replayer — 2026-09-03

The full suite now contains 114 tests across 25 suites, with zero failures,
errors, or skips after an explicit `--rerun-tasks` run.

Five focused trace tests exercise the two source-verified seed-leaf hooks. A
trace recorded from vanilla Overworld seed `8675309` is replayed through a
`RandomState` initialized with an unrelated dummy seed. All interpolated
density bits match for both a 16-block slice and a full 384-block
negative-coordinate chunk. The fixtures record exactly 2,548 and 17,972
entries respectively, and reach both keyed `NormalNoise` and `BlendedNoise`. Changed input bits, a one-entry
recording bound, and an unconsumed trailing entry each fail closed.

At this milestone the trace had no codec or network registration. It stored
only bounded leaf kind/ID, input bits, and output bits; initialized samplers,
permutations, random factories, and the raw seed were absent. Section 15.17
records the later unregistered codec work.

A real remote-disabled dedicated server then started on isolated port `25567`
and universe `runtime-smoke-seed-trace`, generated all 25 spawn NOISE chunks,
reached `Done`, saved, and stopped cleanly. `run/logs/latest.log` contains no
Mixin application, injection, fatal, or exception error. This validates the
inactive-hook vanilla path, not a networked seed-confidential mode.

### 15.17 Seeded-leaf wire-model draft — 2026-09-03

The full forced suite now contains 137 tests across 33 suites, with zero
failures, errors, or skips. Twenty-three new tests cover the 256-bit opaque
context ID, immutable transcript and dictionary bounds, the unregistered job
model, server-keyed authentication, and all three binary codecs.

The transcript codec round-trips exact raw bits, encodes the maximum 100,000
entries beneath its 4,000,000-byte limit, and rejects an oversized buffer
before parsing. It also rejects unknown format/kind values, excessive counts,
duplicate or unused dictionary IDs, invalid indices, non-canonical first use,
trailing bytes, all truncations of a representative valid transcript, and
2,000 deterministic bounded random malformed inputs without escaping with an
`Error`.

Both worldgen equivalence fixtures now encode and decode the transcript before
replaying it through the unrelated dummy-seed `RandomState`; the 2,548-entry
slice and 17,972-entry full-height negative chunk remain bit-exact. The job
codec round-trips its server-issued UUID, opaque context ID, versioned 26.2
graph ID, exact Overworld geometry, and transcript, and rejects unknown job or
graph versions plus every truncation of its valid fixture. A record-component
regression test verifies that the draft exposes neither `worldSeed` nor
`contextFingerprint`.

The HMAC tests use fixed and random 256-bit keys. An issued authorization
verifies under its owner key, while changes to the job UUID, opaque context ID,
either chunk coordinate, height/cell geometry, transcript input bits, or
transcript output bits fail verification. A different key also fails. Key and
tag byte arrays are defensively copied, zero/wrong-length keys and malformed
tags are rejected, and the authorization codec round-trips its tag plus job
while rejecting an unknown version and every truncation of the valid fixture.

These codecs remain unregistered. No client/server runtime networking behavior
or Minecraft Mixin target changed in this milestone, so the section 15.16
dedicated-server inactive-hook smoke remains the applicable runtime evidence.

### 15.18 Seeded-leaf authority and lifecycle — 2026-09-03

The full forced suite now contains 150 tests across 36 suites, with zero
failures, errors, or skips. New authority coverage proves inactive rejection,
non-zero collision-safe job allocation, exact owner/context/tag matching,
one-shot and concurrent claim behavior, monotonic expiry, idempotent
cancellation, and bounded terminal retention.

Admission tests independently cover global/per-owner in-flight limits, a
100,000-entry aggregate live-transcript ceiling, and a configurable cumulative
per-owner disclosure ceiling. A terminal transition reduces the live entry
count immediately and later replay classification succeeds from the fixed-size
claim tombstone. Cancellation and disconnect do not refund disclosure. Datapack
reload cancels the old context, rotates context/key, and retains the charged
owner count; stop removes the active context, while a new server start creates
a distinct context and resets the in-memory server-lifetime budget.
Sequential-connection coverage fills all 1,024 retained owner-budget slots and
verifies that the next previously unseen owner is rejected without growing the
map.

The fixed 81-byte claim codec round-trips UUID, opaque context ID, and HMAC tag,
and rejects a wrong version, every truncation, and trailing input. The lifecycle
fixture exercises start, owner disconnect, reload, tick expiry, and stop against
the same authority state machine.

A real remote-disabled Minecraft 26.2 dedicated server then ran on isolated
port `25569` and universe `runtime-smoke-seed-authority-graceful`. It logged
`seed_authority.started generation=1 disclosure_entries_per_owner=100000`,
completed all 25 spawn NOISE stages, reached `Done`, accepted a graceful `stop`,
logged `seed_authority.stopped generation=1 cancelled_jobs=0`, saved all three
dimensions, and ended with `BUILD SUCCESSFUL`. `run/logs/latest.log` contains no
Mixin, injection, fatal, or exception error. The port was closed afterward.

### 15.19 Seed-free seeded-leaf result boundary — 2026-09-03

The full forced suite now contains 163 tests across 40 suites, with zero
failures, errors, or skips.

New common-model tests prove that the result has no raw-seed or protocol-v2
context-fingerprint field, defensively copies values, preserves signed-zero
bits, bounds count/timing, and rejects NaN, infinity, and excessive magnitude.
RAW and DEFLATE both round-trip losslessly. Corruption, truncation, trailing
compressed input, expansion beyond the declared count, and invalid decoded
values fail closed.

The strict standalone result codec round-trips the fixed claim and envelope,
accepts the maximum 98,304-value RAW section within its 786,545-byte cap, and
rejects an oversized payload, unknown envelope/claim versions, every truncation
of the representative fixture, and trailing bytes.

Server-gate tests prove that owner/context/HMAC admission occurs before
decompression, a wrong owner neither expands malformed data nor consumes the
rightful claim, and exactly one authentic response recovers the retained job.
The gate compares the declared count with the 98,304-sample job before decode,
then rejects malformed compression or NaN values. Shape/data failure after a
valid claim consumes the response and subsequent attempts are duplicates.
Success releases the authority's live transcript accounting and returns only
seed-free geometry plus bounded values.

Two integration tests pass that projection directly to the independent
server-owned `NoiseChunk` validator. A real vanilla-registry `RandomState` with
seed `8675309` reproduces and validates all 32 cells/4,096 values of a 16-block
slice. Changing one density by one ULP is rejected bit-exactly, and geometry
that disagrees with authoritative `NoiseSettings` is rejected before sampling.
The refactor also defers construction of the unused `Blocks.AIR` fluid picker
until a sampler is actually created, so the validator's pure selection and bit-
comparison unit tests no longer depend on another suite having bootstrapped
Minecraft first.

No Minecraft Mixin, Fabric payload registration, or runtime route changed, so
the graceful dedicated-server evidence in section 15.18 remains applicable.

### 15.20 Seed-confidential runtime primitives — partial delegated verification

After the 163-test/40-suite baseline above, a new implementation batch added:

- a fixed-format world-local persistent global transcript-disclosure ledger;
- global-budget charging in `SeededLeafJobAuthority`;
- exact server-side `NoiseChunk` transcript discovery;
- a fixed-public-dummy-seed client density computer and exact-capacity worker;
- split claim-before-decode admission;
- bounded off-thread decode and authoritative validation;
- reload/disconnect admission epochs and real-execution task accounting; and
- a validated-only conversion into the existing generic remote density field.

This batch has deliberately **not** been compiled, unit-tested, built, or
runtime-launched by the implementation agent. The user requested that future
test execution be handed to a lower-tier AI and reviewed afterward. Therefore,
the 163/40 result is a historical baseline and is not evidence for these new
changes.

The complete sequential test-writing, execution, evidence-capture, failure-
handling, and report procedure is now in `VALIDATION_MATRIX.md`. Multi-PC tests were
explicitly deferred. The implementation is not fully verified until the
required race, capacity, complete-chain, client-worker, and static protocol
assertions have fresh evidence in addition to compilation, the full suite, and
the build.

The first delegated run (`20260904-140300`) stopped correctly at Phase A. It
found two compile errors where `RemoteDensityField` accessed the private record
fields of generated Minecraft 26.2 `ChunkPos`. Generated source confirms the
public accessors are `x()` and `z()`; the implementation was corrected to use
those accessors. No tests or build ran in that attempt, and the 163/40 XML still
present under `build/` was stale historical output rather than evidence from
the failed run. A new timestamped run must restart at Phase A.

That run also launched Gradle under Oracle JDK 26.0.2 even though the repository
target is JDK 25. This did not cause the `ChunkPos` access error, and compilation
still used `--release 25`, but subsequent evidence should use the installed
`C:\Program Files\Java\jdk-25.0.4` explicitly as documented in
`VALIDATION_MATRIX.md`.

The second delegated Phase A run (`20260904-141138`) correctly used JDK 25.0.4
and confirmed the `ChunkPos` fix, then exposed one independent constructor bug:
`SeededLeafResultValidationExecutor.validationSampleCells` was validated but not
assigned to its final field. The constructor now assigns the validated value.
No tests/build ran in that attempt; another fresh Phase A run is required.

The third delegated run (`20260904-141809`) used JDK 25.0.4 and completed fresh
compile, focused-test, full-test, and build phases successfully. The final
forced suite regenerated 42 JUnit XML suites containing 176 tests with zero
failures, errors, or skips. The regenerated main JAR SHA-256 is
`0EB6D9538C53F8B40CFE96C3DFBE570C3C6B08862F98BB9C053A2DFEF563F1ED`.
The persistent-ledger cases cover reopen persistence, exact exhaustion,
concurrent charging, every truncated length, selected corruption, pending-file
ambiguity, marker loss/malformation, configured-maximum mismatch, and recovery
after repair. Authority and result-gate extensions also passed.

This is intentionally a **partial** acceptance. The new validation-executor
class contains only two tests (inactive-owner admission/malformed decoding and
idempotent close). The delegated report correctly records no evidence yet for
reload or disconnect admission races, timeout capacity retention, the complete
recorder-to-client-worker-to-executor-to-field chain, client worker accounting,
or a dedicated static protocol-v3 registration/logging guard. Runtime smoke was
not authorized and multi-PC testing remains deferred. Raw evidence is under
`test-artifacts/20260904-141809/`; the review summary is
`TEST_RESULTS_LATEST.md`.

The later delegated run `20260904-152755` stopped at Phase B after a new client
worker test found that an active duplicate job was classified as generic
capacity exhaustion. The worker acquired its sole capacity permit before
checking the active job-ID map. Client admission and close now share a short
lock, and admission checks closed/duplicate state before acquiring capacity and
registering the attempt. The total one-attempt bound and real-exit permit
release remain unchanged. This fix awaits a fresh delegated run beginning at
Phase A; the failed evidence remains under
`test-artifacts/20260904-152755/`.

The delegated run `20260904-153214` verified that duplicate classification fix,
then passed all 180 tests in 44 suites and the build. The later run
`20260904-230917` passed 182 tests in 45 suites and the build, adding an
owner-epoch-tracker exhaustion case and a partial real recorder/authorization-
codec/dummy-seed-client/result-codec path. The tester correctly left the
complete server authority/executor/field chain and true capacity/timeout/
lifecycle races unclaimed.

To remove the reported deterministic-test blocker, the validation executor now
has package-private no-op-by-default hooks after claim/before registration and
on its real worker immediately before decode/validation. The client worker has
an equivalent package-private hook immediately before its real density
computer. These hooks cannot supply results or replace production computation;
they only let tests hold and release real invocations with latches. Default and
public construction remains behaviorally unchanged. This implementation batch
was verified by delegated run `20260904-235250`. That run passed Phase A-D with
188 tests in 45 suites and no failures, errors, or skips. Latch-backed tests
proved executor and client exact-capacity admission, timeout/cancellation
completion without early permit release, and reload/disconnect races held in
the claim-to-registration window. The current main JAR SHA-256 is
`72F043C2526A5E3EA76C26ACE673B468AD12DC486B90FB3BAE46B883473B1007`.

This remains a partial acceptance: valid and one-ULP executor outcomes,
disconnect/reconnect stale epochs, full cancel/reload/close idempotence, queued
client cleanup, the complete authority-to-field end-to-end matrix, and the
remaining static confidentiality guards still require delegated tests before
runtime activation is considered.

Terra-high handoff run `20260905-005106` passed Phase A-D with 194 tests in
46 suites and no failures, errors, or skips. It verified dedicated-worker
execution after synchronous claim, disconnect/reconnect stale epochs, valid
all-cell executor metrics, one-ULP rejection, and field rejection after
authority reload. A test-source-only bridge enabled a complete three-public-
seed 16-block recorder/authority/codec/dummy-client/executor/field path; all
4,096 values per fixture passed authoritative validation and raw-bit field
comparison. Static guards now exercise the explicit `trusted_raw` gate,
counter-only ledger structure, and sensitive production logger arguments.

The initial interrupted static-guard failure in `20260905-003551` was a test
predicate error, not a production defect; the corrected guard was strengthened
and passed in the final run. Standard-height and RAW full-chain variants plus
the remaining lifecycle, mutation, geometry, cancellation, and retention
assertions remain open. Raw evidence is under
`test-artifacts/20260905-005106/`; the detailed mapping is
`TEST_RESULTS_LATEST.md`.

# Advanced configuration and implementation guide

## Alpha.3 scope

The current release extends the trusted raw-seed route to vanilla
Overworld, Nether and End. Each accepted player still assists only demand in
that player's own view. Dimension is bound into every job, fingerprint and
cache key; a level change cancels that owner's old outstanding work, removes
their cache/prediction state, and retains the connection for new work in the
destination. Arbitrary mod dimensions and custom generators are not admitted.

The vanilla Options screen now has a `WorldgenAssist` button. The client page
controls whether that client participates on its next connection. The server
pages edit the saved server policy; a remote player must have Minecraft's admin
permission, while the main-menu path edits the local/integrated-server file.
Server changes apply after a server-process restart (a game restart for the
integrated server). JVM properties and environment variables override saved
values, including explicit `false`. Enabling assistance and consenting to
`trusted_raw` seed disclosure remain two separate settings.

Settings are stored under Fabric's config directory as
`worldgen-assist-client.properties` and `worldgen-assist-server.properties`.
Files are size bounded, strictly validated and atomically replaced; invalid
server settings fail closed to disabled/DENY. The settings network channel is
`settings_v1`; it does not change general density protocol CURRENT=2.

## Concurrent-owner assistance (alpha.2)

See `MULTIPLAYER_SUPPORT.md` for the current scope and verification. Both
existing opt-in routes now support multiple accepted workers. The raw route's
`WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT` is a **global** limit, default 8, range
1..64; each owner is limited to one job. Setting the global limit to 1 still
serializes assistance. The fixture has a fixed global limit of 8 and one per
owner, with its original shared persistent 100,000-entry budget. Neither route
enables remote work by default or removes its seed-disclosure gate.

Direct demand uses non-spectator players' own vanilla view ranges. The nearest
accepted worker within that range is selected (UUID breaks ties). If that
owner is busy, generation falls back locally. Prediction is observed per owner;
raw cache keys include owner and connection generation, and disconnect removes
only that owner's entries. Raw validation remains separately opt-in.

The single-player limits and default-one descriptions in the historical
sections below describe alpha.1. Alpha.2 does not claim hostile-server safety,
private-seed confidentiality or a speedup.

[English overview](../README.md) · [日本語の概要](../README.ja.md)

This guide preserves detailed developer/operator configuration from the original
README. Run shell examples from the repository root. Dated verification sections
are historical; consult [the current evidence](TEST_RESULTS_LATEST.md). Remote
work is disabled by default; do not enable trusted-raw mode with a private seed
or untrusted player. Cache/prediction options below belong to that trusted path,
not the separate public-fixture transcript protocol.

## Implementation overview

WorldgenAssist is an experimental Fabric mod for Minecraft Java Edition 26.2.
It investigates whether a player's client can safely compute selected terrain
generation work for chunks requested by that player, while the server remains
authoritative.

Historical verification (2026-09-10): that consolidated run passed 240 tests
in 51 suites. Public-fixture timeout, malformed result, pending reload/disconnect
and second-player behavior have now run in real local Fabric server/client
sessions. Both-installed-JAR multi-PC assisted/vanilla tests also pass: four
installed chunks and 1,005 shared NOISE digests match. Natural client shutdown,
targeted post-disconnect GC checks and pending SSH-forward loss were verified.
No speedup
or private-seed confidentiality is claimed. See [the evidence report](TEST_RESULTS_LATEST.md)
and [multi-PC procedure](MULTIPC_TESTING.md) for exact status and limits.

Phase 5 now adds an optional adversarial-validation layer to the deliberately
narrow player-owned prediction path and compressed remote worker.
With remote mode explicitly enabled, each trusted client can calculate the
dimension-specific interpolated final-density field for an eligible new chunk, encode
it losslessly as RAW or DEFLATE, and return it to a bounded server decoder. The
server validates the result, stores an optional bounded context-keyed cache
entry, and injects only that mathematical intermediate into the
existing `NoiseChunk`, and keeps aquifers, ore veins, block writes, heightmaps,
fluid post-processing, persistence, and later stages authoritative. Timeout,
disconnect, invalid context/result, reload, shutdown, unavailable worker, and
send failure all retain the original local path. When explicitly configured,
the server chooses unpredictable noise cells after receiving a result,
recomputes them independently, and requires bit-exact density equality before
installation or caching.

The default remains remote-disabled. Phase 1's exact-executor `delegate`
baseline is still available; the experimental project-owned `local`
`ForkJoinPool` remains a research backend after failing its performance gate.
The remote path is for trusted participants and vanilla-compatible registry
context, not hostile public servers, arbitrary datapacks, arbitrary dimensions,
or production anti-cheat.

The Phase 0 instrumentation measures the complete
`ChunkStatusTasks.generateNoise` future, including `fillFromNoise` and its
retrogen continuation, and logs:

- chunk coordinates
- elapsed time
- starting and completion thread names
- generated chunk count
- failure count

Example:

```text
[CAWG] stage.complete stage=noise chunk=10,20 elapsed_ms=42.1 started_thread=... completion_thread=... generated_chunks=1 failures=0 active=0
```

### Optional deterministic digest

For a disposable deterministic test world, enable the canonical NOISE-stage
digest before starting the server:

```powershell
$env:WORLDGEN_ASSIST_NOISE_DIGEST = "true"
.\gradlew.bat runServer --args nogui
```

Successful chunks then emit a SHA-256 digest:

```text
[CAWG] stage.digest stage=noise chunk=10,20 format=1 algorithm=SHA-256 digest=... digest_ms=... blocks=... heightmap_longs=... post_processing=... dimension=minecraft:overworld
```

Digest mode deliberately waits at the NOISE boundary while hashing so later
stages cannot race with the snapshot. Leave it disabled for performance
benchmarks.

### Optional worldgen-context fingerprint

To log the server-derived Phase 2 context fingerprint for every noise-based
dimension after server startup:

```powershell
$env:WORLDGEN_ASSIST_CONTEXT_FINGERPRINT = "true"
.\gradlew.bat runServer --args nogui
```

The logger is disabled by default. It emits only the SHA-256 digest, format,
dimension, and capture time; it does not enable remote generation. The digest
is an equality identifier, not a secret or proof that the world seed cannot be
inferred.

### Phase 5 remote PoC

Enable the remote path on a disposable dedicated server:

```powershell
$env:WORLDGEN_ASSIST_REMOTE = "true"
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE = "trusted_raw"
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT = "1"
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS = "2000"
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES = "16"
.\gradlew.bat runServer --args nogui
```

Then connect one client that has the same mod, for example from another shell:

```powershell
.\gradlew.bat runClient --args="--quickPlayMultiplayer localhost:25565"
```

The client handshake and one-thread worker are automatic. The server currently
offloads eligible new chunks in vanilla Overworld, Nether and End with empty
blending, no retrogen or old-noise state, exact `Beardifier.EMPTY`, keyed noise
settings, and one in-flight job per registered owner. Custom datapack registry content that the vanilla client
lookup cannot reproduce fails the context fingerprint and falls back locally.

Configuration bounds are 1–64 global in-flight jobs, 50–60,000 ms timeout, and 0–256
cached results; defaults are 8, 2,000 ms, and 16. Set the cache to zero to
disable it. A daemon watchdog enforces deadlines even if a synchronous server
command is waiting for chunk completion. The response carries at most 98,304
finite doubles, is capped at 787,456 packet bytes, and is decompressed away from
the Fabric networking callback on a bounded single-thread executor. Cache
entries are invalidated on start, datapack reload, and shutdown. Remote enable
alone is fail-closed: the worker is rejected and no job is sent unless
`WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE=trusted_raw` explicitly authorizes the
current raw-seed protocol. That mode is only for a trusted disposable
environment, never a public server. See `SEED_CONFIDENTIALITY.md` for the
verified 26.2 dependency analysis and retained confidential designs.

The first seed-confidentiality experiment records bounded keyed
`NormalNoise`/`BlendedNoise` leaf calls from an authoritative state and replays
them through a state initialized with an unrelated dummy seed. Slice and
standard-height fixtures still match every density bit after a bounded binary
codec round trip. An unregistered job draft uses a server-random opaque context
ID and has no raw-seed or seed-derived-fingerprint field. A server-only
HMAC-SHA-256 authority and bounded authorization envelope detect changes to the
job identity, geometry, and transcript. The server lifecycle now owns context/
key rotation, owner-bound pending claims, expiry/cancellation, and a conservative
100,000-entry disclosure budget per owner per server lifetime. A second
100,000-entry world-local global budget is persisted atomically under the
world `data/worldgen_assist` directory; malformed, mismatched, ambiguous, or
unwritable ledger state disables further draft issuance rather than resetting
the allowance, and an adjacent initialization marker detects partial ledger
loss. A separate
seed-free result draft carries only the fixed claim and up to 98,304 bounded
density values through a strict RAW/DEFLATE envelope. The server gate consumes
the claim before decompression, checks it against retained job geometry, and
passes only validated geometry/values onward. A bounded single-worker executor
performs decode and authoritative `NoiseChunk` cell sampling away from the
network/main threads, invalidates work across reload/disconnect epochs, and
holds capacity until interrupted computation actually exits. The unregistered
client worker replays the transcript using a public dummy seed, while the
server recorder discovers the exact per-job leaf calls. A validated-only
factory can project successful output into the existing density installation
field without reintroducing the protocol-v2 fingerprint. A transport-free
orchestrator now connects recording, durable issuance, owner-token dispatch,
total deadline, validation and connection quarantine. Its one-use installation
offer is rechecked on the supplied generation executor; the original vanilla
operation runs once and any field is cleared before downstream completion.
The opt-in public-fixture adapter now supplies a live Fabric caller and separate
v3 fixture packets, but only for the already public seed `8675309`. General
protocol CURRENT remains 2; confidential remote generation for private worlds is
not enabled. Deterministic transcripts allow candidate-seed checking, so no
confidentiality guarantee follows from omitting the raw seed. See
`SEEDED_LEAF_FIXTURE_PROTOCOL.md` for the narrow exception and test runner.

The latest 2026-09-05 JDK 25.0.4 run passed compile, focused tests, all **233 tests
in 50 suites**, and build with zero failures/errors/skips. Real loopback fixture
transport installed four remote results, all matching independent local-world
NOISE digests (all 1,005 shared coordinates also match). Wrong-seed runtime
refuses dispatch and creates no fixture ledger. Synchronous chunk loads now
cancel remote attempts immediately and use local generation. Three public seeds
cover short slices and standard height, including DEFLATE and forced RAW.
All 98,304 standard-height values match a separate authoritative traversal;
the production executor itself samples 64/768 cells. Latch-backed tests cover
real-exit capacity and lifecycle races, and continuation tests cover one-shot
application, local fallback and cleanup. See `TEST_RESULTS_LATEST.md`,
`SEEDED_LEAF_ORCHESTRATION.md` and `VALIDATION_MATRIX.md` for exact evidence,
contracts and remaining security/runtime gates.

Player-owned prediction is a separate opt-in. It samples only each worker's
own server-side position and movement, never forces a chunk stage, and sends a
bounded density-only job back to that same player:

```powershell
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION = "true"
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION_INTERVAL_TICKS = "20"
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION_LEAD_CHUNKS = "8"
```

The interval is bounded to `1..1200` ticks and lead to `1..8` chunks; defaults
are `20` and `8`. Prediction requires a non-zero cache, shares the normal
in-flight limit, skips chunks already present at `FULL`, scans at most eight
more chunks along the same direction, respects the world border, and remains
disabled by default. Actual demand rechecks all strict direct-generation
eligibility before joining an in-flight prediction or consuming its cache entry.

Server-side probabilistic validation is separately opt-in and remains disabled
by default:

```powershell
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS = "8"
```

The accepted range is `0..64` cells (`0` disables validation). Standard vanilla
geometry has 768 interpolation cells in Overworld, 256 in Nether and 128 in End;
each selected cell contains its dimension-specific number of block-density
values. The server selects cells with `SecureRandom` only after the
client result is fixed, reconstructs an independent vanilla `NoiseChunk`, and
compares every selected value bit-for-bit. Invalid results are not installed or
cached; the producing worker is disabled for the rest of that connection and
the untouched local path completes the chunk. This is probabilistic protection,
not a claim that the mod is safe for hostile public servers.

Real client/server verification on 2026-09-02 completed multiple remote jobs,
exercised watchdog fallback without disconnecting the client, and matched the
complete canonical NOISE-stage output against an independent local world for
all seven compared remote-applied chunks (`7/7`, zero mismatches).

Phase 3 runtime verification on 2026-09-03 processed 21 full-height envelopes;
all used DEFLATE and reduced 786,432 raw density bytes to 307,123–333,518 bytes
(mean ratio 0.4087). Seven compressed remote-applied chunks available in the
independent local baseline again matched the full NOISE digest exactly (`7/7`,
zero mismatches). Lifecycle logs also confirmed bounded cache stores and
reload/shutdown invalidation.

Phase 4 runtime verification on 2026-09-03 completed speculative jobs owned by
the connected player and then consumed three predicted cache entries during
real NOISE-stage demand. Cache-hit completion took `15.93..31.70 ms`; all three
resulting canonical NOISE digests matched an independent local-only world
exactly (`3/3`, zero mismatches). This is a functional sample, not a controlled
performance claim.

Phase 5 runtime verification on 2026-09-03 accepted four honest results with
eight cells / 1,024 values checked per job. Warm validation took
`6.20..6.45 ms` after an `18.95 ms` first invocation. A development-only
modified-client fixture changed every density by one representable step; the
first result was rejected, never cached, the worker was quarantined, and local
NOISE generation completed normally.

### Optional executor benchmark timing

For disposable performance runs, enable task-boundary timing in addition to
the existing full-stage metrics:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BENCHMARK = "true"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
.\gradlew.bat runServer --args nogui
```

This records queue wait and supplier wall-clock compute time with
`System.nanoTime()`, plus current worker-thread CPU time when the JVM supports
it. It is disabled by default and returns vanilla's original supplier
unchanged when disabled. Summarize a matched vanilla/assisted log pair with
[`scripts/Summarize-NoiseBenchmark.ps1`](../scripts/Summarize-NoiseBenchmark.ps1).

### Optional server-tick benchmark timing

For disposable tick-health runs, enable the independent tick logger together
with NOISE task timing:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BENCHMARK = "true"
$env:WORLDGEN_ASSIST_TICK_BENCHMARK = "true"
Remove-Item Env:WORLDGEN_ASSIST_NOISE_DIGEST -ErrorAction SilentlyContinue
.\gradlew.bat runServer --args nogui
```

The logger records the Fabric server-tick event interval, NOISE completion and
active counts, and local-pool point/interval-peak saturation. It is disabled
by default and does not add another project Mixin. Summarize a matched pair
with [`scripts/Summarize-TickBenchmark.ps1`](../scripts/Summarize-TickBenchmark.ps1).
Aggregate validated pair outputs with
[`scripts/Summarize-BenchmarkSeries.ps1`](../scripts/Summarize-BenchmarkSeries.ps1).

### Phase 1 backends

Enable the zero-scheduling-change delegation baseline for a disposable test
world:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BACKEND = "delegate"
.\gradlew.bat runServer --args nogui
```

This crosses the Worldgen Assist backend selection seam, then returns the exact
vanilla `wgen_fill_noise` executor object. It neither adds a wrapper executor
nor creates a worker pool.

Enable the experimental dedicated local backend with:

```powershell
$env:WORLDGEN_ASSIST_NOISE_BACKEND = "local"
$env:WORLDGEN_ASSIST_LOCAL_WORKERS = "1"
$env:WORLDGEN_ASSIST_LOCAL_QUEUE_PER_WORKER = "1"
.\gradlew.bat runServer --args nogui
```

The worker count defaults to `1` and accepts values from `1` through `64`. The
waiting-task ratio also defaults to `1` and accepts values from `0` through
`16`. Queue capacity is `workers * queue-per-worker`, so total local admission
is `workers * (1 + queue-per-worker)`. A task rejected before local execution
begins is submitted to the original vanilla executor instead. Unset the
variables or use
`WORLDGEN_ASSIST_NOISE_BACKEND=vanilla` for the default path.

When benchmark timing is enabled, each task record distinguishes the requested
`backend` from its actual `execution` route (`vanilla`, `vanilla_delegate`,
`local_pool`, or `vanilla_fallback`). This keeps fallback-heavy measurements
from being mistaken for local-pool performance.

This local mode still runs the complete authoritative server-side vanilla
noise task, including aquifer and ore-vein material selection. It is an
executor-boundary prototype, not the future client protocol or its safe
mathematical intermediate format.

The first FIFO scheduler had materially higher per-task queue latency than
vanilla. Replacing it with a bounded-admission async `ForkJoinPool` reduced
that wait. Replacing the historical fixed 1024-slot wait window with the
worker-proportional policy above sharply reduced the maximum admitted work, but
a 19-worker final screen still had 62/648 vanilla fallbacks and materially worse
stage latency. The local pool remains a research backend, not a
performance-equivalent replacement; see
[the benchmark report](BENCHMARK.md).

The `delegate` baseline matched vanilla canonical digests for all 331 observed
NOISE-stage coordinates. Three fresh 648-task pairs completed 1,944 tasks per
mode without failures; queue wait, workload span, throughput, and active/slow
tick counts were equivalent at this fixture's resolution, while per-run stage
deltas changed sign. Gate 2 therefore passes for the backend-selection seam,
not for the dedicated-pool policy. Reproduce the fixed workload with
[`scripts/Run-NoiseBenchmarkFixture.ps1`](../scripts/Run-NoiseBenchmarkFixture.ps1).

## Requirements

- JDK 25
- Minecraft 26.2
- Fabric Loader 0.19.3
- Fabric API 0.156.0+26.2
- Fabric Loom 1.17.20
- Gradle 9.5.1 (provided by the wrapper)

## Build and test

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4"
.\gradlew.bat genSources
.\gradlew.bat test
.\gradlew.bat build
```

The remapped mod JAR is written to `build/libs/`.

To start the development dedicated server:

```powershell
.\gradlew.bat runServer --args nogui
```

Minecraft requires the developer to review and accept its EULA before a test
world can be generated. Do not use a valuable world for worldgen testing.

## Documentation

Start with [the repository instructions](AGENTS.md), then read the
[architecture](ARCHITECTURE.md),
[verified worldgen pipeline](WORLDGEN_PIPELINE.md), and
[Mixin verification ledger](MIXIN_TARGETS.md).

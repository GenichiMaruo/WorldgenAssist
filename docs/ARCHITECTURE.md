# Architecture

## 1. Objective

Client-Assisted World Generation (CAWG) reduces server-side CPU cost during new terrain generation.

The defining fairness rule is:

> Work caused by Player A should normally be assisted by Player A's own client.

This project is **not** a general volunteer compute pool by default.

---

## 2. Trust model

Minecraft server remains authoritative.

The client is treated as an accelerator, not as the owner of world state.

```text
Client
  calculates candidate intermediate data
        |
        v
Server
  validates / accepts / applies
        |
        v
Authoritative chunk state
```

The client should not initially decide:

- final ore placement
- loot
- gameplay-sensitive features
- final persistent chunk ownership/state
- arbitrary blocks

---

## 3. High-level architecture

```text
                         Server
                           |
                   Chunk demand observed
                           |
                           v
                    Job Scheduler
                     /         \
                    /           \
        local backend         remote backend
              |                    |
              |               Player A client
              |                    |
              |               worker threads
              |                    |
              +---------+----------+
                        |
                        v
                  Terrain Result
                        |
                   Validation
                        |
                  Result Apply
                        |
             Remaining server stages
                        |
                        v
                 Authoritative Chunk
```

---

## 4. Core components

### 4.1 `TerrainDensityJob` / `TerrainDensityResult`

The implemented Phase 2/3 request contains only bounded immutable inputs for the
selected interpolated-density boundary:

```text
TerrainJobIdentity
worldSeed
generateStructures
noiseSettings registry ID
minY / height
cellWidth / cellHeight
```

`TerrainJobIdentity` contains:

```text
protocol version 2
server-issued non-zero UUID
canonical dimension Identifier (<= 256 UTF-8 bytes)
Minecraft-valid chunk X/Z
32-byte immutable SHA-256 context fingerprint
```

Player ownership is deliberately server-side state keyed by the job UUID, not a
value the client may assert. `WorldgenContextFingerprint` and density arrays use
defensive copies.

`WorldgenContextFingerprintFactory` defines canonical format `1` for the base-
noise context. It hashes Minecraft/data/protocol versions, the Worldgen Assist
protocol, dimension, seed, structure flag, generation height range, the selected
`NoiseGeneratorSettings` value, complete density-function and noise-parameter
registries, and relevant Minecraft debug flags. Minecraft direct codecs provide
the registry/settings representation; typed binary framing plus sorted JSON
object keys and registry IDs removes iteration-order dependence.

The fingerprint does not serialize per-chunk `Beardifier`, `Blender`, retrogen,
or mutable chunk state. Eligibility proves the initial PoC's empty cases before
a job is sent. `TerrainDensityResult` returns one finite double per block in the
16x16 generation column plus diagnostic client compute time, with a maximum of
98,304 samples.

The request is guarded by a fail-closed disclosure policy. Remote enable alone
leaves the coordinator disabled; only explicit `TRUSTED_RAW` mode permits this
protocol to register a worker and send a seed-bearing job. `DENY` is the
default. This is an admission boundary, not a confidential transport.

Generated Minecraft 26.2 source shows that `RandomState` expands the seed into
the root positional factory, wired noise holders and blended terrain; the
resulting `NormalNoise` and `PerlinNoise` objects retain seeded `ImprovedNoise`
offsets and permutation tables. Serializing that runtime graph would expose a
reusable arbitrary-coordinate terrain oracle and is not an accepted replacement
for the raw seed. The retained confidential candidates and protocol-v3 gate are
specified in `SEED_CONFIDENTIALITY.md`.

The bounded-transcript candidate now has a server-local proof. Two minimal
source-verified hooks record/replay keyed `NormalNoise` and `BlendedNoise`
calls only during an explicit thread-local session. Exact leaf kind, ID, call
order, input bits, output bits, entry bound, and complete consumption are
enforced. A replay state initialized from an unrelated dummy seed matches the
authoritative `NoiseChunk` density bits at both slice and standard chunk height.
A canonical first-use dictionary codec now bounds the trace at 100,000 entries,
64 leaf IDs, and 4,000,000 encoded bytes. Both deterministic fixtures pass
through that codec before replay.

`SeededLeafJob` is a separate, unregistered protocol-v3 research DTO. It binds
a non-zero server job UUID, a 256-bit server-random opaque context ID, exact
Overworld chunk/height/cell geometry, the fixed
`minecraft:overworld_final_density/26.2/seeded_leaf_v1` graph ID, and the
transcript. Its type has no raw-seed or seed-derived-fingerprint field. The
codec is implemented and bounded, but there is intentionally no Fabric payload
registration, cache, live scheduling, or production mode yet. The separate
dummy-seed client computer/worker remains an unregistered in-process component.

`SeededLeafJobAuthenticator` owns a server-only random 256-bit key and computes
a domain-separated HMAC-SHA-256 over the canonical semantic job fields,
including every transcript input and output bit. Verification uses a constant-
time tag comparison. `AuthorizedSeededLeafJob` and its bounded codec carry the
tag before the job. This supplies the draft authenticated binding primitive;
`SeededLeafJobClaim` is the fixed-size response echo.

`SeededLeafJobAuthority` is a synchronized, independent state machine rather
than an adaptation of the protocol-v2 `TerrainJobIdentity` registry, because
that identity contains the seed-committing fingerprint. It allocates non-zero
job UUIDs, retains the full authorized request server-side, binds each request
to one owner, enforces global/per-owner in-flight bounds and monotonic expiry,
and accepts an exact context/tag claim once. Cancellation and disconnect do not
refund the disclosure budget. Datapack reload cancels pending work and rotates
the 256-bit context/key while retaining per-owner disclosed-entry counts.
Aggregate live transcripts are independently capped at 100,000 entries. A job
entering any terminal state releases its full transcript immediately; the
bounded tombstone retains only fixed-size owner/claim/state metadata.
Issuance also charges a world-local global disclosure ledger before an
authorization is created. The ledger persists only maximum/used/sequence
counters plus a non-secret integrity checksum, uses same-directory atomic
replacement, and fails closed on a leftover pending file, corruption,
configuration mismatch, unsupported atomic replacement, or I/O ambiguity.
An adjacent fixed initialization marker distinguishes first creation from
partial loss of an already initialized ledger.

`SeededLeafDensityResult` is the matching seed-free result DTO. It contains the
fixed claim, 1..98,304 finite doubles bounded to absolute value 1,000,000, and a
bounded diagnostic compute time. `SeededLeafDensityResultEnvelope` losslessly
selects RAW or zlib DEFLATE, never permits encoded density bytes beyond the
declared raw length, and has a strict standalone codec capped at 786,545 bytes.

`SeededLeafDensityResultGate` first asks the authority to consume the exact
owner-bound claim. Only that one successful caller receives the retained job
long enough to compare the declared result count with its geometry. It then
performs bounded decompression and finite/range validation, discards the
transcript-bearing job reference, and returns a seed-free geometry/value
projection explicitly marked for later authoritative validation. A wrong owner
or tag cannot trigger decompression, while a malformed response after a valid
claim consumes the one-shot job and falls back. The existing independent
`RemoteDensityValidator` now accepts this projection directly, verifies its
geometry against server-owned `NoiseSettings`, and samples whole cells through
a separate `NoiseChunk` under server-owned `RandomState`.

`SeededLeafResultValidationExecutor` is the bounded single-worker gate around
decompression and authoritative sampling. Reload increments an admission epoch
before cancellation, and disconnect marks an owner inactive before cancelling
its work. A task crossing either boundary cannot return a validated outcome.
Timeout/cancel completes the caller promptly, but capacity is not returned until
the actual worker invocation exits.

`SeededLeafJobSpecRecorder` performs the exact server-side `NoiseChunk`
traversal under an explicit recording session and refuses generator/noise
geometry disagreement. It requires the registry-backed Overworld settings
holder so the declared ID cannot drift from the graph being recorded.
`SeededLeafClientDensityComputer` constructs a
`RandomState` from a fixed public dummy seed and replays the authorized
transcript through the shared sampler. Its bounded client worker retains a
cancelled attempt until computation actually exits. These components are
intentionally unregistered.

`RemoteDensityField.fromValidated(outcome, authority)` is the only seeded-leaf projection into
the existing `NoiseChunk` installation field. It accepts only a successful
authoritative-validation outcome. The field now stores seed-free geometry and
values, so protocol v2 keeps using the same Mixin without forcing the v3
candidate to manufacture a seed-bearing v2 identity. It rechecks the authority
generation and opaque context immediately before conversion. No live seeded-leaf
scheduler/application call invokes this factory yet.

`SeededLeafJobSecurityLifecycle` is registered with Fabric even though no draft
payload is registered. Server start creates a context/key, reload rotates them,
disconnect cancels only that owner, end tick expires work, and server stopping
cancels all work and drops the live session. The default conservative budget is
100,000 transcript entries per owner per server lifetime, plus 100,000 entries
globally for the world across process restarts. The global ledger does not
aggregate related accounts into one real-world principal, and its numeric limit
is not a cryptographic leakage proof. At most 1,024 distinct owner budget
records are retained per server lifetime; a new owner beyond that bound is
rejected.

---

### 4.2 `TerrainComputeBackend`

The project should hide execution location behind an interface.

Concept:

```java
interface TerrainComputeBackend {
    CompletableFuture<TerrainComputeResult> compute(TerrainComputeJob job);
}
```

Implementations:

```text
LocalTerrainComputeBackend
RemoteClientTerrainComputeBackend
```

Benefits:

- local equivalence testing before networking
- clean fallback
- benchmark comparison
- less Mixin logic

#### Completed Phase 1 executor substrate

The implemented Phase 1 vertical slice is intentionally narrower than the
future `TerrainComputeJob` / `TerrainComputeResult` API. A minimal Mixin wraps
the exact `CompletableFuture.supplyAsync` call inside
`NoiseBasedChunkGenerator.fillFromNoise`. Configuration selects one of three
paths:

```text
captured vanilla supplier + executor
              |
       backend selection
        /      |       \
 vanilla   delegate    local
    |          |         |
unchanged   exact same  project-owned bounded-admission
call        executor    async ForkJoin executor
        \      |       /
      original supplyAsync and downstream stages
```

`vanilla` bypasses project backend selection. `delegate` traverses the
abstraction and returns the exact captured vanilla executor object, without a
wrapper, extra future, task resubmission, queue, worker, or lifecycle resource.
`local` substitutes a `WorldgenTaskBackend` executor and is the experimental
dedicated-pool path.

This proves executor ownership, lifecycle, fallback, and deterministic behavior
without copying private vanilla generation code. The submitted `Runnable`
still closes over live server objects, so it is strictly a same-process
backend. It is not a serializable job and must not be reused as the Phase 2
network boundary.

The pool defaults to one worker and one waiting task per worker. Its
non-blocking semaphore admits `workers * (1 + queued-tasks-per-worker)`
commands, where the waiting ratio accepts `0..16`; the default 19-worker shape
therefore permits 19 active plus 19 waiting commands. Capacity or shutdown
rejection falls back to the original vanilla executor before the calculation
command starts. The pool shuts down with a server lifecycle and is recreated
when another integrated or dedicated server starts in the same JVM. The full
noise task remains server-side and authoritative, including aquifer and
ore-vein decisions.

Opt-in task timing records both the requested backend and actual execution
route. Delegation is `vanilla_delegate`, a locally accepted task is
`local_pool`, and a capacity/rejection fallback is `vanilla_fallback`. This
route context is scoped to execution and restored after nested calls. Warnings
are rate-limited to the first and every 64th fallback, while counters and task
records retain exact totals.

The first implementation used a fixed `ThreadPoolExecutor` with one FIFO
queue. Repeated measurement found much more queue wait than vanilla. Generated
Minecraft source then established that vanilla's shared background scheduler
is an asynchronous-mode `ForkJoinPool`, so the current dedicated scheduler
uses the same pool policy while retaining bounded admission and project-owned
threads.

This reduced queue latency relative to the historical FIFO series, but it did
not make the dedicated same-machine pool latency-equivalent. The original
async-ForkJoin comparison used a fixed 1024-task wait window and averaged 44.26
ms per stage for local versus 22.03 ms for vanilla. The window was subsequently
redesigned to scale with worker count. A final default-ratio route-aware screen
bounded the 19-worker pool to 38 admitted tasks, but 62/648 tasks fell back and
aggregate local-mode stage latency was still 40.66 ms versus 22.93 ms. The
separate local pool competes with vanilla's still-active background pool for
the same CPUs; the fallback route was also slow under that contention.

The dedicated backend therefore proves lifecycle/fallback/determinism and
exposes a real scheduling boundary, but is not an acceptable performance-
equivalent baseline. The separate `delegate` baseline matched 331/331 vanilla
digests and completed three 648-task pairs on the exact vanilla executor. Its
per-run stage deltas changed sign, while queue wait, workload span, throughput,
and active/over-budget tick counts were equivalent at the fixture's resolution.
Gate 2 passes for the backend-selection seam and remains failed for the
dedicated-pool policy. This supplied the prerequisite for Phase 2's later
immutable protocol work, but does not make executor substitution or remote
execution free.

#### Phase 1 tick-health observability

An opt-in logger uses Fabric server lifecycle and tick events; it adds no new
project Mixin. The measured interval begins at Fabric's START event immediately
before `MinecraftServer.tickChildren(BooleanSupplier)` and ends at its END
event at the tail of `tickServer`. It correlates that duration with cumulative
NOISE completion/failure counters, NOISE active point samples, local
ForkJoinPool state, exact active/admitted counts, and their per-interval peaks.

Completion deltas are based on the previous tick end rather than only the
current start. This retains asynchronous completions that occur between tick
event intervals. Point samples describe state at an instant; resettable peaks
describe the local load observed anywhere in the interval. Both are needed
because a short burst can be idle again before `END_SERVER_TICK`.

The repeated fixed-window 648-task tick/CPU fixture confirmed that this
dedicated-pool shape is not a negligible-overhead baseline. Local stage latency
averaged 50.83 ms versus 23.69 ms, almost entirely from queue wait, while its
small apparent worker-CPU difference was below the host counter's coarse
resolution and did not cover total server work. Conditional NOISE-active tick
mean/p95 and the over-budget share were also higher for local. The later
worker-proportional default-ratio screen reduced the maximum admitted peak from
137 to 38, but local still had two over-budget active ticks out of 16 versus one
out of 19 for vanilla and materially higher stage latency. This design
therefore fails the performance/tick portion of Gate 2 even though its
deterministic output, tighter bound, and lifecycle/fallback behavior remain
valid. By contrast, the exact-executor `delegate` series had the same 50
NOISE-active and three over-budget ticks per mode across three runs; its pooled
active-tick mean differed by 5.63 ms. This supports the seam-only Gate 2 result
without changing the dedicated-pool conclusion.

---

### 4.3 `RemoteWorldgenManager`

Server-owned coordinator.

Responsibilities:

- choose backend
- allocate job ID
- bind job to requesting player
- send request
- track timeout
- accept/cancel response
- claim and decode a response off the networking thread
- trigger validation
- consult/store the bounded density cache
- fall back to local compute
- record metrics

It must not contain raw packet parsing.

`RemoteWorldgenManager` now connects Fabric payload handlers and lifecycle
events to the network-independent `RemoteJobCoordinator`, `WorkerRegistry`, and
`PendingTerrainJobRegistry`. The registry atomically binds each server-issued
job UUID to the owning player UUID and full identity, enforces global/per-owner
bounds, tracks monotonic deadlines, and retains bounded terminal tombstones.
Exact responses can win only once; wrong-owner, wrong-identity, duplicate,
cancelled, expired, and unknown responses are rejected without affecting other
jobs.

The manager extracts only an eligible Overworld density job and first consults
the context-rich density cache. On a miss it sends the job to the sole worker,
claims the response exactly once, and decompresses it on a single bounded
`CAWG-RemoteDecode` executor. It validates the result, stores an immutable copy,
installs it into the existing `NoiseChunk`, then invokes the original
`ChunkStatusTasks.generateNoise` path. Tick timeout,
an independent daemon watchdog, disconnect, reload, shutdown, send failure,
client rejection, and validation failure all complete through untouched local
generation. The watchdog avoids a deadlock-like wait when the server main thread
is synchronously waiting for chunk completion and therefore cannot dispatch the
result packet.

---

### 4.4 `WorkerRegistry`

Tracks client capability.

Implemented logical state:

```text
player UUID (server-side connection identity)
protocol version
implementation version
accepted maximum in-flight jobs
current in-flight jobs
```

A worker is associated with its own player. The initial fairness scope admits
remote work only when exactly one compatible worker is registered; multi-player
routing and health/reputation metrics remain later work.

---

### 4.5 `ClientWorldgenWorker`

Client-side execution service.

Implemented responsibilities:

- receive and validate a bounded job
- reject unsupported/mismatched context explicitly
- queue at most one waiting task
- execute on one dedicated worker thread
- reconstruct vanilla `RandomState`/`NoiseChunk` and sample density
- losslessly encode/compress and send a bounded result
- handle cancellation and disconnect

Heavy terrain computation runs on `CAWG-RemoteWorldgen-1`, never the render or
network callback thread. The client uses a vanilla worldgen registry lookup; a
server datapack context it cannot reproduce fails closed to local generation.

---

### 4.6 `RemoteDensityResultCache`

Phase 3 implements a synchronized access-order LRU. Its bounded capacity is
configurable from `0..256` entries, defaults to `16`, and can be disabled with
zero. The implemented key is:

```text
cache lifecycle generation
dimension
chunk coordinate
worldgen context fingerprint
noise-settings identifier
minimum Y / height / cell width / cell height
```

The fingerprint already commits to Minecraft/protocol versions, seed, settings,
and relevant registries. Values are defensively copied. Start, datapack reload,
and shutdown invalidate all entries and advance the cache generation, preventing
an in-progress pre-reload decode from reintroducing stale data. A hit takes the
same live eligibility/application route under a fresh job identity. With Phase
5 enabled, direct and predicted values pass sampled recomputation before they
enter the cache, so a hit consumes an already validated defensive copy. Direct
on-demand generation rarely revisits a chunk's NOISE stage. Phase 4 now uses
the cache for completed predictions; actual demand still constructs a fresh
direct eligibility context before it may consume an entry.

---

### 4.7 `RemoteDensityValidator`

Phase 5 is an optional server-authoritative verification boundary. Its sample
count is bounded to `0..64` whole interpolation cells and defaults to zero.
After a client response is fixed, a server `SecureRandom` selects unique cells.
A separate vanilla `NoiseChunk`, built from the authoritative level
`RandomState` and exact noise settings, recomputes every density in those cells.
The comparison is bit-exact.

Direct results are checked before cache insertion or installation. Predictions
are checked before entering the cache, so no unverified speculative result can
later become a cache hit while validation is enabled. A mismatch removes the
keyed cache value, quarantines the owner for that connection, cancels its other
attempts, and invokes the untouched local supplier. Validation never mutates
the live cached `NoiseChunk`.

Malformed claimed results trigger the same connection quarantine immediately.
Ordinary timeout fallback remains available for transient slowness; a
successful decoded result resets health, while three consecutive timeouts open
the circuit for the remainder of that connection.

---

## 5. Initial execution path

Implemented networked PoC:

```text
Server needs chunk stage
        |
        v
Can Player A assist?
   |             |
  no            yes
   |             |
local         cache lookup
compute       /          \
   |       hit            miss
   |        |              |
   |        |           send to A
   |        |              |
   |        |        client compute
   |        |              |
   |        |        compress/result
   |        |              |
   |        |        bounded decode
   |        |              |
   +--------+----------> validate
                 |
                 v
               apply
                 |
                 v
        continue vanilla pipeline
```

Timeout:

```text
remote request
    |
    +---- result before deadline ---> normal remote completion
    |
    +---- timeout ------------------> local fallback
```

The timeout path must not leave a partially mutated authoritative chunk.

The actual result is not a `Terrain Result` containing blocks. It is the
interpolated `fullNoiseDensity` field. The server substitutes only that cache
fill; vanilla aquifer/ore material selection and `doFill` perform every
authoritative mutation.

---

## 6. Speculative execution

Phase 4 implements an opt-in, bounded player-owned predictor:

```text
Player A motion + view distance
            |
            v
      predictor
            |
      candidate chunks
            |
            v
Client A precomputes SAFE INTERMEDIATE result
            |
            v
       server cache
            |
       future demand
            |
          cache hit
```

On each configured interval (default 20 ticks), the server observes only the
sole registered worker player's chunk and normalizes movement to a direction.
A dimension change or a jump over four chunks resets the observation. The
candidate is the current chunk plus effective view distance and the configured
lead (default 8, bounded `1..8`) in that direction. Effective view distance is
the Minecraft 26.2 rule `clamp(requestedViewDistance, 2, serverViewDistance)`.

The scheduler respects `ChunkPos.isValid` and the world border, skips candidates
already loaded at `FULL`, scans at most eight additional chunks, and never asks
Minecraft to create or advance a chunk. It admits only the same player's sole
worker through the normal global/per-owner in-flight bound. A pending direct
demand may join the exact keyed speculative future; a completed result enters
the context-keyed LRU. Both routes re-enter the normal validated density
application path, while any failure leaves vanilla local generation available.

This predicts only a mathematical density field with empty blender/beardifier
assumptions. At real demand, the full direct eligibility checks—including live
retrogen, blending, and exact `Beardifier.EMPTY`—run again before the cached or
joined result can be installed. Start, reload, shutdown, and owner disconnect
clear associated speculative state. The 2026-09-03 fixture consumed three
predictions and matched independent local NOISE digests `3/3`.

Important distinction:

A speculative job should ideally avoid forcing the server to perform expensive earlier chunk stages solely to prepare inputs for a chunk that may never be visited.

---

## 7. Fairness policy

Default:

```text
A's demand -> A's worker
B's demand -> B's worker
```

Allowed fallback:

```text
A unavailable -> server local compute
```

Not default:

```text
A's demand -> B's worker
```

A future optional volunteer mode may allow shared compute, but it must be explicit and opt-in.

---

## 8. Server authority boundaries

Initially keep these server-side:

- structure ownership / authoritative structure state
- feature placement
- ore placement
- gameplay-sensitive decoration
- chunk persistence
- entities / spawning
- final validation
- fallback generation

Likely remote candidate:

- deterministic compute-heavy terrain/noise-related intermediate work

The current remote candidate is the per-block interpolated final-density field
before aquifer and ore material rules. Lossless compression and a bounded
context-keyed result cache, player-owned speculative dispatch, and optional
sampled validation are implemented. Broader worldgen contexts remain later
work.

---

## 9. Failure behavior

Every remote operation must define:

### Disconnect

Cancel all jobs owned by that client and fall back when needed.

### Timeout

Complete remote attempt exceptionally or as timeout and invoke local backend.

### Invalid payload

Reject. Never partially apply. A bit-exact sampled-validation mismatch also
quarantines the producing worker for the rest of its current connection.

### Mismatched worldgen context

Reject and request/perform context resynchronization or use local backend.

### Duplicate response

Ignore after job completion.

### Late response after fallback

Discard. The current cache stores only timely, fully validated successful
results; expired or cancelled responses cannot populate it.

### Server shutdown

Cancel pending futures without waiting indefinitely.

---

## 10. Data ownership

Prefer immutable DTOs across asynchronous boundaries.

Avoid passing live mutable server chunk objects to client-specific scheduling code.

Recommended shape:

```text
live Minecraft state
      |
extract immutable input
      |
TerrainComputeJob
      |
async backend
      |
TerrainComputeResult
      |
validate
      |
apply on appropriate server execution context
```

---

## 11. Performance definition

The project is successful only if it improves a useful metric.

Possible goals:

1. Reduce server CPU time per generated chunk.
2. Reduce server worldgen worker saturation under multiple players.
3. Improve time-to-visible-chunk for a client.
4. Improve generated chunks/sec without reducing tick health.

Remote total wall latency may be slightly higher than local generation while server CPU savings are still valuable.

Therefore measure both:

```text
server CPU cost
end-to-end latency
```

Do not optimize one blindly.

---

## 12. Design non-goals

The project is not initially:

- a replacement world generator
- a distributed filesystem
- a cross-server compute network
- a blockchain / proof-of-work system
- a client-authoritative world
- a full anti-cheat solution
- a GPU terrain generator

Keep the research question focused.

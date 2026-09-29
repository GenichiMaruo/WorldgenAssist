# Remote Protocol

## Alpha.5 release protocol

`0.1.0-alpha.5+mc26.3` packages protocol **4** and the candidate changes below.
Alpha.4 remains on protocol 3; upgrade the server and participating clients
together. Final distribution contents match the latest saved candidates except
version metadata. Historical checkpoints retain their original identities;
see `releases/v0.1.0-alpha.5+mc26.3-verification.md` for current evidence scope.

## Result dispatch and optional request batches (2026-09-29)

Development protocol remains **4**: job/result identity and density encoding
are unchanged. `terrain_job_batch` is an additive, optional clientbound channel
with 1..4 complete jobs. Its codec bounds the count before allocation and
rejects repeated job IDs. Fabric sends it only when the client advertises that
channel; otherwise the original individual requests are used. Every job keeps
its own registration, owner, deadline, cancellation and immediate result packet.
There is no per-job extra approval round trip or wait for other results.
Native loaders currently use individual requests through the shared sender's
fallback. Published protocol-3 artifacts are unchanged.

Fabric accepts results using a hello-approved connection/owner snapshot before
the server main-thread packet queue. The event thread only submits to the
bounded existing decoder executor. Claiming/rechecking owner/deadline/identity
and decoding run there; revoked connection work cannot be decoded/completed.
Unknown/revoked connections are dropped rather than handed to UUID-only main
handling. The existing density validation and final application gates remain.
Client reply senders capture one connection and are revoked on disconnect,
join/rejection or an observed dimension change. See `WORLDGEN_PIPELINE.md` for
new timestamp definitions. The focused gate
`network-dispatch-gate-20260929-215751-919` passed: both owners' results matched
vanilla, 276 accepted receipts used the network route and ten batch packets
were exercised. Six affected tests and all three loader builds passed; only
Fabric runtime was selected and no performance claim follows from this gate.

The same candidate's later constrained-server measurement confirms that the
new Fabric ingress path was present in the tested JAR, but its shortened RTT
endpoint cannot by itself establish a latency reduction. In three repeats the
server 9-by-9 region proxy improved while both-client 81-chunk receipt was
slightly slower and total throughput nearly unchanged. See
`TEST_RESULTS_LATEST.md` for exact conditions and values.

## Adaptive demand waiting (2026-09-29, focused gate complete)

The new candidate keeps protocol 4 and the same owner/validation/disclosure
gates. Within the enabled pipeline, the configured demand wait is now a base
budget. Successful validated turnarounds update an owner-specific exponential
mean/deviation estimate; initial cold estimate is 150ms, and the margin is 25ms.
The initial wait accounts for the time already spent on a prefetch request.
Waiting is bounded by twice the base, never above 1000ms (default base 100,
maximum 200). A decoded response waiting for validation can receive one grace
of at most 25ms within that same ceiling. Timer expiry still cancels the work,
clears retained output and uses the original local continuation.
Completion also checks the absolute ceiling, so a delayed timer callback cannot
allow a result past that ceiling.

After three samples, direct requests whose estimated turnaround exceeds the
ceiling are skipped only while that owner has active ahead work. With no active
ahead work, bounded direct admission remains available to start the owner's
pipeline; a slow estimate cannot exclude an owner completely. Ahead computation
remains eligible: it can finish before actual demand. Owner/context invalidation
clears latency learning. Setting
`WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT=false` (or
`worldgen_assist.remote.adaptive_demand_wait=false`) restores the fixed base
budget. With both original pipeline switches off, the comparison baseline
still uses the configured request timeout.

Generation candidate observation, fingerprint completion and remote
preparation completion now enqueue a coalesced server task to refill available
prefetch slots between ticks. A server/context token prevents stale queued
tasks from dispatching after invalidation. All dispatch still occurs on the
server thread; the original tick dispatcher remains a backstop. Packet
reception and client sending are unchanged, so their queue delays still exist.
This does not establish a speedup for the new artifact.

Cached density now retains the original immutable result and server-issued job
ID through consumption/application instead of synthesizing a fresh ID. Cache
insertion checks coordinate/context identity as well as shape; owner/epoch key
checks remain authoritative. This preserves tracing and allows the installed
fixture to prove cached assistance actually applied for each owner. It also
avoids cloning/revalidating the density while holding the cache state lock.
When the bounded generation-candidate queue is full, an underrepresented owner
can displace one candidate of an owner with at least two more entries. Capacity
is unchanged; round-robin dispatch can now receive candidates for both owners.

The selected gate passed seven affected JUnit tests, Fabric build and an
installed two-owner Overworld correctness pair on a two-logical-CPU server.
Both required applied chunks and all 1,822 shared NOISE digests matched vanilla;
the owners' successful jobs overlapped. Runtime logs exercised 100–200ms wait
budgets. Evidence is `demand-wait-gate-20260929-200658-043`. A matched three
repeat benchmark on a two-logical-CPU server then found assisted region
completion 13.5% slower and client receipt 10.6% slower; see
`TEST_RESULTS_LATEST.md` for measurements and limits.

## Pipelined 26.3 candidate (2026-09-29, selected gate complete)

Protocol remains 4; payload shapes are unchanged. Clients reuse locally
verified fingerprints and `RandomState` objects, at most three contexts per
worker. Every request still checks the expected fingerprint. Connection and
dimension changes replace the session. New logs separate preparation, worker
queue, total worker work and render-thread send wait; compute time is still
density sampling only.

One bounded server worker prepares secret random validation samples while the
client calculates. Comparison waits for both preparation and decoded result.
Failure cancels both paths promptly. Admission covers the remote wait and
preparation under the global and adaptive owner caps; cancelled running
preparation retains its slot until the real invocation exits. Sample selection
and coverage are unchanged. Preparation timing now includes sampler binding.

An observer at `generateStructureStarts` records bounded metadata for actual
generation within current owner demand, restricted to the exact vanilla noise
generator. Server-tick dispatch interleaves owners and prioritizes nearby
candidates; it never advances a stage or loads a chunk. Fingerprint preparation
runs off the main thread. Actual TERRAIN demand rechecks all existing
blender/beardifier/retrogen/geometry/epoch gates. Validated results are consumed
once and expire after the request timeout. Movement prediction remains lower
priority and reserves one owner slot.

Remote policy stays opt-in. Within enabled mode, reuse and validation
preparation default on; planned prefetch defaults on with nonzero cache
capacity. A/B controls are `WORLDGEN_ASSIST_CLIENT_REUSE_CONTEXT`,
`WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION`, `WORLDGEN_ASSIST_REMOTE_PREFETCH`,
and `WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS` (5..1000, default 100). JVM property
equivalents use `worldgen_assist.client.reuse_context` and
`worldgen_assist.remote.<lowercase suffix>`. With both server pipeline switches
off, demand keeps the existing request timeout for the comparison baseline.
Otherwise the short TERRAIN wait budget is independent of request lifetime;
a result arriving after fallback cannot install.

`rtt_ms` now ends at payload-handler arrival rather than after validation.
It includes setup, client scheduling and waiting for the server packet processor
to enter the handler; it is not timestamped at socket arrival. `estimated_transfer_ms` remains an
unattributed residual, not pure network time. Decoder queue and validation
preparation/queue/comparison have separate logs. `chunk.full_ready` marks full
conversion, not delivery or rendering. The selected Fabric two-owner correctness
pair and eight matched constrained-server performance scenarios passed. No
client-receipt speedup versus vanilla was established. See `TEST_RESULTS_LATEST.md`.

## 26.3 development candidate after alpha.4

The current unpublished scheduler keeps protocol `CURRENT=4`: the existing
`WorkerHelloPayload.maxParallelJobs` field advertises the client's configured
worker count, so no wire shape or version change is needed. The client reads
`worker_threads=1..4` from `worldgen-assist-client.properties` (default 2),
or `-Dworldgen_assist.client.worker_threads=1..4` at startup. Invalid values
fall back to one worker. The server caps each owner at the lower of four,
the advertised count, and the configured global limit. It starts at one
in-flight job, adds a slot after two successfully applied or validated results,
and halves its current limit after a timeout, decode failure, or client failure.
The global in-flight cap and server-authoritative validation remain in force.
Prediction reserves one owner slot for direct player-demand work; until an
owner has at least two active slots it does not dispatch predictions. Direct
results are no longer retained as duplicate full-density cache entries; only
completed predictions enter that cache. The bounded server result decoder has
up to two workers. `-Dworldgen_assist.remote.diagnostics=true` emits a 200-tick
summary of local fallback categories, submissions, pending jobs, and decoder
queue depth. These controls do not establish a measured speedup.

The unpublished candidate increments the **general trusted-raw** protocol to
`CURRENT=4`. The 26.3 sampler produces exact IEEE-754 `float` values; v4
transports their raw 32-bit patterns in big-endian order before optional
DEFLATE, halving the maximum uncompressed result from 786,432 to 393,216
bytes. A decoded value is widened exactly to the existing in-memory `double`
field. Encoding rejects a value that cannot round-trip through `float`,
including a changed signed zero. The 26.2 public fixture's separate v3 family
is still unavailable on 26.3.

The candidate admits a custom dimension ID only when the server's generator
uses the vanilla `NoiseBasedChunkGenerator.buildTerrain/doFill` density path,
the noise settings are keyed, and the existing geometry, empty blender,
empty beardifier, retrogen, owner, and context checks pass. A custom wrapper
must explicitly implement `RemoteDensityCompatibleGenerator` and return the
noise delegate it actually invokes. Prediction stays restricted to the exact
vanilla generator class. Unsupported generators fall back locally.

Generated 26.3 `RegistryDataLoader.SYNCHRONIZED_REGISTRIES` includes dimension
types but not noise settings, density functions, or noise parameters. The
worker still creates `VanillaRegistries.createWorldLookup()`; therefore a
datapack-only custom noise registry that the client cannot reproduce fails
closed. A custom dimension using matching built-in noise settings is the
focused runtime fixture. The selected Fabric protocol-4 fixture and Overworld
assisted/vanilla pairs passed with one matching remotely applied chunk each
(841 and 941 shared matching NOISE digests). Focused Forge/NeoForge builds
passed, but their custom-dimension runtime paths were not checked. See
`TEST_RESULTS_LATEST.md`; alpha.4 release evidence remains historical.

## Minecraft 26.3 development branch

The sections below describe the published **26.2** line unless explicitly
stated otherwise. The published alpha.4 Fabric, Forge, and NeoForge 26.3
implementations send full-block float-exact density volumes and set general
`CURRENT=3`; the
separate public-seed fixture is unavailable. Selected installed-artifact
correctness runs and their limits are recorded in `PORT_26_3.md` and
`TEST_RESULTS_LATEST.md`. Do not interpret the
fixture's historical v3 packet family as the new general protocol.

## Separate public-fixture family — 2026-09-05

Protocol `CURRENT` remains 2. An explicitly enabled `seeded_leaf_fixture_v3_*`
family now wraps the bounded authorization/result/claim codecs. Hello is a
four-byte version; acceptance is version 3 plus one strict boolean byte. Requests
and results use Fabric `registerLarge` with the existing exact maximum encoded
sizes; failure/cancel carry the fixed authenticated claim. See
`SEEDED_LEAF_FIXTURE_PROTOCOL.md` for opt-in and fixed-public-world gates. Earlier
"unregistered" descriptions below record the baseline; they do not describe the
new fixture exception or permit private-world activation.

## Status

Phase 5's optional server-side cell validation, Phase 4's player-owned
prediction path, and Phase 3's compressed network/cache path are implemented.
The current alpha.3 scope is Minecraft 26.2, trusted clients, new
chunks in vanilla Overworld/Nether/End, one job per owner under a global bound,
and server-authoritative application. Exact alpha.3 verification is in
`TEST_RESULTS_LATEST.md`. Remote mode is disabled
unless the server explicitly sets both
`WORLDGEN_ASSIST_REMOTE=true` and
`WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE=trusted_raw` (or the equivalent JVM
properties `worldgen_assist.remote=true` and
`worldgen_assist.remote.seed_disclosure=trusted_raw`). The first flag alone is
fail-closed: the server rejects worker registration and dispatches no job.
Prediction remains separately disabled unless
`WORLDGEN_ASSIST_REMOTE_PREDICTION=true` or
`-Dworldgen_assist.remote.prediction=true` is set.
Validation is also separately disabled by default; set
`WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS=1..64` or the equivalent
`worldgen_assist.remote.validation_sample_cells` system property to enable it.

The client returns an interpolated final-density field, never block states. The
server continues vanilla `NoiseChunk` processing for aquifers and ore veins and
keeps block writes, heightmaps, fluid post-processing, persistence, and all
later stages local. This is not suitable for an untrusted public server.

## Message families

All payloads use Fabric play networking and explicit `StreamCodec` instances:

```text
client -> server  WorkerHelloPayload
server -> client  WorkerAcceptedPayload
server -> client  TerrainJobRequestPayload
client -> server  TerrainJobResultPayload       (bounded large payload)
client -> server  TerrainJobFailurePayload
server -> client  TerrainJobCancelPayload
client <-> server SettingsPayload (`settings_v1`, bounded fixed fields)
```

The alpha.3 `settings_v1` payload carries an action, settings
revision, nonnegative request ID and bounded validated settings. The client
allocates distinct IDs across settings-screen instances; the server echoes the
request ID for STATE, SAVED, DENIED, STALE, IO_ERROR and RATE_LIMITED. After a timeout or a
new request, the screen ignores a delayed response with another ID. The server
checks current-player identity and `COMMANDS_ADMIN` before reading or saving
policy, then serializes optimistic revision decisions on its server thread.
The settings channel does not change general terrain protocol `CURRENT=2` or
the public fixture's dedicated v3 gate.
The 250 ms per-owner settings request limit returns RATE_LIMITED with a retry
message; it does not silently drop a rapid Save after Read.

`WorkerHelloPayload` advertises protocol version, requested parallelism, and a
bounded implementation-version string. The published alpha.3 client advertises
one thread. The server accepts the exact current protocol only and caps the result
at its configured per-worker limit.

Every job-bearing message contains the complete `TerrainJobIdentity`:

```text
protocol version: current value 2
job ID: server-issued non-zero UUID
dimension: canonical Identifier, at most 256 UTF-8 bytes
chunk X/Z: accepted by Minecraft 26.2 ChunkPos.isValid
context fingerprint: exactly 32 SHA-256 bytes
```

The owning player UUID is never client-selected. `PendingTerrainJobRegistry`
binds the server-issued job ID and full identity to the connection/player that
received it.

## Terrain density request

`TerrainDensityJob` adds only immutable values needed to reconstruct the
eligible mathematical context:

```text
TerrainJobIdentity
raw world seed
generate-structures flag
keyed NoiseGeneratorSettings identifier
minimum Y
generation height
cell width
cell height
```

Geometry is constructor-validated. Height is limited to `1..384`; cell width is
a positive divisor of 16; cell height is a positive divisor of the height; and
minimum Y must align with cell height. The noise-settings identifier is limited
to 256 UTF-8 bytes. The maximum job shape is 16 x 16 x 384.

The vanilla shapes resolved from generated 26.2 source are Overworld
`-64/384/4x8`, Nether `0/128/4x8`, and End `0/128/8x4`. Eligibility requires
the complete generator noise range to fit inside the level height; it does not
mistake Nether/End's taller build space for density height.

The raw seed is deliberately disclosed only in explicitly authorized
`TRUSTED_RAW` mode. The default `DENY` mode sends no job at all. The context
fingerprint is an equality identifier, not a confidentiality mechanism. See
`SEED_CONFIDENTIALITY.md`; removing this field without replacing seeded
`RandomState` execution cannot produce the same vanilla density.

The experimental `SeededLeafTrace` is not part of protocol version 2 and cannot
be selected by configuration. Its transcript now has an unregistered bounded
binary codec and an unregistered `SeededLeafJob` draft. The draft carries a
server-issued UUID, a 256-bit server-random opaque context ID, exact Overworld
geometry, a fixed 26.2 graph ID, and the transcript. It has no raw-seed or
seed-derived-fingerprint field. A server-only HMAC-SHA-256 primitive binds all
semantic job fields and its tag has a bounded authorization-envelope codec.
The fixed-size `SeededLeafJobClaim` echoes only UUID, opaque context ID, and tag.
An independent synchronized authority retains the full job, binds it to the
owner, enforces timeout/cancellation and in-flight limits, and consumes exactly
one matching claim. Its Fabric server lifecycle creates/rotates/drops the key
and context and keeps a non-refundable 100,000-entry per-owner disclosure budget
across disconnect and datapack reload. Live pending transcripts have a separate
100,000-entry global cap and are dropped at terminal transition; tombstones keep
only fixed-size claim metadata. A second 100,000-entry world-local cumulative
budget is persisted with atomic replacement before authorization. Ledger
corruption, a leftover pending write, maximum mismatch, or unavailable atomic
replacement makes issuance fail closed. A fixed adjacent initialization marker
also detects partial deletion of an initialized ledger.

The draft now also has an unregistered `SeededLeafDensityResult` and
`SeededLeafDensityResultEnvelope`. The result contains only the fixed claim,
1..98,304 bounded finite doubles, and bounded diagnostic timing. The envelope
uses lossless RAW or zlib DEFLATE, limits encoded density data to the declared
raw length, and its strict standalone codec is capped at 786,545 bytes. The
server gate consumes the exact owner/context/tag claim before decompression,
checks the count against the retained job geometry, rejects malformed or
out-of-range decoded values, then emits only seed-free geometry and values.
The existing independent `NoiseChunk` validator now consumes that projection,
first checks it against server-owned `NoiseSettings`, and performs the same
whole-cell bit-exact sampling used by protocol v2 under server-owned
`RandomState`. Shape or data failure after a valid claim consumes it;
wrong-owner/context/tag attempts do not. A bounded single-worker executor now
performs the decompression and sampling stages. It prevents reload/disconnect
races with admission/owner epochs and holds its capacity permit until the real
worker invocation exits, including after timeout notification.

The unregistered server recorder can discover an exact job transcript and the
unregistered client computer/worker can replay it under a fixed public dummy
seed. A validated-only factory converts a successful outcome to the existing
seed-free density installation field. No draft Fabric payload type, live
scheduler/application call, or protocol-version increment exists yet.

The next integration seam now exists as `SeededLeafJobOrchestrator` and
`SeededLeafGenerationContinuation`, still without a runtime caller. The former
uses one server-issued connection token, off-thread recording/durable issuance,
a total-attempt deadline, one-shot validation and connection quarantine. Only a
successful validation produces a one-use installation offer; the latter checks
that offer again on the supplied generation executor and clears any installed
field before completing the original vanilla future. A failed remote attempt
uses the original operation once without a field. See
`SEEDED_LEAF_ORCHESTRATION.md` for the non-blocking exchange contract and the
remaining Fabric-thread/lifecycle integration gate.

## Terrain density result

The client first produces a `TerrainDensityResult` containing:

```text
TerrainJobIdentity
1..98,304 IEEE-754 doubles
client-reported compute nanoseconds
```

The canonical density index is:

```text
(yOffset * 16 + xOffset) * 16 + zOffset
```

Every density must be finite and have absolute value at most `1,000,000`.
Client timing is diagnostic, bounded to one hour, and never authoritative. The
server also requires the count to equal the exact request shape before applying
the field.

Protocol version 2 wraps that value in `TerrainDensityResultEnvelope`. Density
doubles are converted to canonical big-endian bytes and encoded as either:

```text
RAW      exact count * 8 bytes
DEFLATE  zlib stream, selected only when smaller than RAW
```

The payload carries identity, density count, encoding byte, encoded byte count,
encoded bytes, client compute nanoseconds, and client encode nanoseconds. The
result payload remains registered through Fabric's large-payload splitter with
a maximum packet size of `787,456` bytes (`98,304 * 8 + 1,024`). Count,
encoding, and encoded length are validated before the encoded byte array is
allocated. The encoded density section may never exceed its declared raw size.

Server decompression runs on one dedicated `CAWG-RemoteDecode` daemon with a
queue bounded by the configured in-flight limit. A response claims its pending
job before entering that queue, so duplicates cannot multiply decompression
work. DEFLATE must finish at exactly `count * 8` bytes with no dictionary,
truncation, expansion, or trailing input; malformed data fails the remote
attempt and uses the untouched local path.

## Context fingerprint

`WorldgenContextFingerprintFactory` computes canonical format `1` on both
sides. It commits to:

- Worldgen Assist context/protocol versions;
- Minecraft build ID, data version/series, and game protocol version;
- dimension ID, raw seed, structure-generation flag, minimum Y, and height;
- selected keyed noise settings encoded with
  `NoiseGeneratorSettings.DIRECT_CODEC`;
- every density-function registry entry encoded with
  `DensityFunctions.DIRECT_CODEC`;
- every noise-parameter registry entry encoded with
  `NormalNoise.NoiseParameters.DIRECT_CODEC`;
- debug flags that alter aquifers, fluids, ore veins, or half-world generation.

The preimage is domain-separated and typed. Integers are big-endian; UTF-8
strings are length-prefixed; registry IDs and JSON object keys are sorted; JSON
array order remains significant.

The client constructs a vanilla worldgen lookup before joining. Vanilla server
contexts therefore match without relying on the play registry synchronization,
which does not include noise settings. A custom datapack or registry change that
the client lookup cannot reproduce is rejected as unsupported or as a context
mismatch and uses local generation.

The fingerprint does not encode live `Blender`, `Beardifier`, retrogen, or
mutable chunk state. Those are checked per job.

## Eligibility and calculation

Before submission the server requires:

- one of `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`,
  and `NoiseBasedChunkGenerator`;
- no old-noise-generation marker or below-zero retrogen;
- `Blender.isEmpty()`;
- exact singleton `Beardifier.EMPTY` for the target chunk;
- a keyed noise-settings holder whose complete protocol-valid noise range is
  contained by the level;
- an accepted worker whose own view contains the requested chunk.

The server ensures the chunk has the exact vanilla cached `NoiseChunk`, using a
verified invoker for private `NoiseBasedChunkGenerator.createNoiseChunk` when a
persisted partial chunk did not already create it during BIOMES.

The client reconstructs `RandomState`, creates a client-owned `NoiseChunk` with
`Beardifier.EMPTY` and `Blender.empty()`, and executes the same interpolation
order as `NoiseBasedChunkGenerator.doFill`. The work runs on the dedicated
`CAWG-RemoteWorldgen-1` thread, not the render/network callback thread.

## Server application

The server accepts a result only after the pending registry atomically validates
owner and full identity. `RemoteDensityField` then validates the exact sample
count. With Phase 5 sampling enabled, direct results and completed predictions
are also independently recomputed before cache insertion. The cached
`NoiseChunk` validates cell geometry before installation.

The server chooses `0..64` unique cells through `SecureRandom` after the result
has arrived. For each cell it reconstructs vanilla interpolation state from the
authoritative `RandomState` and noise settings, then compares all values using
exact IEEE-754 bits. Standard jobs have 768 cells in Overworld, 256 in Nether,
and 128 in End. An invalid result is evicted, never installed, and falls back
locally; its owner is quarantined until disconnect so a new hello on the same
connection cannot immediately resume work. Cache hits contain only results that
already passed validation when validation was enabled.

Malformed compressed results are quarantined immediately after the claimed
decode fails. Timeouts retain ordinary local fallback; three consecutive
timeouts quarantine the connection, while a successfully decoded result resets
that counter.

The application Mixin redirects only the `DensityFunction.fillArray` call whose
function is the `noiseFiller` owned by `fullNoiseDensity`. It copies one remote
cell in vanilla Y-descending/X/Z order. Every other cache fill runs unchanged,
and `NoiseBasedChunkGenerator.doFill` remains the only code that mutates chunk
sections and heightmaps. The field is cleared when the original generation
future completes.

No remote value is installed before validation. Consequently a rejected or
failed response cannot leave a partially remote-mutated authoritative chunk.

## Admission and lifecycle

`WorkerRegistry`, `PendingTerrainJobRegistry`, and `RemoteJobCoordinator`
provide synchronized bounded state:

- global/per-worker in-flight limit: configurable `1..64`, default `1`;
- timeout: configurable `50..60,000` ms, default `2,000` ms;
- exactly one response claim per job;
- owner/full-identity matching;
- bounded terminal tombstones for duplicate, cancelled, expired, and late
  response classification;
- cancellation on rejected re-handshake, datapack reload, disconnect, and
  server shutdown;
- worker lease release on every completion path.

`RemoteDensityResultCache` is a synchronized access-order LRU with `0..256`
entries (default `16`; `0` disables it). Its key includes cache generation,
dimension, chunk coordinates, context fingerprint, noise-settings ID, and exact
height/cell geometry. Values are copied on insertion and retrieval. A cache hit
still creates a fresh server identity and passes through the normal validated
density-application path. Server start, datapack reload, and shutdown clear the
cache and increment its generation; a result decoded across invalidation cannot
reinsert stale data.

The Phase 4 scheduler observes only the sole registered worker's authoritative
server-side position. Once movement direction is known, it selects a valid,
world-border-contained chunk beyond that player's effective view distance and
submits only to that exact owner. It runs every `1..1200` ticks (default `20`),
uses a lead of `1..8` chunks (default `8`), skips already-`FULL` candidates with
an eight-chunk scan limit, never requests a chunk from Minecraft, and consumes
the existing in-flight and cache bounds. Prediction requires a non-zero cache.
A direct request for the exact cache key either joins the in-flight result or
uses the completed cache value. The full live direct eligibility check still
precedes either operation, so retrogen, blending, or non-empty beardification
cannot consume the speculative empty-context calculation.

Normal timeout sweeps run at the end of server ticks and may send a cancellation
packet. A daemon `CAWG-RemoteTimeout` watchdog also expires jobs without calling
Fabric networking off-thread. This second path is required because synchronous
commands such as `forceload` may wait for chunk completion while the server main
thread is unable to dispatch the already-arrived result. The watchdog completes
the remote future exceptionally so vanilla fallback can unblock the command;
the queued late result is subsequently rejected.

Unavailable/ambiguous worker, backpressure, send failure, explicit client
failure, invalid payload/context/result, timeout, disconnect, reload, and
shutdown all preserve the untouched local call captured at
`ChunkStatusTasks.generateNoise`.

## Implemented limits

- [x] protocol version and exact-version negotiation
- [x] server-issued non-zero UUID job ID
- [x] owner, dimension, coordinate, and context binding
- [x] bounded identifiers and handshake string
- [x] maximum decoded densities (`98,304`)
- [x] maximum encoded result payload (`787,456` bytes)
- [x] finite/absolute density bounds
- [x] maximum in-flight jobs (`64`; default `1`)
- [x] timeout bounds (`50..60,000` ms; default `2,000` ms)
- [x] cancellation, disconnect, reload, shutdown, replay, and late-result paths
- [x] server-authoritative aquifer, ore, and block application
- [x] lossless RAW/DEFLATE envelope and compressed/decompressed size bounds
- [x] single-thread bounded asynchronous result decoding
- [x] bounded LRU density cache with context-rich keys and lifecycle invalidation
- [x] opt-in player-owned prediction with exact-owner admission and bounded scan
- [x] direct-demand join/cache consumption with live eligibility revalidation
- [x] opt-in unpredictable whole-cell recomputation and bit-exact comparison
- [x] validation-failure cache eviction, local fallback, and connection quarantine
- [x] malformed-result immediate quarantine and three-timeout circuit breaker
- [x] fail-closed raw-seed disclosure policy requiring a second explicit opt-in
- [x] bounded unregistered seeded-leaf transcript/job codecs without a seed field
- [x] unregistered server-only HMAC binding and authorization-envelope codec
- [x] lifecycle-owned context/key rotation and owner-bound one-shot claim registry
- [x] non-refundable per-owner transcript disclosure budget across reload/disconnect
- [x] fail-closed persistent world-global transcript disclosure budget across restart
- [x] bounded aggregate pending transcript memory with fixed-size terminal tombstones
- [x] bounded off-thread seeded-leaf decode/validation with reload/disconnect admission epochs
- [x] unregistered dummy-seed client worker and validated-only density-field projection
- [ ] public-server seed confidentiality

## Runtime evidence

The 2026-09-02 dedicated server/client run verified handshake, request/result
codecs, 98,304-sample client calculations, density installation, original
vanilla completion, and clean world save. Seven remote-applied chunks generated
again in an independent local-only world with seed `8675309` matched the full
canonical NOISE-stage SHA-256 digest exactly (`7/7`, zero mismatches).

A separate 500 ms test held the server main thread in `forceload`. The watchdog
expired the job, local generation completed, the late result was rejected as
`EXPIRED`, the command returned, the client remained connected, and a later
remote job completed normally.

The 2026-09-03 Phase 3 dedicated server/client run exercised 21 standard-height
result envelopes. All selected DEFLATE, reducing each `786,432`-byte density
field to `307,123..333,518` bytes (mean ratio `0.4087`). Mean client encode and
server decode times were `9.69 ms` and `2.49 ms`. Twenty jobs completed through
the remote application path; seven coordinates also present in the independent
local baseline matched their complete canonical NOISE-stage SHA-256 digests
exactly (`7/7`, zero mismatches). A separate run observed cache stores and
datapack-reload/server-stop invalidation under the real lifecycle.

The 2026-09-03 Phase 4 run used the same seed and a lead of eight chunks. The
sole connected worker completed speculative density jobs, after which real
NOISE demand consumed cache entries for chunks `0,17`, `-1,18`, and `0,18`.
Their cache-path completion times were `16.03`, `15.93`, and `31.70 ms`.
Independent local generation produced identical canonical NOISE SHA-256
digests for all three (`3/3`, zero mismatches). This demonstrates correct
ownership, cache consumption, and deterministic application; it is not a
controlled performance-benefit benchmark.

The 2026-09-03 Phase 5 honest-client fixture completed four direct remote jobs
with eight cells / 1,024 values checked per job. Validation took `18.9473 ms`
on the first invocation and `6.2044..6.4522 ms` after warmup. A second real
client enabled the development-only corruption fixture and changed every
density by one representable step. The first remote result failed exact
comparison, produced no cache store or remote completion, quarantined its
owner, and the original NOISE stage completed via local fallback.

The unregistered seeded-leaf authority lifecycle was runtime-verified on
2026-09-03 with remote mode disabled. An isolated dedicated server generated 25
spawn NOISE chunks, logged a generation-1 authority start, then accepted a
graceful stop, logged authority teardown with zero pending jobs, saved every
dimension, and exited successfully. This verifies lifecycle ownership and
inactive-path compatibility only; no seeded-leaf packet was registered or sent.

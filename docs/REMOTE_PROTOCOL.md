# Remote Protocol

## Minecraft 26.3 development branch

The sections below describe the published **26.2** line unless explicitly
stated otherwise. On `feature/mc26.3-multiloader`, the Fabric candidate sends
full-block float-exact density volumes and sets general `CURRENT=3`; the
separate public-seed fixture is unavailable. This candidate has targeted
sampler/transport tests but no assisted network runtime comparison or
Forge/NeoForge implementation yet. See `PORT_26_3.md`. Do not interpret the
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
bounded implementation-version string. The current client advertises one
thread. The server accepts the exact current protocol only and caps the result
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

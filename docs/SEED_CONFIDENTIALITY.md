# Seed Confidentiality

## Status

Public-fixture exception, 2026-09-05: a separate explicitly enabled v3 packet
family and live adapter now exist **only** for the already public seed 8675309.
See `SEEDED_LEAF_FIXTURE_PROTOCOL.md` for the gate and candidate-seed comparison
limitation. Earlier "unregistered/no live caller" descriptions below are the
pre-fixture baseline. This does not establish private-seed confidentiality or
authorize arbitrary-world transport. Current verification is recorded in
`TEST_RESULTS_LATEST.md`.

The existing general-world protocol reproduces exact vanilla 26.2 density by sending
the raw world seed to the client. This path is now fail-closed: setting
`WORLDGEN_ASSIST_REMOTE=true` alone does not register a worker or dispatch a
job. A server operator must additionally set
`WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE=trusted_raw` to authorize the existing
trusted-client protocol.

This guard prevents accidental disclosure. A server-local bounded transcript
recorder/replayer, binary codecs, unregistered seed-free-field job/result
drafts, a dummy-seed client worker, a one-shot result-admission gate, bounded
off-thread authoritative sampling, and a validated-only installation conversion
are implemented as a correctness proof. A transport-free owner-bound orchestrator
and one-shot vanilla generation continuation are now implemented; neither has a
Fabric adapter or a live chunk-pipeline call. Public-server seed confidentiality remains
open. The final 2026-09-05 JDK 25.0.4 run after delegated work and primary review
compiled and built the draft and passed 224 tests in 48 suites. Latch-backed tests verify exact capacity, capacity
retention until real timeout/cancellation exit, and reload/disconnect claim-
registration races. A three-seed 16-block matrix traverses the complete
recorder-to-field path with all-cell validation and raw-bit equality. Structural
guards cover protocol registration, the explicit raw-seed gate, counter-only
ledger state, and sensitive logger arguments. Three-seed standard-height
DEFLATE/RAW fixtures now compare all 98,304 values against a separate authoritative
traversal; the executor itself samples 64/768 cells. Orchestration/continuation
tests cover final lifecycle gates, one-shot use and vanilla cleanup. See
`TEST_RESULTS_LATEST.md` and `VALIDATION_MATRIX.md`.

## Security goal

A confidential design for an untrusted client must provide both:

1. the raw 64-bit world seed is not recoverable from packets, client memory, or
   persistent client state; and
2. the material sent for one authorized chunk is not a reusable oracle for
   arbitrary unassigned coordinates.

Merely deleting the `worldSeed` field is not sufficient. An initialized noise
sampler that can evaluate arbitrary coordinates is seed-equivalent capability
for the terrain-discovery threat even if the numeric seed is not immediately
recoverable.

## Verified Minecraft 26.2 dependency chain

The generated source in `.gradle/source-inspect` establishes the following:

- `RandomState.create(settings, noises, seed)` passes the seed into the private
  constructor.
- `RandomState` derives a root `PositionalRandomFactory`, wires every
  `DensityFunction.NoiseHolder`, supplies the `terrain` random source for
  `BlendedNoise`, and uses the seed directly for End-island density.
- `Noises.instantiate` derives each keyed `NormalNoise` from that root factory.
- `NormalNoise` builds two `PerlinNoise` instances.
- `PerlinNoise` creates seeded `ImprovedNoise` instances for its non-zero
  octaves.
- `ImprovedNoise` retains three random offsets and a 256-byte permutation used
  for every coordinate query.

For the current Overworld path, the client calls `RandomState.create` and then
constructs `NoiseChunk`. Consequently an exact vanilla calculation needs
either the seed, a set of seeded sampler states, a server-side oracle for all
seed-dependent evaluations, or execution inside a component the client owner
cannot inspect.

`SeedDependencyAnalyzer` now walks the wired vanilla Overworld `finalDensity`
graph without extracting any sampler state. The 26.2 regression fixture fixes
the observed boundary at 25 unique keyed noise IDs, 1 `BlendedNoise` node, and
1,116 expanded noise-holder references. It also verifies that the corresponding
unwired settings graph is rejected as replay-ready. This inventory is metadata
only; it does not yet record leaf values.

## Rejected shortcuts

### Encrypt the seed for the Java client

Rejected. The client must possess the decryption key and plaintext during
calculation, so an operator controlling the process can read or modify both.
Code obfuscation does not change this boundary.

### Send `RandomState` or initialized noise samplers

Rejected as a confidentiality claim. Vanilla has no codec for the wired
runtime state, so this also requires invasive custom serialization. More
importantly, the resulting offsets/permutations allow unrestricted terrain
queries and may expose enough deterministic PRNG state for seed recovery. That
must be treated as seed-equivalent material unless a separate cryptanalysis
proves otherwise.

### Send a per-chunk derived seed

Rejected for exact vanilla compatibility. Vanilla noises are global,
coordinate-continuous functions initialized from one world seed. Independently
seeding chunks changes output and can create boundary disagreement. A keyed
per-lattice redesign would be a new world generator, not vanilla delegation.

### Hash the seed into the context fingerprint

Already present, but not confidential. The unkeyed fingerprint is an equality
identifier and permits offline guesses for known configuration. A confidential
protocol must use a server-random opaque context ID or a keyed authenticator;
the client cannot use the existing digest as proof that it has the right secret
context.

## Candidate architectures

### A. Attested worker

Run the vanilla-equivalent calculator inside a hardware-backed trusted
execution environment. After remote attestation, the server establishes an
encrypted channel to the measured worker and sends the seed only inside that
channel. The untrusted Minecraft process forwards opaque requests/results.

This is the only retained candidate that preserves both substantial client CPU
offload and the current exact vanilla algorithm against a hostile client host.
It introduces native components, platform-specific attestation, a larger
supply-chain boundary, and difficult availability/fallback behavior. It is not
implemented.

### B. Bounded seed-dependent transcript

Keep every seeded noise evaluation on the server and send only job-bounded
leaf results. The client evaluates seed-independent arithmetic, interpolation,
and result encoding. A draft request would contain:

```text
server-random opaque context ID
dimension and exact chunk geometry
versioned density-graph identity
ordered, bounded seed-dependent leaf transcript
```

No reusable sampler state or seed-derived PRNG state may be sent. Transcript
entries must be bound to a node ID, exact IEEE-754 input coordinates, call
index, job identity, and output bits. The server must reject extra, missing, or
out-of-order consumption.

The first server-local implementation uses source-verified hooks around
`NormalNoise.getValue(DDD)D` and `BlendedNoise.compute(FunctionContext)D`.
Recording and replay are explicit thread-local sessions. Each entry contains
only kind, stable keyed leaf ID, exact input bits, and exact output bits. The
implementation enforces a caller-selected limit capped at 100,000 entries,
rejects nested/cross-thread sessions, unregistered or unwired leaves, call/input
divergence, bound overflow, and incomplete consumption.

A vanilla Overworld trace recorded with seed `8675309` reproduces every
interpolated-density bit when the replay `RandomState` is initialized with an
unrelated dummy seed. Tests cover a 16-block slice (2,548 entries) and a full
384-block negative-coordinate chunk (17,972 entries), plus changed-input,
overflow, and trailing-entry rejection. A canonical first-use dictionary
permits at most 64 distinct leaf IDs, each at most 320 UTF-8 bytes, and the
standalone encoded transcript is capped at 4,000,000 bytes.

The two deterministic fixtures now pass through the binary codec before replay.
Tests cover exact bit round trips, the 100,000-entry legal maximum, unknown
formats/kinds, excessive counts and bytes, invalid indices, duplicate/unused
dictionary entries, non-canonical first use, bounded random malformed input,
trailing bytes, and every truncation of a representative valid transcript.

`SeededLeafJob` is the unregistered wire-model draft. It contains a non-zero
server job UUID, a 256-bit `SecureRandom` opaque context ID, exact Overworld
chunk/height/cell geometry, the fixed
`minecraft:overworld_final_density/26.2/seeded_leaf_v1` graph identity, and the
transcript. Its record has no raw-seed or seed-derived context-fingerprint
component. Its bounded codec round-trips and rejects unknown job/graph versions
and every truncation of the valid fixture. Neither codec is registered with
Fabric or exposed to a client.

`SeededLeafJobAuthenticator` supplies the draft authenticated binding. A
server-only, non-zero 256-bit random key computes a domain-separated
HMAC-SHA-256 over the job/graph format, job UUID, opaque context ID, dimension,
chunk, noise-settings ID, geometry, and every ordered transcript kind, leaf ID,
input bit pattern, and output bit pattern. Verification compares tags in
constant time. Tests reject altered identity, context, coordinates, geometry,
transcript input/output, and a different key. Key/tag values are defensively
copied and bounded. `AuthorizedSeededLeafJob` adds a versioned, bounded envelope
codec that rejects unknown versions and every truncation.

`SeededLeafJobAuthority` now turns this into a server-side control-plane
boundary without enabling transport. It allocates non-zero job UUIDs, retains
the authorized job, binds it to one owner, and accepts the exact fixed-size
`SeededLeafJobClaim` once. Owner/context/tag mismatch does not consume the job;
duplicates, expiry, cancellation, disconnect, reload, and stop fail closed.
Concurrent claims have exactly one winner.

The authority also charges every issued transcript against a non-refundable
per-owner entry budget. The Fabric lifecycle wrapper defaults to 100,000 entries
per owner per running server. Disconnect and datapack reload do not refund or
reset it; reload cancels all pending work and rotates both random context ID and
HMAC key. Server stopping drops the live session. Logs expose generation and
counts only.

The pending-memory boundary is distinct from the cumulative disclosure budget.
All live draft jobs together may retain at most 100,000 transcript entries.
Once accepted, cancelled, expired, or invalidated, an entry discards its full
authorization/transcript and preserves only fixed-size owner/claim/state data
for bounded replay classification.

Before authorization, issuance now also charges a world-local cumulative
`SeededLeafPersistentDisclosureLedger`. The fixed 72-byte record stores only
format/header data, configured maximum, used entries, monotonic sequence, and a
non-secret SHA-256 integrity checksum—never a seed, transcript, context, tag, or
key. Each accepted charge is written with synchronous same-directory atomic
replacement. A leftover pending file, malformed/truncated/corrupt record,
configured-maximum mismatch, unsupported atomic move, counter overflow, or I/O
failure makes the global budget unavailable and all future issuance fails
closed. Reopening revalidates the file instead of trusting failed in-memory
state. A fixed adjacent initialization marker makes a ledger that disappears
after initialization fail closed. Deliberate deletion of both world-local files
by a trusted filesystem administrator is outside the remote-client model.

The authorization and claim remain unregistered as payloads. A process restart
no longer resets the world-global 100,000-entry allowance. Related accounts are
still not aggregated, and the limit is not a proof that cumulative observations
cannot recover seed information. Adaptive quotas and cryptanalysis are still
required. The retained owner-counter map is capped at 1,024 UUIDs; new owners
beyond that point fail closed, bounding memory without claiming Sybil resistance.

`SeededLeafDensityResult` completes the seed-free draft wire model without
reusing protocol-v2 identity. It contains only the fixed UUID/context/HMAC
claim, 1..98,304 density doubles, and diagnostic compute time. Every value must
be finite and have absolute value at most 1,000,000; timing is limited to one
hour. Its envelope preserves exact big-endian double bits as RAW or zlib
DEFLATE, never accepts more encoded bytes than the declared raw length, and has
a strict standalone codec capped at 786,545 bytes. The codec rejects unknown
versions, every truncation of its fixture, trailing bytes, excessive count or
length, and a payload above that cap.

`SeededLeafDensityResultGate` connects this result to the independent authority
without enabling transport. It authenticates and consumes the owner-bound claim
before any decompression, obtains the server-retained job only for the winning
claim, checks the count against exact job geometry, then validates the decoded
value bounds. Wrong-owner/context/tag attempts cannot cause decompression or
consume the job. Once the exact claim wins, shape, compressed-stream, or value
failure is terminal and a replay is classified as duplicate. Successful output
retains geometry and values but not the transcript, and is explicitly marked as
awaiting authoritative validation.

The existing separate `NoiseChunk` cell validator now has a seeded-leaf
overload for that accepted projection. It checks the projection's minimum Y,
height, cell width, and cell height against authoritative `NoiseSettings`, then
uses server-owned `RandomState` and `NoiseGeneratorSettings` to recompute
unpredictably selected whole cells bit-exactly. The integration fixture validates
all 32 cells/4,096 values of a 16-block slice and rejects a one-ULP mutation.
This adds an authoritative sampling primitive without putting the seed,
fingerprint, or initialized sampler into the job/result model. It is not wired
to a live scheduler or application route.

The decompression and authoritative sampling stages now have an unregistered
bounded runtime primitive: `SeededLeafResultValidationExecutor`. It owns one
worker and an exact total-task semaphore, performs the cheap one-shot claim
before queueing expensive work, and selects validation cells with server-side
randomness only after the result is fixed. Reload advances a lifecycle epoch
before cancelling tasks; disconnect marks the owner inactive before cancelling
that owner's work; reconnect explicitly opens a new owner epoch. A crossing
task can never return `VALIDATED`. Timeout/cancel completes the caller without
waiting, but the capacity permit is retained until the actual invocation exits,
so interrupt-insensitive work cannot create hidden overcommit.

`SeededLeafJobSpecRecorder` provides the exact server-side job discovery
traversal, requires the registry-backed Overworld settings holder, and verifies
the clamped `NoiseSettings` against its generator settings.
`SeededLeafClientDensityComputer` and its exact-capacity client worker
replay the authorized transcript under a fixed public dummy seed without a raw
seed/fingerprint field. Cancellation keeps an attempt accounted until its real
execution exits. These remain transport-free. After authoritative validation,
`RemoteDensityField.fromValidated(outcome, authority)` is the sole seeded-leaf conversion to
the existing `NoiseChunk` installation field; a merely decoded/unvalidated
result cannot use that factory, and a validated result from a rotated authority
generation/context is rejected.

This establishes that the mathematical split can work locally. Dynamic
shifted-noise coordinates still mean the server must discover calls by
evaluating the graph, and repeated bounded outputs may accumulate into a seed-
recovery or terrain-oracle risk. It remains a correctness/security research
path, not a presumed performance win or confidentiality claim.

## Next implementation gate

The inactive integration layer is documented in `SEEDED_LEAF_ORCHESTRATION.md`.
It performs off-thread recording/issuance, owner-token dispatch, total-attempt
timeout, validation, connection quarantine and a one-shot application offer.
The generation continuation checks lifecycle again on the supplied generation
executor, calls the original vanilla operation once, and clears the installed
field before downstream completion. This is server-local code and does not
register or expose a new protocol. Canonical geometry validation also now runs
before recorder session/sampler construction.

Before adding protocol version 3:

1. keep the implemented Overworld seed-leaf inventory locked to generated
   source and extend it deliberately for any newly supported context;
2. extend the implemented bit-exact local replay fixture across multiple seeds,
   positive/negative chunks, and context invalidation;
3. keep the implemented bounded binary codec free of raw seed, initialized
   sampler, permutation table, root random state, and reusable arbitrary-
   coordinate sampler state as the draft evolves;
4. preserve the implemented lifecycle-owned opaque identity, HMAC binding,
   owner-bound pending jobs, one-shot response claims, and invalidation when the
   inactive draft is connected to transport;
5. analyze cumulative leakage, add multi-account/adaptive controls, and justify
   a safe bound; the persistent world-global entry budget is still only a
   conservative control;
6. preserve the implemented bounded seed-free result, claim-before-decode
   admission, and independent authoritative cell sampler;
7. keep the implemented bounded off-thread decode/validation route, exact-
   capacity client worker, and validated-only installation conversion intact;
8. preserve the implemented transport-free orchestration/continuation and add
   its bounded Fabric-thread adapter plus source-verified live eligibility and
   exclusive lifecycle ownership wiring after the disclosure gate;
9. only then register new payloads and increment the protocol version.

Until this gate passes, `DENY` remains the safe default and `TRUSTED_RAW` is
restricted to disposable or otherwise trusted environments.

# AGENTS.md

## Concurrent-owner scope update (2026-09-12)

The user explicitly authorized extending the single-player PoC so each player
can assist their own terrain concurrently. See `MULTIPLAYER_SUPPORT.md` for
the decision, ownership/capacity invariants and current verification. Older
single-worker/player-count-rejection scope below describes alpha.1, not a ban
on this authorized extension. Preserve server authority, owner-specific work,
all disclosure gates and the public fixture's one shared persistent budget.

## Current public-fixture exception (2026-09-05)

Read `SEEDED_LEAF_FIXTURE_PROTOCOL.md` before runtime/network changes. A separate
opt-in v3 fixture family now connects the seeded-leaf orchestrator and vanilla
continuation, only for the already public seed 8675309. General protocol CURRENT
remains 2; private-world confidential offload is still gated. Earlier descriptions
below of an entirely unregistered/inactive draft are the pre-fixture baseline,
not permission to enable it for arbitrary seeds. The fixture manager exclusively
owns its authority lifecycle, replacing (not duplicating) the inactive owner.
Do not treat tests or disclosure quotas as a seed-confidentiality proof.
Consult `TEST_RESULTS_LATEST.md` for what has actually been executed.

## Purpose

This repository develops **Client-Assisted World Generation** for Minecraft Java Edition.

The goal is to reduce server-side terrain-generation CPU cost by allowing each player's own client to calculate selected expensive world-generation work for chunks that the same player is expected to need.

This is an experimental / research-oriented project. Correctness and reproducibility are more important than prematurely maximizing performance.

---

## Target environment

Unless a newer project decision is explicitly recorded:

- Minecraft Java Edition: **26.2**
- Mod loader: **Fabric**
- Fabric Loom: **1.17**
- Java: **JDK 25**
- Gradle: **9.5.1-class toolchain for the initial 26.2 setup**
- IDE: **Visual Studio Code**
- Main language: **Java**
- Primary OS during development: **Windows**
- Version control: **Git**

Do not silently upgrade Minecraft, Fabric Loader, Fabric API, Loom, Gradle, Java, or protocol versions.

If an upgrade is required, document it first.

---

## Source-of-truth priority

When answering implementation questions or modifying code, use this priority:

1. **Generated Minecraft 26.2 source from the current Gradle/Loom workspace**
2. Current Fabric 26.2 documentation
3. Fabric API source resolved by Gradle
4. Fabric Loader / Loom source
5. Sponge Mixin documentation
6. Existing mods for implementation ideas
7. Blogs, forum posts, Reddit, Stack Overflow, old mappings, and AI memory

### Critical rule

**Never guess a Minecraft internal class, method, field, descriptor, constructor, generic parameter, or mapping name.**

Before writing a Mixin or code that depends on Minecraft internals:

1. Run or confirm `genSources`.
2. Open the generated 26.2 source.
3. Confirm the exact symbol.
4. Confirm parameter and return types.
5. For Mixins, confirm the bytecode target / descriptor when ambiguity exists.
6. Record important findings in `docs/MIXIN_TARGETS.md` or `docs/WORLDGEN_PIPELINE.md`.

Minecraft 26.1+ uses unobfuscated game names in the modern Fabric workflow. Do not copy old Yarn/intermediary names without verifying them against the current 26.2 source.

---

## Core architecture rules

These rules are architectural constraints, not suggestions.

1. **The server remains authoritative.**
2. A client must not directly author arbitrary final world state.
3. Client A should normally compute only work caused by or predicted for Client A.
4. Do not use Client A as a general worker for Client B by default.
5. The first offload target is the expensive base-terrain / noise-related work.
6. Resource placement and gameplay-sensitive feature generation remain server-side initially.
7. The server must always have a local vanilla-compatible fallback path.
8. A slow, disconnected, or malicious client must never stall chunk generation indefinitely.
9. Remote jobs must have explicit timeout, cancellation, and fallback behavior.
10. Never mutate chunk/world state from a networking thread.
11. Avoid blocking the main server thread while waiting for a client result.
12. Prefer reusing vanilla Minecraft world-generation code over reimplementing algorithms.
13. Preserve deterministic output whenever the same inputs are used.
14. Performance claims require benchmark data.
15. Security assumptions must be documented explicitly.

---

## Initial proof-of-concept scope

The first complete PoC is intentionally narrow.

### In scope

- Minecraft 26.2
- Fabric
- Overworld
- Newly created test world
- One connected player
- Trusted client
- Seed disclosure allowed in the test environment
- One remote worldgen job at a time initially
- Remote execution of a clearly isolated noise/base-terrain stage
- Server-side result application
- Timeout and local fallback
- Detailed timing logs
- Vanilla-vs-remote deterministic comparison tests

### Out of scope for the first PoC

- Public hostile servers
- Cross-player compute sharing
- Nether and End
- Old-chunk blending edge cases
- Full feature generation on the client
- Ore placement on the client
- Lighting offload
- Complex speculative pre-generation
- GPU/OpenCL implementation
- Production anti-cheat guarantees
- Multi-version support

Do not expand scope until the current phase builds, runs, and has measurements.

---

## Development phases

### Phase 0 — Instrument vanilla

Measure current worldgen before changing behavior.

Required metrics:

- stage elapsed time
- chunk coordinates
- server thread / worker identity
- number of generated chunks
- failures

### Phase 1 — Local asynchronous prototype

Run the candidate calculation through a project-owned worker abstraction on the same machine.

Goal:

- establish clean interfaces before networking
- confirm deterministic equivalence
- measure serialization-independent cost

### Phase 2 — Remote client worker

Add:

- worker capability handshake
- job request
- result response
- timeout
- cancellation
- local fallback

### Phase 3 — Compression and result cache

Status: implemented and runtime-verified on 2026-09-03 for the trusted-client
density PoC. Protocol version 2 provides lossless RAW/DEFLATE envelopes, bounded
off-thread decoding, context-keyed LRU storage, lifecycle invalidation, and
transport/cache timing logs.

Measure:

- raw result size
- encoded size
- compression CPU time
- network transfer time
- result-application time

### Phase 4 — Player-owned prediction

Status: implemented and runtime-verified on 2026-09-03 for the trusted-client
density PoC. Prediction is separately opt-in, observes only the sole worker's
server-side movement, respects effective view distance/world border, submits
only to that owner under the existing in-flight bound, and stores only the safe
density intermediate in the Phase 3 cache. Three consumed predictions matched
independent local NOISE digests exactly (`3/3`).

Implemented:

- observe player position / movement
- respect effective view distance
- predict chunks the same player is likely to need
- precompute only safe intermediate results
- consume the existing explicitly keyed/invalidation-safe cache through
  speculative results and measure useful hit rate

### Phase 5 — Validation / adversarial model

Status: initial opt-in probabilistic validation implemented and runtime-verified
on 2026-09-03. The server unpredictably samples whole interpolation cells,
recomputes them through an independent vanilla `NoiseChunk`, requires bit-exact
equality, evicts rejected cache entries, quarantines the producing worker for
the current connection, and completes through untouched local generation.
Malformed claimed results are quarantined immediately, while three consecutive
timeouts trip the same connection-level circuit breaker and successful decode
resets its count.

This is not public-server readiness. Seed confidentiality and stronger
assurance against sparse corruption remain open.

### Seed-confidentiality hardening

Status: the existing raw-seed protocol is now fail-closed. Remote enable alone
does not accept a worker or dispatch a job; a second explicit
`seed_disclosure=trusted_raw` setting is required. Generated Minecraft 26.2
source confirms that serializing initialized `RandomState` or noise-sampler
state would expose reusable seed-equivalent terrain capability, so it is not
treated as a confidentiality fix. The retained designs and the protocol-v3
recorder/replayer gate are in `docs/SEED_CONFIDENTIALITY.md`.

The first server-local recorder/replayer now intercepts only source-verified
`NormalNoise.getValue` and `BlendedNoise.compute` calls during an explicit
thread-local session. It reproduces a standard-height `NoiseChunk` bit-exactly
under an unrelated dummy seed. A bounded, dictionary-based binary transcript
codec and an unregistered job draft now carry a server-random opaque context
ID, exact Overworld geometry, a fixed 26.2 graph ID, and the transcript without
a raw-seed or seed-derived-fingerprint field. A server-only 256-bit key can
bind every semantic job field with HMAC-SHA-256; its authorization envelope is
also bounded. A separate authority is now wired to Fabric server lifecycle: it
rotates context/key on reload, drops them on stop, binds pending work to the
owner, accepts one exact authenticated claim, and maintains a non-refundable
per-owner transcript-entry budget across disconnect/reload. None of the draft
packet codecs is registered or invoked by a client. A separate seed-free
density result/envelope draft is bounded to 98,304 finite values and uses
lossless RAW/DEFLATE encoding. Its server gate consumes the authenticated claim
before decompression, checks the retained job shape, validates decoded value
bounds, and projects only geometry plus values for later authoritative
sampling. The existing independent `NoiseChunk` cell validator accepts that
projection and checks its geometry against server-owned `NoiseSettings` before
bit-exact sampling under server-owned `RandomState`. Treat this as a security/
correctness experiment, not public-server support.

The inactive draft also has a fail-closed world-local persistent global
disclosure ledger, an exact server-side transcript traversal, a dummy-seed
client computer with a bounded worker, and a bounded off-thread decode/
validation executor. Reload/disconnect admission epochs prevent results from
crossing lifecycle boundaries, and task capacity remains occupied until
interrupted work actually exits. A validated-only factory can create the
existing seed-free density installation field. The final 2026-09-05 run after
parallel delegated tests and primary review passed 224 tests in 48 suites and
the build on JDK 25.0.4.
Latch-backed tests cover exact capacity, real-exit timeout/cancellation
accounting, and reload/disconnect claim-registration races. A three-seed
16-block matrix completes the recorder-to-field path with all-cell validation,
and structural guards cover protocol registration, the explicit raw-seed gate,
counter-only ledger state, and sensitive logger arguments. Three-seed
standard-height DEFLATE/RAW fixtures now compare every value against an
independent authoritative traversal (executor sampling itself remains 64/768
cells). Additional lifecycle and orchestration/continuation tests pass; follow
`docs/TEST_RESULTS_LATEST.md` for precise remaining unverified rows and
`docs/TEST_HANDOFF.md` for reproducible execution.
Payload registration, live scheduling/application, and protocol version 3
remain intentionally disabled.

`SeededLeafJobOrchestrator` and `SeededLeafGenerationContinuation` now provide
the transport-free owner-token control and application seam. They are not
instantiated by Fabric or the live chunk pipeline. They cover off-thread
recording/issuance, total deadline, one-shot validation/installation, connection
quarantine, and an exactly-once untouched vanilla continuation with cleanup.
See `docs/SEEDED_LEAF_ORCHESTRATION.md` before connecting them to anything: the
authority must have one lifecycle owner, and exchange callbacks must be bounded
and non-blocking. Transcript confidentiality remains an unresolved gate.

---

## Required module boundaries

Prefer code organization similar to:

```text
src/
├─ main/
│  ├─ java/
│  │  └─ <package>/
│  │     ├─ common/
│  │     ├─ network/
│  │     ├─ server/
│  │     └─ mixin/
│  └─ resources/
│
├─ client/
│  └─ java/
│     └─ <package>/
│        └─ client/
│
└─ test/
   └─ java/
      └─ <package>/
```

Suggested responsibilities:

```text
common/
  WorldgenJob
  WorldgenResult
  protocol version types
  codecs
  immutable data structures

network/
  payload definitions
  payload registration
  encode/decode only

server/
  RemoteWorldgenManager
  WorkerRegistry
  JobScheduler
  ResultValidator
  ResultCache
  metrics

client/
  ClientWorldgenWorker
  WorkerThreadPool
  client configuration
  local worldgen context

mixin/
  minimal hooks into vanilla pipeline
```

Keep Mixins thin. Business logic belongs outside Mixin classes.

---

## Networking rules

- Use explicit protocol versions.
- Every job has a unique `jobId`.
- Every response must identify the job.
- Reject stale or duplicate responses.
- Put upper bounds on payload sizes.
- Validate coordinates, dimensions, palette sizes, lengths, and enum values.
- Do not trust client-reported timing for authoritative decisions.
- Never deserialize an unbounded collection.
- Do not allow a packet handler to perform expensive worldgen directly on the network event thread.
- Make cancellation idempotent.

See `docs/REMOTE_PROTOCOL.md`.

---

## Concurrency rules

The following must be explicit for every asynchronous operation:

- owning thread
- immutable inputs
- shared state
- synchronization strategy
- completion thread
- cancellation behavior
- timeout behavior

Never assume a vanilla worldgen class is thread-safe merely because it is used asynchronously somewhere in Minecraft.

Before using a vanilla object from a client worker thread, inspect its implementation and ownership assumptions.

---

## Mixin rules

Before adding a Mixin:

1. Explain why an event/API hook is insufficient.
2. Record candidate class/method in `docs/MIXIN_TARGETS.md`.
3. Verify it against generated 26.2 sources.
4. Keep injection surface minimal.
5. Avoid copying large vanilla methods when possible.
6. Add logs that prove the hook is reached.
7. Add a fallback when practical.
8. Build immediately after changing a Mixin.
9. Launch a real dev client/server to catch runtime Mixin errors.

Do not rely on compiler success alone for Mixin correctness.

---

## Logging requirements

Use a consistent prefix such as:

```text
[CAWG]
```

Useful job lifecycle example:

```text
[CAWG] job.created id=42 chunk=10,20 owner=<uuid>
[CAWG] job.sent id=42 bytes=...
[CAWG] job.client_started id=42
[CAWG] job.result_received id=42 rtt_ms=...
[CAWG] job.validated id=42
[CAWG] job.applied id=42 apply_ms=...
[CAWG] job.complete id=42 total_ms=...
```

Fallback:

```text
[CAWG] job.timeout id=42 timeout_ms=500
[CAWG] job.fallback_local id=42
```

Avoid logging the world seed in normal logs.

---

## Testing rules

After behavior changes, run the smallest relevant tests first, then broader tests.

At minimum before considering a feature complete:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

When Minecraft runtime behavior or Mixins changed, also launch the relevant dev runtime.

For deterministic worldgen work, compare vanilla and assisted output for fixed seeds and chunk coordinates.

See `docs/TESTING.md`.

---

## Benchmark rules

Never claim improvement from one anecdotal chunk.

Record at least:

- mode
- seed identifier (not necessarily raw seed)
- dimension
- chunk coordinate
- server generation CPU/wall time
- client calculation time
- encode time
- bytes sent
- bytes received
- RTT
- decode time
- apply time
- total latency
- timeout/fallback status

Warm-up and repeated runs are required before performance conclusions.

See `docs/BENCHMARK.md`.

---

## Documentation update rule

If implementation discovers that an architectural assumption is wrong:

1. Update code.
2. Update the relevant Markdown document.
3. Explain why the assumption changed.
4. Add or update a test if the behavior is testable.

The repository documentation should reflect the actual implementation, not the original idea.

---

## Commands

Windows PowerShell:

```powershell
# Generate Minecraft sources
.\gradlew.bat genSources

# Generate VS Code launch targets
.\gradlew.bat vscode

# Build
.\gradlew.bat build

# Unit tests
.\gradlew.bat test

# Development client
.\gradlew.bat runClient

# Development dedicated server
.\gradlew.bat runServer
```

If a command differs in the current generated project, inspect available Gradle tasks instead of guessing.

---

## AI-agent workflow

For any non-trivial implementation task:

1. Read `AGENTS.md`.
2. Read the relevant files in `docs/`.
3. Inspect current implementation.
4. Inspect generated Minecraft source if internals are involved.
5. State the smallest implementation step.
6. Modify code.
7. Build/test.
8. Read errors or logs.
9. Fix the implementation.
10. Update documentation if a new fact was learned.

Prefer small, verifiable commits over broad rewrites.

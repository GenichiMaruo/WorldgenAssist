# Public-fixture transport decision — 2026-09-05

2026-09-12 development update: the user authorized concurrent player-owned
assistance. See `MULTIPLAYER_SUPPORT.md`. The single-worker/player-count
suspension below is the alpha.1 baseline: development now admits up to 64
workers, 8 total attempts and 1 per owner, with an owner-specific dispatch queue.
The one shared authority and persistent 100,000-entry world budget are unchanged.
Only non-spectator owner-view demand is eligible; no arbitrary/private seeds
are enabled. The historical alpha.2 run passed two-owner installed-JAR simultaneous
dispatch/application and a development-withholding owner-disconnect probe;
their shared vanilla NOISE comparisons pass (588 and 406 shared digests).
See `TEST_RESULTS_LATEST.md` for exact snapshots, warnings and limits. This is
controlled two-owner functional evidence, not general public-server support.

## Decision before implementation

The user requested completion of communication, Minecraft integration and
functional testing, while deferring performance judgment. Implement an explicit
public-fixture-only transport as the next experiment. This is not approval of
confidential transport for arbitrary/private seeds or public hostile servers.

The existing protocol-v2 `CURRENT` stays 2. A separate, explicitly versioned
`seeded_leaf_fixture_v3_*` message family is registered only when the server or
client public-fixture flag is set. Both sides must opt in. The server checks
the actual world seed against the already public fixture seed `8675309` before
accepting a worker or creating a transcript. A different seed fails closed.
The raw-seed PoC and fixture mode cannot both be enabled on the server.

This is a narrow exception to the earlier blanket ban on draft packet
registration: only the fixed public-fixture experiment can be activated. The
original confidentiality gate still prevents general protocol-v3 activation.

## Why a disclosure budget is not a confidentiality proof

Each transcript exposes a deterministic keyed leaf ID, input coordinates and
output bits. An observer can initialize a *candidate* seed locally and compare
its predicted leaf values with that transcript. No server key is required for
this candidate test; the HMAC authenticates job ownership, not secrecy of values.
No attack complexity or successful seed recovery is asserted here. The existence
of offline candidate checking means the 100,000-entry quota alone cannot justify
a nonzero universally safe disclosure count, especially with prior information
that narrows seed candidates. The seed-recovery/security work remains open.

Using an already public seed permits transport/application correctness tests
without making private world information newly available. The persistent ledger,
owner binding, claim-before-decode validation and fail-closed default remain
required even in this experiment.

## Runtime constraints

- Fresh vanilla Overworld, fixed public seed, no private world copy.
- One accepted player/worker and one attempt; a second player suspends eligibility.
- Only chunks inside that owner's effective view range may be submitted.
- No seed, sampler state or protocol-v2 fingerprint in fixture packets.
- Separate bounded request/result codecs; request uses Fabric large-payload support.
- Exchange queues are bounded, drained on the Minecraft thread, and checked again
  for current connection, attempt and authority generation before sending.
- Recorder/validation remain off-thread; generation continuation uses the existing
  vanilla background executor. Reload, disconnect and timeout discard unsent jobs.
- Fixture manager exclusively owns its authority/ledger/orchestrator lifecycle;
  the inactive legacy authority lifecycle is not registered in fixture mode.
- Default general-world behavior and public-server confidentiality claims do not change.
- Synchronous vanilla chunk waits suspend new fixture admission and cancel an
  existing attempt immediately; those waits cannot pump tick-drained transport.
  Nested suspension is balanced in finally. This local fallback does not blame
  the client. Asynchronous owner-view generation remains eligible.

## Verification status

2026-09-10 later checkpoint: 240 tests / 51 suites pass after direct v2 client
integration coverage was added. Both-installed-JAR assisted/vanilla runs pass:
four applied chunks and 1,005 shared NOISE digests match. Installed clients exit
naturally with code 0 and the vanilla shutdown marker. Targeted post-disconnect
live-job class counts are zero after GC; no deep-retention/security proof follows.

2026-09-09: the new final A-D checkpoint is `20260909-184411-455`,
237 tests / 50 suites, zero failures/errors/skips. Queued cancellation and
selected internal-leaf interruption now have deterministic tests. Player-count
changes suspend/cancel fixture work, not just future demand. See the latest
report for the 2026-09-10 batch: all five local adversarial modes pass, four
installed chunks and 1,344 shared NOISE digests match the fresh baseline, and
fallback terminal chunks also match. After SSH restoration, multi-PC assisted
and vanilla also pass: four installed chunks and 1,002 shared NOISE digests match,
including comparison against the primary PC's independent local baseline.

2026-09-05: Phase A-D passes 233 tests / 50 suites. Real loopback server/client
transport and application pass: 4 installed chunks match a separate local-only
world exactly; 1,005 shared NOISE digests have zero mismatches. Wrong-seed runtime
rejects handshake/dispatch and creates no fixture ledger. A synchronous chunk-wait
deadlock dependency discovered in the first smoke is now bypassed immediately by
local fallback. Precise evidence and remaining adversarial/runtime gaps are in
`TEST_RESULTS_LATEST.md`. No cross-PC or performance conclusion is claimed.

## Verification requirements

Run focused and full JUnit/build under JDK 25.0.4, then isolated server/client
functional smoke. Compare authoritative NOISE digests with a separate local-only
world using the public fixture. Exercise reload, disconnect, timeout/local fallback
and malformed-result handling. Record actual runs, JAR hashes and remaining gaps;
do not equate compile/JUnit success with runtime transport success. Use the offered
Tailscale PC only after the local setup is ready and announce the intended test.

## Reproducible local runtime procedure

Use JDK 25.0.4. First run `scripts/Run-SeededLeafVerification.ps1` and inspect all
Phase A-D exits/XML, then stop editing Java/build configuration during runtime.
Do not run another Gradle test/build or any second runtime fixture concurrently.
The runtime script itself starts the server first, then a client in a separate
Gradle process after server readiness; both use unchanged already-built sources.

```powershell
.\scripts\Run-SeededLeafRuntimeFixture.ps1 -Mode assisted
.\scripts\Run-SeededLeafRuntimeFixture.ps1 -Mode vanilla
.\scripts\Compare-SeededLeafFixture.ps1 -AssistedRoot 'test-artifacts/runtime-assisted-<timestamp>' -VanillaRoot 'test-artifacts/runtime-vanilla-<timestamp>'
.\scripts\Run-SeededLeafRuntimeFixture.ps1 -Mode wrong-seed
```

Each invocation creates a new timestamped directory under `test-artifacts` with
separate `server`/`client` profiles. Existing `run/server.properties`, worlds,
client options and disclosure ledgers are not changed. The existing user-agreed
`run/eula.txt` is copied; without explicit existing acceptance the script stops.
Server binding is **127.0.0.1:25585**, offline authentication for this isolated
fixture only, normally max one player (two only in the second-player test),
no RCON/query. The separately authorized multi-PC harness keeps this loopback
binding behind authenticated SSH; never bind offline-mode Minecraft to a
Tailscale/LAN/public address. See `MULTIPC_TESTING.md`. No firewall rule is added.

The client window is a real dev runtime, named `FixtureWorker`. Only its fresh
options disable first-launch accessibility onboarding, which generated 26.2
`Gui.buildInitialScreens` otherwise places before QuickPlay. The script issues
fixed teleports, reload, save, kick and stop; stdout/stderr and Minecraft logs are
retained. After server disconnect/stop it terminates only the client process tree
it started. This forced client shutdown is not evidence of graceful JVM shutdown.
On a timeout/error, `summary.txt` records `success=False`; preserve that directory.

Acceptance criteria:

- Assisted: accepted handshake, at least one `seeded_fixture.sent`, client result,
  `READY`, and **`seeded_fixture.applied`**. READY alone is not installation proof.
- Compare every applied coordinate's authoritative NOISE digest with the same
  coordinate in a separate vanilla world. Missing digests fail, not skip. Also
  reject any mismatch among shared coordinates and any source-manifest difference.
- Wrong seed: `blocked reason=world_not_public_fixture`, no dispatch/application,
  no fixture ledger created, normal player join and vanilla generation complete.
- Reload/kick in this basic smoke are idle lifecycle checks; do not describe them
  as in-flight race tests. Those races currently have latch-backed JUnit evidence.
- Inspect both client/server logs for Mixin, decode, executor and unexpected errors.
  Dependency/auth-service warnings must be described individually, not hidden.

Record commands, timestamps, JDK, source manifests, JAR SHA-256 from Phase D,
all runtime directory names, applied coordinates and comparison JSON in
`TEST_RESULTS_LATEST.md`. A GUI stall or harness error does not pass transport.
This is a dev-classpath run, not proof that the distribution JAR was launched.
No performance verdict follows from digest-enabled timing.

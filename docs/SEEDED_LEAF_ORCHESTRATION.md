# Seeded-leaf orchestration and generation continuation

## Scope

`SeededLeafJobOrchestrator` and `SeededLeafGenerationContinuation` implement the
**transport-free** control core. `SeededLeafFixtureManager` now supplies a live
Fabric adapter only for the explicitly enabled fixed public fixture, as recorded
in `SEEDED_LEAF_FIXTURE_PROTOCOL.md`. General protocol CURRENT remains 2. The
disclosure/leakage gate still forbids general/private-world activation.

The orchestrator accepts an already active authority and owns that authority's
control lifecycle exclusively until close. Runtime construction must use the
existing persistent global ledger; test fixtures may use the package-scoped
volatile budget. Do not attach it to the same authority already controlled by
`SeededLeafJobSecurityLifecycle`: a future runtime integration must transfer
ownership deliberately, not run two lifecycle controllers.

## Implemented sequence

1. The trusted server adapter opens a single owner's connection and receives a
   server-local connection token. The adapter derives the UUID from the actual
   connection, never a request field.
2. `submit` admits one logical attempt, checks connection and validator admission,
   and rejects a busy recorder/validator or an unavailable/exhausted global budget.
3. `CAWG-SeededLeafRecord` records the exact bounded transcript using server-owned
   `RandomState`, the registry-backed Overworld settings holder and clamped noise
   geometry. Wire geometry is checked before opening a trace session or sampler.
4. After recording, the authority monitor protects the second connection,
   generation and deadline check, durable budget charge, job issuance and dispatch
   commitment. A failed or cancelled attempt cannot issue a later authorization.
5. The trusted, non-blocking `ClientExchange` receives only the connection token
   and `AuthorizedSeededLeafJob`. It returns a response stage. The current test
   adapter uses the real dummy-seed calculator in another worker. The public-fixture
   adapter uses `SeededLeafDispatchQueue`, with one transfer and one best-effort
   cancellation; actual network sending rechecks lifecycle under the authority
   monitor on the server tick thread.
6. The response must refer to that attempt's job. The existing authority then
   authenticates context/tag and consumes its claim once before bounded decoding
   and independent server validation on `CAWG-SeededLeafValidate`.
7. A successful outcome is projected to a seed-free field and a single-use
   `ValidatedOffer`. `READY` means an offer exists, **not** that anything was
   installed or that chunk generation has completed.
8. `SeededLeafGenerationContinuation` schedules the application boundary on the
   explicitly supplied generation executor. It rechecks the offer's origin,
   connection, quarantine state, authority generation, deadline and one-shot use.
9. It invokes the same captured vanilla operation once. Rejected attempts,
   expired/stale offers and rejected installation use that operation without a
   remote field. Once vanilla starts, synchronous/asynchronous failure is
   propagated and never retried after possible chunk mutation.
10. Any installed field is cleared by job ID before the continuation completes,
    so the downstream chunk stage cannot race cleanup. Duplicate continuation
    attachment and a foreign orchestrator are rejected before calling vanilla.

## Thread and lifecycle contracts

- Control state uses the authority monitor, with the same lock ordering as the
  validation executor. No worldgen traversal occurs inside that monitor.
- Recording and durable issuance run on the recorder worker. Decode and sampling
  run on the validator worker. All owned worker/timer threads are daemon threads.
- The total attempt deadline starts at admission, covers recording, issuance,
  exchange and validation, and is enforced independently of server ticks.
- A timed-out caller can start its local continuation without waiting for the
  recorder to honor interruption. New admission stays busy until the actual
  recording invocation exits. Validation has its own real-exit capacity accounting;
  its occupied slot also prevents new recording admission.
- `ClientExchange.send` and `cancel` must be non-blocking, thread-safe trusted
  adapter operations. They may run on recorder, validation, watchdog or lifecycle
  threads. Do not put a blocking client computation, network wait or `join` in
  these callbacks. Because dispatch commitment and cancellation serialize with
  lifecycle, a blocking adapter would also delay that lifecycle/watchdog.
- The Fabric adapter must marshal networking to its permitted thread using
  a bounded queue and recheck the exact connection before the final send. This
  class alone is not that thread-marshalling or network-admission implementation.
- Reload cancels the pending attempt, invalidates validation and rotates the
  authority. It retains disclosure charges and connection quarantine. Admission
  is closed during lifecycle callbacks, including reentrant completion callbacks.
- Disconnect compares the connection token by identity. A delayed disconnect for
  an earlier connection cannot disconnect a reconnected copy of the same UUID.
- An invalid result quarantines the connection immediately. Three consecutive
  post-issuance attempt timeouts quarantine it; a validated result resets the
  timeout streak. A timeout before issuance does not blame the client. Reconnect
  resets connection health but does not refund any disclosure budget.
- Close is idempotent, cancels pending work, closes workers and stops the
  exclusively owned authority. It does not delete or close the external ledger.
- The generation executor is an explicit integration dependency. If it rejects
  submission, the returned stage fails; world generation is not run inline on a
  response/network/watchdog thread. Runtime integration must use the captured
  generation executor and handle executor shutdown at its normal lifecycle.
- The supplied `RemoteDensityTarget` must validate geometry before changing its
  field slot. The existing `NoiseChunkRemoteDensityMixin` does this. It is the
  caller's responsibility to supply the eligible, exclusively owned chunk target
  and the untouched vanilla operation, and not attach multiple generation tasks
  to that same target.

## Retention and security limits

Completed attempts clear recording inputs, validation context, exchange and claim
references. The recorder's task reference disappears on real exit (or removal of
queued work). A validated offer retains a plain origin token, connection metadata,
generation/deadline and geometry/density field; it does not retain the
orchestrator, authority, transcript, HMAC or seed-bearing state.

The persistent world-global budget remains the aggregate control across owners
and restarts. Quarantine is a connection health control, not account attribution
or Sybil resistance. Neither this orchestration nor the 100,000-entry ledger
establishes a cryptographically safe transcript disclosure count. Seed inference,
custom registry graph negotiation and general-world activation remain separate
gates. Fixture transport, live eligibility and runtime evidence are tracked in
`SEEDED_LEAF_FIXTURE_PROTOCOL.md` and `TEST_RESULTS_LATEST.md`.

## Verification and handoff

The latest authoritative results and exact evidence paths are in
`TEST_RESULTS_LATEST.md`. The test ladder is in `TEST_HANDOFF.md`.

Focused tests must use the real recorder, client replay, envelope decoding and
validator. `RecordingHook` is package-scoped, no-op in public construction, and
may hold execution before the real recorder only. It may not fabricate a job or
result. Use bounded latches and release them in `finally`.

Required assertions include ready/one-shot install, complete vanilla continuation,
wrong claim and malformed data rejection, timeout before/after dispatch, real-exit
recording capacity, disconnect/reconnect, reload before issue and before install,
quarantine/reset, exhausted budget without recording, exactly-once vanilla calls,
installation cleanup on success/failure, executor rejection and sensitive state
retention after completion.

For standard-height fixtures, the executor samples at most 64 of 768 cells. A
separate full authoritative traversal must compare all 98,304 density values
before reporting full bit equality; do not call a 64-cell sample all-cell
validation. RAW can be deliberately constructed from exact client result bits
even when the production encoder naturally chooses smaller DEFLATE.

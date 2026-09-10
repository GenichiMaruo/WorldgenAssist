# Test results — 20260905-202610-497 + local runtime

## Conclusion

Phase A-D PASS: **233 tests / 50 suites**, failures/errors/skips all zero.
The public-fixture v3 family has run in a real dedicated server and dev client
on this PC. Four installed remote results match independent local-world NOISE
digests exactly; all 1,005 shared coordinates also match. A non-fixture seed
refuses the handshake, sends zero jobs and creates no disclosure ledger.

This verifies only the fixed public-seed experiment, not private-seed
confidentiality, production anti-cheat, cross-PC correctness or a speedup.
General protocol CURRENT remains 2.

## Provenance

- Primary implementation agent performed this batch. No new delegated test run
  is claimed; Terra-high usage exhaustion had already been reported.
- Workspace: F:\Documents\myproject\WorldgenAssist; mostly untracked files,
  no clean-commit claim. Final source/test/build-config manifests match.
- JDK 25.0.4+7 LTS, Windows 11 amd64, Gradle 9.5.1, Loom 1.17.20,
  Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2. No upgrade.
- Final ladder: `test-artifacts/20260905-202610-497/`. Previous reviewed report
  preserved there as `previous-test-results.md` (224-test baseline).
- No Java/resource/build-config edits during the final ladder or successful
  runtime runs. The assisted and baseline runtime source manifests match.
- Runtime used the dev classpath, NOT an installed distribution JAR. Fresh
  server/client profiles and worlds; loopback port 25585 only.
- The offered Tailscale PC was not accessed or used in this batch.

## Final Phase A-D

Run with `scripts/Run-SeededLeafVerification.ps1`. Exact commands, timestamps,
exits, per-phase XML and JSON totals are in the final evidence directory.

| Phase | Seconds | Result |
|---|---:|---|
| A compileJava + compileClientJava | 3.779 | PASS |
| B focused test --rerun-tasks | 14.852 | PASS: 123 tests / 26 suites |
| C full test --rerun-tasks | 15.517 | PASS: 233 tests / 50 suites |
| D build --rerun-tasks | 18.303 | PASS: 233 tests / 50 suites |

Failures/errors/skips are zero in B/C/D. JAR hashes were rechecked after runtime.
The ClientTerrainDensityComputer* pattern still selects no dedicated class;
do not infer a new direct protocol-v2 client-computer integration test.

## New implementation and regression coverage

- Separate opt-in fixture Hello/Accepted/Request/Result/Failure/Cancel payloads,
  strict bounded codecs and Fabric large-payload registration.
- Actual world seed gate before ledger/worker creation, mutual exclusion with
  trusted_raw, exclusive lifecycle owner, one player/one attempt.
- Server-thread owner-demand snapshot, bounded exchange queue, last-moment
  connection/claim/deadline/context checks, validated generation-thread install,
  untouched vanilla supplier and cleanup.
- Persistent client worker preserves real-exit capacity across reconnects.
  Disclosure rejection prevents further expensive recording in that session.
- Source/bytecode-verified WrapOperation on the two ServerChunkCache managedBlock
  calls suspends admission and cancels pending fixture work during synchronous
  chunk waits. Nested scopes resume in finally, avoiding a tick/network wait
  dependency on the main server thread.
- New tests cover configuration/private-world gates, all six payload round trips,
  every truncation/trailing byte, invalid acceptance version/boolean, send-once,
  duplicate/old-owner rejection, queued cancellation/reload/disconnect, bounded
  cancellation, timeout/send failure/close fallback, nested suspension, reentrant
  admission rejection and successful resumption.
- Previous three-seed standard-height RAW/DEFLATE equality, 64/768-cell executor
  sampling, all-cell short-slice and lifecycle tests still pass. Structural
  logger/field tests are not heap-forensics or seed-leakage proofs.

## Real runtime evidence

All paths below are under `test-artifacts/`.

| Run | Evidence directory | Observed result |
|---|---|---|
| Assisted | runtime-assisted-20260905-202722-192 | PASS: 5 sends, 1 synchronous-load cancellation, 4 READY + installs, budget rejection, reload/kick/clean server stop. |
| Local baseline | runtime-vanilla-20260905-202846-427 | PASS: same public seed/route, local generation only. |
| Wrong seed | runtime-wrong-seed-20260905-203030-620 | PASS: blocked, handshake false, 0 sends/installs/ledger files, 1,439 local NOISE digests, clean stop. |

`digest-comparison.json` in the assisted directory records exact hashes for
(1005,-2002), (1005,-2006), (994,-1995), (995,-2004): **4/4 equal**.
All **1,005 shared coordinates** match; no applied-coordinate digest is missing.
`negative-gate-check.json` records the wrong-seed checks.

No server ERROR, uncaught exception, Mixin apply failure or nonzero generation
failure counter was observed in these successful runs. Client logs retain
unauthenticated dev-account HTTP 401 user-properties/profile-key and Realms token
errors, plus missing empty client-resources classpath and empty cod sound warnings.
These did not prevent loopback connection. Clients were kicked, then their owned
process trees terminated; this is NOT a graceful client-JVM shutdown test.
Post-completion reload/kick are idle smokes, not controlled in-flight race tests.

## Failed attempts retained and corrections

- 20260905-200254-715: sandbox Gradle wrapper getsockopt denial; authorized reruns
  used the same pinned JDK/wrapper.
- 20260905-201123-312: intermediate 232-test ladder passed, before the new wait
  Mixin; superseded by the final 233-test evidence.
- runtime-assisted-20260905-201346-090: first-launch accessibility screen blocked
  QuickPlay. Fixed only the fresh fixture profile after reading generated
  Options/Gui source. No transport success claimed for this attempt.
- runtime-assisted-20260905-201726-333: handshake accepted, but synchronous chunk
  waits prevented tick-drained sends: three timeouts, no install. This drove the
  production synchronous-wait fix. Later runs also use view distance 10 to
  exercise asynchronous owner-view generation.
- 20260905-202433-302: Phase B stopped at 123 tests / 1 failure. An existing test
  asserted pendingCount=0 immediately after future.get; source confirms completion
  precedes worker-finally release. Replaced that assertion with the existing
  bounded real-exit await. Production capacity was NOT released early to satisfy
  the test. The subsequent fresh ladder passes.
- The first comparison invocation missed PowerShell DifferenceObject because of
  newline argument parsing. Fixed explicit named arguments; the recorded JSON is
  from the subsequent successful invocation.

## Artifacts

| File | Bytes | SHA-256 |
|---|---:|---|
| worldgen-assist-0.1.0.jar | 412213 | CC9C3064B140B75CD5C93FA9E671C0C4311161D80F28280ACF23241DC7204472 |
| worldgen-assist-0.1.0-sources.jar | 137304 | 2215CC724EEF24DF6995AFB6D13B0E07ADC104295769BB94B7F554EEA4E17D0C |

## Remaining gates

- Private-world seed-inference/leakage analysis and a justified disclosure policy.
  Candidate-seed checking is possible; HMAC/quotas do not establish secrecy.
- Controlled real-network timeout, malformed-result, in-flight reload/disconnect
  and second-player tests. Transport-free control tests pass, but are not a
  substitute for executing those cases over Fabric.
- Installed-JAR and heterogeneous/multi-PC verification; performance judgment
  remains explicitly deferred.
- Previously identified deeper gaps: deterministic queued client observation,
  interruption at a selected internal leaf, deep heap/GC retention, direct v2
  client-computer integration. None is silently marked complete.

Next tester: read `TEST_HANDOFF.md` and `SEEDED_LEAF_FIXTURE_PROTOCOL.md` for exact
commands, runtime acceptance criteria, comparison and evidence rules.

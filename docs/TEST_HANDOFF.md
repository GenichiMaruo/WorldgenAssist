# Test handoff for the next AI tester

2026-09-13 alpha.2 update: the user authorized concurrent player-owned work.
Read `MULTIPLAYER_SUPPORT.md` and the new section of `MULTIPC_TESTING.md`.
The old second-player rejection scenario is retired and replaced by two-owner
concurrent dispatch/application and isolated-disconnect checks. Preserve the
single shared fixture authority/ledger and its public-seed restriction.
Latest checkpoint: 246 tests / 52 suites, Phase A–D PASS in
`test-artifacts/20260913-231321-924/`; runtime verification is tracked in
`TEST_RESULTS_LATEST.md`. Historical checkpoints below are not current gates.

## 1. Purpose and authority

This is the operating procedure for a lower-tier AI acting as a test intern.
The implementation owner will review the recorded evidence and make security or
architecture decisions. The tester's job is to add focused tests, run them,
preserve evidence, and report facts precisely.

The user authorized parallel Terra-high test agents on 2026-09-05. The primary
agent assigns disjoint file ownership and may continue independent production
implementation. Test agents do not spawn further agents. Run only one Gradle
command stream against this workspace at a time; explicitly hand off that runner
between agents. Capture source hashes for each run. After production changes
settle, run a consolidated Phase A-D on the final snapshot; an earlier parallel
focused run is not evidence for later source edits. Gradle may use its normal
internal workers.

The implementation owner has added a narrow **public-fixture-only v3 family**.
Read `SEEDED_LEAF_FIXTURE_PROTOCOL.md` before testing its runtime; it contains the
exact runner, logging and comparison procedure. General CURRENT stays 2. Do not
broaden registration, remove the actual-world seed check, reset/reuse a disclosure
ledger, or enable arbitrary/private-world scheduling. Those remain security gates.
Run the dedicated local runtime script only after final Phase A-D, with no other
build/test stream active. Its intentionally owned server/client Gradle launch
pair is the only exception to the single-stream runner rule above.

The user authorized second-PC tests on 2026-09-09. Follow `MULTIPC_TESTING.md`;
an unreachable SSH host blocks those tests, not local work. Never infer a pass.
Monitor delegated agents at twice the estimated duration, or 120 seconds if
unknown. Event-driven completion does not require polling.

### Current verified checkpoint

The current final checkpoint is `20260910-220816-359`: JDK 25.0.4, Phase A-D
PASS, **240 tests / 51 suites**, zero failures/errors/skips. It adds direct v2
client-computer integration to the previous 237-test checkpoint's deterministic
queued-client cancellation and selected internal recorder/replayer interruption
coverage, plus public-fixture fault controls and player-count suspension.
Standard-height DEFLATE/RAW complete chains now cover three public seeds with
full independent raw-bit comparisons; the executor sample remains 64/768 cells.
Orchestration and vanilla continuation are active only in the public fixture. Read
`TEST_RESULTS_LATEST.md` for the remaining gates; do not duplicate passed rows.

The following 194-test checkpoint is historical background:

Run `20260905-005106` passed Phase A-D on JDK 25.0.4 with 194 tests in
46 suites and zero failures, errors, or skips. Its latch-backed tests cover
executor/client exact capacity, timeout/cancellation capacity retention until
the real hook invocation exits, reload/disconnect races, dedicated worker
execution, and reconnect epoch rejection. It also covers a complete three-seed
16-block recorder-to-field chain with all-cell validation and structural guards
for protocol registration, the explicit raw-seed gate, counter-only ledger
state, and sensitive logger arguments. Do not merely duplicate those cases.

The following were the priorities before the 224-test consolidated run; use the
latest report to distinguish completed rows from remaining work in 5.4-5.7:

- idempotent reload/cancel/close completion, explicit exactly-once completion,
  and non-daemon-thread cleanup;
- queued/running client cleanup and successful-result retention checks;
- the standard-height and RAW complete-chain variants;
- transcript mutation/truncation/extra/input/kind/incomplete replay matrix;
- holder/geometry rejection and recording/client-replay cancellation;
- complete result/outcome/field sensitive-data retention assertions.

Also verify the new inactive orchestration/continuation described in
`SEEDED_LEAF_ORCHESTRATION.md`. The implementation owner owns those production
classes; the assigned tester owns their dedicated tests. Their existence does
not authorize Fabric registration or live scheduling. Standard-height executor
validation is sampled (at most 64/768 cells); full raw-bit equality requires a
separate authoritative full traversal.

Use a new timestamped evidence directory. Preserve the existing passing run.

## 2. Required reading before any action

Read these files completely, in this order:

1. `AGENTS.md`
2. `docs/AGENTS.md`
3. this file
4. `docs/TESTING.md`
5. `docs/SEED_CONFIDENTIALITY.md`
6. `docs/SECURITY_MODEL.md`
7. `docs/REMOTE_PROTOCOL.md`
8. `docs/MIXIN_TARGETS.md`
9. `docs/WORLDGEN_PIPELINE.md`

Generated Minecraft 26.2 source is the primary reference for game internals.
Never guess a Minecraft symbol. If a test needs a new internal symbol, find and
record the exact generated source first.

## 3. State to preserve

Assume the worktree contains valuable uncommitted user changes. Do not reset,
checkout, clean, delete, reformat, or bulk-rewrite it. Use read-only inspection
before editing. On this machine Git may require a per-command safe-directory
override; do not modify global Git configuration merely to inspect status:

```powershell
git -c safe.directory=F:/Documents/myproject/WorldgenAssist status --short
```

Production source under `src/main` and `src/client` is owned by the
implementation agent. The test intern may edit:

- `src/test/**` for focused tests;
- `docs/TEST_RESULTS_LATEST.md` for the human-readable result;
- `test-artifacts/**` for raw temporary evidence.

Use `apply_patch` for source/test/Markdown edits. Shell output capture is only
for generated evidence under `test-artifacts`.

If production code appears wrong or does not compile, stop that test phase and
report the exact error. Do not silently change production behavior to make a
test pass. A tiny test-only fixture correction is allowed and must be listed in
the report.

Never print or store a real private world seed, HMAC key, authorization tag,
transcript values, or opaque context value. Fixed public fixture seeds already
present in tests, such as `8675309`, may be identified explicitly as public test
data. Do not include contents of a real ledger from a user world.

## 4. Evidence directory

Create one run directory. Do not delete an older run.

```powershell
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runRoot = Join-Path 'test-artifacts' $runStamp
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$runRoot
```

Create these files during the run:

```text
test-artifacts/<timestamp>/
  environment.txt
  commands.txt
  compile.log
  focused-tests.log
  full-tests.log
  build.log
  runtime-server.log          # only if the runtime phase is authorized
  junit-summary.txt
  failures/                   # copied XML/log excerpts only when needed
```

Record environment without dumping environment variables:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
@(
  "timestamp=$(Get-Date -Format o)"
  "workspace=$((Get-Location).Path)"
  "os=$([System.Environment]::OSVersion.VersionString)"
  "java="
) | Set-Content -LiteralPath (Join-Path $runRoot 'environment.txt')
java -version 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'java-version.txt')
.\gradlew.bat --version 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'gradle-version.txt')
git -c safe.directory=F:/Documents/myproject/WorldgenAssist status --short |
  Tee-Object -FilePath (Join-Path $runRoot 'worktree-before.txt')
```

This repository targets JDK 25. On the current test PC, do not accept
`java -version` reporting JDK 26 merely because `--release 25` is configured;
the commands above select the installed JDK 25.0.4 for the test session. If
that installation is absent, classify it as `HARNESS/ENVIRONMENT` and stop
instead of silently substituting another major JDK.

Append each exact command, start/end time, duration, and exit code to
`commands.txt`. A convenient pattern is:

```powershell
$started = Get-Date
$commandText = '.\gradlew.bat compileJava compileClientJava'
$commandText | Add-Content -LiteralPath (Join-Path $runRoot 'commands.txt')
& .\gradlew.bat compileJava compileClientJava 2>&1 |
  Tee-Object -FilePath (Join-Path $runRoot 'compile.log')
$exit = $LASTEXITCODE
"start=$($started.ToString('o')) end=$((Get-Date).ToString('o')) exit=$exit" |
  Add-Content -LiteralPath (Join-Path $runRoot 'commands.txt')
if ($exit -ne 0) { throw "Compile phase failed with exit code $exit" }
```

Do not report success from visible console text alone. Check the process exit
code and JUnit XML.

## 5. Test implementation backlog

Add the smallest focused tests first. Follow existing package/style and reuse
fixtures already in `src/test`. Do not weaken bounds merely to simplify a test.

### 5.1 Persistent disclosure ledger

Create `SeededLeafPersistentDisclosureLedgerTest` in the server test package.
Use a JUnit temporary directory, never a real world directory. Cover:

- first open creates exactly a 72-byte ledger plus the fixed initialization
  marker and an ACTIVE zero snapshot;
- accepted charge updates used count and sequence exactly once;
- close/new instance/reopen preserves used count and sequence;
- exhaustion rejects without modifying bytes, count, or sequence;
- concurrent charges have exactly the allowed number of winners and exact final
  counters;
- every truncated length from 0 through 71 fails closed;
- trailing bytes, a bit flip in header/counters/checksum, invalid version,
  negative/out-of-range counters, and recomputed-checksum maximum mismatch fail
  closed;
- a pre-existing `.pending` sibling fails closed without being deleted;
- after initialization, deleting only the ledger while leaving its marker fails
  closed instead of silently recreating a zero allowance;
- malformed initialization-marker bytes fail closed; a valid legacy ledger
  without the marker is validated before the marker is created;
- reopening a FAILED instance revalidates current disk state and does not throw
  an unchecked lifecycle exception;
- a failed persist returns `UNAVAILABLE`, permanently prevents charges for that
  open session, and never advances the in-memory committed count;
- encoded bytes do not contain fixture seed/key/tag/transcript byte sequences.

If the filesystem used by the temporary directory cannot perform an atomic
replace, record the platform fact and assert fail-closed behavior. Do not replace
the implementation with a non-atomic fallback.

### 5.2 Authority and global budget

Extend `SeededLeafJobAuthorityTest` using an injected deterministic budget.
Cover:

- a successful issuance charges the global budget before returning the
  authorization;
- `GLOBAL_DISCLOSURE_LIMIT_REACHED` and
  `GLOBAL_DISCLOSURE_BUDGET_UNAVAILABLE` return no authorization and add no
  pending job;
- cancel, timeout, claim, disconnect, reload, and stop never refund a global
  charge;
- different owners share the same global maximum;
- server-lifetime owner counters reset only where documented, while the injected
  global counter does not;
- a constructor usable outside the server package cannot accidentally select a
  volatile runtime budget.

### 5.3 Gate and immutable boundary objects

Extend `SeededLeafDensityResultGateTest`. Cover:

- wrong owner/context/tag is rejected before decompression and does not consume
  the rightful claim;
- authentic shape mismatch consumes the claim and a retry is duplicate;
- `ClaimedResult`, `JobGeometry`, and `AcceptedResult` reject mismatched
  job/context/count/geometry;
- the public `AcceptedResult` constructor cannot pair one job geometry with a
  different result claim;
- no returned geometry/accepted object contains a transcript, raw seed,
  protocol-v2 fingerprint, authenticator, or key field;
- decode accepts exact RAW and DEFLATE values and rejects corruption, NaN,
  infinity, out-of-range values, truncation, and trailing compressed input.

Use reflection only to verify field/type absence, not to bypass constructors.

### 5.4 Result validation executor

Create `SeededLeafResultValidationExecutorTest`. Prefer deterministic injected
randomness and short legal timeouts. Cover:

Use the package-private constructor accepting `AdmissionHook` and `WorkerHook`
for deterministic concurrency tests. `AdmissionHook` runs synchronously after
the one-shot claim and before task registration. `WorkerHook` runs on
`CAWG-SeededLeafValidate` immediately before the real decode/validator path.
Production constructors install no-op hooks, and tests must not replace or
bypass decoding or authoritative validation. A latch-backed hook may ignore an
interrupt only when specifically proving that timeout/cancellation retains
capacity until the real invocation exits; always release it in `finally`.

- exact total capacity: a running task holds the only slot and another
  submission is `QUEUE_FULL` before its claim is consumed;
- claim occurs synchronously, while decompression/validation occurs on
  `CAWG-SeededLeafValidate`, never the submitting/network/main thread;
- valid all-cell fixture returns `VALIDATED` with exact metrics;
- one-ULP mutation returns `VALIDATION_FAILED` and no accepted result;
- malformed density returns `INVALID_DENSITY_DATA`;
- timeout completes as `TIMED_OUT`, interrupts work, and does **not** release
  capacity until the underlying invocation actually exits;
- `invalidateAndReloadAuthority()` rotates authority and rejects/cancels every
  old epoch as one operation, including a submit racing between claim and task
  registration;
- an owner not opened by `connectOwner()` is `OWNER_INACTIVE` and its claim is
  not consumed;
- first connect registers a positive epoch, and rapid disconnect/reconnect
  rejects delayed work from the prior connection as
  `STALE_OWNER_CONNECTION`;
- `disconnectOwner()` blocks a racing/new submission as `OWNER_INACTIVE`;
- `connectOwner()` opens a new owner epoch without reviving old work;
- exhausting the bounded owner-epoch tracker makes later admission fail closed
  as `OWNER_STATE_UNAVAILABLE` without growing the map;
- cancelOwner, cancelAll, reload invalidation, and close are idempotent;
- every completion happens once, and pending count returns to zero only after
  real work exits;
- close rejects later submissions and leaves no non-daemon test thread.

For races, use latches/barriers and bounded `await` calls. Never use arbitrary
long sleeps as proof. Every wait needs a test timeout.

### 5.5 Recorder, dummy-seed client, and end-to-end local path

Add/extend Fabric JUnit integration fixtures. Cover this complete in-process
chain without networking:

```text
authoritative RandomState
  -> SeededLeafJobSpecRecorder
  -> SeededLeafJobAuthority authorization
  -> authorization codec round trip
  -> SeededLeafClientDensityComputer using public dummy seed
  -> result envelope codec round trip
  -> SeededLeafResultValidationExecutor
  -> RemoteDensityField.fromValidated(outcome, authority)
  -> cell copies equal authoritative density bits
```

Required matrix:

- at least three public fixture seeds;
- positive, negative, and boundary-adjacent valid chunk coordinates;
- a short 16-block slice and standard Overworld height;
- RAW and DEFLATE when fixtures can deterministically force both;
- transcript mutation, truncation, extra entry, input-coordinate mutation,
  wrong leaf ID/kind, and incomplete replay all fail closed;
- a direct/non-registry-backed or non-Overworld settings holder is rejected;
- cell-size/range disagreement between generator settings and clamped
  `NoiseSettings` is rejected before recording, while a valid clamped slice is
  accepted;
- client sampler rejects zero/non-divisor/misaligned/mismatched geometry before
  traversing;
- cancellation during server recording and client replay is observed;
- no result, executor outcome, or `RemoteDensityField` retains transcript or
  seed-bearing protocol-v2 identity.
- `RemoteDensityField.fromValidated` rejects an otherwise successful outcome
  after authority reload/context rotation.

Also rerun existing `ClientTerrainDensityComputer` and `RemoteDensityField`
tests to prove the shared sampler/field refactor did not change protocol v2.

### 5.6 Client worker accounting

Create a client-package test if the Loom/JUnit source set permits it. Cover:

Use the package-private `SeededLeafClientWorker(ComputationHook)` constructor to
hold the real worker immediately before `SeededLeafClientDensityComputer`.
Production construction installs a no-op hook. The hook must not fabricate a
result or bypass the real computation; it exists only to make running/cancelled
capacity states observable with latches.

- exact one-attempt total capacity (running plus queued, not one each);
- duplicate job rejection;
- cancellation completes the future as cancelled but keeps `pendingCount()` and
  capacity occupied until computation exits;
- queued/running cancellation and close do not leak permits;
- close is idempotent and rejects later work;
- completion never exposes transcript/seed/fingerprint data.

If client source cannot be loaded by the current JUnit configuration, record
that as a harness limitation and move this case to the dev-client runtime list;
do not copy client production code into tests.

For the complete cross-package path, prefer a small public **test-source-only**
helper in the server test package that constructs package-scoped authority
fixtures. Do not widen production visibility merely to join the client- and
server-package portions of the test.

### 5.7 Static protocol guard

Add or extend a structural test that asserts:

- `WorldgenProtocolVersion.CURRENT` remains `2`;
- no seeded-leaf payload is registered in client or server networking setup;
- raw-seed protocol remains fail-closed without explicit `trusted_raw`;
- the ledger file stores counters only;
- source/log messages do not print seed, transcript values, keys, tags, or
  opaque context values.

This is a regression guard, not a claim of cryptographic confidentiality.

## 6. Execution ladder

The checked-in `scripts/Run-SeededLeafVerification.ps1` automates the same
ladder on this workstation with the pinned JDK 25.0.4, a new timestamped evidence
directory, exact commands/exits, per-phase XML copies/totals, before/after source
SHA-256 manifests and final JAR hashes:

```powershell
# All seeded-leaf focused tests, then full forced suite and forced build
.\scripts\Run-SeededLeafVerification.ps1

# Compile and a small focused set only; no full/build/runtime claim
.\scripts\Run-SeededLeafVerification.ps1 -FocusedOnly -FocusedTests '*SeededLeafJobOrchestratorTest','*SeededLeafGenerationContinuationTest'
```

Do not edit source/test/build configuration while the consolidated ladder runs.
The script rejects a changed source manifest and stale JUnit XML (for example,
when compilation failed before tests started). Read the evidence even when the
script exits successfully. No runtime or remote-PC command is part of this
script. If sandbox wrapper network preflight is denied, use the product's
escalation mechanism; do not substitute a Java version or disable wrapper checks.

Stop at the first failed phase, preserve evidence, and write the failure report.
After the implementation owner supplies a fix, start a new timestamped run from
the beginning. Do not overwrite the failed run.

### Phase A — compile only

```powershell
.\gradlew.bat compileJava compileClientJava
```

This catches source-set/API errors without claiming tests passed.

### Phase B — focused tests

After adding the tests above, run the smallest affected classes first. Use the
actual class names created; an example is:

```powershell
.\gradlew.bat test --tests '*SeededLeafPersistentDisclosureLedgerTest' --rerun-tasks
.\gradlew.bat test --tests '*SeededLeafJobAuthorityTest' --tests '*SeededLeafDensityResultGateTest' --rerun-tasks
.\gradlew.bat test --tests '*SeededLeafResultValidationExecutorTest' --rerun-tasks
.\gradlew.bat test --tests '*SeededLeaf*' --tests '*RemoteDensityFieldTest' --tests '*ClientTerrainDensityComputer*' --rerun-tasks
```

If a wildcard selects zero tests, that command is not evidence. Record the zero
selection and correct the pattern.

### Phase C — full forced suite

```powershell
.\gradlew.bat test --rerun-tasks
```

Parse every `build/test-results/test/TEST-*.xml`. Record tests, failures, errors,
and skipped totals. Example PowerShell:

```powershell
$xmlFiles = Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml'
$totals = [ordered]@{ suites = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($file in $xmlFiles) {
  [xml]$xml = Get-Content -LiteralPath $file.FullName
  $suite = $xml.testsuite
  $totals.suites++
  $totals.tests += [int]$suite.tests
  $totals.failures += [int]$suite.failures
  $totals.errors += [int]$suite.errors
  $totals.skipped += [int]$suite.skipped
}
$totals | Format-List | Tee-Object -FilePath (Join-Path $runRoot 'junit-summary.txt')
```

The last verified baseline before the current implementation batch was 163
tests across 40 suites with zero failures/errors/skips. The new expected total
must be higher. Do not copy the old count into a new report.

### Phase D — full build

```powershell
.\gradlew.bat build
```

Record exit code and artifact names/sizes. A hash is helpful:

```powershell
Get-ChildItem -LiteralPath 'build/libs' -File |
  Get-FileHash -Algorithm SHA256 |
  Format-Table -AutoSize |
  Tee-Object -FilePath (Join-Path $runRoot 'artifacts-sha256.txt')
```

### Phase E — single-PC runtime smoke

Run this only after the user or implementation owner explicitly asks for
runtime testing. Use an isolated dev-server universe and unused port. Remote
mode must remain disabled for the first smoke. Verify:

- ledger opens beneath that test world's `data/worldgen_assist` directory;
- logs contain only counts/reasons, no sensitive values;
- authority and validation executor start once;
- spawn generation completes;
- `/reload` rotates authority generation and invalidates old validation work;
- connect/disconnect events do not throw;
- graceful `stop` closes executor/ledger and saves all dimensions;
- port is closed after exit;
- no Mixin apply/injection error, fatal exception, crash report, or hung Java
  process remains.

Do not manually kill a healthy server as the normal stop procedure. If it
hangs, preserve logs, record the timeout, then ask before terminating it.

Do not enable or send the unregistered seeded-leaf protocol. Its in-process
integration belongs in JUnit until the security gate authorizes transport.

## 7. Deferred multi-PC tests

Do not perform these in the current pass:

- real separate client/server machines;
- packet capture across a physical network;
- disconnect/power-loss on one physical peer;
- NAT/firewall/latency/loss behavior;
- heterogeneous CPU/JVM deterministic comparison;
- hostile modified client on another PC;
- performance or scalability conclusions.

Keep them in the report under `DEFERRED`, not `PASS` or `FAIL`.

## 8. Failure handling

On failure:

1. stop the current ladder phase;
2. preserve the full command log and JUnit XML;
3. copy only relevant failing XML files into `failures/`;
4. quote the first causal exception and final `Caused by`, not thousands of
   repeated stack lines;
5. classify as `PRODUCTION_DEFECT`, `TEST_DEFECT`, `HARNESS/ENVIRONMENT`, or
   `UNRESOLVED`;
6. identify the smallest reproducing command;
7. do not claim later phases passed;
8. do not edit production code; hand the evidence back to the implementation
   owner.

Warnings are not automatically failures. Explicitly search logs for:

```text
Exception
Caused by
FAILED
Mixin apply failed
InjectionError
OutOfMemoryError
seed_disclosure_ledger.unavailable
seed_result_validation
```

For expected negative tests, distinguish the intentionally caught exception
from an uncaught suite failure.

## 9. Required report

Write `docs/TEST_RESULTS_LATEST.md` using this template. Replace every marker;
do not leave optimistic defaults.

```markdown
# Test results — <timestamp>

## Scope

- Tester model/session: <identifier if known>
- Workspace: <path>
- Production revision/worktree note: <commit if available and dirty summary>
- Test-only files added/changed: <list>
- Raw evidence: `test-artifacts/<timestamp>/`

## Environment

- OS: <value>
- Java: <value>
- Gradle: <value>
- Minecraft/Fabric/Loom: <values from project>

## Results

| Phase | Exact command | Exit | Duration | Result |
|---|---|---:|---:|---|
| Compile | ... | ... | ... | PASS/FAIL/NOT RUN |
| Focused | ... | ... | ... | PASS/FAIL/NOT RUN |
| Full test | ... | ... | ... | PASS/FAIL/NOT RUN |
| Build | ... | ... | ... | PASS/FAIL/NOT RUN |
| Runtime | ... | ... | ... | PASS/FAIL/DEFERRED/NOT AUTHORIZED |

## JUnit totals

- Suites: <n>
- Tests: <n>
- Failures: <n>
- Errors: <n>
- Skipped: <n>

## Security/concurrency assertions observed

- Persistent global budget across reopen: PASS/FAIL/NOT RUN — <evidence>
- Fail-closed corrupt/pending ledger: PASS/FAIL/NOT RUN — <evidence>
- Reload admission race: PASS/FAIL/NOT RUN — <evidence>
- Disconnect admission race: PASS/FAIL/NOT RUN — <evidence>
- Timeout retains capacity until real exit: PASS/FAIL/NOT RUN — <evidence>
- Dummy-seed end-to-end bit equality: PASS/FAIL/NOT RUN — <matrix>
- No seeded-leaf payload registration/protocol bump: PASS/FAIL — <evidence>

## Failures and unexpected warnings

<classification, smallest reproducer, first cause, XML/log path>

## Deferred

- Multi-PC tests: DEFERRED
- <anything else not executed>

## Artifact hashes

<jar name, size, SHA-256; or NOT BUILT>

## Tester conclusion

FACTS ONLY. State whether evidence is sufficient for implementation-owner
review. Do not declare public-server seed confidentiality or performance.
```

Finally, reply to the implementation owner with:

```text
TEST HANDOFF COMPLETE
Result: PASS | FAIL | PARTIAL
Report: docs/TEST_RESULTS_LATEST.md
Raw evidence: test-artifacts/<timestamp>/
First blocker: <none or one sentence>
Production files modified: NO
```

The implementation owner—not the test intern—will decide whether a failure
requires a code change, whether runtime evidence is sufficient, and whether the
next security gate may proceed.

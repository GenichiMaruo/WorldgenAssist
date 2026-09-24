# Validation matrix

On the 26.3 development branch, the Fabric installed-client batch runner now
accepts the authorized isolated two-PC fixture. Its default plan contains ten
scenarios: paired assisted/vanilla correctness in each vanilla dimension for
one owner, an Overworld two-owner pair, and one matched Overworld performance
profile. `-FullMatrix` explicitly selects the 60-case cross-product. A
`-CaseId` selects only its affected pair. This is Fabric evidence; native
Forge/NeoForge installed-JAR validation remains separate. The old 26.2
public-seed transcript regression is always reported as `SKIPPED` on 26.3.

For fixes, select only affected runtime cases with `-CaseId`; their opposite
vanilla/assisted partners are included automatically. This mode rebuilds the
exact JAR without running JUnit, settings smoke or public-fixture regression.
The plan explicitly identifies a partial run; it cannot attest the full matrix.
Use comma-separated IDs from a shell invoking `pwsh -File`, for example:

```powershell
.\scripts\Run-ValidationMatrix.ps1 -Execute -CaseId correctness-overworld-assisted-p2-direct,correctness-the_nether-assisted-p2-prediction,correctness-the_end-assisted-p1-prediction
```

Only expand coverage when another change or failure requires it. A failed
selected case still does not stop independent selected cases; analysis follows
their completion. Passing unaffected tests should not be repeated on every fix.

Performance repeat completion requires the 9-by-9 core around each repeat's
player position to have completed NOISE, followed by two seconds without new
completion ticks. This prevents the small synchronous teleport region from
ending a measurement before player-driven generation starts. Paired completed
task counts must also agree repeat by repeat. Unequal work makes the analysis
INCOMPLETE and suppresses the CPU/tick/throughput deltas and ratios; raw samples
remain available for diagnosis.

Scenario clients use fresh evidence profiles with gameplay movement, jump,
sneak, sprint, attack and use keys unbound. Server commands control relocation;
desktop keyboard/mouse gameplay input must not change the stationary workload.
This does not change normal client settings or imply that previous workload
differences have been conclusively attributed to user input.

`scripts/Run-ValidationMatrix.ps1` is the single batch entry point when a
broader validation pass is required. It is intentionally quiet while work is
running: every child process writes its own log under one timestamped evidence
directory, and the runner emits one completion line with `summary.json` and
`summary.md`. Do not poll the console or use an AI loop to tail the logs.

For the curated 26.3 pass, run one command from the repository root:

```powershell
.\scripts\Run-ValidationMatrix.ps1 -Execute
```

Add `-FullMatrix` only when the complete cross-product is justified. The
batch finishes every eligible independent case, then writes the final
summary and comparison; do not tail each test while it runs. Native Windows
client startup has failed intermittently with `0xC0000005`; such cases remain
failures with their own evidence and cleanup status.

The runner takes an exclusive `test-artifacts/validation-matrix.lock`. A second
invocation refuses to start, so Gradle, server and client streams cannot race.
It creates a new `test-artifacts/validation-matrix-<timestamp>/` directory and
never deletes an earlier run.

## What the plan covers

With `-FullMatrix`, the generated `matrix-plan.json` contains:

| Area | Values |
|---|---|
| Dimensions | Overworld, Nether, End |
| Route | Vanilla and trusted-raw assisted |
| Players | 1 and 2 |
| Correctness conditions | Direct, cache, cache + prediction |
| Validation | 8 authoritative cells for assisted correctness profiles |
| Performance conditions | Baseline and cache + prediction + validation |
| Performance sampling | one warm-up run and three measured repeats per profile |

Correctness and performance scenarios use server `view-distance=10`. The earlier
distance-4 correctness workload sometimes finished locally after its only
remote attempt was correctly cancelled by a synchronous wait; the larger
workload provides additional asynchronous owner demand. Before either measured region, an assisted scenario warms one
owner at a time at a single location and requires a successful remote result;
that initial client/server load is outside the correctness and performance
windows. Network scenarios fix the remote job timeout at 30,000 ms and record
it in `scenario-config.json`; conclusions must state this condition. The
short-timeout fallback path remains covered by the lifecycle and abnormal-case
test suite rather than being mixed into throughput measurements.

The plan first runs compile, the complete JUnit suite using `test --rerun-tasks --continue`,
then `build -x test --continue`. A failed JUnit case preserves
all fresh XML and still permits the independent build and later analysis cases.
JUnit failure details are copied into the case directory and included in the
final JSON. The command exits nonzero only after every eligible case has ended.

Each dimension/route/player/profile combination is an isolated scenario. The
batch runner has a timeout for every owned child process. On timeout it stops
only that child process tree, retains stdout/stderr, and marks later runtime and
performance cases `SKIPPED` if cleanup safety is not proved. JUnit/build and
the final aggregation are still allowed to finish. A normal scenario failure
with an explicit cleanup proof does not prevent independent scenarios.

The 26.2 public Overworld transcript fixture is not ported. The 26.3 batch
records it as `SKIPPED`; it is never counted as a trusted-raw result.

Before the network matrix, `Run-SettingsSmoke.ps1` launches one isolated
development client. It enters the settings screen from vanilla Options,
captures the client page and all three server pages, toggles and reloads the
persistent client/server files, and proves that server enable and raw-seed
disclosure remain separate choices. The fixture exits itself and writes one
machine-readable result; the matrix does not watch the GUI continuously.

To omit performance cases while investigating correctness, use
`-SkipPerformance`. This produces explicit `SKIPPED` rows rather than silently
shrinking the matrix.

## Capability contract

The checked-in `scripts/validation-capabilities.json` enables the selected
26.3 Fabric runtime/performance cases. Its absence or an
invalid declaration produces `SKIPPED` rows with the exact reason; it never
turns unavailable dimensions into a pass. The build/test cases remain eligible.

The production owner should provide this shape when connecting
`Run-WorldgenScenario.ps1`:

```json
{
  "schema": "worldgen-assist.validation-capabilities.v1",
  "runtime": {
    "available": true,
    "reason": "all dimensions implemented and reviewed",
    "loopback_only": true,
    "artifact_policy": "fresh_build_exact_version",
    "public_test_seed": 8675309
  }
}
```

The runner resolves and hashes the distribution JAR produced by this invocation's
successful build. `fresh_build_exact_version` makes that JAR the required
scenario artifact, so ordinary production changes do not require manually
updating a tracked hash. A capability file may optionally add
`expected_artifact_sha256` as a deliberate additional pin; when present, it
must match the fresh build. The scenario runner is
called with the following parameters for every planned case:

```text
-Dimension overworld|the_nether|the_end
-Mode vanilla|assisted
-Players 1|2
-Purpose correctness|performance
-CacheEntries N
-Prediction true|false
-ValidationCells N
-Seed public-test-seed
-OutputRoot <case-directory>
```

Local scenario, fixture, and aggregation processes run with PowerShell 7
(`pwsh.exe`), which is checked before they start. Remote helpers may retain
Windows PowerShell 5 where their own contract requires it.

For performance cases, the scenario runner owns the warm-up and measured-repeat
boundary using the values in `matrix-plan.json`; it must not publish a speed
claim from the warm-up. It must keep the listener on loopback and use the exact
distribution JAR. Its use of the public seed is limited to this test fixture;
no private-world seed belongs in the capability file or evidence.

An initial dimension arrival can finish local generation before the client's
new-world acknowledgement. The assisted warm-up permits one additional fresh
in-dimension relocation after 20 seconds, within the same 60-second deadline;
only actual successful application satisfies readiness. After correctness
assistance is proved, both routes force-load a 21-by-21 comparison region per
owner and require every region NOISE digest. This prevents a quiet but incomplete
vanilla log from omitting an applied coordinate. This extra generation is never
part of a performance measurement.
Each region is split into four commands below vanilla's 256-chunk limit.

Each scenario must finish by writing `<OutputRoot>/scenario-result.json`:

```json
{
  "schema": "worldgen-assist.scenario-result.v1",
  "success": true,
  "cleanup_safe": true,
  "loopback_only": true,
  "artifact_sha256": "UPPERCASE_SHA256_OF_THE_JAR_USED",
  "source_manifest_before_sha256": "SHA256_OF_PRODUCTION_BUILD_AND_SCRIPT_INPUTS",
  "source_manifest_after_sha256": "SAME_SHA256_AFTER_THE_SCENARIO",
  "dimension": "overworld",
  "mode": "assisted",
  "players": 1,
  "purpose": "correctness",
  "cache_entries": 128,
  "prediction": true,
  "validation_cells": 8,
  "correctness": {
    "required_applied_chunks": [{ "dimension": "overworld", "chunk": "10,20", "digest": "LOWERCASE_SHA256" }],
    "noise_digests": [{ "dimension": "overworld", "chunk": "10,20", "digest": "LOWERCASE_SHA256" }]
  }
}
```

Missing, malformed, or false contract fields convert a zero child exit code
into `FAILED`. `cleanup_safe: false` prevents later runtime cases, because a
retained server/client or port could contaminate their evidence. The batch
runner preserves the exact child logs and does not attempt to kill discovered
or unrelated Java processes.

The source manifest includes production, build configuration, and harness
scripts. Its before/after hashes must be equal. The comparator uses the
`dimension + chunk` digest key, requires every assisted
`required_applied_chunks` row to exist and match in its vanilla pair, and
compares every shared NOISE digest. An assisted correctness case with no applied
chunk is a failure, not evidence that assistance worked.

Performance scenarios use this object. `measured` contains measured repeats
only; warm-up rows never belong in it.

```json
{
  "performance": {
    "warmup_runs": 1,
    "measured_repeats": 3,
    "measured": [{
      "repeat": 1,
      "server_cpu_ms": 12.5,
      "tick_mean_ms": 18.2,
      "tick_p95_ms": 31.7,
      "throughput_tasks_per_second": 42.0,
      "attempted_tasks": 648,
      "completed_tasks": 648,
      "timeouts": 0,
      "fallbacks": 0,
      "failed_tasks": 0
    }]
  }
}
```

After every runtime and performance case has ended, the batch runner invokes
`scripts/Compare-WorldgenScenarioMatrix.ps1 -RunRoot <matrix-evidence-root>`
only when the capability file also declares the comparison phase available:

```json
{
  "aggregation": {
    "available": true,
    "reason": "comparison runner installed"
  }
}
```

The comparison runner must aggregate the scenario evidence after execution; it
must not treat an individual case as a final performance conclusion before its
paired vanilla/assisted runs and repeats are available. If the comparison runner
is absent or not declared, the final row is `SKIPPED` with that reason.

The comparator writes JSON, CSV, and Markdown beneath `analysis/` in the matrix
evidence root. It excludes warm-ups and reports median and nearest-rank p95 for
server CPU, tick mean/p95, and throughput, as well as timeout/fallback/failed
task totals and rates. For assisted runs it also reports client calculation and
encoding, RTT, server decode, encoded bytes, validation, application and total
remote latency from the existing job lifecycle metrics. Client-reported timing
is descriptive and is not used for an authoritative decision. Missing metrics,
including per-owner client CPU time, elapsed time, average CPU cores and peak
working set, remain explicit issues. Client process load is sampled once before
closing any client, from process start through server completion, and includes
joining and warm-up. It is reported separately from the three measured server
repeats; it is not a steady-state client benchmark. The runner requires no AI
polling to obtain these measurements. Other missing metrics,
malformed records, missing pairs, and
source/JAR mismatches remain explicit issues. A complete report is descriptive
benchmark evidence; it does not by itself claim an assisted performance gain.

To reanalyse preserved evidence without repeating runtimes, pass a new
`-AnalysisDirectory` to the comparator. Existing analysis directories are never
overwritten. Both FAILED and INCOMPLETE analyses exit nonzero after writing the
report, including CSV rows with missing metrics.

For a targeted follow-up spanning evidence directories, create a new run root
with a `matrix-plan.json` containing exactly the intended cases and pass their
original `scenario-result.json` paths through `-ScenarioResultPath` (a PowerShell
string array). No results need to be copied or relabelled. Performance pairs
record both original paths and still require identical artifact and source
manifest hashes within each pair. Different pairs can retain different harness
snapshots; this is an evidence selection, not a fresh full-matrix execution.

Before publishing performance ratios, the comparator also checks the recorded
seed, player count, warm-up/repeat counts, server view distance/timeout, saved
client rendering conditions, and each repeat's exact NOISE chunk-coordinate
set. Missing evidence, client options-load errors, or unequal conditions suppress
ratios and make the analysis INCOMPLETE. Raw metrics remain available. An
equal task count alone is insufficient for a controlled comparison.

## Results and review

Read `summary.md` or `summary.json` only after the completion line. They list
every case, status, reason, JUnit failures, and log path. Status has a strict
meaning:

| Status | Meaning |
|---|---|
| `PASSED` | Child exit, scenario contract, exact JAR and cleanup requirements all succeeded. |
| `FAILED` / `TIMED_OUT` | The case ran and evidence identifies its log/XML. |
| `SKIPPED` | A declared prerequisite was absent, unsafe, or intentionally disabled; this is not success. |

Performance comparison/analysis belongs after all runtime cases have ended.
The scenario runner should retain per-run data under its `OutputRoot`; the final
summary provides the stable list of those paths for a separate comparison phase.

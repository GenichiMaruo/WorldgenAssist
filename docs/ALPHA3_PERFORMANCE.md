# Alpha.3 performance evaluation — 2026-09-22

The evaluation is complete for the twelve selected conditions below. It does
not demonstrate a throughput improvement: assistance reduced throughput in
every measured condition. Server CPU reductions in some conditions coexisted
with CPU regressions in others. Remote assistance remains opt-in.

## Method and evidence

Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2 and JDK 25.0.4
were held fixed. The dedicated server ran on the authorized second PC; one or
two installed clients ran on the first PC, through a loopback-only SSH tunnel.
Each scenario used public test seed 8675309, server view distance 10, a
30,000 ms remote timeout, one excluded warm-up and three measured fresh-region
repeats. Correctness digests were disabled during performance measurements.

Baseline means cache 0, prediction off, validation 0. The other profile uses
cache 128, prediction on and 8 validation cells. Every pair has the same JAR,
source/harness manifest, server conditions, client rendering conditions and
measured NOISE coordinate sets. Each measured repeat completed 841 NOISE tasks
for one player, or 1,682 for two. Client process CPU/memory samples include
startup and warm-up and are reported separately from server repeat metrics.

The final read-only report is
`test-artifacts/performance-selected-review-20260922-140947-977/analysis/scenario-matrix-analysis.json`:
**COMPLETE, 12 pairs, 0 issues**. Its `evidence-selection.json` retains the
original path for every scenario; no old result was overwritten. The assembly
wrapper initially failed after writing this complete report because it read an
unset PowerShell exit-code variable; the wrapper now reads the report status.
This did not change measurements or the comparator's successful result.

Six unchanged pairs come from `validation-matrix-20260921-012249-436`.
The six repaired pairs combine:

- `targeted-nether-vanilla-p1-20260921-220808-029`
- `targeted-performance-remainder-20260921-221140-896` (five cases, all passed)
- `targeted-performance-partners-20260921-224025-256` (six cases, all passed)

This selection spans several completed runs, not a fresh full regression
matrix. Older pairs use Custom client graphics; repaired pairs use Fancy.
The conditions match within each pair, but comparisons between profiles from
different runs cannot isolate graphics-independent effects. The failed
options-loading run `validation-matrix-20260921-154954-662` is excluded.

Measured artifact SHA-256:
`3A8D1EB51D81AF204C9CD90DE8F6CC467218429D2FBD133F18D952449182720C`.
The later settings-menu fix produced a different JAR SHA-256. A class-by-class
comparison found 205 existing classes unchanged, three settings classes changed,
four settings-only classes added and none removed. The final settings JAR is
`FFEDD9D8B780A6496500ACA955D1D62C3AB7DE4FF85209CAA55D760D8BDF2AC9`;
the exact comparison is saved in
`test-artifacts/settings-rate-limit-verification-20260923-182905-264/class-comparison.json`.
No terrain-generation class
changed, so the worldgen measurements remain tied to the exact JAR above and
are not relabelled as a new benchmark of the settings build.

## Observed median changes

Percentages are assisted relative to vanilla. Negative CPU/tick values mean
less time; negative throughput means fewer completed tasks per second.

| Dimension | Players | Profile | Server CPU | Throughput | Tick p95 |
|---|---:|---|---:|---:|---:|
| Overworld | 1 | Baseline | -5.7% | -2.6% | -0.5% |
| Overworld | 1 | Cache/prediction/validation | +0.8% | -1.0% | -2.9% |
| Overworld | 2 | Baseline | +2.4% | -6.4% | +3.1% |
| Overworld | 2 | Cache/prediction/validation | +0.9% | -3.1% | -4.3% |
| Nether | 1 | Baseline | +2.4% | -1.9% | +1.0% |
| Nether | 1 | Cache/prediction/validation | +10.9% | -2.7% | +9.1% |
| Nether | 2 | Baseline | -1.8% | -3.5% | +6.2% |
| Nether | 2 | Cache/prediction/validation | -0.8% | -6.3% | +9.4% |
| End | 1 | Baseline | -11.5% | -13.0% | -11.9% |
| End | 1 | Cache/prediction/validation | +9.2% | -10.1% | -12.5% |
| End | 2 | Baseline | -1.2% | -8.7% | -7.3% |
| End | 2 | Cache/prediction/validation | -7.5% | -15.6% | -11.9% |

The JSON also records client compute/encode, RTT, decode, encoded bytes,
validation, application and total remote latency, per-owner process load,
and timeout/fallback/failure totals and rates. Three repeats support this
descriptive comparison, not statistical significance or a general performance
claim. Fixed-location relocations are not long-session exploration, public
server, heterogeneous-client or arbitrary-network validation.

## Reproducibility without unnecessary reruns

Use `Run-ValidationMatrix.ps1 -Execute` for an explicitly needed complete
sequential run, or `-CaseId` for affected paired scenarios. Independent failures
are collected after execution. Use `Compare-WorldgenScenarioMatrix.ps1` with
the selected original paths for read-only reanalysis; see
[validation matrix](VALIDATION_MATRIX.md). Do not rerun the successful twelve
pairs for documentation or settings-menu-only edits.

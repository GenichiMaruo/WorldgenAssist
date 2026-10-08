# Native request batching: measured gap and next experiment

## Closed evidence (2026-10-09)

Both native ordinary gameplay pairs pass all25 steps on GENPC-SUB's E-drive,
view32, two clients on the same stronger PC, one excluded warmup and one
measured relocation. Production is exact public `0.1.0-alpha.8+mc26.3`.
OFF/ON refer to remote assistance; both conditions use the same cooperative
terrain backend and parallel FEATURES policy.

| Loader | Closed gameplay root | Full OFF / ON (ms) | Change | Server CPU change |
| --- | --- | --- | --- | --- |
| Forge | `ordinary-forge-gameplay-gate-20261008-003009-312` | 104659.671 / 95680.146 | -8.5797% | -15.1998% |
| NeoForge | `ordinary-neoforge-gameplay-gate-20261009-022233-731` | 109611.099 / 121885.440 | +11.1981% | -19.3924% |

These are single-repeat descriptive comparisons, not repeated native speed
proof or controlled attribution to a code edit. Different loader/run values
are not a causal cross-loader experiment. The prior six Fabric paired ratios
(three reused coordinates, both orders) retain their separate ~20.93% gain.

All four saved players per loader have Health20, DeathTime0, survival,
onGround/nonflying, no recorded deaths/damage, and persisted client-placed
COBBLESTONE. Each condition's own lighting recomputation covers50 interior/
162 halo chunks/10,649,600 values with zero differences. Both measured runs
have10658 terrain tasks,3461 required received chunks per owner,10082 original
feature/light bodies, peak2 bodies and zero footprint conflicts. Extra receipts
are separate. Independent peer/audited/provided-BIOMES paths actually run;
timeouts/rejections/quarantines are rejected by the unchanged gate.

Unshipped input JAR SHA256:

- Forge: `6B65D7D6385D98FC721356E90C9DFA0DEB3BB04BDF57C4763FB2E182913661BD`
- NeoForge: `CA8169353432A46622A80FD8CCA3BDDFCC31E599AD0E81182872A3B4C1341C3C`

`test-artifacts/native-ordinary-analysis-20261009/closed-native-collection.json`
verifies both closed source inventories, current3 production artifacts, both
probes and24 copied native raw inputs. Forge predates the explicit three-file
Neo path repair; its original source identity remains attached to its result.
The interrupted parent003008-389 is NOT declared successful. Neo021910-947's
pre-game264-character path failure is retained; Neo's16-character directory
label now works with full64-character manifest/file checks (72 libraries).
Forge's64-character label remains unchanged (94 libraries).

## Existing-log analysis only

No new games/JUnit/MOD builds. A disposable copy of the original coverage
analyzer changes ONLY its expected closed interval count3->1. Original/adapted
hashes are in `analyzer-provenance.json`; original helper/source/raw logs stay
unchanged. Every actual full application is joined through its original
server-issued UUID to a measured NOISE coordinate; all counts have zero
applications outside that interval's10658 coordinates.

| Loader | Actual complete applications | Ready cache | Prefetch joins | Client compute mean (ms) | Request/reply mean (ms) | Request batches |
| --- | --- | --- | --- | --- | --- | --- |
| Fabric, earlier230655 | 8681 (81.45%) | 3379 | 45 | 16.412 | 54.676 | 1564 |
| Forge | 7581 (71.13%) | 3082 | 59 | 16.867 | 75.479 | 0 |
| NeoForge | 8162 (76.58%) | 3049 | 68 | 15.755 | 96.793 | 0 |

Event means overlap and request/reply time includes queues; these are not
disjoint CPU time, raw network latency, or a critical-path decomposition.
Server CPU falls in both native cases, while Neo completion worsens. Thus
reduced server calculation alone is insufficient here; coordination/waiting
deserves investigation. One run does not prove a persistent native regression.

## Source-confirmed implementation gap

`FabricRemoteJobSender.sendJobs` overrides the common individual-send fallback
and uses `TerrainJobBatchPayload` when supported. Its already bounded shared
codec allows1..4 individually identified jobs, rejects duplicate UUIDs and
preserves each complete request's original input/domain limits.

Neither `ForgeRemoteJobSender` nor `NeoRemoteJobSender` overrides `sendJobs`.
Both therefore send one request packet per job. Their network registrations
and client adapters register only `TerrainJobRequestPayload`, not the optional
batch type. Zero logged native batches agrees with this source evidence.
This establishes the missing feature, NOT that it caused Neo's slower run.

## Next implementation and minimum verification (not implemented yet)

1. Register the existing shared batch codec in BOTH native transports; route
   every contained job to the same original worker request handler. Override
   native `sendJobs`, with the same1..4 bound and individual-send fallback when
   batching is disabled/unavailable. Do not merge identities, approvals,
   deadlines, cancellation or validation. No new generation/Mixin algorithm.
2. Provide an explicit native request-batching switch for a controlled SAME-JAR
   batchingOFF/ON comparison. Keep other scheduling, seed, validation, client
   workers/windows and terrain/feature policies identical. A packet batch
   reduces dispatches; it never grants collective result authority.
3. Advance the development version and native channel compatibility explicitly;
   publicalpha8/tag/assets remain immutable. Inspect pinned Forge66.0.3 and
   Neo26.3.0.13-beta networking APIs before edits. Shared protocol14/job codec
   can remain only if their bytes/semantics are unchanged.
4. Finish ALL implementation/harness/docs before ONE sequential affected batch:
   necessary native builds; changed native registration/actual batch use/
   normal gameplay/saved-state checks; controlled weak E-server/view32/two-client
   performance comparison with repeated same coordinates. Reuse unchanged
   shared-codec/JUnit and Fabric proofs ONLY with exact original identities;
   do not rerun unrelated suites. Fail on missing batch use or any authority,
   terrain/lighting/task/cleanup guard violation; do not lower gates for speed.
5. Analyze completion, server CPU, request/reply timing and actual early/full
   consumption together. Keep/reject batching from completed evidence. Server
   restart/continuous exploration and broader stability remain separate beta
   gaps; passing this transport experiment alone does not complete the goal.

No new production code/version or batching test has run yet. Goal remains active.

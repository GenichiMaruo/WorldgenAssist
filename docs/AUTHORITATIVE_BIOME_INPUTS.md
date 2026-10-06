# Authoritative biome inputs for actual terrain assignments

## Completed dev23 batch: useful gain, further confirmation required

`feature-pipeline-gate-20261006-231008-388`/session21220 exits0. Exactly four
affected methods pass (0 failures/errors/skips), three fresh loader builds and
all16 sequential steps pass. ALL882 required coordinates per loader are equal
for NOISE, decoration, saved structures and saved light. Required-region actual
complete/peer/shaped/provided-input applications are respectively:

| Loader | Complete | Distinct peer | Nonempty shaping | Provided BIOMES |
| --- | ---: | ---: | ---: | ---: |
| Fabric | 583 | 432 | 7 | 152 |
| Forge | 530 | 407 | 16 | 212 |
| NeoForge | 466 | 306 | 16 | 166 |

Both owners exercise distinct peers and provided inputs in every loader.
Whole-process provided-input marker counts220/217/169 are a different scope;
do not substitute them for the required-region counts above. The fresh original
and assisted fixture players pass post-fullphase xyzY150/health20/alive/flying
checks. Functional native view4/server2/client4 is not native performance proof.

Same exact dev23 JAR, remote OFF/ON, identical parallel FEATURES backend,
natural weak E-drive server, two clients on one stronger PC, ordinary view32,
prediction ON/4client workers/owner32/global64/cache128/30s timeout, one warmup
and three matched relocations. Both conditions process10658 tasks and each
owner receives3461 chunks per measured repeat. Each repeat has10082 feature
bodies and10082 light initializations, peak2, all feature/feature-light/light
footprint conflicts0. Failed tasks/timeouts/quarantines/application rejects0,
clean shutdown and frozen runtime source/harness/JAR evidence retained.

| Metric | OFF repeats | ON repeats | Median of matched-repeat ratios |
| --- | --- | --- | ---: |
| FULL completion, seconds | 103.314854 / 119.829496 / 110.075181 | 82.159421 / 94.199018 / 90.302317 | -20.4767% |
| Client receipt, seconds | 109.1661246 / 126.4221194 / 115.9762823 | 86.7078503 / 98.8318507 / 94.4776694 | -20.5726% |
| Server CPU, milliseconds | 407234.375 / 462281.250 / 439671.875 | 336718.750 / 351812.500 / 354046.875 | -19.4748% |
| Tick p95, milliseconds | 12.3090 / 12.5591 / 11.6566 | 13.4542 / 13.5694 / 12.6758 | +8.7435% |

All three improve FULL/receipt/CPU; all three worsen tick p95. Tick p95 remains
below14ms here, but this is a real cost rather than a universal stability gain.
These are paired ratios, not ratios of condition medians. This establishes a
current assistance gain, NOT the isolated causal effect of snapshot inputs or
the difference versus a previous JAR. Do not add older percentage gains.

Exact artifacts:

- Fabric `E6A00C6BB2E44FA45954084C6EDC5B20D44882C3579E80D0E32D0C8346FFBA5A`.
- Forge `69CDB260220997BDDD49E852D74ABB4C0D37C489BC1F873A7D38FFC58A67EE14`.
- NeoForge `BF6FBD4E5CCD92BB815F5CD2DBC22408294E6B9D7A9A365A3B34B2188B8E5BCC`.

## Offline coverage and remaining work

After the entire runtime batch stopped, existing coverage/JFR helpers read the
retained logs and exact measured repeat2 windows. No new game/JUnit/MOD build/
SSH. Actual complete applications8802/9886/9221 (82.59/92.76/86.52% of10658
NOISE tasks), all within those same coordinates. Direct provided-input
applications5369/6461/5485, ready cache3377/3370/3685, pending-prefetch joins
56/55/51; do not equate pending joins to already-ready work. Full-audit events
457/175/394 are a different event population. All current input markers and
original3x3 guards demonstrate consumption, not every historical refusal's fix.
In particular, no provided-input marker at the old-2013,3043 failure coordinate
was found in this run; do not claim an exact-coordinate snapshot repair witness.

Exact repeat2 computational samples30874 OFF /19992 ON, truncated419/285.
Inclusive FEATURES18.018/31.538%, original BIOMES4.227/6.488%, remote management
0.418/11.970%. Input capture4.012%, input digest1.271%, input encode0.075%,
server-private restore0.070% ON. Original six-volume biome resolver assembly
only0.165/0.280% of sampled stacks here. RTree selection remains substantial;
the earlier six-volume-only offload idea is lower priority on this evidence.
Percentages overlap and truncated stacks limit attribution; these are not CPU
durations, disjoint budgets or critical-path savings.

The original analyzer's top150 inclusive methods cut off at2.802/4.262%; absence
from that list is not zero. AFTER stopped runtime, five specific analyzer groups
were added and the same two windows read in ONE offline sequence. Raw JFR
hashes, sample/truncation populations and every prior category count are exactly
unchanged. Original reports and original/new analyzer copies are retained in
`performance/*/analysis-inputs`; this analysis-only edit changes no production
or tested artifact. It does not retroactively change frozen runtime identity.

Next retain this checkpoint and verify opposite measurement order using exact
production/JAR/unit evidence, with no unchanged builds or unit reruns. Then
address measured metadata-copy/digest work and ordinary mixed-stage/light/player
stability before beta. Pure climate sampling is not yet a supported large saving;
do not implement it simply because it is easier to test. The previous saved-light
failure remains retained, not retroactively explained. No new default, public
release or beta claim; the complete acceleration/beta objective stays active.

## Dev23 implementation and retained initial failure

First batch `feature-pipeline-gate-20261006-230842-786`/session36340 exits1 at
the unit/build step: new test evaluated UpgradeData before registry bootstrap;
all four selected methods consequently fail through the same poisoned registry
initialization. Production and test compilation pass; no completed build gate,
SSH transfer, game, native or performance run. All four failure XMLs are retained
in that closed root. Added the existing original bootstrap initialization to the
new test class; production source unchanged. After this repair, ONE fresh full
focused batch reattempts the four previously failed methods and remaining gates.
No unrelated tests. All new runtime/speed verification is still UNRUN.

Version `0.1.0-alpha.8-dev.23+mc26.3`, shared protocol14, Forge channel5 and
NeoForge registrar5. Matching updated clients are required. Remote assistance,
seed disclosure and this additional policy remain explicit opt-ins.
`worldgen_assist.remote.authoritative_biomes` or
`WORLDGEN_ASSIST_REMOTE_AUTHORITATIVE_BIOMES=true` enables the new input path;
default is false. It initially covers fresh actual COMPLETE_TERRAIN requests.
Speculative and already queued jobs retain their private BIOMES generation.

The stopped dev22 failure world and original sampler prove an actual-coordinate
history-sensitive choice between frozen_ocean and snowy_plains, with exactly
equal minimum fitness. They support the refusal mechanism but do not contain
the rejected live payload or its historical thread trace. See
[BIOME_ORDER_DIAGNOSIS.md](BIOME_ORDER_DIAGNOSIS.md). Recomputing those inputs
independently can produce an unusable result and duplicate local terrain work.

## Input and authority lifecycle

1. At fresh actual TERRAIN admission, copy the existing completed BIOMES of
   all nine original dependencies outside coordinator/result locks. The source
   is the eligible WorldGenRegion, not a later network-thread world lookup.
   No new generation task, ticket or neighbor speculation is introduced.
2. Bind immutable geometry, all quart values and per-section palettes including
   unused entries to the job. Only exact stock ProtoChunk/LevelChunk sources,
   completed BIOMES and nonretrogen geometry are accepted. Unsupported capture
   cancels the preparation reservation and uses the existing original fallback.
3. Send a bounded optional input body with the COMPLETE request. Names bind
   biome codes; mutable sections and process registry IDs do not cross the wire.
   The window bound is20+9*(4+CompleteBiomeData.MAX_BYTES). Four maximum windows
   plus maximum shaping and fixed request metadata remain below the verified
   original clientbound custom payload1MiB limit. Decode checks size before
   allocation, truncation, trailing data, version, geometry and assignment.
4. On the owned client/audit computer, restore private AIR ProtoChunks with the
   original biome filler and registry holders. Require the original generator's
   possible-biome domain and exact restoration, including unused palettes. Do
   not run private climate selection for provided dependencies. Execute the
   unchanged original fill, surface and carver methods with those inputs.
5. Independently assigned peers receive identical snapshots and must match the
   inputs as well as existing assignment metadata. First full server audits and
   subsequent private sampling remain. The snapshot grants no reply authority.
6. Before any live write, retain all existing authority/domain/geometry/height/
   empty-target/structure checks and compare the assigned snapshot's v2 digest
   with the entire current original3x3 biome window. Existing result selection
   must match that same window. Reject changes before writes; never normalize a
   hash or accept a history-dependent alternative without current input proof.
7. The actual post-write marker `job.authoritative_biomes_applied` establishes
   consumption. Merely capturing or sending a snapshot does not.

This does not remove the server's original BIOMES computation. It aims to reduce
unusable replies and duplicate terrain work, and skips duplicate private BIOMES
sampling for these assignments. Snapshot capture, transport and full private
restoration add work and may outweigh that saving. There is no shared mutable
cache or new executor. Carver queries outside the window remain original and
may retain their own history sensitivity. No speed or general parity is claimed.
An independent earlier BIOMES offload lane remains a separate future candidate.

## Verified original source relationship

Primary generated26.2 `ChunkAccess.fillBiomesFromNoise` receives a resolver and
Climate.Sampler. Exact26.3 receives the resolver only; the port uses the exact
26.3 signature rather than copying the older descriptor. Exact26.3
`ChunkPyramid` requires BIOMES radius1 for TERRAIN. Existing original dependency
publication supplies the complete input window. `Climate.RTree` has thread-local
lastResult and equal-fitness history-sensitive selection, unchanged here.
Exact26.3 `ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE` is1,048,576 bytes.
No new Mixin target or ordinary gravity/render/player-control change is added.

## One focused sequential batch after all edits

`Run-FeaturePipelineGate.ps1 -Execute -AuthoritativeBiomeInputs` selects exactly
four affected methods: immutable input/wire/original restoration, mixed request
codec bounds, full-peer assignment binding and early-peer assignment binding.
No unchanged publication/feature/lighting unit methods or full matrix rerun.
It builds the three loader artifacts sequentially, then fresh physical and both
native two-owner original/assisted phased fixtures. ALL882 required coordinates
must match for NOISE, decoration, saved structures and saved light, with actual
distinct peers, nonempty shaping, snapshot application for both owners and
post-fullphase player safety. Functional prediction is OFF to exercise fresh
actual requests; native server2/client4 processors/workers are functional only.

Then same dev23 JAR, identical parallel FEATURES scheduler, remote OFF versus
ON, prediction ON, ordinary view32 on the natural weak E-drive server and two
stronger clients: one warmup and three matched relocations, JFR recordings.
Require actual provided-input use in every assisted repeat, unchanged task and
receipt counts, no ownership conflicts, rejection/quarantine/audit failure,
clean shutdown and frozen source/JAR identity. Measure end-to-end FULL/receipt,
server CPU and ticks. Do not add historical percentage gains to this result.

The implementation-time UNRUN statements above retain their original checkpoint
meaning; completed evidence at the top supersedes them. Publicalpha7 is unchanged;
the older saved-light failure and dev22 overall rejection remain retained evidence.
Passing phased snapshots alone does not prove ordinary mixed gameplay lighting
or beta readiness. The full performance/beta objective remains active.

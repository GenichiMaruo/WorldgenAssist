# Early biome distribution — dev.19 (completed; no measured gain)

Candidate `0.1.0-alpha.8-dev.19+mc26.3`, general protocol13, native channel4.
Public alpha.7 is unchanged. This is an experiment, not a beta or speed claim.

## Completed gate154217-052

ONE affected method/3 builds/physical and fresh native actual-route parity pass.
Physical ALL472 postmarker BIO applications match original;Forge111/Neo64
match original. All shared full-BIO and format2 NOISE digests agree. Exact
source/JAR identities,scopes,prior failures and counts are in TEST_RESULTS_LATEST.

SAME-JAR assistance ON BOTH,ordinary weak E-server/view32/two clients on one
stronger PC,BIOMESoff versusready,warm1/3same relocations: paired median
FULL+0.89%,client receipt+0.84%,server CPU+0.17%,tickp95+0.50%;only1/3 lower
for each. No demonstrated speed/CPU gain;default staysOFF. This is NOT general
remote-on/off evidence and does not inherit old~11% results from other artifacts.

Offline analysis of the SAME stopped logs/recordings (no new game/test/build):
BIO applies1079/1635/1534 versus10658 same-window NOISE tasks,10.12/15.34/
14.39% of that workload (not all BIO-task coverage). First-phase early only
7/11/9;remaining1072/1624/1525 complete-cache. Apply spans0.342/0.339/0.396ms
on average,not CPU durations. Exact repeat2 JFR original biome-stage samples
7.18->6.30%,features30.14/29.84%,center capture0.149%,early application0.267%.
Inclusive samples overlap and stacks truncate;no critical-path/CPU-time claim.
This supports low useful early coverage,a small overall opportunity and larger
remaining work,not cutting verification or claiming capture is a large server
bottleneck. Inspect earlier assignments/client ingress and feature/light proof
before selecting the next implementation. All following UNRUN text is historical.

Dev.19 corrects a process-local provenance gap found during review: a decoded
nonpeer complete result has only domain validation on unaudited jobs. It cannot
authorize cached BIOMES merely because peer approval is absent. Cache use now
requires exact peer center agreement OR explicit successful independent FULL
original-server-body equality (including all biome metadata). The latter remains
revocable and is never imported from the wire or reported as peer verification.
Original audit selection/probability is unchanged. After ALL edits,one focused
existing method+3 builds+physical/native parity+same-JAR view32 comparison in
Run-EarlyBiomesGate -TestProfile complete-biome-authority. All dev.19 evidence
UNRUN;completed dev.18 hashes/results below do not certify this source.

## Hypothesis and actual change

Previous JFR inclusive biome selection was about12% of computational samples;
samples overlap and do not forecast wall-clock savings. Private clients already
generate the original3x3 BIOMES before full terrain. Send the center immediately,
then continue full terrain without waiting for an ACK. Ready-only consumption
can avoid server climate/biome selection on existing demanded chunks. No new
assignment, coordinate, ticket, executor, blocking wait or network round trip.
There IS one extra small one-way reply per complete assignment when opted in.

Extra explicit server gate `WORLDGEN_ASSIST_REMOTE_ALLOW_REMOTE_BIOMES=true`
(or `worldgen_assist.remote.allow_remote_biomes`) additionally requires existing
remote-on, trusted_raw, complete+allow, and peer-verification choices. Default OFF.
Current stock Overworld/no blending/retrogen limits remain. Seed secrecy and
hostile collusion remain unresolved; this retains the trusted-friends model.

## Authority and bounds

- Only an already trusted distinct-owner NONAUDIT pair can authorize a first
  reply. First two audits and private1/64 (or fallback1/8) server audits remain;
  audited jobs cannot grant early authority from an unverified first reply.
- Check every center quart voxel and every section's sorted original palette
  including UNUSED names. Private sorted name codes are not registry IDs.
  Maximum128 names/128 UTF-8 bytes per name/384 height/1536 voxels/21286 bytes.
  Unsupported palettes fall back; arbitrary/custom generation is not added.
- Pair maps contain at most the existing global number of pending assignments;
  unknown/unowned replies are discarded, duplicates cannot grow memory. Both
  original identities, geometry, inputs, owner epochs, context and deadline bind
  the pair. Direct and prefetch requests dispatch AFTER pair registration in
  the same scheduling turn, using the existing batch sender.
- The first reply never completes a pending full job or releases its slot.
  Decode/ingress uses the same bounded two-worker decoder, with connection
  revocation before/after receipt. No live world reads/writes on the network.
- Final body repeats the exact center. An owner changing its own early/final
  reply fails validation/quarantines; different truthful private histories do
  NOT become biome authority or get normalized. Existing terrain alternatives
  and authoritative3x3 digest selection remain. A separate local-only exact
  center-agreement flag controls cache BIOMES use; it cannot cross the wire.
- Already captured first-phase approval can survive SUCCESSFUL final validation
  only if both early and final centers agree. Its original deadline/epochs remain;
  its map entries are removed and no TTL extends. Failure revokes it immediately.
- A fully validated cached complete result can supply BIOMES without consuming
  the TERRAIN reply, refreshing TTL or accepting a raw unvalidated response.
  Peer cache use also requires exact center agreement; server-audited cache use
  retains independent complete recomputation. Recheck entry/TTL at application.

## Original game execution

Verified generated primary26.2 and exact Fabric26.3, Forge66.0.3,
Neo26.3.0.13-beta sources: ChunkStatusTasks.generateBiomes delegates to original
ChunkGenerator.createBiomes;26.3 uses Util.backgroundExecutor().forName(createBiomes).
Original decorateBiomeResolver applies blending/retrogen, which this limited
path excludes. Original ChunkAccess.fillBiomesFromNoise visits generation-height
sections; LevelChunkSection.fillBiomesFromNoise recreates its original container,
loops x/y/z and replaces only its biome container. No copied climate or filler math.
26.2's sampler-taking filler differs; it is reference only, not code for26.3.

Exact fresh stock ProtoChunk/sections, pre-BIOMES status, geometry, empty states/
entities/offsets and canonical default PLAINS palette are checked. Resolve
holders only from the current server registry; every used voxel must belong to
the original preset's possibleBiomes. Preflight ALL section copies with original
filler/recreate/resize behavior and compare their complete palettes/voxels before
live mutation. Final authority check follows allocation. Keep live sections and
state containers; original biome-container replacement and ChunkStep future/status
publication remain. Never invoke vanilla after any live write. Unsupported or
unavailable data takes original generation before mutation. Ready-only may have
little coverage because BIOMES occurs earlier than TERRAIN; measure this directly.

## First gate and scoped continuation

Gate125026-461 stopped at Forge route coverage, not terrain mismatch. Affected
gate125027-160 passed7 common+1 fragment methods,3 builds and physical parity:
2,113 shared full biome-stage digests,0 differences;ALL535 postmarker remote
biome applications match original (1early_peer/534complete_cache). Native Forge
same-PC unrestricted server had2,282 matching shared biome digests but0 actual
BIOMES applications,so its proof gate correctly FAILED. Performance and Neo
runtime remain UNRUN. Receiving early replies is not evidence of consuming them.

Continuation reuses the exact successful tests/builds/physical evidence ONLY
after source inventory/hash and JAR checks. No production changes. Native BOTH
conditions now use2 JVM-visible processors to exercise the ready-only branch;
this is explicit functional coverage,not native speed evidence. Fresh native
pairs precede the original natural weak E-server performance comparison,whose
CPU limit remains0. All continuation runtime/performance is UNRUN.

Continuation152214-306 also stopped on Forge coverage:2,318 shared full biome
digests match but0 actual applications,despite2 server processors. Its existing
view4 demand and2 client workers differ from the physical view10/4-worker proof.
Next SAME production/tests/JARs: native BOTH view10/4workers,with the same21x21
comparison regions and2-processor functional server. This tests demand breadth
and producer throughput without adding world fixture tickets/coordinates or
weakening the required actual application/original parity. Fresh native and
natural weak-server performance remain UNRUN; prior failures are retained.

View10 native gate152742-552 exercises Forge209/Neo128 actual remote BIOMES,
all original-equal;2,883/2,913 shared full biome-stage digests match. Both NOISE
comparisons pass,but overall gateFAILS one Neo onboarding premarker3x3 biome
window refusal at0,18. Its original fallback matches vanilla full BIO and NOISE
digests independently. Cause remains unproved;do not normalize/fabricate inputs
or waive the failed gate. Performance is UNRUN. This led to the separate explicit
audit-provenance review/fix above,not a claim it caused/fixes that refusal.

## Original verification sequence (implemented before first gate)

Finish every production/test/harness edit before ONE Run-EarlyBiomesGate -Execute.
Exactly7 affected common methods (3 new bounded wire/authority/application methods,
the changed connection-revocation and shaping-codec methods,2 affected existing
complete-body methods) plus1 changed Forge fragmentation method,3 builds.
Then small physical original/assisted parity, fresh Forge/Neo two-owner pairs,
then SAME-JAR assistance-enabled BIOMES off/ready on natural weak E-server and
two clients on one stronger PC,view32,4 workers/client,owner32/global64/cache128/
30s,FEATURES off,section preparation INLINE,warm1/3same relocations.

Correctness additionally records an INDEPENDENT original name-based digest of
all quart voxels and EACH section palette. All shared centers and EVERY actual
postmarker remote BIOMES application must have an original counterpart/equal
digest. Premarker unpaired onboarding is recorded separately, never asserted
verified. Old format2 NOISE digests alone do not prove biome parity. The new
digest is OFF for performance; actual route/coordinates/source/JAR/CPU/window/
receipt checks are mandatory. Compare FULL, client receipt, CPU and ticks.
Retain failures/gaps; do not claim beta or acceleration until results complete.

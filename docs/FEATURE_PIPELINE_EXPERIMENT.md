# FEATURES pipeline experiment — next implementation

Status2026-10-05: source investigation complete; implementation/tests unrun.
Alpha.7 is published. This experiment is not included in that immutable release.

## Hypothesis and evidence

AG's weak-server/view32 comparison reduced server CPU20.53% but FULL only2.24%.
On all three generated26.3 loaders, ChunkMap creates one consecutive worldgen
executor; generateFeatures synchronously decorates before returning a completed
future. Keeping this stream occupied can prevent it from admitting independent
generation work while clients compute. AG JFR feature-inclusive23.44% supports
investigating the stage, but does not measure its critical-path duration.

Primary sources are the installed target-version generated source archives,
not26.2 code. Exact methods/lines are in WORLDGEN_PIPELINE and MIXIN_TARGETS.
Read-only extraction provenance is retained locally in
test-artifacts/release-alpha7/feature-source-findings.json.

## Implementation sequence

1. Measure FEATURES admission/start/end and original dispatcher occupation with
   compact aggregate diagnostics. Retain job/coordinate/stage identity so
   overlap can be distinguished from CPU reduction or queue migration.
2. Move the original FEATURES body behind its original completion future using
   an explicitly selected owned scheduler. Initially one decoration worker:
   release the dispatcher for independent terrain/biome work while retaining
   FIFO decoration. No client mutation, copied decoration, or new world ticket.
3. Allow two decoration workers only after defining each job's complete access
   footprint: accumulated cached chunk dependencies, writes, and shared
   StructureStart/pieces. Preserve arrival order for all intersecting jobs.
   Do not infer independence from the3x3 block-write area alone. Unsupported
   decoration or missing footprint must use the same serial coordination lane.
4. Keep executor/admission bounded and cancellation/close exactly once. Resolve
   saturation without blocking the worldgen/main/network thread and without
   executing an uncoordinated inline fallback. If this cannot be proved, keep
   the experimental scheduler serial rather than silently losing authority.
5. Version the development candidate separately; retain remote/seed/complete/
   peer opt-ins and default-off behavior. Keep the public alpha.7 tag unchanged.

Critical design questions to resolve before enabling concurrency: shared piece
flags, native decoration hooks, dependencies transitioning after submission,
completion ordering, saturated admission and shutdown. Canonical noise graphs
alone do not establish that a modded feature implementation is thread-safe.

## Minimum affected verification — one sequential batch after implementation

- Focused scheduler tests only: conflicting FIFO/disjoint overlap, bounded
  admission/fallback, exactly-once failure and shutdown. Use deterministic
  coordination, not timing-dependent sleeps or duplicate implementation tests.
- Build three loaders once. Fresh small two-owner functional comparisons must
  digest **FULL state after decoration**, including blocks,biomes,heightmaps,
  postprocessing and block entities that the stage can change. Normalize only
  known unrelated nondeterministic data. Include structures crossing chunk
  boundaries and repeated overlapping demand. Noise-only parity is inadequate.
- Performance on the authorized unrestricted weak server, all remote files on
  E, view32/server+clients32, same two strong-PC clients, same coordinates and
  workload, one warmup and paired repeats. Separate original-scheduler remote
  off, new-scheduler remote off, and new-scheduler remote on. A gain that also
  occurs without clients is a scheduler gain, not distributed-compute evidence.
- Retain source/JAR/order identity, FULL/receipt/server CPU/tickp95, feature
  overlap and fallback counts. Start with one selected batch; repeat/reverse
  order only to resolve a concrete reproducibility gap. Do not run unchanged
  protocol/fragment/cache suites or broad dimension matrices automatically.

## Acceptance

Require actual completion/receipt gains on the intended weak-server setting,
matching final state and no authority/lifecycle failures. Large CPU savings
without waiting-time reduction do not meet the goal. Beta additionally needs
reproducibility and stability evidence; a green minimum gate alone is insufficient.

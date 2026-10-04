# Repository agent instructions

## Scope and sources

The published alpha.6 and current alpha.7-dev.11 candidate target Minecraft Java Edition **26.3** with
Fabric Loader **0.19.5** / Fabric API **0.161.0+26.3**, Forge **66.0.3**,
and NeoForge **26.3.0.13-beta** on Java **25** (verification JDK 25.0.4).
The earlier 26.2 Fabric alpha.3 remains on `mc/26.2` and is immutable.
Dependencies are in `gradle.properties` and the native build files; see
`PORT_26_3.md` for source-verified breaking changes and remaining limits.
Keep dependency and protocol changes explicit. Use
`scripts/Get-WorldgenArtifact.ps1` (with `-Loader` for native builds) to select
exact JAR names. Published tags,
releases, and assets are immutable.

For Minecraft internals, inspect the generated source for the **target version** before changing
symbols, descriptors, threading assumptions, or Mixins. Run `genSources` if it
is missing. Keep verified findings in `MIXIN_TARGETS.md` and
`WORLDGEN_PIPELINE.md` synchronized with code. Consult Fabric sources/docs for
their APIs; do not infer current mappings from older releases.

Read the relevant protocol and test documents before changing their paths:
`REMOTE_PROTOCOL.md`, `SEEDED_LEAF_FIXTURE_PROTOCOL.md`,
`MULTIPLAYER_SUPPORT.md`, `VALIDATION_MATRIX.md`, and
`TEST_RESULTS_LATEST.md`. Use `SEED_CONFIDENTIALITY.md` for the unresolved
security boundary and `ALPHA3_PERFORMANCE.md` for measured performance.

## Non-negotiable behavior

- AA alpha.7-dev.10/protocol10 adds COMPLETE_TERRAIN, only with work kind
  `complete`, separate `allow_complete_terrain=true`, existing remote-on and
  trusted_raw seed-disclosure choices. Default remains off. It uses a fixed
  server-owned50-state terrain palette, private original bulk biome/terrain/
  surface/carver methods, exact bounded ordered postprocessing, and WG heights.
  The complete context also fingerprints biome/material/carver registries,
  preset source and block tags. Initially eligible stock Overworld preset only;
  actual empty blending/Beardifier, geometry, state identity, existing3x3 biome
  window and an empty pre-TERRAIN target are checked before any write.
  Unsupported contexts run original vanilla. No retry after a partial write.
  A separately opted-in trusted-friend whole-chunk audit checks first two
  successful owner/epoch/context results, then server-private probability1/8.
  At most two initial audits are admitted until they agree; all replies still
  receive complete domain/bounds/lifecycle checks. This is not hostile-client
  proof or seed confidentiality; old eight-cell decisions remain unchanged.
  Registration, cancellation, owner/dimension/reload epochs and original owned
  queue bounds remain. AA completed17 affected/3 fragment methods,three builds,
  1,802 matching digests and view32 FULL-8.21%/receipt-8.64% all three faster,
  CPU+3.91% all three higher. No beta or CPU savings. AB dev.11 permits existing
  bounded asynchronous waiting only when overlap is selected; ready mode stays.
  AB completed5 affected methods/three builds/1,802 matching digests, FULL-7.30%/
  receipt-6.51%/CPU-10.67%, all three improved, tick p95+4.48%. Full consumption
  reached74-84%. No fresh native complete runtime or opposite-order AB evidence.
  Retain exact source/JAR identities; never apply AA/Z performance to AB.

- The server owns final world state. Following the user's2026-10-03 authorization
  to change the distributed computation method, clients may return bounded
  density or explicitly gated aquifer-decision intermediates for their assigned
  owner work; never arbitrary block states, entities, tickets or world mutations.
  The server checks
  responses and keeps a vanilla-compatible local fallback.
- Preserve per-owner job limits, cancellation, disconnect and dimension-change
  cleanup, deadlines, and stale-result rejection. Do not block the server or
  network event thread waiting for remote work.
- Remote assistance stays **off by default**. Trusted raw-seed assistance
  requires a second explicit seed-disclosure choice and reveals the seed to
  participating clients. It is for trusted friends' test worlds, not an
  untrusted public server. Seed confidentiality is unresolved.
- On the published 26.2 line, the separate transcript fixture is limited to the already public seed
  **8675309**. Its persistent disclosure budget and authority lifecycle must
  remain intact. Its general protocol `CURRENT` is **2**; fixture packets use
  their separate v3 family. The fixture is **not ported to 26.3**. The
  immutable alpha.4 general trusted-raw protocol is **3**; alpha.5 uses **4**
  for exact float32 density transport. Alpha.6 uses **6** for bounded
  terrain-grid/surface intermediate transport. Server and clients must match. Read
  `SEEDED_LEAF_FIXTURE_PROTOCOL.md` before changing runtime or network behavior.
- Alpha.7-dev.1 used protocol **7** and experimentally added
  `BLOCK_DENSITY_AND_SURFACE`, selected explicitly with remote work kind `block`.
  Only doFill receives canonical positive density values; other final-density
  queries retain the original sampler. General terrain v7 allows 99,073 values;
  the unported fixture's 98,304 bound is unchanged. Eight density groups plus
  eight independent surface groups retain the trusted-client sampling model.
  Default remote-off/seed-disclosure gates and grid selection are unchanged.
  It passed8 affected tests, three builds and1,814 shared terrain digests on
  Fabric. Its matching natural-server view32 pair was slower: FULL+24.05%,
  receipt+24.34%, CPU+13.29%. It is not the default or a speedup.
- Alpha.7-dev.2 used protocol **8**; TERRAIN_DECISIONS_AND_SURFACE
  requires work kind `decisions` plus explicit operator `allow_terrain_decisions`.
  Only seven aquifer codes and the existing surface fields are accepted.
  doFill interprets them; carvers retain vanilla aquifer/density calls.
  Verification uses8 complete aligned4x8x4 cells plus8 surface groups, keeping
  1,024 density/decision checks without column prefixes. Old DENSITY retains
  prefix checks. All code values are checked even with zero sampled validation.
  Supported vanilla Overworld graph/fluids only, original owner/epoch/deadline
  bounds, opt-in/raw-seed gates and fallback remain. Eight tests, three builds,
  1,813 shared terrain digests/zero mismatch passed; view32 FULL+6.47%,
  receipt+6.26%, CPU-1.34%. No acceleration or beta readiness.
- Alpha.7-dev.3 introduced protocol **9**: the same decision domain/validation
  with byte-prefix/float-suffix transport, immutable float result storage and a
  doFill WrapMethod calling a small server-owned fill helper. Check context and
  complete geometry before any mutation; retain vanilla section locks, traversal,
  heightmaps, fluid postprocessing, surface/carver execution and fallback.
  No per-block Aquifer operation hooks or copied code-density buffer remain.
  O passed13 affected tests,2 Forge fragment tests, three builds and1,813
  matching terrain digests. View32 FULL+3.54%, receipt+3.35%, CPU-4.49% with
  overlap timing. P with the unchanged O JAR and ready-at-execution timing
  improved FULL2.75%/receipt2.78% in all three repeats but CPU increased4.42%.
  Existing lookahead32 rotation is the next configuration experiment; no beta
  claim yet. It retains all observed demand and the existing owner/global bounds.
- Q with the unchanged O JAR, ready timing and lookahead32 passed1,802 matching
  terrain digests. View32 FULL-4.15%, receipt-4.46%, CPU+4.02%, all three FULL
  repeats faster but all three CPU measures higher. No substantial speedup or beta.
  R alpha.7-dev.4 keeps protocol9 and adds immutable byte-code results end to end,
  eliminating repeated float expansion/domain scans only for factory-validated
  codes. Secret verification is unchanged. The fill helper skips only vanilla's
  unwritten AIR outcomes and Heightmap.update's exact early-out range. Keep all
  section writes/counts, locks, traversal and fluid postprocessing. R passed13
  affected/2 Forge tests, three builds and1,813 matching terrain digests. Marginal
  FULL medians improved12.16% but two of three same-region repeats were slower;
  no reliable acceleration. Decode/compare overhead decreased; no beta claim.
- S alpha.7-dev.5/protocol9 changes cooperative scheduling only. Bounded owned
  workers choose READY output, then independent LOCAL work, then PENDING work;
  after eight bypasses the oldest work must run. If all work is pending, compute
  locally immediately; never wait on a response. Existing admission/fallback,
  original runnable/dependency/section locks and all authority checks remain.
  Cooperative automatic queue increases4->16 per worker (2 workers/32 queued);
  explicit overrides remain. Readiness is only a hint; consumption still checks
  current owner/context/deadline. Both performance conditions use this backend.
  S passed3 affected methods, three builds and1,792 matching terrain digests.
  Same-repeat view32 FULL-4.56%/receipt-5.38% in all three, CPU+3.28% in all
  three. Cache coverage roughly22%; this small speedup is not a beta basis.
- Trusted raw-seed assistance supports eligible new vanilla noise terrain.
  T alpha.7-dev.6/protocol9 observes existing ChunkMap.scheduleGenerationTask
  centers requesting TERRAIN or later, before their EMPTY/disk stage completes.
  Exclude known persisted TERRAIN, no added tasks/tickets or neighboring guesses.
  These hints share the existing bounded owner queue; loaded/stage hints remain.
  Unknown persisted status may represent an existing chunk, so speculative work
  is discarded unless original actual-stage eligibility and authority checks pass.
  Job diagnostics distinguish task hints and their age before registration.
  T passed4 affected methods, three builds and1,802 matching terrain digests.
  Same-repeat view32 FULL-6.79%/receipt-7.26% in all three; CPU paired median
  +3.83% (two higher), cache coverage~22.5%. No beta basis. U changes only
  existing lookahead32->0 with the exact T source/JAR; original test/build
  identities must match before reuse. U completed1,803 matching digests;
  same-region FULL-13.84% (two faster), receipt-13.48% (all three), CPU-3.75%
  (two lower), using median paired ratios. Initial coverage-assertion failure
  stays recorded; original successful runtime was reused after full identity checks.
  V alpha.7-dev.7/protocol9 visits only already claimed task TERRAIN dependencies
  using the source-verified accumulated radius. Per-holder persisted/demand gates,
  cancellation and final eligibility/authority remain; no new task/ticket.
  Candidate counts/order cache must invalidate on every mutation, owner movement,
  expiry and lookahead change. Six focused methods/three builds and1,802
  matching Fabric digests passed, actual dependency dispatch verified.
  V same-region view32 FULL-5.70%/receipt-6.69% in all three, CPU+4.94%
  in all three. Cache coverage~31-34%; no substantial speedup or beta claim.
  Targeted native two-owner Overworld decision parity/dependency validation
  passed: Forge1,971/NeoForge1,954 shared digests,zero mismatch;304/28 full
  in-workload applications. Forge runtime was reused, analysis fresh; initial
  onboarding/coverage failures remain disclosed. No native speed claim.
  W changed only bounded owner/client32/global64 windows and reversed physical
  condition order, exact V JAR/tests/builds retained. It passed1,803 shared
  digests/zero mismatch and actual1,095 full applications. Same-region view32
  FULL-5.29%/receipt-5.47% all three, CPU+5.21% all three; no beta basis.
  X alpha.7-dev.8 retains protocol9 and adds bounded actual-stage admission
  metadata (at most max_in_flight entries) ahead of speculative observations.
  Capture only already eligible context/key; dispatch/send on the existing
  server task with owner/epoch/demand/started/deadline checks. Registration-wait
  is PENDING, never authority or a blocking response wait. Original eight-bypass
  bound and immediate local work for all-pending queues remain. Clean snapshots
  on start/completion, owner invalidation and reload. X passed5 affected methods,
  three builds and1,813 matching Fabric digests. Same-region view32 FULL-13.93%,
  receipt-14.04%,CPU-2.80%, all three improved; tick p95+3.66%. Full cache use
  ~40-50%; cache.hit counts early uses only. Native X targeted fresh runtimes
  passed Forge1,987/NeoForge1,971 shared digests,zero mismatch, actual new-lane
  full applications. Y opposite order retains X: FULL-6.86%/receipt-7.08% all
  three faster, CPU+6.31% all three higher. CPU savings did not reproduce.
  Z alpha.7-dev.9 skips candidate sorting/batch creation only while no demanded
  owner lease or global/preparation slot is available. The capacity hint never
  reserves or authorizes work; original admission checks/callbacks/ticks remain.
  Z completed three affected methods, three builds and1,802 matching digests.
  View32 FULL-13.99%/receipt-14.49%, two of three faster; CPU-6.79%, two lower.
  Vanilla repeats varied substantially; no beta claim or causal edit attribution.
  The subsequent AA complete-terrain core is implemented and Fabric verified;
  see current AA/AB evidence above. Native complete-mode runtime is unrun.
  Alpha.5 also admits compatible additional dimensions and explicit vanilla
  noise delegates, with only a prior Fabric custom-dimension fixture verified.
  Arbitrary custom generators and client-unavailable noise registries remain
  unsupported. Latest runtime evidence is Fabric Overworld with two owners;
  native builds passed but changed native paths were not rerun. Do not claim
  general client-offload speedup: dev.9's constrained-server cooperative
  scheduler improved FULL/receipt, but assistance was 4.24%/4.53% slower than
  the same scheduler without assistance at view32. Dev.12's wide-window
  ready-only comparison was also slower (+8.42% FULL/+7.48% receipt). The
  unchanged dev.12 asynchronous profile's view32 medians improved 1.88% FULL,
  1.44% receipt and 6.62% CPU, but completion improved only two of three
  repeats and tick p95 worsened. This is limited descriptive evidence; keep
  scheduler and client-assistance effects separate.
- Ordinary players may edit their own participation setting, but server policy
  changes require operator authority. Server-side revision and request identity
  checks must remain authoritative. Saved server policy needs a restart;
  client participation needs a reconnect.

## Changes and verification

Keep Mixins small and confirm changed targets against generated sources. Update
the relevant Markdown when an implementation fact or limitation changes. Do
not silently turn a historical checkpoint into a current test result.

Run **only the tests affected by the change**. For a focused code edit, run its
relevant JUnit class(es) and the build tasks needed to produce or verify the
artifact. For a Fabric runtime/Mixin change, use the smallest affected
vanilla/assisted pair with `Run-ValidationMatrix.ps1 -Execute -CaseId`.
Its 26.3 installed-client batch is enabled; the default is a curated
ten-scenario plan, and `-FullMatrix` opts into the 60-case cross-product.
Use the full matrix only when a
broad change or unresolved evidence gap actually requires it. Documentation
and release packaging alone do not require another Minecraft runtime matrix.
Reuse successful evidence only with its original source, harness, and JAR
identity; report failed and skipped cases honestly. See `VALIDATION_MATRIX.md`
and `TEST_RESULTS_LATEST.md` for selection and evidence rules.

Never run competing Gradle builds/tests or runtime fixtures in this workspace.
Let a selected batch finish, then inspect its compact final summary and all
failures together; avoid frequent status polling. Use `apply_patch` for text
edits. Keep unrelated user changes out of commits. Before publication, verify
the exact versioned artifacts and their hashes, stage only intended files,
check the staged diff, and publish a **new** prerelease without force-pushing
or replacing older tags/assets. See `VERSIONING.md`.

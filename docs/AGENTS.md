# Repository agent instructions

## Scope and sources

The current alpha.6 line targets Minecraft Java Edition **26.3** with
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

- The server owns final world state. A client returns bounded terrain-density
  intermediates only for work assigned to its own player; the server checks
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
- Trusted raw-seed assistance supports eligible new vanilla noise terrain.
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

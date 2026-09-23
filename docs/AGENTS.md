# Repository agent instructions

## Scope and sources

Worldgen Assist targets Minecraft Java Edition **26.2**, Fabric Loader
**0.19.3**, Fabric API **0.156.0+26.2**, and Java **25** (verification JDK
25.0.4). Keep dependency and protocol changes explicit. The current version is
defined by `gradle.properties`; use `scripts/Get-WorldgenArtifact.ps1` to select
its exact JAR and tag. Published tags, releases, and assets are immutable.

For Minecraft internals, inspect the generated **26.2** source before changing
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
- The separate transcript fixture is limited to the already public seed
  **8675309**. Its persistent disclosure budget and authority lifecycle must
  remain intact. General protocol `CURRENT` is **2**; fixture packets use their
  separate v3 family. Read `SEEDED_LEAF_FIXTURE_PROTOCOL.md` before changing
  runtime or network behavior.
- Trusted raw-seed assistance supports vanilla Overworld, Nether, and End for
  eligible new chunks. Mod-added dimensions and custom generators are outside
  the verified scope. Do not claim a speedup: the twelve measured alpha.3
  conditions showed lower assisted throughput.
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
artifact. For a runtime/Mixin change, add the smallest affected vanilla and
assisted scenarios with `Run-ValidationMatrix.ps1 -CaseId`; run the public
fixture separately only if that path changed. Use the full matrix only when a
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

# Repository agent instructions

## Scope and sources

The published line targets Minecraft Java Edition **26.2**, Fabric Loader
**0.19.3**, Fabric API **0.156.0+26.2**, and Java **25** (verification JDK
25.0.4). Branch `feature/mc26.3-multiloader` is an **incomplete 26.3 port**:
do not offer its artifacts for normal installation, tag or publish them.
Isolated installation inside the authorized test fixture is permitted. Its dependency versions are in
`gradle.properties`; see `PORT_26_3.md` for source-verified breaking changes.
Keep dependency and protocol changes explicit. Use
`scripts/Get-WorldgenArtifact.ps1` to select exact JAR names. Published tags,
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
  remain intact. General protocol `CURRENT` is **2**; fixture packets use their
  separate v3 family. It is **not ported to 26.3**. Read
  `SEEDED_LEAF_FIXTURE_PROTOCOL.md` before changing runtime or network behavior.
- Published 26.2 trusted raw-seed assistance supports vanilla Overworld, Nether, and End for
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

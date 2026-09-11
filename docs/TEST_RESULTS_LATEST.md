# Test results — 2026-09-11

## Versioned alpha checkpoint — 2026-09-11

`0.1.0-alpha.1+mc26.2` passes fresh Phase A–D (240 tests / 51 suites,
zero failures/errors/skips) and both-installed-JAR multi-PC assisted/vanilla
checks. All four applied chunks and 1,010 shared NOISE digests match.
See [the release verification report](releases/v0.1.0-alpha.1+mc26.2-verification.md)
for exact new artifact hashes, evidence paths, runtime warnings and limitations.
The following sections retain the preceding implementation checkpoint and its
old unversioned-alpha JAR hash; do not use that hash for the new release.

## Conclusion

Final Phase A-D checkpoint: **240 tests / 51 suites**, zero failures, errors or
skips, JDK 25.0.4. Evidence: `test-artifacts/20260910-220816-359/`.
The previous 237-test checkpoint remains at `20260909-184411-455`.
The source/build-config manifest and distribution JAR were rechecked unchanged
on 2026-09-10. Runtime harness/documentation edits do not change that Java snapshot.
Final local cleanup at 13:56 JST found zero listeners on fixture port 25585.
SSH was restored later the same day; the cross-PC batch below supersedes the
earlier connectivity blocker.
Final cleanup after the installed-client/link-drop batch at 22:18 JST again
found zero dedicated remote Java processes and zero fixture listeners on either
PC. The final 240-test source/build-config manifest still matches. Test worlds,
ledgers and evidence are retained; no user's existing world was deleted/reset.

Five fresh local Fabric server/client adversarial cases PASS: timeout,
malformed result, pending reload, pending disconnect and second player.
Fresh normal assisted/local-baseline rechecks and digest comparisons PASS:
all four installed chunks and all 1,344 shared NOISE digests match. Required
fallback terminal coordinates also match in all five adversarial cases.

Cross-PC assisted and vanilla runs now **PASS**: all four installed chunks and
all 1,002 shared NOISE digests match. The cross-PC assisted output also matches
the primary PC's independent local baseline. A subsequent both-installed-JAR
batch also passes, including natural client shutdown and targeted live-heap
checks. No performance improvement or private-seed confidentiality is claimed.
General protocol CURRENT remains 2; v3 remains restricted to the public fixture.

## Implementation and verification provenance

Two Terra-high agents added bounded test/fault-control work on disjoint files;
the primary integrated, reviewed and ran the consolidated A-D and live tests.
A further read-only Terra-high audit reviewed evidence and Windows PowerShell 5
watchdog syntax. No concurrent Gradle verification stream was used.

New changes:
- Player counts other than one suspend admission and cancel pending fixture work;
  returning to one balances this suspension independently of synchronous waits.
- Deterministic queued-client cancellation test observes a real queued attempt.
- A count-only package-private trace observer tests cancellation inside actual
  recorder/replayer leaf traversal, with cleanup and subsequent traversal checks.
- Development-only, fixture-only client fault injection withholds a bounded
  claim or returns an authenticated, codec-valid but invalid DEFLATE body.
  Installed non-development clients do not activate these fault settings.
- Fresh-profile runtime modes record pending claim ID/coordinates before
  lifecycle actions. Timeout distinguishes legitimate synchronous-wait
  cancellation from an actual TIMED_OUT result.
- Multi-PC scripts keep Minecraft loopback-only behind SSH, verify JAR hashes,
  and preserve evidence. Native cmd.exe paths now use Windows backslashes.
  The remote watchdog checks PID, start time and exact executable identity,
  with a 600-second limit. Its remote operation is not yet verified.

No Minecraft/Mixin descriptors changed in this batch. Tests do not prove
absence of transcript seed inference or deep heap retention.

## A-D and artifacts

All phases exited 0. Full XML/JSON, commands, timestamps and before/after source
manifests are in the checkpoint directory. Phase B also reran focused tests;
C and D each report 240 tests / 51 suites, with no failures/errors/skips.
The added direct protocol-v2 client-computer test compares two full-height fields
(seeds 8675309 and 123456789, origin and negative coordinates) raw-bit-exactly
against separately constructed authoritative NoiseChunk traversals. Wrong
dimension, incompatible geometry, context mismatch and interruption also pass.
Its initial isolated attempt `20260910-220532-399` failed static initialization
before Bootstrap; the test fixture was corrected without production changes.
Fresh focused `20260910-220708-554` and the final A-D pass. Distribution JAR and
source JAR hashes are unchanged because only a test source was added.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| worldgen-assist-0.1.0.jar | 417199 | 8930D8FA29B3FD61B94B5ECEC26B7D36DD60945A3D0CDABD8FA6DFC0C36BC281 |
| worldgen-assist-0.1.0-sources.jar | 138676 | E7AB91E6D88DCF26BBA1EFD3D020F58D056081F6C5144DE25BD03B200763A5FD |

Pinned Minecraft 26.2 / Fabric Loader 0.19.3 / API 0.156.0+26.2 /
Gradle 9.5.1 / Loom 1.17.20; no dependency upgrade.
The workspace remains largely untracked; no clean-commit claim.

## Fresh local runtime evidence

All directory names below are under `test-artifacts/`. Each successful case
has `summary.txt`, source hashes, commands, client/server logs and captured
stdout/stderr. Local runtime uses the dev classpath, not an installed client JAR.

| Case | Directory | Observation |
|---|---|---|
| Timeout | runtime-timeout-20260910-134248-067 | PASS. Held claim 4f2a1c23-9355-4e25-8c72-01cc277c825c, chunk 997,-2003 becomes TIMED_OUT; exact client cancellation verified. No install. |
| Malformed | runtime-malformed-20260910-134444-607 | PASS. Invalid DEFLATE result rejected at 996,-2003; WORKER_QUARANTINED, no later dispatch during probe, no install. |
| Pending reload | runtime-pending-reload-20260910-134549-375 | PASS. Held claim 1c25e360-2f83-45d2-8665-e7b0b339e850, chunk 996,-2001 becomes STALE_CONTEXT; generation rotates to 2 and exact client cancellation is present. |
| Pending disconnect | runtime-pending-disconnect-20260910-134719-463 | PASS. Held claim 3bcbe380-1579-4796-ade9-380571d143ee, chunk 996,-2002 becomes DISCONNECTED after kick. No install. |
| Second player | runtime-second-player-20260910-134834-240 | PASS. Guest joins at 13:49:41; pending chunk 996,-2006 is CANCELLED, count=2 suspends admission, guest handshake false and no dispatch during subsequent movement probe. |
| Assisted | runtime-assisted-20260910-135005-668 | Runtime PASS: four READY results installed. Digest comparison is separate from runtime pass. |
| Wrong seed | runtime-wrong-seed-20260910-135258-176 | PASS: rejected handshake, zero sends/installs/ledger files, 866 local digests. |
| Final local baseline | runtime-vanilla-20260910-135416-640 | PASS: same two-position route, longer generation window, no remote dispatch. |

The assisted `digest-comparison-retry.json` is the successful comparison:
4/4 installed coordinates equal, 1,344 shared coordinates, zero mismatches.
The earlier failed `digest-comparison.json` remains intact.

Each adversarial directory has `fallback-digest-comparison.json`. Required
terminal chunk counts and shared comparison counts are respectively:
timeout 3 / 715; malformed 1 / 1,005; reload 4 / 454; disconnect 1 / 286;
second-player 2 / 586. Every required digest exists and matches; all shared
coordinates have zero mismatches. The second-player cancelled chunk 996,-2006
was additionally checked equal (the comparison script selects timeout/rejection/
reload/disconnect terminal statuses, not CANCELLED). These are NOISE-stage
block/heightmap/post-processing digest comparisons, not all-stage world equality.

All eight servers completed save/stop successfully. Forced cleanup of owned
client process trees is not proof of graceful client JVM shutdown. Dev-account
HTTP 401 profile-key/user-properties and Realms authentication messages are
retained, not hidden; these are unauthenticated offline fixture clients.

## Cross-PC evidence after SSH restoration

- Assisted: `multipc-assisted-20260910-140234-094`; remote case
  `assisted-20260910-140241-250`. Five sends, one synchronous-wait CANCELLED,
  four READY + installed results, then non-refundable disclosure-budget rejection.
- Vanilla: `multipc-vanilla-20260910-140436-591`; remote case
  `vanilla-20260910-140443-793`. No fixture sends or installs.
- `digest-comparison.json` in the assisted directory: 4/4 installed coordinates
  equal, 1,002 shared coordinates, zero mismatches or missing required digests.
- `cross-host-baseline-comparison.json`: also 4/4 and 1,002 shared matches against
  `runtime-vanilla-20260910-135416-640` on the primary PC.
- Remote host DESKTOP-869S2KH, Windows 11 Home, i9-10980XE. Task-owned JDK
  25.0.4; installed mod SHA-256 exactly matches the 237-test artifact above.
  Local source/build-config manifest unchanged. No production code changed.
- Both servers saved/stopped with exit 0. Reviewed server logs have no ERROR,
  exception or nonzero generation-failure counter. Client logs retain the
  expected unauthenticated dev-account HTTP 401/Realms messages.
- Minecraft remained loopback-only on both hosts, reached through authenticated
  SSH over Tailscale; no firewall/global Java changes. This is functional
  multi-PC evidence, not a direct-network performance benchmark.

## Both-installed-JAR batch

- Assisted: `multipc-installed-assisted-20260910-220922-867`; remote case
  `assisted-20260910-220929-101`.
- Vanilla: `multipc-installed-vanilla-20260910-221127-885`; remote case
  `vanilla-20260910-221133-712`.
- `digest-comparison.json`: all four installed coordinates equal, 1,005 shared
  NOISE digests equal, zero missing required coordinates or mismatches.
- Original Minecraft client JAR and cached official libraries were hash-checked.
  Client `mods` contains the distribution mod/API JARs; no development source-set
  paths or dev launch injector. `fabric.development=false` is explicit. Saved
  `installed-client-args.txt`, `installed-client-classpath.json` and
  `installed-client-mods.json` identify the precise launch inputs.
- Both clients naturally exited 0 after an owned-window close request and logged
  `Stopping!`; `client-shutdown.txt` records the check. No forced kill was needed
  for these successful clients. Servers also saved/stopped normally.
- After disconnect and before closing, `jcmd GC.class_histogram` found zero live
  exact job/claim/transcript/entry/result/worker-attempt instances in both clients.
  Histograms and `client-retention-check.txt` are retained. Enum singletons and
  persistent manager/worker objects are expected. This closes a targeted
  normal-session retention check, NOT deep reference-graph, raw-array/string,
  arbitrary lifecycle or confidentiality analysis.

## Pending transport-loss test

`multipc-link-drop-20260910-221444-773` PASS; remote case
`link-drop-20260910-221450-634`. The development client held claim
`5bdaf2f0-daac-4207-b238-83f480df37e5`; only its owned SSH forwarding process
was terminated at 22:15:31.919 JST. The separate server/control SSH session
remained active. Exact chunk 997,-1996 became DISCONNECTED at 22:15:33, no
remote result was installed, and the server saved/stopped normally.
`fallback-digest-comparison.json` verifies the required chunk and all 285 shared
NOISE digests match the both-installed vanilla baseline.

Client logs contain the expected connection reset and one
`Negative index in crash report handler (18/22)` diagnostic. Generated 26.2
`CrashReport.java` confirms this message is in stack-trace alignment code;
the client remained alive on its disconnected screen and the server had no
ERROR/exception/generation-failure counter. The diagnostic is preserved, not
silently reported as zero client errors. No physical NIC/Tailscale configuration
or unrelated SSH session was interrupted. This client used development-only
withholding; it was force-cleaned after the test, not a graceful-exit proof.

## Failed attempts and resolved connectivity blocker

Watchdog PASS: `multipc-watchdog-20260910-141417-403`. The child controller
intentionally exited 97; the watchdog logged
`owned_java_stopped owner_missing_or_changed=True deadline_reached=False`;
the verifier found zero dedicated Java processes and zero fixture listeners.
This verifies controller-loss cleanup while the outer SSH session remains alive,
not physical link loss or graceful server save/shutdown. No mod code changed.

Earlier watchdog probes are retained with `success=False`:
`multipc-watchdog-20260910-140630-054` and `...-140854-645` did not keep the SSH
session host alive after controller exit; all target processes disappeared, but
the watchdog completion marker was missing and its action could not be attributed.
`...-141214-961` recorded the correct watchdog stop, but Windows PowerShell 5
lost the child exit code. Caching the child process handle before waiting was
verified with an isolated exit-97 diagnostic and fixed the subsequent fresh run.

- `runtime-vanilla-20260910-135129-952` exited successfully, but was inadequate
  as a comparison baseline: it stopped before generating the remote-applied
  coordinates. The first `digest-comparison.json` in the assisted case correctly
  records failure (four missing coordinates, no mismatches among 550 shared
  coordinates). The harness now uses the same two-position route and a longer
  generation window for vanilla. Preserve the failed comparison; a retry uses
  a distinct `-ComparisonFileName`, never overwrites it.

- `multipc-assisted-20260909-184603-118`: installed remote server reached
  SERVER_READY, installed mod SHA-256 matched the current artifact. Local client
  exited before launch because cmd.exe used mixed path separators. No packet
  success. The Java process left by interrupted SSH was identified by exact
  task executable/launcher and only that PID was stopped; partial evidence is
  retained in `failed-remote-evidence/`.
- `multipc-assisted-20260910-134004-120`: SSH connection timeout before transfer
  or launch. Nothing was started on the remote host in this attempt.
- `runtime-timeout-20260910-134032-990`: held chunk 996,-2003 was CANCELLED by
  a legitimate synchronous wait, not TIMED_OUT. Later jobs did time out, but the
  original assertion correctly failed for its selected job. Harness now retries
  a newly observed held claim after CANCELLED; it never treats cancellation as
  timeout. Successful fresh evidence is listed above.
- Earlier focused `20260909-183734-891` is superseded by the final A-D checkpoint.

## Remaining boundaries

1. Cross-PC normal generation and watchdog controller-loss tests are complete.
   Pending SSH-forward transport loss also passes. A physical NIC/link outage
   and the full 600-second watchdog deadline have not been separately tested.
2. Installed-JAR client startup, graceful client-JVM shutdown and targeted
   post-disconnect live-heap checks pass. Deep heap-reference/raw-array/string
   and arbitrary-lifecycle retention analysis remains unproven. Direct v2
   client-computer integration passes in the final 240-test checkpoint.
3. Private-world seed-inference/leakage analysis and a justified disclosure
   policy remain a research/security gate, not a switch to enable.
4. Performance judgment remains explicitly deferred.

The previous 233-test batch, its four matching installs and 1,005 shared matching
digests, and its wrong-seed negative test are preserved in
`TEST_RESULTS_20260905.md`; they are historical evidence, not a new run.
See `TEST_HANDOFF.md` for execution/recording rules.

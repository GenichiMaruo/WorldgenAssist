# Test results — 26.3 port checkpoint and published 26.2 alpha.3

On the 26.3 development branch, the Fabric, Forge and NeoForge projects now
build on JDK 25.0.4. Isolated native dedicated servers generated spawn NOISE
chunks with zero generation failures. Connected native NeoForge assistance
applied 17 remote results, and Forge assistance applied 18, each with eight
server validation cells per job. Evidence is under
`test-artifacts/port26.3-neo-assisted-2/` and
`test-artifacts/port26.3-forge-assisted-5/`. These are development launches,
not installed-JAR assisted/vanilla digest comparisons. Forge's earlier
connected runs found and then corrected a missing worker hello and its
32,767-byte serverbound packet limit; failed attempts remain in their own
fixture directories. Offline authentication/Realms errors remain in the
client logs. The 26.3 line is not published and has no measured speedup.
Two subsequent selected Overworld development-client pairs passed exact
same-seed comparison: Forge 812/812 shared NOISE digests and 14/14 remotely
applied chunks; NeoForge 812/812 and 17/17. Each server recorded 841 digests.
The independent logs and generated comparison JSON are under
`test-artifacts/port26.3-{forge,neo}-digest-{assisted,vanilla,comparison}/`.
Those initial Overworld results do not cover installed native JARs, Nether,
End or performance.
One NeoForge dimension-transition assisted/vanilla pair then matched all
2,573 shared NOISE digests across Overworld/Nether/End (841/866/866) and all
53 remote-applied chunks (19/19/15), with zero comparison issues. See
`test-artifacts/port26.3-neo-dim-comparison/` and the paired server logs.
This is a development-client comparison, not an installed-JAR or performance
result.
A matching Forge dimension-transition pair then passed: 2,539 shared NOISE
digests across Overworld/Nether/End (812/861/866) and all 52 remotely
applied chunks (17/19/16) matched. Each server recorded 2,573 digests.
Both servers stopped normally. See
`test-artifacts/port26.3-forge-dim-comparison/` and paired server logs.
This is likewise development-runtime evidence, not installed-JAR or
performance evidence.
A selected Fabric development-runtime dimension-transition pair also passed:
2,539 shared NOISE digests across Overworld/Nether/End (812/861/866) and all
49 remotely applied chunks (18/18/13) matched; each server recorded 2,573
digests. Both servers stopped normally. Evidence is under
`test-artifacts/port26.3-fabric-dim-{assisted,vanilla,comparison}/`.
This does not replace installed-JAR testing.
A selected 26.3 Fabric two-PC installed-JAR two-owner Overworld pair then
passed: both owners' required remote chunks and all 1,803 shared NOISE
digests matched vanilla, with `COMPLETE / PASS`, zero issues and safe cleanup
in both cases. The JAR SHA-256 was
`2D8BEEFE180C784E9CCCC866FBD45EF2146EE02EB217A4650805AACEA14B5894`.
See `test-artifacts/port26.3-fabric-two-owner-comparison/analysis2/` and
the paired scenario roots. Forge/NeoForge simultaneous-owner behavior remains
to be checked.
The selected 26.3 Fabric installed-client performance attempt and its one
retry both stopped during client startup with native Windows code
`0xC0000005`. Both cleaned up safely; neither is a throughput result.
Evidence is under `test-artifacts/port26.3-fabric-performance-assisted{,-retry}/`.
The Fabric adapter refactor then passed a selected two-PC installed-client
Overworld pair on the exact rebuilt JAR SHA-256
`2D8BEEFE180C784E9CCCC866FBD45EF2146EE02EB217A4650805AACEA14B5894`:
951/951 shared NOISE digests and the one remotely applied chunk matched.
The selected-pair analysis reports `COMPLETE / PASS`, zero issues, at
`test-artifacts/port26.3-fabric-refactor-comparison/analysis/`.
The initial vanilla attempt ended during resource loading with native Windows
code `0xC0000005`; its cleanup was safe, and a single retry succeeded. These
are not Nether/End or performance results.

The published 26.2 alpha.3 evidence below is unchanged. On the incomplete
26.3 development branch, two focused JUnit tests and `build` passed after the
2026-09-24 synchronous-wait Mixin fix. One installed-client Overworld
assisted/vanilla pair passed: the required remote-applied chunk and 962/962
shared NOISE digests matched. The deliberately partial comparator returned
pair PASS and overall INCOMPLETE because no full matrix plan was supplied.
See [the 26.3 port record](PORT_26_3.md) for JAR identity, evidence paths,
earlier failed attempts and remaining gates. This is not a 26.3 release or a
performance result.
Two attempted Nether assisted cases then stopped during installed-client
resource loading with native Windows exit codes `0xC0000005` and
`0xC0000374`. They do not establish Nether correctness; their evidence is
`test-artifacts/port26.3-fabric-nether-assisted-1/` and `-2/`.

## Alpha.3 release evidence

Performance evaluation is now complete for twelve selected pairs, with matched
within-pair conditions and zero analysis issues. See
[the performance report](ALPHA3_PERFORMANCE.md) for results and provenance.
Throughput regressed in all twelve measured conditions. The historical failed
runs below are retained; they do not supersede the later selected evidence.
The settings response-correlation correction and focused verification finished
on 2026-09-23. Six settings suites / 20 tests passed, with zero failures,
errors or skips; the distribution build passed on JDK 25.0.4. Evidence is in
`test-artifacts/settings-correlation-verification-retry-20260923-182455-047/`.
The first invocation accidentally expanded a wildcard test selector to
`settings.gradle` under Windows and found no tests; it is retained separately
in `settings-correlation-verification-20260922-141108-854` and is not counted
as a passing test run. The explicit-class retry is the authoritative result.
The testable server decision path now proves denied READ/SAVE cannot touch the
settings store, authorized save and stale revision behavior, IO retry and
request-ID echo. Client tests prove late replies cannot complete a newer
request, even across screen instances. This is focused path verification;
there was no additional connected menu UI runtime after the local smoke.
On 2026-09-23 a rapid Read → Save could otherwise be silently dropped by the
existing 250 ms request limit. A `RATE_LIMITED` response now echoes the request
ID and tells the user to retry. The two directly affected suites / five tests
and build passed, with zero failures/errors/skips, in
`test-artifacts/settings-rate-limit-verification-20260923-182905-264/`.
The focused runs cover 21 distinct settings tests; they do not claim a fresh
run of all 264 historical tests. The final alpha.3 distribution JAR SHA-256 is
`FFEDD9D8B780A6496500ACA955D1D62C3AB7DE4FF85209CAA55D760D8BDF2AC9`.
Its class comparison against the measured JAR is preserved in that evidence
directory: 205 unchanged classes, three changed settings classes, four added
settings classes and zero removed classes.

This section records the evidence used for alpha.3. The alpha.2 evidence below
remains a historical checkpoint. See [selected-case execution](VALIDATION_MATRIX.md)
and [alpha.3 release verification](releases/v0.1.0-alpha.3+mc26.2-verification.md).

- `validation-matrix-20260921-012249-436`: 264 JUnit tests / 58 suites,
  zero failures/errors/skips. Settings smoke passed persistence and the separate
  disclosure gate, with four English screenshots. All four screenshots have
  been visually reviewed at 854 x 480 without clipped controls. This smoke uses
  local settings from the title screen; it does not prove a connected remote
  operator/non-operator save exchange. The permission predicate and codecs have
  unit coverage; connected settings behavior still needs focused verification.
- `validation-matrix-20260921-152005-951`: three selected correctness pairs
  matched 4,284 shared NOISE digests (Overworld two-player direct: 1,802;
  Nether two-player prediction: 1,641; End one-player prediction: 841).
  All selected assisted cases succeeded. The Overworld vanilla shutdown failed,
  so the run as a whole failed; it is not an all-cases success claim.
- `shutdown-overworld-vanilla-p2-20260921-154325-547`: that vanilla case alone
  subsequently succeeded with `cleanup_safe=true`. An intermittent shutdown
  fault remains unresolved, with bounded thread capture added for recurrence.

The above runtime evidence used artifact SHA-256
`3A8D1EB51D81AF204C9CD90DE8F6CC467218429D2FBD133F18D952449182720C`.
Each run retains its own before/after source/harness manifests. Harness changes
between runs must not be represented as one frozen full-matrix result.

### Existing performance observations

In `validation-matrix-20260921-012249-436/analysis/scenario-matrix-analysis.json`,
six pairs have matching completed-task counts and complete metrics. Each uses
one excluded warm-up and three measured repeats, view distance 10, a 30-second
remote job timeout, and one or two installed clients on one PC with the server
on the second PC. These are descriptive median changes, assisted versus vanilla:

A subsequent read-only audit of the retained server logs also compared sorted
NOISE chunk-coordinate sets between each measured BEGIN/END marker. All three
repeats matched in each of these six pairs; this audit did not rerun Minecraft.

| Dimension / players / profile | Server CPU | Throughput | Tick p95 |
|---|---:|---:|---:|
| Overworld / 1 / baseline | -5.7% | -2.6% | -0.5% |
| Overworld / 1 / cache + prediction + validation | +0.8% | -1.0% | -2.9% |
| End / 1 / baseline | -11.5% | -13.0% | -11.9% |
| End / 1 / cache + prediction + validation | +9.2% | -10.1% | -12.5% |
| End / 2 / baseline | -1.2% | -8.7% | -7.3% |
| End / 2 / cache + prediction + validation | -7.5% | -15.6% | -11.9% |

CPU reductions did not translate into throughput gains in these samples;
some CPU measurements regressed. Three repeats do not establish statistical
significance or general acceleration. Per-owner client process CPU/memory and
remote overhead are retained in the JSON; client process load includes startup
and warm-up and is not a steady-state client measurement. At this earlier
checkpoint, five other pairs had unequal workloads and one further pair had a
shutdown/client-load failure. The later selected review at the top resolves
those six pairs without rerunning the six unaffected successful pairs.

The follow-up `validation-matrix-20260921-154954-662` finished with 8 passed,
7 failed and 3 intentionally skipped rows, with cleanup safe. Its 12 runtime
cases include six connection-timeout failures. Post-run review found
`Failed to load options` in every scenario's client logs: the harness added
modern key names without the options data-version header, triggering legacy
integer-key migration. Therefore even that run's six successful runtime rows
are unsuitable for controlled performance comparison (client defaults could
differ). Preserve the original summary as evidence of the reporting gap; do
not reuse its measurements as corrected results. The harness now supplies
the pinned 26.2 data version 4903 and rejects this options error explicitly.
`targeted-nether-vanilla-p1-20260921-220808-029` validates that fix: success and
safe cleanup, no options-load error, and 841 completed tasks in each of three
measured repeats. The saved options confirm version 4903, FPS cap 30 and
unbound movement. Modern options also select the Fancy graphics preset, while
the older migrated profiles used Custom (including a different chunk-update
priority and texture filtering). Server view distance remains 10 in both.
Do not silently combine these runs into a controlled performance ratio:
client rendering conditions differ, despite matching server workload counts.

Offline client authentication/Realms errors can appear in retained logs.
No claim of error-free logs, long-session stability, deep heap retention proof,
seed confidentiality, or hostile-server readiness is made.

The same retained End two-player cache/prediction/validation performance log
also proves overlapping successful owner work: job
`c2512083-b688-48e8-929e-c98297702792` at `1516,-2516` for owner
`0557a034-0101-3132-9f82-f0d4761d4b04` overlaps job
`3a15bbad-5aed-4d9a-92de-a728e4dda0a9` at `-1516,2508` for owner
`c7afee9d-8411-38e2-8537-1c157fc2c41b`; both have `job.complete` records.
This is concurrent-owner runtime evidence, not a digest comparison: performance
logging deliberately disables NOISE digests. End output equivalence is covered
separately by the selected one-player correctness pair and geometry tests.

## Concurrent-owner release checkpoint (alpha.2)

See [the alpha.2 release verification](releases/v0.1.0-alpha.2+mc26.2-verification.md)
for the exact distribution hashes and release scope.

### Current source: A-D and concurrent-owner runtime passed

Trusted-raw simultaneous runtime and comparison also PASS on the same final
JAR: `two-client-trusted-raw-simultaneous-20260914-162621-426` versus the same
independent vanilla world `two-client-fixture-vanilla-20260913-231956-963`.
Both overlapping owner jobs complete remotely; their chunks and all **382**
shared NOISE digests match. Both installed clients exit naturally with code 0
and `Stopping!`. Six other jobs time out and fall back locally, with expired
result rejections and a tick-delay warning; no quarantine is recorded. The raw
test uses timeout 10,000 ms, global capacity 8, validation 8 cells, cache 0,
prediction false. Performance remains NOT_EVALUATED.

On 2026-09-14 after these runs, dedicated remote Java count, local two-client
Java count, remote port 25585 listeners and local port 25585 listeners were all
zero. This is process/port cleanup verification, not deep heap or long-session
retention analysis. Both client processes run on one PC; the installed server
runs on the second PC, with Minecraft limited to the loopback SSH tunnel.

Public-fixture simultaneous runtime and comparison PASS on the current JAR:
`two-client-fixture-simultaneous-20260914-162349-988` versus vanilla
`two-client-fixture-vanilla-20260913-231956-963`. Each owner has one applied
result, the two corresponding requests overlap, and both applied chunks plus
all **588** shared NOISE digests match. Both installed clients exit naturally
with code 0 and `Stopping!`. Three later fixture attempts time out during
save/flush and one admission hits the unchanged disclosure budget; these are
local-fallback paths, not further successful remote applications.

The current source moves both route managers' Fabric disconnect handling onto
`server.execute`, because generated 26.2 `Connection` and
`ServerCommonPacketListenerImpl.disconnect`, together with Fabric API 6.3.3
`ServerPlayNetworkAddon.invokeDisconnectEvent`, show that the event can arrive
on a Netty NIO thread. The raw route ignores a stale disconnect when the UUID
now identifies a different connected player instance.
The development-client shutdown probe retains the actual process handle before
reading its exit code.

`test-artifacts/20260913-231321-924/` passes A-D on JDK 25.0.4. Final D reports
**246 tests / 52 suites**, zero failures/errors/skips, and the before/after
source/build manifests have no difference. The distribution JAR is 425,930
bytes, SHA-256
`4463070DAC7DB305601B6DC9FD1020E578EC6811027387C013E9726E7ADE69A0`; the
141,558-byte sources JAR has SHA-256
`8F52E7754F782D92D9A0C0DBF2049288FBA0ADFDF3B719F4AE3901BA1E7B73DB`.

The current disconnect runtime
`two-client-fixture-disconnect-20260913-231502-332` passes, including
server-thread owner cancellation, owner B application after owner A's pending
job becomes `DISCONNECTED`, and natural exit 0 / `Stopping!` for both clients.
A uses the existing development-only WITHHOLD_RESULT probe; B and the server
use the installed JAR. Comparison against the successful independent vanilla
run `two-client-fixture-vanilla-20260913-231956-963` passes: all three B-applied
chunks, A's disconnected/local-fallback chunk, and all **406** shared NOISE
digests match. Both vanilla clients also exit naturally with code 0.
The 23:10:27 fixture disconnect observed owner A `DISCONNECTED` and a
subsequent owner B application, but could not confirm the dev process exit
code; its overall result is false. It is retained as an observed lifecycle
signal, not a passing disconnect or natural-exit check.

`two-client-fixture-vanilla-20260913-231627-124` generated its world and both
clients exited naturally, but the harness detected that the comparator script
changed during the run and recorded `success=False`. Only
`scripts/Compare-TwoClientFixture.ps1` differs between its manifests; Java and
build inputs did not change. This is retained as a failed harness-integrity
check. The replacement vanilla run freezes all harness scripts. Comparisons
require identical source/test/build inputs across runs; script revisions remain
recorded separately in each manifest and copied runner.

### Preceding source snapshot: completed runtime evidence

Trusted-raw simultaneous runtime and comparison PASS:
`two-client-trusted-raw-simultaneous-20260913-230805-614` versus
`two-client-trusted-raw-vanilla-20260913-181624-500`. Two overlapping jobs
complete remotely for their own players; both target chunks and all **378**
shared NOISE digests match. Both installed clients exit naturally with code 0
and `Stopping!`. Six other jobs fall back on timeout, with expired-result
rejections and a tick-delay warning; this is not uninterrupted assistance or
a performance result. Settings: timeout 10,000 ms, global capacity 8,
validation 8 cells, cache 0, prediction false. Client offline-profile/Realms
errors and the client-side integrated-server raw gate warning remain recorded.

The preceding-source trusted-raw vanilla baseline
`two-client-trusted-raw-vanilla-20260913-181624-500` passed, including natural
exit 0 and `Stopping!` for both clients. The preceding assisted attempt
`two-client-trusted-raw-simultaneous-20260913-181338-230` is retained as failed:
its selected overlapping pair timed out during initial generation, while later
overlapping jobs completed for both owners. The harness waited for the already
timed-out pair, so this is not a successful runtime checkpoint. Its corrected
simultaneous matcher requires an overlapping pair with both successful terminal
events; the fresh successful run above uses that correction. The comparator's
PowerShell `return if` parsing error was also corrected before its successful
comparison; no earlier comparison result was overwritten.

The preceding-source `test-artifacts/20260913-181155-891/` passes compile, focused tests, full tests
and build on JDK 25.0.4. Final C and D XML each report **246 tests / 52 suites**,
zero failures/errors/skips. The focused phase reports 168 tests / 33 suites,
also with zero failures/errors/skips. Source/build manifests match before and
after.
The six added multiplayer tests cover real two-owner transcript exchanges,
owner-scoped connection replacement/quarantine, global/per-owner capacity and
real recorder exit, shared-budget retention on reload, exact-owner raw leases,
view selection and owner/connection-specific cache identity.

That preceding checkpoint includes the timeout-triggered quarantine tightening: the raw
manager now rejects an owner-keyed cache lookup, store, or install as soon as
the coordinator marks that owner quarantined; the coordinator predicate takes
no manager lock.
The next server tick removes that owner's cache entries, predictions, demand
snapshot, and owner generation. The preceding completed checkpoint
`test-artifacts/20260913-005006-026/` remains evidence for the earlier source
snapshot only.

Installed alpha.2 JAR SHA-256:
`BC8E3BA0486EE5CF14157F2DA4E9AD8DFAB7DBC06CEFF4E687C8F31C7631F117`
(425,699 bytes). Sources SHA-256:
`AC7D404562FE30CE38DAA2016149E88EA6C3A0605E8319BCAED7455F9612B773`
(141,441 bytes).

Public-fixture two-client runtime and digest comparison PASS for the preceding
source snapshot: assisted
`two-client-fixture-simultaneous-20260913-005854-196` versus independent vanilla
`two-client-fixture-vanilla-20260913-010033-570` (under test-artifacts).
Both owners receive an applied result in their own disjoint view area. Their
requests overlap before either response; both applied chunks and all 360 shared
NOISE digests match. Both clients close naturally with exit 0 and `Stopping!`.
Two later attempts time out during save/flush and use local fallback. The
clients retain offline-profile HTTP 401/Realms errors; server warnings identify
offline mode and digest logging. No new Mixin/decode/executor error was found.
The first-pair matcher was corrected after the failed
`two-client-fixture-disconnect-20260913-180742-514` run applied both owners'
results, but polling missed the short pending interval; it then exhausted the
100,000-entry fixture budget and timed out. It is not counted as a successful
disconnect check. These are preceding-source results only; performance remains
NOT_EVALUATED.

Failed runtime preparations are retained: `two-client-simultaneous-20260913-005306-572`
stopped before client launch because the cached asset index differed from current
pinned official metadata (47 language entries). A separate verified asset root
now contains all 5,057 objects, with 47 official downloads and 5,010 cache copies;
shared caches were not modified. The remote controller reached its bounded
missing-client timeout and stopped its JVM; final failure logs are retained.
`two-client-fixture-disconnect-20260913-175912-731` reached two-owner overlapping
dispatch but the harness compared only the first job per owner and rejected an
earlier completed B job. No disconnect assertion passed in that failed run;
the matcher is being corrected to correlate individual job intervals.
The initial focused attempt `20260912-183922-197` was blocked by sandbox network
preflight; `20260912-183943-852` compiled and ran 49 tests with one historical
test failure: its owner-tracker loop assumed each connect replaced the previous
owner. It now disconnects each owner explicitly before testing cumulative tracker
exhaustion; the final checkpoint passes. A later launch was blocked by an
automatic-approval usage limit, and no test process started from that rejection.

The alpha.1 release verification and earlier results below remain historical.

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
See `VALIDATION_MATRIX.md` for current execution/recording rules.

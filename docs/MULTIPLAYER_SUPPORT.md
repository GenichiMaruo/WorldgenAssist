# Player-owned concurrent assistance

AG dev.16/protocol12 completed Fabric/native gates225828-933/232706-494.
Both owners had actual peer-approved complete terrain applications on all three
loaders;nonempty shaping matched vanilla(Fabric96,Forge15,NeoForge13 counted
applications). Native shaping was consumed only for ownerA;Fabric both owners.
Physical view32 paired FULL-2.24%/receipt-1.74%/CPU-20.53%,all three improved,
not substantial speedup/beta. Same caps/epochs/cancellation/opt-ins remain.
Following unrun descriptions retain chronology; see TEST_RESULTS_LATEST.

AG dev.16/protocol12 source carries the SAME actual per-chunk structural shaping
in primary/peer assignments and cache keys. Base owner/epoch audit cohort stays
unchanged; changing shape does not reset first-two audits or authorize a stale
cached result. Final preflight compares actual server shaping,not client claims.
Owner/view policy,peer-only cross-view verification,connection/global/owner/
preparation bounds and disconnect cleanup remain. No structural/feature placement
is delegated. ALL AG gates are UNRUN; AF is the last verified candidate.

AF dev.15/protocol11 completed fresh Fabric two-owner parity1,824 shared digests/
zero mismatch,actual complete applications633/695 including peer596/620.
Physical view32 all three FULL/receipt/CPU improved modestly(-4.27%/-3.71%/
-17.10%),tick p95+6.18%. Both clients are processes on the SAME strong PC,
weak server unrestricted on E. No beta or native complete/peer coverage yet.
Unrun paragraphs below are historical implementation records.

## AF additional peer verification assignment (dev.15; unrun)

Primary generation stays owner/view-scoped. Explicit complete peer mode may
assign verification of that same existing demanded chunk to a different current
worker in the same dimension, even outside that verifier's view. This changes
the historical own-view-only assignment rule only for verification. It creates
no additional world task/ticket and cannot authorize mutations. Each side keeps
its own connection/job UUID, adaptive lease and original global/per-owner quota.
Both owners must have two successful independent server full audits of the base
context. Entire outputs must match; the final result is tied to both epochs.
Disconnect invalidates dependent cached approvals; pair failures fall back and
mismatch quarantines both owners. Private1/64 server audits apply only while
peer mode has an eligible peer; default/no peer retains private1/8. Failed peer
admission forces a server full audit. Collusion remains a trusted-friend limit.
Five focused methods/three builds/fresh two-owner parity/view32 are unrun.


## Alpha.5 release scope

Alpha.5 includes the adaptive per-owner pipeline and connection-scoped dispatch
described below. Latest Fabric Overworld two-owner runtime matched 1,802 shared
NOISE digests; latest native-loader runtime was not rerun. All three final JARs
match saved candidates except version metadata. This does not establish faster
chunk completion or client arrival. Historical checkpoints remain separate;
see `releases/v0.1.0-alpha.5+mc26.3-verification.md`.

## Connection-scoped dispatch candidate (2026-09-29)

The current candidate batches approvals per owner (at most four) within a
single scheduling pass and refills while a small amount of server work remains
queued. It keeps the existing global/per-owner reservations and fair candidate
ordering. Every result completes independently. Client sends capture a single
connection; Fabric result ingress captures a hello-approved owner and rejects
revoked/replaced sessions before decoding. A delayed disconnect from the old
connection cannot revoke its replacement or another owner. The smallest
two-owner Overworld correctness pair is selected in `Run-NetworkDispatchGate`;
native ingress and multi-PC speedup are outside that correctness gate.
The focused gate `network-dispatch-gate-20260929-215751-919` passed both owners'
overlapping assistance and matching required chunks, with all 1,802 shared
NOISE digests equal. It observed ten per-owner batch packets and 276 accepted
network receipts; disconnect/replacement isolation is also covered by the
focused unit tests. No new multiplayer performance claim.

The adaptive wait candidate keeps latency learning per owner and clears it
when that owner's context is invalidated. It uses a maximum 200ms default
demand wait, skips unlikely direct work while the owner has active ahead work,
and wakes the existing owner-interleaved
prefetch dispatcher with a coalesced server/context task. Ownership, admission
limits and validation remain unchanged. No active ahead work permits bounded
direct admission even for a slow owner. A saturated candidate queue displaces
overrepresented entries to admit other owners without increasing capacity.
Its focused gate passed seven affected tests, Fabric build and installed
two-owner Overworld correctness on two logical server CPUs: both required
applications and all 1,822 shared digests matched vanilla, with successful
overlapping owner jobs. Evidence is `demand-wait-gate-20260929-200658-043`.
The subsequent matched two-logical-CPU benchmark was slower with assistance;
see `TEST_RESULTS_LATEST.md` for its measurements. Older performance results
below belong to the JARs named in their respective sections.

The 2026-09-29 unpublished pipeline adds actual-generation prefetch within
current player demand. The server chooses ownership and interleaves owners;
clients do not choose arbitrary coordinates. Existing adaptive client limits
also bound remote wait plus authoritative preparation, globally and per owner.
Pending and unused results are removed on owner/context invalidation and expire.
Preparation reuse and early validation do not change the trust/seed boundary.
The selected installed Fabric Overworld two-owner correctness pair passed:
both required applied chunks and all 1,814 shared digests matched vanilla,
with overlapping owner jobs. `Run-PipelinedAssistGate.ps1` also completed eight
matched two-logical-CPU performance scenarios, with complete receipt coverage.
Continuous prefetch improved versus the current-style control but did not beat
vanilla; relocation regressed. See `TEST_RESULTS_LATEST.md` for the measurements.

## 26.3 unpublished scheduler update

The current development candidate can run 1–4 client computation threads per
owner (default two) and up to four in-flight jobs per owner under the existing
global bound. Admission grows after successful work and backs off on failures.
Prediction leaves one owner slot for direct demand. Jobs remain tied to the
selected player's own view and connection; disconnect, dimension change,
timeout and stale-result rules are unchanged. The published alpha.4 behavior
and historical evidence below remain unchanged. See `REMOTE_PROTOCOL.md` for
configuration and `TEST_RESULTS_LATEST.md` for verification status.

## Decision — 2026-09-12

The user authorizes extending the single-player alpha so multiple players can
assist their own terrain concurrently. This does not authorize private-seed
transcript transport, hostile-server support, or a performance claim.

Both existing routes retain their disclosure gates. General protocol CURRENT
stays 2; fixture protocol 3 stays restricted to public seed 8675309. Published
alpha.1 tags/assets are immutable. The concurrent-owner work first shipped in
alpha.2 and remains in alpha.3.

The implementation selects an owner from server-thread snapshots of player
dimension, chunk position and effective view distance. Only an eligible worker
whose own view includes the chunk may receive direct work. Overlapping views
choose the nearest owner, with UUID ordering to break ties; a busy selected
owner falls back locally instead of borrowing an unrelated worker. Commands or
generation outside every worker's view remain local. Prediction stays owned by
the predicting player; cache/in-flight prediction identity must include owner.

The public fixture has one shared authority, context/key lifecycle and
unchanged persistent 100,000-entry global disclosure ledger. Multiple active
connections and attempts replace the singleton state, with one attempt per owner
and a bounded global capacity. Disconnect/reconnect and quarantine affect only
that owner. Reload, stop and synchronous main-thread chunk waits still cancel
all affected work. Recording/validation capacity remains occupied until work
actually exits after logical cancellation.

## Verification coverage

- Concurrent owners complete independent real recorder/replay/validation chains.
- Wrong-owner responses and stale connection tokens cannot consume another job.
- One owner's disconnect, timeout or quarantine leaves another owner working.
- Global/per-owner limits, cancellation real-exit accounting and shared disclosure
  budget remain enforced. Reload/synchronous waits cancel all attempts once.
- Owner selection covers disjoint/overlapping views, dimensions, movement and
  non-worker demand; predictions/cache cannot cross owner identity.
- Final sequential Phase A–D, then two installed clients against an isolated
  server: both owners apply results, overlapping jobs are observed, and applied
  NOISE digests match a separate vanilla baseline. Preserve failures and log
  warnings. Runtime evidence and limitations are recorded below.

## Generated-source references

Verified in existing Minecraft 26.2 `.gradle/source-inspect` sources:
`ChunkMap.getPlayerViewDistance` clamps requested distance to 2..server distance;
`ChunkTrackingView.isInViewDistance(int,int,int,int,int)` supplies vanilla's
rounded view predicate without neighbor padding. Its `Positioned` representation
is immutable. `ServerPlayer` exposes chunk position, level and requested distance.
Player state is read on the server thread and immutable values are published to
generation workers. No new Mixin target is planned.

## Current source — verification (2026-09-14)

Both routes now pass controlled two-owner concurrent assistance on the final
alpha.2 JAR. The default global limit is eight jobs and each owner gets at most
one; this does not promise eight-way runtime coverage or unrestricted public
multiplayer support. The server remains authoritative and busy/ineligible
work falls back locally.

The final trusted-raw run
`two-client-trusted-raw-simultaneous-20260914-162621-426` passes against the
independent vanilla world `two-client-fixture-vanilla-20260913-231956-963`:
both overlapping jobs complete for their respective owners, both required
chunks and all 382 shared NOISE digests match, and both installed clients close
naturally with exit 0. Six other jobs time out, with expired-result and
tick-delay warnings. Settings are timeout 10,000 ms, validation eight cells,
global capacity eight, cache zero and prediction false. This verifies function,
not performance.

The current installed-JAR public-fixture simultaneous test passes:
`two-client-fixture-simultaneous-20260914-162349-988`, compared with
`two-client-fixture-vanilla-20260913-231956-963`. Each owner applies one result;
the two requests overlap before either result. Both applied chunks and all
588 shared NOISE digests match. Both clients close naturally with exit 0 and
`Stopping!`. Three later timeouts and one budget rejection fall back locally.

The current source schedules both route managers' Fabric disconnect callbacks
onto `server.execute`: Fabric may invoke the callback on a Netty NIO thread, so
owner maps, cancellation, and replacement state are not mutated there. The raw
route also protects a replacement connection from the old disconnect callback.
Generated 26.2 `Connection` and
`ServerCommonPacketListenerImpl.disconnect`, plus Fabric API 6.3.3
`ServerPlayNetworkAddon.invokeDisconnectEvent`, were inspected for that thread
boundary. The development-client natural-exit probe now retains its process
handle before reading its exit code.

The new A-D checkpoint `test-artifacts/20260913-231321-924/` passes on JDK
25.0.4: final D reports 246 tests / 52 suites with zero failures, errors, or
skips, and the before/after source/build manifests have no difference. Its
distribution JAR is 425,930 bytes with SHA-256
`4463070DAC7DB305601B6DC9FD1020E578EC6811027387C013E9726E7ADE69A0`; its
141,558-byte sources JAR has SHA-256
`8F52E7754F782D92D9A0C0DBF2049288FBA0ADFDF3B719F4AE3901BA1E7B73DB`.
The current disconnect runtime `two-client-fixture-disconnect-20260913-231502-332`
passes: owner A is cancelled on the server thread and owner B then applies its
result. Both client JVMs exit naturally with code 0 and `Stopping!`. A uses the
existing development-only WITHHOLD_RESULT probe; B and the server use the
installed JAR. Against the independent vanilla run
`two-client-fixture-vanilla-20260913-231956-963`, all three B-applied chunks,
A's disconnected/local-fallback chunk, and all 406 shared NOISE digests match.
After the final runs, dedicated remote Java processes, local test clients, and
both port-25585 listeners are zero. The clients are two independent JVMs on one
PC and the server is on the second PC. Long-session, deep heap-retention and
arbitrary-population testing are not covered.

The 23:10:27 two-owner fixture
disconnect run did observe owner A becoming `DISCONNECTED` and a later owner B
result being applied, but could not confirm the dev process exit code. Its
overall summary remains false; the handle-retention correction is verified
by the later successful run.

## Preceding source checkpoint — 2026-09-13

Phase A–D passes on JDK 25.0.4: 246 tests / 52 suites, with zero
failures/errors/skips and unchanged source/build manifests. Evidence:
`test-artifacts/20260913-181155-891/`. The distribution JAR is 425,699 bytes
with SHA-256
`BC8E3BA0486EE5CF14157F2DA4E9AD8DFAB7DBC06CEFF4E687C8F31C7631F117`; its
141,441-byte sources JAR has SHA-256
`AC7D404562FE30CE38DAA2016149E88EA6C3A0605E8319BCAED7455F9612B773`.

The earlier `test-artifacts/20260913-005006-026/` checkpoint predates the
timeout-quarantine cache fence. In the verified current snapshot, a quarantined
owner is now rejected by every owner-keyed cache lookup, store, and install
check through a lock-free coordinator predicate. The server thread removes its
cached results, predictions, demand snapshot, and owner generation on the next
tick.

The preceding source snapshot passes trusted-raw simultaneous assistance:
`two-client-trusted-raw-simultaneous-20260913-230805-614`, compared with
`two-client-trusted-raw-vanilla-20260913-181624-500`. Both overlapping jobs
complete remotely in their respective owner areas. Both required chunks and all
378 shared NOISE digests match. Both installed clients close naturally with
exit 0 and `Stopping!`. There are six timeout/local fallbacks outside that
successful pair, expired-result rejections and a server tick-delay warning.
The test explicitly uses a 10-second deadline, eight validation cells, global
capacity eight, no cache and no prediction. It proves concurrent functional
completion, not an improvement in latency or sustained throughput.

The public-fixture two-owner installed-JAR result below is from the preceding
source snapshot. The preceding test passes:
`two-client-fixture-simultaneous-20260913-005854-196`, compared with
`two-client-fixture-vanilla-20260913-010033-570` (both under test-artifacts).
The clients run as independent JVMs/profiles on the primary PC and the server
on the second PC. Each owner receives one applied result in a disjoint area;
both requests are sent before either corresponding result arrives. The two
applied chunks and all 360 shared NOISE digests match the independent vanilla
world. Both clients close naturally with code 0 and the Minecraft shutdown marker.
Two later fixture attempts time out during save/flush and fall back locally;
this result does not assert uninterrupted remote assistance or better latency.

The first-pair matcher correction is in place. The earlier disconnect attempt
`two-client-fixture-disconnect-20260913-180742-514` applied results for both
owners, but its poll did not observe the short pending interval before the
100,000-entry fixture budget was consumed and the run timed out. It is retained
as a failed disconnect verification and is not evidence that the disconnect
assertion passed. It predates the current source changes described above.
Offline-profile HTTP 401 and Realms errors are expected fixture limitations;
no claim of completely error-free logs, online authentication, long-session
retention, private-seed secrecy or performance follows from these tests.

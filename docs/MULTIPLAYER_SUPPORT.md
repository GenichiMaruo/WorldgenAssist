# Player-owned concurrent assistance

## Decision — 2026-09-12

The user authorizes extending the single-player alpha so multiple players can
assist their own terrain concurrently. This does not authorize private-seed
transcript transport, hostile-server support, or a performance claim.

Both existing routes retain their disclosure gates. General protocol CURRENT
stays 2; fixture protocol 3 stays restricted to public seed 8675309. Published
alpha.1 tags/assets are immutable; development uses alpha.2.

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

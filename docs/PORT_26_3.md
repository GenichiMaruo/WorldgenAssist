# Minecraft 26.3 and Forge/NeoForge port

The 26.3 version is `0.1.0-alpha.4+mc26.3`. Selected matched output checks
pass across all three vanilla dimensions on each loader and installed-JAR
Overworld pairs pass for all three loaders. Forge and NeoForge both passed
installed-JAR two-owner sessions. The latter sessions kept both clients
connected but warmed their jobs in sequence; they do not prove overlapping
in-flight native jobs. The 26.2 alpha.3 release, tag, and JAR remain separate.

## Current implementation checkpoint (2026-09-25)

The shared 26.3 density calculation, owner coordinator, server validation,
settings and Mixins now build in three separate projects: Fabric, Forge and
NeoForge. Native Forge and NeoForge metadata, packet adapters, client menu
hooks and lifecycle adapters are present. All three builds passed on JDK
25.0.4. The public-seed transcript fixture remains unavailable on 26.3.

After the settings-smoke refactor, the three candidate builds completed
sequentially with the selected tests. Current uninstalled JAR SHA-256 values
are Fabric `0607E86B096CF92A0588BF2E57AA5D2789D2B5663B0F0C232F3B7D48504CBAB9`,
Forge `B9D3159F8D26BE7D8EDBF3F126E447BE6099CD64C85995CE0F622F9C937E4937`,
and NeoForge `437E4CA29E6C6055AF0372F724F87D6F1CD8BCC91FBEA3F0493589390BA56FB6`.
Each JAR excludes the unported public-seed fixture classes. The earlier
installed-client evidence below belongs to its recorded Fabric JAR hash;
it does not automatically validate this rebuilt artifact.
Forge and NeoForge now also produce versioned sources JARs. Their SHA-256
values are `E943DF1CCE785AEF0BE2449F2D4DE4066B3A22FC803BD91925387C1A335D7B73`
and `F7F215EFFEDFF890EF2DB1A4B56952937FA925E42C6C5B968E28875E0A7DB911`
respectively. Both source archives exclude the unported fixture sources;
enabling `sourcesJar` left the verified distribution JAR hashes unchanged.

Isolated native dedicated servers booted with the mod and generated at least
25 spawn NOISE chunks with zero generation failures. With a development client
connected on loopback and trusted raw-seed assistance explicitly enabled,
NeoForge applied 17 remote results with eight server-side validation cells
each (`test-artifacts/port26.3-neo-assisted-2/`). Forge applied 18 remote
results under the same policy (`test-artifacts/port26.3-forge-assisted-5/`).
Both routes retain local fallback on disconnect. Offline client
authentication/Realms errors remain.

Two further selected Overworld comparisons now establish exact output for
these development runs. Forge matched all 812 shared NOISE digests and all
14 remotely applied chunks; NeoForge matched all 812 shared digests and all
17 remotely applied chunks. Each assisted and vanilla server recorded 841
NOISE digests; the 29 nonshared coordinates per pair came from different
randomized player spawn positions. The comparisons were read from completed
server logs using `scripts/Compare-LocalLoaderDigests.ps1`, with results in
`test-artifacts/port26.3-forge-digest-comparison/result.json` and
`test-artifacts/port26.3-neo-digest-comparison/result.json`. These are
same-seed development-client runs, not installed JAR tests, other dimension
coverage or a performance measurement.

A selected NeoForge dimension-transition pair subsequently used one connected
owner and the same seed/teleport coordinates in Overworld, Nether and End.
All 2,573 shared NOISE digests matched (841/866/866), including all 53
remote-applied chunks (19/19/15). Both servers stopped normally; the
comparison and original logs are under `test-artifacts/port26.3-neo-dim-*`.
This verifies the vanilla dimension-transition path for NeoForge's development
runtime, not arbitrary modded dimensions, a long session or an installed JAR.

A matching Forge development-runtime dimension-transition pair also passed.
Of 2,573 assisted and 2,573 vanilla NOISE digests, 2,539 coordinates were
shared and all matched. All 52 remotely applied chunks matched vanilla
(Overworld 17, Nether 19, End 16); the per-dimension shared counts were
812/861/866. Both servers stopped normally after loopback RCON save/stop.
The original logs and comparator JSON are under
`test-artifacts/port26.3-forge-dim-{assisted,vanilla,comparison}/`. The
different Overworld and Nether coordinate sets reflect player/spawn timing;
this result does not establish installed-JAR or arbitrary modded-dimension
coverage.

The Fabric loader split was then checked with the exact rebuilt 26.3 JAR
SHA-256 `2D8BEEFE180C784E9CCCC866FBD45EF2146EE02EB217A4650805AACEA14B5894`
in the authorized isolated two-PC installed-client harness. The selected
Overworld assisted and vanilla results share the same source manifest, match
all 951 shared NOISE digests, and include one matching remotely applied chunk.
The selected-pair analysis is `COMPLETE / PASS`, zero issues, at
`test-artifacts/port26.3-fabric-refactor-comparison/analysis/`.
The first vanilla attempt exited during client resource loading with native
Windows code `0xC0000005`; its cleanup succeeded and it is retained at
`test-artifacts/port26.3-fabric-refactor-vanilla/`. The one retry succeeded
under `test-artifacts/port26.3-fabric-refactor-vanilla-retry/`.

A separate selected Fabric development-runtime dimension-transition pair
completed with one connected owner across Overworld, Nether and End. Each
server recorded 2,573 NOISE digests. All 2,539 shared coordinates matched
(812/861/866 by dimension), as did all 49 remotely applied chunks
(18/18/13). Both servers stopped normally after loopback RCON save/stop.
The original logs and comparison JSON are under
`test-artifacts/port26.3-fabric-dim-{assisted,vanilla,comparison}/`. This
resolves the selected 26.3 Fabric dimension comparison in the development
runtime; the earlier installed-client Nether startup crashes remain separate
and this is not installed-JAR evidence.

The exact rebuilt Fabric 26.3 JAR was also used in a selected, isolated
two-PC two-owner Overworld pair. Both owners received and completed their
own required remote chunk. Its matched vanilla partner produced the same
two chunk digests and all 1,803 shared NOISE digests, with zero analysis
issues. Both scenarios report `success=true` and `cleanup_safe=true` using
JAR SHA-256 `2D8BEEFE180C784E9CCCC866FBD45EF2146EE02EB217A4650805AACEA14B5894`.
The paired analysis is `COMPLETE / PASS` at
`test-artifacts/port26.3-fabric-two-owner-comparison/analysis2/`; the
individual cases are `test-artifacts/port26.3-fabric-two-owner-{assisted,vanilla}/`.
This verifies simultaneous owners for Fabric under the selected conditions,
not yet the native Forge/NeoForge adapters or arbitrary server populations.

The first selected 26.3 Fabric performance condition did not yield a
measurement: the installed Windows client exited during startup with native
code `0xC0000005` in both the initial run and its one retry. Both scenarios
report `cleanup_safe=true`; evidence is under
`test-artifacts/port26.3-fabric-performance-assisted{,-retry}/`.
These failures neither measure assisted throughput nor establish a mod
crash cause. A separately reliable client launch or controlled development
runtime is needed before drawing a 26.3 performance conclusion.

Forge's 26.3 serverbound custom payload limit is 32,767 bytes. A full
compressed density result in the first connected run was about 184 KB and
caused a decoder disconnect. Forge now sends results in bounded 24,000-byte
fragments, with bounded per-owner/server reassembly and expiry before normal
server result validation. Its login/disconnect notification uses two small
client Mixins at generated 26.3 targets, because the first native launch did
not deliver the Forge login event to the worker. The corrected native run
completed 18 results with no decoder disconnect. NeoForge uses its native
payload path without fragmentation.

`MinecraftServer.reloadResources` is intercepted at entry on all loaders to
cancel pending jobs before registry replacement; this target was checked in
the generated 26.3 Fabric, Forge and NeoForge sources. A runtime reload test
has not yet been counted.

The development-only settings-screen smoke fixture was made loader-neutral.
NeoForge's isolated client completed the full offline menu sequence with four
screenshots, persistence and the separate disclosure gate, then exited
normally (`test-artifacts/port26.3-neo-settings/`). The shared fixture and
native adapters compile on all three loaders. Forge's menu-only development
launch did not reach the title screen after resource loading in multiple
isolated attempts, including one with copied known-good options; the test
flag and an initial client tick were confirmed in a diagnostic run. A later
connected Forge client joined its loopback server, but the automated menu
sequence still produced no result; no Forge UI pass is counted. An
installed-artifact or manually observed UI check remains. Two shared 26.3 permission/revision JUnit
classes were selected from the older suite and passed; Forge's fragment
assembler JUnit checks also cover owner-specific partial cleanup.

## Verified dependencies (2026-09-24)

- Minecraft Java Edition 26.3 was released on 2026-09-15.
- Fabric recommends Loom 1.17, Gradle 9.6.0 and Loader 0.19.5 for 26.3;
  Fabric API `0.161.0+26.3` is present in official Maven metadata.
- Forge publishes 26.3 build `66.0.3` (2026-09-22).
- NeoForge official Maven metadata now lists 26.3 build `26.3.0.13-beta`.
  The official 26.3 MDK template still pinned `26.3.0.10-beta` when copied;
  an isolated MDK build with `26.3.0.13-beta` and JDK 25.0.4 passed on
  2026-09-24. Its patched generated game source was inspected for the two
  `ServerChunkCache` waits, `ChunkStatusTasks.buildTerrain`, and
  `NoiseBasedChunkGenerator.doFill`/`sampleVolume`; relevant call shapes remain,
  This initial template check preceded the native WorldgenAssist build and
  Mixin launch recorded above.

The official Forge `26.3-66.0.3` MDK archive was downloaded for inspection;
its SHA-1 `a9446ea6c6ebf1e0cce86c6f65b642e11fa954c7` matches the Forge
download page. Its build uses ForgeGradle `[7.0.17,8)` and Java 25. The
official NeoForge 26.3 ModDevGradle template uses plugin `2.0.147`, Java 25
and originally pinned `26.3.0.10-beta`. Inspection copies are under ignored
`.gradle/loader-inspect/`; these are upstream templates, not the native
WorldgenAssist loader projects now under `loaders/`.

The isolated official Forge MDK also built successfully with JDK 25.0.4 and
Forge `26.3-66.0.3` on 2026-09-24. Its injected source JAR is in the
ForgeGradle Mavenizer cache. These initial Forge and NeoForge template builds
compiled only the upstream example mod. Both
loaders' patched 26.3 source retains `ChunkStatusTasks.buildTerrain`,
`NoiseBasedChunkGenerator.doFill`'s
`DensitySampler.Bound.sampleVolume(DensityVolume)` returning
`ScopedDensityBuffer`, and the two `ServerChunkCache` managed waits. Native
Mixin application and remote execution were subsequently checked above.

The loader network APIs differ materially: Forge `ChannelBuilder` builds a
`PayloadChannel` with a play protocol and per-payload handlers, while NeoForge
registers play payloads through `RegisterPayloadHandlersEvent` and
`PayloadRegistrar`. The existing Fabric `PayloadTypeRegistry` and
`ServerPlayNetworking` calls cannot be reused directly. The common payload
records and codecs are candidates for sharing; registration, send/receive,
handshake capability checks and lifecycle callbacks need loader adapters.

## Generated 26.3 source findings

`genSources` succeeded on JDK 25.0.4 with Gradle 9.6.0 and Loom 1.17.20.
The generated common source JAR is under
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.3/`;
the inspection copy is `.gradle/source-inspect-26.3/` (ignored).

- `DensityFunction` and `DensityFunctions` moved into
  `net.minecraft.world.level.levelgen.densityfunction`. Their old visitor,
  function-context and noise-holder interfaces are gone.
- `NoiseSettings` now stores only `minY` and `height`; interpolation-cell
  width/height are no longer part of the 26.3 shape.
- `NoiseBasedChunkGenerator.buildTerrain` creates a scoped `NoiseChunk` and
  `DensityVolume(16, height, 16, chunkMinX, minY, chunkMinZ)`. `doFill`
  obtains a `DensitySampler.Bound` for `finalDensity`, calls
  `sampleVolume(volume)` and reads **float** values through
  `volume.indexUnchecked(x,y,z)`. The server still performs aquifer and block
  writes. `NoiseChunk` no longer contains the former interpolation methods or
  lives in `ChunkAccess` as a reusable cached field.
- The old `NoiseChunkRemoteDensityMixin` redirects a removed
  `DensityFunction.fillArray` call; its target cannot be reused. The old
  result format is cell-indexed `double[]`, while 26.3's source format is a
  block-indexed `float` buffer. A new shape, codec, context fingerprint,
  independent validation and bounded injection are required. The general
  26.2 protocol `CURRENT=2` must not be accepted as 26.3 work. The working
  trusted-raw protocol is therefore `CURRENT=3`, separate from the disabled
  26.2 public-fixture packet family.
- The 26.2 public-seed transcript fixture also depends on removed
  interpolation and router APIs. Keep it unavailable on 26.3 until its own
  recorder/replayer and disclosure limits are reverified.

The first compile against 26.3 failed with 72 migration errors, principally
old interpolation/visitor APIs and `ChunkAccess.getOrCreateNoiseChunk`.
The Fabric candidate now passes `compileJava`, `compileClientJava`, a
`build -x test`, and two focused 26.3 JUnit classes. A disposable dedicated
Fabric server generated 25 spawn NOISE chunks with zero generation failures
and shut down normally. This smoke did not enable remote assistance.
The first 26.3 settings-screen smoke timed out at step 0 because a fresh
client displays `AccessibilityOnboardingScreen` before `TitleScreen`. The
fixture now closes that onboarding screen through its public API. Its final
isolated run at `test-artifacts/port26.3-settings-smoke-onboarding/` records
`success:true`, four screenshots, persistence and the separate disclosure
gate, with a natural exit. Offline user-property/Realms authentication errors
remain in the development client's log; this is not a claim of error-free logs.

The candidate replaces the 26.2 interpolation cells with a full-block
`DensityVolume` and binds a validated float-exact buffer to the specific
chunk during `doFill`. Its 26.3 trusted-raw general protocol is version 3.
One chunk per vanilla dimension matched the game's caching sampler at every
volume index. Independent vertical-column `sampleVolume` matched every
index too. A direct `sampleValue` differed by one float bit at some positions;
the optional server validator was changed to sample selected columns as
volumes. A focused test also passes the full result through compression,
decode and the exact-float apply buffer, then rejects a changed sampled
value. These unit tests are not a
networked assisted/vanilla world comparison.

On 2026-09-24, the installed-client runner was ported to an isolated 26.3
Fabric server child under the authorized remote test root. The first attempt
was rejected by the fixture server's inherited whitelist; the runner now
sets `white-list=false` while binding to `127.0.0.1:25585` only. The next
connected attempt exposed a real 26.3 deadlock: the client computed and
encoded a 98,304-density result, but a synchronous server chunk wait delayed
result handling until the 30-second fallback. Generated 26.3
`ServerChunkCache.getChunk`/`getChunkFuture` retain two
`MainThreadExecutor.managedBlock` calls. The new
`ServerChunkCacheRemoteWaitMixin263` suspends remote admission and cancels
pending jobs to vanilla fallback during these waits.

After that fix, one Overworld installed-client assisted case and its
same-seed vanilla partner succeeded on JDK 25.0.4, using the exact WIP JAR
SHA-256 `FD83DDADD74DBC1A246E9D7F069FDA615D2FB56F2ABBD11C6C35742D2291E621`.
The required remotely applied chunk matched vanilla. All 962 shared NOISE
digests matched; the assisted and vanilla cases recorded 1,122 and 962
digests respectively. Both cases report `cleanup_safe=true`, and a final
remote read found zero owned Java processes and zero port 25585 listeners.
Evidence is under `test-artifacts/port26.3-fabric-assisted-5/`,
`test-artifacts/port26.3-fabric-vanilla-1/`, and
`test-artifacts/port26.3-fabric-overworld-comparison/`. The comparator's
**pair** status is PASS; overall status is INCOMPLETE because this deliberately
selected pair has no full-matrix plan. Offline client authentication/Realms
errors remain; do not describe the logs as error-free. No 26.3 performance
claim follows from this correctness pair.

The first two selected Nether attempts never reached the dimension-change
path: the installed Windows client exited during resource loading with native
exit codes `0xC0000005` and `0xC0000374`. Both cases recorded
`cleanup_safe=true`; neither is a Nether validation result. The client
launcher now sets `-XX:ErrorFile` inside its isolated evidence profile, though
the second failure produced no JVM error file. Investigate that startup fault
or use a separately verified client environment before counting a Nether pair.

The old 26.2 JUnit sources and public-seed transcript fixture remain on the
published maintenance line. This branch compiles its new targeted tests from
`src/portTest/java` and omits the obsolete fixture classes from the Fabric
artifact. Enabling either old public-fixture flag now fails initialization.
The Fabric installed-client batch matrix has been ported to 26.3. Its default
plan selects ten principal paired scenarios; `-CaseId` narrows to one affected
pair and `-FullMatrix` opts into all 60 combinations. It writes one final
summary after the selected work ends. The unavailable 26.2 public-seed
transcript regression remains an explicit `SKIPPED` row. A selected one-owner
Overworld correctness pair completed through this batch on the rebuilt Fabric
JAR SHA-256 `0607E86B096CF92A0588BF2E57AA5D2789D2B5663B0F0C232F3B7D48504CBAB9`:
941 shared digests and one required remote-applied chunk matched, with
`COMPLETE / PASS`, zero issues and safe cleanup. Evidence is under
`test-artifacts/validation-matrix-20260925-000313-104/`.
The batch's isolated Fabric settings-menu entry also passed on 26.3 with
four screenshots, persistence, separate seed-disclosure gate and safe cleanup
(`test-artifacts/port26.3-settings-batch-entry/`).

The selected performance pair at
`test-artifacts/validation-matrix-20260925-000527-927/` completed three
assisted measured repeats, but its vanilla client exited during resource
loading with native Windows code `0xC0000005`. One isolated vanilla-only
retry at `test-artifacts/port26.3-performance-vanilla-retry-batch/` failed
the same way. Cleanup was safe in all three scenarios. The batch correctly
reported failed/incomplete comparison, so no paired 26.3 performance ratio
or speed claim is available from these runs.

Official installer profiles were prepared only under `test-artifacts/` for
Forge `26.3-66.0.3` and NeoForge `26.3.0.13-beta`. The isolated launcher helper
`scripts/New-InstalledNativeLoaderClient.ps1` verifies the installed profile,
JDK 25.0.4 client libraries and assets, copies the exact loader-specific JAR,
and records classpath and artifact hashes. It does not alter a user launcher
profile. Both installed dedicated servers booted with assistance disabled and
generated at least 25 spawn NOISE chunks without generation failures.

Installed-JAR assisted/vanilla Overworld pairs then passed with the current
Forge and NeoForge candidate JARs. NeoForge matched all 1,628 shared NOISE
digests and all 43 remotely applied chunks, with zero comparison issues;
Forge matched all 816 shared digests and all 18 remotely applied chunks.
The exact logs and comparator JSON are under
`test-artifacts/port26.3-{neo,forge}-installed/`. Server stop completed
normally in both pairs. NeoForge clients were terminated after capture;
the Forge assisted client exited after disconnect and the vanilla client
closed normally. Offline authentication/Realms warnings are present, and
these pairs do not measure throughput or verify native simultaneous owners.

A focused installed Forge two-owner session then passed with the same
candidate JAR SHA-256 `B9D3159F8D26BE7D8EDBF3F126E447BE6099CD64C85995CE0F622F9C937E4937`.
Both distinct owners registered and each had one remotely applied NOISE chunk
while both clients remained connected. The server recorded 857 NOISE digests,
zero Mixin/runtime exception matches, and stopped normally; both clients
exited with code zero. The machine-readable result reports `success=true`,
`cleanup_safe=true`, and loopback-only execution at
`test-artifacts/port26.3-forge-two-owner-console/`. The selected scenario
runner is `scripts/Run-InstalledNativeLoaderScenario.ps1`; it keeps each run
in a fresh evidence directory. This is a two-owner application check, not a
matched vanilla digest comparison or a measured throughput result.

NeoForge's initial installed two-owner attempts did not reach a comparable
result. The first two clients exited during resource loading with native
Windows code `0xC0000005` before joining; a later run joined the first owner,
then its second client exited with the same code before joining. All three
reported safe cleanup. Their evidence is under
`test-artifacts/port26.3-neo-two-owner-{assisted,assisted-retry,console}/`.
These attempts do not establish a NeoForge MOD crash cause or simultaneous
owner support. Forge's menu-only installed-client smoke remained on a
loading/connection screen; the connected smoke joined its loopback server but
still produced no settings result. Forge UI is therefore unverified. The
experimental smoke code was reverted and the candidate JAR hashes above were
restored.

A subsequent authorized two-PC NeoForge attempt used an isolated interactive
remote client, JDK 25.0.4, all 92 verified classpath entries, and a
SHA-256-checked transfer archive. The server and SSH reverse tunnel were
bound to `127.0.0.1:25585`. The first attempt lost the local client before
join; the second lost the remote client before join, again with Windows native
code `0xC0000005`. A focused control launch on that remote PC, with the MOD JAR
temporarily moved into the dedicated fixture's `retired-mods`, reproduced the
same native code during resource loading. The JAR was restored afterward.
This shows the remote crash also occurs without WorldgenAssist in that
environment. The isolated launchers were missing the official 26.3 JVM option
`-XX:StackShadowPages=32`. After that option was added, a local installed
NeoForge session registered two owners and remotely applied results for both,
with `success=true` and `cleanup_safe=true` under
`test-artifacts/port26.3-neo-two-owner-handshake-fix/`. Both clients exited
normally. A first corrected-launch attempt had also reached two registrations
but the test harness checked the first owner's handshake twice; that race was
fixed by waiting for each owner's offline UUID explicitly. The failed two-PC
and no-MOD evidence is under `test-artifacts/port26.3-neo-two-owner-remote{,-retry}/`, including
`remote-no-mod-result.json` and the copied client logs. The local and remote
fixture listeners, temporary task, and owned client processes were closed.

## Loader split now implemented

`WorldgenAssist` initializes loader-neutral computation and policy through
`WorldgenLoaderHooks`. Fabric, Forge and NeoForge supply separate payload,
configuration, lifecycle and client-menu bindings. The coordinator, bounded
codecs, density calculation, eligibility and chunk Mixins remain shared.
Forge alone fragments large serverbound results to respect its native packet
limit. The native projects have separate build metadata and output JARs;
their artifacts are not interchangeable. Initial dedicated-server/client
launches and selected Overworld digest comparisons passed as recorded above.
The selected exact installed-artifact Overworld comparisons are recorded above.

## Remaining verification boundaries

1. Check native overlapping in-flight jobs. Fabric's selected installed
   two-PC run passed; Forge and NeoForge local installed two-owner sessions
   applied each owner's results while both were connected. All three loaders have
   matched development-runtime results across all three vanilla dimensions.
   The two earlier Fabric installed-client Nether startups crashed before
   the dimension transition and do not count as installed-JAR evidence.
2. Broaden native cancel/disconnect, datapack reload and settings permission
   runtime checks. Focused coordinator/policy and Forge fragment-assembler
   tests cover these guards, but the Forge settings screen automation did not
   finish. The Forge menu path remains a verification gap in alpha.4.
3. The selected Fabric 26.3 performance pair completed on the corrected
   launcher with three measured repeats per route. Median throughput was
   69.47 tasks/s vanilla and 67.95 tasks/s assisted; this is one descriptive
   condition, not evidence of a speedup. See
   `test-artifacts/validation-matrix-20260926-170015-863/`.

Official references: [Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3),
[Fabric porting notes](https://fabricmc.net/2026/09/15/263.html),
[Forge 26.3 downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_26.3.html),
[NeoForge Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/).

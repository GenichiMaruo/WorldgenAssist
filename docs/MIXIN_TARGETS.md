# Mixin Targets

## 26.3 port finding (2026-09-23; Fabric candidate only)

Generated 26.3 `NoiseChunk` no longer owns interpolation cells or exposes
`advanceCellX`, `selectCellYZ`, or `DensityFunction.fillArray`. The published
26.2 `NoiseChunkRemoteDensityMixin` and its `NoiseChunkCacheAllInCellAccessor`
therefore have no valid 26.3 target. `NoiseBasedChunkGenerator.buildTerrain`
creates a scoped `NoiseChunk`; its private `doFill(NoiseChunk,ChunkAccess)` calls
`DensitySampler.Bound.sampleVolume(DensityVolume)` for `finalDensity` before
server-side aquifer/block writes. The Fabric candidate uses
`ChunkStatusTasks.buildTerrain` for admission, a `ChunkAccess`-scoped remote
field, and a `doFill` `sampleVolume` WrapOperation to substitute a bounded
float buffer. The field is cleared when generation completes. A default-off
Fabric dedicated-server smoke reached 25 spawn NOISE chunks without a Mixin
error. A 2026-09-24 installed-client Overworld assisted/vanilla pair now passed
one required applied-chunk digest and 962 shared NOISE digests. Nether, End,
multi-owner, Forge and NeoForge runtime remain unverified.

Generated 26.3 `ServerChunkCache.getChunk` and `getChunkFuture` still call
`MainThreadExecutor.managedBlock(BooleanSupplier)` at lines 163 and 216.
The new `ServerChunkCacheRemoteWaitMixin263` wraps both calls (`require = 2`)
and enters `RemoteWorldgenManager.duringSynchronousChunkWait`. Without this
hook, an installed client computed and encoded a 98,304-value result, but the
server remained in the synchronous wait until the 30-second watchdog fired.
The 26.3 Mixin cancels pending remote work into local fallback before that
wait, while asynchronous owner work can still complete remotely. The
26.2-only `ServerChunkCacheFixtureWaitMixin` remains excluded because the
public transcript fixture has not been ported.
See `PORT_26_3.md`.

## Alpha.3 source checks (2026-09-15, runtime pending)

The settings menu uses resolved Fabric screen-api 5.1.0 `ScreenEvents.AFTER_INIT`
and `Screens.getWidgets`, with generated 26.2 `OptionsScreen`, `Screen`,
`Button.builder` and `StringWidget`. Screen changes use `Minecraft.gui.setScreen`.
No GUI Mixin is needed. The generated `Screen.init(int,int)`/resize lifecycle
rebuilds widgets, and the settings screen uses the same lifecycle.

The generated `WorldGenContext` record supplies the authoritative `ServerLevel`
to the existing `ChunkStatusTasks.generateNoise` wrapper. Geometry findings for
Nether and End are recorded in `WORLDGEN_PIPELINE.md`; the injection descriptor
does not need to change for dimension support.

2026-09-13 disconnect-thread recheck adds no Mixin or descriptor. Generated
26.2 `Connection` and `ServerCommonPacketListenerImpl.disconnect` and Fabric
API 6.3.3 `ServerPlayNetworkAddon.invokeDisconnectEvent` show that Fabric's
disconnect notification can originate on a Netty NIO thread. Both route
managers therefore submit owner-map mutation, cancellation, and replacement
handling through `MinecraftServer.execute`; no worldgen or manager state is
mutated from the callback. The raw route ignores an old disconnect notification
when the UUID now belongs to a different connected player instance. A-D
`20260913-231321-924` passes with 246 tests / 52 suites and zero failures,
errors, or skips; its manifests have no difference. The disconnect runtime
`two-client-fixture-disconnect-20260913-231502-332` passes: its owner-disconnect
log runs on the server thread, owner B applies after owner A cancellation,
and both client JVMs exit naturally with code 0. Digest comparison is recorded
in `TEST_RESULTS_LATEST.md`.

2026-09-12 multiplayer work adds no Mixin or descriptor. Rechecked generated
26.2 `ChunkMap.getPlayerViewDistance` (line 813) and
`ChunkTrackingView.isInViewDistance(int,int,int,int,int)` (line 69): owner demand
uses the vanilla rounded view test without neighbor padding, rather than a
square. `ServerPlayer.isSpectator`, `isChangingDimension`, `chunkPosition`,
`requestedViewDistance` and `level` are sampled only on the server tick thread;
generation uses immutable `PlayerChunkDemand` values. `getGameProfile().name()`
is also verified in generated `PlayerList` for owner-name test diagnostics.
The existing synchronous-wait Mixin now cancels all active fixture attempts
through the shared orchestrator. Runtime verification must cover this change.

2026-09-10 installed-client verification adds no Mixin. Generated client
`Minecraft.runTick` / `stop` / `exitWorldAndClose` and `Window.shouldClose` were
read to interpret the owned-window-close test; the `Stopping!` marker and a
natural JVM exit 0 are required. No shutdown hook was injected into production.

2026-09-09 follow-up: no new Mixin or descriptor changes. Selected-leaf
interruption tests use a count-only observer inside `SeededLeafTrace`; the
verified NormalNoise/BlendedNoise targets remain unchanged. Player-count
suspension belongs to the fixture manager's server-tick lifecycle, not a Mixin.

## Public-fixture adapter update (2026-09-05)

Runtime follow-up: tick-drained fixture traffic cannot progress while
`ServerChunkCache` waits synchronously in its private `MainThreadExecutor`.
Generated `ServerChunkCache.java` lines 166/219 and `javap -c -p` confirm both
`getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;`
and `getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;`
invoke `ServerChunkCache$MainThreadExecutor.managedBlock(Ljava/util/function/BooleanSupplier;)V`.
A narrow WrapOperation now brackets those calls only when the future is not done:
suspend fixture admission/cancel its pending attempt, run the original wait, and
resume in finally. This avoids waiting for client/tick traffic during vanilla's
synchronous load. No Fabric event brackets these internal waits. Off/default mode
and already-complete futures call the original unchanged. Runtime must be rerun
after this new target; earlier compilation evidence does not verify this Mixin.
That rerun is now recorded in `TEST_RESULTS_LATEST.md`: the actual synchronous
fallback hook was logged, four subsequent remote installs succeeded, and all
four NOISE digests matched an independent local world. No Mixin apply failure
occurred in the assisted, local-only or wrong-seed runtime.

Before the synchronous-wait fix, no new Mixin descriptor was needed. The existing `RemoteWorldgenManager.generateNoiseOrFallback`
entry now checks the opt-in fixture adapter first. Reverified generated 26.2
`ChunkStatusTasks.generateNoise`: the captured supplier still invokes the original
`fillFromNoise` and retrogen continuation. `ChunkAccess.getOrCreateNoiseChunk` and
the existing generator invoker supply the same cached target used by vanilla.
Only `Util.backgroundExecutor().forName("seeded_leaf_apply")` installs/continues
the validated field; network handlers never mutate it. The existing geometry-first
install and job-ID cleanup contracts are unchanged. The fixture is constrained to
the fixed public seed, not a general activation of draft transport.

The following transport-free paragraph records the earlier baseline. Current
runtime evidence is in `TEST_RESULTS_LATEST.md`; do not infer runtime success from
JUnit alone.

The 2026-09-05 transport-free orchestration/continuation batch adds no Mixin
target or live chunk-pipeline invocation. Reinspection of generated
`NoiseSettings.java` confirmed its direct record constructor can bypass codec
guards, so recorder input now passes shared protocol geometry validation before
session/sampler creation. The existing `NoiseChunkRemoteDensityMixin` still
checks all target geometry before assigning its volatile installation field and
clears only the matching job ID. The new inactive continuation relies on that
ordering; no runtime behavior is inferred from its local tests. Integration
contracts are in `SEEDED_LEAF_ORCHESTRATION.md`.

## Rule

This file is a **verification ledger**.

Do not write a Mixin from AI memory, an old Yarn tutorial, or a 1.21.x source snippet.

All active targets must be verified against the current generated Minecraft 26.2 source.

---

## 1. Verification procedure

Run:

```powershell
.\gradlew.bat genSources
```

Then for every candidate target:

1. Open class definition in VS Code.
2. Find exact method.
3. Copy exact parameter types.
4. Copy exact return type.
5. Check overloads.
6. Check whether method is synthetic/private/static.
7. Inspect callers.
8. Inspect downstream completion behavior.
9. Record descriptor when necessary.
10. Only then implement the Mixin.

After adding a Mixin:

```powershell
.\gradlew.bat build
```

Then launch the actual dev runtime and inspect:

```text
run\logs\latest.log
```

Mixin failures are often runtime failures, not compile failures.

---

## 2. Candidate target: chunk status wiring

Status: `VERIFIED` — Minecraft 26.2 generated source, Loom 1.17.20 (2026-09-01)

Candidate class:

```text
ChunkStatus
```

Purpose:

Determine the exact 26.2 stage responsible for base-terrain/noise generation and the function that invokes the generator.

Questions:

```text
What is the exact status name?
What earlier statuses does it depend on?
What method/function is called?
Which executor/thread handles its future?
How is completion passed to the next stage?
```

Verified findings:

```text
Status sequence:
  EMPTY -> STRUCTURE_STARTS -> STRUCTURE_REFERENCES -> BIOMES -> NOISE
  -> SURFACE -> CARVERS -> FEATURES -> INITIALIZE_LIGHT -> LIGHT -> SPAWN -> FULL

Wiring class:
  net.minecraft.world.level.chunk.status.ChunkPyramid

Relevant step:
  ChunkStatus.NOISE

Task:
  ChunkStatusTasks::generateNoise

Direct requirements:
  STRUCTURE_STARTS radius 8
  BIOMES radius 1

Block-state write radius:
  0

Task method:
  public static CompletableFuture<ChunkAccess> generateNoise(
      WorldGenContext,
      ChunkStep,
      StaticCache2D<GenerationChunkHolder>,
      ChunkAccess)

Task descriptor:
  (Lnet/minecraft/world/level/chunk/status/WorldGenContext;
   Lnet/minecraft/world/level/chunk/status/ChunkStep;
   Lnet/minecraft/util/StaticCache2D;
   Lnet/minecraft/world/level/chunk/ChunkAccess;)
  Ljava/util/concurrent/CompletableFuture;
```

`generateNoise` creates a `WorldGenRegion`, constructs a `Blender` for that
region, obtains the level's `RandomState` and a region-scoped
`StructureManager`, and invokes `ChunkGenerator.fillFromNoise`. Its synchronous
`thenApply` performs below-zero retrogen bedrock replacement/masking when
needed.

`ChunkStep.apply` waits on the task future with `thenApply`, then advances the
persisted chunk status. The noise task therefore completes before the status is
marked `NOISE` and before the next step may consume it.

### Active Phase 0 instrumentation

Status: `IMPLEMENTED AND TEST-LOADED`

Fabric exposes no event that brackets this internal stage's asynchronous
future, so a minimal Mixin is necessary. `ChunkStatusTasksMixin` uses
MixinExtras 0.5.4 `@WrapMethod` on the exact `generateNoise` descriptor above.
The wrapper:

1. records the start time and caller thread,
2. calls the chained original operation exactly once with unchanged arguments,
3. attaches a completion observer to the returned future,
4. returns that same future instance unchanged in normal metrics mode.

It neither mutates the chunk nor replaces the vanilla future in normal metrics
mode. Measuring the whole task also includes below-zero retrogen work and
failures in the task's continuation, rather than only the inner
`fillFromNoise` future.

When the explicit deterministic-digest flag is enabled, the wrapper returns a
derived future that first records the vanilla stage timing and then calculates
the canonical digest. This intentionally holds the stage boundary until the
read-only digest finishes, preventing `SURFACE` or another downstream stage
from racing the snapshot. Digest mode is test instrumentation and must remain
disabled during performance benchmarks.

---

## 3. Candidate target: Overworld noise generator

Status: `VERIFIED` — Minecraft 26.2 generated source, Loom 1.17.20 (2026-09-01)

Candidate class:

```text
NoiseBasedChunkGenerator
```

Reason:

Likely central implementation for base terrain.

Do not assume the old method name `fillFromNoise` is still the exact 26.2 target until source confirms it.

Record:

```text
Exact class FQCN:
Exact method:
Descriptor:
Return type:
Visibility:
Overloads:
Call sites:
Mutation performed:
```

Verified findings:

```text
Exact class FQCN:
  net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

Superclass:
  net.minecraft.world.level.chunk.ChunkGenerator

Exact method:
  public CompletableFuture<ChunkAccess> fillFromNoise(
      Blender,
      RandomState,
      StructureManager,
      ChunkAccess)

Descriptor:
  (Lnet/minecraft/world/level/levelgen/blending/Blender;
   Lnet/minecraft/world/level/levelgen/RandomState;
   Lnet/minecraft/world/level/StructureManager;
   Lnet/minecraft/world/level/chunk/ChunkAccess;)
  Ljava/util/concurrent/CompletableFuture;

Overloads:
  none

Call site used by generation:
  ChunkStatusTasks.generateNoise

Execution:
  CompletableFuture.supplyAsync(...,
      Util.backgroundExecutor().forName("wgen_fill_noise"))
```

The asynchronous body acquires every affected `LevelChunkSection`, calls the
private `doFill` method, and releases all acquired sections in `finally`.
`doFill` mutates the chunk's section block states, `OCEAN_FLOOR_WG` and
`WORLD_SURFACE_WG` heightmaps, and post-processing positions for selected
fluids. Completion and non-async dependent stages normally run on the
`wgen_fill_noise` worker that completes the future (or inline if already
complete).

### Active Phase 1 executor backends

Status: `IMPLEMENTED, TEST-LOADED, AND RUNTIME-VERIFIED`

Generated source line 359 invokes this exact operation inside
`fillFromNoise`; matching bytecode was verified with `javap`:

```text
java.util.concurrent.CompletableFuture.supplyAsync(
    java.util.function.Supplier,
    java.util.concurrent.Executor)

Descriptor:
  (Ljava/util/function/Supplier;
   Ljava/util/concurrent/Executor;)
  Ljava/util/concurrent/CompletableFuture;

Vanilla executor argument:
  Util.backgroundExecutor().forName("wgen_fill_noise")
```

`NoiseBasedChunkGeneratorMixin` uses MixinExtras `@WrapOperation` on that
single invocation. Vanilla mode calls the chained original operation with both
arguments unchanged when benchmark timing is disabled. Opt-in `delegate` mode
crosses the project backend abstraction and calls it with the exact captured
vanilla executor object. It does not wrap that executor, resubmit the task, or
construct another future. Opt-in `local` mode instead calls it once with the
unchanged vanilla supplier and a `LocalWorldgenTaskBackend` executor. All paths
preserve the original future construction and compatibility with other chained
operation wrappers.

An independent benchmark flag may wrap the supplier immediately before that
same call. The wrapper records scheduling, start, and completion timestamps
using `System.nanoTime()`, calls the vanilla supplier exactly once, and
records current worker-thread CPU time through `ThreadMXBean` when supported.
It calls the vanilla supplier exactly once and rethrows the original unchecked
failure. It does not run during normal or digest-only operation; when disabled,
the exact original supplier reference is passed through. Its task compute
interval covers section acquisition, `doFill`, and section release. The outer
`generateNoise` stage metric also includes future completion/continuation
overhead and below-zero retrogen.

Task logs also record the actual execution route independently of the requested
backend: `vanilla`, `vanilla_delegate`, `local_pool`, or `vanilla_fallback`.
The route is scoped to the executing command and restored afterward, so local-
mode fallbacks are not misclassified as project-pool work.

The backend uses a project-owned asynchronous-mode `ForkJoinPool` whose workers
are named `CAWG-LocalWorldgen-*`. A non-blocking semaphore bounds admission to
`workers * (1 + queued-tasks-per-worker)` commands. The waiting ratio defaults
to one and accepts `0..16`, so the default waiting capacity equals the worker
count. If admission is full, or the local pool rejects the command before it
begins, the executor adapter submits the same command to the captured vanilla
executor. It does not attempt a second call to `supplyAsync` and cannot leave a
partially applied local task before fallback. Warnings are limited to the first
and every 64th fallback; exact counters are preserved.

The captured supplier still owns live `Blender`, `RandomState`,
`StructureManager`, and `ChunkAccess` references. This is a local-only
execution substrate, not the immutable context-extraction boundary required
for Phase 2.

`Util.makeExecutor("Main")` was also verified in the generated source. On this
20-logical-processor host, vanilla used 19 workers in a `ForkJoinPool` created
with asynchronous mode enabled. `TracingExecutor.forName("wgen_fill_noise")`
delegates into that shared pool. The revised Phase 1 local backend also uses
asynchronous fork/join scheduling, but in a dedicated pool. Its workers consume
the same host CPUs while upstream work continues on vanilla's shared pool, so
equal worker counts still do not imply equivalent scheduling or resource use.

Runtime verification used fresh Overworlds with seed `8675309`. The initial
one-worker fixture completed all 331 observed noise tasks on
`CAWG-LocalWorldgen-1` and reopened its saved world successfully. After the
scheduler revision, a fresh 19-worker run again completed all 331 tasks on
`CAWG-LocalWorldgen-*`, with zero stage failures and zero executor fallbacks.
Canonical format-1 digests matched the independent vanilla baseline for all 331
coordinates in both local fixtures.

A fresh `delegate` fixture also matched the vanilla baseline at all 331
coordinates and digests, with zero failures or mode-only coordinates. Unit and
runtime evidence agree that its scheduler is
`minecraft_shared_fork_join_async` and its selected executor has object identity
equal to the captured vanilla executor.

The opt-in server-tick benchmark deliberately adds no Worldgen Assist Mixin.
Inspection of Fabric API 0.156.0+26.2's local source verified its
`MinecraftServerMixin`: `START_SERVER_TICK` is injected immediately before
`MinecraftServer.tickChildren(BooleanSupplier)`, and `END_SERVER_TICK` is
injected at the tail of `tickServer`. The logger registers those public events
and combines their interval with NOISE counters and local-backend saturation.
Consequently this instrumentation does not add another Minecraft method target
to the project ledger.

The repeated tick/CPU fixture used three fresh 648-task pairs and did not alter
either verified project Mixin target. All 3,888 measured tasks completed with
zero failures/fallbacks. Local stage latency averaged 50.83 ms versus 23.69 ms
for vanilla, dominated by queue wait, and conditional tick-health summaries
were also worse. The target remains technically valid, but the current
dedicated-pool policy fails Gate 2's performance/tick criterion.

The later worker-proportional admission redesign likewise changed no Mixin
target or descriptor. Its final routed 19-worker/ratio-1 screen capped the
interval peak at 38 admitted commands and completed 648 tasks with zero
failures, but 62 tasks used the vanilla fallback route. Aggregate stage latency
was 40.66 ms versus 22.93 ms for vanilla. The safer admission bound is runtime
verified; the executor policy still fails Gate 2.

The exact-executor delegate baseline likewise changed no target or descriptor.
Three fresh order-balanced vanilla/delegate pairs completed 1,944 tasks per
mode without failure; every delegate task recorded
`execution=vanilla_delegate`. Queue wait, workload span, throughput, and
NOISE-active/over-budget tick counts were equivalent at the fixture's
resolution, and the three per-run stage deltas changed sign. Gate 2 therefore
passes for the backend-selection seam while remaining failed for the dedicated
local-pool policy.

---

## 4. Candidate target: `NoiseChunk`

Status: `VERIFIED` — Minecraft 26.2 generated source, Loom 1.17.20 (2026-09-01)

Candidate class:

```text
NoiseChunk
```

Purpose:

Understand whether remote calculation can reuse vanilla's internal density/noise machinery without reimplementing it.

Record:

```text
Factory/constructor:
Required ChunkAccess:
RandomState use:
Blender use:
Structure/terrain adjustment use:
Aquifer use:
Internal caches:
Thread-safety notes:
```

Verified findings:

```text
Factory:
  NoiseChunk.forChunk(
      ChunkAccess,
      RandomState,
      DensityFunctions.BeardifierOrMarker,
      NoiseGeneratorSettings,
      Aquifer.FluidPicker,
      Blender)

Owner/cache:
  ChunkAccess.getOrCreateNoiseChunk(...)

Important mutable state:
  interpolation position/counters
  density-function wrappers and caches
  preliminary-surface cache
  aquifer caches and fluid-update flag
```

`NoiseBasedChunkGenerator.createNoiseChunk` supplies a structure-derived
`Beardifier`, generator settings, the generator's global fluid picker, the
level `RandomState`, and the current `Blender`.

`NoiseChunk` is stateful for one chunk calculation. Its interpolation and
aquifer state is mutated throughout `doFill`; it must not be shared between
independent jobs or assumed thread-safe. The `NoiseChunk` itself computes
intermediate values, while `NoiseBasedChunkGenerator.doFill` performs the
authoritative chunk mutation.

Security-relevant finding: when ore veins are enabled, the material rule list
inside `NoiseChunk` contains `OreVeinifier`. It can emit copper ore, raw copper
blocks, deepslate iron ore, and raw iron blocks during the `NOISE` stage. A
future remote implementation must not accept the full stage's block states
from an untrusted client without separately protecting this output.

---

## 5. Candidate target: context extraction

Status: `IMPLEMENTED, TEST-LOADED, AND RUNTIME-VERIFIED` — trusted-client
interpolated-density PoC with Phase 3 transport/cache (2026-09-03)

Goal:

Find the smallest safe point where server state can be converted into an immutable `TerrainComputeJob`.

Desired properties:

- no live mutable chunk crosses network boundary
- no main-thread-only object is used by client worker
- enough data exists for deterministic computation
- extraction is cheaper than the work being offloaded

Candidate source areas:

```text
chunk generation task
chunk generator
random/noise state
structure manager
blender
```

Verified findings:

The immediate call boundary consumes `Blender`, `RandomState`, a region-scoped
`StructureManager`, and the live mutable `ChunkAccess`. `RandomState` is built
from noise settings, registered noise parameters, and the seed. It owns a
`NoiseRouter`, climate sampler, surface system, positional random factories,
and concurrent caches for noise instances and named random factories.

These live objects are not a network DTO. `TerrainDensityJob` now carries only
the server-issued identity, seed, structure flag, keyed noise-settings ID, and
bounded cell/height geometry. For the fresh-world PoC, `Blender.isEmpty()` is a
required eligibility condition; non-empty blending forces local generation.

Further generated-source inspection confirmed that `NoiseChunk` maps the
seed-wired `RandomState.router()`, substitutes `Beardifier` at its marker,
constructs aquifer state from six router functions plus preliminary-surface
queries, and optionally appends `OreVeinifier`. `Beardifier.forStructuresInChunk`
returns its public `EMPTY` singleton when no relevant terrain-adjusting
structure contribution exists. `RemoteWorldgenEligibility` requires that exact
singleton, so structure-adjusted chunks remain local. It also rejects Nether,
End, retrogen, old-noise state, unkeyed settings, and invalid geometry.

Phase 2 adds immutable `TerrainDensityJob`/`TerrainDensityResult` values and
explicit bounded `StreamCodec` payloads around the existing identity metadata.
The coordinator binds IDs to owners, applies capacity/deadline/cancellation/
replay state, and captures no live Minecraft worldgen object in a packet.

Generated Minecraft 26.2 source also verifies the canonical context inputs:
`ChunkMap` creates `RandomState` from concrete generator settings, the `NOISE`
registry, and the level seed; `RegistryDataLoader` uses the direct codecs for
noise settings, density functions, and noise parameters. The implemented
format-1 factory hashes those settings, complete density/noise registries,
version/dimension/seed/height/configuration values, and relevant debug flags
with deterministic field and registry ordering. Its opt-in lifecycle logger
produced stable, distinct fingerprints for all three vanilla dimensions across
two server starts. The client repeats the same factory over
`VanillaRegistries.createLookup()`; unsupported custom datapack context fails
the equality check and returns to local generation.

### Active Phase 2/3 density-application hooks

The first networked PoC returns one finite `double` density for each block in a
16x16 Overworld column. It does not return block states. Generated source
inspection identifies the following minimal application hooks:

```text
ChunkAccess.noiseChunk
  protected @Nullable NoiseChunk
  read-only accessor used after ensuring the cached NoiseChunk exists

NoiseBasedChunkGenerator.createNoiseChunk(
    ChunkAccess, StructureManager, Blender, RandomState)
  private exact vanilla factory exposed by an @Invoker
  descriptor:
    (Lnet/minecraft/world/level/chunk/ChunkAccess;
     Lnet/minecraft/world/level/StructureManager;
     Lnet/minecraft/world/level/levelgen/blending/Blender;
     Lnet/minecraft/world/level/levelgen/RandomState;)
    Lnet/minecraft/world/level/levelgen/NoiseChunk;

NoiseChunk.advanceCellX(int)
  HEAD injection records the current vanilla cell-X index

NoiseChunk.selectCellYZ(int cellYIndex, int cellZIndex)
  redirects the single DensityFunction.fillArray(double[], ContextProvider)
  invocation used to populate each CacheAllInCell

NoiseChunk$CacheAllInCell.noiseFiller
  read-only accessor identifies which fill belongs to fullNoiseDensity
```

Only the `noiseFiller` owned by `NoiseChunk.fullNoiseDensity` is replaced. The
remote field is copied into the exact vanilla `CacheAllInCell` order (Y
descending, then X, then Z). Other caches remain local, so aquifer barrier/
floodedness/spread/lava functions and ore-vein functions still execute on the
server. `NoiseBasedChunkGenerator.doFill` remains responsible for block writes,
heightmaps, and fluid post-processing.

The server calls `ChunkAccess.getOrCreateNoiseChunk` before submission. The
verified private-factory invoker is necessary because a persisted partial chunk
can reach NOISE without an in-memory `NoiseChunk`, even though fresh BIOMES work
normally creates one. The field is installed only when cached geometry matches
the server-issued job and is cleared when the vanilla fill future completes.
With no installed field the redirect calls the original density function
unchanged.

Fabric JUnit loaded all four Phase 2/3 accessor/invoker/application targets. A
real dedicated server/client run then applied multiple 98,304-sample results
without a Mixin error. Seven remotely applied chunks matched independent
local-only canonical NOISE-stage digests exactly (`7/7`).

Phase 3 adds no Minecraft target or redirect. Compression happens after the
client-owned `NoiseChunk` has produced the canonical array, and decompression/
cache lookup completes before `RemoteDensityTarget.install` reaches these same
application hooks. The LRU stores only defensively copied density arrays, not a
Minecraft `NoiseChunk` or any live server object. A second compressed runtime
comparison matched the independent local baseline for all seven overlapping
remote-completed coordinates (`7/7`), confirming the transport/cache layer did
not change the verified Mixin boundary.

Phase 4 likewise adds no Minecraft target or redirect. Prediction uses Fabric
server-tick and connection lifecycle events plus public Minecraft 26.2 APIs:
`ServerPlayer.chunkPosition()`, `ServerPlayer.requestedViewDistance()`,
`PlayerList.getViewDistance()`, `WorldBorder.isWithinBounds(ChunkPos)`, and
`ServerChunkCache.hasChunk(...)`. Generated source confirms effective player
view distance is `clamp(requestedViewDistance, 2, serverViewDistance)`. The
predictor never creates or advances a chunk; completed values enter the same
Phase 3 cache and the same previously verified density-installation redirect.
Three actually consumed predicted fields matched independent local canonical
NOISE digests (`3/3`).

Phase 5 adds no Mixin target. Generated Minecraft 26.2 `NoiseChunk` source was
rechecked before implementation. The validator constructs a separate
server-owned instance through its public constructor and uses the public
interpolation sequence `initializeForFirstCellX`, `advanceCellX`,
`selectCellYZ`, `updateForY`, `updateForX`, `updateForZ`, `swapSlices`, and
`stopInterpolation`; its protected `getInterpolatedDensity` is exposed only by
a project-owned subclass. The live chunk's cached `NoiseChunk` is not mutated
by validation. Honest and deliberately corrupted client runtime fixtures both
reached the existing application/fallback boundary without a new Mixin error.

---

## 6. Candidate target: result application

Status: `IMPLEMENTED AND RUNTIME-VERIFIED` — density-only substitution keeps
all authoritative mutations in vanilla server code (2026-09-02)

Goal:

Identify where a returned intermediate result can be applied while preserving all downstream vanilla assumptions.

Questions:

```text
Which block states are expected to exist?
Which heightmaps must be updated?
Which post-processing lists must be populated?
Are light-source markers needed?
Does the stage lock chunk sections?
Which thread must perform application?
```

Verified findings:

`NoiseBasedChunkGenerator.doFill` currently performs all of the following
while the relevant sections are acquired:

```text
LevelChunkSection.setBlockState(..., false)
Heightmap.Types.OCEAN_FLOOR_WG update
Heightmap.Types.WORLD_SURFACE_WG update
ChunkAccess.markPosForPostProcessing(...) for selected fluids
```

It also reuses the resulting `NoiseChunk` in later surface and carver work via
`ChunkAccess.getOrCreateNoiseChunk`. Phase 2 therefore preserves that exact
object and supplies only its `fullNoiseDensity` cache cells. The original
`fillFromNoise` future still acquires/releases sections and performs all writes;
the remote handler never writes a block directly. The field is cleared at
future completion, leaving the normal downstream `NoiseChunk` lifecycle intact.

---

## 7. Preferred Mixin shape

Keep hooks small.

Preferred:

```text
@Inject / WrapMethod / WrapOperation / Redirect
     |
     v
Project-owned service
```

Avoid:

```text
Huge copied vanilla method inside Mixin
```

A Mixin should ideally:

1. extract inputs
2. delegate to `RemoteWorldgenManager`
3. return/bridge a future
4. preserve vanilla fallback

---

### Seed-confidentiality recorder/replayer hooks

Status: `IMPLEMENTED, TEST-LOADED, AND SERVER-LOADED` — Minecraft 26.2
generated source, Loom 1.17.20 (2026-09-03)

Fabric and vanilla expose no event around individual seeded density leaves.
Most concrete density-function records are also non-public, while copying the
complete graph evaluator would duplicate a large vanilla implementation. Two
minimal research hooks are therefore used:

```text
net.minecraft.world.level.levelgen.synth.NormalNoise
public double getValue(double x, double y, double z)
descriptor: (DDD)D

net.minecraft.world.level.levelgen.synth.BlendedNoise
public double compute(DensityFunction.FunctionContext context)
descriptor:
  (Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D
```

`NormalNoiseSeedTraceMixin` and `BlendedNoiseSeedTraceMixin` inspect only a
project-owned `ThreadLocal` session. With no session, they do not cancel or
alter vanilla return values. Recording captures the registered leaf ID, exact
input bits, and exact output bits at return. Replay validates kind, ID, call
order, and inputs at method entry before supplying the recorded value.

The trace implementation is bounded, thread-confined, rejects nesting and
unregistered/unwired leaves, and requires complete consumption. Start/close
logs expose only bounds and entry counts. It does not log a seed or any leaf
values. Its project-owned transcript and unregistered job draft now have
bounded binary codecs. The server-only HMAC job binding and authorization
envelope are also project-owned; these add no Minecraft hook and no Fabric
payload is registered.

The owner-bound seeded-leaf authority uses Fabric server lifecycle, connection,
and end-tick events for context/key rotation, cancellation, and expiry. It does
not introduce or alter a Minecraft Mixin target. Logs contain generation and
counts only, never context IDs, tags, keys, transcript values, or a seed.
Its world-global disclosure ledger resolves the path from source-verified
`MinecraftServer.getWorldPath(LevelResource.DATA)` and stores counters only.
Reload/disconnect admission epochs and bounded off-thread result validation are
project-owned concurrency code and add no injection.

The seed-free result DTO, bounded RAW/DEFLATE codec, and one-shot server result
gate are also project-owned code. They add no Minecraft Mixin and remain
unregistered with Fabric. Its transcript-free geometry/value projection can be
checked by the existing separate `NoiseChunk` validator, whose seeded-leaf
overload adds no target or injection. No live seeded-leaf path invokes the
validator or installation Mixin yet. `SeededLeafJobSpecRecorder` and the client
computer reuse the same public `NoiseChunk` constructor/traversal methods already
verified for the protocol-v2 sampler. `RemoteDensityField` now carries generic
seed-free geometry so a `VALIDATED` seeded-leaf result can eventually reuse the
existing `NoiseChunkRemoteDensityMixin`; the target method and descriptor are
unchanged. The original hook/descriptor has prior runtime evidence, but the
generic field refactor itself awaits the delegated compile, regression suite,
and single-PC runtime smoke described in `TEST_RESULTS_LATEST.md`.

Fabric JUnit proves that a trace recorded from seed `8675309` reproduces all
interpolated density bits with an unrelated dummy seed for both a 16-block
slice and a standard 384-block negative-coordinate chunk. A real remote-disabled
dedicated server generated its 25 spawn NOISE chunks and shut down cleanly with
no Mixin error.

---

## 8. Fallback design constraint

Do not design an injection that destroys access to the original behavior before the remote result is known to be usable.

The fallback must be able to execute the equivalent vanilla path.

Potential patterns should be evaluated after seeing the exact 26.2 call graph.

---

## 9. Runtime verification checklist

For every new worldgen Mixin:

- [ ] clean build
- [ ] client/server starts
- [ ] no Mixin apply error
- [ ] hook log appears
- [ ] correct chunk coordinate logged
- [ ] expected thread name logged
- [ ] vanilla path still works when remote disabled
- [ ] forced timeout uses fallback
- [ ] test world can be reopened
- [ ] no obvious chunk corruption

Phase 1 local-backend run on 2026-09-01:

- [x] clean build
- [x] dedicated server starts
- [x] no Mixin apply error
- [x] local backend enable log appears
- [x] correct chunk coordinates logged
- [x] completion threads are project-owned `CAWG-LocalWorldgen-*` workers
- [x] vanilla mode remains the default
- [x] executor rejection unit test uses vanilla fallback
- [x] generated local test world reopened after shutdown
- [x] 331/331 NOISE-stage canonical digests match vanilla
- [x] benchmark wrapper emits matched queue/compute/CPU records only when enabled
- [x] three 648-task vanilla/local benchmark pairs complete without failures
- [x] revised async ForkJoin scheduler also matches 331/331 vanilla digests
- [x] tick-health logging uses verified Fabric events without a new project Mixin
- [x] one-pair tick smoke captures 19 active / 61 admitted local interval peaks
- [x] three-pair tick/CPU fixture completes 3,888 tasks without failures
- [x] worker-proportional admission caps the 19-worker default-ratio peak at 38
- [x] benchmark records distinguish 586 local-pool and 62 vanilla-fallback tasks
- [x] fallback warnings are rate-limited while exact counters remain available
- [x] current dedicated-pool policy is recorded as failing Gate 2 performance/tick
- [x] delegate backend returns the exact captured vanilla executor object
- [x] delegate runtime route is `vanilla_delegate` on the vanilla scheduler
- [x] delegate mode matches 331/331 vanilla canonical digests
- [x] three 648-task vanilla/delegate pairs complete without failures
- [x] backend-selection seam passes Gate 2; dedicated local pool remains rejected

Phase 2 trusted-client run on 2026-09-02:

- [x] clean build and Fabric JUnit Mixin loading
- [x] dedicated server and real client start without Mixin application errors
- [x] worker handshake, request, 98,304-sample result, apply, and completion logs
- [x] heavy client computation runs on `CAWG-RemoteWorldgen-1`
- [x] remote-disabled server uses the vanilla/local path
- [x] forced 500 ms watchdog timeout completes local fallback
- [x] late result is rejected as `EXPIRED`
- [x] client remains connected and completes later remote work after fallback
- [x] generated worlds save and reopen cleanly
- [x] seven remote-applied NOISE digests match independent local results (`7/7`)
- [x] server retains aquifer, ore, block-write, heightmap, and post-process logic

Phase 3 compressed transport/cache run on 2026-09-03:

- [x] no additional Minecraft Mixin target is introduced
- [x] DEFLATE decoding completes before the existing density installation hook
- [x] cache entries contain only immutable density values and context-rich keys
- [x] seven compressed remote-applied digests match local output (`7/7`)

Phase 4 player-owned prediction run on 2026-09-03:

- [x] no additional Minecraft Mixin target is introduced
- [x] generated public APIs and effective-view clamp are source-verified
- [x] prediction does not request or advance Minecraft chunk stages
- [x] three consumed predicted-cache digests match local output (`3/3`)

Phase 5 sampled validation run on 2026-09-03:

- [x] no additional Minecraft Mixin target is introduced
- [x] validator API sequence is verified against generated 26.2 source
- [x] four honest results pass bit-exact sampled recomputation
- [x] one deliberately corrupted result is rejected before installation
- [x] rejected target completes through the original local NOISE path

Seed-confidentiality hardening:

- [x] no additional Minecraft Mixin target is introduced by the fail-closed policy
- [x] generated `RandomState`, `NormalNoise`, `PerlinNoise`, and `ImprovedNoise` seed dependencies are documented
- [x] `DENY` mode remains entirely on the existing local fallback path
- [x] Overworld `finalDensity` seed-leaf inventory is implemented without a Mixin
- [x] `NormalNoise.getValue(DDD)D` and `BlendedNoise.compute(FunctionContext)D` are source-verified
- [x] recorder/replayer hooks apply in Fabric JUnit and a real dedicated server
- [x] remote-disabled spawn generation completes 25/25 NOISE stages with no active trace
- [x] transcript/job/authentication codecs add no Minecraft Mixin target or runtime registration
- [x] seeded-leaf authority lifecycle uses Fabric events without a new Mixin
- [x] seeded-leaf result codec/admission gate adds no Mixin or runtime payload registration
- [x] lifecycle logs start/stop around a graceful dedicated-server run with 25/25 spawn NOISE completions
- [ ] client/runtime networking must not be added before the transcript security gate

---

## 10. Version-change policy

2026-09-20: the existing `ServerChunkCacheFixtureWaitMixin` wraps trusted-raw
admission as well as fixture admission. The two `MainThreadExecutor.managedBlock`
targets and descriptors are unchanged and were rechecked in generated 26.2
source. Pending raw jobs fall back without timeout quarantine, and nested waits
restore admission in `finally`. See WORLDGEN_PIPELINE section 20. Runtime
verification of the added route is pending.

When updating Minecraft:

1. Set all target statuses to `UNVERIFIED`.
2. Regenerate sources.
3. Re-check every target.
4. Update descriptors and findings.
5. Re-run deterministic tests.

Never assume a Mixin target survived a Minecraft update unchanged.

# Minecraft 26.3 and Forge/NeoForge port — in progress

This branch is **not a working release**. The published 26.2 alpha.3, its tag,
maintenance branch and JAR remain unchanged. The 26.3 working version is
`0.1.0-alpha.4+mc26.3`; no compatibility claim is made until loader-specific
builds and runtime comparisons pass.

## Verified dependencies (2026-09-23)

- Minecraft Java Edition 26.3 was released on 2026-09-15.
- Fabric recommends Loom 1.17, Gradle 9.6.0 and Loader 0.19.5 for 26.3;
  Fabric API `0.161.0+26.3` is present in official Maven metadata.
- Forge publishes 26.3 build `66.0.3` (2026-09-22).
- NeoForge official Maven metadata lists 26.3 build `26.3.0.10-beta`.
  Its current documentation is still versioned for 26.1, so verify 26.3
  behavior against the actual MDK/generated sources rather than extrapolating.

The official Forge `26.3-66.0.3` MDK archive was downloaded for inspection;
its SHA-1 `a9446ea6c6ebf1e0cce86c6f65b642e11fa954c7` matches the Forge
download page. Its build uses ForgeGradle `[7.0.17,8)` and Java 25. The
official NeoForge 26.3 ModDevGradle template uses plugin `2.0.147`, Java 25
and currently pins `26.3.0.10-beta`. Inspection copies are under ignored
`.gradle/loader-inspect/`; these are upstream templates, **not** working
WorldgenAssist loader projects.

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

The old 26.2 JUnit sources and public-seed transcript fixture remain on the
published maintenance line. This branch compiles its new targeted tests from
`src/portTest/java` and omits the obsolete fixture classes from the Fabric
artifact. Enabling either old public-fixture flag now fails initialization.
`scripts/validation-capabilities.json` disables the existing matrix because
its installed-client harness still pins 26.2 binaries. Port the harness before
using it as 26.3 evidence.

## Loader split to implement

The job identity, bounded codecs, density calculation, eligibility, result
cache, coordinator and chunk Mixins can be shared only after compilation and
target verification against each loader's 26.3 game source. Fabric-specific
calls currently live in `WorldgenAssist`, `WorldgenPayloadTypes`,
`RemoteWorldgenManager.registerHandlers`, `FabricRemoteJobSender`,
`ServerSettingsMenu`, the two server loggers, `ServerSettingsStore`,
`ClientWorldgenWorker`, `ClientSettings`, and `WorldgenSettingsScreen`.
Extract lifecycle callbacks, payload registration/send, config directory,
mod-version lookup and options-screen entry into loader adapters. Forge and
NeoForge then need independent metadata, development builds, Mixin startup,
dedicated-server/client launches and assisted/vanilla comparisons. The
templates alone cannot satisfy this gate.

## Remaining acceptance gates

1. Confirm the candidate's owner-bound full-volume result in one 26.3
   assisted/vanilla installed-client pair. The focused sampler checks pass;
   networked application, cancellation and disconnect still need runtime
   evidence. Keep raw-seed disclosure separately opt-in.
2. Extract loader-neutral computation/job/validation from Fabric lifecycle and
   packet registration. Supply separate Fabric, Forge and NeoForge adapters,
   metadata and exact artifacts. Confirm every Mixin target against each
   loader's 26.3 generated source and launch a client/dedicated server per
   loader. Do not label a Fabric JAR as Forge/NeoForge compatible.
3. Run only focused tests and affected vanilla/assisted cases until a broad
   change warrants more. Measure performance anew; 26.2 results do not
   predict 26.3 or another loader. Update install guides only after builds
   and runtime evidence exist. Do not publish from this branch meanwhile.

Official references: [Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3),
[Fabric porting notes](https://fabricmc.net/2026/09/15/263.html),
[Forge 26.3 downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_26.3.html),
[NeoForge Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/).

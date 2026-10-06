# Fixture player falling: diagnosis and correction

## Dev22 followup verifies the correction in the affected fixture

Closed batch `feature-pipeline-gate-20261006-184431-462`/session12222:
physical Fabric, Forge and NeoForge original/assisted pairs all pass their
two-player post-fullphase checks. All12 runtime observations are exact Y150,
health20, alive and flying. ALL882 required chunks per loader match for noise,
decoration, saved structures and saved light; actual two-owner peer/shaped use
is retained, not waived.

Read-only original gzip NBT inspection AFTER the native runs stopped confirms
all8 native player saves: target x/z, Y150, Health20, DeathTime0, flying1.
The raw save hashes and selected fields are retained in
`test-artifacts/feature-pipeline-gate-20261006-184431-462/saved-player-safety.json`.
No fresh game, build or JUnit was run for this inspection.

The previous fall was real and should not have been dismissed by passing chunk
snapshots. The server-only fixture pause plus continuing client gravity is the
likely mechanism, not a proven timed transition. Fixture-only original ability
packets correct the observed condition; ordinary physics and gameplay have
not been changed or comprehensively verified.

Overall followup gate still FAILS: both ordinary performance conditions finish
with clean shutdown, but parallel has one prewrite authoritative biome-window
rejection at chunk-2013,3043. Original fallback remains; this is separate from
the now-passing player checks. No complete safety, speed or beta claim.
Exactly unchanged2 prior unit methods and3 prior dev22 builds reused only after
input/artifact hash verification;0freshJUnit/builds. Both earlier failed batches
below are preserved as history; their UNRUN headings are historical.

## Dev22 first batch stopped on early player-check timing

183616-904/session40122 reuses exact2unchanged21methods,3fresh22builds pass.
Physical original player command ran AFTER quiet NOISE prefix but BEFORE full
fixture/SPAWN completion,so command correctly refused and runner timed out;
remaining cases/performance NOTRUN. No new flight/player safety pass claimed.
Moved ONLY remote harness command after Complete-CorrectnessRegion/fullphase.
Next ONE gate uses exact22production inventory/current+retained3JAR hashes and
completed build-step proofs (-ReuseBuildRoot183616-904),plus exact21test reuse.
ZERO unchanged builds/JUnit reruns;freshall3loader fixtures/playerproof and same
22JAR assistedoff/parallel weakE/view32 comparison still UNRUN. All edits before
batch;failed timing attempt retained. Floating production code unchanged.

## Observed dev.21 failure; dev.22 correction verification UNRUN

User observed a visible Minecraft test player near Y-1400 and apparent death.
Do not dismiss this because terrain snapshots pass. Closed dev21 batch
`feature-pipeline-gate-20261006-181055-178`/session55467 exits1. Its2affected
publication methods pass,3builds pass,physical and Forge ALL882 noise/decoration/
savedstructure/light comparisons pass. Forge actual complete applications250
(NativeA214/NativeB36),nonempty shaping0,so the REQUIRED coverage guard stops
before Neo/performance. This is not a full current safety/performance pass.

Read-only original gzip NBT and player statistics in both CLOSED Forge worlds:

| World / player | Saved Y | Health | DeathTime | Flying |
| --- | --- | --- | --- | --- |
| original / NativeA | -3034.158279381188 | 6.0 | 0 | false |
| original / NativeB | -3027.135333677834 | 5.3333330154418945 | 0 | false |
| assisted / NativeA | -2893.2254209852395 | 12.0 | 0 | false |
| assisted / NativeB | -601.7229332529503 | 12.666666030883789 | 0 | false |

All x/z are exact target16000/-32000 and-16000/32000,creative mode1.
Original damage_taken200 each;assisted120 each. No deaths statistic or death
message found,DeathTime0 andpositive savedHealth: actual falling/damage proved,
NOT actual death or absence of an earlier visual death screen. These four
player states are separate from unchanged passing chunk arrays/structures.

Source explains a likely mechanism: stock TickRateManager freeze excludes
players. Existing scoped fixture cancels ServerPlayer.tick/doTick and private
movement/collision helper while original stage barriers are outstanding. It
does NOT freeze client physics;creative players had mayfly=true but flying=false.
At phase completion server player processing resumes and can accept the client's
accumulated falling position. Saved target x/z plus deeply negative Y in BOTH
original and assisted support this fixture-specific explanation;there was no
client/server position trace proving the exact transition time. Do not generalize
this observation to ordinary movement or claim every void-fall cause fixed.

Dev22 fixture-only correction:

- Before original teleport/tickets,send original flying abilities and zero motion
  for the two isolated creative fixture players. No client/gravity Mixin,world
  floor,terrain rewrite,forced healing,authority or ordinary-play change.
- New console-only `worldgenassist_feature_fixture_players` check after completed
  original phase:both required owner identities/target xyz aroundY150,alive,full
  health,flying and exact level. Failures stop the batch. Runners require actual
  two player safety markers;offline proof requires exactY150/fullhealth20.
- Fresh physical and both-native original/assisted fixtures,ALL882 four-stage
  parity and existing both-owner/distinct-peer/nonempty shaping gates retained.
  Native functional CPU profile explicit2server processors/4client workers,view4;
  previous21 native actually used unrestrictedserver/2client workers. This may
  improve shaped coverage;not a proven cause or a native performance claim.
- Reuse ONLY the exact2 passing publication-method XML after exact unchanged
  publication/queue/dispatcher/config/pump/Mixin/resources/test/build input hashes
  match closed21 evidence. Zero unchanged JUnit reruns. Three fresh22builds and
  ALL new affected runtime checks;no reuse of falling player success evidence.
- Then same22JAR assistanceON BOTH,FEATURESoff/parallel2 ordinary weak E/view32/
  warm1/3same repeats/JFR. This tests scheduler increment,not total remoteOFF gain.

Sources:primary26.2 and Fabric26.3 ServerPlayer original onUpdateAbilities sends
ClientboundPlayerAbilitiesPacket;Entity setDeltaMovement public;no new Mixin
target. Existing publication target source verification/hashes remain in
STAGE_PUBLICATION_GUARD.md. Dev22 implementation complete,ALL new verification
UNRUN;old21 failure retained. Publicalpha7 unchanged;goal active;no beta/release.

# Regional single-worker experiment

## Dev.20 verification complete: slower generation

ONE sequential batch `feature-pipeline-gate-20261006-171927-610`/session42610
exits0,success=true,issues=[],source and JARs frozen. Exactly2 selected existing
methods pass (0failures/errors/skips),three fresh builds. Physical and BOTH
native original/guarded-assisted fixtures each match ALL882 NOISE,decoration,
saved structures and saved light. Actual applications/peer/shaped respectively:
physical561/376/7,Forge481/344/7,Neo469/331/9;both owners represented.

SAME dev20 JAR,assistance ON BOTH,natural weak E-server,two strong-PC clients,
view32,unchanged profile,warm1/3 same-coordinate repeats,JFRon:

| Measurement | Parallel2 (ms) | Guarded1 (ms) | Median paired change |
| --- | --- | --- | --- |
| FULL | 85870.754 /95397.507 /87274.146 | 89424.529 /107466.671 /108284.567 | +12.6514%,0/3 faster |
| Both-owner receipt | 91028.9354 /100716.2047 /91598.8128 | 94845.4917 /113344.019 /112979.5815 | +12.5380%,0/3 faster |
| Server CPU | 314453.125 /328140.625 /331640.625 | 317906.250 /314953.125 /319531.250 | -3.6514%,2/3 lower |
| Tick p95 | 12.9780 /13.0562 /12.5220 | 12.9243 /12.5041 /11.3146 | -4.2286%,3/3 lower |

Both finish10658 tasks and3461 receipts/owner/repeat,0 failed tasks/timeouts,
clean stop. ALL10082 FEATURES bodies/repeat;parallel overlaps3163/3916/4943,
peak2;guarded overlap0/peak1. All feature/feature-light/point conflicts0.
One-worker hypothesis did NOT improve waiting time; keep parallel as the speed
candidate,defaultOFF unchanged. Some CPU/tick reduction does not establish a
contention cause. No reversed-order replication;not general/native speed proof.

Artifacts SHA256:Fabric50CB4101774D29EA882F110C62BF534933C6C51DC3B781D226D9DB0A78CBA526;
Forge75D64B06872AC4317F8BDDBD50B26F64B43A39A822D08E01FCA2A93D63FDBB22;
NeoFABC479D8B79916DE519866E5DD7C3C8EA89E1D8F6E8347E5402CD4FF36DDA61.
Historical original saved-light20 differences remain failed. Source inspection
also finds a FAST-body/late-thenApply publication gap in the inherited queue:
body completion can precede original ChunkStep.apply installing its persisted
status callback. These passing phased fixtures do not prove that race absent.
Next fix publication ordering without blocking workers or narrowing R8.
Publicalpha7 unchanged;goal active;no beta/release/default promotion.

## Original implementation-time plan (historical UNRUN)

Current source0.1.0-alpha.8-dev.20+mc26.3,protocol13/native channel4 unchanged.
Existing defaultFEATURESoff/remoteoff,seed-disclosure/complete/peer opt-ins stay.
New explicit `WORLDGEN_ASSIST_FEATURE_BACKEND=guarded` (or existing JVM property
`worldgen_assist.feature_backend=guarded`) retains the same radius8 FEATURES and
point INITIALIZE_LIGHT ownership as parallel,with ONE bounded CPU worker.
Asynchronous futures retain capacity/footprints through original stage
publication and release CPU permits; all conflict FIFO/disjoint bypass/cancellation/
shutdown/original bodies and external light-engine futures remain unchanged.
It can overlap independent original worldgen work,not two decoration bodies.
No terrain/network/verification policy change,new game hook or weaker footprint.

Hypothesis from dev.19 same-artifact164636-518:two-worker parallel shortens FULL
4.9954% and receipts4.8922%(all3) but increases serverCPU1.7530%/tickp955.0281%
(all3). Summed body spans rise,and exactrepeat2JFR still has~31% FEATURES,
~8.6% OreFeature.place. These are inclusive samples/wall spans,not a CPU cause
proof. Fewer active workers may reduce contention/preemption while preserving
the useful off-dispatcher execution and guarded async source-publication timing.
It may instead lose the measured parallel gain; no speed promise/default switch.
Old original saved-light20 differences/12 direct-source contradictions remain
failed,not explained by this change or relabeled as a safety pass.

After ALL edits,ONE `Run-FeaturePipelineGate -Execute -RegionalSingleWorker`:

- Exactly TWO existing affected methods:config/source-verified footprint bounds;
  async ownership/FIFO/CPU-permit checks now exercise both1/2 workers. No unchanged
  decoration/probe/wire/weighted selection/fragment tests.
- Three fresh loader builds; no-daemon avoids retained idle build pipes.
- Fresh small physical original/guarded-assisted recorded/replayed fixture;
  EVERY882 NOISE/decoration/saved structures/light must match. Actual both-owner
  complete/distinct-peer use and nonempty shaping remain required.
- Fresh original/guarded-assisted Forge and Neo pairs with same strict882 stages,
  original Neo weights only fixture-normalized by previously tested hook. Native
  local view4 is correctness only,not speed. No need for two feature-body overlap;
  single CPU/retained async ownership and zero feature/init conflicts remain.
- SAME new JAR assistance ON BOTH:parallel2 versusguarded1,natural weak E-server/
  two clients on one stronger PC,view32,4workers/client,owner32/global64/cache128/
  30s,BIOMESoff/sectionprepINLINE,warm1/three same relocations,JFRon. Actual
  configurations/tasks/receipts/CPU/ticks/error/cleanup and frozen source/JARs.

Guarded is opt-in and ALL tests/builds/runtimes/performance currently UNRUN.
Publicalpha7 unchanged;no new tag/release/beta claim. Goal remains active.

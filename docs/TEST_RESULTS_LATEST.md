# Test results — 26.3 port checkpoint and published 26.2 alpha.3

## Alpha.6 release packaging (2026-10-03 JST)

Final0.1.0-alpha.6+mc26.3 Fabric/Forge/NeoForge archives were assembled in one
sequential batch without repeating runtime/unit/performance tests. Exact
decompressed archive comparison to successful dev.12 evidence proved that
only each loader's version descriptor differs; all classes, Mixins and other
resources match, and all source archive entries match. Prior correctness and
performance records below retain their dev.12 identities. Exact final hashes,
candidate hashes and scope are in
[alpha.6 verification](releases/v0.1.0-alpha.6+mc26.3-verification.md).
Local packaging evidence: test-artifacts/release-alpha6/build-verification.json.
Protocol6 is incompatible with alpha.5 protocol4. Defaults remain unchanged;
the limited gain requires the documented experimental profile.

## Candidate L: bounded asynchronous application (view10/view32 complete)

remote-overlap-gate-20261003-094940-125 is COMPLETE with unchanged dev.12
JAR; no builds/JUnit repeated. Fresh correctness1,813 shared / zero mismatch,
waited-and-applied grids472/503 for the two owners, safe cleanup. Matching
cooperative/wide view10 pair csb-20261003-095520-487 (actual affinity/JVM2,
warmup1/measured3): FULL41,819.877 ->39,034.615ms (-6.66%), receipt47,334.5662
->43,925.4632ms (-7.20%), CPU104,062.5 ->94,015.625ms (-9.65%), tick p95
36.0791 ->37.9017ms (+5.05%). FULL/CPU improved all three repeats; receipt
two of three. Actual grids1480/1282/1375 from1532/1477/1421 sent; raw cache
hits652/463/552 are distinct. Each repeat completed1,682 terrain tasks and385
receipts per owner. Generation failures/job-timeout counters0, but bounded
demand-wait fallbacks27/13/30 are separate and nonzero. Completed7,145 positions
/ zero late stores. RTT mean median29.576858ms, compute2.567871ms, validation
0.159556ms. Fixed-order three-repeat descriptive evidence, not a general claim
or an isolated causal comparison to K's earlier separate batch.

Matching full-view32 pair csb-20261003-100930-620 is COMPLETE: FULL298,621.631
->293,004.549ms (-1.88%), receipt314,193.6249 ->309,658.5105ms (-1.44%),
CPU624,859.375 ->583,500ms (-6.62%), tick p9549.9067 ->51.7245ms (+3.64%).
FULL/receipt improved two of three repeats; CPU improved all three. FULL per
repeat off280357.466/300790.516/298621.631ms, on277909.150/313130.996/293004.549ms.
Actual grids9541/8908/9064 from10354/10077/9999 sent, distinct from prefetch-only
sent1415/1428/1497 and raw cache hits757/483/734. Each repeat completed10,658
terrain tasks and3,461 receipts per owner. Generation failures/job timeouts0,
bounded demand-wait fallbacks158/225/173, completed43,084 positions / zero late
stores. RTT mean median26.453151ms, compute2.499686ms, validation0.124808ms,
decode0.293935ms. Exact JAR/source/settings/coordinates match, safe cleanup
and both owned disconnects confirmed. This is a small descriptive gain with
remaining variance and worse tick p95, not stable/general acceleration.
The same JAR/harness/profile was used without extra build/unit/correctness
repetitions. CPU covers the whole measured interval, not only until FULL.

Candidate K's matching view32 pair csb-20261003-085104-175 is COMPLETE but
slower: FULL284,325.404 ->308,268.538ms (+8.42%), receipt302,373.4547 ->
324,979.9174ms (+7.48%), CPU598,140.625 ->647,046.875ms (+8.18%). All three
FULL/receipt repeats were slower; both conditions used cooperative backend,
actual affinity/JVM2, view32, owner16/global32/client16. Each repeat completed
10,658 terrain tasks and3,461 receipts per owner, zero failures/timeouts.
Actual grid applications2733/2461/2732 from4549/4323/4412 sent; raw cache hits
660/442/640 are distinct. Completed43,114 positions / zero late stores.
Exact JAR/source/settings/coordinates match and both clients stopped cleanly.
RTT mean median30.273949ms, compute2.504377ms, validation0.115084ms.
More useful results did not provide a completion or server-CPU benefit.

This configuration-only experiment uses the exact unchanged dev.12 JAR
and its existing ready_surface_only=false path. A pending result delays only
that chunk's CompletableFuture, not a server/network thread; independent
generation can continue. Base100ms/max200ms, original job/owner/lifecycle
limits and local fallback remain. Run-RemoteOverlapGate completes one fresh
matching-harness two-owner Overworld correctness pair (both owners must wait
asynchronously and actually consume a grid), then one cooperative/wide view10
pair with warmup1/measured3. Builds/JUnit NOT_RUN because MOD code is unchanged.
View10 and view32 completed as above. Native runtime not selected. Remote
assistance and experimental scheduler/application defaults stay unchanged.

## Candidate K dev.12: view10 and matching view32 complete

Recovery remote-window-gate-20261003-083305-914 is COMPLETE: exact original
five-test/three-build evidence reused, fresh matching-harness Fabric correctness
1,803 shared / zero mismatch, both owners negotiating sixteen jobs, direct
network admission and actual grid consumption. Both fixture clients disconnected
normally before save/stop. Completed7164 positions / zero late cache stores.
Native runtime remains unverified. Same-backend cooperative view10 pair
csb-20261003-083838-402 completed actual logical/JVM2, warmup1/measured3:
FULL40,543.785 -> 39,341.047ms (-2.97%), receipt46,534.0949 -> 45,122.3589ms
(-3.03%), CPU102,656.25 -> 96,937.5ms (-5.57%), tick p9534.1135 -> 36.9271ms
(+8.25%). FULL improved in two of three repeats; receipt also two of three.
Each repeat completed1682 terrain tasks and385 receipts per owner; zero
failures/timeouts. Actual grid applications778/667/729 from894/946/834 sent,
distinct from raw cache-hit counts597/407/560. RTT mean median33.58372ms,
client compute2.557138ms, validation0.112288ms, decode0.405659ms, bytes22076.936508.
Three descriptive repeats and fixed off-before-on order do not establish a
general benefit or isolate the window change from all earlier versions.
The unchanged exact dev.12 JAR was measured at server/client view32 with
both cooperative conditions and the same owner16/global32/client16 profile.
Only this larger performance condition is run; no repeated build/JUnit/runtime
correctness suite. Distance32 was slower as recorded above; view10's small
gain does not generalize to the requested larger workload.

First gate remote-window-gate-20261003-034557-843 FAILED: five JUnit methods
and all three builds/metadata passed; normal correctness passed, assisted
failed only during bounded clean shutdown. Both required applications/digests
were recorded, but no completed correctness comparison or performance exists
yet. Fabric SHA97187A759E3AE7D2DA74E00BB50AF3EC70FD7AAF15248D7AAE8DEFEB5CC1672D.
Forge SHAA129153948EC2C8012DBC704F823C3D5114188BB53A3FCEA54BED115BE1C870A;
NeoForge SHA8FE4EA30E4DA0F481DDB9D5C603C2C57CEE9BA27BBC50779A1BB7890990CF0F9.
Fixture recovery explicitly disconnects both owned clients after measurement
and before stop to avoid a source-observed mutable PlayerList iteration risk.
No MOD change; reuse only successful exact-JAR unit/build evidence. Original
failed runtime remains failed; both runtime cases must be fresh together.

Protocol6 and density shape stay unchanged. New explicit owner_window (1..64,
default4) and client.job_window (1..64, default twice worker count) permit a
larger bounded latency window. Global configured limit remains authoritative;
normal defaults remain owner4/global8/client4. The affected gate selects five
unit methods, three builds and Fabric Overworld two-owner correctness, then
one matching cooperative view10 performance pair with owner16/global32/client16.
Refill watermark grows with the explicit window; adaptive failure backoff,
owner isolation, cancellation, deadlines and epoch fences remain unchanged.
Prefetch lookahead defaults to zero after dev.11's unsuccessful measurements;
the optional setting and its original evidence are retained. No new Mixins.

One-CPU dev.11 pair csb-20261003-031726-969 is COMPLETE, unchanged exact JAR,
cooperative both modes, view10, warmup1/measured3. NOISE region median
33,969.668 -> 35,201.888ms (+3.63%); **FULL** 48,917.337 -> 49,441.635ms
(+1.07%); client receipt 57,055.5315 -> 56,922.1964ms (-0.23%); CPU
64,546.875 -> 63,406.25ms (-1.77%); tick p95 33.5529 -> 40.5673ms.
This does not establish a generation speedup. Completed affinity/JVM1 evidence
is separate from earlier CPU2 results. Older pending paragraphs describe the
original planning checkpoint and are superseded by this completed result.

Existing H JFR reanalysis computational-analysis-noise-callers.json retains
all original data: material-rule density inclusive samples 2.776% off/2.507%
on; surface-depth noise 0.305%/0.267%. These overlapping CPU sampling fractions
do not imply wall-clock speedup. No game runtime repeated for this analysis.

## Candidate J dev.11 complete: more grid use, no view10 speedup

generation-lookahead-gate-20261003-024234-087 is COMPLETE: two tests, zero
failures/errors/skips, three builds/versioned metadata and Fabric correctness
1,813 shared / zero mismatch. Both owners consumed grids and used network
admission; 7,153 completed positions / zero late stores. Fabric SHA-256
3980D2F6D014939B00C7064EE6910BDA283D5F9D44D8090B3296F0AC20D8F5C9.
Native runtime remains unverified. csb-20261003-024820-512 matching cooperative
view10 pair is COMPLETE (one warmup, measured3, actual logical/JVM2): FULL
39,978.974 -> 41,151.292ms (+2.93%), receipt 46,174.2811 -> 47,115.7074 (+2.04%),
CPU 97,312.5 -> 100,578.125 (+3.36%), tick p95 32.2868 -> 35.9744 (+11.42%).
Actual grids 444/347/388 from 661/605/543 jobs. Every repeat completed 1,682
terrain tasks and 385 chunks per owner, zero failures/timeouts. RTT mean median
27.411326ms, validation0.145341ms. Additional adoption is not speedup.

One-logical/JVM-CPU hypothesis completed in csb-20261003-031726-969, using
the unchanged dev.11 JAR, two strong clients and matching cooperative backend.
FULL +1.07%, receipt -0.23%; no convincing isolated improvement. Earlier data
retain their original artifact and source/harness identities.

Future observed demand is requested before each owner's immediate head (default
lookahead16, bounded 0..64, control0). Every candidate and owner fairness remain
retained. Protocol6 and all job/cache/lifecycle bounds are unchanged. The focused
gate selected two new ordering/bounds tests, three builds and one two-owner
correctness pair before same-backend view10 speed comparison.

## Candidate I dev.10 complete: latency reduction alone did not speed completion

Fabric client requests now use connection-scoped bounded network admission;
native requests retain main-thread handling. Protocol 6, server authority and
eight secret checks remain. Run-ClientRequestGate selects four affected unit
methods, three builds and the smallest two-owner correctness pair, then one
matched-backend view10 performance pair. Gate client-request-gate-20261003-022053-441
is COMPLETE: four tests, zero failures/errors/skips; three builds and metadata
passed. Fabric SHA F2987C2C78C019BB35C9A54E0FE729096B31D1FFEE0AF7C051A62D1A9A045409.
Correctness: 1,792 shared digests / zero mismatch, both owners consuming grids
and logging request_path=network; 7,158 completed positions / zero late stores.
Native compilation only, changed native lifecycle paths not runtime-tested.
csb-20261003-022647-096 is COMPLETE: matching cooperative backend on both sides,
two actual logical/JVM CPUs, view10, one warmup plus three measured repeats.
FULL 40,080.353 -> 40,630.007 ms (+1.37%); receipt 45,960.8874 -> 46,662.6227
(+1.53%); CPU 100,140.625 -> 99,234.375 (-0.90%); tick p95 35.1778 -> 35.4605.
All repeats completed 1,682 terrain tasks and 385 chunks per owner. No failures
or timeouts. Sent/applied grids 610/377,559/309,495/351; RTT mean median29.424292ms,
validation0.131864ms. These descriptive medians do not establish speedup or
causal improvement relative to a different version's earlier RTT.

## Cooperative dev.9: server parallelism helps; isolated assistance slower

The candidate now provides explicit noise_backend=cooperative, at most two
automatic local terrain workers and four queued tasks per worker, default
backend still vanilla. Explicit overrides remain authoritative. Lifecycle and
tick saturation registration include the mode; transport remains protocol 6.
Essential validation timings are restored in quiet traces and retained job.sent
lines supply prefetch counts. Gate `cooperative-terrain-gate-20261002-233308-926`
passed two affected tests, all three builds/versioned native metadata, two-owner
Fabric correctness and view10 performance. Fabric SHA-256
`6642D74367C5C5130C5BFD2193AF4E2E80ABD8A83244B5EBB17EA693D51DD136`.
Shared 1,803 digests and both required applied owner chunks matched, zero
mismatch. Real parallel terrain execution was recorded, zero late cache stores.

`csb-20261002-233926-431` completed matched view10 settings, two logical/JVM
CPUs, one warmup plus three measured repeats. All three FULL and receipt
times were shorter. Median FULL 46,849.310 -> 41,960.782 ms (-10.43%), receipt
53,581.0364 -> 47,817.8833 (-10.76%), CPU 87,000 -> 104,125 (+19.68%).
Tick p95 median 22.7268 -> 34.4215 ms (+51.46%). The combined scheduler and
client assistance reduces wait by using more server CPU; it does not establish
client offload alone or general speedup. All repeats completed 1,682 terrain
tasks and both owners received all 385 target chunks; sent/applied 671/402,
600/288, 503/315. No terrain failures or job timeouts, 608 bounded local
admission fallbacks executed normally. Validation timing median 0.126156 ms.
Native changed runtime paths remain unverified. Exact-JAR full32 confirmation
reused the completed assisted scenario and reran only the failed baseline,
without another build/unit suite.

The original full32 batch `csb-20261002-235317-913` crossed local midnight.
Its vanilla warmup failed the controller's idle wait after latest.log rolled:
the retained console stdout contains all required NOISE and FULL target chunks,
while the new latest.log omitted 6,827 required NOISE / 6,394 FULL records.
There are no valid measured vanilla repeats from that failed scenario. Cleanup
was safe. Preserve its INCOMPLETE status. After the unchanged assisted scenario
finishes, Run-Cooperative32Recovery can reuse only successful exact-JAR/source
evidence and rerun the vanilla baseline, plus a cooperative/no-assistance case
to isolate client contribution. Comparisons explicitly record reversed case
order and remain descriptive. No build or unrelated unit suite is repeated.

Recovered full32 comparison in `cooperative32-recovery-20261003-005853-523/vanilla-comparison`
is COMPLETE with matching source/JAR/settings/coordinates/workload: FULL median
332,000.362 -> 301,262.651 ms (-9.26%), receipt 350,664.2747 -> 318,581.2107
(-9.15%), CPU 527,078.125 -> 636,281.25 (+20.72%), tick p95 29.8794 -> 48.6834
ms (+62.93%). All three FULL and receipt rows were shorter; all repeats
completed 10,658 terrain tasks and both owners received 3,461 chunks.
Assisted sent 3,980/3,915/4,138 and applied cached grids 1,701/1,510/1,299,
with no terrain failures or job timeouts. Raw ready_cache_used counts only
cache.hit; it is not the total of queued_ready grid applications. Use retained
job.complete source=cache for actual consumption.

The earlier recovery `cooperative32-recovery-20261003-003030-484` completed
its vanilla runtime and comparison, then its wrapper failed by reading unset
LASTEXITCODE after a successful in-process PowerShell script. Preserve its
INCOMPLETE wrapper record; removing that irrelevant check and adding explicit
successful-baseline reuse avoids repeating valid runtime evidence. The second
recovery completed aggregation and the cooperative/no-assistance runtime.
Its cooperative-comparison is COMPLETE: FULL 289,001.270 -> 301,262.651 ms
(+4.24%), receipt 304,766.7000 -> 318,581.2107 (+4.53%), CPU 606,921.875 ->
636,281.25 (+4.84%). Assistance is slower than the same backend without it.
The three conditions were executed assisted, normal baseline, cooperative
baseline; these are descriptive three-repeat medians, not randomized trials.

Matched cooperative JFR `cpu-profile-20261003-014354-350` is COMPLETE with the
unchanged dev.9 JAR, two owners, actual CPU/JVM2, view10 and one measured repeat.
Execution samples: 8,861 off / 8,975 on. Inclusive density 24.51%/21.92%,
management 0.49%/1.25%, validation two assisted samples; categories overlap.
The targeted callers analysis locates DensityVolume.indexOfBlock mainly in
server material/ore processing; eligibility accounted for 24 assisted samples
and remote grid integration five. None proves absolute CPU cost or causation.
`console-capture-20261003-014354-762` passes two PS5 checks (live read and midnight
rolling-log preservation). Both runtime cases verify integrated console capture.
`cpu-profile-20261003-014131-259` remains INCOMPLETE: inherited PS7 module path
prevented PS5 Get-FileHash before any Minecraft case. The successful retry sets
the owned child PS5 module path explicitly; no game run was repeated for that
preparation failure.

## Completed dev.8 CPU diagnosis

`cpu-profile-20261002-213032-205` used the unchanged dev.8 SHA-256 below,
two owners, two logical/JVM CPUs, view10, one warmup and one measured repeat.
Both scenarios completed and cleaned up. Deep analysis reads exports as a
stream and excludes NativeMethodSample waiting records: 5,745 vanilla / 5,849
assisted ExecutionSamples, 11 / 13 truncated stacks. One Worker-Main thread
had 83.92% / 81.77% of computational samples. Inclusive surface material
29.82% / 28.69%, block fill 27.29% / 26.40%, density sampler 23.60% / 21.03%.
Categories overlap, percentages are not absolute CPU times, and this single
instrumented repeat is not a speed gate. Validation had two assisted samples
(0.034%); that is evidence against validation dominating this diagnostic,
not a precise stable cost estimate. Original shallow/native-inclusive exports
are retained separately from computational-analysis.json.

The first diagnosis `cpu-profile-20261002-212753-264` failed before Minecraft
startup because the dedicated Java image has no built-in profile.jfc. Cleanup
was safe and no client/runtime measurement occurred. The retry transferred the
pinned local JDK's settings with SHA-256 verification, then recorded to each
owned evidence child without attach.dll. No extra unit/build/correctness suite.

## Load-ahead dev.8: correctness passed, performance gate incomplete

Gate `load-ahead-gate-20261002-210528-949`, Fabric SHA-256
`960700A2131431AF3DA9BA2898C5A9FEF0966A7050C1D9575573DAED37C66A5A`:
five affected JUnit tests and all three loader builds passed, native versioned
TOML descriptors matched dev.8. Two-owner correctness shared 1,803 digests with
zero mismatch, required applied chunks matched and both owners consumed grids.
Early-load hints were observed. Native changed runtime paths were not run.

Performance root `csb-20261002-211136-651`: both scenarios completed and cleaned
up, all three repeats completed 1,682 terrain tasks and each owner received all
385 target chunks. However, quiet tracing suppressed validation timing required
by the comparator: `MISSING_REMOTE_METRIC`. Original analysis and gate remain
INCOMPLETE/failed, not retroactively converted into passing results. The saved
observation labels this gap. FULL median 44,272.984 -> 46,281.260 ms (+4.54%),
receipt 51,292.5711 -> 52,289.2154 (+1.94%), CPU 80,671.875 -> 90,328.125
(+11.97%); all three FULL repeats slower. Sent/applied 553/235, 526/193,
495/229. This does not support speedup. Closed-log check found zero late cache
stores among 7,198 completed positions, recorded in dev8-observation.json.

The subsequent diagnostic reused this JAR without build/JUnit/correctness
reruns; its completed results and limits are above.

## Candidate G implementation checkpoint (2026-10-02, not yet tested)

Local alpha.6-dev.8 keeps protocol 6. It observes Minecraft's completed
loads of unfinished terrain before structure starts, skips already generated
terrain, retains owner/world lifecycle gates and adds no tickets or waits.
At balanced queue capacity a nearer candidate can replace only its own
owner's farther candidate. Grid validation calculates selected aquifer points
alone; original material 16x16 interpolation remains exact. Eight secret
points are unchanged. Routine detailed logs follow diagnostics/trace_jobs,
with an explicit quiet performance option while preserving sent/result/use
metrics, late-cache traces, warnings and errors. Native version labels now
read shared gradle.properties instead of hardcoding alpha.5; prior locally
built labels are archived, published release assets remain untouched.
Run-LoadAheadGate selects five affected tests, three builds plus native
metadata inspection, two-owner Fabric correctness and one quiet distance-10
pair. All implementation precedes this batch. No G result exists yet.

## Candidate F distance-32 confirmation (2026-10-02)

`csb-20261002-194852-473` completed with zero issues using the exact
dev.7 Fabric JAR above, actual JVM processors=2 plus two logical affinity
CPUs, both clients/server view 32, one warm-up plus three measured fresh
regions. Each mode completed 10,658 NOISE tasks per repeat (31,974 measured)
and both clients received all 3,461 target chunks each time. Median FULL
336.991s vanilla versus 348.701s assisted (+3.47%); receipt 356.444s versus
368.300s (+3.33%); CPU 531.391s versus 558.359s (+5.08%); NOISE readiness
296.984s versus 306.692s (+3.27%). All three FULL repeats were slower with
assistance. The small distance-10 gain did not carry over to full view 32.

Measured sent/use pairs were 3,031/372, 2,961/380 and 2,967/436: only about
13% of sent requests and 4% of all terrain tasks supplied consumed cache
results. Client compute remained 2.478ms, but RTT was 80.295ms and validation
1.641ms (medians of per-repeat means). Cleanup succeeded in both modes.
This motivates earlier, better prioritized useful work rather than claiming
general speedup. Fixed-order, n=3 evidence remains descriptive.

## Candidate F completed distance-10 gate (2026-10-02)

`retired-work-gate-20261002-192955-323` passed six affected tests, three
builds, Fabric two-owner correctness and its speed pair. Shared NOISE
digests: 1,803, zero mismatch; both required applications matched and both
owners consumed terrain grids. Correctness recorded 184 grid applications,
zero BUSY, stage errors or shutdown dumps. Fabric alpha.6-dev.7 SHA-256:
`F95C394B8EB75ACA1026ECF6E50DC5D0E57E8823393BF5910B5DC1AC48FB13B1`.
The binary and built sources JAR are archived in the gate folder.

`csb-20261002-193557-391` completed with zero issues: full view 10,
two logical affinity CPUs and actual JVM processors=2, one warm-up and
three fresh measured regions. Both modes completed 1,682 NOISE tasks
per repeat and each client received all 385 target chunks. Median FULL
46.757s vanilla versus 45.680s assisted (-2.30%); NOISE readiness 34.043s
versus 33.111s (-2.74%); receipt 53.531s versus 52.459s (-2.00%);
server CPU 91.266s versus 87.125s (-4.54%). All three FULL repeats were
shorter with assistance. This is a small descriptive improvement with
fixed vanilla-then-assisted order, n=3, not a general speed guarantee.

Measured cache applications were 298/221/297. Validation was 1.494ms,
client compute 2.487ms, encoded bytes 22,164 and RTT 44.260ms (medians
of per-repeat means). Late-cache inspection covered 7,194 completed
positions and found zero saves after their NOISE completion. Cleanup was
safe. Native builds passed; native runtime remains unverified. The prior
dev.6 shutdown failure remains unresolved; dev.7 adds diagnostics and this
successful run does not prove that intermittent failure fixed.

The exact dev.7 JAR's full view 32 confirmation is recorded above and did
not reproduce a speedup; the modest distance-10 result is not a general claim.

## Candidate F retained shutdown failure (2026-10-02)

`retired-work-gate-20261002-190807-467` passed six focused tests and three
builds. Local dev.6 Fabric SHA-256:
`892D7CEB36933824D6F68146F388321358D74B9D5D7A7CA82FB0662C8245BD80`.
Vanilla correctness passed; assisted correctness reached both required
applications but failed clean shutdown, so comparison and performance did
not run. Cleanup was safe. The assisted log contained 209 grid applications
and no stage failures, but those facts do not establish a successful gate.
The server stack was in stopServer's ordinary chunk draining loop, with
idle generation/validation workers. Local dev.7 adds a diagnostic-only
server-thread read of shutdown work; the underlying failure is unresolved.

## Candidate F retired work and sampled verification (2026-10-02, checkpoint)

Local alpha.6-dev.6 retains protocol 6 and exact terrain grids. It cancels
work that cannot be consumed once terrain execution starts, fences late
cache writes with bounded expiring metadata, atomically clears remaining
work at terrain completion and avoids requesting already completed terrain
using a nonblocking holder lookup. Eight secret grid points replace eight
groups of sixteen; surface-only and full-density verification are unchanged.
Run-RetiredWorkGate selects six affected tests, three builds, two-owner
correctness and one full-view distance 10 speed pair, including a late-cache
check. Its dev.6 failure and dev.7 completed gate are recorded above.

## Candidate E completed (2026-10-02)

`grid-density-gate-20261002-184106-142` passed nine affected tests, three
builds, Fabric two-owner correctness (1,803 shared digests, zero mismatch,
both required applications equal), actual grid use by both owners and a
complete speed pair. Fabric SHA-256:
`2D100E932AD5BFDE88BB66913CC629AE06B1517E4DD4886E9784EBCE171268E9`.
The built sources JAR is archived alongside it. Native runtime was not run.
`csb-20261002-184703-650` used actual JVM processors=2 plus affinity=2,
full view 10, one warm-up and three measured fresh regions, 1,682 NOISE
tasks per repeat and 385/385 received chunks per owner. Median FULL
46.188s vanilla versus 46.308s assisted (+0.26%); receipt 53.429s versus
52.835s (-1.11%); CPU 85.391s versus 89.938s (+5.32%). This does not
establish an end-to-end speedup. Applied output used about 22,139 encoded
bytes, client compute 2.529ms and validation 2.796ms (per-repeat mean medians).
Measured cache applications were only 27/74/107. Cleanup was safe.

## Candidate E terrain grids (2026-10-02, implementation checkpoint)

Local alpha.6-dev.5 uses protocol 6. It sends the five outer 4x8x4
interpolator inputs (5x49x5 each at Overworld height 384), followed by the
769 surface fields: 6,894 exact float values. Vanilla interpolation,
nonlinear final-density operations and block mutation stay on the server.
The ready-only path and all owner/epoch/deadline/seed opt-ins are retained.
Run-GridDensityGate batches nine affected tests, three builds, two-owner
correctness with actual grid use, and one full-view distance 10 speed pair.
The completed batch is recorded above; speedup is still not established.

## Candidate D completed (2026-10-02)

`ready-surface-gate-20261002-181204-435` passed all five focused tests,
three builds, two-owner Fabric correctness and its performance pair.
All 1,813 shared digests and both required applications matched; both
owners used queued-ready output. Fabric SHA-256:
`CBE5F03CEF70735F07E8339C8F0010C9D98282408A3A7A194E386C55CA259FDC`.
Native runtime was not selected. `csb-20261002-181802-421` is COMPLETE,
with two logical affinity CPUs and actual JVM processors=2, full view 10,
one warm-up plus three measured fresh regions. Each repeat had 1,682 NOISE
tasks and 385/385 received chunks per owner. Median FULL 43.949s versus
45.819s assisted (+4.26%); receipt 51.001s versus 52.253s (+2.46%);
server CPU 78.844s versus 89.266s (+13.22%). There is no speedup.
The complete assisted log had zero BUSY, remote timeout and shutdown-dump
records; cleanup was safe. Only 96/81/101 cache applications occurred in
the measured repeats. The result motivates offloading more useful work,
not claiming success from removing response waits.

## Candidate C retained failures and candidate D (2026-10-02)

Local alpha.6-dev.3 Fabric SHA-256
`A93F7FA3B0BFDDF99C92569FAE1F529956411CBB97C100AAB9727107D9C0B079`.
`generation-resource-gate-20261002-170658-130` passed six affected tests and
three builds, then exceeded the correctness controller deadline. The server
finished generation; indexing completed jobs once replaced per-job log scans.
The revised `generation-resource-gate-20261002-172329-371` reused those exact
build/test artifacts and passed two-owner correctness: 1,803 shared NOISE
digests, zero mismatches, both required applications equal and concurrent owners.
Actual JVM availableProcessors and affinity were both limited to two.

Its `csb-20261002-172904-994` performance pair is **INCOMPLETE**. All three
measured repeats reached 1,682 NOISE tasks, but assisted shutdown stalled in
world saving and the bounded controller killed its owned server. Cleanup was
proved safe; this is not a successful performance gate. Partial FULL times
vanilla/assisted were 46.960/47.839s, 47.101/47.402s, 40.358/40.491s.
Corresponding CPU milliseconds were 93750/92796.875, 89765.625/86843.75,
73203.125/68968.75. They suggest CPU reduction without shorter completion,
but do not establish a speedup. Many BUSY replies occurred: the server released
leases when replies arrived while client executor tasks were still returning.

Candidate D, local alpha.6-dev.4, completed as recorded above. Surface work enters the
vanilla executor immediately, claims independently validated cache output at
execution time and never waits for a response. Missing output falls back at
once. Client queues retain bounded post-result handoff slack. Diagnostics can
emit an in-process shutdown thread dump without the dedicated Java image's
missing attach library. Run-ReadySurfaceGate batches only the affected scope,
ownership and executor tests plus builds, correctness and a speed pair.

## Surface-field candidate B (2026-10-02)

`surface-density-gate-20261002-163816-896/summary.json` passed nine affected
tests, three loader builds, and Fabric two-owner correctness. All 1,813 shared
NOISE digests and both required applied chunks matched. 526 completed
applications recorded nonzero surface-field consumption. Archived Fabric
alpha.6-dev.2 SHA-256:
`BD95723687E85C02B2D8256D658C8CD7112A5E19DC45947BEE103C8DC4B857C5`.
Native runtime was not selected; no release was published.

Screening: `csb-20261002-164537-906/summary.json` COMPLETE, zero issues,
two logical server CPUs via affinity only, full view distance 10, two clients,
one warm-up and three measured repeats. Each repeat completed 1,682 NOISE
tasks; both owners received 385/385 target chunks. Median FULL was 39.168s
vanilla vs 40.439s assisted (+3.25%); client receipt 44.391s vs 45.752s
(+3.07%); server CPU 96.500s vs 97.984s (+1.54%). NOISE was 28.286s vs
29.117s (+2.94%). This remains a regression, not a speedup. Per-repeat mean
medians for applied output: client compute 0.583ms, encode 0.154ms, RTT
45.675ms, decode 0.036ms, validation 0.392ms, encoded bytes 343.226.
The payload optimization works, but end-to-end savings are not established.

Candidate C (local alpha.6-dev.3, pending) retains surface fields, assigns only
observed terrain work within the source-derived ticket dependency square,
and advertises existing client running+queued capacity. Its measurement
explicitly limits both affinity and JVM availableProcessors to two, so results
must not be treated as a controlled comparison against candidate B's CPU model.

## Validation-cost candidate (2026-10-02)

`validation-cost-gate-20261002-150213-010/summary.json` passed two focused
tests, three loader builds, a two-owner Fabric correctness pair (1,813 shared
NOISE digests, zero mismatches), and the full distance-32 performance pair.
Archived local alpha.6 Fabric SHA-256:
`20AAC1922FD19B68834F5D92FD7D4FC490184B63902BC9FB01C74DB01D7D2BB1`.
This candidate was not published. Performance evidence is
`csb-20261002-151052-305/summary.json`, COMPLETE, zero issues, three matched
fresh measured repeats, two logical server CPUs and two distance-32 clients.
Every repeat completed 10,658 NOISE tasks and both owners received 3,461 chunks.

| Median | Vanilla | Assisted | Change |
| --- | ---: | ---: | ---: |
| NOISE ready | 254.013s | 278.704s | 9.72% slower |
| FULL ready | 289.682s | 316.435s | 9.24% slower |
| Both clients received target | 306.654s | 334.176s | 8.97% slower |
| Server CPU | 602.016s | 644.844s | 7.11% higher |

Applied-result validation averaged 4.223ms (median of per-repeat means),
versus 14.569ms in the earlier alpha.5 run. This demonstrates reduced local
validation cost, not an end-to-end speedup or a controlled historical comparison.
All three paired FULL and receipt measurements remained slower with assistance.
The warmed unit microbenchmark measured preparation 4.836ms to 1.567ms;
timing assertions were not used. Affinity limits execution to two logical CPUs
but does not change JVM availableProcessors; many background workers remain.

The next local `alpha.6-dev.2` candidate uses protocol 5 surface intermediates.
Its correctness and performance remain pending until its sequential gate passes.

## Alpha.5 distance-32 constrained-server experiment (2026-10-02)

The requested SSH experiment completed using the existing published Fabric
alpha.5 JAR, SHA-256
`629085B905A1B86DA6E8A62A79C2B8C452063568807A770AB7E0EF94CB539EEB`.
Evidence: `test-artifacts/csb-20261002-134517-261/summary.json`, paired report
`c2/analysis/scenario-matrix-analysis.json`, and density-use analysis alongside it.

- Dedicated server on the authorized SSH PC, applied affinity mask 3 (two
  logical CPUs), 6 GiB heap, view-distance 32, simulation-distance 3. Two
  installed clients on the initiating PC, each 4 GiB heap and renderDistance 32,
  CUSTOM preset, FPS cap 30. Traffic uses the existing loopback SSH tunnel.
- Overworld, seed 8675309, prediction enabled, cache 128, eight validation cells,
  prefetch profile. One excluded warm-up and three measured fresh relocations
  per mode. No production code changes, builds, JUnit or correctness matrix.
- All six measured repeats completed exactly 10,658 NOISE tasks on matching
  coordinates, 31,974 per mode. Source manifests and JAR identities matched.
  Every owner/repeat received all 3,461 target chunks, including outer view
  coordinates. Both scenarios proved cleanup safety. Analysis is COMPLETE with
  zero issues and DESCRIPTIVE_ONLY performance scope.

| Median metric | Vanilla | Assisted | Assisted change |
| --- | ---: | ---: | ---: |
| Target NOISE ready | 251.707s | 289.049s | 14.8% slower |
| Target FULL ready | 286.485s | 329.332s | 15.0% slower |
| Both clients received target | 303.751s | 347.289s | 14.3% slower |
| NOISE throughput | 32.703/s | 28.798/s | 11.9% lower |
| Whole measured server CPU | 594.375s | 673.906s | 13.4% higher |
| Server tick p95 | 22.360ms | 34.348ms | 53.6% higher |

Successful remote/cache density applications were 2,230, 1,828 and 1,951,
17.15–20.92% of NOISE completions (18.79% pooled, not CPU saved). The raw
measured logs recorded 115, 179 and 101 demand-wait fallbacks (395 total),
which the comparator's separate fallback/timeout counters omit. The median
of per-repeat means was client compute 5.804ms, decode 5.561ms, validation
14.569ms and RTT 115.076ms. RTT includes unseparated transport and queues and
is not pure network time; these result timings exclude unused/failed requests.
Request queue means were 1.02–1.18ms and ingress queue means 2.81–3.38ms.

The larger workload used more successful remote density results, but did not
reduce server CPU or player-visible wait. Additional server processing and
bounded waiting remain plausible contributors; exact CPU attribution requires
profiling. This experiment does not support workload size alone as a fix for
this hardware/network setup. Each assisted FULL and receipt measurement was
slower than its corresponding vanilla repeat. Fixed mode order, three repeats,
shared client hardware and relocation rather than long continuous exploration
limit generalization. Receipt is chunk loading, not finished rendering.

Retained harness failures: `csb-20261002-134113-077` stopped during warm-up
because a region-loop variable collided with the typed log offset. Its clients
also used FANCY preset (distance 16); it is not performance evidence. After
correcting both, the complete runtime pair above passed. The outer batch adapter
then errored reading an unset LASTEXITCODE after the successful nested script;
the adapter now runs the batch in a child PowerShell and propagates its exit.
Its syntax/geometry/plan checks passed after this fix; successful Minecraft
measurements were retained without rerunning them. Offline test-account/Realms
and OS counter warnings remain in logs as in prior installed fixtures.

## Alpha.5 packaging checkpoint (2026-09-29)

Version `0.1.0-alpha.5+mc26.3` packages the protocol-4 candidate below.
All three loader distribution/source builds passed in one sequential batch.
Comparing every decompressed distribution archive entry with the saved candidate
found only the loader's version metadata changed; all classes and other entries
were identical. No new runtime/performance matrix was run for this packaging.
Original evidence remains attached to its original JAR identities. Exact final
hashes and scope are in [alpha.5 verification](releases/v0.1.0-alpha.5+mc26.3-verification.md).

## Network dispatch candidate — constrained-server performance (2026-09-29)

The requested paired speed test completed for the exact network-dispatch
candidate:

- Evidence: `test-artifacts/csb-20260929-220931-467/summary.json`; paired
  analysis: `test-artifacts/csb-20260929-220931-467/c2/analysis/scenario-matrix-analysis.json`.
- Fabric JAR SHA-256:
  `922F27B8B3427D16DAA23AD1ABB5FD15DABF00FBC0264619D71D6E4FFC0F3BF0`.
- Two logical server CPUs (applied affinity mask 3), two clients, Overworld,
  seed 8675309, prediction, cache 128 and eight validation cells. Each mode
  used one excluded warm-up and three measured repeats, with the same measured
  coordinates and 1,682 NOISE tasks per repeat (5,046 per mode). The source
  manifest matched before and after both modes. All six client regions had
  81/81 chunk receipt; both scenarios cleaned up safely. The paired summary
  recorded no failed tasks or timeout/fallback events in its separate counters.
  Raw assisted measurements additionally recorded 10, 2 and 8 demand-wait
  fallbacks (20 total); these short-wait expirations trigger local generation
  and are not included in those summary counters.
- Median server 9-by-9 NOISE-region completion was 11,264ms vanilla and
  7,454ms assisted (**33.8% faster on this server-side proxy**).
- Median server 9-by-9 FULL completion was 14,576ms vanilla and 14,844ms
  assisted (**1.8% slower**, descriptive). The NOISE improvement did not
  persist through server-side chunk completion.
- Median time until both clients loaded all 81 chunks was 17,436ms vanilla and
  17,801ms assisted (**2.1% slower assisted**). Median throughput was 29.44 vs
  29.56 NOISE tasks/s (**0.4% higher assisted**).
- Median server CPU was 103,328ms vs 102,297ms (1.0% lower assisted). Median
  tick p95 was 16.96ms vs 18.97ms (11.8% higher assisted); tick mean was
  12.40ms vs 10.79ms. These metrics move in different directions.
- In assisted measured runs, mean request RTT was 91.46ms, decode 11.32ms,
  validation 16.28ms, and client density computation 5.80ms. RTT now ends when
  the complete payload reaches the Fabric network ingress, before decoder
  queueing. It is not directly comparable to RTT from the earlier handler-entry
  timestamp. The separate correctness run observed 33.33ms mean decoder
  ingress queue, but that is a whole-run diagnostic, not a measurement paired
  to these three performance repeats.

Assessment: server-side chunk work completed earlier in this constrained
region test, but players did **not** receive the full region sooner. The end to
end client receipt metric was slightly slower and throughput was effectively
unchanged. Three repeats are descriptive evidence, not a broad performance
claim. The comparator reports `DESCRIPTIVE_ONLY`; no speedup in player-visible
generation time is established for this setup.

## Network dispatch candidate — focused gate complete (2026-09-29)

All implementation preceded one `Run-NetworkDispatchGate.ps1 -Execute` batch:

- Evidence: `test-artifacts/network-dispatch-gate-20260929-215751-919/summary.json`.
- Fabric JAR SHA-256:
  `922F27B8B3427D16DAA23AD1ABB5FD15DABF00FBC0264619D71D6E4FFC0F3BF0`.
- Six affected tests across three classes: zero failures/errors/skips.
  Fabric build and the affected Forge/NeoForge transport builds passed;
  native loader tests/runtime were not selected.
- Installed Fabric Overworld vanilla/assisted correctness pair: two owners,
  separate dedicated server with two logical CPUs, eight validation cells,
  cache 128, prefetch and 100ms demand base. Both required applied chunks and
  all 1,802 shared NOISE digests matched; owner jobs overlapped, comparison
  `COMPLETE` with zero issues. Both cases reported safe cleanup and unchanged
  source manifests through their execution.
- `transport-paths.json`: 276 accepted network-ingress receipts, zero main-route
  receipts, ten 2..4-job batch packets. Both clients used direct worker send
  (141/154 submissions); mean local send handoff was 0.0536/0.0560ms.
- Whole correctness-log diagnostics (including warm-up): mean registration
  0.1434ms, request submission queue 6.3640ms, decoder ingress queue 33.3321ms
  and result identity claim 0.4969ms. The decoder queue remains a material wait;
  these are not performance samples or synchronized client/server timelines.
- No ingress-capacity rejection, decode failure/rejection, density mismatch
  or job-timeout log. Twenty cancelled replies were rejected. Server overload
  and scripted movement warnings remained. Client logs retain offline test
  account/Realms HTTP errors and OS performance-counter warnings; no worker
  computation failure was found.

The candidate removes client main-thread reply scheduling, adds Fabric
connection-scoped result ingress before the server packet queue, batches
observed-generation approvals and permits bounded early refill. The subsequent
performance pair above found an earlier server-region proxy but slightly slower
client receipt. Fabric RTT ends at full payload arrival on the network thread,
so it must not be compared directly with old handler-entry RTT.

## Adaptive demand wait: constrained-server performance — 2026-09-29

The requested performance comparison completed for the same Fabric candidate:

- Evidence: `test-artifacts/csb-20260929-201939-871/summary.json`; paired
  analysis: `test-artifacts/csb-20260929-201939-871/c2/analysis/scenario-matrix-analysis.json`.
- Fabric JAR SHA-256:
  `2BEC7A498A1F1ECFC9F00CE9292D3E23BEAD98157D176144EFEC0C96DE87CC5D`.
- Two logical server CPUs, two clients, Overworld, seed 8675309, prediction,
  cache 128 and eight validation cells. Vanilla and assisted each used one
  excluded warm-up and three measured repeats. They completed the same 1,682
  NOISE tasks per repeat (5,046 total per mode) on identical coordinates. All six client regions had
  full 81/81 chunk receipt coverage; no failures or timeouts were recorded.
- Median server 9-by-9 NOISE-region completion was 7,276ms vanilla and
  8,257ms assisted (**13.5% slower assisted**). Median completion at both
  clients was 15,697ms and 17,365ms (**10.6% slower assisted**).
- Median NOISE throughput was 30.03 tasks/s vanilla and 29.19 assisted
  (**2.8% lower assisted**). Median server CPU use increased from 100,750ms
  to 102,938ms; median server tick p95 increased from 20.06ms to 21.40ms.
- Assisted logs show 130ms mean request RTT, 5.76ms mean client density
  computation and 16.50ms mean authoritative validation. This suggests network
  and queue delay can outweigh the small density calculation in this setup.

This result does **not** show a speedup on a two-logical-CPU server; all three
primary time/throughput measures were worse with assistance. The three repeats
are descriptive, and execution order was vanilla followed by assisted. This
does not establish results for other server hardware, networks or movement.
Performance scripts were the only work run for this request; no tests or builds
were repeated. The benchmark lock now opens the existing shared lock file
exclusively and removes it after releasing it, so prior batch lock files do not
prevent the measurement from starting.

## Adaptive demand wait: minimum affected gate — 2026-09-29

All implementation and each necessary correction preceded its batch execution.
The final `Run-DemandWaitGate.ps1 -Execute` passed:

- Evidence: `test-artifacts/demand-wait-gate-20260929-200658-043/summary.json`.
- Fabric JAR SHA-256:
  `2BEC7A498A1F1ECFC9F00CE9292D3E23BEAD98157D176144EFEC0C96DE87CC5D`.
- Three affected JUnit classes, seven tests, zero failures/errors/skips:
  five virtual-time adaptive wait tests, one consumed-cache identity/isolation
  test and the existing candidate-queue test extended for saturated admission.
  Coverage includes delayed timer execution, the absolute wait ceiling and
  bounded admission for a slow owner when no ahead work is active.
- Fabric build passed. No unrelated JUnit suite or native-loader build ran.
- Installed Fabric Overworld vanilla/assisted correctness pair, two owners,
  server CPU affinity mask 3 (two logical processors), actual 100ms base wait,
  prefetch, cache 128, prediction disabled and eight validation cells.
- Both required remotely applied chunks matched vanilla, successful owner
  jobs overlapped, all 1,822 shared NOISE digests matched, and final comparison
  was `COMPLETE` with zero issues. Both scenarios reported safe cleanup.
- Runtime logs contained 170 demand waits with configured budgets from 100
  through 200ms, 279 successful applications, 131 cache hits and 133 slow-direct
  skips. These are whole correctness-scenario counts, including warm-up;
  they are not performance samples or throughput measurements.

No performance scenario ran for this artifact. Bounded waiting and two-owner
participation are verified within this selected scope; faster player
receipt/rendering or general speedup remains unproven. Forge/NeoForge runtime,
other dimensions and arbitrary custom generators are outside this gate.

Retained intermediate checkpoints (never substituted for the final result):

- `demand-wait-gate-20260929-170441-607`: five tests/build passed; assisted
  warm-up failed to trace cached applications because cache consumption used
  a fresh request ID. Logs had 171 cache applications, but this was not a
  successful owner correctness proof. Cache now retains the original immutable
  result/ID and checks insertion coordinate/context identity.
- `demand-wait-gate-20260929-171752-574`: the added cache test failed because
  Minecraft registries were not bootstrapped. Its setup was corrected.
- `demand-wait-gate-20260929-172058-597`: six tests/build and vanilla runtime
  passed, but assisted correctness timed out. After relocation one owner had
  118 applied results while the other had no sent jobs. Unconditional slow
  direct suppression exposed uneven ahead admission; the correction preserves
  bounded direct admission when that owner has no active ahead work and admits
  underrepresented owners to a saturated candidate queue. Those failed runtime
  cases reported safe cleanup. Their JARs are not final-candidate evidence.
- `demand-wait-gate-20260929-200515-629`: build tasks were up to date, so the
  gate correctly rejected the older JUnit timestamps instead of reporting
  them as a fresh run. The script now uses Gradle `--rerun-tasks`; the next gate
  executed all selected tests and completed successfully.

## Pipelined candidate: complete selected gate — 2026-09-29

All implementation preceded testing. The successful local-only gate below was
reused with its exact JAR SHA-256 after the user explicitly authorized transfer
and batch execution on the dedicated other PC. The runtime gate completed:

- Evidence: `test-artifacts/pipelined-assist-gate-20260929-154717-022/summary.json`.
- Same Fabric JAR: `D0B937582352C03D3D75F9C703BD56E6FF91295C572EA0CB4C7C28E29FC11B1F`.
- Installed Fabric two-owner Overworld correctness: both required remotely
  applied chunks and all 1,814 shared NOISE digests matched; overlapping owner
  jobs; zero issues.
- Eight performance scenarios completed, each with one excluded warm-up and
  three measured repeats. The dedicated server applied CPU affinity mask 3
  (two logical processors); both clients ran on the initiating PC.
- Read-only final aggregation created six paired reports, all `COMPLETE`,
  under `comparisons/`, indexed by `performance-comparisons.json`. Every pair
  had equal runtime/client conditions, exact measured coordinates, 1,682
  completed NOISE tasks per repeat, zero failed tasks, matching JAR/source
  identity, safe cleanup, and full 81/81 receipt coverage for both owners.

Medians, in milliseconds (CPU is whole measured server CPU consumption):

| Movement | Variant | Client receipt | Server FULL | Server NOISE region | Server CPU |
|---|---|---:|---:|---:|---:|
| Relocation | Vanilla | 15,399 | 12,359 | 7,549 | 98,203 |
| Relocation | Current-style control | 16,636 | 13,003 | 6,859 | 97,469 |
| Relocation | Prepared | 16,841 | 13,617 | 6,983 | 101,500 |
| Relocation | Prefetch | 17,429 | 14,334 | 7,873 | 101,844 |
| Continuous | Vanilla | 17,640 | 14,954 | 11,873 | 101,984 |
| Continuous | Current-style control | 20,184 | 17,822 | 13,045 | 100,641 |
| Continuous | Prepared | 18,872 | 16,564 | 12,114 | 98,188 |
| Continuous | Prefetch | 18,133 | 15,885 | 11,714 | 98,563 |

The current-style control uses the same candidate with context reuse and both
server pipeline switches disabled; it is not an older artifact. Prepared also
shortens demand waiting, so this comparison cannot isolate validation overlap.
Prefetch improved continuous client receipt by 10.2% versus that control, but
remained 2.8% slower than vanilla. Relocation was 13.2% slower than vanilla.
**The player's wait-time speedup goal remains unproven.** Three repeats with
overlapping ranges, fixed variant order, shared client hardware, and logical
CPU affinity limit generalization. Receipt excludes rendering, and server/client
clock origins differ.

Mechanism evidence and remaining causes:

- Client preparation median fell from about 2.64/2.68ms to 0.035/0.034ms in
  relocation/continuous prefetch. Reuse is working.
- Prefetch sent a median 113/80 jobs and used 103/59 ready cache entries.
  The `prefetch_applied` summary counter only counts joined completions with
  `source=prefetch`; cached completions are logged with `source=cache` and must
  be counted separately. Its zero median does not mean no early result applied.
- `density-use-analysis.json` counts successful remote/cache/prefetch density
  applications against all NOISE completions in the same measured windows.
  Prefetch applied 130–174 / 1,682 in relocation and 72–140 / 1,682 in continuous
  movement: 7.73–10.34% and 4.28–8.32%, respectively. This is a task fraction,
  not a fraction of CPU saved; block placement, surface, carvers, features,
  lighting, full conversion and sending still execute on the server.
- Prepared-only demand timed out a median 98/114 times; prefetch reduced this
  to 42/57. The fixed 100ms budget often discards direct work, causing local
  recomputation after remote preparation/transfer costs. Successful prefetch
  result RTT means were about 112–140ms; failed requests are excluded from that
  RTT metric, so it is not the latency distribution of all requests.
- Early validation makes final comparison very short, but sample preparation
  still consumes the constrained server CPU. These measurements support low
  useful coverage and deadline waste as further investigation targets; they
  do not identify an exact CPU attribution or a measured send-only duration.

The selected gate verifies Fabric Overworld with two owners, plus local sampler
equivalence for three vanilla dimensions and all three loader builds. It does
not verify new-hook runtime on native loaders or performance of arbitrary
custom generators. No additional runtime tests were part of that completed gate.

## Pipelined candidate: local gate only — 2026-09-29

After context reuse, early authoritative validation, generation-candidate
prefetch, demand wait bounds and the batch harness were all implemented,
`Run-PipelinedAssistGate.ps1 -Execute -LocalOnly` completed:

- Evidence: `test-artifacts/pipelined-assist-gate-20260929-154212-051/summary.json`.
- Four selected JUnit suites, 7 tests, 0 failures/errors/skips. Coverage includes
  reusable context equality/reset/mismatch, all three vanilla density volumes,
  prepared-sample mismatch, two-input comparison, queued/running cancellation
  capacity, bounded/deduplicated owner-interleaved candidate selection.
- Fabric test/build, Forge build and NeoForge build passed sequentially.
- Fabric JAR SHA-256:
  `D0B937582352C03D3D75F9C703BD56E6FF91295C572EA0CB4C7C28E29FC11B1F`.
- Installed Minecraft runtime injection, two-owner output equality and
  performance were **not run**. No speedup claim is made.

The first full gate (`pipelined-assist-gate-20260929-153944-440`) stopped before
compilation: the sandbox denied Gradle networking. Its retained old JAR hash is
not evidence of the new candidate. An escalated full-gate request was then
rejected by automatic approval review because SSH artifact/configuration
transfer to the dedicated other PC lacked explicit destination authorization.
Local-only execution was separately approved. The user subsequently explicitly
authorized transfer and batch execution in the dedicated remote fixture.
Runtime resumed from the successful local evidence with the exact SHA-256,
without repeating builds or unit tests, under
`test-artifacts/pipelined-assist-gate-20260929-154717-022/`.
Its two-owner correctness pair matched both required remotely applied chunks
and all 1,814 shared NOISE digests, with zero issues. The subsequent eight
performance scenarios and their final comparisons completed as recorded above.
Historical results below are unchanged.

## Parallel-worker candidate and client receipt — 2026-09-27

The unpublished scheduler now admits up to four jobs per owner after
successful work, capped by the client's configured 1–4 workers and the
existing global limit. It reserves one owner slot for direct demand, uses
two bounded server decoder threads, avoids duplicate direct-result cache
retention, and can report aggregated fallback reasons. Protocol `CURRENT=4`
and the trusted-raw opt-in gate did not change. The initial sequential gate
`test-artifacts/parallel-assist-gate-20260927-214041-171/summary.json`
passed the focused `WorkerRegistry263Test`, the Fabric installed-client
two-owner performance pair, and Forge/NeoForge builds. Its JAR hash was
`5711AA77C81DD8AF09BADF5B996A18A82581D30E5E00641725DD49EA6A39FC05`.

The six-scenario constrained-server batch for that hash is
`test-artifacts/csb-20260927-214639-537/summary.json`: all modes and CPU
tiers completed, with equal coordinates/tasks, zero comparison issues, and
safe cleanup. Its medians were:

| Server CPU affinity | Server 9×9 ready, vanilla / assisted | NOISE throughput, vanilla / assisted |
|---|---:|---:|
| Unrestricted | 1,513 / 1,663 ms | 96.23 / 83.50 tasks/s |
| Four logical processors | 4,511 / 4,108 ms | 53.52 / 50.36 tasks/s |
| Two logical processors | 8,966 / 8,615 ms | 30.43 / 28.36 tasks/s |

The constrained server-side region metric improved in the four- and
two-logical tiers, but overall NOISE throughput remained lower with assistance.
These runs predated client receipt instrumentation and cannot establish a
player-visible improvement.

The benchmark now timestamps the measured BEGIN message and each client chunk
load on the same monotonic client clock. The first instrumentation attempt
`validation-matrix-20260927-221327-360/` passed builds and both runtime
scenarios but its aggregate was `INCOMPLETE`: no receipt markers were captured.
After registering both Fabric chat and game-message callbacks, the selected
pair `validation-matrix-20260927-222019-262/` passed with complete 81/81
chunk coverage for both owners in all three repeats. At unrestricted server
CPU, median client receipt was 3,808 ms vanilla versus 4,476 ms assisted;
server region-ready was 1,569 versus 1,817 ms. Its JAR SHA-256 was
`58D83F28F7E496000DF37D604D7F98239A1BD2AB526A6F74AE430A7A51CE1474`.

The exact same final JAR passed the targeted two-logical-server pair
`test-artifacts/csb-20260927-222555-696/summary.json`, with full client
coverage and no comparison issues. Median server 9×9 readiness improved from
7,803 to 7,044 ms, but **median client receipt worsened from 15,403 to
16,571 ms**. Throughput was 30.15 versus 29.14 NOISE tasks/s. The client
receipt clock begins when each client receives the benchmark BEGIN message;
it includes chunk delivery and client processing but not the earlier server
command-to-message delay. Both clients still share one PC, and CPU affinity
does not reproduce all limits of a weak physical server. The player's wait
time goal has therefore not been demonstrated by this candidate.

The selected two-owner direct correctness pair at
`test-artifacts/validation-matrix-20260927-223824-411/` passed with this
same JAR: both required remotely applied chunks matched their independent
vanilla digests, all 1,803 shared NOISE digests matched, the owners' jobs
overlapped, and aggregation reported zero issues. This verifies the selected
Overworld path under concurrent owners; it is not a correctness matrix for
all dimensions or custom generators.

## Two-owner constrained-server experiment — 2026-09-27

The unpublished protocol-4 Fabric JAR SHA-256
`DEFF86143B2F59915AAE17B54FF3628485466348DB009924BD01B8BE4B746E4C`
completed six installed-client performance scenarios: vanilla and assisted
under unrestricted, four-logical-processor, and two-logical-processor server
Java affinity. The dedicated server ran on the second PC; both clients ran
on the first. Each scenario excluded one warm-up and measured three fresh
regions. Every measured repeat completed 1,682 NOISE tasks; all six scenarios
had the same measured coordinate sets, the same source/JAR identity, zero
timeouts/fallbacks, and safe cleanup. The CPU affinity masks recorded on both
routes were `68719476735`, `15`, and `3`, respectively.

| Server Java affinity | Both owners' 9x9 NOISE region ready, vanilla / assisted | Throughput, vanilla / assisted |
|---|---:|---:|
| Unrestricted | 1,595 / 1,551 ms | 98.49 / 87.49 tasks/s |
| Four logical processors | 3,889 / 4,487 ms | 51.50 / 51.12 tasks/s |
| Two logical processors | 7,937 / 9,088 ms | 29.29 / 28.38 tasks/s |

These are medians of three repeats. The region-ready measure runs from the
scripted relocation until both owners' 9-by-9 NOISE regions complete on the
server; it does **not** measure client chunk receipt or visible waiting.
Assistance did not improve median throughput in any CPU condition. At the
two constrained settings, its median server region-ready time was also
longer. CPU affinity to logical processors approximates a constrained server
but does not reproduce a weaker CPU, memory, disk, or separate client PCs.
This is descriptive evidence for the selected configuration, not a general
performance conclusion.

The assisted measured windows sent 211 jobs unrestricted, 187 with four
logical processors, and 232 with two, versus 5,046 completed NOISE tasks
per tier. Prediction and cross-window completion mean those counts cannot be
treated as an exact offloaded-work percentage. The recorded median RTTs were
116, 129, and 142 ms, while client density calculation was about 6 ms in
each tier. Median total remote-job time increased from 131 to 195 to 329 ms
as the server was constrained. The current trusted-raw coordinator admits
one in-flight job per owner (`RemoteJobCoordinator` constructs
`PendingTerrainJobRegistry` with a per-owner limit of 1). These observations
motivate instrumenting why eligible NOISE work remains local and where the
remote lifecycle waits before changing concurrency or transport policy; they
do not by themselves establish a causal bottleneck.

The preserved six-scenario run is `test-artifacts/csb-20260927-180639-953/`.
Its original `summary.json` reports `INCOMPLETE` because the comparator's new
CPU-condition expression had a PowerShell syntax error. The error was fixed
without rerunning Minecraft; all three read-only comparisons then reported
`COMPLETE`, zero issues, under each tier's `analysis-fixed/`. The verified
cross-tier audit is `summary-reanalysis.json`. Two prior attempts remain
separate: `constrained-server-benchmark-20260927-175901-862/` stopped at
sandbox-denied SSH preparation, and
`constrained-server-benchmark-20260927-175950-892/` failed client startup
because Windows rejected an overlong native-library path. Neither attempt
provides performance measurements.

## Unpublished 26.3 protocol-4 follow-up — 2026-09-27

The focused Fabric matrix at
`test-artifacts/validation-matrix-20260927-165014-519/` completed with ten
passes, zero failures and two intentional skips. Four selected JUnit suites
ran six tests. The custom `worldgen_assist:fixture` dimension pair matched
841/841 shared NOISE digests and its one remotely applied chunk; the
Overworld pair matched 941/941 and its one applied chunk. Analysis was
`COMPLETE`, zero issues; both scenarios in each pair reported safe cleanup.
The exact tested Fabric JAR SHA-256 was
`DEFF86143B2F59915AAE17B54FF3628485466348DB009924BD01B8BE4B746E4C`.

The selected Overworld performance pair completed three repeats per route:
median throughput was 70.32 tasks/s vanilla and 70.08 assisted. This is
still a lower assisted median and does not prove a speedup. The v4 result
uses four bytes per raw density instead of eight; 78 logged remote results
had median encoded payload 180,401 bytes and RTT 126.24 ms. Their median
client encode and server decode times were 4.85 and 1.71 ms. The earlier
alpha.4 event sample had different jobs, so cross-run latency differences
are descriptive rather than a controlled causal performance result.

Forge's selected fragment-assembler suite passed three tests, and its native
build passed. NeoForge's native build passed. The completed sequential gate
is `test-artifacts/focused-worldgen-gate-20260927-171829-732/summary.json`;
it reuses the exact finished Fabric matrix after a wrapper-only process-output
fix. Native custom-dimension runtime, independent custom density algorithms,
and client-unavailable custom noise registries are not verified by this run.
The published alpha.4 evidence and hashes below remain separate.

The isolated 26.3 client launcher omitted the official Minecraft JVM option
`-XX:StackShadowPages=32`. After it was added, an installed NeoForge two-owner
session passed with both distinct owners registered and each completing remote
work while both clients remained connected. The server and both clients exited
normally, with safe cleanup. Evidence:
`test-artifacts/port26.3-neo-two-owner-handshake-fix/result.json` and its logs.
The first corrected-launch attempt reached both handshakes but failed because
the harness read the first owner's registration a second time; owner-specific
waiting fixed that race. This result checks two owners, not an independent
vanilla comparison or overlapping in-flight jobs. The earlier no-MOD crash
control and all failed attempts remain retained as historical evidence.

A selected Fabric 26.3 Overworld performance pair then completed on the
corrected launcher, with three measured repeats per route. The final batch
reported 5 passed, 0 failed, 3 intentionally skipped, safe cleanup, and
analysis `COMPLETE / DESCRIPTIVE_ONLY` with zero evidence issues. Median
throughput was 69.47 tasks/s for normal generation versus 67.95 tasks/s with
assistance. This is one condition, not a speedup claim. Evidence:
`test-artifacts/validation-matrix-20260926-170015-863/` and the exact Fabric
JAR SHA-256 `0BB2B7647C473EDD94A2143CC9EBDD05EF02AD2F4402CB4407B3EDAAF5FA838A`.
That run used temporary settings-smoke diagnostics; reverting them restored the
earlier exact final Fabric distribution hash `0607E86B096CF92A0588BF2E57AA5D2789D2B5663B0F0C232F3B7D48504CBAB9`.
The performance evidence belongs to its own JAR and is descriptive for the
unchanged worldgen code, not an exact-byte benchmark of the final JAR.

Forge settings-menu development and installed-client smoke attempts did not
finish their screen sequence. The installed attempt joined a loopback server
and gained operator status, but produced no settings result before the client
closed; `test-artifacts/port26.3-forge-installed-settings-connected-fix/`
records `success=false`, `cleanup_safe=true`. Temporary smoke code was
reverted, restoring the previously verified Forge distribution hash. Forge
settings UI remains an explicit alpha.4 runtime verification gap.

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
A selected 26.3 Fabric batch run on the rebuilt JAR SHA-256
`0607E86B096CF92A0588BF2E57AA5D2789D2B5663B0F0C232F3B7D48504CBAB9`
passed an installed-client Overworld pair: one remote-applied chunk and all
941 shared NOISE digests matched, analysis `COMPLETE / PASS`, zero issues,
safe cleanup (`test-artifacts/validation-matrix-20260925-000313-104/`).
Its selected performance pair yielded three assisted measurements, but the
vanilla client exited at startup with native Windows code `0xC0000005` in
both the batch and one retry. Analysis remains incomplete, with no paired
performance conclusion; see
`test-artifacts/validation-matrix-20260925-000527-927/` and
`test-artifacts/port26.3-performance-vanilla-retry-batch/`.
The 26.3 Fabric settings-menu batch entry separately passed with four
screenshots, persistence, disclosure gate and safe cleanup at
`test-artifacts/port26.3-settings-batch-entry/`.
The current Forge and NeoForge installed-JAR Overworld pairs also passed.
NeoForge matched 1,628 shared NOISE digests and 43 remotely applied chunks;
Forge matched 816 shared digests and 18 remotely applied chunks. Each
comparison found zero differing shared or applied results. The official
installed loader profiles, exact candidate JARs, server/client logs and
comparison JSON are under `test-artifacts/port26.3-{neo,forge}-installed/`.
This is one-owner correctness evidence, not a performance or native
simultaneous-owner result. Offline authentication/Realms warnings remain.
A focused installed Forge session subsequently registered two distinct owners
and applied one remote NOISE result for each while both were connected. The
scenario result was successful with safe cleanup and exact Forge JAR SHA-256
`B9D3159F8D26BE7D8EDBF3F126E447BE6099CD64C85995CE0F622F9C937E4937`;
see `test-artifacts/port26.3-forge-two-owner-console/`. It was not a paired
vanilla digest or performance run. Three NeoForge two-owner attempts stopped
during Windows client resource loading with native `0xC0000005` (twice before
the first join, once before the second join); all cleaned up safely and do
not verify its simultaneous-owner route. Installed Forge settings smoke did
not complete its screen sequence, and its experimental code was reverted.
An authorized two-PC NeoForge retry then stopped before both owners could
join. A remote client control launch without WorldgenAssist reproduced
`0xC0000005` during resource loading on the same machine. The MOD JAR was
restored, with no owned client, temporary task or fixture listener remaining.
The two-PC and no-MOD control results are under
`test-artifacts/port26.3-neo-two-owner-remote{,-retry}/`; they separate a
client-runtime failure from any proven worldgen mismatch, but cannot verify
NeoForge simultaneous-owner behavior.
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
NeoForge's isolated settings-menu smoke passed with four screenshots,
persistence and a separate seed-disclosure gate, exiting normally. The Forge
menu-only client did not reach the title screen in two attempts, so its UI
result remains unverified. Shared 26.3 settings permission/revision tests
passed, as did Forge's focused fragment-reassembly/owner-cleanup tests.
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

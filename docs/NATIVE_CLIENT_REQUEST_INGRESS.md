# Native client request ingress (alpha.9-dev.2; affected verification passed)

## Dev2 native client ingress: completed affected batch (2026-10-09)

`native-client-request-gate-20261009-050308-626/summary.json` succeeds:
all five parent steps, exactly three affected client methods (failures/errors/
skips0), three sequential builds. Forge050342-770 and Neo053327-855 each pass
all25 ordinary/lifetime/saved-light steps; source manifests/JARs stayed frozen.
Implementation commit `0d4bfe0`, alpha.9-dev.2, shared14/native6.
Both conditions use remote assistance, SAME dev2 JAR, batchingFALSE, MAIN first,
weak E-server/view32/two clients on one stronger PC/warm1/three matched regions.
This measures the additional client ingress switch, not assistance ON versus OFF.

| Median of matched NETWORK/MAIN ratios | Forge | NeoForge |
| --- | ---: | ---: |
| FULL completion | -2.9218% (3/3 faster) | -0.1960% (2/3 faster) |
| Client receipt | -3.6770% | -1.2628% |
| Job RTT | -21.2801% | -17.2566% |
| Server CPU | +9.9923% | -3.4267% |
| Tick p95 | +3.1584% | -0.5808% |
| Result application mean | +12.6503% | -8.7101% |

Actual successful dispatches join server-issued job IDs for both owners in
all three repeats and select the intended MAIN/NETWORK path. Each repeat10658
tasks/3461 required receipts per owner/10082 feature and light bodies/peak2/
zero footprint conflicts. Both native loaders pass original post-measurement
End/Overworld callbacks, permanent old-session revocation and client reconnect.
All8 saved players Health20/survival/onGround/nonflying/deaths0/damage0; original
client-placed COBBLESTONE persists. Four own-light cases each compare50 interior/
162 halo chunks/10649600 values, BLOCK/SKY differences0. No probe ships.

RTT decreases consistently, but whole-generation gains are small and Forge CPU
increases. No substantial/universal gain, opposite-order native reproducibility,
server restart, continuous exploration or beta proof. Receipt excludes rendering;
queue means have different starting points. Native batching/client ingress stay
defaultFALSE, remote stays defaultOFF. All previous failed/UNRUN checkpoints
retain their historical identities. Current release preparation may package
this exact candidate as a new alpha; alpha.8/tag/assets remain immutable.

## Historical implementation and verification plan

The following UNRUN statements describe the pre-batch checkpoint. The closed
results above supersede that status without relabeling prior evidence.

## Hypothesis and scope

Closed dev1 comparison033001 passed both native ALL25 functional/saved checks,
but batching did not improve FULL (Forge+2.8553%, Neo+0.1877% median matched
ratios); request/reply means rose. See NATIVE_REQUEST_BATCHING.md for the exact
artifacts and limitations. Native client request and server result handlers
still waited on MAIN. Client MAIN includes rendering/chunk receipt activity.
The amount of waiting has not been isolated from wire/server queues, so this is
a hypothesis, not a measured attribution or claimed speedup.

New dev2 changes native client request admission only. Forge PayloadFlow.add
and explicit Neo client HandlerThread.NETWORK use the verified native APIs.
WorkerAccepted stays MAIN and captures the actual connection/dimension/private
worker context. Request/cancel handlers read that captured session; the existing
ClientWorldgenWorker/ClientWorkExecutor retain their bounds and reply revocation.
No private generation runs on Netty; it only submits bounded owned worker work.
Fallback queues the original MAIN handler and checks the exact receive connection
against the current client, so an obsolete connection cannot target a replacement.
Each batch still owns one worker lock and at most4 individually identified jobs.

ClientConnectionIngress retains one connection, with immutable session identities.
Suspend revokes the old identity permanently; successful MAIN resume publishes
a new identity. A delayed old close cannot revoke a replacement. Native-only
observation Mixins suspend before original respawn dispatch, revoke on inactive/
protocol change, and resume at original handleRespawn TAIL. Original packet
routing, Forge packetLogger, counters and PacketUtils remain. Fabric registers
neither new native Mixin, and its original adapters/worker/game code are unchanged.
One integer counts pending original respawns: two packets read before MAIN
runs keep the admission suspended until both original TAIL callbacks complete.
The same selected new lifecycle method covers this case without another suite.

Server result handlers and server-thread-only Forge fragment HashMap remain
original. Wire14/native6, codec, generation/authority/audit/peer/domain/epochs/
deadlines/current-input/final-write/fallback and seed policies are unchanged.
Remote stays defaultOFF. Both native JVM switches defaultFALSE:

- Server: `worldgen_assist.native.request_batching`
- Client: `worldgen_assist.native.client_request_ingress`

The new client switch must be explicitly enabled for the controlled experiment;
no default promotion before a demonstrated full-generation gain. Dev1 public
release does not exist; publicalpha8/native5/tag/assets remain immutable.

## Verified primary references

Inspect generated26.2 first, then exact26.3 and pinned Forge66.0.3/Neo26.3.0.13-beta:
Connection.channelRead0 checks shouldHandleMessage before genericsFtw; native
payload routing runs before vanilla MAIN handoff. channelInactive and
setupInboundProtocol revoke lifetime; ClientPacketListener.handleRespawn first
enforces MAIN then installs its new world/player before TAIL. Source/API entry
hashes are retained in test-artifacts/native-ingress-source-review-20261009/
findings.json. Forge add requires explicit setPacketHandled; Neo's two-argument
client event registration defaults MAIN even if a separate registrar changes
thread. New hooks observe only and use require1; native configs are client-only.

## Minimum affected batch (implementation and harness ready; ALL UNRUN)

Run once: `pwsh -NoProfile -File scripts/Run-NativeRequestBatchGate.ps1 -Execute -ClientRequestIngress`.
Parent parses affected scripts, freezes source/harness/
docs/artifacts, runs selected3 methods and Fabric assemble, then Forge/Neo
assemble and MAIN/NETWORK comparisons sequentially. Fresh XML names/counts and
retained hashes are required. Canonical `native_transport_experiment=client_request`
distinguishes this from prior batching; legacy native flag supplies only the
bounded three-repeat workload. Both server conditions explicitly batchFALSE.
Archive checks permit only seven exact new helper/Mixin class entries, native
client configs, three original native adapter stems and version metadata.
Every original generation/worker/server validation class must remain byte-equal.

Finish every production/harness/doc edit before ONE sequential batch:
one new captured-session lifecycle method and the two existing client admission
methods (three total), then three loader builds. No unchanged terrain/peer/
fragment/lighting unit suites. Require archive/source identity to keep every
original generation/validation/worker class unchanged except the exact listed
native adapters and newly added client helpers/Mixins/configs.

Forge then Neo SAME dev2 JAR, client ingress MAIN/NETWORK, BOTH remoteON,
server batchingFALSE, weak E-server/view32/two stronger-PC clients/warm1/three
matched coordinates, same complete/peer/provided-biome/cooperative/parallel
profile. Require actual per-owner path selection, original10658 tasks/3461
required receipts per owner/repeat, feature/light ownership and authority gates.
Post-measurement original dimension transition/return exercises the changed
respawn fence; original natural landing/mining/placing/reconnect and stopped
saved player/voxel/own-light gates stay strict. Extra transitions are unmeasured
lifetime checks, not End terrain parity or additional performance samples.
Both creative actors visit the End after all measurements/receipts, with original
server dimension callbacks and Health20/alive witnesses, then return to known
natural first measured ground. Both fresh3461 return receipts remain required.
NETWORK logs must show bind -> suspend -> End resume -> suspend -> Overworld
resume -> disconnect revoke -> reconnect bind. MAIN shows no captured-session
use. Measured server job IDs join each owner's actual successful client dispatch;
each repeat must use its intended path. Queue means start at different admission
times and cannot isolate MAIN waiting by subtraction. Metrics remain original.
The unshipped native observer adds require1 handleRespawn TAIL at the same
verified target, enabled only for this lifetime profile. Both actual clients
must acknowledge their original End transition before return. Its original
three shared gameplay-driver class files remain exact successful Fabric bytes;
no shipped probe or new floor/healing/physics pause is introduced.

No build/test/runtime/performance verdict yet. Native repeatability, server
restart, continuous exploration and broader stability/beta/full goal remain
unproved. Retain every failed artifact and freeze inputs during the selected batch.

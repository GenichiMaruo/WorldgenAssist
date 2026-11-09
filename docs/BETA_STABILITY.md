# Beta stability gate

The user resumed work on 2026-10-09: prioritize restart, continuous exploration
and multiplayer stability, then publish beta. Earlier paused checkpoints are
historical. Fabric has completed the gate; native stability and beta
publication are still pending as of 2026-11-10.

`scripts/Run-BetaStabilityGate.ps1 -Execute` runs one sequential affected batch:
Fabric0.19.5, Forge66.0.3, NeoForge26.3.0.13-beta / Minecraft26.3 / Java25.
It checks exact publishedalpha9 runtime hashes and unchanged dev2 production
inputs. No fresh MOD build/JUnit is needed for this test-only harness. Previous
generation/peer/audit/lifecycle proofs retain their original identities.

Authorized weak server100.103.102.109 uses only E:/WorldgenAssist for runtime,
world/temp/install/logs. Two clients share the stronger local PC; view32 on all.
Assistance explicitly uses cooperative/parallel/demand/peer. Both native
experimental switches retain defaultFALSE. After the original bounded workload
and survival mining/placing/reconnect, each loader runs:

1. Two-client60 real-minute creative flight through +/-768-block corridors using
   normal forward input and normal jump input to stabilize creative altitude,
   with original physics/movement packets. Console sets only
   initial creative position and final natural survival landing; no exploration
   teleports, artificial floors, healing or player tick pause. This is creative
   exploration, with survival before/after, not60 minutes of survival play.
2. Minute samples: travel, turns, loaded current chunk, live heartbeat, health,
   process private/working memory; server authoritative health/flight witnesses.
   Require >=30 travel blocks/minute, >=6000 total and >=3 turns/owner,
   >=10 loaded-current-chunk samples, no three-minute receipt stall, no unexpected
   disconnect; private memory <=16GiB/client, local host free physical memory
   >=8GiB, server private memory <=10GiB. Client committed/private memory differs
   from resident physical use. Capture owned GC.heap_info at start/midpoint/end
   without forced GC. These are bounded
   checks, not proof of no memory leaks or indefinite uptime.
3. Before the60-minute exploration, verify the previously client-placed cobblestone;
   disconnect individually, flush/save/exit0. Preserve first-runtime raw logs and
   saved input snapshots. Start a new PID with identical arguments/properties
   and SAME existing world, checking saved input hashes before startup.
4. Original clients rejoin; both client and server check loaded survival position,
   Health20/nonflying/onGround and existing cobblestone BEFORE any new block write.
   Repeat original mining/placing/reconnect, then run the60-minute exploration.
   Restore natural survival, check ground/health/placed block and repeat those
   interactions again after exploration, followed by clean final save/stop.
5. Existing offline readers check both pre-restart/final players and placed
   voxels, plus final original saved-light recomputation:50 interior/162 halo
   chunks/10,649,600 values. Repeated controls clear exact earlier result/ack
   files, preventing stale evidence reuse. All source inputs stay frozen.

Failures retain their original identity/logs. Beta scope remains verified
trusted-participant environments, opt-in assistance/raw seed disclosure;
adversarial collusion, arbitrary MOD/custom-generator compatibility, WAN gains,
indefinite uptime and latest absent-MOD speed comparison are not established.

Preparation failures remain separate:143930 stopped at missing offline compile
classpath dependency;144000 stopped on unshipped flight initialization;144748
passed33 exploration samples but was intentionally controller-aborted after a
saved-path array concatenation defect was confirmed. All are failed/unfinished
gates, never60-minute or restart proof. Corrections add the exact existing MOD
compile dependency, original jump input for altitude, and explicit path-expression
parentheses. Production generation remains unchanged.

153003 passed32 exploration samples; at33 one client's private memory8.073GiB
exceeded the predeclared8GiB ceiling. This remains a FAILED gate. Actual resident
use4.882GiB, other client4.730GiB, and host free physical memory26.4GiB/total63.8GiB
show no physical exhaustion; no game crash/movement/receipt/health failure was
observed. Revised prospective criteria add real physical headroom,16GiB private
cap and owned heap observations, without reclassifying prior failure. Restart is
now checked first to fail early on harness defects rather than waste an hour.

161606 restarted the saved world but failed raw properties-byte equality before
client rejoin. Primary26.2 and exact26.3 dedicated/Settings.store59-61 invoke
Java Properties.store, and DedicatedServerSettings.forceSave19-20 calls it;
startup rewrites the timestamp comment. Prospective comparison retains both raw
files/hashes but requires all noncomment setting lines and launch arguments to
match exactly. The earlier gate remains FAILED; no exploration/reload proof.

## Continuing from the completed Fabric run

The 162356-447 batch ended unsuccessfully, with one complete Fabric result.
Both players finished its 60 minute samples and same-world restart. All four
saved-player observations passed, and the final lighting comparison found no
block or sky differences across 10,649,600 values. The result is retained
under its original date, harness and runtime hash. The subsequent Forge run
passed restart/rejoin and the repeated survival interaction. At flight startup
owner A reported Y=319.884, health 20, flying and a loaded chunk, with zero
horizontal travel after 45.2 seconds. Owner B had already moved. No NeoForge
run was attempted in that batch.

The affected Forge profile had `pauseOnLostFocus:true`. In generated 26.2
`Minecraft.pauseIfInactive` (1441–1447) and exact 26.3 (1454–1460), an
unfocused window can open the pause menu after 500 ms. Native key bindings
also check whether the in-game input context is active. That can explain
the asymmetric failure; the old sample did not record the active screen.
The owned beta profiles now disable automatic pausing. From their saved
standing positions, both players take off with normal jump input. The
controller waits for each client to hover above the ground with a clear input
screen, and for the server to confirm both players are airborne, flying and
healthy, before positioning them at the start of the flight. That preparation
time does not count towards the 60 minutes. The simulation, movement, health, and
one-hour requirements stay in force. Only the two missing loaders need
fresh runs: `scripts/Run-BetaStabilityGate.ps1 -Execute -ReuseVerifiedFabric`.

November preparation attempts 013709 and 014019 did not start a game. The
first found missing local Gradle cache entries; the restored classpath matches
the original 72 hashes. The second compiled the offline readers and verified
the retained Fabric evidence, then found a wrong screen accessor in the input
driver. Exact 26.3 Minecraft uses `gui.screen()` (e.g. Minecraft.tick, 1895).

In run 014119-334, Forge completed the restart, survival interactions and 43
minutes of exploration. At sample 43 the players had traveled 28,025 and
28,020 blocks. Before sample 44 both probes recorded the vanilla PauseScreen,
health 20, flight at Y=330 and a loaded chunk. The run ended there. The source
manifest also changed during cleanup after the runtime failure. The partial
run supplies no 60-minute pass.

The client-only driver now closes an incidental vanilla pause menu through
the original Screen.onClose (26.2:202, 26.3:208) during owned creative flight.
It records each dismissal, limits repeat dismissals and keeps the original
movement and elapsed-time checks. Both new test profiles start with the
Minecraft music volume set to zero.

Run 024010-200 completed seven Forge samples. Its eighth sample found an old
heartbeat. The unpublished client report contained a Windows AccessDeniedException
while atomically replacing beta-state.json at the time the controller read it.
The recorded player was healthy, flying and in a loaded chunk. The client then
stopped producing reports; the server and the other client continued until
cleanup. This run failed. The controller now permits Windows to replace a file
while its previous snapshot is open. A sharing violation retries only the
atomic move of the same timestamped bytes. The batch exercises that exact
overlap before launching Minecraft.

## Verified unshipped input targets

Primary generated26.2 LocalPlayer.aiStep729 calls input.tick748; creative flight
uses abilities.flying/onUpdateAbilities796-810. onUpdateAbilities363 sends the
original ServerboundPlayerAbilitiesPacket. Exact26.3 aiStep746/input.tick766,
flight814-828/abilities380; Options.keyUp643 is forward KeyMapping. Unshipped
Minecraft.tick TAIL and LocalPlayer.aiStep HEAD each require1; the latter holds
only that virtual key before original input.tick, without canceling original
physics/routing. Shared survival input class bytes are copied unchanged from
the successful Fabric proof. No probe is included in release archives.

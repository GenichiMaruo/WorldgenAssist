# Multi-PC fixture testing — 2026-09-09

## Authority and isolation

The user explicitly authorized multi-PC tests on 2026-09-09 and provided SSH
access as `gen1c@100.117.255.71`. Keep the fixed public-seed gate in place.
Do not copy private worlds, change the global Java installation, open firewall
ports, or expose an offline-mode Minecraft listener on LAN/Tailscale addresses.
Performance judgment is still deferred.

Remote host observed: DESKTOP-869S2KH, Windows 11 Home, Intel i9-10980XE,
approximately 128 GiB RAM. Its existing Java is 24.0.1. A task-owned Java 25.0.4
runtime was created from the local pinned JDK with jlink (all JMODs, stripped
debug/header/man files), then copied into:

`C:/Users/gen1c/AppData/Local/Temp/WorldgenAssist-20260909`

The system Java/PATH is unchanged. Both the Minecraft server listener and the
local forwarded listener bind **127.0.0.1:25585**. SSH authentication protects
the tunnel over Tailscale. This is a real two-PC calculation/packet test, but
not a direct-network latency benchmark or an authenticated Minecraft-account test.

## Fixed distribution

The remote server uses the installed mod JAR, not Gradle source outputs.
Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2 are pinned.
The launcher was obtained from the [official Fabric server download page](https://fabricmc.net/use/server/):
`https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.3/1.1.2/server/jar`.
Launcher SHA-256: `301F83AAC36B23F2BC64CC58560EDF98533CFAA30E53AF002BA950C75F4100B4`.
Its `--initSettings` run downloaded the fixed server/dependencies without starting
a playable world. Initial missing properties/EULA warnings are bootstrap output,
not successful runtime evidence. Existing user EULA acceptance is copied later;
the scripts do not invent or automatically accept an agreement.

## Run

Run the final local Phase A-D before transfer; stop editing source/config until
both runtime cases and digest comparison finish. These commands require SSH/SCP,
the prepared remote root, and local JDK 25.0.4/cache paths in the scripts.

```powershell
.\scripts\Run-MultiPcFixture.ps1 -Mode assisted
.\scripts\Run-MultiPcFixture.ps1 -Mode vanilla
.\scripts\Compare-SeededLeafFixture.ps1 -AssistedRoot 'test-artifacts/multipc-assisted-<timestamp>' -VanillaRoot 'test-artifacts/multipc-vanilla-<timestamp>'
.\scripts\Test-RemoteFixtureWatchdog.ps1
```

The runner copies the mod/API JARs and verifies the mod SHA-256 remotely before
launch. `Remote-FixtureServer.ps1` serializes runs with an exclusive file lock,
uses a new world name each time, records installed-JAR hashes/environment/logs,
and stops the server after the fixed route. It never deletes or resets a ledger.
The client runs on the primary PC's dev classpath through an owned SSH tunnel.
The comparison requires identical source manifests and equality of all applied
coordinates; it also rejects any shared-coordinate mismatch.

Preserve failed run directories. Review both client and server logs. A source
classpath client plus installed-JAR server does not prove installed-JAR client
startup. Forced client-process cleanup is not graceful JVM-shutdown evidence.
The remote helper has bounded startup/join/workload waits and only kills the Java
process it started on failure. A hidden independent watchdog checks the exact
Java PID, start time and executable path; it terminates that process if its
controller disappears or 600 seconds elapse. Prepared write targets reject reparse
points. If SSH is lost, reconnect and verify that this
task's server has exited before any retry; do not kill arbitrary Java processes.

The watchdog test uses a fresh disposable world and intentionally exits its
controller with code 97 after server readiness. It keeps the outer SSH session
alive for five seconds so session teardown cannot mask the watchdog's own action.
Acceptance requires the watchdog's exact owner-loss stop marker, zero dedicated
Java processes and zero fixture listeners. It is not a graceful save/shutdown
test, a physical-network interruption test, or a test of the full 600-second
deadline. Failed evidence must remain; see the latest report for actual results.
The 2026-09-10 fresh `multipc-watchdog-20260910-141417-403` probe passes both
exit-97 and the watchdog stop marker/zero-process/zero-listener checks. Earlier
failed harness runs are retained in the latest report.

## Installed client and targeted retention check

The 2026-09-10 preparation downloaded the pinned official Fabric client profile
from `https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.3/profile/json` to
`test-artifacts/installed-client-prep-20260910/fabric-profile.json`.
`New-InstalledFixtureClient.ps1` uses the original checksum-verified Minecraft
26.2 client JAR and cached official libraries, Windows x64 natives and the
verified asset index. Fabric Loader/ASM/Mixin versions match the resolved build.
It copies distribution mod/API JARs into a fresh client `mods` directory.
No build/classes, source-set directories, dev launch injector, or development
classpath groups are used; `fabric.development=false` is explicit.

```powershell
.\scripts\Run-MultiPcFixture.ps1 -Mode assisted -ClientRuntime installed
.\scripts\Run-MultiPcFixture.ps1 -Mode vanilla -ClientRuntime installed
```

The installed runtime additionally runs `jcmd <owned-client-pid> GC.class_histogram`
after server disconnect/stop. It requires zero live instances of the exact
fixture job/claim/transcript/entry/result/worker-attempt classes. The histogram,
classpath SHA-256 list, copied-mod hashes and Java argument file are retained.
Enum singletons and long-lived manager/worker objects are expected; this is not
a complete reference-graph or raw-array/string retention proof, nor a test of
every cancellation/reconnect lifecycle. Do not claim otherwise.

The runner then requests closure of only its owned client process's main window,
requires natural exit code 0 and Minecraft's `Stopping!` marker. Generated
Minecraft 26.2 `runTick` checks `Window.shouldClose`, calls `stop`, and
`exitWorldAndClose` logs that marker before resource cleanup. Forced kill remains
failure cleanup only; a forced termination cannot satisfy the graceful check.

## Local adversarial modes (development client)

For an actual two-PC transport disconnect while a claim is held:

```powershell
.\scripts\Run-MultiPcFixture.ps1 -Mode link-drop
```

This uses the development client's bounded withholding fault, records the exact
held ID, then terminates only the locally owned SSH forwarding process. The
independent server-control SSH session stays alive. The exact sent ID/coordinate
must become DISCONNECTED and no remote result may be installed. Compare its
fallback digest with the same-snapshot vanilla case using the fallback comparator.
It does not disable Tailscale, the NIC, or other connections. Installed clients
intentionally reject this test mode because their development faults are disabled.

`Run-SeededLeafRuntimeFixture.ps1` additionally accepts `timeout`, `malformed`,
`pending-reload`, `pending-disconnect`, and `second-player`. Fault injection is
gated by **both** the client public-fixture setting and Fabric development mode.
It withholds one bounded claim without sleeping on the render thread, or returns
a valid claim with an invalid DEFLATE body. Normal installed clients do not enable
these faults. The tester must observe a currently pending held job (recorded as
`held-job.json`) before triggering lifecycle actions, not reuse an old log marker.
Second-player mode has two isolated dev profiles and raises max-players only in
that disposable fixture; it checks dispatch remains suspended at player count 2.

For local fallback correctness, run a fresh vanilla case with the same source
snapshot, then use (substitute the actual evidence directories):

```powershell
.\scripts\Compare-SeededLeafFallback.ps1 -FallbackRoots @('test-artifacts/runtime-timeout-<timestamp>', 'test-artifacts/runtime-malformed-<timestamp>', 'test-artifacts/runtime-pending-reload-<timestamp>', 'test-artifacts/runtime-pending-disconnect-<timestamp>') -VanillaRoot 'test-artifacts/runtime-vanilla-<timestamp>'
```

This requires successful run summaries, identical source manifests, no remote
installation in fallback cases, every TIMED_OUT/RESULT_REJECTED/STALE_CONTEXT/
DISCONNECTED coordinate present and equal in both worlds, and no mismatch among
shared coordinates. Missing evidence fails, not skips. It writes a new
`fallback-digest-comparison.json` per case and refuses to overwrite one.
Timeout/reload cancellation assertions use the exact held claim ID. A legitimate
synchronous-wait CANCELLED result is not timeout proof: the timeout mode observes
another pending claim within a bounded deadline. Preserve unsuccessful runs.

## Evidence and remaining limits

Consult `TEST_RESULTS_LATEST.md` for actual executions, not this procedure.
2026-09-10 after SSH restoration: cross-PC assisted and vanilla runs pass,
4/4 installed coordinates and 1,002 shared NOISE digests match. The installed
server's assisted output also matches the primary PC's independent baseline.
Never infer pass from process exit alone or quote digest-enabled timings as a
speedup. These experiments do not resolve candidate-seed inference, Sybil/account
abuse, or a justified confidentiality disclosure bound. Temporary remote data is
retained for audit; cleanup requires stopping this task's processes first and
targeting only the exact dedicated directory above.

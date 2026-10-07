# Installation — Minecraft 26.3 and 26.2

[English](INSTALL.md) | [日本語](INSTALL.ja.md) · [Overview](../README.md)

## Minecraft 26.3 alpha.8

Use Minecraft **26.3**, Java **25** (tested 25.0.4), and Fabric **0.19.5**
with Fabric API **0.161.0+26.3**, Forge **66.0.3**, or NeoForge **26.3.0.13-beta**.
Download your loader's JAR from [alpha.8](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.8%2Bmc26.3).
Replace older WorldgenAssist JARs on the server and all participating clients.
**Protocol14/native channel5 requires updating both sides together.**

| Loader | Install this JAR |
| --- | --- |
| Fabric | `worldgen-assist-0.1.0-alpha.8+mc26.3.jar` |
| Forge | `worldgen-assist-forge-0.1.0-alpha.8+mc26.3.jar` |
| NeoForge | `worldgen-assist-neoforge-0.1.0-alpha.8+mc26.3.jar` |

Source JARs are for developers. Remote assistance remains off by default.
Use trusted participants and a backed-up test world. Raw-seed disclosure is
explicit; seed secrecy and protection against colluding clients are unresolved.
Complete terrain targets eligible vanilla Overworld contexts; arbitrary
generators are unsupported. Read the [release verification](releases/v0.1.0-alpha.8+mc26.3-verification.md).

### Alpha.8 measured profile

Before starting the server, set these environment values in PowerShell.
This opts into seed disclosure and experimental parallel FEATURES:

```powershell
$env:WORLDGEN_ASSIST_REMOTE='true'
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE='trusted_raw'
$env:WORLDGEN_ASSIST_NOISE_BACKEND='cooperative'
$env:WORLDGEN_ASSIST_FEATURE_BACKEND='parallel'
$env:WORLDGEN_ASSIST_REMOTE_WORK_KIND='complete'
$env:WORLDGEN_ASSIST_REMOTE_ALLOW_COMPLETE_TERRAIN='true'
$env:WORLDGEN_ASSIST_REMOTE_COMPLETE_VERIFICATION='peer'
$env:WORLDGEN_ASSIST_REMOTE_AUTHORITATIVE_BIOMES='true'
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT='64'
$env:WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW='32'
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES='128'
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION='true'
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS='8'
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS='30000'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH='true'
$env:WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION='true'
$env:WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT='true'
$env:WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS='100'
$env:WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY='false'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD='0'
$env:WORLDGEN_ASSIST_REMOTE_PREPARE_SECTIONS='false'
$env:WORLDGEN_ASSIST_REMOTE_ALLOW_REMOTE_BIOMES='false'
```

Set server `view-distance=32`, `simulation-distance=3`, and client render
distance32. Measured heap sizes: server6GB, each client4GB. For EACH client,
enable participation in Options → WorldgenAssist, set environment
`WORLDGEN_ASSIST_CLIENT_JOB_WINDOW='32'`, and use JVM arguments
`-Dworldgen_assist.client.worker_threads=4 -Dworldgen_assist.client.reuse_context=true`.
Reconnect after changing participation. Saved operator server policy applies on
restart; JVM overrides take priority. Remove experimental overrides to restore
ordinary defaults.

Two clients sharing a stronger PC assisted a separate weaker server with no
CPU/JVM processor restrictions. Both comparison conditions used identical
cooperative NOISE and parallel FEATURES. Across both execution orders/six paired
ratios reusing three coordinates: FULL **20.93% shorter**, receipt **21.20%
shorter**, CPU **19.63% lower**, all six improve; tick p95 **8.95% higher**.
Receipt is not rendered completion. These are this profile's results, not an
alpha.7 comparison or a general guarantee.

Generation/decoration/saved structures/light match across all three loaders'
bounded fixtures. Basic peaceful survival landing/mining/placing/reconnect and
stopped saved state/light pass on **Fabric only**. Native ordinary gameplay,
server restart, continuous exploration/combat/hazards and long sessions remain
unverified; this release remains alpha. First two full server audits and later
private1/8 (server) or1/64 with eligible independent peer/full agreement remain.
Final domain/owner/epochs/current biome/shaping checks retain server authority.
Pending demand waits are asynchronous100ms/adaptive max200ms; task deadline30s.

## Historical Minecraft 26.3 alpha.7

Use Java25(tested25.0.4) and Minecraft26.3. Choose Fabric0.19.5 + API0.161.0+26.3,
Forge66.0.3 or NeoForge26.3.0.13-beta. Download the matching distribution JAR
from [alpha.7](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.7%2Bmc26.3)
and replace the old mod on the server and every participating client together.
**Protocol12 is incompatible with previous releases.** Never install source JARs.

| Loader | JAR |
| --- | --- |
| Fabric | `worldgen-assist-0.1.0-alpha.7+mc26.3.jar` |
| Forge | `worldgen-assist-forge-0.1.0-alpha.7+mc26.3.jar` |
| NeoForge | `worldgen-assist-neoforge-0.1.0-alpha.7+mc26.3.jar` |

Remote assistance is off by default. In-game Options→WorldgenAssist controls
participation/reconnect and operator server policy/restart. Raw-seed disclosure
is a separate choice. Complete terrain is limited to eligible stock Overworld
contexts; existing intermediate paths remain,with prior dimension evidence
retaining its original versions. Arbitrary generators/client-unavailable
registries are unsupported. Use trusted test worlds; seed secrecy and peer
collusion resistance are unresolved. [Verification](releases/v0.1.0-alpha.7+mc26.3-verification.md)
includes fresh three-loader Overworld correctness,not a full dimension matrix.

### Measured complete-terrain profile

For the explicit view32 profile,start the server from a shell with:

```powershell
$env:WORLDGEN_ASSIST_REMOTE='true'
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE='trusted_raw'
$env:WORLDGEN_ASSIST_NOISE_BACKEND='cooperative'
$env:WORLDGEN_ASSIST_REMOTE_WORK_KIND='complete'
$env:WORLDGEN_ASSIST_REMOTE_ALLOW_COMPLETE_TERRAIN='true'
$env:WORLDGEN_ASSIST_REMOTE_COMPLETE_VERIFICATION='peer'
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT='64'
$env:WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW='32'
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES='128'
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION='false'
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS='8'
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS='30000'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH='true'
$env:WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION='true'
$env:WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT='true'
$env:WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS='100'
$env:WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY='false'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD='0'
```

Set `view-distance=32` in server.properties and client render distance32.
On each client,add JVM argument `-Dworldgen_assist.client.worker_threads=4` and
set environment `WORLDGEN_ASSIST_CLIENT_JOB_WINDOW='32'` before launching,reconnect with
participation enabled. The two measured clients shared one stronger PC.
Both modes used two owned terrain workers/16 queued per worker;no CPU/JVM
limits on the weaker server. Same-region paired FULL-2.24%,receipt-1.74%,
CPU-20.53%,all3 improved;tick p95+0.32%(two higher). Not beta/a general guarantee.
Receipt excludes rendering. Job deadline30s; demand wait asynchronous100ms,
adaptive max200ms,then local fallback. Server verification is default1/8 after
two initial full audits; optional trusted peer mode uses entire agreement and
private1/64 draws when a suitable distinct audited peer is available. A peer can
verify an assigned chunk outside its own view; no additional world ticket.
No peer/raced admission retains or forces server audit. This is not hostile
client proof. Removing these shell overrides restores the ordinary profile.

## Historical Minecraft 26.3 alpha.6

Use Minecraft Java Edition **26.3**, Java **25** (tested with 25.0.4), and one
loader: Fabric Loader **0.19.5** with Fabric API **0.161.0+26.3**, Forge
**66.0.3**, or NeoForge **26.3.0.13-beta**. Download the corresponding
`0.1.0-alpha.6+mc26.3` distribution JAR from
[the alpha.6 release](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.6%2Bmc26.3).
Install the same loader-specific JAR on the server and each participating
client. Fabric API is required only for Fabric. Do not place a `-sources.jar`
in `mods` or mix JARs for different loaders or Minecraft versions.

Remove the previous Worldgen Assist JAR from each `mods` folder. Alpha.6 uses
protocol 6 and cannot share assistance with alpha.5 (protocol 4); upgrade the
server and all participating clients together. Choose exactly one:

| Loader | Installable JAR |
| --- | --- |
| Fabric | `worldgen-assist-0.1.0-alpha.6+mc26.3.jar` |
| Forge | `worldgen-assist-forge-0.1.0-alpha.6+mc26.3.jar` |
| NeoForge | `worldgen-assist-neoforge-0.1.0-alpha.6+mc26.3.jar` |

The latest installed runtime/correctness and performance checks cover Fabric
Overworld with two clients. The latest Forge/NeoForge candidates were built,
but their changed runtime paths were not rerun. Earlier vanilla-dimension and
compatible custom-dimension results retain their original candidate identities.
See the attached verification record for exact scope.

Use a disposable world and trusted participants. Remote assistance is off by
default; enabling it requires a separate explicit raw-seed disclosure choice.
26.3 does not include the 26.2 public-seed transcript fixture. The supported
terrain scope is eligible new chunks in vanilla Overworld, Nether, and End.
Compatible additional dimensions and explicit vanilla-noise wrappers are
experimental; arbitrary custom generators and server-only noise definitions
are unsupported. Hostile clients and a speedup are not supported claims.
The in-game menu is **Options → WorldgenAssist**; client
participation applies after reconnect and saved server policy after restart.

### Measured experimental profile

Installing alpha.6 keeps remote assistance off and ordinary generation defaults
intact. The measured profile is an explicit experiment, not the default or a
speed guarantee. Its Fabric distance32 medians improved FULL1.88%, receipt1.44%,
CPU6.62%; completion improved two of three repeats and tick p95 worsened3.64%.
Server and clients were on separate PCs, with server affinity/JVM2 and two
clients sharing the other PC. The server scheduler was cooperative in both
comparison modes, isolating assistance from that scheduler choice.

For a trusted disposable world, set these environment variables in the shell
that starts the **server**, then restart it. The raw-seed choice discloses the
world seed to participants; secret eight-point sampling does not authenticate
all malicious results. Command-line properties override environment variables.

```powershell
$env:WORLDGEN_ASSIST_REMOTE='true'
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE='trusted_raw'
$env:WORLDGEN_ASSIST_NOISE_BACKEND='cooperative'
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT='32'
$env:WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW='16'
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES='128'
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION='true'
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS='8'
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS='30000'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH='true'
$env:WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION='true'
$env:WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT='true'
$env:WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS='100'
$env:WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY='false'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD='0'
```

The job lifetime is30s; a demanded chunk waits asynchronously at most200ms,
then falls back locally. No server/network thread blocks for that wait.
On **each client**, enable participation in Options → WorldgenAssist and add
`-Dworldgen_assist.client.job_window=16` and
`-Dworldgen_assist.client.reuse_context=true` to its launcher JVM arguments,
then reconnect/restart as appropriate. Client computation defaults to two
threads. This does not share another player's work or bypass owner limits.
For the measured large region set server `view-distance=32` and both client
render distances to32; measurement used `simulation-distance=3`, server6GB
heap and each client4GB. CPU restriction was a test condition, not a required
installation step. Lower distances can use the same opt-in profile.
See [alpha.6 evidence](releases/v0.1.0-alpha.6+mc26.3-verification.md).

## Earlier Minecraft 26.2 Fabric alpha.3

This alpha is for disposable test worlds with trusted participants. It is not
ready for ordinary public servers. Installing it does not automatically enable
remote generation, and measured speedup is not established.

1. Back up any existing installation and use a separate test profile/world.
2. Prepare Minecraft Java Edition **26.2**, Java **25** (tested: 25.0.4),
   Fabric Loader **0.19.3**, and Fabric API **0.156.0+26.2**.
3. Download `worldgen-assist-0.1.0-alpha.3+mc26.2.jar` from
   [GitHub Releases](https://github.com/GenichiMaruo/WorldgenAssist/releases).
   The `-sources.jar` is developer source code, not an installable mod.
4. Put Fabric API and the same Worldgen Assist mod JAR in the client and server
   `mods` folders. Move any previous Worldgen Assist JAR out of `mods`; do not
   load two versions together. Do not replace Minecraft's own JAR.
5. Keep remote assistance disabled until you have read the mode restrictions in
   the [advanced guide](ADVANCED_GUIDE.md). A successful launch alone does not
   mean client assistance is active.

The trusted raw-seed mode explicitly discloses the world seed to the client.
The separate public-transcript fixture is restricted to public seed `8675309`.
Neither is a private-seed-safe mode for an ordinary survival server. Do not
enable both routes together. For the controlled fixture, follow the
[fixture protocol and procedure](SEEDED_LEAF_FIXTURE_PROTOCOL.md).

Alpha.3 lets participating clients assist their own eligible new terrain in
vanilla Overworld, Nether and End concurrently. Install alpha.3 on the server
and every participating client; do not mix versions. The global default is
eight jobs, at most one per player. A busy or disconnected owner falls back
locally. Open **Options → WorldgenAssist** to change client participation or,
as an operator, server policy. Client participation takes effect on reconnect;
saved server policy takes effect after server restart. Larger groups,
mod-added dimensions, custom generators and hostile participants are not
guaranteed.

## Verify a download

Compare the SHA-256 with the matching filename in the release's `SHA256SUMS.txt`:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\worldgen-assist-0.1.0-alpha.6+mc26.3.jar'
```

The checksums detect mismatched files; they are not a separate publisher signature.
Minecraft, Java, Fabric Loader, and Fabric API are not bundled in this release.
Other Minecraft versions and mod/datapack combinations are not verified.

## Reporting a problem

Include the mod/Minecraft/Java/Fabric versions, selected mode, reproduction steps,
and relevant client/server errors. Remove access tokens, private addresses, player
information and world seeds from logs before sharing them. Do not upload your
private world. State whether the problem reproduces in a fresh test profile.

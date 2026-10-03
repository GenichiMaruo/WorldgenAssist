# Installation — Minecraft 26.3 and 26.2

[English](INSTALL.md) | [日本語](INSTALL.ja.md) · [Overview](../README.md)

## Minecraft 26.3 alpha.6

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

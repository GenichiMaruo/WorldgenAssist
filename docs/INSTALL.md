# Installation — Minecraft 26.2

[English](INSTALL.md) | [日本語](INSTALL.ja.md) · [Overview](../README.md)

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
Get-FileHash -Algorithm SHA256 -LiteralPath '.\worldgen-assist-0.1.0-alpha.3+mc26.2.jar'
```

The checksums detect mismatched files; they are not a separate publisher signature.
Minecraft, Java, Fabric Loader, and Fabric API are not bundled in this release.
Other Minecraft versions and mod/datapack combinations are not verified.

## Reporting a problem

Include the mod/Minecraft/Java/Fabric versions, selected mode, reproduction steps,
and relevant client/server errors. Remove access tokens, private addresses, player
information and world seeds from logs before sharing them. Do not upload your
private world. State whether the problem reproduces in a fresh test profile.

# Worldgen Assist

[English](README.md) | [日本語](README.ja.md)

Worldgen Assist is a Fabric mod that lets a player's PC help the server calculate
new terrain. Its goal is to reduce server world-generation work while keeping
the server in control of the world. It is currently an experimental alpha;
measured performance improvements have not yet been established.

When Minecraft needs a new eligible Overworld chunk, the mod can ask that same
player's client to calculate the terrain density field. The server receives the
result, checks it, and continues normal vanilla generation itself. Ores,
structures, fluids, block placement, saving, and all gameplay-relevant world
state remain server-authoritative.

## Alpha status

Alpha version: **0.1.0-alpha.2+mc26.2**. This release adds concurrent
assistance by each player's own client. Direct work is
assigned only within that worker's view; overlapping views choose one owner.
The global default is eight jobs, with at most one per owner. See
[multiplayer development and verification](docs/MULTIPLAYER_SUPPORT.md).
It passes 246 tests in 52 suites, two-owner concurrent runtime checks and an
owner-disconnect test.

This alpha is for controlled trials, not everyday use on public servers.
Remote assistance is disabled by default, and no speedup is promised. The
implemented remote paths are limited to explicitly trusted participants,
a freshly generated vanilla-compatible Overworld, and eligible new terrain.
It is not for hostile public servers, arbitrary datapacks, other dimensions, or
sharing one player's compute work with other players.

Download [alpha.2 for Minecraft 26.2](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.2%2Bmc26.2)
(Git tag `v0.1.0-alpha.2+mc26.2`). Use the same version on the server and every
participating client. The older alpha.1 release remains available separately.

## What is implemented

- Client and server share the terrain-density calculation rather than inventing
  a different generator.
- The server applies bounded input/result checks and optional independent,
  bit-exact validation before accepting a result.
- Timeouts, disconnects, malformed results, reloads, and unavailable workers
  fall back to normal local server generation.
- The legacy trusted-raw path supports lossless RAW/DEFLATE compression, a
  bounded context-aware cache, and player-owned prediction. Cache and
  prediction are not part of the public fixture described below.

The release build passes **246 tests in 52 suites** on Java 25.0.4.
Two installed client profiles and a server on a second PC pass simultaneous
assistance in both routes: 588 public-fixture and 382 trusted-raw shared NOISE
digests match independent normal generation. An owner-disconnect probe using
the existing development-only result withholding on one client also passes,
with 406 shared digests matching. Timeouts and offline-profile/Realms errors
remain in the test logs. This evidence establishes functional behavior within the stated
scope; it does not prove a speedup, private-seed confidentiality, or
public-server safety. See the English [test results](docs/TEST_RESULTS_LATEST.md).

## Important security distinction

There are two separate experimental routes:

| Route | Scope | Security meaning |
| --- | --- | --- |
| Trusted raw-seed mode | A controlled, disposable world with an explicitly trusted client | The server sends the raw seed. Do not use it where the seed must remain private. |
| Public transcript fixture | Only the already public seed `8675309` | A narrow test route; it does not provide private-seed confidentiality. |

Omitting a raw-seed field alone is not a confidentiality guarantee: deterministic
transcripts may still support candidate-seed checking. Private-world remote
generation remains a research and security gate, not an option to enable for
normal servers.

## Install safely

Use the same **mod JAR** on both the
Fabric client and the Fabric server. Do not put the `-sources.jar` in either
`mods` folder. Keep remote assistance off unless you understand the documented
research constraints, and test only on a disposable world.

| Requirement | Version |
| --- | --- |
| Minecraft Java Edition | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Java | 25 (tested with 25.0.4) |

Minecraft, Fabric Loader, Fabric API, and Java must be present wherever the mod
runs. The mod does not make a vanilla client into a worker; install it on both
sides for the experimental assisted route.

## Learn more

- [Installation](docs/INSTALL.md) / [日本語の導入方法](docs/INSTALL.ja.md).
- [Advanced guide (English)](docs/ADVANCED_GUIDE.md) — setup, configuration,
  diagnostics, benchmarking, and research-only switches.
- [Architecture (English)](docs/ARCHITECTURE.md) and [verified worldgen pipeline (English)](docs/WORLDGEN_PIPELINE.md).
- [Seed-confidentiality notes (English)](docs/SEED_CONFIDENTIALITY.md) and
  [public-fixture protocol (English)](docs/SEEDED_LEAF_FIXTURE_PROTOCOL.md).
- [Test evidence (English)](docs/TEST_RESULTS_LATEST.md).
- [Versions and Minecraft branches / バージョン管理](docs/VERSIONING.md).

Most detailed project documentation is currently English. This README does not
promise complete documentation translation or in-game localization.

## FAQ

**Will this make my server faster?** Maybe not. Performance judgment is still
deferred until controlled benchmarks support it.

**Can I use it on a private survival world?** Not for remote assistance. The
trusted raw-seed route reveals the seed to the worker, and the public fixture is
only for seed `8675309`.

**Does a client control terrain or features?** Clients return intermediate
calculations; the server applies results and completes generation. Sampled
validation is not a guarantee against all malicious results.

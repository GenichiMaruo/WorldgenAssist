# Worldgen Assist

[English](README.md) | [日本語](README.ja.md)

Worldgen Assist is a Fabric mod that lets a player's PC help the server calculate
new terrain. Its goal is to reduce server world-generation work while keeping
the server in control of the world. It is currently an experimental alpha;
measured performance improvements have not yet been established.

When Minecraft needs a new eligible Overworld, Nether or End chunk, the mod can ask that same
player's client to calculate the terrain density field. The server receives the
result, checks it, and continues normal vanilla generation itself. Ores,
structures, fluids, block placement, saving, and all gameplay-relevant world
state remain server-authoritative.

## Alpha status

Version **0.1.0-alpha.3+mc26.2** adds player-owned assistance across vanilla
Overworld, Nether and End, an in-game settings screen, and a one-command
correctness/performance matrix. In the twelve measured conditions, assisted
throughput was lower than vanilla; see the [performance report](docs/ALPHA3_PERFORMANCE.md)
and [verification details](docs/TEST_RESULTS_LATEST.md). Open vanilla
**Options → WorldgenAssist**. Client
participation applies on reconnect; saved server policy applies after a server
restart and is editable remotely only by a server administrator. Full-seed
disclosure is still a separate explicit choice.

Each player's own client can assist concurrently. Direct work is
assigned only within that worker's view; overlapping views choose one owner.
The global default is eight jobs, with at most one per owner. See
[multiplayer development and verification](docs/MULTIPLAYER_SUPPORT.md).

This alpha is for controlled trials, not everyday use on public servers.
Remote assistance is disabled by default, and no speedup is promised. The
implemented remote paths are limited to explicitly trusted participants,
fresh vanilla-compatible Overworld, Nether and End worlds, and eligible new terrain.
It is not for hostile public servers, arbitrary datapacks, mod-added dimensions, or
sharing one player's compute work with other players.

Download [alpha.3 for Minecraft 26.2](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.3%2Bmc26.2)
(Git tag `v0.1.0-alpha.3+mc26.2`). Use the same version on the server and every
participating client. Older releases remain available separately.

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

The alpha.3 checkpoint ran **264 tests in 58 suites** on Java 25.0.4. Later
settings fixes passed focused tests and a build on the final JAR; the full suite
was not rerun. Three selected all-dimension correctness pairs matched **4,284**
shared NOISE digests. Offline-profile/Realms errors and an intermittent vanilla
server shutdown appear in the test evidence. These checks do not prove a
speedup, private-seed confidentiality, or public-server safety. See the
[test results](docs/TEST_RESULTS_LATEST.md).

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

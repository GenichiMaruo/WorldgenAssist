# Worldgen Assist

![Worldgen Assist icon](src/main/resources/icon.png)

[English](README.md) | [日本語](README.ja.md)

Worldgen Assist is a Fabric, Forge, and NeoForge mod that lets a player's PC help the server calculate
new terrain. Its goal is to reduce server world-generation work while keeping
the server in control of the world. The beta targets dedicated servers with
trusted players who opt in to assistance.

When Minecraft needs a new eligible Overworld, Nether or End chunk, the mod can ask that same
player's client to calculate the terrain density field. The server receives the
result, checks it, and continues normal vanilla generation itself. Ores,
structures, fluids, block placement, saving, and all gameplay-relevant world
state remain server-authoritative.

## Current beta

**0.1.0-beta.1+mc26.3** targets Minecraft 26.3 and Java 25. Install the JAR for
your loader on the server and on each participating client. Shared protocol 14
and native channel 6 apply. Remote assistance and both experimental native
transport options are off by default. Complete terrain delegation requires an
eligible vanilla Overworld and discloses the seed to participating clients.

In the earlier Fabric benchmark, a weak dedicated server (i7-7700K) served two
clients on one stronger PC (i7-12700K) at view distance 32. With the same mod
and generation settings, enabling assistance **shortened time to FULL by a
median 20.93%** across six paired runs using three regions in both execution
orders. Required chunks reached clients 21.20% sooner, server CPU time fell
19.63%, and 95th-percentile server tick time rose 8.95%. Receipt ends before
rendering. This benchmark compared assistance off and on within Fabric.

Beta verification covers same-world restart, two players exploring in creative
flight for 60 minutes, survival interactions before and after, and saved player,
block and lighting data. The verification record lists each loader's evidence.
[Installation](docs/INSTALL.md) · [Beta release notes](docs/releases/v0.1.0-beta.1+mc26.3.md) ·
[Beta verification](docs/releases/v0.1.0-beta.1+mc26.3-verification.md).

In the separate alpha.9 comparison, the optional client request path shortened
job round trips by 21.28% on Forge and 17.26% on NeoForge with assistance
enabled in both conditions. FULL times fell 2.92% and 0.20%, respectively.
Forge server CPU time rose 9.99%. [Alpha.9 release notes](docs/releases/v0.1.0-alpha.9+mc26.3.md) ·
[Alpha.9 verification](docs/releases/v0.1.0-alpha.9+mc26.3-verification.md).

### Historical alpha.6

Version **0.1.0-alpha.6+mc26.3** targets Minecraft **26.3** with separate
Fabric, Forge, and NeoForge JARs. Use the JAR matching your loader on both the
server and every participating client. The server remains authoritative and
remote assistance is disabled by default. See the [26.3 installation guide](docs/INSTALL.md)
and [alpha.6 verification record](docs/releases/v0.1.0-alpha.6+mc26.3-verification.md).
The public-seed transcript fixture has not been ported to 26.3; only the
explicit trusted raw-seed route is available there. Alpha.6 adds smaller
terrain-grid/surface intermediates, earlier generation hints, stale-result
retirement, bounded larger job windows, Fabric client network admission and an
optional cooperative server scheduler. Protocol **6** requires matching
alpha.6 server/client JARs; do not mix alpha.5 (protocol 4) with alpha.6.
An explicit asynchronous/wide/cooperative profile on a separate two-CPU server
with two clients at distance32 reduced median FULL completion **1.88%**, receipt
**1.44%** and server CPU time **6.62%**. Completion improved in two of three
repeats; tick p95 worsened. Defaults are unchanged and this is not a general
speed guarantee or an alpha.5-versus-alpha.6 controlled comparison.
See [release notes](docs/releases/v0.1.0-alpha.6+mc26.3.md) and the
[measured experimental profile](docs/INSTALL.md#measured-experimental-profile).

The following alpha.3 results describe the earlier **Minecraft 26.2 Fabric** release.

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
The global default is eight jobs. Alpha.6 starts at one in-flight job per owner
and adapts up to four within the advertised client capacity; client computation
defaults to two threads. See
[multiplayer development and verification](docs/MULTIPLAYER_SUPPORT.md).

Those alpha releases were intended for controlled trials with trusted players.
Remote assistance is disabled by default, and no speedup is promised. The
implemented remote paths are limited to explicitly trusted participants,
fresh vanilla-compatible Overworld, Nether and End worlds, and eligible new terrain.
Alpha.5 experimentally admits additional dimensions using compatible vanilla
noise settings available on both sides, and custom wrappers explicitly exposing
the same vanilla noise delegate. A prior Fabric custom-dimension fixture passed;
arbitrary custom algorithms, client-unavailable noise registries, hostile public
servers and sharing another player's compute work remain unsupported.

The older release is [alpha.3 for Minecraft 26.2](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.3%2Bmc26.2)
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

For the current 26.3 release, use the [loader-specific installation guide](docs/INSTALL.md).
The table below applies to the earlier 26.2 Fabric release.

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

**Will this make my server faster?** At view distance 32, all six matched
Fabric runs on the weak test server completed generation sooner with assistance.
The result depends on your hardware, network and configuration. Use the settings
in the [installation guide](docs/INSTALL.md) to reproduce that comparison.

**Can I keep a survival world's seed private?** Leave assistance off if the
seed must stay private. The 26.3 assistance profile sends it to participating
clients. The older 26.2 public fixture covers only seed `8675309`.

**Does a client control terrain or features?** Clients return intermediate
calculations; the server applies results and completes generation. Sampled
validation is not a guarantee against all malicious results.
